package network.reticulum.integration

import network.reticulum.channel.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Channel and Buffer.
 */
@DisplayName("Channel Integration Tests")
class ChannelIntegrationTest {

    @Test
    @DisplayName("Channel constants match Python reference")
    @Timeout(5)
    fun `channel constants match python reference`() {
        assertEquals(2, ChannelConstants.WINDOW, "WINDOW should be 2")
        assertEquals(2, ChannelConstants.WINDOW_MIN, "WINDOW_MIN should be 2")
        assertEquals(48, ChannelConstants.WINDOW_MAX_FAST, "WINDOW_MAX_FAST should be 48")
        assertEquals(0xFFFF, ChannelConstants.SEQ_MAX, "SEQ_MAX should be 0xFFFF")
        assertEquals(0xF000, ChannelConstants.SYSTEM_MESSAGE_MIN, "SYSTEM_MESSAGE_MIN should be 0xF000")
    }

    @Test
    @DisplayName("Envelope can pack and unpack messages")
    @Timeout(5)
    fun `envelope can pack and unpack messages`() {
        val outlet = MockChannelOutlet()

        // Create a test message
        val testMessage = TestMessage().apply {
            content = "Hello, Channel!"
        }

        // Create envelope and pack
        val envelope = Envelope(outlet, message = testMessage, sequence = 42)
        val packed = envelope.pack()

        // Verify packed format
        assertTrue(packed.size >= 6, "Packed data should have at least 6 bytes header")

        // Unpack into new envelope
        val unpacked = Envelope(outlet, raw = packed)
        val factories = mapOf(TestMessage.MSG_TYPE to MessageFactory { TestMessage() })
        val received = unpacked.unpack(factories)

        // Verify
        assertTrue(received is TestMessage)
        assertEquals("Hello, Channel!", (received as TestMessage).content)
        assertEquals(42, unpacked.sequence)
    }

    @Test
    @DisplayName("StreamDataMessage can pack and unpack")
    @Timeout(5)
    fun `stream data message can pack and unpack`() {
        val original = StreamDataMessage().apply {
            streamId = 123
            data = "Test data".toByteArray()
            eof = false
            compressed = false
        }

        val packed = original.pack()
        assertTrue(packed.size >= 3, "Packed should have at least 3 bytes header")

        val unpacked = StreamDataMessage()
        unpacked.unpack(packed)

        assertEquals(123, unpacked.streamId)
        assertEquals("Test data", String(unpacked.data))
        assertEquals(false, unpacked.eof)
        assertEquals(false, unpacked.compressed)
    }

    @Test
    @DisplayName("StreamDataMessage encodes EOF flag")
    @Timeout(5)
    fun `stream data message encodes eof flag`() {
        val original = StreamDataMessage().apply {
            streamId = 1
            data = ByteArray(0)
            eof = true
            compressed = false
        }

        val packed = original.pack()
        val unpacked = StreamDataMessage()
        unpacked.unpack(packed)

        assertEquals(1, unpacked.streamId)
        assertTrue(unpacked.eof)
        assertEquals(false, unpacked.compressed)
    }

    @Test
    @DisplayName("MessageState constants are correct")
    @Timeout(5)
    fun `message state constants are correct`() {
        assertEquals(0, MessageState.NEW)
        assertEquals(1, MessageState.SENT)
        assertEquals(2, MessageState.DELIVERED)
        assertEquals(3, MessageState.FAILED)
    }

    /**
     * Test message implementation.
     */
    class TestMessage : MessageBase() {
        companion object {
            const val MSG_TYPE = 0x0001
        }

        override val msgType = MSG_TYPE
        var content: String = ""

        override fun pack(): ByteArray = content.toByteArray(Charsets.UTF_8)

        override fun unpack(raw: ByteArray) {
            content = String(raw, Charsets.UTF_8)
        }
    }

    /**
     * Mock channel outlet for testing.
     */
    class MockChannelOutlet : ChannelOutlet {
        override fun send(raw: ByteArray): Any? = raw.hashCode()
        override fun resend(packet: Any): Any? = packet
        override val mdu: Int = 400
        override val rtt: Long? = 100
        override val isUsable: Boolean = true
        override val timedOut: Boolean = false
        override fun getPacketState(packet: Any): Int = MessageState.NEW
        override fun setPacketTimeoutCallback(packet: Any, callback: ((Any) -> Unit)?, timeout: Long?) {}
        override fun setPacketDeliveredCallback(packet: Any, callback: ((Any) -> Unit)?) {}
        override fun getPacketId(packet: Any): Any = packet
    }
}
