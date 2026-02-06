package network.reticulum.android

/**
 * Connection states for the Reticulum foreground service notification.
 *
 * These states drive the notification's visual appearance (color, text, icon)
 * and are computed from the current set of registered interfaces.
 */
enum class ServiceConnectionState {
    /** All configured interfaces are online. */
    CONNECTED,

    /** Some but not all interfaces are online. */
    PARTIAL,

    /** Service started but no interfaces are online yet. */
    CONNECTING,

    /** Service running but all interfaces are offline/detached. */
    DISCONNECTED,

    /** User explicitly paused via notification quick action. */
    PAUSED
}

/**
 * Point-in-time snapshot of a single interface's status.
 *
 * Used to compute [ServiceConnectionState] and render per-interface
 * detail lines in the expanded notification view.
 *
 * @property name Interface name (e.g., "testnet")
 * @property typeName Type category for grouping (e.g., "TCP", "UDP", "Auto")
 * @property isOnline Whether the interface is currently connected
 * @property detail Additional detail for expanded view (e.g., "10.0.0.1:4242")
 */
data class InterfaceSnapshot(
    val name: String,
    val typeName: String,
    val isOnline: Boolean,
    val detail: String = ""
)

/**
 * Complete snapshot of service connection state at a point in time.
 *
 * Passed to [NotificationContentBuilder] to render the foreground
 * service notification with status, uptime, and interface breakdown.
 *
 * @property state The computed connection state
 * @property interfaces Current interface snapshots
 * @property sessionStartTime System.currentTimeMillis() when service started
 * @property enableTransport Whether transport routing is enabled
 * @property isPaused Whether user paused via notification action
 */
data class ConnectionSnapshot(
    val state: ServiceConnectionState,
    val interfaces: List<InterfaceSnapshot>,
    val sessionStartTime: Long,
    val enableTransport: Boolean,
    val isPaused: Boolean = false
)

/**
 * Compute the connection state from current interface data and pause flag.
 *
 * Logic:
 * - If paused by user -> [ServiceConnectionState.PAUSED]
 * - If no interfaces registered -> [ServiceConnectionState.CONNECTING]
 * - If all interfaces online -> [ServiceConnectionState.CONNECTED]
 * - If some interfaces online -> [ServiceConnectionState.PARTIAL]
 * - If no interfaces online -> [ServiceConnectionState.DISCONNECTED]
 */
fun computeConnectionState(
    interfaces: List<InterfaceSnapshot>,
    isPaused: Boolean
): ServiceConnectionState {
    if (isPaused) return ServiceConnectionState.PAUSED
    if (interfaces.isEmpty()) return ServiceConnectionState.CONNECTING

    val onlineCount = interfaces.count { it.isOnline }
    return when {
        onlineCount == interfaces.size -> ServiceConnectionState.CONNECTED
        onlineCount > 0 -> ServiceConnectionState.PARTIAL
        else -> ServiceConnectionState.DISCONNECTED
    }
}

/**
 * Format a human-readable breakdown of online interfaces grouped by type.
 *
 * Only counts online interfaces. Examples:
 * - "2 TCP, 1 UDP"
 * - "1 TCP"
 * - "No interfaces"
 *
 * @param interfaces The current interface snapshots
 * @return Formatted breakdown string
 */
fun formatInterfaceBreakdown(interfaces: List<InterfaceSnapshot>): String {
    val onlineByType = interfaces
        .filter { it.isOnline }
        .groupBy { it.typeName }
        .mapValues { it.value.size }

    if (onlineByType.isEmpty()) return "No interfaces"

    return onlineByType.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { (type, count) -> "$count $type" }
}

/**
 * Format elapsed uptime as a human-readable duration string.
 *
 * Examples:
 * - "< 1m" (less than 60 seconds)
 * - "45m" (45 minutes)
 * - "2h 34m" (2 hours and 34 minutes)
 *
 * @param sessionStartTime The System.currentTimeMillis() when the session started
 * @return Formatted duration string
 */
fun formatUptime(sessionStartTime: Long): String {
    val elapsedMs = System.currentTimeMillis() - sessionStartTime
    val elapsedSeconds = elapsedMs / 1000

    if (elapsedSeconds < 60) return "< 1m"

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60

    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}
