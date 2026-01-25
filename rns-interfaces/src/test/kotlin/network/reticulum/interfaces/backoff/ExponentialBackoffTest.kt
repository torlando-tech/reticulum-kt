package network.reticulum.interfaces.backoff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExponentialBackoffTest {

    @Test
    fun `initial delay is 1 second`() {
        val backoff = ExponentialBackoff()

        assertEquals(1000L, backoff.nextDelay())
    }

    @Test
    fun `delay doubles each attempt`() {
        val backoff = ExponentialBackoff()

        assertEquals(1000L, backoff.nextDelay())
        assertEquals(2000L, backoff.nextDelay())
        assertEquals(4000L, backoff.nextDelay())
        assertEquals(8000L, backoff.nextDelay())
    }

    @Test
    fun `delay caps at max 60 seconds`() {
        val backoff = ExponentialBackoff()

        // 1s, 2s, 4s, 8s, 16s, 32s -> next would be 64s but caps at 60s
        repeat(6) { backoff.nextDelay() }

        assertEquals(60000L, backoff.nextDelay())  // 7th: capped at 60s
        assertEquals(60000L, backoff.nextDelay())  // 8th: stays at 60s
        assertEquals(60000L, backoff.nextDelay())  // 9th: stays at 60s
    }

    @Test
    fun `returns null after 10 attempts`() {
        val backoff = ExponentialBackoff()

        // 10 attempts should all return values
        repeat(10) { attempt ->
            val delay = backoff.nextDelay()
            assertTrue(delay != null, "Attempt ${attempt + 1} should return a delay")
        }

        // 11th attempt should return null
        assertNull(backoff.nextDelay())
    }

    @Test
    fun `reset restores initial state`() {
        val backoff = ExponentialBackoff()

        // Use some attempts
        backoff.nextDelay()  // 1s
        backoff.nextDelay()  // 2s
        backoff.nextDelay()  // 4s
        assertEquals(3, backoff.attemptCount)

        // Reset
        backoff.reset()

        // Should be back to initial state
        assertEquals(0, backoff.attemptCount)
        assertEquals(1000L, backoff.nextDelay())
    }

    @Test
    fun `attemptCount tracks correctly`() {
        val backoff = ExponentialBackoff()

        assertEquals(0, backoff.attemptCount)

        backoff.nextDelay()
        assertEquals(1, backoff.attemptCount)

        backoff.nextDelay()
        assertEquals(2, backoff.attemptCount)

        backoff.nextDelay()
        assertEquals(3, backoff.attemptCount)
    }

    @Test
    fun `isExhausted returns true after max attempts`() {
        val backoff = ExponentialBackoff()

        assertFalse(backoff.isExhausted)

        // Use up all 10 attempts
        repeat(9) {
            backoff.nextDelay()
            assertFalse(backoff.isExhausted, "Should not be exhausted after ${backoff.attemptCount} attempts")
        }

        // 10th attempt
        backoff.nextDelay()
        assertTrue(backoff.isExhausted)
    }

    @Test
    fun `custom parameters work correctly`() {
        val backoff = ExponentialBackoff(
            initialDelayMs = 500L,
            maxDelayMs = 2000L,
            multiplier = 3.0,
            maxAttempts = 4
        )

        assertEquals(500L, backoff.nextDelay())   // 1st: 500ms
        assertEquals(1500L, backoff.nextDelay())  // 2nd: 500 * 3 = 1500ms
        assertEquals(2000L, backoff.nextDelay())  // 3rd: 1500 * 3 = 4500ms, capped at 2000ms
        assertEquals(2000L, backoff.nextDelay())  // 4th: stays at max
        assertNull(backoff.nextDelay())           // 5th: exhausted
        assertTrue(backoff.isExhausted)
    }
}
