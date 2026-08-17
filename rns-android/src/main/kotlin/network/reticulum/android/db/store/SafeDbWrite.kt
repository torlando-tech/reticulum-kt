package network.reticulum.android.db.store

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.math.min
import network.reticulum.common.ByteArrayKey

private const val TAG = "RoomStore"

/**
 * Submit a best-effort, write-through persistence task to this executor.
 *
 * Reticulum's Room stores (paths, packet hashes, cached announces) are
 * write-through caches whose contents are reconstructable from the live
 * network — losing a single write is acceptable; crashing the process is
 * not.
 *
 * [network.reticulum.android.lifecycle.StoreLifecycle.drain] quiesces this
 * executor before the `RoomDatabase` is closed on service teardown, but by
 * design it closes the DB even when a write is still in flight (its
 * `Forced`/`Stuck`/`Interrupted` outcomes — the drain budget is bounded so
 * a foreground-service `onDestroy` can't ANR, and Room/SQLite writes don't
 * observe thread interruption). A write caught in that residual window
 * throws from deep inside Room:
 *
 *  - [IllegalStateException] — "Cannot perform this operation because there
 *    is no current transaction" (Sentry COLUMBA-B7, close landed between
 *    `beginTransaction` and `endTransaction`), "...the connection pool has
 *    been closed" (COLUMBA-8X, close before the write's transaction began),
 *    or "attempt to re-open an already-closed object" (COLUMBA-8R, close
 *    mid-transaction).
 *  - [SQLException] — a transient SQLite failure (e.g. `SQLITE_FULL` on a
 *    device low on storage, or a lock timeout) unrelated to teardown.
 *
 * and if the executor was already `shutdown()` by the drain,
 * [ExecutorService.execute] itself throws [RejectedExecutionException] on
 * the calling (RNS) thread. On the dedicated write thread an unhandled
 * exception reaches the thread's uncaught handler and is **fatal** — these
 * are the crashes above. Swallow + log at WARN instead; the dropped write
 * is rebuilt from the next announce.
 *
 * [IllegalStateException] is caught broadly on purpose. The close race
 * surfaces as several different framework messages — "no current
 * transaction" / "connection pool has been closed" / "already-closed
 * object", and (COLUMBA-B7 was itself a late-discovered variant of 8X/8R)
 * plausibly others — so matching on message text would risk missing a
 * variant and reintroducing the crash. The trade-off is that an
 * [IllegalStateException] from a genuine logic error would also be
 * swallowed, so each [block] is kept to plain DAO calls with no
 * `check`/`error`/`require` and no main-thread DB access — there is no such
 * error here to mask. Keep them that way.
 *
 * @param op short operation label for the dropped-write log line.
 */
internal fun ExecutorService.submitWriteThrough(op: String, block: () -> Unit) {
    try {
        execute {
            try {
                block()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Dropped DB write '$op'; database closed mid-write: ${e.message}")
            } catch (e: SQLException) {
                Log.w(TAG, "Dropped DB write '$op'; transient SQLite error: ${e.message}")
            }
        }
    } catch (e: RejectedExecutionException) {
        // Executor already shutdown() by StoreLifecycle during service teardown.
        Log.w(TAG, "DB write '$op' rejected; executor already shut down")
    }
}

// Bounded retry budget for a transient SQLite lock on a security-relevant
// durable write. Small backoff doubles between attempts; the total worst-case
// stall of a single attempt sequence is bounded so a foreground-service write
// thread can never ANR.
private const val MAX_LOCK_ATTEMPTS = 3
private const val INITIAL_LOCK_BACKOFF_MS = 10L
private const val MAX_LOCK_BACKOFF_MS = 40L

// Per-turn cap on how many pending durable writes one successful write may
// reconcile, so a single executor task's reconciliation work stays bounded as
// the backlog grows (see flushPendingDurableWrites).
internal const val MAX_PENDING_FLUSH_PER_TURN = 16

// Durable (identity/ratchet) writes whose bounded lock-retry budget was
// exhausted while SQLite stayed locked. They MUST be durable and are NOT
// reconstructable from the live network, so they are never permanently
// dropped: each stays pending and is reconciled (flushed) into Room at the
// next safe point — a successful durable write, which proves the lock has
// cleared. Mutated only from the single write-executor thread (or the test
// thread), so the synchronized wrappers are belt-and-braces; each re-attempt
// uses the same bounded budget, so nothing unbounded blocks the write thread.
//
// The pending set is scoped to the owning executor — not process-global, as
// the previous correction was — so one writer/database instance can never
// flush another instance's backlog. Entries are coalesced by a row key
// (table namespace + dest_hash) so same-key writes reconcile latest-write-wins:
// an older pending write for a key is superseded by a newer one, and a write
// that commits removes any older pending entry for the same key before the
// flush runs, so replay can never regress durable state to a stale value.
internal class DurableRowKey(val namespace: String, val destHash: ByteArrayKey) {
    override fun equals(other: Any?): Boolean =
        other is DurableRowKey && namespace == other.namespace && destHash == other.destHash

    override fun hashCode(): Int = 31 * namespace.hashCode() + destHash.hashCode()

    override fun toString(): String = "$namespace:${destHash}"
}

/**
 * Instance-owned pending durable-write state for ONE durable writer/store
 * (e.g. [RoomIdentityStore]) — NOT a process-global registry keyed by
 * `ExecutorService`.
 *
 * Owning the state per writer/store with an explicit lifecycle boundary
 * ([dispose]) closes the teardown defect of the previous process-global map:
 * a retired writer's `ExecutorService`, DAO-capturing lambdas, and
 * `RoomDatabase` are released when the writer's lifecycle ends, and a
 * replacement writer/store never observes or flushes the retired instance's
 * backlog even when both share an executor. Mutated only from the single
 * write-executor thread (or the test thread); the synchronized wrappers are
 * belt-and-braces. Entries are coalesced by a row key (table namespace +
 * dest_hash) so same-key writes reconcile latest-write-wins: an older pending
 * write for a key is superseded by a newer one, and a write that commits
 * removes any older pending entry for the same key before the flush runs.
 */
internal class DurableWriteState {

    private val pending = mutableMapOf<DurableRowKey, Pair<String, () -> Unit>>()
    @Volatile private var disposed = false

    /**
     * Take at most [MAX_PENDING_FLUSH_PER_TURN] pending entries for one bounded
     * flush turn, removing them from the map; entries that remain stay pending
     * (coalesced under their key) and are reconciled by later bounded turns.
     */
    @Synchronized
    fun takeTurn(): ArrayList<Pair<DurableRowKey, Pair<String, () -> Unit>>> {
        val taken = ArrayList<Pair<DurableRowKey, Pair<String, () -> Unit>>>(MAX_PENDING_FLUSH_PER_TURN)
        val it = pending.iterator()
        while (it.hasNext() && taken.size < MAX_PENDING_FLUSH_PER_TURN) {
            val e = it.next()
            taken.add(e.key to e.value)
            it.remove()
        }
        return taken
    }

    /** Keep a retry-exhausted durable write pending for reconciliation. No-op after [dispose]. */
    @Synchronized
    fun put(key: DurableRowKey, op: String, block: () -> Unit) {
        if (!disposed) pending[key] = op to block
    }

    /** A committed write removes any older pending entry for the same [key] (latest-write-wins). */
    @Synchronized
    fun remove(key: DurableRowKey) {
        if (!disposed) pending.remove(key)
    }

    /** Re-queue a reconciled entry that stayed locked after one bounded re-attempt. */
    @Synchronized
    fun requeue(key: DurableRowKey, entry: Pair<String, () -> Unit>) {
        if (!disposed) pending[key] = entry
    }

    /**
     * Explicit lifecycle boundary: release every pending entry (and the
     * DAO-capturing lambda it holds) and mark the state disposed so no later
     * write can resurrect or flush the retired backlog. Called by the owning
     * writer/store on teardown after the executor is drained and before/with
     * the `RoomDatabase` close.
     */
    @Synchronized
    fun dispose() {
        disposed = true
        pending.clear()
    }
}

private enum class DurableWriteOutcome { COMMITTED, LOCK_EXHAUSTED, DROPPED }

// Runs [block] through the bounded SQLite-lock retry budget and reports how
// it ended. COMMITTED — written durably. LOCK_EXHAUSTED — SQLite stayed locked
// through the whole budget (the caller keeps the write pending for
// reconciliation). DROPPED — an immediate, non-retryable drop: the
// [IllegalStateException] close-race (DB closed mid-write), a non-lock
// [SQLException], or an interrupt while awaiting the lock. Those keep exactly
// the [submitWriteThrough] semantics — a write cannot be made durable once the
// `RoomDatabase` is gone.
private fun attemptDurableWrite(op: String, block: () -> Unit): DurableWriteOutcome {
    var attempt = 0
    var backoffMs = INITIAL_LOCK_BACKOFF_MS
    while (true) {
        try {
            block()
            return DurableWriteOutcome.COMMITTED
        } catch (e: SQLiteDatabaseLockedException) {
            attempt++
            if (attempt >= MAX_LOCK_ATTEMPTS) {
                return DurableWriteOutcome.LOCK_EXHAUSTED
            }
            try {
                Thread.sleep(backoffMs)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "DB write '$op' interrupted while awaiting SQLite lock: ${ie.message}")
                return DurableWriteOutcome.DROPPED
            }
            backoffMs = min(backoffMs * 2, MAX_LOCK_BACKOFF_MS)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Dropped DB write '$op'; database closed mid-write: ${e.message}")
            return DurableWriteOutcome.DROPPED
        } catch (e: SQLException) {
            Log.w(TAG, "Dropped DB write '$op'; transient SQLite error: ${e.message}")
            return DurableWriteOutcome.DROPPED
        }
    }
}

// Reconcile a BOUNDED number of pending durable writes now that a write has
// just succeeded (the lock cleared). Each turn takes at most
// [MAX_PENDING_FLUSH_PER_TURN] pending entries, so one executor task performs
// only a fixed amount of reconciliation work no matter how large the backlog
// grows; entries that remain after the turn stay pending (coalesced under their
// key) and are reconciled by later bounded turns — the next successful durable
// writes. Each pending block is re-attempted once with the same bounded budget;
// one that still exhausts stays pending, one that is dropped (DB closed /
// non-lock error) is removed — it can no longer be made durable. Runs on the
// write thread for the owning executor and never spins.
private fun flushPendingDurableWrites(state: DurableWriteState) {
    val turn = state.takeTurn()
    for ((key, entry) in turn) {
        val (op, block) = entry
        when (attemptDurableWrite(op, block)) {
            DurableWriteOutcome.COMMITTED -> { /* reconciled durably */ }
            DurableWriteOutcome.LOCK_EXHAUSTED -> state.requeue(key, entry)
            DurableWriteOutcome.DROPPED -> { /* can no longer be made durable */ }
        }
    }
}

/**
 * Submit a write-through persistence task whose contents MUST be durable —
 * the security-relevant identity/ratchet state stored by [RoomIdentityStore]
 * is not reconstructable from the live network (unlike the announce/path/
 * packet-hash caches that [submitWriteThrough] serves).
 *
 * The differences from [submitWriteThrough]: a transient SQLite lock
 * ([SQLiteDatabaseLockedException], e.g. "database is locked" / SQLITE_BUSY)
 * is retried a bounded number of times with a small doubling backoff, so a
 * momentary lock cannot silently discard a ratchet/identity write that would
 * otherwise leave durable state stale or missing after a restart. And, unlike
 * [submitWriteThrough], retry exhaustion is NOT a permanent drop: the write is
 * kept pending and reconciled (flushed) into Room at the next safe point — a
 * successful durable write, which proves the lock has cleared — so the
 * security-relevant value is never silently lost even when another writer
 * holds the lock longer than the bounded retry window.
 *
 * [key] is the durable row identity (table namespace + dest_hash) used to
 * coalesce the pending set latest-write-wins. Reconciliation is bounded per
 * successful write ([MAX_PENDING_FLUSH_PER_TURN] entries per turn) and scoped
 * to the [state] instance owned by this durable writer, so a single task never
 * drains an unbounded backlog and one writer/store instance can never flush
 * another instance's backlog — even when they share an executor. A write that
 * commits removes any older pending entry for the same [key] before the flush
 * runs, so a stale pending write can never replay over newer state.
 *
 * [IllegalStateException] (DB closed mid-write during teardown) and every
 * other [SQLException] (non-lock transient SQLite failure) are still dropped
 * immediately, exactly as in [submitWriteThrough] — the close-race semantics
 * and the reconstructable-cache policy are unchanged; a write cannot be made
 * durable once the `RoomDatabase` is gone. The [IllegalStateException] catch
 * is deliberately NOT broadened: each [block] stays a plain DAO call with no
 * main-thread DB access, matching the [submitWriteThrough] contract.
 *
 * @param state instance-owned pending durable-write state for this writer;
 *   released via [DurableWriteState.dispose] on the owning writer/store
 *   lifecycle teardown.
 * @param op short operation label for the dropped-write log line.
 * @param key durable row identity used to coalesce same-key pending writes
 *   latest-write-wins.
 */
internal fun ExecutorService.submitWriteThroughDurable(
    state: DurableWriteState,
    op: String, key: DurableRowKey, block: () -> Unit
) {
    try {
        execute {
            when (attemptDurableWrite(op, block)) {
                DurableWriteOutcome.COMMITTED -> {
                    // The value just committed is newer than any pending write
                    // for the same key — remove the stale pending entry so a
                    // later flush can never replay it over the newer value
                    // (latest-write-wins), then reconcile a bounded turn.
                    state.remove(key)
                    flushPendingDurableWrites(state)
                }
                DurableWriteOutcome.LOCK_EXHAUSTED -> {
                    Log.w(TAG, "DB write '$op'; SQLite locked after $MAX_LOCK_ATTEMPTS attempts; keeping pending for reconciliation")
                    state.put(key, op, block)
                }
                DurableWriteOutcome.DROPPED -> { /* dropped immediately, as above */ }
            }
        }
    } catch (e: RejectedExecutionException) {
        // Executor already shutdown() by StoreLifecycle during service teardown.
        Log.w(TAG, "DB write '$op' rejected; executor already shut down")
    }
}
