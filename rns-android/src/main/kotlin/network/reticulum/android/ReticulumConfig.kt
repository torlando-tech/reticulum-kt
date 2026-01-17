package network.reticulum.android

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for the Reticulum Android service.
 *
 * This configuration allows tuning the behavior of Reticulum for different
 * use cases and battery/performance trade-offs.
 */
@Parcelize
data class ReticulumConfig(
    /**
     * Enable transport routing.
     * When enabled, this node will participate in the mesh by forwarding
     * packets and announces for other nodes. Uses more battery.
     * When disabled (default), this is a "client only" node that can still
     * send/receive its own traffic but doesn't route for others.
     */
    val enableTransport: Boolean = false,

    /**
     * Job interval in milliseconds.
     * Lower values increase responsiveness but drain more battery.
     * Default: 60000 (60 seconds). Was 250ms in original implementation.
     */
    val jobIntervalMs: Long = 60_000L,

    /**
     * Enable TCP keep-alive on connections.
     * Disabling saves battery but may cause connections to be dropped
     * by intermediate proxies/NATs during inactivity.
     */
    val tcpKeepAlive: Boolean = false,

    /**
     * Maximum size of the packet hashlist for deduplication.
     * Reduced from 1,000,000 (JVM) to 50,000 for mobile to save memory.
     */
    val maxHashlistSize: Int = 50_000,

    /**
     * Maximum number of queued announces.
     * Reduced from 16,384 (JVM) to 1,024 for mobile to save memory.
     */
    val maxQueuedAnnounces: Int = 1_024,

    /**
     * Show foreground service notification.
     * Required for reliable background operation on Android 8+.
     */
    val showNotification: Boolean = true,

    /**
     * Battery optimization mode.
     * Affects polling intervals, keepalive frequency, and announce rates.
     */
    val batteryOptimization: BatteryMode = BatteryMode.BALANCED,

    /**
     * Auto-start service on device boot.
     * Requires RECEIVE_BOOT_COMPLETED permission and user consent.
     */
    val autoStartOnBoot: Boolean = false,

    /**
     * Path to the Reticulum configuration directory.
     * If null, uses the app's default files directory.
     */
    val configDir: String? = null,

    /**
     * Share this Reticulum instance with other apps via local TCP.
     * When enabled, starts a LocalServerInterface that other apps can connect to.
     * This allows multiple apps to share a single Reticulum instance.
     * Default: true (recommended for Android to save battery)
     */
    val shareInstance: Boolean = true,

    /**
     * Port for shared instance communication.
     * Must match between server and clients.
     * Default: 37428 (same as Python RNS)
     */
    val sharedInstancePort: Int = 37428
) : Parcelable {
    /**
     * Battery optimization levels.
     */
    enum class BatteryMode {
        /**
         * Maximum battery savings.
         * - Longest polling intervals
         * - Reduced announce frequency
         * - Slower response to network events
         * Best for: Background messaging apps, low-priority services
         */
        MAXIMUM_BATTERY,

        /**
         * Balanced power and responsiveness.
         * - Moderate polling intervals
         * - Normal announce frequency
         * Best for: Most applications
         */
        BALANCED,

        /**
         * Maximum responsiveness, higher battery usage.
         * - Shorter polling intervals
         * - Immediate response to network events
         * Best for: Real-time communication, when plugged in
         */
        PERFORMANCE
    }

    /**
     * Get the effective hashlist size based on battery mode.
     * Performance mode allows larger hashlists; battery mode uses smaller.
     */
    fun getEffectiveHashlistSize(): Int = when (batteryOptimization) {
        BatteryMode.MAXIMUM_BATTERY -> minOf(maxHashlistSize, 25_000)
        BatteryMode.BALANCED -> maxHashlistSize
        BatteryMode.PERFORMANCE -> maxHashlistSize * 2
    }

    /**
     * Get the effective tables cull interval based on battery mode.
     * This controls how often path/link/reverse tables are cleaned.
     */
    fun getEffectiveTablesCullInterval(): Long = when (batteryOptimization) {
        BatteryMode.PERFORMANCE -> 5_000L       // 5s (Python default)
        BatteryMode.BALANCED -> 10_000L         // 10s
        BatteryMode.MAXIMUM_BATTERY -> 30_000L  // 30s
    }

    /**
     * Get the effective announces check interval based on battery mode.
     * This controls how often announce rebroadcasts are processed.
     */
    fun getEffectiveAnnouncesCheckInterval(): Long = when (batteryOptimization) {
        BatteryMode.PERFORMANCE -> 1_000L       // 1s (Python default)
        BatteryMode.BALANCED -> 5_000L          // 5s
        BatteryMode.MAXIMUM_BATTERY -> 10_000L  // 10s
    }

    companion object {
        /**
         * Default configuration: client-only mode.
         * Optimized for battery life with minimal background activity.
         */
        val DEFAULT = ReticulumConfig()

        /**
         * Configuration with transport enabled.
         * Participates in mesh network by routing for other nodes.
         */
        val WITH_TRANSPORT = ReticulumConfig(
            enableTransport = true,
            batteryOptimization = BatteryMode.BALANCED
        )

        /**
         * Configuration optimized for maximum battery savings.
         * Use when battery life is critical.
         */
        val BATTERY_SAVER = ReticulumConfig(
            enableTransport = false,
            batteryOptimization = BatteryMode.MAXIMUM_BATTERY,
            tcpKeepAlive = false
        )

        /**
         * Configuration optimized for performance.
         * Use when plugged in or for real-time applications.
         */
        val PERFORMANCE = ReticulumConfig(
            enableTransport = true,
            batteryOptimization = BatteryMode.PERFORMANCE,
            tcpKeepAlive = true,
            maxHashlistSize = 100_000
        )
    }
}
