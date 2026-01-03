package network.reticulum.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Broadcast receiver for starting Reticulum service on device boot.
 *
 * This receiver is disabled by default in the manifest. To enable auto-start:
 * 1. Enable the receiver in AndroidManifest.xml
 * 2. Ensure the app has RECEIVE_BOOT_COMPLETED permission granted
 *
 * Note: Auto-starting services on boot should be used sparingly, as it
 * can increase boot time and battery usage. Only enable this for users
 * who explicitly want Reticulum running at all times.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the Reticulum service with default config
            // Apps can customize this by subclassing BootReceiver
            ReticulumService.start(context)
        }
    }
}
