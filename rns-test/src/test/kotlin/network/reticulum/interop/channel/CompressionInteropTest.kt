package network.reticulum.interop.channel

import network.reticulum.channel.StreamDataMessage
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interoperability tests for BZ2 compression used in Channel/Buffer.
 *
 * BZ2 compression is used for StreamDataMessage when data is large enough
 * to benefit from compression. The compression flag (bit 14) indicates
 * whether the data payload is BZ2 compressed.
 *
 * Tests verify that Python's BZ2 compression/decompression produces
 * consistent results that would be compatible with Kotlin's implementation.
 * The actual Kotlin BZ2 implementation is in rns-core (commons-compress).
 */
@DisplayName("Compression Interop")
class CompressionInteropTest : InteropTestBase() {

    @Nested
    @DisplayName("BZ2 Compression Format")
    inner class Bz2Compression {

        @Test
        @DisplayName("Python compress/decompress round-trip")
        fun `python compress decompress round trip`() {
            val originalData = "Hello, this is test data for compression. ".repeat(100).toByteArray()

            // Compress in Python
            val compressResult = python(
                "bz2_compress",
                "data" to originalData
            )
            val compressed = compressResult.getBytes("compressed")

            // Decompress in Python
            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertBytesEqual(originalData, decompressed, "Round-trip should preserve data")
        }

        @Test
        @DisplayName("Empty data compresses correctly")
        fun `empty data compresses correctly`() {
            val emptyData = ByteArray(0)

            // Compress in Python
            val compressResult = python(
                "bz2_compress",
                "data" to emptyData
            )
            val compressed = compressResult.getBytes("compressed")

            // Decompress in Python
            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertEquals(0, decompressed.size, "Empty data should remain empty after compression")
        }

        @Test
        @DisplayName("Small data compresses correctly")
        fun `small data compresses correctly`() {
            val smallData = "Hi".toByteArray()

            val compressResult = python(
                "bz2_compress",
                "data" to smallData
            )
            val compressed = compressResult.getBytes("compressed")

            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertBytesEqual(smallData, decompressed, "Small data should compress/decompress correctly")
        }

        @Test
        @DisplayName("Large data compresses with good ratio")
        fun `large data compresses with good ratio`() {
            // Create 100KB of repetitive data (compresses well)
            val largeData = ByteArray(100_000) { (it % 256).toByte() }

            val compressResult = python(
                "bz2_compress",
                "data" to largeData
            )
            val compressed = compressResult.getBytes("compressed")
            val compressedSize = compressResult.getInt("compressed_size")

            // Verify compression reduced size
            assertTrue(compressedSize < largeData.size, "Compression should reduce size")

            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertEquals(largeData.size, decompressed.size, "Decompressed size should match original")
            assertBytesEqual(largeData, decompressed, "Large data should decompress correctly")
        }

        @Test
        @DisplayName("Binary data with all byte values")
        fun `binary data with all byte values`() {
            // Create binary data with all possible byte values
            val binaryData = ByteArray(512) { it.toByte() }

            val compressResult = python(
                "bz2_compress",
                "data" to binaryData
            )
            val compressed = compressResult.getBytes("compressed")

            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertBytesEqual(binaryData, decompressed, "Binary data should compress/decompress correctly")
        }

        @Test
        @DisplayName("High entropy data compresses correctly")
        fun `high entropy data compresses correctly`() {
            // Create pseudo-random data that doesn't compress well
            val randomData = ByteArray(1000) { (it * 17 + it / 3).toByte() }

            val compressResult = python(
                "bz2_compress",
                "data" to randomData
            )
            val compressed = compressResult.getBytes("compressed")

            val decompressResult = python(
                "bz2_decompress",
                "compressed" to compressed
            )
            val decompressed = decompressResult.getBytes("decompressed")

            assertBytesEqual(randomData, decompressed, "High entropy data should decompress correctly")
        }
    }

    @Nested
    @DisplayName("Compression Stats")
    inner class CompressionStats {

        @Test
        @DisplayName("Compression ratio reported correctly")
        fun `compression ratio reported correctly`() {
            val data = "AAAAAAAAAA".repeat(100).toByteArray()

            val pythonResult = python(
                "bz2_compress",
                "data" to data
            )

            val originalSize = pythonResult.getInt("original_size")
            val compressedSize = pythonResult.getInt("compressed_size")

            assertEquals(data.size, originalSize, "Original size should match input")
            assertTrue(compressedSize < originalSize, "Repetitive data should compress well")
            assertTrue(compressedSize > 0, "Compressed size should be positive")
        }
    }

    @Nested
    @DisplayName("StreamDataMessage Compression Flag")
    inner class StreamDataMessageCompression {

        @Test
        @DisplayName("Compression flag encoding in header")
        fun `compression flag encoding in header`() {
            val streamId = 123
            val data = "test".toByteArray()

            // Pack without compression
            val uncompressedMsg = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.compressed = false
            }.pack()

            // Pack with compression flag
            val compressedMsg = StreamDataMessage().apply {
                this.streamId = streamId
                this.data = data
                this.compressed = true
            }.pack()

            // Verify the header differs by the compression flag (bit 14 = 0x4000)
            val uncompressedHeader = ((uncompressedMsg[0].toInt() and 0xFF) shl 8) or (uncompressedMsg[1].toInt() and 0xFF)
            val compressedHeader = ((compressedMsg[0].toInt() and 0xFF) shl 8) or (compressedMsg[1].toInt() and 0xFF)

            assertEquals(0, uncompressedHeader and 0x4000, "Uncompressed should not have bit 14 set")
            assertEquals(0x4000, compressedHeader and 0x4000, "Compressed should have bit 14 set")

            // Stream ID should be same in both
            assertEquals(streamId, uncompressedHeader and 0x3FFF, "Stream ID preserved")
            assertEquals(streamId, compressedHeader and 0x3FFF, "Stream ID preserved")
        }
    }
}
