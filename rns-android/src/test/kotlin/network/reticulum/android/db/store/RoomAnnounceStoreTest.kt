package network.reticulum.android.db.store

import network.reticulum.android.db.dao.AnnounceCacheDao
import network.reticulum.android.db.entity.AnnounceCacheEntity
import network.reticulum.common.ByteArrayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Regression tests for Sentry COLUMBA-B7 and its siblings (COLUMBA-8X/8R).
 *
 * [RoomAnnounceStore] is a write-through cache backed by a single Room write
 * executor that is drained-then-closed on service teardown by
 * [network.reticulum.android.lifecycle.StoreLifecycle]. The drain is bounded
 * (so a foreground-service `onDestroy` can't ANR) and therefore closes the
 * `RoomDatabase` even when a write is still in flight on a slow device — Room
 * then throws `IllegalStateException` ("no current transaction" etc.) from
 * inside the executor task, and on the dedicated write thread that is a fatal
 * uncaught crash. A best-effort cache write must drop the write, not crash.
 *
 * The production exception can't be reproduced through a real Robolectric Room
 * DB (host JDBC SQLite no-ops writes after `close()` instead of throwing — see
 * [network.reticulum.android.lifecycle.StoreLifecycleTest]), so these tests
 * inject the exact exception through a fake DAO and run the task inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomAnnounceStoreTest {

    private val noCurrentTransaction =
        IllegalStateException("Cannot perform this operation because there is no current transaction.")

    @Test
    fun `removeAllExcept swallows a closed-DB IllegalStateException instead of crashing the write thread`() {
        val dao = FakeAnnounceCacheDao(
            allHashes = listOf(byteArrayOf(1), byteArrayOf(2)),
            failure = noCurrentTransaction,
        )
        val store = RoomAnnounceStore(dao, DirectExecutorService())

        // Pre-fix: this IllegalStateException escapes the executor task and, on the
        // NativeReticulumDB-write thread, reaches the uncaught handler — fatal (COLUMBA-B7).
        store.removeAllExcept(setOf(ByteArrayKey(byteArrayOf(9))))

        // Reaching here means it did not throw; the write was attempted then dropped.
        assertTrue("the prune should have attempted at least one delete", dao.deleteByHashCount >= 1)
    }

    @Test
    fun `cacheAnnounce swallows a closed-DB IllegalStateException`() {
        val dao = FakeAnnounceCacheDao(failure = noCurrentTransaction)
        val store = RoomAnnounceStore(dao, DirectExecutorService())

        store.cacheAnnounce(byteArrayOf(1), byteArrayOf(2, 3), "iface")

        assertEquals(1, dao.upsertCount)
    }

    @Test
    fun `removeAnnounce swallows a closed-DB IllegalStateException`() {
        val dao = FakeAnnounceCacheDao(failure = noCurrentTransaction)
        val store = RoomAnnounceStore(dao, DirectExecutorService())

        store.removeAnnounce(byteArrayOf(7))

        assertEquals(1, dao.deleteByHashCount)
    }

    @Test
    fun `write is dropped without throwing when the executor is already shut down`() {
        val dao = FakeAnnounceCacheDao()
        val executor = DirectExecutorService().apply { shutdown() }
        val store = RoomAnnounceStore(dao, executor)

        // Mirrors StoreLifecycle.drain() having shut the executor down during teardown:
        // execute() throws RejectedExecutionException on the calling RNS thread.
        store.cacheAnnounce(byteArrayOf(1), byteArrayOf(2), "iface")

        assertEquals("rejected task must not run", 0, dao.upsertCount)
    }

    @Test
    fun `removeAllExcept deletes only the inactive cached hashes when the DB is healthy`() {
        val active = byteArrayOf(9, 9)
        val inactive = byteArrayOf(1)
        val dao = FakeAnnounceCacheDao(allHashes = listOf(active, inactive))
        val store = RoomAnnounceStore(dao, DirectExecutorService())

        store.removeAllExcept(setOf(ByteArrayKey(active)))

        // The guard must not change normal pruning behavior.
        assertEquals(listOf(inactive.toList()), dao.deleted.map { it.toList() })
    }

    /** Inline executor so write tasks run deterministically on the test thread. */
    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("executor shut down")
            command.run()
        }

        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class FakeAnnounceCacheDao(
        private val allHashes: List<ByteArray> = emptyList(),
        private val failure: RuntimeException? = null,
    ) : AnnounceCacheDao {
        var upsertCount = 0
        var deleteByHashCount = 0
        val deleted = mutableListOf<ByteArray>()

        override fun upsert(entity: AnnounceCacheEntity) {
            upsertCount++
            failure?.let { throw it }
        }

        override fun getByHash(packetHash: ByteArray): AnnounceCacheEntity? = null

        override fun deleteByHash(packetHash: ByteArray) {
            deleteByHashCount++
            deleted.add(packetHash)
            failure?.let { throw it }
        }

        override fun getAllHashes(): List<ByteArray> = allHashes

        override fun deleteAllExcept(activeHashes: List<ByteArray>) = Unit
    }
}
