package network.reticulum.android

import android.app.Notification
import android.app.NotificationChannel
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
 * val notification = helper.createServiceNotification(mode, stats)
 * startForeground(NOTIFICATION_ID, notification)
 * ```
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ReticulumService.CHANNEL_ID,
            "Reticulum Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Reticulum mesh network service"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Create the foreground service notification.
     *
     * @param mode Current operating mode (CLIENT_ONLY or ROUTING)
     * @param status Status text to display
     * @param contentIntent Optional intent to launch when notification is tapped
     * @return The notification to display
     */
    fun createServiceNotification(
        mode: ReticulumConfig.Mode,
        status: String = "Running",
        contentIntent: PendingIntent? = null
    ): Notification {
        val modeText = when (mode) {
            ReticulumConfig.Mode.CLIENT_ONLY -> "Client"
            ReticulumConfig.Mode.ROUTING -> "Routing"
        }

        val builder = NotificationCompat.Builder(context, ReticulumService.CHANNEL_ID)
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
     * @param mode Current operating mode
     * @param stats Network statistics to display
     * @param contentIntent Optional intent to launch when notification is tapped
     * @return The notification to display
     */
    fun createServiceNotification(
        mode: ReticulumConfig.Mode,
        stats: NetworkStats,
        contentIntent: PendingIntent? = null
    ): Notification {
        return createServiceNotification(
            mode = mode,
            status = formatStats(stats),
            contentIntent = contentIntent
        )
    }

    /**
     * Update the existing notification with new status.
     *
     * @param notificationId The notification ID to update
     * @param mode Current operating mode
     * @param status Status text to display
     */
    fun updateNotification(
        notificationId: Int,
        mode: ReticulumConfig.Mode,
        status: String
    ) {
        val notification = createServiceNotification(mode, status)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Update the existing notification with network statistics.
     *
     * @param notificationId The notification ID to update
     * @param mode Current operating mode
     * @param stats Network statistics to display
     */
    fun updateNotification(
        notificationId: Int,
        mode: ReticulumConfig.Mode,
        stats: NetworkStats
    ) {
        val notification = createServiceNotification(mode, stats)
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
