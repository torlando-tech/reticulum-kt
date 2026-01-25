package network.reticulum.interfaces.backoff

/**
 * Exponential backoff calculator for reconnection attempts.
 *
 * Starts at [initialDelayMs] (default 1 second), doubles each attempt
 * up to [maxDelayMs] (default 60 seconds), and gives up after
 * [maxAttempts] (default 10) consecutive failures.
 *
 * Call [reset] when connection succeeds or network changes to start fresh.
 *
 * Backoff sequence with defaults: 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, 60s, 60s, null
 *
 * Usage:
 * ```kotlin
 * val backoff = ExponentialBackoff()
 * while (!connected) {
 *     val delayMs = backoff.nextDelay() ?: break  // null = give up
 *     delay(delayMs)
 *     connected = tryConnect()
 * }
 * if (connected) backoff.reset()
 * ```
 *
 * @param initialDelayMs Starting delay in milliseconds (default 1000ms = 1 second)
 * @param maxDelayMs Maximum delay cap in milliseconds (default 60000ms = 60 seconds)
 * @param multiplier Delay multiplier between attempts (default 2.0)
 * @param maxAttempts Maximum attempts before giving up (default 10)
 */
class ExponentialBackoff(
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
    private val maxAttempts: Int = 10
) {
    private var currentDelay = initialDelayMs
    private var attempts = 0

    /**
     * Get the next delay in milliseconds.
     *
     * Each call increments the attempt counter and calculates the next delay.
     * The delay doubles each time (using [multiplier]) up to [maxDelayMs].
     *
     * @return Delay before next attempt in milliseconds, or null if max attempts reached
     */
    fun nextDelay(): Long? {
        if (attempts >= maxAttempts) return null

        val delay = currentDelay
        currentDelay = minOf((currentDelay * multiplier).toLong(), maxDelayMs)
        attempts++

        return delay
    }

    /**
     * Reset the backoff state to initial values.
     *
     * Call this when:
     * - Connection succeeds (reward success)
     * - Network type changes (fresh start on new network)
     * - Manual reconnection requested by user
     */
    fun reset() {
        currentDelay = initialDelayMs
        attempts = 0
    }

    /**
     * Current attempt number (0-based, incremented by [nextDelay]).
     *
     * Returns 0 before first attempt, 1 after first nextDelay() call, etc.
     */
    val attemptCount: Int
        get() = attempts

    /**
     * Whether max attempts have been reached.
     *
     * When true, [nextDelay] will return null.
     * Call [reset] to start fresh.
     */
    val isExhausted: Boolean
        get() = attempts >= maxAttempts
}
