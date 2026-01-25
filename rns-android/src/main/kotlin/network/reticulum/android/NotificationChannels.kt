package network.reticulum.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes

/**
 * Notification channel configuration for Reticulum service.
 *
 * Creates two channels:
 * - Service channel: Low importance, no sound/vibration, for persistent service notification
 * - Alerts channel: Default importance, with sound, for disconnect warnings and important events
 */
object NotificationChannels {

    const val SERVICE_CHANNEL_ID = "reticulum_service"
    const val ALERTS_CHANNEL_ID = "reticulum_alerts"

    /**
     * Create all notification channels.
     * Safe to call multiple times - Android ignores duplicate channel creation.
     *
     * @param context Application or service context
     * @param serviceImportance Override importance for service channel (default: LOW)
     * @param alertsImportance Override importance for alerts channel (default: DEFAULT)
     */
    fun createChannels(
        context: Context,
        serviceImportance: Int = NotificationManager.IMPORTANCE_LOW,
        alertsImportance: Int = NotificationManager.IMPORTANCE_DEFAULT
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(
            createServiceChannel(serviceImportance)
        )
        notificationManager.createNotificationChannel(
            createAlertsChannel(alertsImportance)
        )
    }

    /**
     * Create the service notification channel.
     *
     * Low importance by default - shows in notification shade but no sound/vibration.
     * User can configure importance in system settings after channel creation.
     */
    fun createServiceChannel(
        importance: Int = NotificationManager.IMPORTANCE_LOW
    ): NotificationChannel {
        return NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Reticulum Service",
            importance
        ).apply {
            description = "Persistent notification for mesh network connection"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
    }

    /**
     * Create the alerts notification channel.
     *
     * Default importance - shows in shade with sound for important events like:
     * - Disconnect warnings
     * - Connection failures requiring attention
     * - Network transitions that may affect connectivity
     */
    fun createAlertsChannel(
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT
    ): NotificationChannel {
        return NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Reticulum Alerts",
            importance
        ).apply {
            description = "Important alerts about connection status"
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
            // Use default notification sound
            setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }
}
