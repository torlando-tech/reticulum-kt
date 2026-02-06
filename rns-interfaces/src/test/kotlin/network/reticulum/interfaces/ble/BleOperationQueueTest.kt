package network.reticulum.interfaces.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleOperationQueueTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = BleOperationQueue(scope = scope, defaultTimeoutMs = 5_000L)

    @AfterEach
    fun tearDown() {
        queue.shutdown()
    }

    @Test
    fun `operations execute in FIFO order`() = runBlocking {
        val executionOrder = mutableListOf<Int>()

        // Enqueue 3 operations that each record their execution order.
        // Each delays briefly to prove serialization (op2 cannot start before op1 finishes).
        val results = (1..3).map { i ->
            async {
                queue.enqueue {
                    executionOrder.add(i)
                    delay(50)
                    i
                }
            }
        }

        val values = results.map { it.await() }
        assertEquals(listOf(1, 2, 3), executionOrder, "Operations should execute in FIFO order")
        assertEquals(listOf(1, 2, 3), values, "Return values should match enqueue order")
    }

    @Test
    fun `operations are serialized not interleaved`() = runBlocking {
        // Each operation records start and end. If serialized, no two operations overlap.
        data class Span(val id: Int, val start: Long, val end: Long)

        val spans = mutableListOf<Span>()

        val jobs = (1..3).map { i ->
            async {
                queue.enqueue {
                    val start = System.nanoTime()
                    delay(50)
                    val end = System.nanoTime()
                    synchronized(spans) { spans.add(Span(i, start, end)) }
                    i
                }
            }
        }
        jobs.awaitAll()

        // Verify no overlap: each operation's start should be >= previous operation's end
        val sorted = spans.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i].start >= sorted[i - 1].end,
                "Operation ${sorted[i].id} started before operation ${sorted[i - 1].id} ended",
            )
        }
    }

    @Test
    fun `operation timeout throws BleOperationTimeoutException`() = runBlocking {
        val shortTimeoutQueue = BleOperationQueue(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            defaultTimeoutMs = 100L,
        )

        try {
            assertThrows<BleOperationTimeoutException> {
                runBlocking {
                    shortTimeoutQueue.enqueue<String> {
                        delay(5_000L) // Far exceeds 100ms timeout
                        "should not reach"
                    }
                }
            }
        } finally {
            shortTimeoutQueue.shutdown()
        }
    }

    @Test
    fun `custom timeout overrides default`() = runBlocking {
        // Queue has long default timeout, but operation has short custom timeout
        assertThrows<BleOperationTimeoutException> {
            runBlocking {
                queue.enqueue<String>(timeoutMs = 100L) {
                    delay(5_000L)
                    "should not reach"
                }
            }
        }
    }

    @Test
    fun `exception in operation propagates to caller`() = runBlocking {
        val exception = assertThrows<IllegalStateException> {
            runBlocking {
                queue.enqueue {
                    throw IllegalStateException("test error")
                }
            }
        }
        assertEquals("test error", exception.message)
    }

    @Test
    fun `queue continues processing after failed operation`() = runBlocking {
        // First operation throws
        val failResult = runCatching {
            queue.enqueue<Unit> {
                throw RuntimeException("intentional failure")
            }
        }
        assertTrue(failResult.isFailure, "First operation should fail")

        // Second operation should succeed despite previous failure
        val result = queue.enqueue {
            "success after failure"
        }
        assertEquals("success after failure", result)
    }

    @Test
    fun `concurrent enqueues all complete with correct count`() = runBlocking {
        val count = 20
        val results = (1..count).map { i ->
            async {
                queue.enqueue {
                    i * 10
                }
            }
        }

        val values = results.map { it.await() }
        assertEquals(count, values.size, "All operations should complete")
        // Each value should be unique and in the set {10, 20, ..., 200}
        val expected = (1..count).map { it * 10 }.toSet()
        assertEquals(expected, values.toSet(), "All results should be present")
    }

    @Test
    fun `operation return values are correctly typed`() = runBlocking {
        val stringResult: String = queue.enqueue { "hello" }
        assertEquals("hello", stringResult)

        val intResult: Int = queue.enqueue { 42 }
        assertEquals(42, intResult)

        val listResult: List<Int> = queue.enqueue { listOf(1, 2, 3) }
        assertEquals(listOf(1, 2, 3), listResult)
    }
}
