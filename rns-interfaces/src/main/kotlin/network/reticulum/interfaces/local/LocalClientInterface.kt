package network.reticulum.interfaces.local

import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    }

    private val useUnixSocket: Boolean
    private val socketPath: Path?
    private val tcpHost: String?
    private val tcpPort: Int?

    private var socket: Socket? = null
    private var readThread: Thread? = null

    private val writing = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val neverConnected = AtomicBoolean(true)
    private val isSharedInstanceClient = AtomicBoolean(false)

    private val hdlcDeframer = HDLC.createDeframer { data ->
        processIncoming(data)
    }

    private var parentServer: LocalServerInterface? = null

    override val bitrate: Int = BITRATE
    override val hwMtu: Int = HW_MTU
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

        online.set(true)

        readThread = thread(name = "LocalClient-$name-read", isDaemon = true) {
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

            online.set(true)
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
    private fun reconnect() {
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

            while (!online.get()) {
                attempts++
                Thread.sleep(RECONNECT_WAIT)

                try {
                    log("Reconnection attempt $attempts...")
                    connect()
                    break
                } catch (e: Exception) {
                    // Continue loop
                }
            }

            if (!neverConnected.get() && online.get()) {
                log("Reconnected successfully")
            }
        } finally {
            reconnecting.set(false)
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)

        try {
            while (online.get() && !detached.get()) {
                val sock = socket ?: break
                val bytesRead = sock.getInputStream().read(buffer)

                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    hdlcDeframer.process(data)
                } else if (bytesRead == -1) {
                    // Connection closed
                    online.set(false)

                    if (isSharedInstanceClient.get() && !detached.get()) {
                        log("Connection closed, attempting to reconnect...")
                        reconnect()
                    } else {
                        detach()
                    }
                    break
                }
            }
        } catch (e: IOException) {
            if (!detached.get()) {
                online.set(false)

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
                log("Error in read loop: ${e.message}")
                detach()
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        while (writing.get()) {
            Thread.sleep(10)
        }

        try {
            writing.set(true)

            val framedData = HDLC.frame(data)

            socket?.getOutputStream()?.write(framedData)
            socket?.getOutputStream()?.flush()

            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            online.set(false)

            if (isSharedInstanceClient.get() && !detached.get()) {
                log("Send error: ${e.message}, will reconnect...")
                // Start reconnection in background
                thread(name = "LocalClient-$name-reconnect", isDaemon = true) {
                    reconnect()
                }
            } else {
                detach()
            }

            throw e
        } finally {
            writing.set(false)
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)

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
        println("[$name] $message")
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
