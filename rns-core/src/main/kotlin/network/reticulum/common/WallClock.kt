package network.reticulum.common

/**
 * Wall-clock source with a test override.
 *
 * The python reference's conformance bridge pins protocol wall-clock values
 * (announce emission timestamps, path-response tag windows, table-cull ages)
 * by monkeypatching `time.time()` around a single call — the library still
 * does all real work, it just sees a pinned clock. The JVM cannot patch
 * `System.currentTimeMillis()`, so protocol code paths whose *timestamps are
 * part of observable behavior* read this source instead.
 *
 * [overrideMs] is a conformance/test seam ONLY: production code must never
 * set it. While null (the default, always in production) [nowMs] is exactly
 * `System.currentTimeMillis()`.
 */
object WallClock {
    @Volatile
    var overrideMs: Long? = null

    fun nowMs(): Long = overrideMs ?: System.currentTimeMillis()

    /** Epoch seconds, matching python's `int(time.time())`. */
    fun nowSeconds(): Long = nowMs() / 1000

    /** Run [block] with the clock pinned to [epochMs], restoring afterwards. */
    fun <T> withPinned(epochMs: Long, block: () -> T): T {
        val prior = overrideMs
        overrideMs = epochMs
        try {
            return block()
        } finally {
            overrideMs = prior
        }
    }
}
