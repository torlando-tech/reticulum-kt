package network.reticulum.common

/**
 * Platform detection utilities.
 *
 * Used to detect the runtime environment and adjust behavior accordingly,
 * particularly for Android vs JVM differences in memory limits and threading.
 */
object Platform {
    /**
     * Detect if running on Android.
     * Checks for Android-specific classes in the classpath.
     */
    val isAndroid: Boolean by lazy {
        try {
            Class.forName("android.os.Build")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * Detect if running on a server/desktop JVM (not Android).
     */
    val isJvm: Boolean
        get() = !isAndroid

    /**
     * Get the platform name for logging/debugging.
     */
    val name: String
        get() = if (isAndroid) "Android" else "JVM"

    /**
     * Get the recommended hashlist size for this platform.
     * Android uses smaller values to conserve memory.
     */
    val recommendedHashlistSize: Int
        get() = if (isAndroid) 50_000 else 1_000_000

    /**
     * Get the recommended max queued announces for this platform.
     */
    val recommendedMaxQueuedAnnounces: Int
        get() = if (isAndroid) 1_024 else 16_384

    /**
     * Get the recommended job interval in milliseconds.
     * Android uses longer intervals to save battery.
     */
    val recommendedJobIntervalMs: Long
        get() = if (isAndroid) 60_000L else 250L

    /**
     * Get the recommended max tunnels for this platform.
     */
    val recommendedMaxTunnels: Int
        get() = if (isAndroid) 1_000 else 10_000

    /**
     * Get the recommended max receipts for this platform.
     */
    val recommendedMaxReceipts: Int
        get() = if (isAndroid) 256 else 1_024

    /**
     * Get the recommended max random blobs per destination.
     */
    val recommendedMaxRandomBlobs: Int
        get() = if (isAndroid) 16 else 64

    /**
     * Get the available heap memory in bytes.
     * Useful for adaptive memory management.
     */
    val availableHeapMemory: Long
        get() {
            val runtime = Runtime.getRuntime()
            return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        }

    /**
     * Get the used heap memory in bytes.
     */
    val usedHeapMemory: Long
        get() {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }

    /**
     * Get the max heap memory in bytes.
     */
    val maxHeapMemory: Long
        get() = Runtime.getRuntime().maxMemory()

    /**
     * Check if memory is running low (> 80% used).
     */
    val isLowMemory: Boolean
        get() = usedHeapMemory > (maxHeapMemory * 0.8)
}
