package network.reticulum.interfaces.tcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.common.RnsLog
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.DeframerFeed
import network.reticulum.interfaces.framing.streamDeframer
import network.reticulum.interfaces.framing.streamFramer
import network.reticulum.interfaces.util.TcpKeepalive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP server interface for Reticulum.
 *
 * Listens for incoming TCP connections and exchanges packets using
 * HDLC or KISS framing. Spawns child interfaces for each client.
 */
class TCPServerInterface(
    name: String,
    private val bindAddress: String = "0.0.0.0",
    private val bindPort: Int,
    private val useKissFraming: Boolean = false,
    private val maxClients: Int = 64,
    // IFAC (Interface Access Code) parameters for network isolation
    override val ifacNetname: String? = null,
    override val ifacNetkey: String? = null,
    // Configured bitrate in bps. A value below MINIMUM_BITRATE is ignored and
    // the interface keeps its class BITRATE_GUESS (python Reticulum.py:765-768).
    bitrate: Int? = null,
    // Pinned link MTU in bytes. Non-null puts the interface in FIXED_MTU mode
    // (AUTOCONFIGURE_MTU off), so the negotiated link MTU settles at this value
    // (python TCPInterface FIXED_MTU; the bridge's fixed_mtu config knob).
    internal val fixedMtuBytes: Int? = null,
    // Configured IFAC size in BITS. Resolved to bytes via the python floor at
    // Reticulum.py:719-723 (>= IFAC_MIN_SIZE*8 -> //8, else DEFAULT_IFAC_SIZE).
    private val ifacSizeBits: Int? = null,
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 262144
        /** Default IFAC tag length in bytes for packet/IP media (python TCPInterface.py:454). */
        const val DEFAULT_IFAC_SIZE = 16

        /** Pause after a failed accept() so an EMFILE condition cannot spin the loop. */
        const val ACCEPT_ERROR_DELAY_MS = 250L
    }

    // IFAC credentials are derived in the Interface base from ifacNetname/ifacNetkey;
    // only the tag size is interface-specific.
    // python Reticulum.py:719-723: a configured ifac_size (bits) >=
    // IFAC_MIN_SIZE*8 (==8) divides by 8; otherwise it floors back to
    // DEFAULT_IFAC_SIZE.
    override val defaultIfacSize: Int
        get() = DEFAULT_IFAC_SIZE

    override val configuredIfacSizeBits: Int?
        get() = ifacSizeBits

    // python Reticulum.py:765-768 — a configured bitrate below MINIMUM_BITRATE
    // is ignored; the interface keeps its class BITRATE_GUESS.
    override val bitrate: Int =
        if (bitrate != null && bitrate >= Reticulum.MINIMUM_BITRATE) bitrate else BITRATE_GUESS
    // FIXED_MTU mode pins HW_MTU to the configured value; default mode applies the
    // bitrate→HW_MTU optimisation python runs per-interface at config load
    // (Reticulum.interface_post_init → interface.optimise_mtu(), Reticulum.py:860;
    // Interface.optimise_mtu, Interface.py:198-221). The 10 Mbps BITRATE_GUESS maps
    // to 8192. Falls back to the class HW_MTU only for the lowest bitrate tier
    // (optimise_mtu → None). AUTOCONFIGURE_MTU=True for TCP, so the gate always holds.
    override val hwMtu: Int = fixedMtuBytes ?: (Interface.optimiseMtu(this.bitrate.toLong()) ?: HW_MTU)
    override val autoconfigureMtu: Boolean = (fixedMtuBytes == null)
    override val fixedMtu: Boolean = (fixedMtuBytes != null)
    override val supportsLinkMtuDiscovery: Boolean = true

    // Discovery support
    override val supportsDiscovery: Boolean = true
    override val discoveryInterfaceType: String = "TCPServerInterface"
    override fun getDiscoveryData(): Map<Int, Any> = mapOf(
        network.reticulum.discovery.DiscoveryConstants.REACHABLE_ON to bindAddress,
        network.reticulum.discovery.DiscoveryConstants.PORT to bindPort,
    )
    // Python-exact semantics (RNS/Interfaces/TCPInterface.py): the
    // parent TCPServerInterface sets `IN=True` (canReceive) and starts
    // with `OUT=False`, but `Reticulum.interface_post_init` flips OUT
    // to True at config load (Reticulum.py:770-771). It therefore
    // participates in Transport.outbound iteration. Its own
    // `process_outgoing` is a `pass` (TCPInterface.py:627-628); the
    // spawned child interfaces are what actually write to the wire,
    // each registered with Transport as it is accepted (see
    // `handleClient`) so Transport.transmit addresses them directly.
    //
    // Setting canSend=false here would exclude the parent from
    // Transport.interfaces iteration entirely, breaking any code that
    // looks up an interface by hash (link_table resolution, announce
    // path broadcast, etc.) — see #46 follow-up where the send-inert
    // variant regressed multi-hop resource transfers.
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    /**
     * Called when a new client connects, after the spawned interface has been registered
     * with Transport and started.
     *
     * A notification hook, not the registration mechanism. It used to be the latter, which
     * made routing to a connected client depend on the caller knowing to wire this up; the
     * class now registers the child itself. Registering it again from here is harmless —
     * `Transport.registerInterface` is idempotent, as the reference's `add_interface` is —
     * but it is no longer needed.
     */
    var onClientConnected: ((Interface) -> Unit)? = null

    /**
     * Called when a client disconnects. Use to deregister the spawned interface from Transport.
     */
    var onClientDisconnected: ((Interface) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val clientCounter = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<TCPServerClientInterface>()

    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    init {
        spawnedInterfaces = java.util.concurrent.CopyOnWriteArrayList()
    }

    override fun start() {
        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress(bindAddress, bindPort))
            setOnline(true)
            log("Listening on $bindAddress:$bindPort")

            acceptJob = ioScope.launch {
                acceptLoop()
            }
        } catch (e: Exception) {
            log("Failed to start server: ${e.message}")
            throw e
        }
    }

    private suspend fun acceptLoop() {
        while (online.value && !detached.get()) {
            try {
                val server = serverSocket ?: break
                val clientSocket = server.accept()

                if (clients.size >= maxClients) {
                    log("Max clients ($maxClients) reached, rejecting connection")
                    clientSocket.close()
                    continue
                }

                val clientId = clientCounter.incrementAndGet()
                val clientName = "$name/client-$clientId"

                val clientInterface = TCPServerClientInterface(
                    name = clientName,
                    socket = clientSocket,
                    parentServer = this,
                    useKissFraming = useKissFraming
                )
                // Mirror Python TCPInterface.py:619 — spawned_interface.mode = self.mode.
                // The spawned child inherits the parent's effective mode so that
                // Transport sees the configured mode when a packet arrives (the
                // `receiving_interface` for a client-sourced packet is the spawned
                // child, not the parent server).
                //
                // Contract: snapshot-at-spawn, backfill-on-change. This line
                // copies the parent's effective mode as a concrete value at
                // spawn time; later mutation of the parent's `modeOverride`
                // does NOT automatically propagate to already-spawned children
                // (each child has its own `modeOverride` field). Callers that
                // need runtime mode changes to affect existing children must
                // iterate `spawnedInterfaces` and back-fill — the conformance
                // bridge does this in `wire_set_interface_mode`.
                clientInterface.modeOverride = this.modeOverride ?: this.mode

                clients.add(clientInterface)
                spawnedInterfaces?.add(clientInterface)

                clientInterface.onPacketReceived = { data, iface ->
                    // Forward received packets to Transport via the parent's callback.
                    // Do NOT fan out to other connected clients — matches Python's
                    // TCPServerInterface.incoming_connection semantics where spawned
                    // children's process_outgoing is a pass. Packets reach peers through
                    // Transport's routing decisions (outbound(packet)), not via raw
                    // rebroadcast at the TCP layer. See #46 for why the fan-out broke
                    // path-layer invariants (cached-announce overwrite, PR leakage,
                    // announce mode filtering bypass, double delivery races).
                    onPacketReceived?.invoke(data, iface)
                }

                // Register the child with Transport, and do it BEFORE starting it.
                //
                // This is what makes a connected client reachable at all. Transport routes
                // to interfaces it knows about, and this child holds the only socket to
                // that peer; the parent's processOutgoing is deliberately a pass, per the
                // comment above, precisely because delivery is supposed to go through
                // Transport's routing to the children. Without this the server can receive
                // from a client forever and never send to it, and the deregistration in
                // closeClient below is undoing something that never happened. The reference
                // registers here too: `RNS.Transport.add_interface(spawned_interface)` in
                // `TCPInterface.py`'s `incoming_connection`.
                //
                // Before start(), not after, for the reason LocalServerInterface documents
                // at the same point: a probe that connects and closes at once can otherwise
                // have its read loop hit EOF and detach — deregistering nothing, because
                // registration has not happened yet — after which this line would add an
                // already-detached interface that nothing ever removes. Registering first
                // means the eventual detach can actually take it out again. See upstream #74.
                Transport.registerInterface(clientInterface.toRef())

                clientInterface.start()
                onClientConnected?.invoke(clientInterface)

                val clientAddr = clientSocket.remoteSocketAddress
                log("Client connected: $clientAddr ($clientName)")

            } catch (e: CancellationException) {
                // Normal cancellation, exit loop
                break
            } catch (e: SocketException) {
                // Keep listening through transient accept failures, but pause so an
                // EMFILE condition cannot spin this loop with a log line per iteration.
                if (detached.get() || serverSocket?.isClosed != false) break
                log("Accept error: ${e.message}")
                delay(ACCEPT_ERROR_DELAY_MS)
            } catch (e: Exception) {
                if (detached.get() || serverSocket?.isClosed != false) break
                log("Error accepting connection: ${e.message}")
                delay(ACCEPT_ERROR_DELAY_MS)
            }
        }
    }

    /**
     * Called by client interface when it disconnects.
     */
    internal fun clientDisconnected(client: TCPServerClientInterface) {
        clients.remove(client)
        spawnedInterfaces?.remove(client)
        Transport.deregisterInterface(client.toRef())
        onClientDisconnected?.invoke(client)
        log("Client disconnected: ${client.name}")
    }

    /**
     * Parent-level outbound is a no-op — mirrors Python
     * `TCPServerInterface.process_outgoing` (RNS/Interfaces/TCPInterface.py:627-628,
     * which is `pass`). Each spawned child is its own Transport-registered
     * interface — registered as it is accepted, the same as the reference does
     * in `incoming_connection` — so Transport.transmit addresses the correct
     * child directly. Fanning out here would duplicate every
     * Transport broadcast: once via this parent, again via each child that
     * Transport iterates independently — producing the path-layer invariant
     * violations that #46 set out to fix (cached-announce overwrite, PR
     * leakage, announce mode-filter bypass, double delivery races).
     */
    override fun processOutgoing(data: ByteArray) {
        // Intentionally empty.
    }

    /**
     * Get number of connected clients.
     */
    fun clientCount(): Int = clients.size

    /**
     * Get list of connected client interfaces.
     *
     * Snapshot with a Java copy, not Kotlin's List.toList(): its size==1 fast path
     * reads size then get(0) separately, which races with a concurrent removal on
     * the CopyOnWriteArrayList (ArrayIndexOutOfBoundsException during teardown).
     * Same fix as LocalServerInterface.
     */
    fun getClients(): List<Interface> = ArrayList(clients)

    override fun detach() {
        super.detach()

        // Cancel coroutines first
        acceptJob?.cancel()
        ioScope.cancel()

        // Disconnect all clients (Java snapshot copy; see getClients for why not toList()).
        for (client in ArrayList(clients)) {
            client.detach()
        }
        clients.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null

        log("Server stopped")
    }

    private fun log(message: String) {
        RnsLog.log(RnsLog.INFO, name, message)
    }

    override fun toString(): String = "TCPServerInterface[$name @ $bindAddress:$bindPort]"
}

/**
 * Interface for a single client connected to a TCP server.
 */
class TCPServerClientInterface internal constructor(
    name: String,
    private val socket: Socket,
    private val parentServer: TCPServerInterface,
    private val useKissFraming: Boolean
) : Interface(name) {

    init {
        parentInterface = parentServer
    }

    // IFAC: same network as the parent server. Credentials derive in the Interface
    // base from the delegated netname/netkey (identical bytes); the tag size follows
    // the parent so a configured ifacSizeBits applies to every client.
    override val ifacNetname: String? get() = parentServer.ifacNetname
    override val ifacNetkey: String? get() = parentServer.ifacNetkey
    override val ifacSize: Int get() = parentServer.ifacSize

    // Spawned children inherit the parent server's MTU/bitrate posture so the
    // RECEIVER side of a fixed-MTU link negotiates the same value as the parent
    // (mirrors python TCPInterface.py:619 spawned_interface.mode = self.mode,
    // extended to the MTU/bitrate posture the parent resolved from config).
    override val bitrate: Int = parentServer.bitrate
    override val hwMtu: Int = parentServer.hwMtu
    override val autoconfigureMtu: Boolean = parentServer.autoconfigureMtu
    override val fixedMtu: Boolean = parentServer.fixedMtu
    override val supportsLinkMtuDiscovery: Boolean = true


    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    // Framer and deframer are selected once from useKissFraming; the deframer bounds
    // (KISS decode cap at HW_MTU, HDLC escaped-buffer cap 2*HW_MTU+16) derive from hwMtu.
    private val framer: (ByteArray) -> ByteArray = streamFramer(useKissFraming)
    private val deframer: DeframerFeed =
        streamDeframer(useKissFraming, hwMtu, ifacSize = { ifacSize }) { data ->
        processIncoming(data)
    }

    override fun start() {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        // Dead-peer detection on every accepted socket (python TCPInterface.py:143-147
        // calls set_timeouts_linux on each connected_socket; :183-197 sets SO_KEEPALIVE,
        // TCP_KEEPIDLE=5, TCP_KEEPINTVL=2, TCP_KEEPCNT=12). Without it a peer that
        // vanished without FIN held one of the maxClients slots until the OS default
        // keepalive (~2 h), or forever with keepalive off.
        TcpKeepalive.apply(socket)
        setOnline(true)

        readJob = ioScope.launch {
            readLoop()
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(4096)

        try {
            while (online.value && !detached.get()) {
                val bytesRead = socket.getInputStream().read(buffer)

                if (bytesRead > 0) {
                    deframer(buffer, 0, bytesRead)
                } else if (bytesRead == -1) {
                    // Connection closed
                    break
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation, don't log as error
        } catch (e: IOException) {
            if (!detached.get()) {
                // Client disconnected
            }
        } catch (e: Exception) {
            // python TCPInterface.py:426-434 catches Exception and tears the client
            // down; without this the slot in the parent's client list leaked.
            if (!detached.get()) {
                RnsLog.log(RnsLog.WARNING, name, "Read loop error: ${e.javaClass.name}: ${e.message}")
                setOnline(false)
            }
        }

        // Clean up
        detach()
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.value || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        // Serialize all writes to this spawned-client socket. Pre-#46 the
        // TCPServerInterface's raw fan-out delivered packets on a single
        // reader thread, hiding concurrency here. Post-#46, Transport routes
        // via spawned children and multiple reader coroutines (one per
        // connected peer) can each trigger a `processOutgoing` on the same
        // target child concurrently — the old check-then-set on `writing`
        // was racy and interleaved socket writes, corrupting resource
        // transfers (status=CORRUPT / 7). A monitor lock is the minimum
        // viable fix and matches Python's effective serialization via the
        // GIL around blocking socket writes.
        synchronized(this) {
            try {
                val framedData = framer(data)

                socket.getOutputStream().write(framedData)
                socket.getOutputStream().flush()

                logDebug { "Sent ${data.size} bytes (framed: ${framedData.size} bytes)" }
                txBytes.addAndGet(framedData.size.toLong())
                parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

            } catch (e: IOException) {
                detach()
                throw e
            }
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        setOnline(false)

        // Cancel coroutines first
        readJob?.cancel()
        ioScope.cancel()

        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }

        parentServer.clientDisconnected(this)
    }

    private fun log(message: String) {
        RnsLog.log(RnsLog.INFO, name, message)
    }

    /** Lazy DEBUG log for per-frame hot paths — the message is built only when enabled. */
    private inline fun logDebug(message: () -> String) {
        RnsLog.log(RnsLog.DEBUG, name, message)
    }

    override fun toString(): String = "TCPServerClientInterface[$name]"
}
