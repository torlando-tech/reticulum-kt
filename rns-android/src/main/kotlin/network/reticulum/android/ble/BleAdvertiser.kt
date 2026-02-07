package network.reticulum.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.ble.BLEConstants

/**
 * Manages BLE advertising for peripheral mode.
 *
 * Advertises the Reticulum service UUID so that other devices can discover and connect.
 * Handles OEM resilience (some chipsets silently stop advertising on connection),
 * periodic 60-second refresh for background persistence, and user-configurable
 * advertising mode.
 *
 * Advertising payload is minimal: service UUID only, no device name or TX power level.
 */
internal class BleAdvertiser(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "BleAdvertiser"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 2_000L
        private const val REFRESH_INTERVAL_MS = 60_000L
        private const val REFRESH_GAP_MS = 100L
    }

    /**
     * User-configurable advertising mode controlling power/latency trade-off.
     */
    internal enum class AdvertiseMode(val value: Int) {
        /** ~1000ms interval. Best battery life, slowest discovery. */
        LOW_POWER(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER),

        /** ~250ms interval. Good balance of power and discovery speed. */
        BALANCED(AdvertiseSettings.ADVERTISE_MODE_BALANCED),

        /** ~100ms interval. Fastest discovery, highest power consumption. */
        LOW_LATENCY(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY),
    }

    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter.bluetoothLeAdvertiser

    // State
    private val _isAdvertising = MutableStateFlow(false)

    /** Current advertising state. */
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    /**
     * Whether advertising should be active. Tracks desired state, which may differ
     * from actual state when OEMs silently stop advertising.
     */
    @Volatile
    private var shouldBeAdvertising = false

    /** The advertising mode currently in use. */
    @Volatile
    private var currentMode: AdvertiseMode = AdvertiseMode.BALANCED

    /** Current retry count for advertising failures. */
    @Volatile
    private var retryCount = 0

    /** Job for the 60-second periodic advertising refresh. */
    private var refreshJob: Job? = null

    /**
     * Advertise callback for handling advertising state changes.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully (mode=${settingsInEffect.mode})")
            _isAdvertising.value = true
            retryCount = 0
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false

            if (retryCount < MAX_RETRY_ATTEMPTS && shouldBeAdvertising) {
                retryCount++
                val backoffMs = RETRY_BACKOFF_MS * retryCount
                Log.e(TAG, "Advertising failed (code $errorCode), retrying in ${backoffMs}ms " +
                    "(attempt $retryCount/$MAX_RETRY_ATTEMPTS)")

                scope.launch {
                    delay(backoffMs)
                    if (shouldBeAdvertising) {
                        startAdvertisingInternal()
                    }
                }
            } else {
                Log.e(TAG, "Advertising failed (code $errorCode) after $retryCount retries")
            }
        }
    }

    /**
     * Start BLE advertising with the Reticulum service UUID.
     *
     * @param mode Advertising mode controlling power/latency trade-off
     * @return Result indicating success or failure
     */
    suspend fun startAdvertising(mode: AdvertiseMode = AdvertiseMode.BALANCED): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "startAdvertising: entering (mode=$mode, btEnabled=${bluetoothAdapter.isEnabled})")
                if (!bluetoothAdapter.isEnabled) {
                    return@withContext Result.failure(
                        IllegalStateException("Bluetooth is disabled"),
                    )
                }

                if (!hasAdvertisePermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLUETOOTH_ADVERTISE permission"),
                    )
                }

                if (bluetoothLeAdvertiser == null) {
                    return@withContext Result.failure(
                        IllegalStateException("BluetoothLeAdvertiser not available"),
                    )
                }

                if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
                    return@withContext Result.failure(
                        IllegalStateException("BLE advertising not supported on this device"),
                    )
                }

                currentMode = mode
                shouldBeAdvertising = true
                retryCount = 0

                Log.d(TAG, "startAdvertising: calling startAdvertisingInternal...")
                startAdvertisingInternal()
                startRefreshTimer()

                Log.d(TAG, "startAdvertising: returning success")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting advertising", e)
                Result.failure(e)
            }
        }

    /**
     * Stop BLE advertising.
     */
    suspend fun stopAdvertising() {
        shouldBeAdvertising = false
        stopRefreshTimer()

        try {
            withContext(Dispatchers.Main) {
                stopAdvertisingInternal()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied stopping advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
    }

    /**
     * Restart advertising if it should be active but was stopped externally.
     *
     * Call this from BleGattServer's onConnectionStateChange to handle OEMs that
     * silently stop advertising when a connection is established.
     */
    fun restartIfNeeded() {
        if (shouldBeAdvertising && !_isAdvertising.value) {
            scope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        retryCount = 0
                        startAdvertisingInternal()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting advertising", e)
                }
            }
        }
    }

    /**
     * Shutdown the advertiser. Stops advertising, cancels refresh timer, cancels scope.
     */
    fun shutdown() {
        shouldBeAdvertising = false
        stopRefreshTimer()
        try {
            stopAdvertisingInternal()
        } catch (_: Exception) {
            // Best-effort cleanup
        }
        scope.cancel()
    }

    // ---- Internal ----

    /**
     * Start advertising via the BLE stack. Must be called on the Main thread.
     */
    private fun startAdvertisingInternal() {
        val advertiser = bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(currentMode.value)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Minimal payload: service UUID only. No device name, no TX power level.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(settings, advertiseData, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied starting advertising", e)
            _isAdvertising.value = false
        }
    }

    /**
     * Stop advertising via the BLE stack. Safe to call even if not advertising.
     */
    private fun stopAdvertisingInternal() {
        val advertiser = bluetoothLeAdvertiser ?: return
        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
            // Ignore -- best-effort stop
        }
        _isAdvertising.value = false
    }

    /**
     * Start the 60-second periodic advertising refresh.
     *
     * Some OEMs and chipsets silently stop advertising after extended periods,
     * particularly when the app is in the background. Periodically stopping and
     * restarting advertising ensures it remains active.
     */
    private fun startRefreshTimer() {
        stopRefreshTimer()
        refreshJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                if (shouldBeAdvertising) {
                    try {
                        withContext(Dispatchers.Main) {
                            stopAdvertisingInternal()
                            delay(REFRESH_GAP_MS)
                            startAdvertisingInternal()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during advertising refresh", e)
                    }
                }
            }
        }
    }

    /**
     * Stop the periodic advertising refresh timer.
     */
    private fun stopRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * Check if BLUETOOTH_ADVERTISE permission is granted (required on API 31+).
     */
    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
