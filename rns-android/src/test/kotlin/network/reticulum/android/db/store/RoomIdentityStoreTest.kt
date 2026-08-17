package network.reticulum.android.db.store

import android.database.sqlite.SQLiteDatabaseLockedException
import network.reticulum.android.db.dao.IdentityRatchetDao
import network.reticulum.android.db.dao.KnownDestinationDao
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.identity.Identity.IdentityData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * `SQLiteDatabaseLockedException` through a fake DAO and runs the production
 * write lambda inline on the test thread via [DirectExecutorService]. The
 * store method, the executor, and the lambda are all production code; only
 * the DAO is a fake that raises the exact real exception type. Pre-fix the
 * exception escapes the `writeExecutor.execute` lambda and the test fails;
 * post-fix [submitWriteThrough] swallows it (its `SQLException` branch covers
 * `SQLiteDatabaseLockedException` by inheritance).
 *
 * The identity/ratchet state written by this store is security-relevant and is
 * NOT reconstructable from the live network (unlike the announce/path/
 * packet-hash caches), so its writes route through
 * [submitWriteThroughDurable]: a transient SQLite lock is retried a bounded
 * number of times so a momentary lock cannot silently discard the durable
 * write. The D4 crash-escape guarantee is preserved — the lock still never
 * escapes the write thread.
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
        // — fatal (COLUMBA-D4). Post-fix: submitWriteThroughDurable retries the
        // transient lock a bounded number of times, then drops it — it never
        // escapes the write thread.
        store.upsertKnownDestination(byteArrayOf(1), sampleIdentityData())

        // Reaching here means the exception did not escape; the write was
        // attempted the bounded retry budget times (1 + retries = 3), then
        // dropped. Every attempt reached the DAO before the drop.
        assertEquals(3, dao.upsertCount)
    }

    /**
     * Follow-up test: the policy retries a transient SQLITE_BUSY lock and the
     * write still lands durably without degrading the write path. The DAO
     * fails its FIRST upsert with the real SQLiteDatabaseLockedException and
     * succeeds on the second; the single write must be retried and committed
     * and no exception may escape. This guards against a "fix" that works by
     * broadly swallowing arbitrary production exceptions or by permanently
     * dropping the security-relevant path.
     */
    @Test
    fun `upsertKnownDestination retries one SQLITE_BUSY lock and durably commits the identity write`() {
        val dao = FailFirstKnownDestinationDao(
            firstCallFailure = SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)")
        )
        val store = RoomIdentityStore(dao, NoopIdentityRatchetDao(), DirectExecutorService())

        // First write: transient lock — retried by submitWriteThroughDurable,
        // must not escape and must still land durably.
        store.upsertKnownDestination(byteArrayOf(1), sampleIdentityData())
        // Second write: must still reach the DAO and succeed.
        store.upsertKnownDestination(byteArrayOf(1), sampleIdentityData())

        // Under the durable contract the FIRST write is retried and committed
        // (attempt 1 locked, attempt 2 committed), then the second write lands:
        // 3 upsert calls total. No exception escaped.
        assertEquals(3, dao.upsertCount)
    }

    /**
     * New durability regression (Greptile P1): a security-relevant RATCHET
     * upsert that hits one transient SQLiteDatabaseLockedException must end
     * with the durable ratchet state PRESENT — the single write itself is
     * retried and committed, not merely "the next independent write lands".
     * Before the durable correction, submitWriteThrough dropped the write on
     * the lock and the ratchet existed only in memory: restarting would
     * restore stale/missing ratchet state until the peer re-announced.
     */
    @Test
    fun `upsertRatchet retries one transient SQLite lock and the durable ratchet state is present`() {
        val dao = FailOnceThenCommitRatchetDao(
            firstCallFailure = SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)")
        )
        val store = RoomIdentityStore(NoopKnownDestinationDao(), dao, DirectExecutorService())

        // The security-relevant ratchet write must NOT be silently discarded on
        // a transient lock: after one SQLiteDatabaseLockedException the durable
        // ratchet state must be present (the write is retried and committed).
        store.upsertRatchet(byteArrayOf(1), byteArrayOf(7, 8, 9), timestampMs = 42L)

        val committed = dao.committed
        assertNotNull(committed)
        assertArrayEquals(byteArrayOf(1), committed!!.destHash)
        assertArrayEquals(byteArrayOf(7, 8, 9), committed.ratchet)
        assertEquals(42L, committed.timestamp)
        // First attempt locked, retried and committed.
        assertEquals(2, dao.upsertCount)
    }

    /**
     * Greptile P1 regression (retry-exhaustion discard): when SQLite stays
     * locked through the ENTIRE bounded retry budget (3 attempts), the
     * security-relevant ratchet write must NOT be permanently dropped. The
     * value is not reconstructable from the live network, so it must be kept
     * pending and reconciled into Room at the next safe point (flush on the
     * next successful durable write). Pre-fix the exhaustion branch logs and
     * drops the write, so the value never reappears after a later successful
     * write — this test fails (red). Post-fix the pending value is flushed and
     * lands durably.
     */
    @Test
    fun `ratchet retry exhaustion keeps the durable write pending and reconciles it on the next successful write`() {
        // Locks for the first 3 upsert calls (the whole bounded budget of the
        // first write), then commits. The first ratchet write must exhaust the
        // budget and go PENDING, not be dropped; a later successful write must
        // flush it into Room.
        val dao = LockThenCommitRatchetDao(
            lockCalls = 3,
            lock = SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)")
        )
        val store = RoomIdentityStore(NoopKnownDestinationDao(), dao, DirectExecutorService())

        // First ratchet write: the lock persists through the whole bounded
        // retry budget. Durable state is (still) absent — the lock never
        // cleared — but the write must be kept PENDING for reconciliation,
        // not permanently dropped. Attempts stay bounded (no spin): exactly 3.
        store.upsertRatchet(byteArrayOf(1), byteArrayOf(7, 8, 9), timestampMs = 42L)
        assertNull(store.getRatchet(byteArrayOf(1)))
        assertEquals(3, dao.upsertCount)

        // A second write now succeeds (the lock clears). It must flush the
        // pending first ratchet value into Room: the durable state that was
        // "lost" on exhaustion must reappear. Pre-fix nothing rewrites it, so
        // the value stays missing and this assertion fails (red).
        store.upsertRatchet(byteArrayOf(2), byteArrayOf(10, 11, 12), timestampMs = 99L)

        val first = store.getRatchet(byteArrayOf(1))
        assertNotNull(first)
        assertArrayEquals(byteArrayOf(7, 8, 9), first!!.first)
        assertEquals(42L, first.second)

        val second = store.getRatchet(byteArrayOf(2))
        assertNotNull(second)
        assertArrayEquals(byteArrayOf(10, 11, 12), second!!.first)
        assertEquals(99L, second.second)
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

    private class FailFirstKnownDestinationDao(
        private val firstCallFailure: Exception,
    ) : KnownDestinationDao {
        var upsertCount = 0

        override fun upsert(entity: KnownDestinationEntity) {
            upsertCount++
            if (upsertCount == 1) throw firstCallFailure
        }

        override fun getByHash(destHash: ByteArray): KnownDestinationEntity? = null
        override fun getAll(): List<KnownDestinationEntity> = emptyList()
        override fun count(): Int = 0
    }

    private class FailOnceThenCommitRatchetDao(
        private val firstCallFailure: Exception,
    ) : IdentityRatchetDao {
        var upsertCount = 0
        var committed: IdentityRatchetEntity? = null

        override fun upsert(entity: IdentityRatchetEntity) {
            upsertCount++
            if (upsertCount == 1) throw firstCallFailure
            committed = entity
        }

        override fun getByHash(destHash: ByteArray): IdentityRatchetEntity? =
            committed?.takeIf { it.destHash.contentEquals(destHash) }

        override fun deleteExpiredBefore(thresholdMs: Long) = Unit
    }

    private class LockThenCommitRatchetDao(
        private val lockCalls: Int,
        private val lock: Exception,
    ) : IdentityRatchetDao {
        var upsertCount = 0
        private val committed = mutableListOf<IdentityRatchetEntity>()

        override fun upsert(entity: IdentityRatchetEntity) {
            upsertCount++
            if (upsertCount <= lockCalls) throw lock
            committed.add(entity)
        }

        override fun getByHash(destHash: ByteArray): IdentityRatchetEntity? =
            committed.firstOrNull { it.destHash.contentEquals(destHash) }

        override fun deleteExpiredBefore(thresholdMs: Long) = Unit
    }

    private class NoopKnownDestinationDao : KnownDestinationDao {
        override fun upsert(entity: KnownDestinationEntity) = Unit
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
