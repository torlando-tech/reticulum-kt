package network.reticulum.android.db.store

import network.reticulum.android.db.dao.PacketHashDao
import network.reticulum.android.db.entity.PacketHashEntity
import network.reticulum.common.ByteArrayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * COLUMBA-B7 sibling coverage for [RoomPacketHashStore]. Shares the
 * close-during-write race with [RoomAnnounceStore]; see [RoomAnnounceStoreTest]
 * for the full root-cause notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomPacketHashStoreTest {

    private val noCurrentTransaction =
        IllegalStateException("Cannot perform this operation because there is no current transaction.")

    @Test
    fun `saveAll swallows a closed-DB IllegalStateException (documented partial-clear)`() {
        // saveAll is a two-phase write: deleteByGeneration THEN batched insertAll.
        // If teardown closes the DB after the delete but before the insert, the guard
        // drops the insert — loadAll() then sees an empty generation. That partial
        // clear is accepted (best-effort cache), and it must not crash.
        val dao = FakePacketHashDao(failOnInsert = noCurrentTransaction)
        val store = RoomPacketHashStore(dao, DirectExecutorService())

        store.saveAll(setOf(ByteArrayKey(byteArrayOf(1))), generation = 0)

        assertEquals("delete phase ran", 1, dao.deleteByGenerationCount)
        assertTrue("insert phase was attempted then dropped", dao.insertAttempted)
    }

    @Test
    fun `clear swallows a closed-DB IllegalStateException`() {
        val dao = FakePacketHashDao(failOnDeleteAll = noCurrentTransaction)
        val store = RoomPacketHashStore(dao, DirectExecutorService())

        store.clear()

        assertEquals(1, dao.deleteAllCount)
    }

    @Test
    fun `saveAll deletes the generation then inserts the hashes when healthy`() {
        val dao = FakePacketHashDao()
        val store = RoomPacketHashStore(dao, DirectExecutorService())

        store.saveAll(setOf(ByteArrayKey(byteArrayOf(1)), ByteArrayKey(byteArrayOf(2))), generation = 3)

        assertEquals(1, dao.deleteByGenerationCount)
        assertEquals(2, dao.inserted.size)
    }

    @Test
    fun `loadCapped bounds each generation at the requested limit`() {
        val dao = FakePacketHashDao()
        dao.insertAll(
            (0 until 10).map { PacketHashEntity(hash = hashOf(it), generation = 0) } +
                (0 until 10).map { PacketHashEntity(hash = hashOf(it + 100), generation = 1) },
        )
        val store = RoomPacketHashStore(dao, DirectExecutorService())

        val (current, prev) = store.loadCapped(limitPerGeneration = 3)

        assertEquals(3, current.size)
        assertEquals(3, prev.size)
        assertEquals(3, dao.lastLimit)
    }

    private fun hashOf(value: Int): ByteArray = ByteArray(32) { value.toByte() }

    private class FakePacketHashDao(
        private val failOnInsert: RuntimeException? = null,
        private val failOnDeleteAll: RuntimeException? = null,
    ) : PacketHashDao {
        var deleteByGenerationCount = 0
        var deleteAllCount = 0
        var insertAttempted = false
        var lastLimit: Int? = null
        val inserted = mutableListOf<PacketHashEntity>()
        val stored = mutableListOf<PacketHashEntity>()

        override fun insertAll(entities: List<PacketHashEntity>) {
            insertAttempted = true
            failOnInsert?.let { throw it }
            inserted.addAll(entities)
            stored.addAll(entities)
        }

        override fun getByGeneration(generation: Int): List<PacketHashEntity> =
            stored.filter { it.generation == generation }

        override fun getByGenerationLimited(generation: Int, limit: Int): List<PacketHashEntity> {
            lastLimit = limit
            return stored.filter { it.generation == generation }.take(limit)
        }

        override fun deleteByGeneration(generation: Int) {
            deleteByGenerationCount++
        }

        override fun deleteAll() {
            deleteAllCount++
            failOnDeleteAll?.let { throw it }
        }
    }
}
