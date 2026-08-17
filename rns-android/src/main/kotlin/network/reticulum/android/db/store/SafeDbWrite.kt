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
// stall is bounded so a foreground-service write thread can never ANR.
private const val MAX_LOCK_ATTEMPTS = 3
private const val INITIAL_LOCK_BACKOFF_MS = 10L
private const val MAX_LOCK_BACKOFF_MS = 40L

/**
 * Submit a write-through persistence task whose contents MUST be durable —
 * the security-relevant identity/ratchet state stored by [RoomIdentityStore]
 * is not reconstructable from the live network (unlike the announce/path/
 * packet-hash caches that [submitWriteThrough] serves).
 *
 * The one difference from [submitWriteThrough]: a transient SQLite lock
 * ([SQLiteDatabaseLockedException], e.g. "database is locked" / SQLITE_BUSY)
 * is retried a bounded number of times with a small doubling backoff, so a
 * momentary lock cannot silently discard a ratchet/identity write that would
 * otherwise leave durable state stale or missing after a restart. Only after
 * the bounded budget is exhausted does the write fall back to the same
 * drop-and-log behavior as [submitWriteThrough].
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
            var attempt = 0
            var backoffMs = INITIAL_LOCK_BACKOFF_MS
            while (true) {
                try {
                    block()
                    return@execute // committed durably
                } catch (e: SQLiteDatabaseLockedException) {
                    attempt++
                    if (attempt >= MAX_LOCK_ATTEMPTS) {
                        Log.w(TAG, "Dropped DB write '$op'; SQLite locked after $MAX_LOCK_ATTEMPTS attempts: ${e.message}")
                        return@execute
                    }
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.w(TAG, "Dropped DB write '$op'; interrupted while awaiting SQLite lock: ${ie.message}")
                        return@execute
                    }
                    backoffMs = min(backoffMs * 2, MAX_LOCK_BACKOFF_MS)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Dropped DB write '$op'; database closed mid-write: ${e.message}")
                    return@execute
                } catch (e: SQLException) {
                    Log.w(TAG, "Dropped DB write '$op'; transient SQLite error: ${e.message}")
                    return@execute
                }
            }
        }
    } catch (e: RejectedExecutionException) {
        // Executor already shutdown() by StoreLifecycle during service teardown.
        Log.w(TAG, "DB write '$op' rejected; executor already shut down")
    }
}
