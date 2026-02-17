package network.reticulum.interfaces.tcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 262144
    }

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

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = true
    // Server can receive/send via its spawned client interfaces
    // processOutgoing broadcasts to all connected clients
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    /**
     * Called when a new client connects. Use to register the spawned interface with Transport.
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
        spawnedInterfaces = mutableListOf()
    }

    override fun start() {
        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress(bindAddress, bindPort))
            online.set(true)
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
        while (online.get() && !detached.get()) {
            try {
                val server = serverSocket ?: break
                // Blocking accept wrapped in IO dispatcher
                val clientSocket = withContext(Dispatchers.IO) {
                    server.accept()
                }

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

                clients.add(clientInterface)
                spawnedInterfaces?.add(clientInterface)

                clientInterface.onPacketReceived = { data, iface ->
                    // Forward received packets to our callback
                    onPacketReceived?.invoke(data, iface)

                    // Rebroadcast to all OTHER clients (not the sender)
                    for (otherClient in clients) {
                        if (otherClient !== iface && otherClient.online.get()) {
                            try {
                                otherClient.processOutgoing(data)
                            } catch (e: Exception) {
                                // Client will be cleaned up by its own error handling
                            }
                        }
                    }
                }

                clientInterface.start()
                onClientConnected?.invoke(clientInterface)

                val clientAddr = clientSocket.remoteSocketAddress
                log("Client connected: $clientAddr ($clientName)")

            } catch (e: CancellationException) {
                // Normal cancellation, exit loop
                break
            } catch (e: SocketException) {
                if (!detached.get()) {
                    log("Accept error: ${e.message}")
                }
            } catch (e: Exception) {
                log("Error accepting connection: ${e.message}")
            }
        }
    }

    /**
     * Called by client interface when it disconnects.
     */
    internal fun clientDisconnected(client: TCPServerClientInterface) {
        clients.remove(client)
        spawnedInterfaces?.remove(client)
        onClientDisconnected?.invoke(client)
        log("Client disconnected: ${client.name}")
    }

    /**
     * Send data to all connected clients.
     */
    override fun processOutgoing(data: ByteArray) {
        for (client in clients) {
            try {
                if (client.online.get()) {
                    client.processOutgoing(data)
                }
            } catch (e: Exception) {
                // Client will be cleaned up by its own error handling
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

        // Cancel coroutines first
        acceptJob?.cancel()
        ioScope.cancel()

        // Disconnect all clients
        for (client in clients.toList()) {
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
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
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

    // IFAC: delegate to parent server (same credentials for all clients on this server)
    override val ifacNetname: String? get() = parentServer.ifacNetname
    override val ifacNetkey: String? get() = parentServer.ifacNetkey
    override val ifacSize: Int get() = parentServer.ifacSize
    override val ifacKey: ByteArray? get() = parentServer.ifacKey
    override val ifacIdentity: Identity? get() = parentServer.ifacIdentity

    override val bitrate: Int = TCPServerInterface.BITRATE_GUESS
    override val hwMtu: Int = TCPServerInterface.HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = true

    private val writing = AtomicBoolean(false)

    // Coroutine scope for I/O operations (battery-efficient on Android)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    private val hdlcDeframer = HDLC.createDeframer { data ->
        processIncoming(data)
    }

    private val kissDeframer = KISS.createDeframer { _, data ->
        processIncoming(data)
    }

    override fun start() {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        online.set(true)

        readJob = ioScope.launch {
            readLoop()
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(4096)

        try {
            while (online.get() && !detached.get()) {
                // Blocking read wrapped in IO dispatcher
                val bytesRead = withContext(Dispatchers.IO) {
                    socket.getInputStream().read(buffer)
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
                    break
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation, don't log as error
        } catch (e: IOException) {
            if (!detached.get()) {
                // Client disconnected
            }
        }

        // Clean up
        detach()
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

            val framedData = if (useKissFraming) {
                KISS.frame(data)
            } else {
                HDLC.frame(data)
            }

            socket.getOutputStream().write(framedData)
            socket.getOutputStream().flush()

            log("Sent ${data.size} bytes (framed: ${framedData.size} bytes)")
            txBytes.addAndGet(framedData.size.toLong())
            parentInterface?.txBytes?.addAndGet(framedData.size.toLong())

        } catch (e: IOException) {
            detach()
            throw e
        } finally {
            writing.set(false)
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)

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
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String = "TCPServerClientInterface[$name]"
}
