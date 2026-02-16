package network.reticulum.interop.framing

import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.KISS
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Framing interoperability tests with Python RNS.
 */
@DisplayName("Framing Interop")
class FramingInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("HDLC Framing")
    inner class HdlcFraming {

        @Test
        @DisplayName("HDLC escape matches Python")
        fun `hdlc escape matches Python`() {
            val testCases = listOf(
                // Normal data (no escaping needed)
                ByteArray(10) { it.toByte() },
                // Data containing FLAG byte (0x7E)
                byteArrayOf(0x01, 0x7E, 0x02),
                // Data containing ESC byte (0x7D)
                byteArrayOf(0x01, 0x7D, 0x02),
                // Data containing both FLAG and ESC
                byteArrayOf(0x7E, 0x7D, 0x7E, 0x7D),
                // Multiple consecutive special bytes
                byteArrayOf(0x7E, 0x7E, 0x7D, 0x7D),
                // Empty data
                ByteArray(0),
                // Just a FLAG
                byteArrayOf(0x7E),
                // Just an ESC
                byteArrayOf(0x7D)
            )

            for (data in testCases) {
                val kotlinEscaped = HDLC.escape(data)
                val pythonResult = python("hdlc_escape", "data" to data)

                assertBytesEqual(
                    pythonResult.getBytes("escaped"),
                    kotlinEscaped,
                    "HDLC escape for ${data.toHex()}"
                )
            }
        }

        @Test
        @DisplayName("HDLC frame matches Python")
        fun `hdlc frame matches Python`() {
            val testCases = listOf(
                "Hello, World!".toByteArray(),
                ByteArray(100) { it.toByte() },
                byteArrayOf(0x7E, 0x7D, 0x00, 0xFF.toByte()),
                ByteArray(0)
            )

            for (data in testCases) {
                val kotlinFramed = HDLC.frame(data)
                val pythonResult = python("hdlc_frame", "data" to data)

                assertBytesEqual(
                    pythonResult.getBytes("framed"),
                    kotlinFramed,
                    "HDLC frame for ${data.size} bytes"
                )

                // Verify frame structure
                assert(kotlinFramed[0] == HDLC.FLAG) { "Frame should start with FLAG" }
                assert(kotlinFramed.last() == HDLC.FLAG) { "Frame should end with FLAG" }
            }
        }

        @Test
        @DisplayName("HDLC escape/unescape round-trip")
        fun `hdlc escape unescape round-trip`() {
            val testCases = listOf(
                ByteArray(256) { it.toByte() },
                byteArrayOf(0x7E, 0x7D, 0x7E, 0x7D),
                "Test message with special bytes: \u007E\u007D".toByteArray(),
                ByteArray(1000) { (it % 256).toByte() }
            )

            for (original in testCases) {
                val escaped = HDLC.escape(original)
                val unescaped = HDLC.unescape(escaped)

                assertBytesEqual(original, unescaped, "Round-trip for ${original.size} bytes")
            }
        }

        @Test
        @DisplayName("HDLC deframer handles streaming data")
        fun `hdlc deframer handles streaming data`() {
            // Messages must be > HEADER_MIN_SIZE (19 bytes) to pass HDLC deframer
            val messages = listOf(
                "First message padded!!".toByteArray(),
                "Second message padded!".toByteArray(),
                ByteArray(20) { (0x7E + it).toByte() }
            )

            val receivedMessages = mutableListOf<ByteArray>()
            val deframer = HDLC.createDeframer { data ->
                receivedMessages.add(data)
            }

            // Frame all messages
            val framedData = messages.map { HDLC.frame(it) }.reduce { acc, bytes -> acc + bytes }

            // Process in chunks
            val chunkSize = 7
            for (i in framedData.indices step chunkSize) {
                val chunk = framedData.copyOfRange(i, minOf(i + chunkSize, framedData.size))
                deframer.process(chunk)
            }

            assert(receivedMessages.size == messages.size) {
                "Should receive ${messages.size} messages, got ${receivedMessages.size}"
            }

            for (i in messages.indices) {
                assertBytesEqual(messages[i], receivedMessages[i], "Message $i")
            }
        }
    }

    @Nested
    @DisplayName("KISS Framing")
    inner class KissFraming {

        @Test
        @DisplayName("KISS escape matches Python")
        fun `kiss escape matches Python`() {
            val testCases = listOf(
                // Normal data (no escaping needed)
                ByteArray(10) { it.toByte() },
                // Data containing FEND byte (0xC0)
                byteArrayOf(0x01, 0xC0.toByte(), 0x02),
                // Data containing FESC byte (0xDB)
                byteArrayOf(0x01, 0xDB.toByte(), 0x02),
                // Data containing both FEND and FESC
                byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte(), 0xDB.toByte()),
                // Empty data
                ByteArray(0),
                // Just a FEND
                byteArrayOf(0xC0.toByte()),
                // Just a FESC
                byteArrayOf(0xDB.toByte())
            )

            for (data in testCases) {
                val kotlinEscaped = KISS.escape(data)
                val pythonResult = python("kiss_escape", "data" to data)

                assertBytesEqual(
                    pythonResult.getBytes("escaped"),
                    kotlinEscaped,
                    "KISS escape for ${data.toHex()}"
                )
            }
        }

        @Test
        @DisplayName("KISS frame matches Python")
        fun `kiss frame matches Python`() {
            val testCases = listOf(
                "Hello, World!".toByteArray(),
                ByteArray(100) { it.toByte() },
                byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x00, 0xFF.toByte()),
                ByteArray(0)
            )

            for (data in testCases) {
                val kotlinFramed = KISS.frame(data)
                val pythonResult = python("kiss_frame", "data" to data)

                assertBytesEqual(
                    pythonResult.getBytes("framed"),
                    kotlinFramed,
                    "KISS frame for ${data.size} bytes"
                )

                // Verify frame structure
                assert(kotlinFramed[0] == KISS.FEND) { "Frame should start with FEND" }
                assert(kotlinFramed[1] == KISS.CMD_DATA) { "Second byte should be CMD_DATA" }
                assert(kotlinFramed.last() == KISS.FEND) { "Frame should end with FEND" }
            }
        }

        @Test
        @DisplayName("KISS escape/unescape round-trip")
        fun `kiss escape unescape round-trip`() {
            val testCases = listOf(
                ByteArray(256) { it.toByte() },
                byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xC0.toByte(), 0xDB.toByte()),
                ByteArray(1000) { (it % 256).toByte() }
            )

            for (original in testCases) {
                val escaped = KISS.escape(original)
                val unescaped = KISS.unescape(escaped)

                assertBytesEqual(original, unescaped, "Round-trip for ${original.size} bytes")
            }
        }

        @Test
        @DisplayName("KISS deframer handles streaming data")
        fun `kiss deframer handles streaming data`() {
            val messages = listOf(
                "First KISS message".toByteArray(),
                "Second KISS message".toByteArray(),
                byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x00)
            )

            val receivedMessages = mutableListOf<ByteArray>()
            val deframer = KISS.createDeframer { cmd, data ->
                assert(cmd == KISS.CMD_DATA) { "Command should be CMD_DATA" }
                receivedMessages.add(data)
            }

            // Frame all messages
            val framedData = messages.map { KISS.frame(it) }.reduce { acc, bytes -> acc + bytes }

            // Process in chunks
            val chunkSize = 11
            for (i in framedData.indices step chunkSize) {
                val chunk = framedData.copyOfRange(i, minOf(i + chunkSize, framedData.size))
                deframer.process(chunk)
            }

            assert(receivedMessages.size == messages.size) {
                "Should receive ${messages.size} messages, got ${receivedMessages.size}"
            }

            for (i in messages.indices) {
                assertBytesEqual(messages[i], receivedMessages[i], "Message $i")
            }
        }
    }
}
