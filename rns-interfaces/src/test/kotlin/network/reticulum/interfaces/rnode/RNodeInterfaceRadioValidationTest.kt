package network.reticulum.interfaces.rnode

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import network.reticulum.interfaces.framing.KISS
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RNode firmware may rewrite the requested TX power and report its own value: it
 * clamps to the modem/PA ceiling (SX127x 17 dBm, SX1262 22 dBm), quantises through
 * the LoRa-PA gain table, and echoes its stored value in TNC mode.
 * A clamped request used to fail [RNodeInterface]'s radio validation, which aborted
 * `start()` before `setOnline(true)` — the interface never came online and the only
 * trace was one log line (issue #1255). The reported value is adopted as the
 * interface's effective TX power; every other parameter has no legitimate re-write
 * path and stays fatal.
 */
class RNodeInterfaceRadioValidationTest {

    @Test
    fun `TX power rewritten by the device is adopted instead of failing validation`() {
        val iface = createInterface(txPower = 1)
        setReported(iface, txPower = 11)

        val output = captureStdout { invokeValidate(iface) }

        assertTrue(output.result, "a rewritten TX power must not fail radio validation")
        assertTrue(
            output.text.contains("configured=1 dBm, the device can only transmit 11 dBm"),
            "the mismatch must be logged with both values, got: ${output.text}",
        )
        assertEquals(
            11,
            readField(iface, "txPower"),
            "the interface must adopt the value the radio can actually transmit",
        )
    }

    @Test
    fun `the adopted TX power is what reconfiguration sends`() {
        val output = ByteArrayOutputStream()
        val iface = createInterface(txPower = 1, outputStream = output)
        setReported(iface, txPower = 11)

        assertTrue(invokeValidate(iface))
        invokePrivate(iface, "sendTxPower")

        assertArrayEquals(
            byteArrayOf(KISS.FEND, KISS.CMD_TXPOWER, 11, KISS.FEND),
            output.toByteArray(),
            "reconfiguring must re-send the achievable power, not the rejected request",
        )
    }

    @Test
    fun `unset TX power sentinel is not adopted and stays fatal`() {
        val iface = createInterface(txPower = 1)
        setReported(iface, txPower = 0xFF)

        assertFalse(invokeValidate(iface), "0xFF is the firmware's not-configured sentinel")
        assertEquals(1, readField(iface, "txPower"))
    }

    @Test
    fun `matching radio state stays valid`() {
        val iface = createInterface(txPower = 17)
        setReported(iface, txPower = 17)

        assertTrue(invokeValidate(iface))
        assertEquals(17, readField(iface, "txPower"))
    }

    @Test
    fun `spreading factor mismatch is still fatal`() {
        val iface = createInterface(txPower = 17)
        setReported(iface, txPower = 17, spreadingFactor = 9)

        assertFalse(invokeValidate(iface), "a real radio mismatch must still fail validation")
    }

    // -- helpers --

    private data class Captured(val text: String, val result: Boolean)

    private fun createInterface(
        txPower: Int,
        outputStream: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): RNodeInterface =
        RNodeInterface(
            name = "TestRNode",
            inputStream = ByteArrayInputStream(byteArrayOf()),
            outputStream = outputStream,
            frequency = 915_000_000L,
            bandwidth = 125_000L,
            txPower = txPower,
            spreadingFactor = 7,
            codingRate = 5,
            flowControl = false,
            parentScope = null,
            displayImageData = null,
        )

    /** Fill every reported field with the configured value, then apply the overrides. */
    private fun setReported(
        iface: RNodeInterface,
        txPower: Int,
        spreadingFactor: Int? = null,
    ) {
        setField(iface, "rFrequency", 915_000_000L)
        setField(iface, "rBandwidth", 125_000L)
        setField(iface, "rTxpower", txPower)
        setField(iface, "rSf", spreadingFactor ?: 7)
        setField(iface, "rCr", 5)
        setField(iface, "rState", 1)
    }

    private fun setField(iface: RNodeInterface, name: String, value: Any) {
        val field = iface.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(iface, value)
    }

    private fun readField(iface: RNodeInterface, name: String): Any? {
        val field = iface.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(iface)
    }

    private fun invokePrivate(iface: RNodeInterface, name: String) {
        val method = iface.javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(iface)
    }

    private fun invokeValidate(iface: RNodeInterface): Boolean {
        val method = iface.javaClass.getDeclaredMethod("validateRadioState")
        method.isAccessible = true
        return method.invoke(iface) as Boolean
    }

    private fun captureStdout(block: () -> Boolean): Captured {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        return try {
            val result = block()
            Captured(text = buffer.toString(), result = result)
        } finally {
            System.setOut(original)
        }
    }
}
