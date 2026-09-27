package network.reticulum.interfaces.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.reticulum.common.RnsLog
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local client interface for connecting to shared daemon.
 *
 * This interface connects to a LocalServerInterface, allowing this application
 * to use a shared Reticulum daemon instance instead of running its own.
 *
 * Features:
 * - Automatic reconnection on disconnect
 * - HDLC framing for packet delimiting
 * - Support for Unix domain sockets and TCP
 *
 * Example usage:
 * ```
 * // Connect via Unix socket
 * val client = LocalClientInterface(
 *     name = "MyApp",
 *     socketPath = "~/.reticulum/rnstransport.socket"
 * )
 *
 * // Or connect via TCP
 * val client = LocalClientInterface(
 *     name = "MyApp",
 *     tcpPort = 37428
 * )
 *
 * client.start()
 * ```
 */
class LocalClientInterface : Interface {
    companion object {
        /** Default socket path for shared instance. */
        const val DEFAULT_SOCKET_PATH = ".reticulum/rnstransport.socket"

        /** Default TCP port when Unix sockets unavailable. */
        const val DEFAULT_TCP_PORT = 37428

        /** Delay before reconnection attempts (milliseconds). */
        const val RECONNECT_WAIT = 8000L

        /** Bitrate for local IPC (1 Gbps). */
        const val BITRATE = 1_000_000_000

        /** Hardware MTU for local IPC. */
        const val HW_MTU = 262144

        /**
         * Escaped-buffer bound of the HDLC deframer: an unterminated run longer than this
         * is discarded and the deframer resyncs on the next FLAG (python util/HDLC.py:81-83,
         * 2*mtu; +16 for the same slack the stream interfaces allow).
         */
        const val MAX_FRAME_BYTES = 2 * HW_MTU + 16
    }

    private val useUnixSocket: Boolean
    private val socketPath: Path?
    private val tcpHost: String?
    private val tcpPort: Int?

    private var socket: Socket? = null

    // Serializes concurrent writes to the socket. Was an AtomicBoolean check-then-set with a
    // 10ms Thread.sleep busy-spin — racy (two threads can pass the check before either sets
    // the flag, interleaving HDLC frame bytes on the socket) and slow (10ms floor per
    // contended turn). A ReentrantLock gives real mutual exclusion and wakes immediately.
    // Same fix as TCPClientInterface.
    private val writeLock = java.util.concurrent.locks.ReentrantLock()
    private val reconnecting = AtomicBoolean(false)
    private val neverConnected = AtomicBoolean(true)
    private val isSharedInstanceClient = AtomicBoolean(false)

    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    // Bounded like python LocalInterface.py:79-80 (ReceiveBuffer(mtu=HW_MTU,
    // max_frame_len=HW_MTU)), whose util/HDLC.py:81-83 discards an unterminated run
    // longer than 2*HW_MTU. An unbounded deframer let any local client grow this
    // buffer without limit by never sending a closing FLAG.
    private val hdlcDeframer = HDLC.createDeframer(maxFrameBytes = MAX_FRAME_BYTES) { data ->
        processIncoming(data)
    }

    private var parentServer: LocalServerInterface? = null

    override val bitrate: Int = BITRATE
    override val hwMtu: Int = HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = true
    override val canReceive: Boolean = true
    override val canSend: Boolean = true // Clients can both send and receive

    /**
     * Create a LocalClientInterface with Unix domain socket.
     *
     * @param name Interface name
     * @param socketPath Path to Unix socket (expands ~ to user home)
     */
    constructor(
        name: String,
        socketPath: String = DEFAULT_SOCKET_PATH
    ) : super(name) {
        this.useUnixSocket = LocalServerInterface.supportsUnixSockets()

        if (this.useUnixSocket) {
            // Expand ~ to user home directory
            val expanded = if (socketPath.startsWith("~/")) {
                System.getProperty("user.home") + socketPath.substring(1)
            } else {
                socketPath
            }
            this.socketPath = Path.of(expanded)
            this.tcpHost = null
            this.tcpPort = null
        } else {
            // Fall back to TCP
            this.socketPath = null
            this.tcpHost = "127.0.0.1"
            this.tcpPort = DEFAULT_TCP_PORT
        }

        this.isSharedInstanceClient.set(true)
        // Shared instance already handles ingress control
        this.ingressControl.set(false)
    }

    /**
     * Create a LocalClientInterface with TCP.
     *
     * @param name Interface name
     * @param tcpPort TCP port to connect to
     * @param tcpHost TCP host to connect to (default: 127.0.0.1)
     */
    constructor(
        name: String,
        tcpPort: Int,
        tcpHost: String = "127.0.0.1"
    ) : super(name) {
        this.useUnixSocket = false
        this.socketPath = null
        this.tcpHost = tcpHost
        this.tcpPort = tcpPort
        this.isSharedInstanceClient.set(true)
        // Shared instance already handles ingress control
        this.ingressControl.set(false)
    }

    /**
     * Internal constructor for server-spawned client interfaces.
     *
     * @param name Interface name
     * @param connectedSocket Already-connected socket
     * @param parentServer Parent server that spawned this client
     */
    internal constructor(
        name: String,
        connectedSocket: Socket,
        parentServer: LocalServerInterface
    ) : super(name) {
        this.useUnixSocket = false
        this.socketPath = null
        this.tcpHost = null
        this.tcpPort = null
        this.socket = connectedSocket
        this.parentServer = parentServer
        this.parentInterface = parentServer
        this.isSharedInstanceClient.set(false) // This is server-side, not a client
    }

    override fun start() {
        if (socket != null) {
            // Already connected (server-spawned)
            startWithSocket()
        } else {
            // Need to connect to server
            connect()
        }
    }

    private fun startWithSocket() {
        val sock = socket ?: throw IllegalStateException("Socket is null")

        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 0
        } catch (e: Exception) {
            // Unix sockets don't support TCP options, ignore
        }

        setOnline(true)

        readJob = ioScope.launch {
            readLoop()
        }
    }

    /**
     * Connect to the shared instance server.
     */
    private fun connect() {
        try {
            socket = if (useUnixSocket && socketPath != null) {
                connectUnixSocket()
            } else if (tcpHost != null && tcpPort != null) {
                connectTcpSocket()
            } else {
                throw IllegalStateException("No socket path or TCP port configured")
            }

            setOnline(true)
            neverConnected.set(false)

            startWithSocket()

            log("Connected to shared instance")
        } catch (e: Exception) {
            log("Failed to connect: ${e.message}")
            throw e
        }
    }

    private fun connectUnixSocket(): Socket {
        val path = socketPath ?: throw IllegalStateException("Socket path is null")

        if (!Files.exists(path)) {
            throw IOException("Socket file does not exist: $path")
        }

        val socketAddress = UnixDomainSocketAddress.of(path)
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(socketAddress)

        return channel.socket()
    }

    private fun connectTcpSocket(): Socket {
        val host = tcpHost ?: throw IllegalStateException("TCP host is null")
        val port = tcpPort ?: throw IllegalStateException("TCP port is null")

        val socket = Socket()
        socket.connect(InetSocketAddress(host, port))
        socket.tcpNoDelay = true

        return socket
    }

    /**
     * Attempt to reconnect to the shared instance.
     */
    private suspend fun reconnect() {
        if (!isSharedInstanceClient.get()) {
            log("Attempt to reconnect on server-spawned interface, ignoring")
            return
        }

        if (reconnecting.getAndSet(true)) {
            // Already reconnecting
            return
        }

        try {
            var attempts = 0

            while (!online.value) {
                attempts++
                delay(RECONNECT_WAIT)

                try {
                    log("Reconnection attempt $attempts...")
                    connect()
                    break
                } catch (e: CancellationException) {
                    // Scope was cancelled, stop reconnecting
                    break
                } catch (e: Exception) {
                    // Continue loop
                }
            }

            if (!neverConnected.get() && online.value) {
                log("Reconnected successfully")
            }
        } finally {
            reconnecting.set(false)
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(4096)

        log("Read loop started")

        try {
            while (online.value && !detached.get()) {
                val sock = socket ?: break
                // The enclosing coroutine is launched on `ioScope`, already bound to
                // Dispatchers.IO; read directly rather than re-dispatching to the same
                // dispatcher every iteration (a no-op thread hop). Matches python's direct
                // `LocalInterface.py:302 self.socket.recv(4096)`. See upstream PR #75.
                val bytesRead = sock.getInputStream().read(buffer)

                if (bytesRead > 0) {
                    logDebug { "Received $bytesRead bytes" }
                    hdlcDeframer.process(buffer, 0, bytesRead)
                } else if (bytesRead == -1) {
                    // Connection closed
                    log("Connection closed by remote (read returned -1)")
                    setOnline(false)

                    if (isSharedInstanceClient.get() && !detached.get()) {
                        log("Connection closed, attempting to reconnect...")
                        reconnect()
                    } else {
                        detach()
                    }
                    break
                }
            }
            log("Read loop exited normally (online=${online.value}, detached=${detached.get()})")
        } catch (e: CancellationException) {
            // Normal cancellation, don't log as error
        } catch (e: IOException) {
            if (!detached.get()) {
                log("IOException in read loop: ${e.message}")
                setOnline(false)

                if (isSharedInstanceClient.get()) {
                    log("Connection error: ${e.message}, attempting to reconnect...")
                    reconnect()
                } else {
                    // Server-spawned client, just disconnect
                    detach()
                }
            }
        } catch (e: Exception) {
            if (!detached.get()) {
                log("Error in read loop: ${e.javaClass.simpleName}: ${e.message}")
                detach()
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.value || detached.get()) {
            log("processOutgoing called but interface not online (online=${online.value}, detached=${detached.get()})")
            throw IllegalStateException("Interface is not online")
        }

        // lockInterruptibly() keeps the old busy-spin's InterruptedException path, so a
        // stop()-style teardown can still interrupt a contended writer (see TCPClientInterface).
        writeLock.lockInterruptibly()
        try {
            val framedData = HDLC.frame(data)
            logDebug { "Sending ${framedData.size} bytes (${data.size} unframed)" }

            socket?.getOutputStream()?.write(framedData)
            socket?.getOutputStream()?.flush()

            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            setOnline(false)

            if (isSharedInstanceClient.get() && !detached.get()) {
                log("Send error: ${e.message}, will reconnect...")
                // Start reconnection in background
                ioScope.launch {
                    reconnect()
                }
            } else {
                detach()
            }

            throw e
        } finally {
            writeLock.unlock()
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        setOnline(false)

        // Cancel coroutines first
        readJob?.cancel()
        ioScope.cancel()

        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null

        // Notify parent server if this is a server-spawned client
        parentServer?.clientDisconnected(this)

        if (isSharedInstanceClient.get()) {
            log("Disconnected from shared instance")
        }
    }

    /**
     * Check if this is a client connecting to a shared instance.
     */
    fun isConnectedToSharedInstance(): Boolean = isSharedInstanceClient.get()

    /**
     * Check if reconnection is in progress.
     */
    fun isReconnecting(): Boolean = reconnecting.get()

    private fun log(message: String) {
        RnsLog.log(RnsLog.INFO, name, message)
    }

    /** Lazy DEBUG log for per-frame hot paths — the message is built only when enabled. */
    private inline fun logDebug(message: () -> String) {
        RnsLog.log(RnsLog.DEBUG, name, message)
    }

    override fun toString(): String {
        return if (useUnixSocket && socketPath != null) {
            "LocalClientInterface[$name @ $socketPath]"
        } else if (tcpHost != null && tcpPort != null) {
            "LocalClientInterface[$name @ $tcpHost:$tcpPort]"
        } else {
            "LocalClientInterface[$name]"
        }
    }
}
