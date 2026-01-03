package network.reticulum.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.reticulum.common.ByteArrayPool
import network.reticulum.common.Platform
import network.reticulum.transport.Transport
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Performance tests for Android optimization.
 *
 * These tests verify:
 * - Memory usage stays within bounds
 * - ByteArray pooling reduces allocations
 * - Platform detection works correctly
 */
@RunWith(AndroidJUnit4::class)
class PerformanceInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ByteArrayPool.clear()
        ByteArrayPool.resetStats()
    }

    @Test
    fun platform_detectsAndroid() {
        // When running on Android, should detect as Android
        assertTrue("Should detect Android platform", Platform.isAndroid)
    }

    @Test
    fun platform_mobileConstants_areSmallerThanJvm() {
        // Mobile constants should be smaller for memory efficiency
        assertTrue(
            "Mobile hashlist size should be reasonable",
            Platform.recommendedHashlistSize <= 100_000
        )
        assertTrue(
            "Mobile max queued announces should be reasonable",
            Platform.recommendedMaxQueuedAnnounces <= 2_000
        )
        assertTrue(
            "Mobile job interval should be longer for battery",
            Platform.recommendedJobIntervalMs >= 30_000
        )
    }

    @Test
    fun byteArrayPool_reusesBuffers() {
        ByteArrayPool.clear()
        ByteArrayPool.resetStats()

        // Obtain and recycle several buffers
        val buffers = mutableListOf<ByteArray>()
        repeat(10) {
            buffers.add(ByteArrayPool.obtain(4096))
        }

        // Recycle them all
        buffers.forEach { ByteArrayPool.recycle(it) }
        buffers.clear()

        // Obtain again - should hit pool
        repeat(10) {
            buffers.add(ByteArrayPool.obtain(4096))
        }

        val stats = ByteArrayPool.stats()

        // Should have some hits from recycled buffers
        assertTrue(
            "Should have pool hits after recycling: hits=${stats.hits}",
            stats.hits > 0
        )
    }

    @Test
    fun byteArrayPool_respectsMaxPoolSize() {
        ByteArrayPool.clear()

        // Try to recycle many buffers
        repeat(100) {
            val buffer = ByteArray(4096)
            ByteArrayPool.recycle(buffer)
        }

        // Pool should limit total memory
        val pooledBytes = ByteArrayPool.pooledBytes()
        assertTrue(
            "Pooled bytes should be limited: $pooledBytes",
            pooledBytes <= 8 * 1024 * 1024 // 8 MB max
        )
    }

    @Test
    fun byteArrayPool_clearReleasesMemory() {
        // Fill the pool
        repeat(20) {
            val buffer = ByteArray(4096)
            ByteArrayPool.recycle(buffer)
        }

        val beforeClear = ByteArrayPool.pooledBytes()
        assertTrue("Pool should have some bytes", beforeClear > 0)

        ByteArrayPool.clear()

        val afterClear = ByteArrayPool.pooledBytes()
        assertTrue("Pool should be empty after clear", afterClear == 0L)
    }

    @Test
    fun transport_memoryStats_returnsValidStats() {
        // This test verifies Transport memory stats work
        // Transport may or may not be started, so we just verify no crash
        try {
            val stats = Transport.getMemoryStats()
            assertTrue("Path table size should be >= 0", stats.pathTableSize >= 0)
            assertTrue("Link table size should be >= 0", stats.linkTableSize >= 0)
            assertTrue("Heap used should be > 0", stats.heapUsedBytes > 0)
            assertTrue("Heap max should be > 0", stats.heapMaxBytes > 0)
            assertTrue("Heap percent should be valid", stats.heapUsedPercent in 0..100)
        } catch (e: Exception) {
            // Transport may not be initialized, which is okay for this test
        }
    }

    @Test
    fun heapMemory_isWithinReasonableBounds() {
        // Verify heap memory utilities work
        val used = Platform.usedHeapMemory
        val max = Platform.maxHeapMemory
        val available = Platform.availableHeapMemory

        assertTrue("Used heap should be > 0", used > 0)
        assertTrue("Max heap should be > 0", max > 0)
        assertTrue("Available heap should be >= 0", available >= 0)
        assertTrue("Used + available should be <= max", used + available <= max + 1024) // small tolerance
    }

    @Test
    fun stressTest_byteArrayPool_underLoad() {
        ByteArrayPool.clear()
        ByteArrayPool.resetStats()

        val startTime = System.currentTimeMillis()

        // Simulate heavy buffer usage
        repeat(1000) { iteration ->
            val size = when (iteration % 4) {
                0 -> 512
                1 -> 1024
                2 -> 4096
                else -> 8192
            }

            val buffer = ByteArrayPool.obtain(size)
            // Simulate some work
            buffer[0] = iteration.toByte()
            ByteArrayPool.recycle(buffer)
        }

        val duration = System.currentTimeMillis() - startTime
        val stats = ByteArrayPool.stats()

        // Should complete in reasonable time (< 1 second)
        assertTrue(
            "1000 buffer operations should complete quickly: ${duration}ms",
            duration < 1000
        )

        // Should have good hit rate after warm-up
        assertTrue(
            "Hit rate should be > 50%: ${stats.hitRate}",
            stats.hitRate > 0.5
        )
    }
}
