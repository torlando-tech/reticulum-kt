package network.reticulum.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Builds rich foreground service notifications from [ConnectionSnapshot] data.
 *
 * Produces notifications with:
 * - Color-coded status per [ServiceConnectionState]
 * - Interface type breakdown (e.g., "2 TCP, 1 UDP")
 * - Session uptime duration (e.g., "Connected for 2h 34m")
 * - [BigTextStyle][NotificationCompat.BigTextStyle] expanded view with per-interface status lines
 *
 * Usage:
 * ```kotlin
 * val builder = NotificationContentBuilder(context)
 * val notification = builder.buildNotification(snapshot, contentIntent)
 * ```
 */
class NotificationContentBuilder(private val context: Context) {

    /**
     * Build a rich notification from the given connection snapshot.
     *
     * @param snapshot Current connection state and interface data
     * @param contentIntent Optional intent for tapping the notification
     * @param actions Quick action buttons (e.g., Reconnect, Pause/Resume)
     * @return A fully configured [Notification]
     */
    fun buildNotification(
        snapshot: ConnectionSnapshot,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ): Notification {
        val title = buildTitle(snapshot)
        val contentText = buildContentText(snapshot)
        val expandedText = buildExpandedText(snapshot)
        val color = stateColor(snapshot.state)

        val builder = NotificationCompat.Builder(context, NotificationChannels.SERVICE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_reticulum)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(color)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedText)
            )

        contentIntent?.let { builder.setContentIntent(it) }
        actions.forEach { builder.addAction(it) }

        return builder.build()
    }

    private fun buildTitle(snapshot: ConnectionSnapshot): String {
        val mode = if (snapshot.enableTransport) "Transport" else "Client"
        return "Reticulum $mode"
    }

    private fun buildContentText(snapshot: ConnectionSnapshot): String {
        val breakdown = formatInterfaceBreakdown(snapshot.interfaces)
        return when (snapshot.state) {
            ServiceConnectionState.PAUSED -> "Paused"
            ServiceConnectionState.CONNECTING -> "Connecting..."
            ServiceConnectionState.DISCONNECTED -> "Disconnected"
            ServiceConnectionState.CONNECTED -> "Connected \u2014 $breakdown"
            ServiceConnectionState.PARTIAL -> "Partial \u2014 $breakdown online"
        }
    }

    private fun buildExpandedText(snapshot: ConnectionSnapshot): String {
        val lines = mutableListOf<String>()

        // Line 1: same as collapsed content
        lines.add(buildContentText(snapshot))

        // Line 2: uptime (only for connected/partial states)
        if (snapshot.state == ServiceConnectionState.CONNECTED ||
            snapshot.state == ServiceConnectionState.PARTIAL
        ) {
            val uptime = formatUptime(snapshot.sessionStartTime)
            lines.add("Connected for $uptime")
        }

        // Lines 3+: per-interface status
        for (iface in snapshot.interfaces) {
            val status = if (iface.isOnline) "Online" else "Offline"
            val detail = if (iface.detail.isNotEmpty()) " (${iface.detail})" else ""
            lines.add("${iface.typeName} ${iface.name}$detail \u2014 $status")
        }

        return lines.joinToString("\n")
    }

    companion object {
        /** Default notification ID for the foreground service. */
        const val NOTIFICATION_ID = 1001

        // Status colors per connection state
        private const val COLOR_CONNECTED = 0xFF4CAF50.toInt()    // Green
        private const val COLOR_PARTIAL = 0xFFFF9800.toInt()       // Orange
        private const val COLOR_CONNECTING = 0xFFFFC107.toInt()    // Yellow-orange
        private const val COLOR_DISCONNECTED = 0xFFF44336.toInt()  // Red
        private const val COLOR_PAUSED = 0xFF9E9E9E.toInt()        // Gray

        /**
         * Get the notification accent color for a given connection state.
         */
        fun stateColor(state: ServiceConnectionState): Int = when (state) {
            ServiceConnectionState.CONNECTED -> COLOR_CONNECTED
            ServiceConnectionState.PARTIAL -> COLOR_PARTIAL
            ServiceConnectionState.CONNECTING -> COLOR_CONNECTING
            ServiceConnectionState.DISCONNECTED -> COLOR_DISCONNECTED
            ServiceConnectionState.PAUSED -> COLOR_PAUSED
        }
    }
}
