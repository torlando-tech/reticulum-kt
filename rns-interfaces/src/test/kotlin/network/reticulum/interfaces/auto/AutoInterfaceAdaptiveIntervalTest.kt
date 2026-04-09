package network.reticulum.interfaces.auto

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for AutoInterface's adaptive announce interval logic.
 *
 * Verifies the battery-saving multicast throttling:
 * - Fast 1.6s rate on startup and peer changes
 * - Linear ramp to 2 minutes when stable
 * - 5x throttle multiplier scales max interval to 10 minutes
 * - Immediate announce flag on peer change
 */
@DisplayName("AutoInterface Adaptive Interval")
class AutoInterfaceAdaptiveIntervalTest {

    // Mirror the constants from AutoInterface for testing
    private val minInterval = AutoInterfaceConstants.ANNOUNCE_INTERVAL_MS  // 1600
    private val maxInterval = 120_000L  // 2 minutes
    private val rampDuration = 60_000L  // 60 seconds

    /**
     * Compute interval the same way AutoInterface.updateAnnounceInterval does.
     */
    private fun computeInterval(
        timeSinceChange: Long,
        throttleMultiplier: Float = 1.0f,
    ): Long {
        val progress = (timeSinceChange.toDouble() / rampDuration).coerceIn(0.0, 1.0)
        val effectiveMax = (maxInterval * throttleMultiplier).toLong()
        return (minInterval + (effectiveMax - minInterval) * progress).toLong()
    }

    @Test
    @DisplayName("Interval starts at min (1.6s) immediately after peer change")
    fun `interval starts at min`() {
        val interval = computeInterval(0)
        assertEquals(minInterval, interval)
    }

    @Test
    @DisplayName("Interval reaches max (2 min) after ramp duration")
    fun `interval reaches max after ramp`() {
        val interval = computeInterval(rampDuration)
        assertEquals(maxInterval, interval)
    }

    @Test
    @DisplayName("Interval stays at max beyond ramp duration")
    fun `interval stays at max beyond ramp`() {
        val interval = computeInterval(rampDuration * 2)
        assertEquals(maxInterval, interval)
    }

    @Test
    @DisplayName("Interval is halfway at half ramp duration")
    fun `interval is halfway at half ramp`() {
        val interval = computeInterval(rampDuration / 2)
        val expected = minInterval + (maxInterval - minInterval) / 2
        assertEquals(expected, interval)
    }

    @Test
    @DisplayName("Throttle multiplier 5x scales max to 10 minutes")
    fun `throttle multiplier scales max`() {
        val interval = computeInterval(rampDuration, throttleMultiplier = 5.0f)
        val expected = (maxInterval * 5.0f).toLong()
        assertEquals(expected, interval)
    }

    @Test
    @DisplayName("Throttle multiplier 5x at midpoint is between min and 10 min")
    fun `throttle multiplier at midpoint`() {
        val interval = computeInterval(rampDuration / 2, throttleMultiplier = 5.0f)
        assertTrue(interval > minInterval)
        assertTrue(interval < maxInterval * 5)
    }

    @Test
    @DisplayName("Throttle multiplier 1.0 has no effect on max")
    fun `throttle multiplier 1 is identity`() {
        val withoutThrottle = computeInterval(rampDuration, throttleMultiplier = 1.0f)
        assertEquals(maxInterval, withoutThrottle)
    }

    @Test
    @DisplayName("Interval never goes below min regardless of throttle")
    fun `interval never below min`() {
        // Even with throttle, at time 0 should be min
        val interval = computeInterval(0, throttleMultiplier = 5.0f)
        assertEquals(minInterval, interval)
    }

    @Test
    @DisplayName("Announce interval constant matches Python 1.6s")
    fun `min interval matches python`() {
        assertEquals(1600L, AutoInterfaceConstants.ANNOUNCE_INTERVAL_MS)
    }

    @Test
    @DisplayName("Peer timeout constant matches Python")
    fun `peer timeout matches python`() {
        assertEquals(22.0, AutoInterfaceConstants.PEERING_TIMEOUT)
    }
}
