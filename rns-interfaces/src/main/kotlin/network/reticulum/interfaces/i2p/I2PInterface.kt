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
 * I2P server interface for Reticulum.
 *
 * Port of Python's I2PInterface. This interface creates a localhost TCP server
 * and optionally exposes it via an I2P server tunnel. It also connects to
 * configured peer destinations via I2P client tunnels.
 *
 * Architecture:
 * ```
 * I2P Network ←→ SAM API ←→ localhost:bindPort ←→ I2PInterface (TCP server)
 *                                                       ├── I2PInterfacePeer (spawned for incoming)
 *                                                       └── I2PInterfacePeer (created for configured peers)
 * ```
 *
 * The I2PController manages the SAM tunnel lifecycle. Once tunnels are up,
 * everything is just HDLC-framed TCP through localhost.
 *
 * @param name Interface name.
 * @param storagePath Reticulum storage path for key persistence.
 * @param peers List of remote I2P destinations to connect to.
 * @param connectable Whether to accept incoming I2P connections (server tunnel).
 * @param useKissFraming Use KISS framing instead of HDLC (default false).
 * @param samAddress SAM API address override.
 * @param ifacNetname IFAC network name for isolation.
 * @param ifacNetkey IFAC network passphrase for isolation.
 */
class I2PInterface(
    name: String,
    private val storagePath: String,
    private val peers: List<String> = emptyList(),
    private val connectable: Boolean = false,
    private val useKissFraming: Boolean = false,
    private val samAddress: InetSocketAddress = I2PSamClient.getSamAddress(),
    override val ifacNetname: String? = null,
    override val ifacNetkey: String? = null,
) : Interface(name) {

    companion object {
        const val BITRATE_GUESS = 256_000 // 256 kbps — I2P is slow
        const val HW_MTU = 1064 // Must match Python for interop
    }

    override val bitrate: Int = BITRATE_GUESS
    override val hwMtu: Int = HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = false

    // Discovery support
    override val supportsDiscovery: Boolean = true
    override val discoveryInterfaceType: String = "I2PInterface"
    override fun getDiscoveryData(): Map<Int, Any>? {
        // Only publish reachable_on if connectable and b32 is known
        if (!connectable || b32 == null) return null
        return mapOf(network.reticulum.discovery.DiscoveryConstants.REACHABLE_ON to b32!!)
    }

    // IFAC credentials
    private val _ifacCredentials: IfacCredentials? by lazy {
        IfacUtils.deriveIfacCredentials(ifacNetname, ifacNetkey)
    }
    override val ifacSize: Int get() = if (_ifacCredentials != null) 16 else 0
    override val ifacKey: ByteArray? get() = _ifacCredentials?.key
    override val ifacIdentity: Identity? get() = _ifacCredentials?.identity

    // Server listens on localhost
    override val canReceive: Boolean = true
    override val canSend: Boolean = false // Server delegates to spawned peers
    // I2P interfaces are tunneled
    val i2pTunneled: Boolean = true

    /** The I2P controller managing SAM tunnels. */
    internal val i2pController = I2PController(storagePath, samAddress)

    /** Base32 address if connectable. */
    var b32: String? = null
        internal set

    /** Called when a peer connects. Use to register spawned interface with Transport. */
    var onPeerConnected: ((Interface) -> Unit)? = null

    /** Called when a peer disconnects. Use to deregister spawned interface from Transport. */
    var onPeerDisconnected: ((Interface) -> Unit)? = null

    /** Called for each outbound peer created from the peers list. */
    var onOutboundPeerCreated: ((Interface) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val bindIp = "127.0.0.1"
    private var bindPort: Int = 0

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var tunnelJob: Job? = null

    init {
        spawnedInterfaces = mutableListOf()
    }

    override fun start() {
        // Allocate a free port for the localhost server
        bindPort = I2PSamClient.getFreePort()

        // Start localhost TCP server (accepts tunneled connections from SAM)
        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress(bindIp, bindPort))
            log("Local server listening on $bindIp:$bindPort")
        } catch (e: Exception) {
            log("Failed to start local server: ${e.message}")
            throw e
        }

        // Accept loop for incoming tunneled connections
        acceptJob = ioScope.launch {
            acceptLoop()
        }

        // If connectable, set up the I2P server tunnel
        if (connectable) {
            tunnelJob = ioScope.launch {
                serverTunnelLoop()
            }
        }

        // Connect to configured peers
        for (peerDest in peers) {
            val peerName = "$name to ${peerDest.take(16)}..."
            ioScope.launch {
                createOutboundPeer(peerName, peerDest)
            }
        }

        // We go online even before tunnels are up — the server socket is ready
        // Peers will connect once their tunnels establish
        if (!connectable && peers.isEmpty()) {
            online.set(true)
        }
    }

    /**
     * Accept incoming connections on the localhost server.
     * SAM's STREAM FORWARD directs I2P connections here.
     */
    private suspend fun acceptLoop() {
        while (!detached.get()) {
            try {
                val server = serverSocket ?: break
                val clientSocket = withContext(Dispatchers.IO) {
                    server.accept()
                }

                log("Incoming connection from ${clientSocket.remoteSocketAddress}")
                spawnIncomingPeer(clientSocket)

            } catch (e: CancellationException) {
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
     * Keep the server tunnel alive, retrying on failure.
     */
    private suspend fun serverTunnelLoop() {
        while (!detached.get()) {
            try {
                val addr = withContext(Dispatchers.IO) {
                    i2pController.setupServerTunnel(name, bindPort)
                }
                b32 = addr
                online.set(true)
                log("I2P endpoint ready: $addr.b32.i2p")
                // Tunnel is up — wait until we're detached
                while (!detached.get()) {
                    delay(5000)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log("Server tunnel error: ${e.message}, retrying in 15s...")
                delay(15_000)
            }
        }
    }

    /**
     * Create an outbound peer connection through an I2P client tunnel.
     */
    private suspend fun createOutboundPeer(peerName: String, i2pDestination: String) {
        val peer = I2PInterfacePeer(
            name = peerName,
            parentI2P = this,
            targetI2pDest = i2pDestination,
            useKissFraming = useKissFraming,
            samAddress = samAddress,
        )

        peer.onPacketReceived = { data, iface ->
            onPacketReceived?.invoke(data, iface)
        }

        spawnedInterfaces?.add(peer)
        onOutboundPeerCreated?.invoke(peer)

        // Start the peer (it handles its own tunnel setup and connection)
        withContext(Dispatchers.IO) {
            peer.start()
        }
    }

    /**
     * Spawn a peer interface for an incoming connection.
     */
    private fun spawnIncomingPeer(socket: Socket) {
        val peerName = "I2P peer on $name"
        val peer = I2PInterfacePeer(
            name = peerName,
            parentI2P = this,
            connectedSocket = socket,
            useKissFraming = useKissFraming,
        )

        peer.onPacketReceived = { data, iface ->
            onPacketReceived?.invoke(data, iface)
        }

        // Remove any stale entries first
        spawnedInterfaces?.removeAll { it === peer }
        spawnedInterfaces?.add(peer)

        onPeerConnected?.invoke(peer)
        log("Spawned I2P peer: $peerName")

        // Start reading on the peer
        peer.start()
    }

    /**
     * Called by I2PInterfacePeer when it disconnects.
     */
    internal fun peerDisconnected(peer: I2PInterfacePeer) {
        spawnedInterfaces?.remove(peer)
        onPeerDisconnected?.invoke(peer)
        log("Peer disconnected: ${peer.name}")
    }

    /**
     * Server doesn't send directly — Transport sends through individual peers.
     */
    override fun processOutgoing(data: ByteArray) {
        // No-op: server delegates to spawned interfaces
    }

    override fun detach() {
        super.detach()

        acceptJob?.cancel()
        tunnelJob?.cancel()
        ioScope.cancel()

        // Disconnect all peers
        for (peer in (spawnedInterfaces?.toList() ?: emptyList())) {
            (peer as? I2PInterfacePeer)?.detach()
        }
        spawnedInterfaces?.clear()

        // Stop I2P controller
        i2pController.stop()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        log("I2P interface stopped")
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String {
        val addr = b32?.let { " @ $it.b32.i2p" } ?: ""
        return "I2PInterface[$name$addr]"
    }
}
