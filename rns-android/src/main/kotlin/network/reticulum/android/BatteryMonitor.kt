package network.reticulum.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Monitors battery state for adaptive behavior.
 *
 * Adjusts Reticulum behavior based on:
 * - Battery level (reduce activity when low)
 * - Charging state (increase activity when charging)
 * - Power save mode (respect system-wide power saving)
 *
 * Usage:
 * ```kotlin
 * val monitor = BatteryMonitor(context)
 * monitor.start()
 *
 * // Get recommended battery mode
 * val mode = monitor.recommendedBatteryMode()
 *
 * // Clean up when done
 * monitor.stop()
 * ```
 */
class BatteryMonitor(private val context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /**
     * Listener for battery state changes.
     */
    interface BatteryStateListener {
        fun onBatteryStateChanged(info: BatteryInfo)
        fun onPowerSaveModeChanged(enabled: Boolean)
    }

    private var listener: BatteryStateListener? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var powerSaveReceiver: BroadcastReceiver? = null

    /**
     * Current battery level (0-100).
     */
    val batteryLevel: Int
        get() = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /**
     * Whether the device is currently charging.
     */
    val isCharging: Boolean
        get() {
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                   status == BatteryManager.BATTERY_STATUS_FULL
        }

    /**
     * Whether the device is plugged in (AC or USB).
     */
    val isPluggedIn: Boolean
        get() {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return plugged != 0
        }

    /**
     * Whether system-wide power save mode is enabled.
     */
    val isPowerSaveMode: Boolean
        get() = powerManager.isPowerSaveMode

    /**
     * Set a listener for battery state changes.
     */
    fun setListener(listener: BatteryStateListener?) {
        this.listener = listener
    }

    /**
     * Start monitoring battery state.
     */
    fun start() {
        // Register for battery changes
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED,
                    Intent.ACTION_BATTERY_LOW,
                    Intent.ACTION_BATTERY_OKAY,
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        notifyBatteryStateChanged()
                    }
                }
            }
        }

        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, batteryFilter)

        // Register for power save mode changes
        powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    listener?.onPowerSaveModeChanged(isPowerSaveMode)
                }
            }
        }

        val powerSaveFilter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        context.registerReceiver(powerSaveReceiver, powerSaveFilter)
    }

    /**
     * Stop monitoring battery state.
     */
    fun stop() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        batteryReceiver = null

        powerSaveReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        powerSaveReceiver = null
    }

    private fun notifyBatteryStateChanged() {
        listener?.onBatteryStateChanged(getBatteryInfo())
    }

    /**
     * Get current battery information.
     */
    fun getBatteryInfo(): BatteryInfo {
        return BatteryInfo(
            level = batteryLevel,
            charging = isCharging,
            pluggedIn = isPluggedIn,
            powerSaveMode = isPowerSaveMode
        )
    }

    /**
     * Get the recommended battery mode based on current state.
     *
     * This provides a suggestion for how Reticulum should behave
     * based on battery level, charging state, and power save mode.
     *
     * @return Recommended battery mode for Reticulum
     */
    fun recommendedBatteryMode(): ReticulumConfig.BatteryMode {
        val level = batteryLevel
        val charging = isCharging
        val powerSave = isPowerSaveMode

        return when {
            // System power save mode always takes priority
            powerSave -> ReticulumConfig.BatteryMode.MAXIMUM_BATTERY

            // Critical battery level
            level < 15 -> ReticulumConfig.BatteryMode.MAXIMUM_BATTERY

            // Low battery level
            level < 30 && !charging -> ReticulumConfig.BatteryMode.MAXIMUM_BATTERY

            // Charging or high battery
            charging || level > 80 -> ReticulumConfig.BatteryMode.PERFORMANCE

            // Normal operation
            else -> ReticulumConfig.BatteryMode.BALANCED
        }
    }

    /**
     * Battery information.
     */
    data class BatteryInfo(
        val level: Int,
        val charging: Boolean,
        val pluggedIn: Boolean,
        val powerSaveMode: Boolean
    ) {
        override fun toString(): String {
            return buildString {
                append("$level%")
                if (charging) append(" (charging)")
                else if (pluggedIn) append(" (plugged)")
                if (powerSaveMode) append(" [power save]")
            }
        }
    }
}
