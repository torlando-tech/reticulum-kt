package network.reticulum.android

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import network.reticulum.Reticulum
import network.reticulum.transport.Transport
import java.util.concurrent.TimeUnit

/**
 * Recovery-focused periodic worker for Reticulum maintenance.
 *
 * This worker is a companion to [ReticulumService], not a replacement. The foreground
 * service runs Transport's continuous job loop (250ms). WorkManager's role is recovery
 * when Doze suspends the service:
 *
 * 1. **Interface health check** - Verify TCP/UDP connections are online (logging only;
 *    reconnection is handled by TCPClientInterface's built-in reconnect loop)
 * 2. **Transport maintenance** - Run accumulated maintenance jobs (path table cleanup,
 *    hashlist rotation, tunnel table save)
 * 3. **Service health check** - Restart the foreground service if it was killed by the OS
 *    and auto-restart is enabled
 *
 * Battery awareness: When battery is below [ConnectionPolicy.BATTERY_THROTTLE_THRESHOLD]
 * (15%) and not charging, non-critical tasks (interface health logging) are skipped.
 *
 * No network connectivity constraint is set on the work request because Reticulum can
 * route over non-internet transports (LoRa, BLE) that Android doesn't consider "connected".
 *
 * Usage:
 * ```kotlin
 * // Schedule periodic maintenance (every 15 minutes)
 * ReticulumWorker.schedule(context, intervalMinutes = 15, autoRestart = true)
 *
 * // Cancel scheduled work
 * ReticulumWorker.cancel(context)
 * ```
 */
class ReticulumWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Maintenance worker starting")

        var interfacesTotal = 0
        var interfacesOnline = 0
        var serviceRunning = false
        var serviceRestarted = false
        var batteryLow = false

        return try {
            // Check battery state for low-battery mode
            batteryLow = isBatteryLow()
            if (batteryLow) {
                Log.i(TAG, "Low battery mode - skipping non-critical tasks")
            }

            // 1. Interface Health Check (non-critical, skip in low battery)
            if (!batteryLow) {
                if (Reticulum.isStarted()) {
                    val interfaces = Transport.getInterfaces()
                    interfacesTotal = interfaces.size
                    interfacesOnline = interfaces.count { it.online }
                    Log.i(TAG, "Interface health: $interfacesOnline/$interfacesTotal online")

                    // Log individual interface status for diagnostics
                    for (iface in interfaces) {
                        Log.d(TAG, "  ${iface.name}: online=${iface.online}")
                    }
                } else {
                    Log.d(TAG, "Transport not started, skipping interface health check")
                }
            }

            // 2. Transport Maintenance (important, always run)
            if (Reticulum.isStarted()) {
                Transport.runMaintenanceJobs()
                Log.d(TAG, "Transport maintenance completed")
            } else {
                Log.d(TAG, "Transport not started, skipping maintenance")
            }

            // 3. Service Health Check and Restart (critical, always run)
            serviceRunning = Reticulum.isStarted()
            if (!serviceRunning) {
                val autoRestart = inputData.getBoolean(KEY_AUTO_RESTART, false)
                if (autoRestart) {
                    Log.i(TAG, "Service not running, auto-restart enabled - restarting")
                    ReticulumService.start(applicationContext)
                    serviceRestarted = true
                } else {
                    Log.i(TAG, "Service not running, auto-restart disabled - skipping")
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Maintenance worker completed: $interfacesOnline/$interfacesTotal interfaces online, service=$serviceRunning (${elapsed}ms)")

            Result.success(
                workDataOf(
                    KEY_INTERFACES_TOTAL to interfacesTotal,
                    KEY_INTERFACES_ONLINE to interfacesOnline,
                    KEY_SERVICE_RUNNING to serviceRunning,
                    KEY_SERVICE_RESTARTED to serviceRestarted,
                    KEY_BATTERY_LOW to batteryLow,
                    KEY_TIMESTAMP to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Maintenance worker failed after ${elapsed}ms: ${e.message}", e)
            // Let WorkManager handle backoff
            Result.retry()
        }
    }

    /**
     * Check if battery is below the throttle threshold and not charging.
     * Uses BatteryManager system service for a point-in-time reading.
     */
    private fun isBatteryLow(): Boolean {
        return try {
            val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)
                ?: return false

            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            // Low battery = below threshold AND not charging
            level < ConnectionPolicy.BATTERY_THROTTLE_THRESHOLD && !isCharging
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery state: ${e.message}")
            false // Default to not low-battery if we can't read
        }
    }

    companion object {
        private const val TAG = "ReticulumWorker"
        private const val WORK_NAME = "reticulum_maintenance"

        // Input data keys
        private const val KEY_AUTO_RESTART = "auto_restart"

        // Output data keys
        private const val KEY_INTERFACES_TOTAL = "interfaces_total"
        private const val KEY_INTERFACES_ONLINE = "interfaces_online"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_SERVICE_RESTARTED = "service_restarted"
        private const val KEY_BATTERY_LOW = "battery_low"
        private const val KEY_TIMESTAMP = "timestamp"

        /**
         * Schedule periodic maintenance work.
         *
         * No network connectivity constraint is set because Reticulum can route
         * over non-internet transports (LoRa, BLE) that Android doesn't consider
         * "connected".
         *
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so existing work survives app updates.
         *
         * @param context Application context
         * @param intervalMinutes Interval between maintenance runs (minimum 15 minutes
         *                        due to WorkManager constraints)
         * @param autoRestart Whether to restart the foreground service if it's not running
         */
        fun schedule(context: Context, intervalMinutes: Long = 15, autoRestart: Boolean = false) {
            // WorkManager requires minimum 15 minute intervals
            val effectiveInterval = maxOf(intervalMinutes, 15L)

            val request = PeriodicWorkRequestBuilder<ReticulumWorker>(
                effectiveInterval, TimeUnit.MINUTES
            )
                .addTag(WORK_NAME)
                .setInputData(workDataOf(KEY_AUTO_RESTART to autoRestart))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled periodic maintenance: interval=${effectiveInterval}min, autoRestart=$autoRestart")
        }

        /**
         * Cancel scheduled maintenance work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic maintenance")
        }

        /**
         * Check if maintenance work is currently scheduled.
         */
        suspend fun isScheduled(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
            return workInfos.any { !it.state.isFinished }
        }
    }
}
