package network.reticulum.android.db.store

import network.reticulum.android.db.dao.PathDao
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.transport.PathEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * COLUMBA-B7 sibling coverage for [RoomPathStore]. Shares the
 * close-during-write race with [RoomAnnounceStore]; see [RoomAnnounceStoreTest]
 * for the full root-cause notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomPathStoreTest {

    private val noCurrentTransaction =
        IllegalStateException("Cannot perform this operation because there is no current transaction.")

    @Test
    fun `upsertPath swallows a closed-DB IllegalStateException`() {
        val dao = FakePathDao(failure = noCurrentTransaction)
        val store = RoomPathStore(dao, DirectExecutorService())

        store.upsertPath(byteArrayOf(1), samplePathEntry())

        assertEquals(1, dao.upsertCount)
    }

    @Test
    fun `removePath swallows a closed-DB IllegalStateException`() {
        val dao = FakePathDao(failure = noCurrentTransaction)
        val store = RoomPathStore(dao, DirectExecutorService())

        store.removePath(byteArrayOf(2))

        assertEquals(1, dao.deleteByHashCount)
    }

    @Test
    fun `removeExpiredBefore swallows a closed-DB IllegalStateException`() {
        val dao = FakePathDao(failure = noCurrentTransaction)
        val store = RoomPathStore(dao, DirectExecutorService())

        store.removeExpiredBefore(123L)

        assertEquals(123L, dao.lastExpiredBefore)
    }

    @Test
    fun `removeExpiredBefore passes the timestamp through when healthy`() {
        val dao = FakePathDao()
        val store = RoomPathStore(dao, DirectExecutorService())

        store.removeExpiredBefore(456L)

        assertEquals(456L, dao.lastExpiredBefore)
    }

    private fun samplePathEntry() =
        PathEntry(
            timestamp = 1L,
            nextHop = ByteArray(16),
            hops = 1,
            expires = 2L,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = ByteArray(8),
            announcePacketHash = ByteArray(16),
        )

    private class FakePathDao(
        private val failure: RuntimeException? = null,
    ) : PathDao {
        var upsertCount = 0
        var deleteByHashCount = 0
        var lastExpiredBefore: Long? = null

        override fun upsert(entity: PathEntity) {
            upsertCount++
            failure?.let { throw it }
        }

        override fun getAll(): List<PathEntity> = emptyList()

        override fun deleteByHash(destHash: ByteArray) {
            deleteByHashCount++
            failure?.let { throw it }
        }

        override fun deleteExpiredBefore(timestampMs: Long) {
            lastExpiredBefore = timestampMs
            failure?.let { throw it }
        }
    }
}
