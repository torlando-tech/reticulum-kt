package network.reticulum.android.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.reticulum.android.db.entity.AnnounceCacheEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.store.RoomAnnounceStore
import network.reticulum.android.db.store.RoomIdentityStore
import network.reticulum.android.db.store.RoomPathStore
import network.reticulum.common.toKey
import network.reticulum.transport.PathState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * Instrumented tests for Room DAO and Store implementations.
 *
 * Uses an in-memory Room database for fast, isolated tests.
 */
@RunWith(AndroidJUnit4::class)
class RoomStoreInstrumentedTest {

    private lateinit var db: ReticulumDatabase
    private val executor = Executors.newSingleThreadExecutor()

    /** Drain the write executor by submitting a barrier task and waiting for it. */
    private fun drainExecutor() {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ReticulumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
        executor.shutdown()
    }

    // ===== PathDao Tests =====

    @Test
    fun pathDao_upsertAndRetrieve() {
        val dao = db.pathDao()
        val destHash = ByteArray(16) { it.toByte() }
        val entity = PathEntity(
            destHash = destHash,
            nextHop = ByteArray(16) { 0x01 },
            hops = 3,
            expires = System.currentTimeMillis() + 86400000,
            timestamp = System.currentTimeMillis(),
            interfaceHash = ByteArray(16) { 0x02 },
            announceHash = ByteArray(16) { 0x03 },
            state = PathState.ACTIVE.ordinal,
            failureCount = 0
        )

        dao.upsert(entity)
        val all = dao.getAll()

        assertEquals(1, all.size)
        assertEquals(3, all[0].hops)
        assertTrue(all[0].destHash.contentEquals(destHash))
    }

    @Test
    fun pathDao_upsertUpdatesExisting() {
        val dao = db.pathDao()
        val destHash = ByteArray(16) { 0xAA.toByte() }
        val now = System.currentTimeMillis()

        dao.upsert(PathEntity(destHash, ByteArray(16), 3, now + 86400000, now, ByteArray(16), ByteArray(16), 0, 0))
        dao.upsert(PathEntity(destHash, ByteArray(16), 1, now + 86400000, now, ByteArray(16), ByteArray(16), 0, 0))

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals(1, all[0].hops) // Updated to 1 hop
    }

    @Test
    fun pathDao_deleteExpired() {
        val dao = db.pathDao()
        val now = System.currentTimeMillis()

        dao.upsert(PathEntity(ByteArray(16) { 0x01 }, ByteArray(16), 1, now - 1000, now, ByteArray(16), ByteArray(16), 0, 0)) // expired
        dao.upsert(PathEntity(ByteArray(16) { 0x02 }, ByteArray(16), 1, now + 86400000, now, ByteArray(16), ByteArray(16), 0, 0)) // not expired

        dao.deleteExpiredBefore(now)

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertTrue(all[0].destHash.contentEquals(ByteArray(16) { 0x02 }))
    }

    // ===== RoomPathStore Integration =====

    @Test
    fun roomPathStore_fullCycle() {
        val store = RoomPathStore(db.pathDao(), executor)
        val destHash = ByteArray(16) { 0xBB.toByte() }
        val now = System.currentTimeMillis()

        val entry = network.reticulum.transport.PathEntry(
            timestamp = now,
            nextHop = ByteArray(16) { 0x01 },
            hops = 2,
            expires = now + 86400000,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = ByteArray(16) { 0x02 },
            announcePacketHash = ByteArray(16) { 0x03 },
            state = PathState.ACTIVE,
            failureCount = 0
        )

        // Write through executor — need to wait for it to complete
        store.upsertPath(destHash, entry)
        drainExecutor()

        val loaded = store.loadAllPaths()
        assertEquals(1, loaded.size)
        assertEquals(2, loaded[destHash.toKey()]!!.hops)
    }

    // ===== KnownDestinationDao Tests =====

    @Test
    fun knownDestinationDao_upsertAndRetrieve() {
        val dao = db.knownDestinationDao()
        val destHash = ByteArray(16) { 0xCC.toByte() }

        dao.upsert(KnownDestinationEntity(
            destHash = destHash,
            timestamp = System.currentTimeMillis(),
            packetHash = ByteArray(32) { 0x01 },
            publicKey = ByteArray(64) { 0x02 },
            appData = "test".toByteArray()
        ))

        val result = dao.getByHash(destHash)
        assertNotNull(result)
        assertEquals("test", String(result!!.appData!!))
        assertEquals(1, dao.count())
    }

    // ===== AnnounceCacheDao Tests =====

    @Test
    fun announceCacheDao_cacheAndRetrieve() {
        val dao = db.announceCacheDao()
        val hash = ByteArray(32) { it.toByte() }

        dao.upsert(AnnounceCacheEntity(
            packetHash = hash,
            raw = ByteArray(100) { 0xFF.toByte() },
            interfaceName = "TestIface"
        ))

        val result = dao.getByHash(hash)
        assertNotNull(result)
        assertEquals("TestIface", result!!.interfaceName)
        assertEquals(100, result.raw.size)
    }

    @Test
    fun announceCacheDao_deleteAllExcept() {
        val dao = db.announceCacheDao()
        val keep = ByteArray(32) { 0x01 }
        val remove = ByteArray(32) { 0x02 }

        dao.upsert(AnnounceCacheEntity(keep, ByteArray(10), "iface1"))
        dao.upsert(AnnounceCacheEntity(remove, ByteArray(10), "iface2"))

        dao.deleteAllExcept(listOf(keep))

        assertNotNull(dao.getByHash(keep))
        assertNull(dao.getByHash(remove))
    }

    // ===== IdentityRatchetDao Tests =====

    @Test
    fun identityRatchetDao_upsertAndRetrieve() {
        val dao = db.identityRatchetDao()
        val destHash = ByteArray(16) { 0xDD.toByte() }
        val ratchet = ByteArray(32) { 0xEE.toByte() }

        dao.upsert(IdentityRatchetEntity(destHash, ratchet, System.currentTimeMillis()))

        val result = dao.getByHash(destHash)
        assertNotNull(result)
        assertTrue(result!!.ratchet.contentEquals(ratchet))
    }

    @Test
    fun identityRatchetDao_deleteExpired() {
        val dao = db.identityRatchetDao()
        val now = System.currentTimeMillis()

        dao.upsert(IdentityRatchetEntity(ByteArray(16) { 0x01 }, ByteArray(32), now - 100000)) // old
        dao.upsert(IdentityRatchetEntity(ByteArray(16) { 0x02 }, ByteArray(32), now))            // current

        dao.deleteExpiredBefore(now - 50000)

        assertNull(dao.getByHash(ByteArray(16) { 0x01 }))
        assertNotNull(dao.getByHash(ByteArray(16) { 0x02 }))
    }

    // ===== RoomIdentityStore Integration =====

    @Test
    fun roomIdentityStore_ratchetRoundTrip() {
        val store = RoomIdentityStore(db.knownDestinationDao(), db.identityRatchetDao(), executor)
        val destHash = ByteArray(16) { 0xFF.toByte() }
        val ratchet = ByteArray(32) { 0xAA.toByte() }

        store.upsertRatchet(destHash, ratchet, System.currentTimeMillis())
        drainExecutor()

        val result = store.getRatchet(destHash)
        assertNotNull(result)
        assertTrue(result!!.first.contentEquals(ratchet))
    }
}
