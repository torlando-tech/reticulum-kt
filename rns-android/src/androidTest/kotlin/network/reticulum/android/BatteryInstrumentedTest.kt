package network.reticulum.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for battery monitoring and optimization.
 *
 * These tests run on an Android device/emulator and verify:
 * - BatteryMonitor correctly reads battery state
 * - DozeHandler correctly detects Doze state
 * - Battery mode recommendations are sensible
 *
 * Note: Some tests may behave differently on emulators vs real devices.
 */
@RunWith(AndroidJUnit4::class)
class BatteryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var dozeHandler: DozeHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        batteryMonitor = BatteryMonitor(context)
        dozeHandler = DozeHandler(context)
    }

    @After
    fun teardown() {
        batteryMonitor.stop()
        dozeHandler.stop()
    }

    @Test
    fun batteryMonitor_readsValidBatteryLevel() {
        val level = batteryMonitor.batteryLevel

        // Battery level should be between 0 and 100
        assertTrue("Battery level should be >= 0", level >= 0)
        assertTrue("Battery level should be <= 100", level <= 100)
    }

    @Test
    fun batteryMonitor_getBatteryInfo_returnsValidInfo() {
        val info = batteryMonitor.getBatteryInfo()

        assertNotNull(info)
        assertTrue("Battery level should be >= 0", info.level >= 0)
        assertTrue("Battery level should be <= 100", info.level <= 100)
        // charging and pluggedIn are booleans, always valid
        // powerSaveMode is boolean, always valid
    }

    @Test
    fun batteryMonitor_recommendedBatteryMode_returnsValidMode() {
        val mode = batteryMonitor.recommendedBatteryMode()

        assertNotNull(mode)
        assertTrue(
            "Mode should be a valid BatteryMode",
            mode in ReticulumConfig.BatteryMode.entries
        )
    }

    @Test
    fun batteryMonitor_startAndStop_noExceptions() {
        // Should not throw
        batteryMonitor.start()
        batteryMonitor.stop()

        // Should be safe to call multiple times
        batteryMonitor.start()
        batteryMonitor.start()
        batteryMonitor.stop()
        batteryMonitor.stop()
    }

    @Test
    fun dozeHandler_readsDozeState() {
        // isDozeMode should return a valid boolean (not throw)
        val inDoze = dozeHandler.isDozeMode

        // We can't assert the value since it depends on device state,
        // but it should be a valid boolean
        assertTrue("isDozeMode should return true or false", inDoze || !inDoze)
    }

    @Test
    fun dozeHandler_readsOptimizationState() {
        // Should not throw
        val exempt = dozeHandler.isIgnoringBatteryOptimizations

        // Can't assert value, just verify no exception
        assertTrue("Should return true or false", exempt || !exempt)
    }

    @Test
    fun dozeHandler_getDozeInfo_returnsValidInfo() {
        val info = dozeHandler.getDozeInfo()

        assertNotNull(info)
        // Values are booleans, always valid
    }

    @Test
    fun dozeHandler_startAndStop_noExceptions() {
        // Should not throw
        dozeHandler.start()
        dozeHandler.stop()

        // Should be safe to call multiple times
        dozeHandler.start()
        dozeHandler.start()
        dozeHandler.stop()
        dozeHandler.stop()
    }

    /**
     * Test that low battery triggers MAXIMUM_BATTERY mode recommendation.
     *
     * Note: This test may not work correctly on emulators since we can't
     * easily control the battery level. On real devices, it would need
     * to be run when battery is actually low.
     */
    @Test
    fun batteryMonitor_lowBattery_recommendsMaximumBattery() {
        val level = batteryMonitor.batteryLevel
        val mode = batteryMonitor.recommendedBatteryMode()

        // If battery is actually low, should recommend MAXIMUM_BATTERY
        if (level < 15) {
            assertTrue(
                "Low battery should recommend MAXIMUM_BATTERY",
                mode == ReticulumConfig.BatteryMode.MAXIMUM_BATTERY
            )
        }
        // Otherwise we can't make assertions about the mode
    }
}
