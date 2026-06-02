package network.reticulum.android.db.store

import android.database.SQLException
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

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
