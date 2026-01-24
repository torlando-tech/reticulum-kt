package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Tests for FIELD_IMAGE round-trip between Kotlin and Python.
 *
 * Verifies that image attachments in LXMF messages survive Kotlin-Python
 * round-trips with byte-level accuracy, including:
 * - Extension encoding (UTF-8 bytes)
 * - Binary image content preservation
 * - SHA-256 checksum verification for larger content
 */
class ImageFieldInteropTest : LXMFInteropTestBase() {

    companion object {
        /**
         * Minimal 1x1 pixel WebP image (lossless VP8L format).
         * Source: WebP specification - RIFF container with VP8L chunk.
         *
         * Structure:
         * - RIFF header (8 bytes): "RIFF" + file size - 8
         * - WEBP marker (4 bytes): "WEBP"
         * - VP8L chunk (17 bytes): "VP8L" + chunk size + signature + 1x1 image data
         * Total: 34 bytes
         */
        val MINIMAL_WEBP = byteArrayOf(
            // RIFF header
            0x52, 0x49, 0x46, 0x46,  // "RIFF"
            0x1a, 0x00, 0x00, 0x00,  // File size - 8 (26 bytes remaining)
            0x57, 0x45, 0x42, 0x50,  // "WEBP"
            // VP8L chunk (lossless)
            0x56, 0x50, 0x38, 0x4c,  // "VP8L"
            0x0d, 0x00, 0x00, 0x00,  // Chunk size (13 bytes)
            0x2f, 0x00, 0x00, 0x00,  // Signature
            0x00, 0x00, 0x00, 0x00,  // 1x1 image data
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00              // Padding to complete VP8L data
        ).also { require(it.size == 34) { "Expected 34 bytes, got ${it.size}" } }
    }

    /**
     * Helper to compute SHA-256 checksum of a byte array.
     */
    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this).toHex()
    }

    /**
     * Create a message with an image field.
     *
     * FIELD_IMAGE structure: [extension_bytes, image_bytes]
     * - Extension is stored as UTF-8 encoded bytes (not a String)
     * - Image bytes are the raw binary content
     */
    private fun createImageMessage(extension: String, imageBytes: ByteArray): LXMessage {
        val imageField = listOf(
            extension.toByteArray(Charsets.UTF_8),
            imageBytes
        )
        return createTestMessage(
            content = "Message with image",
            fields = mutableMapOf(
                LXMFConstants.FIELD_IMAGE to imageField
            )
        )
    }

    /**
     * Parsed image field from Python response.
     */
    data class ParsedImageField(
        val extension: String,
        val content: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedImageField) return false
            return extension == other.extension && content.contentEquals(other.content)
        }

        override fun hashCode(): Int {
            var result = extension.hashCode()
            result = 31 * result + content.contentHashCode()
            return result
        }
    }

    /**
     * Parse the FIELD_IMAGE from Python fields_hex response.
     *
     * Expected structure:
     * {
     *   "6": {                                // FIELD_IMAGE key
     *     "type": "list",
     *     "items": [
     *       { "type": "bytes", "hex": "..." },  // extension
     *       { "type": "bytes", "hex": "..." }   // content
     *     ]
     *   }
     * }
     */
    private fun parseImageFieldFromFieldsHex(pythonResult: kotlinx.serialization.json.JsonObject): ParsedImageField? {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject
            ?: return null

        val imageFieldKey = LXMFConstants.FIELD_IMAGE.toString()
        val imageField = fieldsHex[imageFieldKey]?.jsonObject
            ?: return null

        if (imageField.getString("type") != "list") {
            throw AssertionError("Expected list type for image field, got: ${imageField.getString("type")}")
        }

        val items = imageField["items"]?.jsonArray
            ?: throw AssertionError("Missing items in image field")

        if (items.size < 2) {
            throw AssertionError("Image field should have 2 items (extension, content), got: ${items.size}")
        }

        // First item is extension (bytes)
        val extensionObj = items[0].jsonObject
        if (extensionObj.getString("type") != "bytes") {
            throw AssertionError("Expected bytes type for extension, got: ${extensionObj.getString("type")}")
        }
        val extensionHex = extensionObj.getString("hex")
        val extensionBytes = extensionHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val extension = String(extensionBytes, Charsets.UTF_8)

        // Second item is content (bytes)
        val contentObj = items[1].jsonObject
        if (contentObj.getString("type") != "bytes") {
            throw AssertionError("Expected bytes type for content, got: ${contentObj.getString("type")}")
        }
        val contentHex = contentObj.getString("hex")
        val content = contentHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        return ParsedImageField(extension, content)
    }

    /**
     * Verify message in Python using lxmf_unpack_with_fields command.
     */
    private fun verifyInPythonWithFields(lxmfBytes: ByteArray): kotlinx.serialization.json.JsonObject {
        val startTime = System.currentTimeMillis()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to lxmfBytes.toHex())

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_unpack_with_fields completed in ${elapsed}ms")

        return result
    }

    @Test
    fun `webp image field round-trips correctly`() {
        println("\n=== Test: webp image field round-trips correctly ===")

        val message = createImageMessage("webp", MINIMAL_WEBP)

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")
        println("  [Kotlin] Image extension: webp, content size: ${MINIMAL_WEBP.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val parsedImage = parseImageFieldFromFieldsHex(pythonResult)

        assertSoftly {
            parsedImage shouldNotBe null
            parsedImage!!.extension shouldBe "webp"
            parsedImage.content.size shouldBe MINIMAL_WEBP.size
            parsedImage.content.contentEquals(MINIMAL_WEBP) shouldBe true

            println("  [Python] Extension match: ${parsedImage.extension}")
            println("  [Python] Content size match: ${parsedImage.content.size} bytes")
        }

        println("  SUCCESS: WebP image field round-trips correctly")
    }

    @Test
    fun `larger image content preserved`() {
        println("\n=== Test: larger image content preserved ===")

        // Create 10KB of random binary data simulating image content
        val imageBytes = Random.nextBytes(10 * 1024)
        val expectedChecksum = imageBytes.sha256Hex()

        println("  [Kotlin] Generated 10KB image content")
        println("  [Kotlin] SHA-256: $expectedChecksum")

        val message = createImageMessage("webp", imageBytes)

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val parsedImage = parseImageFieldFromFieldsHex(pythonResult)

        assertSoftly {
            parsedImage shouldNotBe null
            parsedImage!!.extension shouldBe "webp"
            parsedImage.content.size shouldBe imageBytes.size

            // Verify via SHA-256 checksum
            val actualChecksum = parsedImage.content.sha256Hex()
            println("  [Python] SHA-256: $actualChecksum")

            actualChecksum shouldBe expectedChecksum
        }

        println("  SUCCESS: Larger image content preserved (10KB, SHA-256 match)")
    }

    @Test
    fun `extension encoding as UTF-8 bytes`() {
        println("\n=== Test: extension encoding as UTF-8 bytes ===")

        // Test multiple common extensions
        val extensions = listOf("png", "jpeg", "gif", "bmp")
        val testContent = byteArrayOf(0x01, 0x02, 0x03, 0x04)  // Minimal content

        for (ext in extensions) {
            println("  Testing extension: $ext")

            val message = createImageMessage(ext, testContent)
            val packed = message.pack()

            val pythonResult = verifyInPythonWithFields(packed)
            val parsedImage = parseImageFieldFromFieldsHex(pythonResult)

            assertSoftly {
                parsedImage shouldNotBe null
                parsedImage!!.extension shouldBe ext
                println("    [Python] Extension decoded correctly: $ext")
            }
        }

        println("  SUCCESS: Extension encoding as UTF-8 bytes works for all tested formats")
    }

    @Test
    fun `image field with empty content`() {
        println("\n=== Test: image field with empty content ===")

        // Test edge case: image field with empty byte array
        // This discovers Python's behavior for this edge case
        val emptyContent = byteArrayOf()

        val message = createImageMessage("webp", emptyContent)

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val parsedImage = parseImageFieldFromFieldsHex(pythonResult)

        assertSoftly {
            parsedImage shouldNotBe null
            parsedImage!!.extension shouldBe "webp"
            parsedImage.content.size shouldBe 0
            println("  [Python] Empty content handled: 0 bytes")
        }

        println("  SUCCESS: Image field with empty content handled correctly")
    }
}
