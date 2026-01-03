package network.reticulum.common

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool for reusing byte arrays to reduce GC pressure.
 *
 * On mobile devices, frequent byte array allocations can cause
 * significant GC overhead. This pool allows reusing arrays of
 * common sizes (4KB, 8KB, etc.) used in network I/O.
 *
 * Usage:
 * ```kotlin
 * // Obtain a buffer
 * val buffer = ByteArrayPool.obtain(4096)
 *
 * // Use the buffer...
 * val bytesRead = inputStream.read(buffer)
 *
 * // Return to pool when done
 * ByteArrayPool.recycle(buffer)
 * ```
 *
 * Thread-safe: Can be used from multiple coroutines/threads.
 */
object ByteArrayPool {
    /** Maximum arrays to keep per size bucket. */
    private const val MAX_POOL_SIZE = 32

    /** Maximum total memory for the pool (8 MB). */
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024L

    /** Standard buffer sizes to pool. */
    private val STANDARD_SIZES = intArrayOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)

    /** Pools keyed by buffer size. */
    private val pools = ConcurrentHashMap<Int, ArrayDeque<ByteArray>>()

    /** Track approximate memory used by pool. */
    @Volatile
    private var totalPooledBytes = 0L

    /** Statistics for monitoring. */
    @Volatile
    var hits = 0L
        private set

    @Volatile
    var misses = 0L
        private set

    @Volatile
    var recycled = 0L
        private set

    /**
     * Obtain a byte array of at least the specified size.
     *
     * May return a larger array from the pool if available.
     * Returns a new array if none available in pool.
     *
     * @param minSize Minimum required size
     * @return A byte array of at least minSize
     */
    fun obtain(minSize: Int): ByteArray {
        // Find the smallest standard size that fits
        val poolSize = STANDARD_SIZES.firstOrNull { it >= minSize } ?: minSize

        val pool = pools[poolSize]
        if (pool != null) {
            synchronized(pool) {
                val array = pool.pollFirst()
                if (array != null) {
                    totalPooledBytes -= array.size
                    hits++
                    return array
                }
            }
        }

        misses++
        return ByteArray(poolSize)
    }

    /**
     * Return a byte array to the pool for reuse.
     *
     * The array contents may be retained - caller should not
     * assume the array is zeroed after recycling.
     *
     * @param array The array to recycle
     */
    fun recycle(array: ByteArray) {
        val size = array.size

        // Only pool standard sizes
        if (size !in STANDARD_SIZES) return

        // Don't exceed total memory limit
        if (totalPooledBytes + size > MAX_TOTAL_BYTES) return

        val pool = pools.getOrPut(size) { ArrayDeque() }
        synchronized(pool) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.addLast(array)
                totalPooledBytes += size
                recycled++
            }
        }
    }

    /**
     * Clear all pooled arrays.
     *
     * Call this when memory is low to free pooled buffers.
     */
    fun clear() {
        for (pool in pools.values) {
            synchronized(pool) {
                pool.clear()
            }
        }
        totalPooledBytes = 0
    }

    /**
     * Trim the pool to reduce memory usage.
     *
     * Removes half of the pooled arrays from each bucket.
     */
    fun trim() {
        for (pool in pools.values) {
            synchronized(pool) {
                val toRemove = pool.size / 2
                repeat(toRemove) {
                    val array = pool.pollFirst()
                    if (array != null) {
                        totalPooledBytes -= array.size
                    }
                }
            }
        }
    }

    /**
     * Get approximate memory used by pooled arrays.
     */
    fun pooledBytes(): Long = totalPooledBytes

    /**
     * Get pool statistics for monitoring.
     */
    fun stats(): PoolStats = PoolStats(
        pooledBytes = totalPooledBytes,
        hits = hits,
        misses = misses,
        recycled = recycled,
        hitRate = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
    )

    /**
     * Reset statistics counters.
     */
    fun resetStats() {
        hits = 0
        misses = 0
        recycled = 0
    }

    /**
     * Pool statistics.
     */
    data class PoolStats(
        val pooledBytes: Long,
        val hits: Long,
        val misses: Long,
        val recycled: Long,
        val hitRate: Double
    )
}
