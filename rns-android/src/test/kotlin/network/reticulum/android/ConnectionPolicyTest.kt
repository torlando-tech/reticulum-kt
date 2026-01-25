package network.reticulum.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ConnectionPolicy factory method and hysteresis logic.
 *
 * Uses Robolectric to satisfy Android module dependencies, though the logic
 * being tested is pure Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionPolicyTest {

    private val normalNetwork = NetworkState(NetworkType.WiFi, "wlan0")
    private val noNetwork = NetworkState(NetworkType.None)

    @Test
    fun `charging overrides low battery throttling`() {
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 10, // Very low battery
            isCharging = true,  // But charging
            wasThrottledForBattery = false
        )

        assertEquals(ConnectionPolicy.NORMAL_MULTIPLIER, policy.throttleMultiplier)
        assertFalse(policy.shouldThrottle)
        assertTrue(policy.networkAvailable)
        assertFalse(wasThrottled) // Should reset hysteresis when charging
    }

    @Test
    fun `doze mode applies 5x throttle`() {
        val (policy, _) = ConnectionPolicy.create(
            doze = DozeState.Dozing,
            network = normalNetwork,
            batteryLevel = 80, // Good battery
            isCharging = false,
            wasThrottledForBattery = false
        )

        assertEquals(ConnectionPolicy.DOZE_MULTIPLIER, policy.throttleMultiplier)
        assertTrue(policy.shouldThrottle)
        assertEquals("Device in Doze mode", policy.reason)
    }

    @Test
    fun `low battery triggers 5x throttle`() {
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 14, // Below 15% threshold
            isCharging = false,
            wasThrottledForBattery = false // Not yet throttled
        )

        assertEquals(ConnectionPolicy.DOZE_MULTIPLIER, policy.throttleMultiplier)
        assertTrue(policy.shouldThrottle)
        assertTrue(wasThrottled) // Should now be throttled
        assertTrue(policy.reason.contains("Low battery"))
    }

    @Test
    fun `hysteresis prevents flip-flop between 15 and 18 percent`() {
        // Battery at 16% but was already throttled - should stay throttled
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 16, // Between 15% and 18%
            isCharging = false,
            wasThrottledForBattery = true // Was throttled
        )

        assertEquals(ConnectionPolicy.DOZE_MULTIPLIER, policy.throttleMultiplier)
        assertTrue(policy.shouldThrottle)
        assertTrue(wasThrottled) // Should stay throttled
    }

    @Test
    fun `battery resume at 18 percent clears throttle`() {
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 18, // At resume threshold
            isCharging = false,
            wasThrottledForBattery = true // Was throttled
        )

        assertEquals(ConnectionPolicy.NORMAL_MULTIPLIER, policy.throttleMultiplier)
        assertFalse(policy.shouldThrottle)
        assertFalse(wasThrottled) // Should clear throttle state
    }

    @Test
    fun `normal operation with good battery and no doze`() {
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 50,
            isCharging = false,
            wasThrottledForBattery = false
        )

        assertEquals(ConnectionPolicy.NORMAL_MULTIPLIER, policy.throttleMultiplier)
        assertFalse(policy.shouldThrottle)
        assertTrue(policy.networkAvailable)
        assertFalse(wasThrottled)
        assertEquals("Normal operation", policy.reason)
    }

    @Test
    fun `network unavailable reflected in policy`() {
        val (policy, _) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = noNetwork,
            batteryLevel = 80,
            isCharging = false,
            wasThrottledForBattery = false
        )

        assertFalse(policy.networkAvailable)
        assertEquals(ConnectionPolicy.NORMAL_MULTIPLIER, policy.throttleMultiplier)
    }

    @Test
    fun `doze while charging still throttles`() {
        // Even when charging, Doze should apply throttling
        val (policy, _) = ConnectionPolicy.create(
            doze = DozeState.Dozing,
            network = normalNetwork,
            batteryLevel = 10,
            isCharging = true,
            wasThrottledForBattery = false
        )

        assertEquals(ConnectionPolicy.DOZE_MULTIPLIER, policy.throttleMultiplier)
        assertTrue(policy.shouldThrottle)
        assertEquals("Device in Doze mode", policy.reason)
    }

    @Test
    fun `battery at exactly 15 percent triggers throttle`() {
        // Edge case: exactly at threshold
        val (policy, wasThrottled) = ConnectionPolicy.create(
            doze = DozeState.Active,
            network = normalNetwork,
            batteryLevel = 15, // At threshold (not below)
            isCharging = false,
            wasThrottledForBattery = false
        )

        // 15% is not below 15%, so should not trigger new throttle
        assertEquals(ConnectionPolicy.NORMAL_MULTIPLIER, policy.throttleMultiplier)
        assertFalse(wasThrottled)
    }

    @Test
    fun `constants have expected values from CONTEXT`() {
        assertEquals(1.0f, ConnectionPolicy.NORMAL_MULTIPLIER)
        assertEquals(5.0f, ConnectionPolicy.DOZE_MULTIPLIER)
        assertEquals(15, ConnectionPolicy.BATTERY_THROTTLE_THRESHOLD)
        assertEquals(18, ConnectionPolicy.BATTERY_RESUME_THRESHOLD)
    }
}
