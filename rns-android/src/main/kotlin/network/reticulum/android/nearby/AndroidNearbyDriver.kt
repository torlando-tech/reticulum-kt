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
 * Deterministic tie-breaking on discovery: the NearbyInterface compares endpoint names and
 * decides which side initiates. This driver exposes discovered endpoints for the interface
 * to make that decision.
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

    private val _connectionLost = MutableSharedFlow<String>(extraBufferCapacity = 16)
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

                _discoveredEndpoints.tryEmit(DiscoveredEndpoint(endpointId, info.endpointName))

                // Deterministic tie-breaking: lower name initiates to prevent dual-connect.
                // On equal names, both sides initiate — Nearby Connections deduplicates
                // the dual requestConnection and one side receives onConnectionInitiated.
                if (localEndpointName <= info.endpointName) {
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
            }
        }

    // ---- NearbyDriver Implementation ----

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
        isRunning = true

        Log.i(TAG, "Starting Nearby Connections (name=$localEndpointName, max=${this.maxConnections})")

        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options =
            AdvertisingOptions
                .Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()

        connectionsClient
            .startAdvertising(localEndpointName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { Log.i(TAG, "Advertising started") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to start advertising", e) }
    }

    private fun startDiscovery() {
        val options =
            DiscoveryOptions
                .Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()

        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Log.i(TAG, "Discovery started") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to start discovery", e) }
    }

    override suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        Log.i(TAG, "Stopping Nearby Connections")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        // Emit connectionLost for all active peers before clearing state
        for (endpointId in _connectedEndpoints.keys()) {
            _connectionLost.tryEmit(endpointId)
        }

        connectionsClient.stopAllEndpoints()
        _connectedEndpoints.clear()
        _pendingConnections.clear()
    }

    override suspend fun send(
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

    override suspend fun broadcast(data: ByteArray) {
        val endpoints = _connectedEndpoints.keys().toList()
        if (endpoints.isEmpty()) return
        connectionsClient.sendPayload(endpoints, Payload.fromBytes(data))
            .addOnFailureListener { e ->
                Log.e(TAG, "broadcast sendPayload failed: ${e.message}")
            }
    }

    override suspend fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        _connectedEndpoints.remove(endpointId)
    }

    override fun shutdown() {
        if (isRunning) {
            isRunning = false
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        }
        _connectedEndpoints.clear()
        _pendingConnections.clear()
        scope.cancel()
        Log.i(TAG, "Nearby driver shut down")
    }
}
