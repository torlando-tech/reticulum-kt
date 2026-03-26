package network.reticulum.interfaces.nearby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Connections mesh interface for Reticulum networking.
 *
 * Server-style parent interface (like [network.reticulum.interfaces.ble.BLEInterface])
 * that orchestrates discovery, connection, and peer management via Google Nearby
 * Connections (WiFi Direct + BLE). Spawns [NearbyPeerInterface] children for each
 * connected endpoint and registers them with Transport.
 *
 * Key differences from BLEInterface:
 * - No fragmentation: Nearby BYTES payloads handle up to 32KB (Reticulum max ~16KB)
 * - No keepalive: Nearby Connections manages connection health internally
 * - No identity handshake: endpoint names carry RNS identity hash prefix for tie-breaking
 * - No RSSI/MTU negotiation: Nearby Connections handles bandwidth upgrades automatically
 *
 * Architecture:
 * - Dual-role: advertises and discovers simultaneously via P2P_CLUSTER strategy
 * - Deterministic tie-breaking: lower endpoint name initiates connection (prevents dual-connect)
 * - processOutgoing() is a no-op — Transport calls each NearbyPeerInterface directly
 *
 * @param name Human-readable interface name
 * @param driver NearbyDriver implementation (platform-specific)
 * @param localEndpointName Local name for tie-breaking (RNS identity hash prefix)
 * @param maxConnections Maximum simultaneous peer connections (default 10)
 */
class NearbyInterface(
    name: String,
    private val driver: NearbyDriver,
    private val localEndpointName: String,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
) : Interface(name) {
    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 10
        const val BITRATE_ESTIMATE = 2_000_000 // WiFi Direct: ~2 Mbps effective
    }

    override val bitrate: Int = BITRATE_ESTIMATE
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // endpointId -> NearbyPeerInterface
    private val peers = ConcurrentHashMap<String, NearbyPeerInterface>()

    // Endpoints we've already initiated connection to (prevents duplicate requests)
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()

    init {
        spawnedInterfaces = mutableListOf()
    }

    // ---- Lifecycle ----

    override fun start() {
        log("start() called — launching Nearby Connections coroutines")

        scope.launch {
            try {
                driver.start(localEndpointName, maxConnections)
                online.set(true)
                log("Advertising and discovery started (name=$localEndpointName)")
            } catch (e: Exception) {
                online.set(false)
                log("Failed to start: ${e.message}")
            }
        }

        // Event collection coroutines
        scope.launch { collectDiscoveredEndpoints() }
        scope.launch { collectConnectedEndpoints() }
        scope.launch { collectConnectionLost() }
        scope.launch { collectDataReceived() }
    }

    /**
     * No-op for server-style parent interface.
     *
     * Transport calls each spawned [NearbyPeerInterface]'s processOutgoing() directly.
     */
    override fun processOutgoing(data: ByteArray) {
        // No-op: server-style parent
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)
        log("Detaching — shutting down all peers and driver")

        // Tear down all peer interfaces directly (don't call peer.detach() which
        // would re-enter peerDisconnected and double-deregister from Transport)
        for ((_, peer) in peers) {
            peer.online.set(false)
            peer.detached.set(true)
            try {
                Transport.deregisterInterface(peer.toRef())
            } catch (_: Exception) {
            }
        }
        peers.clear()
        spawnedInterfaces?.clear()
        pendingConnections.clear()

        // Shutdown driver and cancel scope
        driver.shutdown()
        scope.cancel()

        log("Detached")
    }

    // ---- Event Collection ----

    /**
     * Handle discovered endpoints with deterministic tie-breaking.
     * The peer with the lexicographically lower endpoint name initiates the connection.
     */
    private suspend fun collectDiscoveredEndpoints() {
        try {
            driver.discoveredEndpoints.collect { endpoint ->
                if (!online.get() || detached.get()) return@collect

                // Skip if already connected or pending
                if (peers.containsKey(endpoint.endpointId)) return@collect
                if (pendingConnections.contains(endpoint.endpointId)) return@collect
                if (peers.size + pendingConnections.size >= maxConnections.coerceAtMost(DEFAULT_MAX_CONNECTIONS)) return@collect

                // Use the driver's tie-breaking decision to track pending state
                if (endpoint.weInitiate) {
                    log(
                        "Initiating connection to ${endpoint.endpointId} " +
                            "(our name=$localEndpointName, theirs=${endpoint.endpointName})",
                    )
                    pendingConnections.add(endpoint.endpointId)
                    // Driver handles the actual requestConnection call internally
                    // The result comes via connectedEndpoints or connectionLost
                } else {
                    log(
                        "Waiting for ${endpoint.endpointId} to initiate " +
                            "(our name=$localEndpointName, theirs=${endpoint.endpointName})",
                    )
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (e: Exception) {
            log("Error collecting discovered endpoints: ${e.message}")
        }
    }

    /**
     * Spawn a [NearbyPeerInterface] for each successfully connected endpoint.
     */
    private suspend fun collectConnectedEndpoints() {
        try {
            driver.connectedEndpoints.collect { endpoint ->
                if (!online.get() || detached.get()) return@collect

                pendingConnections.remove(endpoint.endpointId)

                // Skip if already have a peer for this endpoint
                if (peers.containsKey(endpoint.endpointId)) return@collect

                spawnPeerInterface(endpoint.endpointId, endpoint.endpointName)
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (e: Exception) {
            log("Error collecting connected endpoints: ${e.message}")
        }
    }

    /**
     * Tear down peer interface when an endpoint disconnects.
     */
    private suspend fun collectConnectionLost() {
        try {
            driver.connectionLost.collect { endpointId ->
                pendingConnections.remove(endpointId)
                tearDownPeer(endpointId)
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (e: Exception) {
            log("Error collecting connection lost: ${e.message}")
        }
    }

    /**
     * Route incoming data to the correct [NearbyPeerInterface].
     */
    private suspend fun collectDataReceived() {
        try {
            driver.dataReceived.collect { received ->
                if (!online.get() || detached.get()) return@collect

                val peer = peers[received.endpointId]
                if (peer != null) {
                    peer.deliverIncoming(received.data)
                } else {
                    log("Data from unknown endpoint ${received.endpointId}, ignoring")
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (e: Exception) {
            log("Error collecting received data: ${e.message}")
        }
    }

    // ---- Peer Management ----

    private fun spawnPeerInterface(
        endpointId: String,
        endpointName: String,
    ) {
        val peerName = "Nearby|${endpointId.take(8)}"
        val peer =
            NearbyPeerInterface(
                name = peerName,
                endpointId = endpointId,
                parentNearbyInterface = this,
                driver = driver,
            )

        peers[endpointId] = peer
        spawnedInterfaces?.add(peer)

        // Set up packet callback and register with Transport
        peer.onPacketReceived = { data, fromInterface ->
            Transport.inbound(data, fromInterface.toRef())
        }
        peer.start()
        Transport.registerInterface(peer.toRef())

        log("Spawned peer interface: $peerName ($endpointName), total=${peers.size}")
    }

    private fun tearDownPeer(endpointId: String) {
        val peer = peers.remove(endpointId) ?: return
        spawnedInterfaces?.remove(peer)

        try {
            Transport.deregisterInterface(peer.toRef())
        } catch (_: Exception) {
        }

        // Only detach if not already detached (avoids recursive peerDisconnected call)
        if (!peer.detached.get()) {
            peer.online.set(false)
            peer.detached.set(true)
        }

        log("Tore down peer: ${peer.name}, total=${peers.size}")
    }

    /**
     * Called by [NearbyPeerInterface.detach] to notify parent of disconnection.
     */
    internal fun peerDisconnected(peer: NearbyPeerInterface) {
        peers.remove(peer.endpointId)
        spawnedInterfaces?.remove(peer)
        try {
            Transport.deregisterInterface(peer.toRef())
        } catch (_: Exception) {
        }

        log("Peer disconnected: ${peer.name}, total=${peers.size}")
    }

    /** Get the number of currently connected peers. */
    fun getConnectedCount(): Int = peers.size

    private fun log(message: String) {
        println("[NearbyInterface][$name] $message")
    }

    override fun toString(): String = "NearbyInterface[$name] (${peers.size} peers)"
}
