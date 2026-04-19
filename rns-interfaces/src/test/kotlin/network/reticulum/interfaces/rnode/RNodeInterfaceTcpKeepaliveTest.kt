package network.reticulum.interfaces.rnode

import network.reticulum.interfaces.framing.KISS
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RNodeInterfaceTcpKeepaliveTest {

    @Test
    fun `idle TCP interface sends detect keepalive when online`() {
        val output = ByteArrayOutputStream()
        val iface = createInterface(output, activityKeepaliveMs = 3_500L)

        iface.setOnline(true)
        setLastWriteMs(iface, System.currentTimeMillis() - 4_000L)

        invokeHandleIdleMaintenance(iface)

        assertArrayEquals(expectedDetectFrames(), output.toByteArray())
    }

    @Test
    fun `idle maintenance does not send keepalive when offline`() {
        val output = ByteArrayOutputStream()
        val iface = createInterface(output, activityKeepaliveMs = 3_500L)

        iface.setOnline(false)
        setLastWriteMs(iface, System.currentTimeMillis() - 4_000L)

        invokeHandleIdleMaintenance(iface)

        assertEquals(0, output.size())
    }

    @Test
    fun `idle maintenance does not send keepalive before threshold`() {
        val output = ByteArrayOutputStream()
        val iface = createInterface(output, activityKeepaliveMs = 3_500L)

        iface.setOnline(true)
        setLastWriteMs(iface, System.currentTimeMillis() - 1_000L)

        invokeHandleIdleMaintenance(iface)

        assertEquals(0, output.size())
    }

    private fun createInterface(
        output: ByteArrayOutputStream,
        activityKeepaliveMs: Long?,
    ): RNodeInterface =
        RNodeInterface(
            name = "TestRNode",
            inputStream = ByteArrayInputStream(byteArrayOf()),
            outputStream = output,
            frequency = 915_000_000L,
            bandwidth = 125_000L,
            txPower = 7,
            spreadingFactor = 7,
            codingRate = 5,
            flowControl = false,
            activityKeepaliveMs = activityKeepaliveMs,
            parentScope = null,
            displayImageData = null,
        )

    private fun invokeHandleIdleMaintenance(iface: RNodeInterface) {
        val method = iface.javaClass.getDeclaredMethod("handleIdleMaintenance")
        method.isAccessible = true
        method.invoke(iface)
    }

    private fun setLastWriteMs(
        iface: RNodeInterface,
        value: Long,
    ) {
        val field = iface.javaClass.getDeclaredField("lastWriteMs")
        field.isAccessible = true
        field.setLong(iface, value)
    }

    private fun expectedDetectFrames(): ByteArray =
        byteArrayOf(
            KISS.FEND,
            KISS.CMD_DETECT,
            KISS.DETECT_REQ,
            KISS.FEND,
            KISS.FEND,
            KISS.CMD_FW_VERSION,
            0x00,
            KISS.FEND,
            KISS.FEND,
            KISS.CMD_PLATFORM,
            0x00,
            KISS.FEND,
            KISS.FEND,
            KISS.CMD_MCU,
            0x00,
            KISS.FEND,
        )
}
