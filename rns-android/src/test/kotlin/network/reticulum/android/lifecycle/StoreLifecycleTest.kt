package network.reticulum.android.lifecycle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [StoreLifecycle] and the production race it guards against.
 *
 * Why no Room database here
 * -------------------------
 *
 * The production failure mode is `IllegalStateException` from Android's
 * `SQLiteConnectionPool.throwIfClosedLocked` (or `SQLiteClosable.acquireReference`)
 * when a queued Room write runs after `RoomDatabase.close()`. Robolectric's
 * in-memory Room runs against host JDBC SQLite, which has different
 * connection-pool semantics — writes after `db.close()` simply no-op
 * instead of throwing, so the production exception literally cannot
 * surface in `src/test`.
 *
 * The bug itself is one layer below SQLite though: it's a concurrency
 * invariant violation. The naive close pattern lets queued executor
 * tasks run AFTER the "close" marker (real DB close on device, marker
 * variable here) is set. These tests demonstrate that invariant directly:
 *
 *   - [`naive close pattern lets queued tasks execute after close`]
 *     reproduces the BUG. Asserts the bad behavior so the test
 *     catches the regression if anyone introduces it again.
 *
 *   - [`drain prevents queued tasks from executing after close`]
 *     proves the FIX. Asserts the queued task never runs, period.
 *
 * If you want full-stack coverage of the actual SQLite throw, see the
 * sibling instrumented test in `androidTest/` (TODO) — it needs an
 * emulator and isn't part of the default unit-test build.
 */
class StoreLifecycleTest {

    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "TestReticulumDB-write").apply { isDaemon = true }
        }
    }

    @After
    fun tearDown() {
        if (!executor.isShutdown) executor.shutdownNow()
    }

    /**
     * Pre-fix repro. Queues two tasks; task1 blocks on a release latch
     * so task2 stays queued. Runs the buggy close pattern (shutdown +
     * awaitTermination(short) + ignore boolean + set close marker).
     * Then releases task1. Task1 finishes, the executor runs task2,
     * task2 observes that the close marker is set — that's the race.
     *
     * On device this manifests as task2's `dao.insertAll` finding a
     * closed `SQLiteConnectionPool` (Sentry COLUMBA-8X) or a closed
     * `SQLiteDatabase` mid-transaction (Sentry COLUMBA-8R).
     */
    @Test
    fun `naive close pattern lets queued tasks execute after close`() {
        val task1Releasable = CountDownLatch(1)
        val task1Started = CountDownLatch(1)
        val task2Ran = CountDownLatch(1)
        val closeMarker = AtomicReference<Long?>(null)
        val task2ObservedClose = AtomicBoolean(false)

        executor.execute {
            task1Started.countDown()
            task1Releasable.await()
        }
        executor.execute {
            if (closeMarker.get() != null) task2ObservedClose.set(true)
            task2Ran.countDown()
        }

        // Make sure task1 is actively occupying the executor before we
        // proceed — otherwise the test races itself.
        assertTrue(task1Started.await(2, TimeUnit.SECONDS))

        // Buggy close pattern: ignore the awaitTermination boolean.
        executor.shutdown()
        executor.awaitTermination(50, TimeUnit.MILLISECONDS) // returns false, ignored
        closeMarker.set(System.nanoTime()) // "database closed"

        // Release task1 — executor now runs task2, AFTER our close marker.
        task1Releasable.countDown()
        assertTrue(
            "Task2 didn't run within 5s of task1 release",
            task2Ran.await(5, TimeUnit.SECONDS),
        )

        assertTrue(
            "Task2 should have observed the close marker — this is the production race. " +
                "If this stops failing, either ExecutorService.shutdown() changed semantics " +
                "or the harness scheduling changed.",
            task2ObservedClose.get(),
        )
    }

    /**
     * Same setup as the repro, but uses [StoreLifecycle.drain] instead
     * of the buggy pattern. Asserts task2 NEVER runs — `shutdownNow()`
     * removed it from the queue before the close marker was set.
     */
    @Test
    fun `drain prevents queued tasks from executing after close`() {
        val task1Started = CountDownLatch(1)
        val task1Releasable = CountDownLatch(1)
        val task2Ran = CountDownLatch(1)
        val closeMarker = AtomicReference<Long?>(null)
        val task2ObservedClose = AtomicBoolean(false)

        executor.execute {
            task1Started.countDown()
            try {
                task1Releasable.await()
            } catch (_: InterruptedException) {
                // shutdownNow's interrupt; expected.
                Thread.currentThread().interrupt()
            }
        }
        executor.execute {
            if (closeMarker.get() != null) task2ObservedClose.set(true)
            task2Ran.countDown()
        }

        assertTrue(task1Started.await(2, TimeUnit.SECONDS))

        val outcome = StoreLifecycle(gracefulMillis = 50, forceMillis = 1_000).drain(executor)
        closeMarker.set(System.nanoTime()) // "database closed"

        // task1 was interrupted by shutdownNow; task2 was dropped from
        // the queue. Neither will hit the close marker.
        task1Releasable.countDown() // no effect — task1 already interrupted

        // Give task2 plenty of time to run if it were going to. It must not.
        val task2DidRun = task2Ran.await(500, TimeUnit.MILLISECONDS)
        assertFalse(
            "Task2 should never run — drain() should have removed it from the queue " +
                "before close. But it ran. Outcome was $outcome.",
            task2DidRun,
        )
        assertFalse(
            "Task2 must not observe the close marker since it should never run.",
            task2ObservedClose.get(),
        )
        assertEquals(StoreLifecycle.DrainOutcome.Forced, outcome)
    }

    @Test
    fun `drain returns Drained when all tasks complete within graceful window`() {
        val executed = AtomicInteger(0)
        repeat(10) {
            executor.execute {
                Thread.sleep(5)
                executed.incrementAndGet()
            }
        }
        val outcome = StoreLifecycle(gracefulMillis = 5_000, forceMillis = 1_000).drain(executor)
        assertEquals(StoreLifecycle.DrainOutcome.Drained, outcome)
        assertEquals(10, executed.get())
    }

    @Test
    fun `drain returns Forced when graceful window expires but force completes`() {
        val taskRan = AtomicInteger(0)
        // One slow task that ignores interruption for ~30ms — long enough
        // to outlast the 1ms graceful window, short enough that
        // shutdownNow's 200ms wait drains it.
        executor.execute {
            val end = System.currentTimeMillis() + 30
            while (System.currentTimeMillis() < end) {
                // Busy-wait — InterruptedException can't end us early.
            }
            taskRan.incrementAndGet()
        }

        val outcome = StoreLifecycle(gracefulMillis = 1, forceMillis = 200).drain(executor)
        assertEquals(StoreLifecycle.DrainOutcome.Forced, outcome)
        assertEquals(1, taskRan.get())
    }

    @Test
    fun `drain returns Stuck when even shutdownNow + force cannot stop a task`() {
        val taskStarted = CountDownLatch(1)
        // Uninterruptible loop. shutdownNow can't end this; assertions
        // just check the helper reports Stuck cleanly. Hard wall-clock
        // bound so the test process exits even if the helper has a bug.
        executor.execute {
            taskStarted.countDown()
            val end = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < end) {
                // Ignore interrupts; simulate a misbehaving worker.
            }
        }
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS))

        val outcome = StoreLifecycle(gracefulMillis = 10, forceMillis = 50).drain(executor)
        assertEquals(StoreLifecycle.DrainOutcome.Stuck, outcome)
    }

    @Test
    fun `drain rejects new submissions after returning`() {
        StoreLifecycle(gracefulMillis = 100, forceMillis = 100).drain(executor)
        assertTrue(executor.isShutdown)
        var rejected = false
        try {
            executor.execute { /* no-op */ }
        } catch (_: RejectedExecutionException) {
            rejected = true
        }
        assertTrue("Executor must reject new submissions after drain()", rejected)
    }

    /**
     * If the thread that called drain() is interrupted mid-wait, drain
     * still has to leave the executor quiescent before returning —
     * otherwise an in-flight task can be mid-write when the caller
     * proceeds to db.close(), reintroducing the race.
     *
     * Setup: run drain on a separate thread, interrupt it during the
     * graceful wait, and verify (a) outcome is Interrupted, (b) the
     * caller-thread's interrupt flag was re-asserted, (c) the
     * in-flight task actually finished before drain returned.
     */
    @Test
    fun `drain Interrupted path waits for in-flight task before returning`() {
        val taskStarted = CountDownLatch(1)
        val taskFinished = CountDownLatch(1)
        val taskWasRunningWhenDrainReturned = AtomicBoolean(false)
        val drainObservedInterrupt = AtomicBoolean(false)

        // Long-but-finite task. Force window of 500ms is enough for it
        // to finish; this proves drain awaits termination after
        // shutdownNow even on the Interrupted path.
        executor.execute {
            taskStarted.countDown()
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                // Eat the interrupt so the task runs to completion;
                // mirrors a Room insertAll that doesn't honor interrupts.
            }
            taskFinished.countDown()
        }
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS))

        var outcome: StoreLifecycle.DrainOutcome? = null
        val drainThread = Thread {
            outcome = StoreLifecycle(
                gracefulMillis = 60_000,
                forceMillis = 2_000,
            ).drain(executor)
            taskWasRunningWhenDrainReturned.set(taskFinished.count > 0)
            drainObservedInterrupt.set(Thread.currentThread().isInterrupted)
        }
        drainThread.start()
        // Let drain enter awaitTermination, then interrupt it.
        Thread.sleep(50)
        drainThread.interrupt()
        drainThread.join(5_000)

        assertEquals(StoreLifecycle.DrainOutcome.Interrupted, outcome)
        assertFalse(
            "drain returned before task finished — would let caller race db.close() against the in-flight write",
            taskWasRunningWhenDrainReturned.get(),
        )
        assertTrue(
            "drain must re-assert the interrupt flag on its caller thread",
            drainObservedInterrupt.get(),
        )
    }

    @Test
    fun `drain logs warning when graceful window expires`() {
        val logs = mutableListOf<String>()
        executor.execute {
            val end = System.currentTimeMillis() + 30
            while (System.currentTimeMillis() < end) { /* busy-wait */ }
        }
        StoreLifecycle(gracefulMillis = 1, forceMillis = 200, log = { logs += it }).drain(executor)
        assertTrue(
            "Expected a warning about not draining within the graceful window. Got: $logs",
            logs.any { it.contains("did not drain within") },
        )
    }
}
