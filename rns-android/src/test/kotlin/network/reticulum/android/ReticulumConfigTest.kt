package network.reticulum.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ReticulumConfig.
 *
 * Uses Robolectric to provide Android framework support for Parcelable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReticulumConfigTest {

    @Test
    fun `DEFAULT preset has correct defaults`() {
        val config = ReticulumConfig.DEFAULT

        assertFalse(config.enableTransport)
        assertEquals(ReticulumConfig.BatteryMode.BALANCED, config.batteryOptimization)
        assertEquals(60_000L, config.jobIntervalMs)
    }

    @Test
    fun `WITH_TRANSPORT preset has correct defaults`() {
        val config = ReticulumConfig.WITH_TRANSPORT

        assertTrue(config.enableTransport)
        assertEquals(ReticulumConfig.BatteryMode.BALANCED, config.batteryOptimization)
    }

    @Test
    fun `custom config can override defaults`() {
        val config = ReticulumConfig(
            enableTransport = true,
            jobIntervalMs = 30_000,
            batteryOptimization = ReticulumConfig.BatteryMode.PERFORMANCE
        )

        assertTrue(config.enableTransport)
        assertEquals(30_000L, config.jobIntervalMs)
        assertEquals(ReticulumConfig.BatteryMode.PERFORMANCE, config.batteryOptimization)
    }

    @Test
    fun `getEffectiveTablesCullInterval for MAXIMUM_BATTERY returns 30 seconds`() {
        val config = ReticulumConfig(
            batteryOptimization = ReticulumConfig.BatteryMode.MAXIMUM_BATTERY
        )

        assertEquals(30_000L, config.getEffectiveTablesCullInterval())
    }

    @Test
    fun `getEffectiveTablesCullInterval for BALANCED returns 10 seconds`() {
        val config = ReticulumConfig(
            batteryOptimization = ReticulumConfig.BatteryMode.BALANCED
        )

        assertEquals(10_000L, config.getEffectiveTablesCullInterval())
    }

    @Test
    fun `getEffectiveTablesCullInterval for PERFORMANCE returns 5 seconds`() {
        val config = ReticulumConfig(
            batteryOptimization = ReticulumConfig.BatteryMode.PERFORMANCE
        )

        assertEquals(5_000L, config.getEffectiveTablesCullInterval())
    }

    @Test
    fun `getEffectiveHashlistSize for MAXIMUM_BATTERY reduces size`() {
        val config = ReticulumConfig(
            maxHashlistSize = 50_000,
            batteryOptimization = ReticulumConfig.BatteryMode.MAXIMUM_BATTERY
        )

        val size = config.getEffectiveHashlistSize()
        assertTrue("Should be at most 25000", size <= 25_000)
    }

    @Test
    fun `getEffectiveHashlistSize for PERFORMANCE doubles size`() {
        val config = ReticulumConfig(
            maxHashlistSize = 50_000,
            batteryOptimization = ReticulumConfig.BatteryMode.PERFORMANCE
        )

        assertEquals(100_000, config.getEffectiveHashlistSize())
    }

    @Test
    fun `battery modes have correct ordering`() {
        val modes = ReticulumConfig.BatteryMode.entries

        assertEquals(3, modes.size)
        assertEquals(ReticulumConfig.BatteryMode.MAXIMUM_BATTERY, modes[0])
        assertEquals(ReticulumConfig.BatteryMode.BALANCED, modes[1])
        assertEquals(ReticulumConfig.BatteryMode.PERFORMANCE, modes[2])
    }

    @Test
    fun `BATTERY_SAVER preset has correct configuration`() {
        val config = ReticulumConfig.BATTERY_SAVER

        assertFalse(config.enableTransport)
        assertEquals(ReticulumConfig.BatteryMode.MAXIMUM_BATTERY, config.batteryOptimization)
        assertFalse(config.tcpKeepAlive)
    }

    @Test
    fun `PERFORMANCE preset has correct configuration`() {
        val config = ReticulumConfig.PERFORMANCE

        assertTrue(config.enableTransport)
        assertEquals(ReticulumConfig.BatteryMode.PERFORMANCE, config.batteryOptimization)
        assertTrue(config.tcpKeepAlive)
    }
}
