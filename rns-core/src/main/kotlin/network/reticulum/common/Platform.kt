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
}
