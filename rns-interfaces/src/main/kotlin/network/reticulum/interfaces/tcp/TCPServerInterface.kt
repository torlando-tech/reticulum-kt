package network.reticulum.interfaces.tcp

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
import kotlin.concurrent.thread

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
    private val maxClients: Int = 64
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 10_000_000 // 10 Mbps
        const val HW_MTU = 262144
    }

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU
    // Server can receive/send via its spawned client interfaces
    // processOutgoing broadcasts to all connected clients
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    private var serverSocket: ServerSocket? = null
    private val clientCounter = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<TCPServerClientInterface>()
    private var acceptThread: Thread? = null

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

            acceptThread = thread(name = "TCPServer-$name-accept", isDaemon = true) {
                acceptLoop()
            }
        } catch (e: Exception) {
            log("Failed to start server: ${e.message}")
            throw e
        }
    }

    private fun acceptLoop() {
        while (online.get() && !detached.get()) {
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

                val clientAddr = clientSocket.remoteSocketAddress
                log("Client connected: $clientAddr ($clientName)")

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

    override val bitrate: Int = TCPServerInterface.BITRATE_GUESS
    override val hwMtu: Int = TCPServerInterface.HW_MTU

    private val writing = AtomicBoolean(false)
    private var readThread: Thread? = null

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

        readThread = thread(name = "TCPServerClient-$name-read", isDaemon = true) {
            readLoop()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)

        try {
            while (online.get() && !detached.get()) {
                val bytesRead = socket.getInputStream().read(buffer)

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

        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }

        parentServer.clientDisconnected(this)
    }

    override fun toString(): String = "TCPServerClientInterface[$name]"
}
