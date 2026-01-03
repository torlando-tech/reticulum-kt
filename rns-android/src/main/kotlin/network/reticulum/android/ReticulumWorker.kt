package network.reticulum.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import network.reticulum.transport.Transport
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic Reticulum maintenance tasks.
 *
 * This worker runs maintenance jobs like path table cleanup, announce
 * rebroadcasting, and link keepalive checks. It's designed to work with
 * Android's Doze mode and battery optimization.
 *
 * In ROUTING mode, this supplements the coroutine-based job loop with
 * periodic maintenance that survives app backgrounding and Doze mode.
 *
 * Usage:
 * ```kotlin
 * // Schedule periodic maintenance (every 15 minutes)
 * ReticulumWorker.schedule(context, 15)
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
        return try {
            // Run maintenance jobs (path cleanup, announce rebroadcast, etc.)
            Transport.runMaintenanceJobs()
            Result.success()
        } catch (e: Exception) {
            // Log error but don't retry immediately - let WorkManager handle backoff
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "reticulum_maintenance"

        /**
         * Schedule periodic maintenance work.
         *
         * @param context Application context
         * @param intervalMinutes Interval between maintenance runs (minimum 15 minutes
         *                        due to WorkManager constraints)
         */
        fun schedule(context: Context, intervalMinutes: Long = 15) {
            // WorkManager requires minimum 15 minute intervals
            val effectiveInterval = maxOf(intervalMinutes, 15L)

            val request = PeriodicWorkRequestBuilder<ReticulumWorker>(
                effectiveInterval, TimeUnit.MINUTES
            )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Cancel scheduled maintenance work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
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
