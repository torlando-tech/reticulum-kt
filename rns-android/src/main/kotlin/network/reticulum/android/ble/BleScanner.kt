package network.reticulum.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.ble.BLEConstants
import network.reticulum.interfaces.ble.DiscoveredPeer

/**
 * BLE scanner for discovering Reticulum peers.
 *
 * Uses a hardware [ScanFilter] with [BLEConstants.SERVICE_UUID] so that scanning is
 * offloaded to the BLE chip. Runs as a single long-running scan until explicitly
 * stopped (no cycling). Emits [DiscoveredPeer] instances via a [SharedFlow], throttled
 * per device address to reduce callback noise while keeping RSSI data fresh.
 *
 * Scan mode is user-configurable via [ScanMode] (similar to [BleAdvertiser.AdvertiseMode]).
 *
 * @property context Application context
 * @property bluetoothAdapter Bluetooth adapter
 * @property scope Coroutine scope for async operations
 */
internal class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "BleScanner"
    }

    /**
     * User-configurable scan mode controlling power/latency trade-off.
     */
    internal enum class ScanMode(val value: Int) {
        /** ~5120ms scan window. Best battery life, slowest discovery. */
        LOW_POWER(ScanSettings.SCAN_MODE_LOW_POWER),

        /** ~2048ms scan window. Good balance of power and discovery speed. */
        BALANCED(ScanSettings.SCAN_MODE_BALANCED),

        /** Continuous scan. Fastest discovery, highest power consumption. */
        LOW_LATENCY(ScanSettings.SCAN_MODE_LOW_LATENCY),
    }

    // ---- Per-device throttle ----

    /** Last emission time per device address, used to throttle scan callbacks. */
    private val lastEmitTimes = mutableMapOf<String, Long>()
    private val throttleMutex = Mutex()
    private val throttleIntervalMs = 3_000L

    // ---- State ----

    private val _isScanning = MutableStateFlow(false)

    /** Current scanning state. */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ---- Event flows ----

    private val _discoveredPeers = MutableSharedFlow<DiscoveredPeer>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Emits a [DiscoveredPeer] for each scan result (throttled per device address). */
    val discoveredPeers: SharedFlow<DiscoveredPeer> = _discoveredPeers.asSharedFlow()

    // ---- Scan callback ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            scope.launch { results.forEach { handleScanResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    // ========== Public API ==========

    /**
     * Start scanning for Reticulum BLE peers.
     *
     * Uses a single long-running scan with a hardware [ScanFilter] for [BLEConstants.SERVICE_UUID].
     * Scan results are throttled per device address (3 seconds between emissions for the same device).
     *
     * @param mode Scan mode controlling power/latency trade-off
     * @return Result indicating success or failure
     */
    suspend fun startScanning(mode: ScanMode = ScanMode.BALANCED): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                if (!bluetoothAdapter.isEnabled) {
                    return@withContext Result.failure(
                        IllegalStateException("Bluetooth is disabled"),
                    )
                }

                if (!hasScanPermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLE scan permission"),
                    )
                }

                val scanner = bluetoothAdapter.bluetoothLeScanner
                if (scanner == null) {
                    return@withContext Result.failure(
                        IllegalStateException("BluetoothLeScanner not available"),
                    )
                }

                if (_isScanning.value) {
                    return@withContext Result.success(Unit)
                }

                // Hardware ScanFilter offloaded to BLE chip
                val scanFilter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
                    .build()

                val scanSettings = ScanSettings.Builder()
                    .setScanMode(mode.value)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build()

                scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
                _isScanning.value = true

                Result.success(Unit)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied when starting scan", e)
                Result.failure(SecurityException("Bluetooth scan permission required", e))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scan", e)
                Result.failure(e)
            }
        }

    /**
     * Stop the current BLE scan.
     */
    suspend fun stopScanning() {
        try {
            withContext(Dispatchers.Main) {
                if (!hasScanPermission()) return@withContext

                val scanner = bluetoothAdapter.bluetoothLeScanner
                if (scanner != null) {
                    scanner.stopScan(scanCallback)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        _isScanning.value = false
    }

    /**
     * Shutdown the scanner. Stops scanning, cancels scope.
     *
     * After shutdown, this instance cannot be reused.
     */
    fun shutdown() {
        try {
            if (_isScanning.value) {
                val scanner = bluetoothAdapter.bluetoothLeScanner
                if (scanner != null && hasScanPermission()) {
                    scanner.stopScan(scanCallback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
        _isScanning.value = false
        scope.cancel()
    }

    // ========== Internal ==========

    /**
     * Handle a single scan result: filter by RSSI, throttle per device, emit DiscoveredPeer.
     */
    private suspend fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        val rssi = result.rssi

        // Filter results below minimum RSSI threshold
        if (rssi < BLEConstants.MIN_RSSI_DBM) return

        // Per-device throttle: skip if last emit was too recent
        val now = System.currentTimeMillis()
        throttleMutex.withLock {
            val lastEmit = lastEmitTimes[address]
            if (lastEmit != null && (now - lastEmit) < throttleIntervalMs) {
                return
            }
            lastEmitTimes[address] = now
        }

        val peer = DiscoveredPeer(
            address = address,
            rssi = rssi,
            lastSeen = now,
        )
        _discoveredPeers.tryEmit(peer)
    }

    // ========== Permission Check ==========

    /**
     * Check if BLE scan permission is granted.
     *
     * API 31+ requires BLUETOOTH_SCAN. API 30 and below requires ACCESS_FINE_LOCATION.
     */
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
