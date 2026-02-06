package network.reticulum.interfaces.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Generic operation queue that ensures only one BLE operation runs at a time.
 *
 * Android's BLE stack does NOT queue operations internally. If you call
 * multiple GATT operations in succession, the second silently fails.
 * This queue serializes operations with proper completion tracking and timeouts.
 *
 * Pure JVM -- uses only kotlinx.coroutines, no Android imports.
 * Used by BleGattClient (Phase 20) for GATT write/read serialization.
 */
class BleOperationQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val defaultTimeoutMs: Long = BLEConstants.OPERATION_TIMEOUT_MS,
) {
    private data class Operation<T>(
        val block: suspend () -> T,
        val deferred: CompletableDeferred<T>,
        val timeoutMs: Long,
    )

    private val channel = Channel<Operation<*>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (op in channel) {
                @Suppress("UNCHECKED_CAST")
                executeOperation(op as Operation<Any?>)
            }
        }
    }

    private suspend fun <T> executeOperation(op: Operation<T>) {
        try {
            val result = withTimeout(op.timeoutMs) { op.block() }
            op.deferred.complete(result)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            op.deferred.completeExceptionally(
                BleOperationTimeoutException("Operation timed out after ${op.timeoutMs}ms", e),
            )
        } catch (e: CancellationException) {
            op.deferred.cancel(e)
        } catch (e: Exception) {
            op.deferred.completeExceptionally(e)
        }
    }

    /**
     * Enqueue an operation and suspend until it completes.
     * Operations execute in FIFO order, one at a time.
     *
     * @param timeoutMs timeout for this operation (default from constructor)
     * @param block the suspend function to execute
     * @return result of the operation
     * @throws BleOperationTimeoutException if the operation times out
     */
    suspend fun <T> enqueue(
        timeoutMs: Long = defaultTimeoutMs,
        block: suspend () -> T,
    ): T {
        val deferred = CompletableDeferred<T>()
        val operation = Operation(block, deferred, timeoutMs)
        channel.send(operation)
        return deferred.await()
    }

    /**
     * Shutdown the queue. Cancels all pending operations.
     */
    fun shutdown() {
        channel.close()
        scope.cancel()
    }
}

/**
 * Thrown when a BLE operation exceeds its timeout.
 */
class BleOperationTimeoutException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
