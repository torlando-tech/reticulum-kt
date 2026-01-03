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
     * Operating mode for this Reticulum instance.
     * CLIENT_ONLY mode uses significantly less battery.
     */
    val mode: Mode = Mode.CLIENT_ONLY,

    /**
     * Enable transport routing (only effective in ROUTING mode).
     * When enabled, this node will participate in the mesh by forwarding
     * packets and announces for other nodes.
     */
    val enableTransport: Boolean = false,

    /**
     * Job interval in milliseconds (only for ROUTING mode).
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
    val configDir: String? = null
) : Parcelable {
    /**
     * Operating mode for the Reticulum instance.
     */
    enum class Mode {
        /**
         * Client-only mode: minimal battery usage.
         * - No routing/forwarding
         * - No announce rebroadcasting
         * - No tunnel synthesis
         * - Can still send/receive messages, establish links, use resources
         */
        CLIENT_ONLY,

        /**
         * Full routing mode: participate in mesh network.
         * - Routes packets for other nodes
         * - Rebroadcasts announces
         * - Can synthesize tunnels
         * - Higher battery usage (3-5% per hour vs <1% for client-only)
         */
        ROUTING
    }

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
     * Get the effective job interval based on battery mode.
     */
    fun getEffectiveJobInterval(): Long = when (batteryOptimization) {
        BatteryMode.MAXIMUM_BATTERY -> maxOf(jobIntervalMs, 120_000L) // At least 2 minutes
        BatteryMode.BALANCED -> jobIntervalMs
        BatteryMode.PERFORMANCE -> minOf(jobIntervalMs, 30_000L) // At most 30 seconds
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

    companion object {
        /**
         * Default configuration for client-only mode.
         * Optimized for battery life with minimal background activity.
         */
        val CLIENT_ONLY = ReticulumConfig(
            mode = Mode.CLIENT_ONLY,
            enableTransport = false,
            batteryOptimization = BatteryMode.BALANCED
        )

        /**
         * Default configuration for routing mode.
         * Participates in mesh network with balanced battery usage.
         */
        val ROUTING = ReticulumConfig(
            mode = Mode.ROUTING,
            enableTransport = true,
            batteryOptimization = BatteryMode.BALANCED
        )

        /**
         * Configuration optimized for maximum battery savings.
         * Use when battery life is critical.
         */
        val BATTERY_SAVER = ReticulumConfig(
            mode = Mode.CLIENT_ONLY,
            enableTransport = false,
            batteryOptimization = BatteryMode.MAXIMUM_BATTERY,
            tcpKeepAlive = false
        )

        /**
         * Configuration optimized for performance.
         * Use when plugged in or for real-time applications.
         */
        val PERFORMANCE = ReticulumConfig(
            mode = Mode.ROUTING,
            enableTransport = true,
            batteryOptimization = BatteryMode.PERFORMANCE,
            tcpKeepAlive = true,
            maxHashlistSize = 100_000
        )
    }
}
