package network.reticulum.android.lifecycle

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Helper for shutting down a Room write executor before its associated
 * `RoomDatabase` is closed.
 *
 * Concurrency invariant being enforced
 * ------------------------------------
 *
 * `RoomDatabase.close()` releases the SQLite connection pool. Any work
 * still queued on the write executor that subsequently runs against
 * that database hits one of two fatal failure modes:
 *
 *   1. `IllegalStateException: Cannot perform this operation because
 *      the connection pool has been closed` — write hadn't started its
 *      transaction yet; throws at `beginTransaction → throwIfClosedLocked`.
 *      (Sentry COLUMBA-8X.)
 *   2. `IllegalStateException: attempt to re-open an already-closed
 *      object: SQLiteDatabase` — write was *mid-transaction* when the
 *      database closed; throws at `endTransaction → acquireReference`.
 *      (Sentry COLUMBA-8R.)
 *
 * Both are downstream symptoms of the same race: `database.close()`
 * outpaced a queued or in-flight Room write on the executor. The naive
 * pattern is `executor.shutdown(); executor.awaitTermination(N, SECONDS);
 * database.close()` — but if the boolean return of `awaitTermination`
 * is ignored (or not present) and a chunked write workload doesn't
 * drain in `N` seconds, the close still fires while writes are
 * pending. Reticulum's final flush via `packetHashStore.saveAll(...)`
 * routinely posts a `deleteByGeneration` plus N chunked `insertAll(500)`
 * transactions that can take well past 5s to drain on a low-end device.
 *
 * What this helper does
 * ---------------------
 *
 *   1. `executor.shutdown()` — refuse new submissions, let queued tasks
 *      keep running.
 *   2. `awaitTermination(graceful)` — block up to `graceful` for queued
 *      tasks to complete naturally. Capture the boolean return.
 *   3. If graceful timed out, `executor.shutdownNow()` — interrupt
 *      in-flight tasks and drop the rest of the queue. Then
 *      `awaitTermination(force)` to give interrupted tasks a chance to
 *      unwind.
 *   4. Return a [DrainOutcome] describing what happened, so callers
 *      can log or surface partial-data risk.
 *
 * After [drain] returns, no more tasks will execute on the supplied
 * executor — it's safe to close the associated database.
 *
 * On `InterruptedException` during the wait (caller's thread was
 * interrupted by something else), reassert the interrupt and call
 * `shutdownNow()` so the executor's queue isn't left hanging across
 * shutdown.
 */
class StoreLifecycle(
    private val gracefulMillis: Long = 15_000,
    private val forceMillis: Long = 5_000,
    private val log: (String) -> Unit = {},
) {
    enum class DrainOutcome {
        /** All queued tasks completed within the graceful window. */
        Drained,

        /**
         * Graceful window elapsed before the queue emptied; `shutdownNow()`
         * was called and the force-window completed cleanly. In-flight
         * writes were interrupted; queued writes were dropped.
         */
        Forced,

        /**
         * Even after `shutdownNow()` plus the force window, the executor
         * is still running. Caller is closing the database anyway because
         * leaving it open across shutdown is worse than losing the writes.
         */
        Stuck,

        /**
         * The thread calling [drain] was interrupted while waiting.
         * `shutdownNow()` was called and the interrupt re-asserted.
         */
        Interrupted,
    }

    /**
     * Drain [executor] before its database can be safely closed.
     *
     * @return what kind of drain happened (see [DrainOutcome]).
     */
    fun drain(executor: ExecutorService): DrainOutcome {
        executor.shutdown()
        return try {
            if (executor.awaitTermination(gracefulMillis, TimeUnit.MILLISECONDS)) {
                DrainOutcome.Drained
            } else {
                log(
                    "DB write executor did not drain within ${gracefulMillis}ms; " +
                        "forcing shutdownNow. Some persisted writes may be lost.",
                )
                executor.shutdownNow()
                if (executor.awaitTermination(forceMillis, TimeUnit.MILLISECONDS)) {
                    DrainOutcome.Forced
                } else {
                    log("DB write executor still running after shutdownNow + ${forceMillis}ms wait")
                    DrainOutcome.Stuck
                }
            }
        } catch (_: InterruptedException) {
            // Best-effort: re-assert the interrupt and abandon the wait.
            // We still call shutdownNow so the executor's queue isn't
            // left hanging across the rest of teardown.
            Thread.currentThread().interrupt()
            executor.shutdownNow()
            DrainOutcome.Interrupted
        }
    }
}
