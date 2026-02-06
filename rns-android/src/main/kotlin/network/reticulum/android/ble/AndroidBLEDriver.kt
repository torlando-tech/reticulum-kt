package network.reticulum.android.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.reticulum.interfaces.ble.BLEConstants
import network.reticulum.interfaces.ble.BLEDriver
import network.reticulum.interfaces.ble.BLEPeerConnection
import network.reticulum.interfaces.ble.BleOperationQueue
import network.reticulum.interfaces.ble.DiscoveredPeer

/**
 * Concrete Android implementation of [BLEDriver].
 *
 * This is the single public entry point for the BLE subsystem in the rns-android module.
 * It implements the [BLEDriver] interface from rns-interfaces, delegating to the internal
 * BLE components: [BleGattServer], [BleAdvertiser], [BleScanner], and [BleGattClient].
 *
 * Phase 21's BLEInterface will receive an AndroidBLEDriver instance and interact with it
 * purely through the [BLEDriver] contract, achieving full separation between protocol
 * logic (pure JVM) and platform implementation (Android).
 *
 * Event flows from the four BLE components are aggregated and exposed through the
 * [BLEDriver] interface flows ([discoveredPeers], [incomingConnections], [connectionLost]).
 *
 * @property context Application context
 * @property bluetoothManager Bluetooth manager
 * @property scope Coroutine scope for async operations and event aggregation
 */
class AndroidBLEDriver(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : BLEDriver {

    companion object {
        private const val TAG = "AndroidBLEDriver"
    }

    // ---- Internal BLE components ----

    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val operationQueue = BleOperationQueue(scope)
    private val gattServer = BleGattServer(context, bluetoothManager, scope)
    private val advertiser = BleAdvertiser(context, bluetoothAdapter, scope)
    private val scanner = BleScanner(context, bluetoothAdapter, scope)
    private val gattClient = BleGattClient(context, bluetoothAdapter, operationQueue, scope)

    // ---- Active peer connections ----

    private val peerConnections = mutableMapOf<String, AndroidBLEPeerConnection>()
    private val peersMutex = Mutex()

    // ---- Event flows ----

    private val _discoveredPeers = MutableSharedFlow<DiscoveredPeer>(extraBufferCapacity = 64)
    override val discoveredPeers: SharedFlow<DiscoveredPeer> = _discoveredPeers.asSharedFlow()

    private val _incomingConnections = MutableSharedFlow<BLEPeerConnection>(extraBufferCapacity = 16)
    override val incomingConnections: SharedFlow<BLEPeerConnection> = _incomingConnections.asSharedFlow()

    private val _connectionLost = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val connectionLost: SharedFlow<String> = _connectionLost.asSharedFlow()

    // ---- State ----

    override val localAddress: String?
        get() = try {
            @Suppress("MissingPermission")
            bluetoothAdapter.address
        } catch (_: SecurityException) {
            null
        }

    @Volatile
    private var _isRunning: Boolean = false
    override val isRunning: Boolean get() = _isRunning

    // ---- Event flow aggregation ----

    init {
        // Scanner -> discoveredPeers
        scope.launch {
            scanner.discoveredPeers.collect { peer ->
                _discoveredPeers.tryEmit(peer)
            }
        }

        // GattServer centralConnected -> incomingConnections
        scope.launch {
            gattServer.centralConnected.collect { address ->
                val peerConn = AndroidBLEPeerConnection(address, isOutgoing = false)
                peersMutex.withLock { peerConnections[address] = peerConn }
                _incomingConnections.tryEmit(peerConn)
            }
        }

        // GattServer centralDisconnected -> connectionLost
        scope.launch {
            gattServer.centralDisconnected.collect { address ->
                peersMutex.withLock { peerConnections.remove(address) }
                _connectionLost.tryEmit(address)
            }
        }

        // GattClient disconnected -> connectionLost
        scope.launch {
            gattClient.disconnected.collect { address ->
                peersMutex.withLock { peerConnections.remove(address) }
                _connectionLost.tryEmit(address)
            }
        }

        // GattServer dataReceived -> route to peer connection
        scope.launch {
            gattServer.dataReceived.collect { (address, data) ->
                val peerConn = peersMutex.withLock { peerConnections[address] }
                peerConn?.emitFragment(data)
            }
        }

        // GattClient dataReceived -> route to peer connection
        scope.launch {
            gattClient.dataReceived.collect { (address, data) ->
                val peerConn = peersMutex.withLock { peerConnections[address] }
                peerConn?.emitFragment(data)
            }
        }
    }

    // ========== BLEDriver Interface Implementation ==========

    override suspend fun startAdvertising() {
        gattServer.open().getOrThrow()
        advertiser.startAdvertising().getOrThrow()
        _isRunning = true
    }

    override suspend fun stopAdvertising() {
        advertiser.stopAdvertising()
        gattServer.close()
    }

    override suspend fun startScanning() {
        scanner.startScanning().getOrThrow()
        _isRunning = true
    }

    override suspend fun stopScanning() {
        scanner.stopScanning()
    }

    override suspend fun connect(address: String): BLEPeerConnection {
        gattClient.connect(address).getOrThrow()
        // Wait for the connected event to know setup is complete
        val (connAddress, _) = gattClient.connected.first { it.first == address }
        val peerConn = AndroidBLEPeerConnection(connAddress, isOutgoing = true)
        peersMutex.withLock { peerConnections[connAddress] = peerConn }
        return peerConn
    }

    override suspend fun disconnect(address: String) {
        val peerConn = peersMutex.withLock { peerConnections.remove(address) }
        if (peerConn != null) {
            peerConn.close()
        } else {
            gattClient.disconnect(address)
            gattServer.disconnectCentral(address)
        }
    }

    override fun shutdown() {
        scanner.shutdown()
        advertiser.shutdown()
        gattClient.shutdown()
        gattServer.shutdown()
        operationQueue.shutdown()
        scope.cancel()
        _isRunning = false
    }

    // ========== Additional Public API (for Phase 21) ==========

    /**
     * Set the local transport identity hash on the GATT server.
     *
     * Called by BLEInterface/Transport after Reticulum initialization.
     * The identity is served to centrals via the Identity GATT characteristic.
     *
     * @param identityHash 16-byte Reticulum Transport identity hash
     */
    fun setTransportIdentity(identityHash: ByteArray) {
        gattServer.setTransportIdentity(identityHash)
    }

    /**
     * Get the [BLEPeerConnection] for a given address, if it exists.
     *
     * @param address BLE MAC address
     * @return The active peer connection, or null if not connected
     */
    suspend fun getPeerConnection(address: String): BLEPeerConnection? {
        return peersMutex.withLock { peerConnections[address] }
    }

    /**
     * Restart advertising if it was silently stopped (OEM workaround).
     *
     * Some chipsets silently stop advertising when a connection is established.
     * Call this after connection events to ensure advertising remains active.
     */
    fun restartAdvertisingIfNeeded() {
        advertiser.restartIfNeeded()
    }

    // ========== AndroidBLEPeerConnection ==========

    /**
     * Wraps connection state from either [BleGattClient] (outgoing) or [BleGattServer] (incoming),
     * implementing the [BLEPeerConnection] interface.
     *
     * @property address BLE MAC address of the remote peer
     * @property isOutgoing true if we are central (connected via gattClient), false if peripheral
     */
    private inner class AndroidBLEPeerConnection(
        override val address: String,
        private val isOutgoing: Boolean,
    ) : BLEPeerConnection {

        override val mtu: Int
            get() = runBlocking {
                if (isOutgoing) {
                    gattClient.getMtu(address) ?: BLEConstants.DEFAULT_MTU
                } else {
                    gattServer.getMtu(address) ?: BLEConstants.DEFAULT_MTU
                }
            }

        override var identity: ByteArray? = null

        private val _receivedFragments = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val receivedFragments: SharedFlow<ByteArray> = _receivedFragments.asSharedFlow()

        /**
         * Emit a received fragment into this connection's [receivedFragments] flow.
         * Called by the event aggregation collectors in AndroidBLEDriver.
         */
        fun emitFragment(data: ByteArray) {
            _receivedFragments.tryEmit(data)
        }

        override suspend fun sendFragment(data: ByteArray) {
            if (isOutgoing) {
                gattClient.sendData(address, data).getOrThrow()
            } else {
                val sent = gattServer.sendNotificationToAddress(address, data)
                if (!sent) throw IllegalStateException("Failed to send notification to $address")
            }
        }

        override suspend fun readIdentity(): ByteArray {
            check(isOutgoing) { "readIdentity only supported on outgoing (central) connections" }
            return gattClient.readCharacteristic(address, BLEConstants.IDENTITY_CHAR_UUID)
        }

        override suspend fun writeIdentity(identity: ByteArray) {
            check(isOutgoing) { "writeIdentity only supported on outgoing (central) connections" }
            gattClient.sendData(address, identity).getOrThrow()
        }

        override fun close() {
            scope.launch {
                try {
                    if (isOutgoing) {
                        gattClient.disconnect(address)
                    } else {
                        gattServer.disconnectCentral(address)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing peer connection to $address", e)
                }
            }
        }
    }
}
