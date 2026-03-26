package network.reticulum.android.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import network.reticulum.interfaces.nearby.ConnectedEndpoint
import network.reticulum.interfaces.nearby.DiscoveredEndpoint
import network.reticulum.interfaces.nearby.NearbyDriver
import network.reticulum.interfaces.nearby.ReceivedData
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [NearbyDriver] wrapping Google Nearby Connections API.
 *
 * Uses P2P_CLUSTER strategy for mesh-compatible topology (vs P2P_STAR which forces hub-spoke).
 * Auto-accepts all connections since Reticulum handles authentication at the protocol level.
 *
 * The driver is the single source of truth for all connection state — tie-breaking, connection
 * limits, and pending/connected tracking. The interface layer reacts to driver events only.
 *
 * @param context Android application context
 * @param scope CoroutineScope for the driver's internal operations
 */
class AndroidNearbyDriver(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : NearbyDriver {
    companion object {
        private const val TAG = "Carina:Nearby:Driver"
        const val SERVICE_ID = "network.reticulum.nearby"
    }

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // Connected endpoints: endpointId -> endpointName
    private val _connectedEndpoints = ConcurrentHashMap<String, String>()

    // Pending connections: endpointId -> endpointName (initiated but not yet resolved)
    private val _pendingConnections = ConcurrentHashMap<String, String>()

    @Volatile
    private var localEndpointName: String = ""

    @Volatile
    private var maxConnections: Int = 10

    @Volatile
    override var isRunning: Boolean = false
        private set

    override val connectedCount: Int get() = _connectedEndpoints.size

    // ---- Event Flows ----

    private val _discoveredEndpoints = MutableSharedFlow<DiscoveredEndpoint>(extraBufferCapacity = 64)
    override val discoveredEndpoints: SharedFlow<DiscoveredEndpoint> = _discoveredEndpoints.asSharedFlow()

    private val _connectedEndpointsFlow = MutableSharedFlow<ConnectedEndpoint>(extraBufferCapacity = 16)
    override val connectedEndpoints: SharedFlow<ConnectedEndpoint> = _connectedEndpointsFlow.asSharedFlow()

    private val _connectionLost = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val connectionLost: SharedFlow<String> = _connectionLost.asSharedFlow()

    private val _dataReceived = MutableSharedFlow<ReceivedData>(extraBufferCapacity = 256)
    override val dataReceived: SharedFlow<ReceivedData> = _dataReceived.asSharedFlow()

    // ---- Nearby Connections Callbacks ----

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String,
                info: ConnectionInfo,
            ) {
                Log.i(TAG, "Connection initiated from $endpointId (${info.endpointName})")
                if (_connectedEndpoints.size + _pendingConnections.size >= maxConnections) {
                    Log.w(TAG, "At max connections ($maxConnections), rejecting inbound $endpointId")
                    connectionsClient.rejectConnection(endpointId)
                    return
                }
                // Auto-accept: Reticulum handles authentication at the protocol layer
                _pendingConnections[endpointId] = info.endpointName
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution,
            ) {
                val name = _pendingConnections.remove(endpointId) ?: "unknown"
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        Log.i(TAG, "Connected to $endpointId ($name)")
                        _connectedEndpoints[endpointId] = name
                        _connectedEndpointsFlow.tryEmit(ConnectedEndpoint(endpointId, name))
                    }
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                        Log.w(TAG, "Connection rejected by $endpointId")
                        _connectionLost.tryEmit(endpointId)
                    }
                    ConnectionsStatusCodes.STATUS_ERROR -> {
                        Log.e(TAG, "Connection error with $endpointId: ${result.status.statusMessage}")
                        _connectionLost.tryEmit(endpointId)
                    }
                    else -> {
                        Log.w(TAG, "Connection to $endpointId failed: ${result.status}")
                        _connectionLost.tryEmit(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "Disconnected from $endpointId")
                _connectedEndpoints.remove(endpointId)
                _connectionLost.tryEmit(endpointId)
            }
        }

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload,
            ) {
                if (payload.type == Payload.Type.BYTES) {
                    payload.asBytes()?.let { data ->
                        _dataReceived.tryEmit(ReceivedData(endpointId, data))
                    }
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) {
                // Only relevant for STREAM/FILE payloads; BYTES are atomic
            }
        }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo,
            ) {
                Log.i(TAG, "Discovered endpoint $endpointId (${info.endpointName})")

                // Skip if already connected or at limit
                if (_connectedEndpoints.containsKey(endpointId)) return
                if (_connectedEndpoints.size + _pendingConnections.size >= maxConnections) {
                    Log.d(TAG, "At max connections ($maxConnections), skipping $endpointId")
                    return
                }
                if (_pendingConnections.containsKey(endpointId)) return

                // Deterministic tie-breaking: lower name initiates to prevent dual-connect.
                // On equal names, both sides initiate — Nearby randomly fails one side's
                // requestConnection (handled by addOnFailureListener), then the surviving
                // request causes both devices to receive onConnectionInitiated.
                val weInitiate = localEndpointName <= info.endpointName
                _discoveredEndpoints.tryEmit(DiscoveredEndpoint(endpointId, info.endpointName, weInitiate))

                if (weInitiate) {
                    Log.d(TAG, "Initiating connection to $endpointId (we initiate)")
                    _pendingConnections[endpointId] = info.endpointName
                    connectionsClient
                        .requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to request connection to $endpointId", e)
                            _pendingConnections.remove(endpointId)
                            _connectionLost.tryEmit(endpointId)
                        }
                } else {
                    Log.d(TAG, "Waiting for $endpointId to initiate (their name < ours)")
                }
            }

            override fun onEndpointLost(endpointId: String) {
                Log.d(TAG, "Endpoint lost: $endpointId")
                if (_pendingConnections.remove(endpointId) != null) {
                    Log.d(TAG, "Cleared pending connection for lost endpoint $endpointId")
                    _connectionLost.tryEmit(endpointId)
                }
            }
        }

    // ---- NearbyDriver Implementation ----

    /**
     * Start advertising and discovery. Suspends until both are confirmed started.
     * Throws on failure so the caller can set online=false.
     */
    override suspend fun start(
        endpointName: String,
        maxConnections: Int,
    ) {
        if (isRunning) {
            Log.w(TAG, "Already running, ignoring start()")
            return
        }

        localEndpointName = endpointName
        val clamped = maxConnections.coerceIn(1, 10)
        if (clamped != maxConnections) {
            Log.w(TAG, "maxConnections clamped from $maxConnections to $clamped (driver limit: 1..10)")
        }
        this.maxConnections = clamped

        Log.i(TAG, "Starting Nearby Connections (name=$localEndpointName, max=${this.maxConnections})")

        try {
            val advOptions = AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
            connectionsClient
                .startAdvertising(localEndpointName, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .await()
            Log.i(TAG, "Advertising started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            throw e
        }

        try {
            val discOptions = DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
            connectionsClient
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                .await()
            Log.i(TAG, "Discovery started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery, stopping advertising", e)
            connectionsClient.stopAdvertising()
            throw e
        }

        isRunning = true
    }

    override suspend fun stop() {
        isRunning = false

        Log.i(TAG, "Stopping Nearby Connections")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        // Emit connectionLost for pending connections (won't get onDisconnected callbacks)
        for (endpointId in _pendingConnections.keys()) {
            _connectionLost.tryEmit(endpointId)
        }
        _pendingConnections.clear()

        // stopAllEndpoints triggers onDisconnected for each connected endpoint,
        // which emits connectionLost and removes from _connectedEndpoints
        connectionsClient.stopAllEndpoints()
    }

    override fun send(
        endpointId: String,
        data: ByteArray,
    ) {
        if (!_connectedEndpoints.containsKey(endpointId)) {
            Log.w(TAG, "Cannot send to disconnected endpoint $endpointId")
            return
        }
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(data))
            .addOnFailureListener { e ->
                Log.e(TAG, "sendPayload to $endpointId failed: ${e.message}")
            }
    }

    override fun broadcast(data: ByteArray) {
        val endpoints = _connectedEndpoints.keys().toList()
        if (endpoints.isEmpty()) return
        connectionsClient.sendPayload(endpoints, Payload.fromBytes(data))
            .addOnFailureListener { e ->
                Log.e(TAG, "broadcast sendPayload failed: ${e.message}")
            }
    }

    override fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        // onDisconnected callback handles _connectedEndpoints removal and connectionLost emission
    }

    override fun shutdown() {
        isRunning = false
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _connectedEndpoints.clear()
        _pendingConnections.clear()
        scope.cancel()
        Log.i(TAG, "Nearby driver shut down")
    }
}
