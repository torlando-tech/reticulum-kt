package network.reticulum.interfaces.nearby

import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction interface for Google Nearby Connections operations.
 *
 * This defines the contract between pure-JVM protocol logic (in rns-interfaces)
 * and platform-specific Nearby Connections implementation (AndroidNearbyDriver in rns-android).
 *
 * Unlike [network.reticulum.interfaces.ble.BLEDriver], there is no fragmentation, MTU
 * negotiation, or identity handshake at the transport level — Nearby Connections handles
 * transport internally with BYTES payloads (up to 32KB, well above Reticulum's ~16KB max).
 *
 * Connection management uses deterministic tie-breaking: the peer with the lexicographically
 * lower endpoint name initiates the connection to prevent both sides connecting simultaneously.
 *
 * Thread safety: Implementations must be safe to call from any coroutine context.
 */
interface NearbyDriver {
    // ---- Lifecycle ----

    /**
     * Start advertising and discovery simultaneously.
     *
     * @param endpointName Local endpoint name for tie-breaking (e.g., RNS identity hash prefix)
     * @param maxConnections Maximum simultaneous connections to maintain
     */
    suspend fun start(
        endpointName: String,
        maxConnections: Int,
    )

    /**
     * Stop advertising and discovery, disconnect all endpoints.
     */
    suspend fun stop()

    /**
     * Send data to a specific connected endpoint.
     *
     * @param endpointId Target endpoint identifier
     * @param data Bytes to send (max 32KB for BYTES payload)
     */
    fun send(
        endpointId: String,
        data: ByteArray,
    )

    /**
     * Broadcast data to all connected endpoints.
     *
     * @param data Bytes to broadcast
     */
    fun broadcast(data: ByteArray)

    /**
     * Disconnect a specific endpoint.
     */
    fun disconnect(endpointId: String)

    /**
     * Full shutdown — stop and release all resources.
     * After shutdown, the driver cannot be reused.
     */
    fun shutdown()

    // ---- Event Flows ----

    /**
     * Flow of discovered endpoints from Nearby Connections discovery.
     * Emitted when a new endpoint is found. The NearbyInterface uses the endpoint name
     * for deterministic tie-breaking to decide which side initiates the connection.
     */
    val discoveredEndpoints: SharedFlow<DiscoveredEndpoint>

    /**
     * Flow of successfully connected endpoints.
     * Emitted after connection negotiation completes (auto-accepted).
     */
    val connectedEndpoints: SharedFlow<ConnectedEndpoint>

    /**
     * Flow of endpoint IDs for connections that have been lost.
     */
    val connectionLost: SharedFlow<String>

    /**
     * Flow of data received from connected endpoints.
     */
    val dataReceived: SharedFlow<ReceivedData>

    // ---- State ----

    /** Whether the driver is currently running (advertising + discovering). */
    val isRunning: Boolean

    /** Number of currently connected endpoints. */
    val connectedCount: Int
}

/** A nearby endpoint discovered via Nearby Connections. */
data class DiscoveredEndpoint(
    val endpointId: String,
    val endpointName: String,
    /** Whether the driver initiated a connection to this endpoint. */
    val weInitiate: Boolean = false,
)

/** An endpoint that has successfully connected. */
data class ConnectedEndpoint(
    val endpointId: String,
    val endpointName: String,
)

/** Data received from a connected endpoint. */
data class ReceivedData(
    val endpointId: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedData) return false
        return endpointId == other.endpointId && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = endpointId.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
