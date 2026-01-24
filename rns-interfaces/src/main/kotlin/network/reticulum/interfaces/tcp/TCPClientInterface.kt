package network.reticulum.interfaces.tcp

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
import network.reticulum.identity.Identity
import network.reticulum.interfaces.IfacCredentials
import network.reticulum.interfaces.IfacUtils
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.KISS
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP client interface for Reticulum.
 *
 * Connects to a remote TCP server and exchanges packets using
 * HDLC or KISS framing.
 *
 * Debug logging can be enabled via system property: -Dreticulum.tcp.debug=true
 */
class TCPClientInterface(
    name: String,
    private val targetHost: String,
    private val targetPort: Int,
    private val useKissFraming: Boolean = false,
    private val connectTimeoutMs: Int = INITIAL_CONNECT_TIMEOUT,
    private val maxReconnectAttempts: Int? = null,
    /** Enable TCP keep-alive. Default true for Python RNS compatibility (can disable for mobile battery). */
    private val keepAlive: Boolean = true,
    // IFAC (Interface Access Code) parameters for network isolation
    override val ifacNetname: String? = null,
    override val ifacNetkey: String? = null,
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 262144
        const val INITIAL_CONNECT_TIMEOUT = 5000 // 5 seconds
        const val RECONNECT_WAIT_MS = 5000L // 5 seconds

        /** Enable verbose debug logging via -Dreticulum.tcp.debug=true */
        private val DEBUG = System.getProperty("reticulum.tcp.debug", "false").toBoolean()
    }

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU

    // IFAC credentials - derived lazily from network name/passphrase
    private val _ifacCredentials: IfacCredentials? by lazy {
        IfacUtils.deriveIfacCredentials(ifacNetname, ifacNetkey)
    }

    override val ifacSize: Int
        get() = if (_ifacCredentials != null) 16 else 0

    override val ifacKey: ByteArray?
        get() = _ifacCredentials?.key

    override val ifacIdentity: Identity?
        get() = _ifacCredentials?.identity

    private var socket: Socket? = null
    private val reconnecting = AtomicBoolean(false)
    private val neverConnected = AtomicBoolean(true)
    private val writing = AtomicBoolean(false)

    // Debug counters
    private val framesSent = AtomicLong(0)
    private val framesReceived = AtomicLong(0)

    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var connectJob: Job? = null

    private val hdlcDeframer = HDLC.createDeframer { data ->
        val frameNum = framesReceived.incrementAndGet()
        if (DEBUG) {
            val hexPreview = data.take(16).joinToString(" ") { "%02x".format(it) }
            val suffix = if (data.size > 16) "..." else ""
            debugLog("RECV frame #$frameNum: ${data.size} bytes, data=[$hexPreview$suffix]")
        }
        processIncoming(data)
    }

    private val kissDeframer = KISS.createDeframer { _, data ->
        val frameNum = framesReceived.incrementAndGet()
        if (DEBUG) {
            val hexPreview = data.take(16).joinToString(" ") { "%02x".format(it) }
            val suffix = if (data.size > 16) "..." else ""
            debugLog("RECV frame #$frameNum (KISS): ${data.size} bytes, data=[$hexPreview$suffix]")
        }
        processIncoming(data)
    }

    override fun start() {
        connectJob = ioScope.launch {
            if (!connect(initial = true)) {
                reconnect()
            }
        }
    }

    private fun connect(initial: Boolean = false): Boolean {
        return try {
            if (initial) {
                log("Establishing TCP connection to $targetHost:$targetPort...")
            }

            val sock = Socket()
            sock.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
            sock.tcpNoDelay = true
            sock.keepAlive = keepAlive
            sock.soTimeout = 0 // Block on read
            sock.setSoLinger(true, 5) // Clean shutdown with 5s linger (prevents RST on close)

            // Get input stream immediately while we have the socket reference
            val inputStream = sock.getInputStream()

            socket = sock
            online.set(true)
            neverConnected.set(false)

            // Request tunnel synthesis for this connection
            wantsTunnel = true

            if (initial) {
                log("TCP connection established")
            }

            // Debug: Log socket state after connection
            if (DEBUG) {
                debugLog("Socket connected - state dump:")
                debugLog("  localAddress: ${sock.localSocketAddress}")
                debugLog("  remoteAddress: ${sock.remoteSocketAddress}")
                debugLog("  tcpNoDelay: ${sock.tcpNoDelay}")
                debugLog("  keepAlive: ${sock.keepAlive}")
                debugLog("  soTimeout: ${sock.soTimeout}")
                debugLog("  receiveBufferSize: ${sock.receiveBufferSize}")
                debugLog("  sendBufferSize: ${sock.sendBufferSize}")
                debugLog("  soLinger: ${sock.soLinger}")
                debugLog("  reuseAddress: ${sock.reuseAddress}")
            }

            // Send an empty HDLC frame to "activate" the connection
            // This triggers Python RNS's read_loop recv() to return, which ensures
            // the handler thread is fully blocked and won't close the socket
            try {
                val keepaliveFrame = HDLC.frame(ByteArray(0))
                sock.getOutputStream().write(keepaliveFrame)
                sock.getOutputStream().flush()
            } catch (e: Exception) {
                // Keepalive failure is not critical
            }

            // Delay for Python's ThreadingMixIn to spawn handler and enter read_loop()
            // 100ms gives time for the handler thread to start blocking on recv()
            Thread.sleep(100)

            // Pass socket and stream directly to avoid race conditions
            startReadLoop(sock, inputStream)
            true
        } catch (e: Exception) {
            if (initial) {
                log("Initial connection failed: ${e.message}")
                log("Will retry in ${RECONNECT_WAIT_MS / 1000} seconds")
            }
            false
        }
    }

    private suspend fun reconnect() {
        if (reconnecting.getAndSet(true)) return

        var attempts = 0
        while (!online.get() && !detached.get()) {
            delay(RECONNECT_WAIT_MS)
            attempts++

            if (maxReconnectAttempts != null && attempts > maxReconnectAttempts) {
                log("Max reconnection attempts ($maxReconnectAttempts) reached")
                detach()
                break
            }

            try {
                if (connect()) {
                    if (!neverConnected.get()) {
                        log("Reconnected successfully")
                    }
                    break
                }
            } catch (e: CancellationException) {
                // Scope was cancelled, stop reconnecting
                break
            } catch (e: Exception) {
                log("Reconnection attempt $attempts failed: ${e.message}")
            }
        }

        reconnecting.set(false)
    }

    private fun startReadLoop(sock: Socket, inputStream: InputStream) {
        readJob = ioScope.launch {
            val buffer = ByteArray(4096)

            try {
                while (isActive && online.get() && !detached.get()) {
                    if (sock.isClosed || !sock.isConnected || sock.isInputShutdown) {
                        break
                    }

                    // Blocking read wrapped in IO dispatcher
                    val bytesRead = withContext(Dispatchers.IO) {
                        inputStream.read(buffer)
                    }

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        if (useKissFraming) {
                            kissDeframer.process(data)
                        } else {
                            hdlcDeframer.process(data)
                        }
                    } else if (bytesRead == -1) {
                        // Connection closed
                        if (DEBUG) {
                            debugLog("Read loop: EOF received (bytesRead=-1), connection closed by peer")
                            debugLog("  frames sent: ${framesSent.get()}, frames received: ${framesReceived.get()}")
                            debugLog("  socket state: isConnected=${sock.isConnected}, isClosed=${sock.isClosed}")
                            debugLog("  socket state: isInputShutdown=${sock.isInputShutdown}, isOutputShutdown=${sock.isOutputShutdown}")
                        }
                        online.set(false)
                        break
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation, don't log as error
                if (DEBUG) {
                    debugLog("Read loop: CancellationException (normal shutdown)")
                }
            } catch (e: IOException) {
                if (DEBUG) {
                    debugLog("Read loop: IOException - ${e.javaClass.name}: ${e.message}")
                    debugLog("  frames sent: ${framesSent.get()}, frames received: ${framesReceived.get()}")
                    try {
                        debugLog("  socket state: isConnected=${sock.isConnected}, isClosed=${sock.isClosed}")
                        debugLog("  socket state: isInputShutdown=${sock.isInputShutdown}, isOutputShutdown=${sock.isOutputShutdown}")
                    } catch (ex: Exception) {
                        debugLog("  socket state: unavailable (${ex.message})")
                    }
                }
                if (!detached.get()) {
                    online.set(false)
                }
            }

            // Connection lost, try to reconnect
            if (!detached.get() && isActive) {
                log("Connection lost, attempting to reconnect...")
                reconnect()
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        val sock = socket ?: throw IllegalStateException("Socket is null")

        // Verify connection state before attempting write
        if (!sock.isConnected || sock.isClosed || sock.isOutputShutdown) {
            val state = "isConnected=${sock.isConnected}, isClosed=${sock.isClosed}, isOutputShutdown=${sock.isOutputShutdown}"
            log("Socket not in valid state for write: $state")
            teardown()
            throw IOException("Socket not in valid state for write: $state")
        }

        // Wait for any pending write to complete
        while (writing.get()) {
            Thread.sleep(10)
        }

        try {
            writing.set(true)

            val framedData = if (useKissFraming) {
                KISS.frame(data)
            } else {
                HDLC.frame(data)
            }

            // Get output stream once for atomic write+flush
            val outputStream = sock.getOutputStream()

            // Write and flush atomically - if either fails, teardown
            try {
                outputStream.write(framedData)
                outputStream.flush()
            } catch (e: java.net.SocketTimeoutException) {
                // Specific timeout handling with diagnostic info
                log("Write timeout: ${e.message}")
                if (DEBUG) {
                    debugLog("SocketTimeoutException during write:")
                    debugLog("  data size: ${framedData.size} bytes")
                    debugLog("  socket state: isConnected=${sock.isConnected}, isClosed=${sock.isClosed}")
                }
                teardown()
                throw e
            }

            val frameNum = framesSent.incrementAndGet()
            if (DEBUG) {
                val hexPreview = data.take(16).joinToString(" ") { "%02x".format(it) }
                val suffix = if (data.size > 16) "..." else ""
                debugLog("SENT frame #$frameNum: ${data.size} bytes (framed: ${framedData.size}), data=[$hexPreview$suffix]")
                debugLog("  socket.isConnected: ${sock.isConnected}, socket.isClosed: ${sock.isClosed}")
            }

            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            log("Write error: ${e.message}")
            if (DEBUG) {
                debugLog("Write IOException - full details:")
                debugLog("  exception: ${e.javaClass.name}: ${e.message}")
                debugLog("  socket state: isConnected=${sock.isConnected}, isClosed=${sock.isClosed}")
                debugLog("  socket state: isInputShutdown=${sock.isInputShutdown}, isOutputShutdown=${sock.isOutputShutdown}")
                debugLog("  frames sent before error: ${framesSent.get()}")
                debugLog("  frames received before error: ${framesReceived.get()}")
            }
            teardown()
            throw e
        } finally {
            writing.set(false)
        }
    }

    override fun detach() {
        super.detach()
        // Cancel all coroutines first
        readJob?.cancel()
        connectJob?.cancel()
        ioScope.cancel()
        closeSocket()
    }

    private fun teardown() {
        if (DEBUG) {
            debugLog("Teardown called - transitioning to OFFLINE")
            debugLog("  frames sent: ${framesSent.get()}, frames received: ${framesReceived.get()}")
            debugLog("  detached: ${detached.get()}")
        }
        online.set(false)
        closeSocket()

        if (!detached.get()) {
            ioScope.launch {
                reconnect()
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    private fun debugLog(message: String) {
        if (DEBUG) {
            val timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            )
            println("[$timestamp] [$name] [DEBUG] $message")
        }
    }

    override fun toString(): String = "TCPClientInterface[$name -> $targetHost:$targetPort]"
}
