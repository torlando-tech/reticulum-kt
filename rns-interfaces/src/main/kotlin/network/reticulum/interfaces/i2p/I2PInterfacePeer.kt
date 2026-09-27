package network.reticulum.interfaces.i2p

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.DeframerFeed
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.streamDeframer
import network.reticulum.interfaces.framing.streamFramer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * I2P peer interface — handles a single I2P connection.
 *
 * Port of Python's I2PInterfacePeer. Can operate in two modes:
 *
 * 1. **Outbound (initiator)**: Connects to a remote I2P destination via SAM client tunnel.
 *    Sets up tunnel, connects through localhost to SAM, handles reconnection.
 *
 * 2. **Inbound (spawned)**: Wraps an already-connected socket from an incoming I2P connection.
 *    Does not reconnect — when the connection drops, it's torn down.
 *
 * Both modes use HDLC or KISS framing over the TCP connection.
 *
 * Includes a **read watchdog** that monitors tunnel health:
 * - Sends keepalive HDLC frames if no data sent recently
 * - Marks tunnel as STALE if no data received for too long
 * - Closes the connection if the tunnel is completely unresponsive
 *
 * @param name Interface name.
 * @param parentInterface The I2PInterface that owns this peer.
 * @param targetI2pDest Remote I2P destination (for outbound connections, null for inbound).
 * @param connectedSocket Pre-connected socket (for inbound connections, null for outbound).
 * @param useKissFraming Use KISS framing instead of HDLC.
 * @param maxReconnectTries Max reconnection attempts (null = unlimited for outbound).
 * @param samAddress SAM API address (for outbound tunnel setup).
 */
class I2PInterfacePeer(
    name: String,
    private val parentI2P: I2PInterface,
    private val targetI2pDest: String? = null,
    private var connectedSocket: Socket? = null,
    private val useKissFraming: Boolean = false,
    private val maxReconnectTries: Int? = null,
    private val samAddress: InetSocketAddress = I2PSamClient.getSamAddress(),
) : Interface(name) {

    companion object {
        // Reconnection
        const val RECONNECT_WAIT_MS = 15_000L // 15 seconds (matches Python)

        // Watchdog timing — tuned for I2P's high latency
        const val I2P_USER_TIMEOUT_S = 45
        const val I2P_PROBE_AFTER_S = 10
        const val I2P_PROBE_INTERVAL_S = 9
        const val I2P_PROBES = 5
        val I2P_READ_TIMEOUT_S = (I2P_PROBE_INTERVAL_S * I2P_PROBES + I2P_PROBE_AFTER_S) * 2 // ~140s

        // Tunnel states
        const val TUNNEL_STATE_INIT = 0
        const val TUNNEL_STATE_ACTIVE = 1
        const val TUNNEL_STATE_STALE = 2
    }

    override val bitrate: Int = I2PInterface.BITRATE_GUESS
    override val hwMtu: Int = I2PInterface.HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = false

    // IFAC: same network as the parent. Credentials and the 16-byte tag size derive
    // in the Interface base from the delegated netname/netkey (identical bytes).
    override val ifacNetname: String? get() = parentI2P.ifacNetname
    override val ifacNetkey: String? get() = parentI2P.ifacNetkey

    /** Whether this peer initiated the connection (outbound). */
    val isInitiator: Boolean = targetI2pDest != null

    /** Current tunnel health state. */
    @Volatile var tunnelState: Int = TUNNEL_STATE_INIT
        private set

    private var socket: Socket? = connectedSocket
    // Serializes concurrent writes to the socket. Was an AtomicBoolean check-then-set with a
    // 1ms Thread.sleep busy-spin — racy (two threads can pass the check before either sets
    // the flag, interleaving frame bytes on the socket). A ReentrantLock gives real mutual
    // exclusion and wakes immediately. Same fix as TCPClientInterface.
    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    // Two HDLC flags — the watchdog keepalive. Allocated once, not per tick.
    private val keepaliveFrame = byteArrayOf(HDLC.FLAG, HDLC.FLAG)
    private var samStreamConnection: SamConnection? = null

    // Watchdog timing
    @Volatile private var lastRead: Long = 0
    @Volatile private var lastWrite: Long = 0

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var watchdogJob: Job? = null
    private var tunnelJob: Job? = null
    private var samSessionConnection: SamConnection? = null

    // Framer and deframer are selected once from useKissFraming; the deframer bounds
    // (KISS decode cap at HW_MTU, HDLC escaped-buffer cap 2*HW_MTU+16) derive from hwMtu.
    private val framer: (ByteArray) -> ByteArray = streamFramer(useKissFraming)
    private val deframer: DeframerFeed =
        streamDeframer(useKissFraming, hwMtu, ifacSize = { ifacSize }) { data ->
        processIncoming(data)
    }

    init {
        parentInterface = parentI2P
    }

    override fun start() {
        if (connectedSocket != null) {
            // Inbound connection — socket already connected
            socket = connectedSocket
            setOnline(true)

            configureSocket(socket!!)
            startReadLoop()
        } else if (targetI2pDest != null) {
            // Outbound connection — need to set up I2P tunnel first
            tunnelJob = ioScope.launch {
                tunnelAndConnect()
            }
        }
    }

    /**
     * Set up the I2P tunnel and connect.
     *
     * For outbound peers, this:
     * 1. Creates a SAM session and stream connection
     * 2. Uses the SAM stream's underlying socket for data
     * 3. Starts the read loop and watchdog
     */
    private suspend fun tunnelAndConnect() {
        val dest = targetI2pDest ?: return

        while (!detached.get() && ioScope.isActive) {
            try {
                log("Setting up I2P tunnel, this may take a while...")

                val sessionName = I2PSamClient.generateSessionId()

                // Create SAM session with transient destination
                // Must keep sessionConn alive — closing it destroys the SAM session
                samSessionConnection = withContext(Dispatchers.IO) {
                    I2PSamClient.createSession(
                        sessionName = sessionName,
                        samAddress = samAddress,
                    )
                }

                // Connect stream to remote destination
                val streamConn = withContext(Dispatchers.IO) {
                    I2PSamClient.streamConnect(
                        sessionName = sessionName,
                        destination = dest,
                        samAddress = samAddress,
                    )
                }

                samStreamConnection = streamConn
                socket = streamConn.socket
                configureSocket(streamConn.socket)

                setOnline(true)
                tunnelState = TUNNEL_STATE_ACTIVE

                // Request tunnel synthesis for this I2P connection
                if (!useKissFraming) {
                    wantsTunnel = true
                }

                log("I2P tunnel established to ${dest.take(16)}...")

                startReadLoop()

                // Wait for read loop to finish (disconnection)
                readJob?.join()

                // If we get here, connection was lost
                if (!detached.get()) {
                    log("Connection lost, will retry in ${RECONNECT_WAIT_MS / 1000}s...")
                    delay(RECONNECT_WAIT_MS)
                }

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log("Tunnel setup error: ${e.message}")
                log("Check that I2P is installed and running, and that SAM is enabled")
                if (!detached.get()) {
                    delay(RECONNECT_WAIT_MS)
                }
            }
        }
    }

    /**
     * Configure socket options for I2P's high-latency characteristics.
     */
    private fun configureSocket(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 0 // blocking reads
        } catch (e: Exception) {
            // Socket may already be in a bad state
        }
    }

    private fun startReadLoop() {
        val sock = socket ?: return
        val input = try {
            sock.getInputStream()
        } catch (e: Exception) {
            // Neither the read loop nor the watchdog starts from here, so returning alone
            // strands the peer: for an inbound connection start() has already called
            // setOnline(true), leaving it registered and online with nothing that will
            // ever close it. Transport can then select it as a route and the traffic
            // disappears. Report it, and take the peer down the way every other
            // unrecoverable path in this class does.
            log("Could not open input stream, no read loop started: ${e.javaClass.name}: ${e.message}")
            teardown()
            return
        }

        lastRead = System.currentTimeMillis()
        lastWrite = System.currentTimeMillis()

        readJob = ioScope.launch {
            readLoop(input)
        }

        // Start watchdog for tunnel health monitoring
        watchdogJob?.cancel()
        watchdogJob = ioScope.launch {
            readWatchdog(sock)
        }
    }

    /**
     * Read loop — receives framed data from the I2P tunnel.
     *
     * Matches Python's I2PInterfacePeer.read_loop, including the
     * inline HDLC/KISS deframing.
     */
    private suspend fun readLoop(input: InputStream) {
        val buffer = ByteArray(4096)

        try {
            while (ioScope.isActive && online.value && !detached.get()) {
                val bytesRead = input.read(buffer)

                if (bytesRead > 0) {
                    lastRead = System.currentTimeMillis()
                    deframer(buffer, 0, bytesRead)
                } else if (bytesRead == -1) {
                    // Connection closed
                    setOnline(false)
                    break
                }
            }
        } catch (e: CancellationException) {
            // Normal shutdown
        } catch (e: IOException) {
            if (!detached.get()) {
                setOnline(false)
            }
        } catch (e: Exception) {
            // python TCPInterface.py:426-434 / I2PInterface.py read_loop catch Exception:
            // go offline so the teardown/reconnect path below runs.
            if (!detached.get()) {
                log("Read loop error: ${e.javaClass.name}: ${e.message}")
                setOnline(false)
            }
        }

        // Connection lost
        watchdogJob?.cancel()

        if (!isInitiator && !detached.get()) {
            // Inbound peer — just tear down
            log("Socket closed for remote peer")
            teardown()
        }
        // Outbound peers will reconnect via tunnelAndConnect loop
    }

    /**
     * Read watchdog — monitors tunnel health and sends keepalives.
     *
     * Matches Python's I2PInterfacePeer.read_watchdog:
     * - If no data received for 2×PROBE_AFTER: mark tunnel as STALE
     * - If no data sent for 1×PROBE_AFTER: send HDLC keepalive (empty flags)
     * - If no data received for READ_TIMEOUT: force close socket
     */
    private suspend fun readWatchdog(sock: Socket) {
        try {
            while (ioScope.isActive && online.value && !detached.get()) {
                delay(1000)

                val now = System.currentTimeMillis()
                val timeSinceRead = (now - lastRead) / 1000
                val timeSinceWrite = (now - lastWrite) / 1000

                // Check for stale tunnel
                if (timeSinceRead > I2P_PROBE_AFTER_S * 2) {
                    if (tunnelState != TUNNEL_STATE_STALE) {
                        log("I2P tunnel became unresponsive")
                    }
                    tunnelState = TUNNEL_STATE_STALE
                } else {
                    tunnelState = TUNNEL_STATE_ACTIVE
                }

                // Send keepalive if we haven't written recently
                if (timeSinceWrite > I2P_PROBE_AFTER_S) {
                    try {
                        if (!sock.isClosed && sock.isConnected) {
                            // Send empty HDLC flags as keepalive — under writeLock so it can
                            // never be spliced into a frame processOutgoing is mid-write.
                            writeLock.lock()
                            try {
                                val out = sock.getOutputStream()
                                out.write(keepaliveFrame)
                                out.flush()
                            } finally {
                                writeLock.unlock()
                            }
                            lastWrite = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        log("Keepalive send error: ${e.message}")
                        closeSocket(sock)
                        break
                    }
                }

                // Force close if completely unresponsive
                if (timeSinceRead > I2P_READ_TIMEOUT_S) {
                    log("I2P socket unresponsive for ${timeSinceRead}s, restarting...")
                    closeSocket(sock)
                    break
                }
            }
        } catch (e: CancellationException) {
            // Normal shutdown
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.value || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        val sock = socket ?: throw IllegalStateException("Socket is null")

        // lockInterruptibly() keeps the old busy-spin's InterruptedException path, so a
        // teardown can still interrupt a contended writer (see TCPClientInterface).
        writeLock.lockInterruptibly()
        try {
            val framedData = framer(data)

            sock.getOutputStream().write(framedData)
            sock.getOutputStream().flush()
            lastWrite = System.currentTimeMillis()

            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            log("Write error: ${e.message}")
            teardown()
            throw e
        } finally {
            writeLock.unlock()
        }
    }

    private fun teardown() {
        if (isInitiator && !detached.get()) {
            log("Unrecoverable error, tearing down")
        }
        setOnline(false)
        closeSocket(socket)
        parentI2P.peerDisconnected(this)
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        setOnline(false)

        readJob?.cancel()
        watchdogJob?.cancel()
        tunnelJob?.cancel()
        ioScope.cancel()

        closeSocket(socket)
        samStreamConnection?.close()
        samSessionConnection?.close()

        parentI2P.peerDisconnected(this)
    }

    private fun closeSocket(sock: Socket?) {
        try {
            sock?.close()
        } catch (_: Exception) {}
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String {
        val dest = targetI2pDest?.take(16)?.let { " → $it..." } ?: ""
        return "I2PInterfacePeer[$name$dest]"
    }
}
