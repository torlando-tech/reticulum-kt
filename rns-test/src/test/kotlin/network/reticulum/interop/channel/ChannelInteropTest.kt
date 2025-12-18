package network.reticulum.interop.channel

import io.kotest.matchers.shouldBe
import network.reticulum.channel.StreamDataMessage
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Channel/Buffer interoperability tests with Python RNS.
 *
 * Tests the serialization formats for channel envelopes and stream
 * data messages to ensure Kotlin and Python implementations are byte-compatible.
 *
 * Envelope format: [msgtype:2][sequence:2][length:2][data:N]
 *
 * StreamDataMessage format: [header:2][data:N]
 *   Header bits:
 *     Bit 15: EOF flag (0x8000)
 *     Bit 14: compressed flag (0x4000)
 *     Bits 13-0: stream_id (0-16383)
 */
@DisplayName("Channel Interop")
class ChannelInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("Envelope Pack")
    inner class EnvelopePackTests {

        @Test
        @DisplayName("Envelope pack matches Python")
        fun `envelope pack matches python`() {
            val msgtype = 0x0001
            val sequence = 42
            val data = "Hello, Channel!".toByteArray()

            val pythonResult = python(
                "envelope_pack",
                "msgtype" to msgtype,
                "sequence" to sequence,
                "data" to data
            )

            val pythonEnvelope = pythonResult.getBytes("envelope")
            val length = pythonResult.getInt("length")

            // Verify header size (6 bytes) + data
            pythonEnvelope.size shouldBe (6 + data.size)
            length shouldBe data.size

            // Build Kotlin envelope
            val header = byteArrayOf(
                ((msgtype shr 8) and 0xFF).toByte(),
                (msgtype and 0xFF).toByte(),
                ((sequence shr 8) and 0xFF).toByte(),
                (sequence and 0xFF).toByte(),
                ((data.size shr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte()
            )
            val kotlinEnvelope = header + data

            assertBytesEqual(pythonEnvelope, kotlinEnvelope, "Envelope should match Python")
        }

        @Test
        @DisplayName("Envelope with max sequence (0xFFFF) matches Python")
        fun `envelope with max sequence matches python`() {
            val msgtype = 0x1234
            val sequence = 0xFFFF // Max sequence
            val data = ByteArray(10) { it.toByte() }

            val pythonResult = python(
                "envelope_pack",
                "msgtype" to msgtype,
                "sequence" to sequence,
                "data" to data
            )

            val pythonEnvelope = pythonResult.getBytes("envelope")

            // Build Kotlin envelope
            val header = byteArrayOf(
                ((msgtype shr 8) and 0xFF).toByte(),
                (msgtype and 0xFF).toByte(),
                ((sequence shr 8) and 0xFF).toByte(),
                (sequence and 0xFF).toByte(),
                ((data.size shr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte()
            )
            val kotlinEnvelope = header + data

            assertBytesEqual(pythonEnvelope, kotlinEnvelope, "Envelope with max sequence should match")
        }
    }

    @Nested
    @DisplayName("Envelope Unpack")
    inner class EnvelopeUnpackTests {

        @Test
        @DisplayName("Envelope unpack matches Python")
        fun `envelope unpack matches python`() {
            val msgtype = 0x5678
            val sequence = 1234
            val data = "Unpack test".toByteArray()

            // Build envelope
            val header = byteArrayOf(
                ((msgtype shr 8) and 0xFF).toByte(),
                (msgtype and 0xFF).toByte(),
                ((sequence shr 8) and 0xFF).toByte(),
                (sequence and 0xFF).toByte(),
                ((data.size shr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte()
            )
            val envelope = header + data

            val pythonResult = python(
                "envelope_unpack",
                "envelope" to envelope
            )

            val unpackedMsgtype = pythonResult.getInt("msgtype")
            val unpackedSequence = pythonResult.getInt("sequence")
            val unpackedLength = pythonResult.getInt("length")
            val unpackedData = pythonResult.getBytes("data")

            unpackedMsgtype shouldBe msgtype
            unpackedSequence shouldBe sequence
            unpackedLength shouldBe data.size
            assertBytesEqual(data, unpackedData, "Data should match")
        }

        @Test
        @DisplayName("Envelope sequence wrap-around handling")
        fun `envelope sequence wrap around handling`() {
            // Test sequence numbers at boundaries
            val testSequences = listOf(0, 1, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF)

            for (seq in testSequences) {
                val msgtype = 1
                val data = ByteArray(5)

                val pythonPackResult = python(
                    "envelope_pack",
                    "msgtype" to msgtype,
                    "sequence" to seq,
                    "data" to data
                )

                val envelope = pythonPackResult.getBytes("envelope")

                val pythonUnpackResult = python(
                    "envelope_unpack",
                    "envelope" to envelope
                )

                val unpackedSeq = pythonUnpackResult.getInt("sequence")
                unpackedSeq shouldBe seq
            }
        }

        @Test
        @DisplayName("Round-trip envelope pack/unpack preserves data")
        fun `round trip envelope pack unpack preserves data`() {
            val testCases = listOf(
                Triple(0x0001, 0, ByteArray(0)),
                Triple(0xF000, 100, ByteArray(1) { 0x42 }),
                Triple(0x1234, 50000, ByteArray(100) { (it % 256).toByte() }),
                Triple(0xFFFF, 0xFFFF, "Large text data for testing".toByteArray())
            )

            for ((msgtype, sequence, data) in testCases) {
                // Pack
                val packResult = python(
                    "envelope_pack",
                    "msgtype" to msgtype,
                    "sequence" to sequence,
                    "data" to data
                )
                val envelope = packResult.getBytes("envelope")

                // Unpack
                val unpackResult = python(
                    "envelope_unpack",
                    "envelope" to envelope
                )

                val unpackedMsgtype = unpackResult.getInt("msgtype")
                val unpackedSequence = unpackResult.getInt("sequence")
                val unpackedData = unpackResult.getBytes("data")

                unpackedMsgtype shouldBe msgtype
                unpackedSequence shouldBe sequence
                assertBytesEqual(data, unpackedData, "Data should match for msgtype=$msgtype")
            }
        }
    }

    @Nested
    @DisplayName("StreamDataMessage Pack")
    inner class StreamDataMessagePackTests {

        @Test
        @DisplayName("Stream data message pack matches Python")
        fun `stream data message pack matches python`() {
            val streamId = 123
            val data = "Stream test data".toByteArray()

            val pythonResult = python(
                "stream_msg_pack",
                "stream_id" to streamId,
                "data" to data,
                "eof" to false,
                "compressed" to false
            )

            val pythonMessage = pythonResult.getBytes("message")
            val headerVal = pythonResult.getInt("header_val")

            // Verify header value
            headerVal shouldBe streamId

            // Build Kotlin message
            val kotlinMessage = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = false
                this.compressed = false
            }.pack()

            assertBytesEqual(pythonMessage, kotlinMessage, "Stream message should match Python")
        }

        @Test
        @DisplayName("Stream message with EOF flag matches Python")
        fun `stream message with eof flag matches python`() {
            val streamId = 1
            val data = ByteArray(0)

            val pythonResult = python(
                "stream_msg_pack",
                "stream_id" to streamId,
                "data" to data,
                "eof" to true,
                "compressed" to false
            )

            val pythonMessage = pythonResult.getBytes("message")
            val headerVal = pythonResult.getInt("header_val")

            // Verify EOF flag is set (bit 15 = 0x8000)
            (headerVal and 0x8000) shouldBe 0x8000

            // Build Kotlin message
            val kotlinMessage = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = true
                this.compressed = false
            }.pack()

            assertBytesEqual(pythonMessage, kotlinMessage, "Stream message with EOF should match Python")
        }

        @Test
        @DisplayName("Stream message with compressed flag matches Python")
        fun `stream message with compressed flag matches python`() {
            val streamId = 50
            val data = ByteArray(20) { it.toByte() }

            val pythonResult = python(
                "stream_msg_pack",
                "stream_id" to streamId,
                "data" to data,
                "eof" to false,
                "compressed" to true
            )

            val pythonMessage = pythonResult.getBytes("message")
            val headerVal = pythonResult.getInt("header_val")

            // Verify compressed flag is set (bit 14 = 0x4000)
            (headerVal and 0x4000) shouldBe 0x4000

            // Build Kotlin message
            val kotlinMessage = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = false
                this.compressed = true
            }.pack()

            assertBytesEqual(pythonMessage, kotlinMessage, "Stream message with compressed should match Python")
        }

        @Test
        @DisplayName("Stream message with max stream_id (16383) matches Python")
        fun `stream message with max stream id matches python`() {
            val streamId = 16383 // 0x3FFF - max 14-bit value
            val data = "max stream".toByteArray()

            val pythonResult = python(
                "stream_msg_pack",
                "stream_id" to streamId,
                "data" to data,
                "eof" to false,
                "compressed" to false
            )

            val pythonMessage = pythonResult.getBytes("message")
            val headerVal = pythonResult.getInt("header_val")

            // Verify stream_id
            (headerVal and 0x3FFF) shouldBe streamId

            // Build Kotlin message
            val kotlinMessage = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = false
                this.compressed = false
            }.pack()

            assertBytesEqual(pythonMessage, kotlinMessage, "Stream message with max ID should match Python")
        }
    }

    @Nested
    @DisplayName("StreamDataMessage Unpack")
    inner class StreamDataMessageUnpackTests {

        @Test
        @DisplayName("Stream data message unpack matches Python")
        fun `stream data message unpack matches python`() {
            val streamId = 456
            val data = "Unpack stream test".toByteArray()
            val eof = false
            val compressed = false

            // Build message in Kotlin
            val message = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = eof
                this.compressed = compressed
            }.pack()

            val pythonResult = python(
                "stream_msg_unpack",
                "message" to message
            )

            val unpackedStreamId = pythonResult.getInt("stream_id")
            val unpackedEof = pythonResult.getBoolean("eof")
            val unpackedCompressed = pythonResult.getBoolean("compressed")
            val unpackedData = pythonResult.getBytes("data")

            unpackedStreamId shouldBe streamId
            unpackedEof shouldBe eof
            unpackedCompressed shouldBe compressed
            assertBytesEqual(data, unpackedData, "Data should match")
        }

        @Test
        @DisplayName("Stream message unpack with all flags")
        fun `stream message unpack with all flags`() {
            val streamId = 100
            val data = ByteArray(0)
            val eof = true
            val compressed = true

            // Build message with both flags
            val message = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.eof = eof
                this.compressed = compressed
            }.pack()

            val pythonResult = python(
                "stream_msg_unpack",
                "message" to message
            )

            val unpackedStreamId = pythonResult.getInt("stream_id")
            val unpackedEof = pythonResult.getBoolean("eof")
            val unpackedCompressed = pythonResult.getBoolean("compressed")

            unpackedStreamId shouldBe streamId
            unpackedEof shouldBe true
            unpackedCompressed shouldBe true
        }
    }
}
