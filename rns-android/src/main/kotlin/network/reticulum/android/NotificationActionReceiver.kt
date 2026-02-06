package network.reticulum.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * BroadcastReceiver that handles notification quick action intents.
 *
 * Dispatches [ReticulumService.ACTION_PAUSE], [ReticulumService.ACTION_RESUME],
 * and [ReticulumService.ACTION_RECONNECT] intents to the running service instance.
 *
 * Registered in AndroidManifest as non-exported (only app-internal intents).
 * Used by [PendingIntent]s attached to notification action buttons.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = ReticulumService.getInstance()
        if (service == null) {
            Log.w(TAG, "Service not running, ignoring action: ${intent.action}")
            return
        }

        when (intent.action) {
            ReticulumService.ACTION_PAUSE -> {
                Log.i(TAG, "Pause action received")
                service.pause()
            }
            ReticulumService.ACTION_RESUME -> {
                Log.i(TAG, "Resume action received")
                service.resume()
            }
            ReticulumService.ACTION_RECONNECT -> {
                Log.i(TAG, "Reconnect action received")
                service.reconnectInterfaces()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    companion object {
        private const val TAG = "NotifActionReceiver"

        // PendingIntent request codes to differentiate actions
        private const val REQUEST_RECONNECT = 0
        private const val REQUEST_PAUSE_RESUME = 1

        /**
         * Build notification action buttons based on current pause state.
         *
         * When running (not paused): returns [Reconnect, Pause]
         * When paused: returns [Resume] only (Reconnect hidden since interfaces are frozen)
         *
         * @param context Context for creating PendingIntents
         * @param isPaused Whether the service is currently paused
         * @return List of notification actions to attach to the notification
         */
        fun buildActions(context: Context, isPaused: Boolean): List<NotificationCompat.Action> {
            val actions = mutableListOf<NotificationCompat.Action>()

            if (!isPaused) {
                // Reconnect action (only when running - reconnect during pause makes no sense)
                val reconnectIntent = Intent(ReticulumService.ACTION_RECONNECT)
                    .setPackage(context.packageName)
                val reconnectPending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_RECONNECT,
                    reconnectIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                actions.add(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_popup_sync,
                        "Reconnect",
                        reconnectPending
                    ).build()
                )

                // Pause action
                val pauseIntent = Intent(ReticulumService.ACTION_PAUSE)
                    .setPackage(context.packageName)
                val pausePending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_PAUSE_RESUME,
                    pauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                actions.add(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Pause",
                        pausePending
                    ).build()
                )
            } else {
                // Resume action (only when paused)
                val resumeIntent = Intent(ReticulumService.ACTION_RESUME)
                    .setPackage(context.packageName)
                val resumePending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_PAUSE_RESUME,
                    resumeIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                actions.add(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_media_play,
                        "Resume",
                        resumePending
                    ).build()
                )
            }

            return actions
        }
    }
}
