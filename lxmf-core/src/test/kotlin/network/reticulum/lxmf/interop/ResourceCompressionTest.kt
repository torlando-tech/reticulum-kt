package network.reticulum.lxmf.interop

import io.kotest.matchers.shouldBe
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.toHex
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * BZ2 compression interoperability tests.
 *
 * Verifies that BZ2 compressed data from Kotlin can be decompressed
 * by Python, and vice versa. This is critical for Resource transfer
 * which uses BZ2 compression by default.
 */
class ResourceCompressionTest : InteropTestBase() {

    private val random = SecureRandom()

    /**
     * Compress data using BZ2 (same method as Resource.compress).
     */
    private fun compressBz2(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bz2 ->
            bz2.write(data)
        }
        return output.toByteArray()
    }

    /**
     * Decompress BZ2 data (same method as Resource.decompress).
     */
    private fun decompressBz2(compressed: ByteArray): ByteArray {
        return BZip2CompressorInputStream(ByteArrayInputStream(compressed)).use { bz2 ->
            bz2.readBytes()
        }
    }

    @Test
    fun `Kotlin BZ2 compressed data decompresses in Python`() {
        println("\n=== KOTLIN -> PYTHON BZ2 TEST ===\n")

        // Create repeating pattern that compresses well
        val originalData = "Hello World ".repeat(100).toByteArray(Charsets.UTF_8)
        println("[Test] Original data size: ${originalData.size} bytes")

        // Compress in Kotlin
        val compressed = compressBz2(originalData)
        println("[Test] Compressed size: ${compressed.size} bytes")
        println("[Test] Compression ratio: %.2f%%".format(compressed.size * 100.0 / originalData.size))

        // Send to Python for decompression
        val result = python("bz2_decompress", "compressed" to compressed.toHex())
        val decompressed = result.getBytes("decompressed")
        val decompressedSize = result.getInt("size")

        println("[Python] Decompressed size: $decompressedSize bytes")

        // Verify data matches
        decompressed.size shouldBe originalData.size
        decompressed.contentEquals(originalData) shouldBe true

        println("[OK] Kotlin BZ2 -> Python decompress successful")
    }

    @Test
    fun `Python BZ2 compressed data decompresses in Kotlin`() {
        println("\n=== PYTHON -> KOTLIN BZ2 TEST ===\n")

        // Create repeating pattern that compresses well
        val originalData = "Testing Python compression ".repeat(50).toByteArray(Charsets.UTF_8)
        println("[Test] Original data size: ${originalData.size} bytes")

        // Send to Python for compression
        val result = python("bz2_compress", "data" to originalData.toHex())
        val compressed = result.getBytes("compressed")
        val compressedSize = result.getInt("compressed_size")

        println("[Python] Compressed size: $compressedSize bytes")

        // Decompress in Kotlin
        val decompressed = decompressBz2(compressed)
        println("[Kotlin] Decompressed size: ${decompressed.size} bytes")

        // Verify data matches
        decompressed.size shouldBe originalData.size
        decompressed.contentEquals(originalData) shouldBe true

        println("[OK] Python BZ2 -> Kotlin decompress successful")
    }

    @Test
    fun `round-trip BZ2 compression preserves data`() {
        println("\n=== ROUND-TRIP BZ2 TEST ===\n")

        // Create varied test data
        val originalData = buildString {
            repeat(100) { i ->
                append("Line $i: ${if (i % 2 == 0) "even" else "odd"} data here\n")
            }
        }.toByteArray(Charsets.UTF_8)

        println("[Test] Original data size: ${originalData.size} bytes")

        // Step 1: Compress in Kotlin
        val kotlinCompressed = compressBz2(originalData)
        println("[Step 1] Kotlin compressed: ${kotlinCompressed.size} bytes")

        // Step 2: Decompress in Python
        val pyDecompResult = python("bz2_decompress", "compressed" to kotlinCompressed.toHex())
        val pyDecompressed = pyDecompResult.getBytes("decompressed")
        println("[Step 2] Python decompressed: ${pyDecompressed.size} bytes")

        // Verify intermediate step
        pyDecompressed.contentEquals(originalData) shouldBe true

        // Step 3: Compress in Python
        val pyCompResult = python("bz2_compress", "data" to pyDecompressed.toHex())
        val pythonCompressed = pyCompResult.getBytes("compressed")
        println("[Step 3] Python compressed: ${pythonCompressed.size} bytes")

        // Step 4: Decompress in Kotlin
        val finalData = decompressBz2(pythonCompressed)
        println("[Step 4] Kotlin decompressed: ${finalData.size} bytes")

        // Verify final data matches original
        finalData.size shouldBe originalData.size
        finalData.contentEquals(originalData) shouldBe true

        println("[OK] Round-trip BZ2 compression preserved data integrity")
    }

    @Test
    fun `incompressible data handled correctly`() {
        println("\n=== INCOMPRESSIBLE DATA TEST ===\n")

        // Create random data (incompressible)
        val randomData = ByteArray(1000)
        random.nextBytes(randomData)

        println("[Test] Random data size: ${randomData.size} bytes")

        // Compress in Kotlin
        val kotlinCompressed = compressBz2(randomData)
        println("[Kotlin] Compressed size: ${kotlinCompressed.size} bytes")
        println("[Test] Note: Compressed may be larger than original for random data")

        // Verify Python can still decompress
        val pyResult = python("bz2_decompress", "compressed" to kotlinCompressed.toHex())
        val pyDecompressed = pyResult.getBytes("decompressed")

        pyDecompressed.size shouldBe randomData.size
        pyDecompressed.contentEquals(randomData) shouldBe true
        println("[OK] Python decompressed Kotlin's incompressible data")

        // Compress in Python
        val pyCompResult = python("bz2_compress", "data" to randomData.toHex())
        val pythonCompressed = pyCompResult.getBytes("compressed")
        println("[Python] Compressed size: ${pythonCompressed.size} bytes")

        // Verify Kotlin can decompress
        val ktDecompressed = decompressBz2(pythonCompressed)
        ktDecompressed.size shouldBe randomData.size
        ktDecompressed.contentEquals(randomData) shouldBe true
        println("[OK] Kotlin decompressed Python's incompressible data")

        println("[OK] Both implementations handle incompressible data correctly")
    }
}
