package network.reticulum.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Helper class for managing Reticulum service notifications.
 *
 * Handles notification channel creation, service notifications,
 * and notification updates with network statistics.
 *
 * Usage:
 * ```kotlin
 * val helper = NotificationHelper(context)
 * val notification = helper.createServiceNotification(enableTransport, stats)
 * startForeground(NOTIFICATION_ID, notification)
 * ```
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        NotificationChannels.createChannels(context)
    }

    /**
     * Create the foreground service notification.
     *
     * @param enableTransport Whether transport routing is enabled
     * @param status Status text to display
     * @param contentIntent Optional intent to launch when notification is tapped
     * @return The notification to display
     */
    fun createServiceNotification(
        enableTransport: Boolean,
        status: String = "Running",
        contentIntent: PendingIntent? = null
    ): Notification {
        val modeText = if (enableTransport) "Transport" else "Client"

        val builder = NotificationCompat.Builder(context, NotificationChannels.SERVICE_CHANNEL_ID)
            .setContentTitle("Reticulum $modeText")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_reticulum)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        contentIntent?.let {
            builder.setContentIntent(it)
        }

        return builder.build()
    }

    /**
     * Create a notification with network statistics.
     *
     * @param enableTransport Whether transport routing is enabled
     * @param stats Network statistics to display
     * @param contentIntent Optional intent to launch when notification is tapped
     * @return The notification to display
     */
    fun createServiceNotification(
        enableTransport: Boolean,
        stats: NetworkStats,
        contentIntent: PendingIntent? = null
    ): Notification {
        return createServiceNotification(
            enableTransport = enableTransport,
            status = formatStats(stats),
            contentIntent = contentIntent
        )
    }

    /**
     * Update the existing notification with new status.
     *
     * @param notificationId The notification ID to update
     * @param enableTransport Whether transport routing is enabled
     * @param status Status text to display
     */
    fun updateNotification(
        notificationId: Int,
        enableTransport: Boolean,
        status: String
    ) {
        val notification = createServiceNotification(enableTransport, status)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Update the existing notification with network statistics.
     *
     * @param notificationId The notification ID to update
     * @param enableTransport Whether transport routing is enabled
     * @param stats Network statistics to display
     */
    fun updateNotification(
        notificationId: Int,
        enableTransport: Boolean,
        stats: NetworkStats
    ) {
        val notification = createServiceNotification(enableTransport, stats)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Create a rich foreground service notification from a connection snapshot.
     *
     * Produces a notification with color-coded status, interface breakdown,
     * uptime, and BigTextStyle expanded view with per-interface details.
     *
     * @param snapshot Current connection state and interface data
     * @param contentIntent Optional intent for tapping the notification
     * @param actions Quick action buttons (e.g., Reconnect, Pause/Resume)
     * @return The notification to display
     */
    fun createServiceNotification(
        snapshot: ConnectionSnapshot,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ): Notification {
        return NotificationContentBuilder(context).buildNotification(
            snapshot = snapshot,
            contentIntent = contentIntent,
            actions = actions
        )
    }

    /**
     * Update the existing notification with a connection snapshot.
     *
     * @param notificationId The notification ID to update
     * @param snapshot Current connection state and interface data
     * @param contentIntent Optional intent for tapping the notification
     * @param actions Quick action buttons (e.g., Reconnect, Pause/Resume)
     */
    fun updateNotification(
        notificationId: Int,
        snapshot: ConnectionSnapshot,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ) {
        val notification = createServiceNotification(snapshot, contentIntent, actions)
        notificationManager.notify(notificationId, notification)
    }

    private fun formatStats(stats: NetworkStats): String {
        return when {
            stats.activeLinks > 0 -> "${stats.activeLinks} links, ${stats.knownPeers} peers"
            stats.knownPeers > 0 -> "${stats.knownPeers} peers known"
            else -> "Connected"
        }
    }

    /**
     * Network statistics for notification display.
     */
    data class NetworkStats(
        val activeLinks: Int = 0,
        val knownPeers: Int = 0,
        val rxBytes: Long = 0,
        val txBytes: Long = 0
    )

    companion object {
        /** Default notification ID for the service. */
        const val SERVICE_NOTIFICATION_ID = 1001
    }
}
