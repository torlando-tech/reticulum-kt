package network.reticulum.android.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import network.reticulum.interfaces.ble.BLEConstants

/**
 * BLE GATT Server for peripheral mode.
 *
 * Creates a GATT server with the Reticulum service and accepts incoming connections from
 * central devices. Handles characteristic reads/writes and sends notifications.
 *
 * **GATT Service Structure (Protocol v2.2):**
 * ```
 * Reticulum Service (37145b00-442d-4a94-917f-8f42c5da28e3)
 * +-- RX Characteristic (37145b00-442d-4a94-917f-8f42c5da28e5)
 * |   +-- Properties: WRITE, WRITE_WITHOUT_RESPONSE
 * |   +-- Permissions: WRITE
 * |   +-- Purpose: Centrals write data here -> we receive (incl. identity handshake)
 * +-- TX Characteristic (37145b00-442d-4a94-917f-8f42c5da28e4)
 * |   +-- Properties: READ, NOTIFY
 * |   +-- Permissions: READ
 * |   +-- CCCD Descriptor (00002902-...)
 * |       +-- Purpose: We notify centrals here -> they receive
 * +-- Identity Characteristic (37145b00-442d-4a94-917f-8f42c5da28e6)
 *     +-- Properties: READ
 *     +-- Permissions: READ
 *     +-- Purpose: Provides 16-byte transport identity hash for stable peer tracking
 * ```
 *
 * @property context Application context
 * @property bluetoothManager Bluetooth manager
 * @property scope Coroutine scope for async operations
 */
internal class BleGattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "BleGattServer"
    }

    // ---- GATT Server ----

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Local transport identity (16 bytes, set by BLEInterface/Transport)
    @Volatile
    private var transportIdentityHash: ByteArray? = null

    // ---- Per-device state ----

    /** Connected centrals: device address -> device object. */
    private val connectedCentrals = mutableMapOf<String, BluetoothDevice>()

    /** MTU tracking per central: device address -> usable MTU (ATT MTU - 3). */
    private val centralMtus = mutableMapOf<String, Int>()

    /** CCCD subscription state per central: device address -> notifications enabled. */
    private val notificationSubscriptions = mutableMapOf<String, Boolean>()

    /** Single mutex protecting all per-device state maps. */
    private val stateMutex = Mutex()

    // ---- Notification serialization ----

    /**
     * Global notification deferred. Android BLE stack serializes notifications globally
     * (not per-device), so we use a single lock for all notification sends.
     */
    private var notificationDeferred: CompletableDeferred<Int>? = null
    private val notificationMutex = Mutex()

    // ---- Service registration ----

    private var serviceAddedDeferred: CompletableDeferred<Result<Unit>>? = null

    // ---- Event flows ----

    private val _centralConnected = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits device address when a central connects. */
    val centralConnected: SharedFlow<String> = _centralConnected.asSharedFlow()

    private val _centralDisconnected = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits device address when a central disconnects. */
    val centralDisconnected: SharedFlow<String> = _centralDisconnected.asSharedFlow()

    private val _dataReceived = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 16)
    /** Emits (address, data) when data is written to the RX characteristic. */
    val dataReceived: SharedFlow<Pair<String, ByteArray>> = _dataReceived.asSharedFlow()

    private val _mtuChanged = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)
    /** Emits (address, usableMtu) when MTU is negotiated for a device. */
    val mtuChanged: SharedFlow<Pair<String, Int>> = _mtuChanged.asSharedFlow()

    // ---- GATT Server Callback ----

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            scope.launch {
                handleConnectionStateChange(device, status, newState)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            scope.launch {
                handleCharacteristicReadRequest(device, requestId, offset, characteristic)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            scope.launch {
                handleCharacteristicWriteRequest(
                    device, requestId, characteristic,
                    preparedWrite, responseNeeded, offset, value,
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            scope.launch {
                handleDescriptorReadRequest(device, requestId, offset, descriptor)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            scope.launch {
                handleDescriptorWriteRequest(
                    device, requestId, descriptor,
                    preparedWrite, responseNeeded, offset, value,
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            scope.launch {
                handleMtuChanged(device, mtu)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationDeferred?.complete(status)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                serviceAddedDeferred?.complete(Result.success(Unit))
            } else {
                serviceAddedDeferred?.complete(
                    Result.failure(IllegalStateException("Failed to add GATT service (status: $status)")),
                )
            }
        }
    }

    // ========== Public API ==========

    /**
     * Open the GATT server and register the Reticulum service.
     *
     * Waits for the asynchronous [onServiceAdded] callback before returning.
     * Returns [Result.failure] if the server cannot be opened, service cannot be added,
     * or the service registration times out (5 seconds).
     */
    suspend fun open(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            if (gattServer != null) {
                return@withContext Result.success(Unit)
            }

            if (!hasConnectPermission()) {
                return@withContext Result.failure(
                    SecurityException("Missing BLUETOOTH_CONNECT permission"),
                )
            }

            val server = bluetoothManager.openGattServer(context, gattServerCallback)
                ?: return@withContext Result.failure(
                    IllegalStateException("Failed to open GATT server"),
                )
            gattServer = server

            val service = createReticulumService()

            serviceAddedDeferred = CompletableDeferred()

            val added = server.addService(service)
            if (!added) {
                serviceAddedDeferred = null
                server.close()
                gattServer = null
                return@withContext Result.failure(
                    IllegalStateException("Failed to add Reticulum service to GATT server"),
                )
            }

            // Wait for onServiceAdded callback
            val serviceResult = withTimeoutOrNull(BLEConstants.OPERATION_TIMEOUT_MS) {
                serviceAddedDeferred?.await()
            }
            serviceAddedDeferred = null

            if (serviceResult == null) {
                server.close()
                gattServer = null
                return@withContext Result.failure(
                    IllegalStateException("Timeout waiting for GATT service registration"),
                )
            }

            if (serviceResult.isFailure) {
                server.close()
                gattServer = null
                return@withContext serviceResult
            }

            // Store TX characteristic reference for notifications
            txCharacteristic = service.getCharacteristic(BLEConstants.TX_CHAR_UUID)

            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when opening GATT server", e)
            gattServer?.close()
            gattServer = null
            Result.failure(SecurityException("Bluetooth permission required", e))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GATT server", e)
            gattServer?.close()
            gattServer = null
            Result.failure(e)
        }
    }

    /**
     * Close the GATT server and clear all per-device state.
     */
    suspend fun close() = withContext(Dispatchers.Main) {
        try {
            stateMutex.withLock {
                connectedCentrals.clear()
                centralMtus.clear()
                notificationSubscriptions.clear()
            }

            gattServer?.close()
            gattServer = null
            txCharacteristic = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when closing server", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server", e)
        }
    }

    /**
     * Set the local transport identity hash.
     *
     * Called by the BLEInterface/Transport layer after Reticulum initialization.
     * The identity is served to centrals via the Identity GATT characteristic.
     *
     * @param identityHash 16-byte Reticulum Transport identity hash
     * @throws IllegalArgumentException if identityHash is not exactly 16 bytes
     */
    fun setTransportIdentity(identityHash: ByteArray) {
        require(identityHash.size == BLEConstants.IDENTITY_SIZE) {
            "Transport identity hash must be exactly ${BLEConstants.IDENTITY_SIZE} bytes (got ${identityHash.size})"
        }
        transportIdentityHash = identityHash.copyOf()
    }

    /**
     * Send a notification to a specific connected central.
     *
     * Notifications are serialized globally via [notificationMutex] because the Android
     * BLE stack processes notifications one at a time across all devices. Each send waits
     * for the [onNotificationSent] callback before returning.
     *
     * @param device The target BluetoothDevice
     * @param data The notification payload
     * @return true if the notification was sent and acknowledged successfully
     */
    suspend fun sendNotification(device: BluetoothDevice, data: ByteArray): Boolean {
        notificationMutex.withLock {
            val server = gattServer ?: return false
            val txChar = txCharacteristic ?: return false

            // Only send to subscribed devices
            val subscribed = stateMutex.withLock {
                notificationSubscriptions[device.address] == true
            }
            if (!subscribed) return false

            if (!hasConnectPermission()) return false

            val deferred = CompletableDeferred<Int>()
            notificationDeferred = deferred

            val result = withContext(Dispatchers.Main) {
                sendNotificationCompat(server, device, txChar, data)
            }

            if (result != BluetoothGatt.GATT_SUCCESS) {
                notificationDeferred = null
                return false
            }

            // Wait for onNotificationSent callback
            val status = withTimeoutOrNull(BLEConstants.OPERATION_TIMEOUT_MS) {
                deferred.await()
            }
            notificationDeferred = null
            return status == BluetoothGatt.GATT_SUCCESS
        }
    }

    /**
     * Send a notification to a connected central by address.
     *
     * Convenience wrapper around [sendNotification] that looks up the device by address.
     *
     * @param address BLE MAC address of the target central
     * @param data The notification payload
     * @return true if the notification was sent and acknowledged successfully
     */
    suspend fun sendNotificationToAddress(address: String, data: ByteArray): Boolean {
        val device = stateMutex.withLock { connectedCentrals[address] } ?: return false
        return sendNotification(device, data)
    }

    /**
     * Get the list of currently connected central addresses.
     */
    suspend fun getConnectedCentrals(): List<String> {
        return stateMutex.withLock { connectedCentrals.keys.toList() }
    }

    /**
     * Get the usable MTU for a specific central (ATT MTU - 3).
     *
     * @return The usable MTU, or null if the device is not connected
     */
    suspend fun getMtu(address: String): Int? {
        return stateMutex.withLock { centralMtus[address] }
    }

    /**
     * Check whether a central has subscribed to TX notifications via CCCD.
     */
    suspend fun isSubscribed(address: String): Boolean {
        return stateMutex.withLock { notificationSubscriptions[address] == true }
    }

    /**
     * Request disconnection of a connected central.
     *
     * Calls [BluetoothGattServer.cancelConnection] which triggers
     * [onConnectionStateChange] with STATE_DISCONNECTED.
     */
    suspend fun disconnectCentral(address: String) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) return@withContext

            val device = stateMutex.withLock { connectedCentrals[address] }
            if (device != null) {
                gattServer?.cancelConnection(device)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when disconnecting central", e)
        }
    }

    /**
     * Shut down the GATT server and cancel the coroutine scope.
     *
     * After shutdown, this instance cannot be reused.
     */
    fun shutdown() {
        try {
            stateMutex.tryLock()
            try {
                connectedCentrals.clear()
                centralMtus.clear()
                notificationSubscriptions.clear()
            } finally {
                try { stateMutex.unlock() } catch (_: IllegalStateException) { }
            }

            gattServer?.close()
            gattServer = null
            txCharacteristic = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
        scope.cancel()
    }

    // ========== GATT Callback Handlers ==========

    private suspend fun handleConnectionStateChange(
        device: BluetoothDevice,
        status: Int,
        newState: Int,
    ) {
        val address = device.address

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                stateMutex.withLock {
                    connectedCentrals[address] = device
                    centralMtus[address] = BLEConstants.MIN_MTU
                }
                _centralConnected.tryEmit(address)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.d(TAG, "Central disconnected: $address (status: $status)")
                stateMutex.withLock {
                    connectedCentrals.remove(address)
                    centralMtus.remove(address)
                    notificationSubscriptions.remove(address)
                }
                _centralDisconnected.tryEmit(address)
            }
        }
    }

    private suspend fun handleCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) return@withContext

            when (characteristic.uuid) {
                BLEConstants.TX_CHAR_UUID -> {
                    // TX characteristic read: return empty (notifications are the data mechanism)
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset,
                        ByteArray(0),
                    )
                }

                BLEConstants.IDENTITY_CHAR_UUID -> {
                    val identity = transportIdentityHash
                    if (identity != null) {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset,
                            identity,
                        )
                    } else {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_FAILURE, offset,
                            null,
                        )
                    }
                }

                else -> {
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_FAILURE, offset,
                        null,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in read request handler", e)
        }
    }

    private suspend fun handleCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) return@withContext

            when (characteristic.uuid) {
                BLEConstants.RX_CHAR_UUID -> {
                    // Send response FIRST if needed (prevents GATT error 133)
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset,
                            value,
                        )
                    }

                    // Silent drop on empty data per CONTEXT.md
                    if (value.isEmpty()) return@withContext

                    // Emit data to consumers
                    _dataReceived.tryEmit(device.address to value)
                }

                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_FAILURE, offset,
                            null,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in write request handler", e)
        }
    }

    private suspend fun handleDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) return@withContext

            when (descriptor.uuid) {
                BLEConstants.CCCD_UUID -> {
                    val subscribed = stateMutex.withLock {
                        notificationSubscriptions[device.address] == true
                    }
                    val value = if (subscribed) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset,
                        value,
                    )
                }

                else -> {
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_FAILURE, offset,
                        null,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in descriptor read handler", e)
        }
    }

    private suspend fun handleDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) return@withContext

            when (descriptor.uuid) {
                BLEConstants.CCCD_UUID -> {
                    val enabled = value.contentEquals(
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    )
                    stateMutex.withLock {
                        notificationSubscriptions[device.address] = enabled
                    }

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset,
                            value,
                        )
                    }
                }

                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_FAILURE, offset,
                            null,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in descriptor write handler", e)
        }
    }

    private suspend fun handleMtuChanged(device: BluetoothDevice, mtu: Int) {
        // ATT MTU includes 3-byte header; usable payload is mtu - 3
        val usableMtu = mtu - 3

        stateMutex.withLock {
            centralMtus[device.address] = usableMtu
        }

        _mtuChanged.tryEmit(device.address to usableMtu)
    }

    // ========== Service Creation ==========

    /**
     * Create the Reticulum GATT service with RX, TX, and Identity characteristics.
     */
    private fun createReticulumService(): BluetoothGattService {
        val service = BluetoothGattService(
            BLEConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // RX: Centrals write data here (fragments, identity handshake, keepalive)
        val rxChar = BluetoothGattCharacteristic(
            BLEConstants.RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // TX: We notify centrals here (fragments, keepalive)
        val txChar = BluetoothGattCharacteristic(
            BLEConstants.TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        // CCCD descriptor on TX for notification subscription
        val cccd = BluetoothGattDescriptor(
            BLEConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        txChar.addDescriptor(cccd)

        // Identity: Read-only 16-byte Transport identity hash
        val identityChar = BluetoothGattCharacteristic(
            BLEConstants.IDENTITY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        service.addCharacteristic(identityChar)

        return service
    }

    // ========== API 33 Compatibility ==========

    /**
     * Send a notification using the appropriate API for the current Android version.
     *
     * API 33+ uses the new 4-parameter [BluetoothGattServer.notifyCharacteristicChanged]
     * that accepts the data directly and returns an int status code. Pre-API 33 uses the
     * deprecated 3-parameter version that requires setting the characteristic value first
     * and returns a boolean.
     */
    @Suppress("DEPRECATION")
    private fun sendNotificationCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, data)
        } else {
            characteristic.value = data
            val success = server.notifyCharacteristicChanged(device, characteristic, false)
            if (success) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }
    }

    // ========== Permission Check ==========

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
