package network.reticulum.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import network.reticulum.transport.Transport

/**
 * Handles Android Doze mode for Reticulum service.
 *
 * Doze mode is an Android power-saving feature that restricts network
 * access and defers background jobs when the device is idle. This class:
 * - Monitors Doze state changes
 * - Adjusts Reticulum behavior during Doze
 * - Provides battery optimization whitelist management
 *
 * Usage:
 * ```kotlin
 * val handler = DozeHandler(context)
 * handler.start()
 *
 * // Check Doze state
 * if (handler.isDozeMode) {
 *     // Handle restricted state
 * }
 *
 * // Request whitelist (from Activity)
 * handler.requestBatteryOptimizationExemption(activity)
 *
 * // Clean up when done
 * handler.stop()
 * ```
 */
class DozeHandler(private val context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var dozeReceiver: BroadcastReceiver? = null

    /**
     * Listener for Doze state changes.
     */
    interface DozeStateListener {
        fun onDozeStateChanged(inDoze: Boolean)
    }

    private var listener: DozeStateListener? = null

    /**
     * Whether the device is currently in Doze mode.
     */
    val isDozeMode: Boolean
        get() = powerManager.isDeviceIdleMode

    /**
     * Whether this app is exempt from battery optimizations.
     *
     * When exempt, the app can:
     * - Use network during Doze maintenance windows
     * - Receive FCM high-priority messages
     * - Use foreground services more freely
     */
    val isIgnoringBatteryOptimizations: Boolean
        get() = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Set a listener for Doze state changes.
     */
    fun setListener(listener: DozeStateListener?) {
        this.listener = listener
    }

    /**
     * Start monitoring Doze state.
     */
    fun start() {
        dozeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                    handleDozeStateChanged()
                }
            }
        }

        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        context.registerReceiver(dozeReceiver, filter)
    }

    /**
     * Stop monitoring Doze state.
     */
    fun stop() {
        dozeReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        dozeReceiver = null
    }

    private fun handleDozeStateChanged() {
        val inDoze = isDozeMode
        listener?.onDozeStateChanged(inDoze)

        if (inDoze) {
            onEnterDoze()
        } else {
            onExitDoze()
        }
    }

    /**
     * Called when device enters Doze mode.
     * Reduces activity to maintenance windows.
     */
    private fun onEnterDoze() {
        try {
            Transport.setDozeMode(true)
        } catch (e: Exception) {
            // Transport may not be initialized
        }
    }

    /**
     * Called when device exits Doze mode.
     * Resumes normal operation.
     */
    private fun onExitDoze() {
        try {
            Transport.setDozeMode(false)
        } catch (e: Exception) {
            // Transport may not be initialized
        }
    }

    /**
     * Request exemption from battery optimizations.
     *
     * This opens the system settings screen where the user can choose
     * to exempt this app from battery optimizations. This allows the
     * app to use network during Doze maintenance windows.
     *
     * Note: This should only be requested when necessary (e.g., for
     * mesh routing mode) and with user explanation.
     *
     * @param activity Activity to use for starting the settings intent
     */
    fun requestBatteryOptimizationExemption(activity: Activity) {
        if (isIgnoringBatteryOptimizations) {
            return // Already exempt
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        activity.startActivity(intent)
    }

    /**
     * Open battery optimization settings for this app.
     *
     * This opens the detailed battery settings page where the user
     * can manage this app's battery optimization settings.
     *
     * @param activity Activity to use for starting the settings intent
     */
    fun openBatterySettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        activity.startActivity(intent)
    }

    /**
     * Get Doze state information.
     */
    fun getDozeInfo(): DozeInfo {
        return DozeInfo(
            inDozeMode = isDozeMode,
            exemptFromOptimization = isIgnoringBatteryOptimizations
        )
    }

    /**
     * Doze state information.
     */
    data class DozeInfo(
        val inDozeMode: Boolean,
        val exemptFromOptimization: Boolean
    ) {
        override fun toString(): String {
            return buildString {
                append(if (inDozeMode) "In Doze" else "Not in Doze")
                append(if (exemptFromOptimization) " (exempt)" else " (optimized)")
            }
        }
    }
}
