package network.reticulum.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver for starting Reticulum service on device boot and after app updates.
 *
 * This receiver is disabled by default in the manifest. To enable auto-start:
 * 1. Enable the receiver via PackageManager based on user preference
 * 2. Ensure the app has RECEIVE_BOOT_COMPLETED permission granted
 *
 * Handles two intents:
 * - ACTION_BOOT_COMPLETED: Starts the service immediately and enqueues WorkManager for recovery
 * - MY_PACKAGE_REPLACED: Re-enqueues WorkManager after app update (safety net for KEEP policy)
 *
 * Note: Auto-starting services on boot should be used sparingly, as it
 * can increase boot time and battery usage. Only enable this for users
 * who explicitly want Reticulum running at all times.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Boot completed - starting Reticulum service")

                // Start the Reticulum service immediately for availability
                // Apps can customize this by subclassing BootReceiver
                ReticulumService.start(context)
                Log.i(TAG, "Service start requested")

                // Enqueue WorkManager for recovery backup
                // The worker ensures the service is restarted if killed by the OS
                ReticulumWorker.schedule(context)
                Log.i(TAG, "WorkManager recovery worker enqueued")
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "App updated - re-enqueuing WorkManager for recovery")

                // Re-enqueue WorkManager after app update as a safety net.
                // WorkManager uses KEEP policy so existing work should survive updates,
                // but re-enqueuing ensures continuity if the work was lost.
                ReticulumWorker.schedule(context)
                Log.i(TAG, "WorkManager recovery worker re-enqueued after update")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
