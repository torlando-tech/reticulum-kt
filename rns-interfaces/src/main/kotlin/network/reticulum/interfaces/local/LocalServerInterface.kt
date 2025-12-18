package network.reticulum.interfaces.local

import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Local server interface for shared daemon IPC.
 *
 * This interface listens for connections from LocalClientInterface instances,
 * allowing multiple applications to share a single Reticulum daemon instance.
 *
 * Supports both Unix domain sockets (preferred) and TCP localhost fallback.
 *
 * Example usage:
 * ```
 * val server = LocalServerInterface(
 *     name = "SharedInstance",
 *     socketPath = "~/.reticulum/rnstransport.socket"
 * )
 * server.start()
 * ```
 */
class LocalServerInterface : Interface {
    companion object {
        /** Default socket path for shared instance. */
        const val DEFAULT_SOCKET_PATH = ".reticulum/rnstransport.socket"

        /** Default TCP port when Unix sockets unavailable. */
        const val DEFAULT_TCP_PORT = 37428

        /** Maximum number of concurrent clients. */
        const val MAX_CLIENTS = 256

        /** Bitrate for local IPC (1 Gbps). */
        const val BITRATE = 1_000_000_000

        /** Hardware MTU for local IPC. */
        const val HW_MTU = 262144

        /**
         * Check if Unix domain sockets are available on this platform.
         */
        fun supportsUnixSockets(): Boolean {
            return try {
                // Try to create a Unix domain socket address (will fail on unsupported platforms)
                UnixDomainSocketAddress.of(Path.of("/tmp/test"))
                true
            } catch (e: UnsupportedOperationException) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private val useUnixSocket: Boolean
    private val socketPath: Path?
    private val tcpPort: Int?

    private var serverChannel: ServerSocketChannel? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val clientCounter = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<LocalClientInterface>()

    override val bitrate: Int = BITRATE
    override val hwMtu: Int = HW_MTU
    override val canReceive: Boolean = true
    override val canSend: Boolean = false // Server doesn't send directly, clients do

    /**
     * Create a LocalServerInterface with Unix domain socket.
     *
     * @param name Interface name
     * @param socketPath Path to Unix socket (expands ~ to user home)
     */
    constructor(
        name: String = "SharedInstance",
        socketPath: String = DEFAULT_SOCKET_PATH
    ) : super(name) {
        this.useUnixSocket = supportsUnixSockets()

        if (this.useUnixSocket) {
            // Expand ~ to user home directory
            val expanded = if (socketPath.startsWith("~/")) {
                System.getProperty("user.home") + socketPath.substring(1)
            } else {
                socketPath
            }
            this.socketPath = Path.of(expanded)
            this.tcpPort = null
        } else {
            // Fall back to TCP
            this.socketPath = null
            this.tcpPort = DEFAULT_TCP_PORT
        }

        spawnedInterfaces = mutableListOf()
    }

    /**
     * Create a LocalServerInterface with TCP.
     *
     * @param name Interface name
     * @param tcpPort TCP port to bind to (localhost only)
     */
    constructor(
        name: String = "SharedInstance",
        tcpPort: Int
    ) : super(name) {
        this.useUnixSocket = false
        this.socketPath = null
        this.tcpPort = tcpPort
        spawnedInterfaces = mutableListOf()
    }

    override fun start() {
        try {
            if (useUnixSocket && socketPath != null) {
                startUnixSocket()
            } else if (tcpPort != null) {
                startTcpSocket()
            } else {
                throw IllegalStateException("No socket path or TCP port configured")
            }

            online.set(true)

            acceptThread = thread(name = "LocalServer-$name-accept", isDaemon = true) {
                acceptLoop()
            }
        } catch (e: Exception) {
            log("Failed to start local server: ${e.message}")
            throw e
        }
    }

    private fun startUnixSocket() {
        val path = socketPath ?: throw IllegalStateException("Socket path is null")

        // Create parent directory if it doesn't exist
        val parentDir = path.parent
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        // Remove old socket file if it exists
        if (Files.exists(path)) {
            Files.delete(path)
        }

        // Create Unix domain socket server
        val socketAddress = UnixDomainSocketAddress.of(path)
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel?.bind(socketAddress)

        log("Listening on Unix socket: $path")
    }

    private fun startTcpSocket() {
        val port = tcpPort ?: throw IllegalStateException("TCP port is null")

        serverSocket = ServerSocket()
        serverSocket?.reuseAddress = true
        serverSocket?.bind(InetSocketAddress("127.0.0.1", port))

        log("Listening on TCP: 127.0.0.1:$port")
    }

    private fun acceptLoop() {
        while (online.get() && !detached.get()) {
            try {
                val socket = if (useUnixSocket && serverChannel != null) {
                    acceptUnixSocket()
                } else if (serverSocket != null) {
                    acceptTcpSocket()
                } else {
                    break
                }

                if (socket != null) {
                    handleNewClient(socket)
                }
            } catch (e: SocketException) {
                if (!detached.get()) {
                    log("Accept error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (!detached.get()) {
                    log("Error accepting connection: ${e.message}")
                }
            }
        }
    }

    private fun acceptUnixSocket(): Socket? {
        val channel = serverChannel?.accept() ?: return null
        return channel.socket()
    }

    private fun acceptTcpSocket(): Socket? {
        return serverSocket?.accept()
    }

    private fun handleNewClient(socket: Socket) {
        if (clients.size >= MAX_CLIENTS) {
            log("Max clients ($MAX_CLIENTS) reached, rejecting connection")
            socket.close()
            return
        }

        val clientId = clientCounter.incrementAndGet()
        val clientName = if (useUnixSocket) {
            "$clientId@${socketPath?.fileName}"
        } else {
            "$clientId@${tcpPort}"
        }

        val clientInterface = LocalClientInterface(
            name = clientName,
            connectedSocket = socket,
            parentServer = this
        )

        clients.add(clientInterface)
        spawnedInterfaces?.add(clientInterface)

        clientInterface.onPacketReceived = { data, iface ->
            // Forward received packets to our callback
            onPacketReceived?.invoke(data, iface)
        }

        clientInterface.start()

        log("Client connected: $clientName (total: ${clients.size})")
    }

    /**
     * Called by client interface when it disconnects.
     */
    internal fun clientDisconnected(client: LocalClientInterface) {
        clients.remove(client)
        spawnedInterfaces?.remove(client)
        log("Client disconnected: ${client.name} (remaining: ${clients.size})")
    }

    /**
     * Send data to all connected clients.
     *
     * This is called by Transport when broadcasting packets to local clients.
     */
    override fun processOutgoing(data: ByteArray) {
        // Broadcast to all connected clients
        for (client in clients) {
            try {
                if (client.online.get()) {
                    client.processOutgoing(data)
                }
            } catch (e: Exception) {
                // Client will be cleaned up by its own error handling
                log("Error sending to client ${client.name}: ${e.message}")
            }
        }
    }

    /**
     * Get number of connected clients.
     */
    fun clientCount(): Int = clients.size

    /**
     * Get list of connected client interfaces.
     */
    fun getClients(): List<Interface> = clients.toList()

    override fun detach() {
        super.detach()

        // Disconnect all clients
        for (client in clients.toList()) {
            client.detach()
        }
        clients.clear()

        // Close server socket
        try {
            serverChannel?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverChannel = null
        serverSocket = null

        // Clean up Unix socket file
        if (useUnixSocket && socketPath != null) {
            try {
                Files.deleteIfExists(socketPath)
            } catch (e: Exception) {
                // Ignore
            }
        }

        log("Server stopped")
    }

    private fun log(message: String) {
        println("[$name] $message")
    }

    override fun toString(): String {
        return if (useUnixSocket && socketPath != null) {
            "LocalServerInterface[$name @ $socketPath]"
        } else if (tcpPort != null) {
            "LocalServerInterface[$name @ 127.0.0.1:$tcpPort]"
        } else {
            "LocalServerInterface[$name]"
        }
    }
}
