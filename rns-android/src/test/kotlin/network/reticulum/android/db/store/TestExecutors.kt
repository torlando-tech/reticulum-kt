package network.reticulum.android.db.store

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Inline [java.util.concurrent.ExecutorService] so store write tasks run
 * deterministically on the test thread. After [shutdown], [execute] throws
 * [RejectedExecutionException] — mirroring an executor already drained by
 * `StoreLifecycle` on service teardown.
 */
internal class DirectExecutorService : AbstractExecutorService() {
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) {
        if (shutdown) throw RejectedExecutionException("executor shut down")
        command.run()
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}
