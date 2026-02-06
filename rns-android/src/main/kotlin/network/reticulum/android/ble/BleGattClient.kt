package network.reticulum.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.ble.BLEConstants
import network.reticulum.interfaces.ble.BleOperationQueue
import java.util.UUID

/**
 * BLE GATT client for central-mode operations.
 *
 * Connects to peripherals advertising the Reticulum service, discovers the GATT service,
 * negotiates MTU, enables TX notifications via CCCD, and provides data send/receive.
 * All GATT operations are serialized through a [BleOperationQueue] to prevent Android BLE
 * stack silent failures from concurrent operations.
 *
 * GATT error 133 is handled with full close + fresh connectGatt using exponential backoff
 * (1s, 2s, 4s, 8s, 16s) with a maximum of [MAX_RETRIES] attempts before temporary blacklisting.
 *
 * This class handles only the BLE transport layer. Identity handshake, keepalive, MAC sorting,
 * and Transport integration are the concern of Phase 21's BLEInterface.
 *
 * @property context Application context
 * @property bluetoothAdapter Bluetooth adapter
 * @property operationQueue Shared operation queue for GATT serialization
 * @property scope Coroutine scope for async operations
 */
internal class BleGattClient(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val operationQueue: BleOperationQueue,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "BleGattClient"
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val GATT_ERROR_133 = 133
    }

    // ---- Per-connection state ----

    private data class ConnectionData(
        val gatt: BluetoothGatt,
        val address: String,
        var mtu: Int = BLEConstants.MIN_MTU,
        var rxCharacteristic: BluetoothGattCharacteristic? = null,
        var txCharacteristic: BluetoothGattCharacteristic? = null,
        var identityCharacteristic: BluetoothGattCharacteristic? = null,
        var retryCount: Int = 0,
        var connectionJob: Job? = null,
        var setupComplete: Boolean = false,
    )

    private val connections = mutableMapOf<String, ConnectionData>()
    private val connectionsMutex = Mutex()

    // ---- Pending deferreds for GATT callback bridge ----
    // Only one active at a time per operation type (serialized via operationQueue)

    @Volatile
    private var pendingServiceDiscovery: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingMtuDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var pendingWriteDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var pendingReadDeferred: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var pendingDescriptorWriteDeferred: CompletableDeferred<Int>? = null

    // ---- Temporary blacklist ----

    private val blacklistedAddresses = mutableMapOf<String, Long>()
    private val blacklistDurationMs = 60_000L

    // ---- Event flows ----

    private val _connected = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)
    /** Emits (address, usableMtu) when connection setup completes successfully. */
    val connected: SharedFlow<Pair<String, Int>> = _connected.asSharedFlow()

    private val _disconnected = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits address when a connection is lost or closed. */
    val disconnected: SharedFlow<String> = _disconnected.asSharedFlow()

    private val _connectionFailed = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    /** Emits (address, reason) when a connection attempt fails. */
    val connectionFailed: SharedFlow<Pair<String, String>> = _connectionFailed.asSharedFlow()

    private val _dataReceived = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 16)
    /** Emits (address, data) when data is received via TX characteristic notifications. */
    val dataReceived: SharedFlow<Pair<String, ByteArray>> = _dataReceived.asSharedFlow()

    private val _mtuChanged = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)
    /** Emits (address, usableMtu) when MTU is negotiated. */
    val mtuChanged: SharedFlow<Pair<String, Int>> = _mtuChanged.asSharedFlow()

    // ---- GATT Callback ----

    /**
     * Per-connection GATT callback. All callbacks dispatch to [scope] to avoid blocking
     * the Android binder thread.
     */
    private inner class GattCallback(private val address: String) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when {
                    status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to $address")
                        // Cancel the connection timeout job
                        connectionsMutex.withLock {
                            connections[address]?.connectionJob?.cancel()
                            connections[address]?.connectionJob = null
                        }
                        // Request high connection priority for faster throughput
                        try {
                            if (hasConnectPermission()) {
                                withContext(Dispatchers.Main) {
                                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Permission denied requesting connection priority", e)
                        }
                        // Start the post-connect setup sequence
                        setupConnection(address, gatt)
                    }

                    status == GATT_ERROR_133 -> {
                        handleError133(address, gatt)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from $address (status: $status)")
                        connectionsMutex.withLock {
                            val conn = connections.remove(address)
                            conn?.connectionJob?.cancel()
                        }
                        withContext(Dispatchers.Main) {
                            try {
                                gatt.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error closing gatt for $address", e)
                            }
                        }
                        _disconnected.tryEmit(address)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                pendingServiceDiscovery?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val usableMtu = mtu - 3
                    connectionsMutex.withLock {
                        connections[address]?.mtu = usableMtu
                    }
                    _mtuChanged.tryEmit(address to usableMtu)
                }
                pendingMtuDeferred?.complete(mtu)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            scope.launch {
                if (characteristic.uuid == BLEConstants.TX_CHAR_UUID) {
                    _dataReceived.tryEmit(address to value)
                }
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Pre-API 33 callback: read value from characteristic object
            scope.launch {
                if (characteristic.uuid == BLEConstants.TX_CHAR_UUID) {
                    val value = characteristic.value
                    if (value != null) {
                        _dataReceived.tryEmit(address to value)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                pendingWriteDeferred?.complete(status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingReadDeferred?.complete(value)
                } else {
                    pendingReadDeferred?.completeExceptionally(
                        IllegalStateException("Characteristic read failed: status=$status"),
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Pre-API 33 callback: read value from characteristic object
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val value = characteristic.value
                    if (value != null) {
                        pendingReadDeferred?.complete(value)
                    } else {
                        pendingReadDeferred?.completeExceptionally(
                            IllegalStateException("Characteristic read returned null value"),
                        )
                    }
                } else {
                    pendingReadDeferred?.completeExceptionally(
                        IllegalStateException("Characteristic read failed: status=$status"),
                    )
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            scope.launch {
                pendingDescriptorWriteDeferred?.complete(status)
            }
        }
    }

    // ========== Public API ==========

    /**
     * Initiate a GATT connection to a peripheral.
     *
     * This returns once the [BluetoothDevice.connectGatt] call is made. The actual
     * connection setup (service discovery, MTU negotiation, CCCD write) happens
     * asynchronously via [setupConnection]. Subscribe to [connected] to know when
     * the connection is fully ready.
     *
     * @param address BLE MAC address of the peripheral
     * @param retryCount Internal retry counter (used by error 133 handler)
     * @return Result indicating whether the connect call was initiated
     */
    suspend fun connect(address: String, retryCount: Int = 0): Result<Unit> {
        if (!hasConnectPermission()) {
            return Result.failure(SecurityException("Missing BLUETOOTH_CONNECT permission"))
        }

        if (isBlacklisted(address)) {
            return Result.failure(
                IllegalStateException("Address $address is temporarily blacklisted"),
            )
        }

        // Check if already connected
        connectionsMutex.withLock {
            if (connections.containsKey(address)) {
                return Result.success(Unit)
            }
        }

        return try {
            val device = bluetoothAdapter.getRemoteDevice(address)

            val gatt = withContext(Dispatchers.Main) {
                device.connectGatt(
                    context,
                    false, // autoConnect=false for direct connection
                    GattCallback(address),
                    BluetoothDevice.TRANSPORT_LE,
                )
            }

            if (gatt == null) {
                return Result.failure(
                    IllegalStateException("connectGatt returned null for $address"),
                )
            }

            // Start connection timeout job
            val timeoutJob = scope.launch {
                delay(BLEConstants.CONNECTION_TIMEOUT_MS)
                // If still in connections and setup not complete, timeout
                val conn = connectionsMutex.withLock { connections[address] }
                if (conn != null && !conn.setupComplete) {
                    Log.e(TAG, "Connection timeout for $address")
                    connectionsMutex.withLock { connections.remove(address) }
                    withContext(Dispatchers.Main) {
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing timed-out connection", e)
                        }
                    }
                    _connectionFailed.tryEmit(address to "Connection timeout")
                }
            }

            connectionsMutex.withLock {
                connections[address] = ConnectionData(
                    gatt = gatt,
                    address = address,
                    retryCount = retryCount,
                    connectionJob = timeoutJob,
                )
            }

            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied connecting to $address", e)
            Result.failure(SecurityException("Bluetooth permission required", e))
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $address", e)
            Result.failure(e)
        }
    }

    /**
     * Disconnect from a peripheral and clean up resources.
     *
     * @param address BLE MAC address of the peripheral
     */
    suspend fun disconnect(address: String) {
        val conn = connectionsMutex.withLock { connections.remove(address) }
        if (conn != null) {
            conn.connectionJob?.cancel()
            try {
                withContext(Dispatchers.Main) {
                    if (hasConnectPermission()) {
                        conn.gatt.disconnect()
                        conn.gatt.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from $address", e)
            }
            _disconnected.tryEmit(address)
        }
    }

    /**
     * Send data to a peripheral by writing to the RX characteristic.
     *
     * The write is serialized through [operationQueue] and uses a [CompletableDeferred]
     * to bridge the GATT write callback.
     *
     * @param address BLE MAC address of the peripheral
     * @param data The data to send
     * @return Result indicating success or failure
     */
    suspend fun sendData(address: String, data: ByteArray): Result<Unit> {
        val conn = connectionsMutex.withLock { connections[address] }
            ?: return Result.failure(IllegalStateException("Not connected to $address"))

        val rxChar = conn.rxCharacteristic
            ?: return Result.failure(IllegalStateException("RX characteristic not found for $address"))

        return try {
            operationQueue.enqueue(timeoutMs = BLEConstants.OPERATION_TIMEOUT_MS) {
                val deferred = CompletableDeferred<Int>()
                pendingWriteDeferred = deferred

                withContext(Dispatchers.Main) {
                    if (!hasConnectPermission()) {
                        throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                    }
                    writeCharacteristicCompat(conn.gatt, rxChar, data)
                }

                val status = deferred.await()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("Characteristic write failed: status=$status")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data to $address", e)
            Result.failure(e)
        }
    }

    /**
     * Read a characteristic value from a peripheral.
     *
     * The read is serialized through [operationQueue] and uses a [CompletableDeferred]
     * to bridge the GATT read callback.
     *
     * @param address BLE MAC address of the peripheral
     * @param characteristicUuid UUID of the characteristic to read
     * @return The characteristic value
     * @throws IllegalStateException if not connected or characteristic not found
     */
    suspend fun readCharacteristic(address: String, characteristicUuid: UUID): ByteArray {
        val conn = connectionsMutex.withLock { connections[address] }
            ?: throw IllegalStateException("Not connected to $address")

        val service = conn.gatt.getService(BLEConstants.SERVICE_UUID)
            ?: throw IllegalStateException("Reticulum service not found for $address")

        val characteristic = service.getCharacteristic(characteristicUuid)
            ?: throw IllegalStateException("Characteristic $characteristicUuid not found for $address")

        return operationQueue.enqueue(timeoutMs = BLEConstants.OPERATION_TIMEOUT_MS) {
            val deferred = CompletableDeferred<ByteArray>()
            pendingReadDeferred = deferred

            withContext(Dispatchers.Main) {
                if (!hasConnectPermission()) {
                    throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                }
                conn.gatt.readCharacteristic(characteristic)
            }

            deferred.await()
        }
    }

    /**
     * Get the usable MTU for a connected peripheral.
     *
     * The usable MTU is the negotiated ATT MTU minus 3 bytes (ATT header).
     *
     * @param address BLE MAC address of the peripheral
     * @return The usable MTU, or null if the device is not connected
     */
    suspend fun getMtu(address: String): Int? {
        return connectionsMutex.withLock { connections[address]?.mtu }
    }

    /**
     * Check whether a device is currently connected.
     *
     * @param address BLE MAC address to check
     * @return true if connected (setup may or may not be complete)
     */
    suspend fun isConnected(address: String): Boolean {
        return connectionsMutex.withLock { connections.containsKey(address) }
    }

    /**
     * Shutdown the GATT client. Disconnects all peripherals, cancels scope.
     *
     * After shutdown, this instance cannot be reused.
     */
    fun shutdown() {
        // Disconnect all connections (best-effort, non-suspend)
        val allConns = connections.toMap()
        connections.clear()
        for ((_, conn) in allConns) {
            conn.connectionJob?.cancel()
            try {
                if (hasConnectPermission()) {
                    conn.gatt.disconnect()
                    conn.gatt.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting during shutdown: ${conn.address}", e)
            }
        }
        scope.cancel()
    }

    // ========== Connection Setup ==========

    /**
     * Post-connect setup sequence: discover services, request MTU, enable notifications.
     *
     * All GATT operations go through [operationQueue] to serialize with any other connections.
     * If any step fails, the connection is torn down and [connectionFailed] is emitted.
     */
    private suspend fun setupConnection(address: String, gatt: BluetoothGatt) {
        try {
            // 1. Discover services
            operationQueue.enqueue(timeoutMs = BLEConstants.OPERATION_TIMEOUT_MS) {
                val deferred = CompletableDeferred<Boolean>()
                pendingServiceDiscovery = deferred
                withContext(Dispatchers.Main) {
                    if (!hasConnectPermission()) {
                        throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                    }
                    gatt.discoverServices()
                }
                val success = deferred.await()
                if (!success) throw IllegalStateException("Service discovery failed")
            }

            // 2. Find Reticulum service and characteristics
            val service = gatt.getService(BLEConstants.SERVICE_UUID)
                ?: throw IllegalStateException("Reticulum service not found on $address")
            val rxChar = service.getCharacteristic(BLEConstants.RX_CHAR_UUID)
                ?: throw IllegalStateException("RX characteristic not found on $address")
            val txChar = service.getCharacteristic(BLEConstants.TX_CHAR_UUID)
                ?: throw IllegalStateException("TX characteristic not found on $address")
            val identityChar = service.getCharacteristic(BLEConstants.IDENTITY_CHAR_UUID)

            connectionsMutex.withLock {
                connections[address]?.apply {
                    rxCharacteristic = rxChar
                    txCharacteristic = txChar
                    identityCharacteristic = identityChar
                }
            }

            // 3. Request MTU 517
            operationQueue.enqueue(timeoutMs = BLEConstants.OPERATION_TIMEOUT_MS) {
                val deferred = CompletableDeferred<Int>()
                pendingMtuDeferred = deferred
                withContext(Dispatchers.Main) {
                    if (!hasConnectPermission()) {
                        throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                    }
                    gatt.requestMtu(BLEConstants.MAX_MTU)
                }
                deferred.await() // MTU value stored via onMtuChanged callback
            }

            // 4. Enable TX notifications locally
            withContext(Dispatchers.Main) {
                if (!hasConnectPermission()) {
                    throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                }
                gatt.setCharacteristicNotification(txChar, true)
            }

            // 5. Write CCCD descriptor to enable remote notifications
            val cccd = txChar.getDescriptor(BLEConstants.CCCD_UUID)
                ?: throw IllegalStateException("CCCD descriptor not found on TX characteristic for $address")

            operationQueue.enqueue(timeoutMs = BLEConstants.OPERATION_TIMEOUT_MS) {
                val deferred = CompletableDeferred<Int>()
                pendingDescriptorWriteDeferred = deferred
                withContext(Dispatchers.Main) {
                    if (!hasConnectPermission()) {
                        throw SecurityException("Missing BLUETOOTH_CONNECT permission")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
                val status = deferred.await()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("CCCD write failed: status=$status")
                }
            }

            // Connection setup complete
            val usableMtu = connectionsMutex.withLock {
                connections[address]?.setupComplete = true
                connections[address]?.mtu ?: BLEConstants.MIN_MTU
            }
            Log.d(TAG, "Connection setup complete for $address (mtu=$usableMtu)")
            _connected.tryEmit(address to usableMtu)
        } catch (e: Exception) {
            Log.e(TAG, "Connection setup failed for $address", e)
            connectionsMutex.withLock { connections.remove(address) }
            try {
                withContext(Dispatchers.Main) {
                    if (hasConnectPermission()) {
                        gatt.disconnect()
                        gatt.close()
                    }
                }
            } catch (closeEx: Exception) {
                Log.e(TAG, "Error closing failed connection for $address", closeEx)
            }
            _connectionFailed.tryEmit(address to (e.message ?: "Setup failed"))
        }
    }

    // ========== GATT Error 133 Handling ==========

    /**
     * Handle GATT error 133: full close + fresh connectGatt with exponential backoff.
     *
     * Error 133 is Android's generic GATT failure. The only reliable recovery is to
     * fully close the BluetoothGatt object (without disconnect) and retry with a fresh
     * connectGatt call. Backoff: 1s, 2s, 4s, 8s, 16s.
     */
    private suspend fun handleError133(address: String, gatt: BluetoothGatt) {
        Log.e(TAG, "GATT error 133 for $address")

        // Full teardown: close immediately (no gatt.disconnect() per CONTEXT.md)
        withContext(Dispatchers.Main) {
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing gatt after 133 for $address", e)
            }
        }

        val retryCount = connectionsMutex.withLock {
            val conn = connections.remove(address)
            conn?.connectionJob?.cancel()
            conn?.retryCount ?: 0
        }

        if (retryCount < MAX_RETRIES) {
            val nextRetry = retryCount + 1
            val backoffMs = INITIAL_BACKOFF_MS * (1L shl retryCount) // 1s, 2s, 4s, 8s, 16s
            Log.d(TAG, "Retrying $address (attempt $nextRetry/$MAX_RETRIES) in ${backoffMs}ms")

            scope.launch {
                delay(backoffMs)
                val result = connect(address, retryCount = nextRetry)
                if (result.isFailure) {
                    _connectionFailed.tryEmit(
                        address to "GATT 133: retry $nextRetry failed: ${result.exceptionOrNull()?.message}",
                    )
                }
            }
        } else {
            Log.e(TAG, "Max retries exceeded for $address, blacklisting temporarily")
            blacklistedAddresses[address] = System.currentTimeMillis()
            _connectionFailed.tryEmit(address to "GATT 133: max retries exceeded, blacklisted")
        }
    }

    // ========== Blacklist ==========

    /**
     * Check if an address is temporarily blacklisted due to repeated GATT error 133.
     * Blacklist expires after [blacklistDurationMs] (1 minute).
     */
    private fun isBlacklisted(address: String): Boolean {
        val blacklistedAt = blacklistedAddresses[address] ?: return false
        if (System.currentTimeMillis() - blacklistedAt > blacklistDurationMs) {
            blacklistedAddresses.remove(address)
            return false
        }
        return true
    }

    // ========== API 33 Compatibility ==========

    /**
     * Write a characteristic value using the appropriate API for the current Android version.
     *
     * API 33+ uses the new 4-parameter writeCharacteristic that accepts data directly.
     * Pre-API 33 uses the deprecated version that requires setting the characteristic value first.
     */
    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
        }
    }

    // ========== Permission Check ==========

    /**
     * Check if BLUETOOTH_CONNECT permission is granted (required on API 31+).
     */
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
