package network.reticulum.android.db.store

import android.database.sqlite.SQLiteDatabaseLockedException
import network.reticulum.android.db.dao.IdentityRatchetDao
import network.reticulum.android.db.dao.KnownDestinationDao
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.identity.Identity.IdentityData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for Sentry COLUMBA-D4.
 *
 * [RoomIdentityStore] is a write-through cache backed by a single Room write
 * executor, exactly like its siblings [RoomAnnounceStore] / [RoomPathStore] /
 * [RoomPacketHashStore]. But it was never migrated off the bare
 * `writeExecutor.execute { dao.upsert(...) }` call — it bypasses the
 * [submitWriteThrough] policy its siblings use. When SQLite raises
 * SQLITE_BUSY (`android.database.sqlite.SQLiteDatabaseLockedException`,
 * "database is locked") from inside the write task, the exception escapes the
 * executor uncaught and, on the dedicated NativeReticulumDB-write thread,
 * reaches the uncaught handler — a fatal crash. That is COLUMBA-D4
 * (culprit `SQLiteConnection.nativeExecute`, frame
 * `RoomIdentityStore.upsertKnownDestination$lambda$0`).
 *
 * A live SQLITE_BUSY can't be reproduced deterministically through a real
 * Robolectric Room DB (host JDBC SQLite no-ops writes after `close()` instead
 * of throwing — see [RoomAnnounceStoreTest]), so the test injects the real
 * `SQLiteDatabaseLockedException` through a fake [KnownDestinationDao] and runs
 * the production write lambda inline on the test thread via
 * [DirectExecutorService]. The store method, the executor, and the lambda are
 * all production code; only the DAO is a fake that raises the exact real
 * exception type. Pre-fix the exception escapes the `writeExecutor.execute`
 * lambda and the test fails; post-fix [submitWriteThrough] swallows it (its
 * `SQLException` branch covers `SQLiteDatabaseLockedException` by inheritance).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomIdentityStoreTest {

    @Test
    fun `upsertKnownDestination swallows a SQLITE_BUSY SQLiteDatabaseLockedException instead of crashing the write thread`() {
        val dao = FakeKnownDestinationDao(
            failure = SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)")
        )
        val store = RoomIdentityStore(dao, NoopIdentityRatchetDao(), DirectExecutorService())

        // Pre-fix: upsertKnownDestination bypasses submitWriteThrough, so this
        // SQLiteDatabaseLockedException escapes the writeExecutor.execute lambda
        // and, on the NativeReticulumDB-write thread, reaches the uncaught handler
        // — fatal (COLUMBA-D4). Post-fix: submitWriteThrough's SQLException branch
        // swallows it.
        store.upsertKnownDestination(byteArrayOf(1), sampleIdentityData())

        // Reaching here means the exception did not escape; the write was
        // attempted once then dropped.
        assertEquals(1, dao.upsertCount)
    }

    private fun sampleIdentityData() = IdentityData(
        timestamp = 1L,
        packetHash = byteArrayOf(2, 3),
        // 32-byte X25519 public key.
        publicKey = byteArrayOf(
            4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
            32, 33, 34, 35
        ),
        appData = byteArrayOf(9)
    )

    private class FakeKnownDestinationDao(
        private val failure: Exception? = null,
    ) : KnownDestinationDao {
        var upsertCount = 0

        override fun upsert(entity: KnownDestinationEntity) {
            upsertCount++
            failure?.let { throw it }
        }

        override fun getByHash(destHash: ByteArray): KnownDestinationEntity? = null
        override fun getAll(): List<KnownDestinationEntity> = emptyList()
        override fun count(): Int = 0
    }

    private class NoopIdentityRatchetDao : IdentityRatchetDao {
        override fun upsert(entity: IdentityRatchetEntity) = Unit
        override fun getByHash(destHash: ByteArray): IdentityRatchetEntity? = null
        override fun deleteExpiredBefore(thresholdMs: Long) = Unit
    }
}
