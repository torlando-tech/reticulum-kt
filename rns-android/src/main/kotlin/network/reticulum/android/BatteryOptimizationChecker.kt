package network.reticulum.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the app's battery optimization status.
 */
sealed class BatteryOptimizationStatus {
    /**
     * App is battery-optimized (default Android behavior).
     * Background restrictions may apply in Doze mode.
     */
    data object Optimized : BatteryOptimizationStatus()

    /**
     * App is exempt from battery optimization.
     * Can maintain network connections during Doze maintenance windows.
     */
    data object Unrestricted : BatteryOptimizationStatus()

    override fun toString(): String = when (this) {
        is Optimized -> "Optimized"
        is Unrestricted -> "Unrestricted"
    }

    /**
     * Whether the app is exempt from battery optimization.
     */
    val isExempt: Boolean
        get() = this is Unrestricted
}

/**
 * Checks and observes battery optimization status for this app.
 *
 * Battery optimization affects whether the app can maintain network connections
 * during Doze mode. Unrestricted apps can use maintenance windows more freely.
 *
 * Usage:
 * ```kotlin
 * val checker = BatteryOptimizationChecker(context)
 * checker.start()
 *
 * // Current status
 * if (checker.status.value.isExempt) {
 *     // App is unrestricted
 * }
 *
 * // React to changes (rare - only changes when user modifies settings)
 * checker.status.collect { status ->
 *     updateUI(status)
 * }
 *
 * checker.stop()
 * ```
 *
 * @param context Application or service context
 */
class BatteryOptimizationChecker(
    private val context: Context
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var receiver: BroadcastReceiver? = null

    private val _status = MutableStateFlow(getCurrentStatus())

    /**
     * The current battery optimization status as a StateFlow.
     *
     * Use `.value` for immediate access or `collect` for reactive updates.
     */
    val status: StateFlow<BatteryOptimizationStatus> = _status.asStateFlow()

    /**
     * Convenience property for checking if app is exempt from battery optimization.
     */
    val isExempt: Boolean
        get() = _status.value.isExempt

    /**
     * Start observing battery optimization status changes.
     *
     * Note: Status changes are rare - only when user manually changes
     * battery settings for this app.
     */
    fun start() {
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_POWER_SAVE_WHITELIST_CHANGED) {
                    updateStatus()
                }
            }
        }

        val filter = IntentFilter(ACTION_POWER_SAVE_WHITELIST_CHANGED)
        context.registerReceiver(receiver, filter)

        Log.i(TAG, "Started checking battery optimization. Current: ${_status.value}")
    }

    /**
     * Stop observing battery optimization status changes.
     */
    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        receiver = null
        Log.i(TAG, "Stopped checking battery optimization")
    }

    /**
     * Check if currently observing.
     */
    val isObserving: Boolean
        get() = receiver != null

    /**
     * Force a status refresh.
     *
     * Useful after requesting battery optimization exemption to check the result.
     */
    fun refresh() {
        updateStatus()
    }

    private fun updateStatus() {
        val newStatus = getCurrentStatus()
        val oldStatus = _status.value

        if (newStatus != oldStatus) {
            Log.i(TAG, "Battery optimization status changed: $oldStatus -> $newStatus")
            _status.value = newStatus
        }
    }

    private fun getCurrentStatus(): BatteryOptimizationStatus {
        return if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            BatteryOptimizationStatus.Unrestricted
        } else {
            BatteryOptimizationStatus.Optimized
        }
    }

    companion object {
        private const val TAG = "BatteryOptChecker"

        /**
         * Broadcast action for battery optimization whitelist changes.
         * This is a hidden API constant that we use directly as a string.
         */
        private const val ACTION_POWER_SAVE_WHITELIST_CHANGED =
            "android.os.action.POWER_SAVE_WHITELIST_CHANGED"
    }
}
