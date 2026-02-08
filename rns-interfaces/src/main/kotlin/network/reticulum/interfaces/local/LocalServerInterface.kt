package network.reticulum.interfaces.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
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

        /** Default instance name for abstract sockets (Python RNS compatible). */
        const val DEFAULT_INSTANCE_NAME = "default"

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

        /**
         * Check if abstract Unix sockets are available (Linux-only).
         */
        fun supportsAbstractSockets(): Boolean {
            if (!supportsUnixSockets()) return false
            // Abstract sockets are Linux-specific
            return System.getProperty("os.name").lowercase().contains("linux")
        }
    }

    private val useUnixSocket: Boolean
    private val useAbstractSocket: Boolean
    private val abstractSocketName: String?
    private val socketPath: Path?
    private val tcpPort: Int?

    private var serverChannel: ServerSocketChannel? = null
    private var serverSocket: ServerSocket? = null

    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    private val clientCounter = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<LocalClientInterface>()

    override val bitrate: Int = BITRATE
    override val hwMtu: Int = HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = true
    override val canReceive: Boolean = true
    override val canSend: Boolean = true // Broadcasts to all connected clients
    override val isLocalSharedInstance: Boolean = true // Python RNS compatibility

    /**
     * Create a LocalServerInterface with Unix domain socket (file-based).
     *
     * @param name Interface name
     * @param socketPath Path to Unix socket (expands ~ to user home)
     */
    constructor(
        name: String = "SharedInstance",
        socketPath: String = DEFAULT_SOCKET_PATH
    ) : super(name) {
        this.useUnixSocket = supportsUnixSockets()
        this.useAbstractSocket = false
        this.abstractSocketName = null

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
        this.useAbstractSocket = false
        this.abstractSocketName = null
        this.socketPath = null
        this.tcpPort = tcpPort
        spawnedInterfaces = mutableListOf()
    }

    /**
     * Create a LocalServerInterface with abstract Unix socket (Python RNS compatible).
     *
     * Abstract sockets use the Linux-specific abstract namespace, which doesn't
     * create a file on the filesystem. Python RNS uses abstract sockets with
     * the format "\0rns/{instance_name}".
     *
     * @param name Interface name
     * @param instanceName Instance name (used in abstract socket path)
     * @param useAbstract Must be true to distinguish from file-based constructor
     */
    constructor(
        name: String = "SharedInstance",
        instanceName: String = DEFAULT_INSTANCE_NAME,
        useAbstract: Boolean
    ) : super(name) {
        if (useAbstract && supportsAbstractSockets()) {
            this.useUnixSocket = true
            this.useAbstractSocket = true
            this.abstractSocketName = "\u0000rns/$instanceName"
            this.socketPath = null
            this.tcpPort = null
        } else {
            // Fall back to TCP if abstract sockets not supported
            this.useUnixSocket = false
            this.useAbstractSocket = false
            this.abstractSocketName = null
            this.socketPath = null
            this.tcpPort = DEFAULT_TCP_PORT
        }
        spawnedInterfaces = mutableListOf()
    }

    override fun start() {
        try {
            if (useAbstractSocket && abstractSocketName != null) {
                startAbstractSocket()
            } else if (useUnixSocket && socketPath != null) {
                startUnixSocket()
            } else if (tcpPort != null) {
                startTcpSocket()
            } else {
                throw IllegalStateException("No socket path or TCP port configured")
            }

            online.set(true)

            acceptJob = ioScope.launch {
                acceptLoop()
            }
        } catch (e: Exception) {
            log("Failed to start local server: ${e.message}")
            throw e
        }
    }

    private fun startAbstractSocket() {
        val sockName = abstractSocketName ?: throw IllegalStateException("Abstract socket name is null")

        // Create abstract Unix domain socket server
        // Abstract sockets don't need filesystem cleanup
        val socketAddress = UnixDomainSocketAddress.of(sockName)
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel?.bind(socketAddress)

        // Display the abstract socket name nicely (replace \0 with @)
        val displayName = sockName.replace("\u0000", "@")
        log("Listening on abstract socket: $displayName")
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

    private suspend fun acceptLoop() {
        while (online.get() && !detached.get()) {
            try {
                // Blocking accept wrapped in IO dispatcher
                val socket = withContext(Dispatchers.IO) {
                    if ((useUnixSocket || useAbstractSocket) && serverChannel != null) {
                        acceptUnixSocket()
                    } else if (serverSocket != null) {
                        acceptTcpSocket()
                    } else {
                        null
                    }
                }

                if (socket != null) {
                    handleNewClient(socket)
                } else {
                    break
                }
            } catch (e: CancellationException) {
                // Normal cancellation, exit loop
                break
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
        val clientName = when {
            useAbstractSocket -> "$clientId@abstract"
            useUnixSocket -> "$clientId@${socketPath?.fileName}"
            else -> "$clientId@${tcpPort}"
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

        try {
            Transport.registerInterface(clientInterface.toRef())
        } catch (e: Exception) {
            log("Could not register spawned interface with Transport: ${e.message}")
        }

        log("Client connected: $clientName (total: ${clients.size})")
    }

    /**
     * Called by client interface when it disconnects.
     */
    internal fun clientDisconnected(client: LocalClientInterface) {
        clients.remove(client)
        spawnedInterfaces?.remove(client)
        try {
            Transport.deregisterInterface(client.toRef())
        } catch (e: Exception) {
            log("Could not deregister spawned interface from Transport: ${e.message}")
        }

        log("Client disconnected: ${client.name} (remaining: ${clients.size})")
    }

    /**
     * No-op for server interface.
     *
     * Transport calls each spawned client's processOutgoing() directly.
     * If the server also broadcasts here, clients would receive packets TWICE,
     * causing data corruption.
     *
     * Python reference: LocalInterface.py:454-455
     * def process_outgoing(self, data):
     *     pass
     */
    override fun processOutgoing(data: ByteArray) {
        // No-op: Server interface is just a listener, not a transmitter
        // Spawned client interfaces handle transmission when called by Transport
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

        // Cancel coroutines first
        acceptJob?.cancel()
        ioScope.cancel()

        // Disconnect all clients
        for (client in clients.toList()) {
            try {
                Transport.deregisterInterface(client.toRef())
            } catch (e: Exception) {
                // Ignore during shutdown
            }
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

        // Clean up Unix socket file (not needed for abstract sockets)
        if (useUnixSocket && !useAbstractSocket && socketPath != null) {
            try {
                Files.deleteIfExists(socketPath)
            } catch (e: Exception) {
                // Ignore
            }
        }

        log("Server stopped")
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String {
        return when {
            useAbstractSocket && abstractSocketName != null -> {
                val displayName = abstractSocketName.replace("\u0000", "@")
                "LocalServerInterface[$name @ $displayName]"
            }
            useUnixSocket && socketPath != null -> {
                "LocalServerInterface[$name @ $socketPath]"
            }
            tcpPort != null -> {
                "LocalServerInterface[$name @ 127.0.0.1:$tcpPort]"
            }
            else -> {
                "LocalServerInterface[$name]"
            }
        }
    }
}
