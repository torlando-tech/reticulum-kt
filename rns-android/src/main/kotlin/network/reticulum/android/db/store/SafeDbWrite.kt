package network.reticulum.android.db.store

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.math.min

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

// Durable (identity/ratchet) writes whose bounded lock-retry budget was
// exhausted while SQLite stayed locked. They MUST be durable and are NOT
// reconstructable from the live network, so they are never permanently
// dropped: each stays pending and is reconciled (flushed) into Room at the
// next safe point — the next successful durable write, which proves the lock
// has cleared. Mutated only from the single write-executor thread (or the
// test thread), so the synchronized wrappers are belt-and-braces; the list is
// drained once per flush, never spun, and each re-attempt uses the same
// bounded budget, so nothing unbounded blocks the write thread.
private val pendingDurableWrites = mutableListOf<Pair<String, () -> Unit>>()

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

// Reconcile any pending durable writes now that a write has just succeeded
// (the lock cleared). Re-attempts each pending block once with the same
// bounded budget; a pending write that still exhausts stays pending for a
// later flush, one that is dropped (DB closed / non-lock error) is removed —
// it can no longer be made durable. Runs on the write thread, drains the list
// once, and never spins.
private fun flushPendingDurableWrites() {
    val pending = synchronized(pendingDurableWrites) {
        pendingDurableWrites.toList().also { pendingDurableWrites.clear() }
    }
    for ((op, block) in pending) {
        when (attemptDurableWrite(op, block)) {
            DurableWriteOutcome.COMMITTED -> { /* reconciled durably */ }
            DurableWriteOutcome.LOCK_EXHAUSTED -> synchronized(pendingDurableWrites) {
                pendingDurableWrites.add(op to block)
            }
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
 * kept pending and reconciled (flushed) into Room at the next safe point — the
 * next successful durable write, which proves the lock has cleared — so the
 * security-relevant value is never silently lost even when another writer
 * holds the lock longer than the bounded retry window.
 *
 * [IllegalStateException] (DB closed mid-write during teardown) and every
 * other [SQLException] (non-lock transient SQLite failure) are still dropped
 * immediately, exactly as in [submitWriteThrough] — the close-race semantics
 * and the reconstructable-cache policy are unchanged; a write cannot be made
 * durable once the `RoomDatabase` is gone. The [IllegalStateException] catch
 * is deliberately NOT broadened: each [block] stays a plain DAO call with no
 * main-thread DB access, matching the [submitWriteThrough] contract.
 *
 * @param op short operation label for the dropped-write log line.
 */
internal fun ExecutorService.submitWriteThroughDurable(op: String, block: () -> Unit) {
    try {
        execute {
            when (attemptDurableWrite(op, block)) {
                DurableWriteOutcome.COMMITTED -> flushPendingDurableWrites()
                DurableWriteOutcome.LOCK_EXHAUSTED -> {
                    Log.w(TAG, "DB write '$op'; SQLite locked after $MAX_LOCK_ATTEMPTS attempts; keeping pending for reconciliation")
                    synchronized(pendingDurableWrites) { pendingDurableWrites.add(op to block) }
                }
                DurableWriteOutcome.DROPPED -> { /* dropped immediately, as above */ }
            }
        }
    } catch (e: RejectedExecutionException) {
        // Executor already shutdown() by StoreLifecycle during service teardown.
        Log.w(TAG, "DB write '$op' rejected; executor already shut down")
    }
}
