package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Tests for FIELD_FILE_ATTACHMENTS round-trip between Kotlin and Python.
 *
 * Verifies that file attachments in LXMF messages survive Kotlin-Python
 * round-trips with byte-level accuracy, including:
 * - Filename encoding (UTF-8)
 * - Binary content preservation
 * - Attachment ordering
 * - Unicode filename handling
 */
class AttachmentFieldInteropTest : LXMFInteropTestBase() {

    /**
     * Fixture for test attachments.
     */
    data class AttachmentFixture(
        val filename: String,
        val content: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AttachmentFixture) return false
            return filename == other.filename && content.contentEquals(other.content)
        }

        override fun hashCode(): Int {
            var result = filename.hashCode()
            result = 31 * result + content.contentHashCode()
            return result
        }
    }

    /**
     * Create a message with file attachments.
     */
    private fun createAttachmentMessage(attachments: List<AttachmentFixture>) = createTestMessage(
        content = "Message with ${attachments.size} attachment(s)",
        fields = mutableMapOf(
            LXMFConstants.FIELD_FILE_ATTACHMENTS to attachments.map { att ->
                listOf(att.filename.toByteArray(Charsets.UTF_8), att.content)
            }
        )
    )

    /**
     * Parse the fields_hex structure from Python response.
     * Returns list of (filename, content) pairs.
     */
    private fun parseAttachmentsFromFieldsHex(pythonResult: JsonObject): List<AttachmentFixture> {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject
            ?: return emptyList()

        val attachmentFieldKey = LXMFConstants.FIELD_FILE_ATTACHMENTS.toString()
        val attachmentField = fieldsHex[attachmentFieldKey]?.jsonObject
            ?: return emptyList()

        // Expected structure: { type: "list", items: [ { type: "list", items: [ {type: "bytes", hex: "..."}, {type: "bytes", hex: "..."} ] }, ... ] }
        if (attachmentField.getString("type") != "list") {
            throw AssertionError("Expected list type for attachments field, got: ${attachmentField.getString("type")}")
        }

        val items = attachmentField["items"]?.jsonArray
            ?: throw AssertionError("Missing items in attachments field")

        return items.map { item ->
            val attachmentList = item.jsonObject
            if (attachmentList.getString("type") != "list") {
                throw AssertionError("Expected list type for attachment, got: ${attachmentList.getString("type")}")
            }

            val attachmentItems = attachmentList["items"]?.jsonArray
                ?: throw AssertionError("Missing items in attachment")

            if (attachmentItems.size < 2) {
                throw AssertionError("Attachment should have at least 2 items (filename, content), got: ${attachmentItems.size}")
            }

            // First item is filename (bytes)
            val filenameObj = attachmentItems[0].jsonObject
            if (filenameObj.getString("type") != "bytes") {
                throw AssertionError("Expected bytes type for filename, got: ${filenameObj.getString("type")}")
            }
            val filenameHex = filenameObj.getString("hex")
            val filenameBytes = filenameHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val filename = String(filenameBytes, Charsets.UTF_8)

            // Second item is content (bytes)
            val contentObj = attachmentItems[1].jsonObject
            if (contentObj.getString("type") != "bytes") {
                throw AssertionError("Expected bytes type for content, got: ${contentObj.getString("type")}")
            }
            val contentHex = contentObj.getString("hex")
            val content = contentHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            AttachmentFixture(filename, content)
        }
    }

    /**
     * Verify message in Python using the new lxmf_unpack_with_fields command.
     */
    private fun verifyInPythonWithFields(lxmfBytes: ByteArray): JsonObject {
        val startTime = System.currentTimeMillis()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to lxmfBytes.toHex())

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_unpack_with_fields completed in ${elapsed}ms")

        return result
    }

    @Test
    fun `single text attachment round-trips correctly`() {
        println("\n=== Test: single text attachment round-trips correctly ===")

        val attachment = AttachmentFixture(
            filename = "readme.txt",
            content = "Hello, this is a text file attachment!".toByteArray(Charsets.UTF_8)
        )

        val message = createAttachmentMessage(listOf(attachment))

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        // Parse attachments from Python response
        val pythonAttachments = parseAttachmentsFromFieldsHex(pythonResult)

        assertSoftly {
            pythonAttachments shouldHaveSize 1

            val pythonAttachment = pythonAttachments[0]
            pythonAttachment.filename shouldBe attachment.filename
            pythonAttachment.content.contentEquals(attachment.content) shouldBe true

            println("  Filename match: ${attachment.filename}")
            println("  Content match: ${attachment.content.size} bytes")
        }

        println("  SUCCESS: Single text attachment round-trips correctly")
    }

    @Test
    fun `multiple attachments preserve ordering`() {
        println("\n=== Test: multiple attachments preserve ordering ===")

        val attachments = listOf(
            AttachmentFixture("first.txt", "First file content".toByteArray(Charsets.UTF_8)),
            AttachmentFixture("second.bin", byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte())),
            AttachmentFixture("third.txt", "Third file content".toByteArray(Charsets.UTF_8))
        )

        val message = createAttachmentMessage(attachments)

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val pythonAttachments = parseAttachmentsFromFieldsHex(pythonResult)

        assertSoftly {
            pythonAttachments shouldHaveSize 3

            // Verify ordering is preserved
            for (i in attachments.indices) {
                val expected = attachments[i]
                val actual = pythonAttachments[i]

                actual.filename shouldBe expected.filename
                actual.content.contentEquals(expected.content) shouldBe true

                println("  Attachment $i: ${expected.filename} - ${expected.content.size} bytes OK")
            }
        }

        println("  SUCCESS: Multiple attachments preserve ordering")
    }

    @Test
    fun `binary attachment content preserved`() {
        println("\n=== Test: binary attachment content preserved ===")

        // Create 1KB of random binary data
        val binaryContent = Random.nextBytes(1024)
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(binaryContent).toHex()

        val attachment = AttachmentFixture(
            filename = "binary_data.bin",
            content = binaryContent
        )

        val message = createAttachmentMessage(listOf(attachment))

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")
        println("  [Kotlin] Binary content SHA-256: $expectedChecksum")

        val pythonResult = verifyInPythonWithFields(packed)

        val pythonAttachments = parseAttachmentsFromFieldsHex(pythonResult)

        assertSoftly {
            pythonAttachments shouldHaveSize 1

            val pythonAttachment = pythonAttachments[0]
            pythonAttachment.filename shouldBe attachment.filename

            // Verify via SHA-256 checksum
            val actualChecksum = MessageDigest.getInstance("SHA-256").digest(pythonAttachment.content).toHex()
            println("  [Python] Binary content SHA-256: $actualChecksum")

            actualChecksum shouldBe expectedChecksum
            pythonAttachment.content.size shouldBe binaryContent.size
        }

        println("  SUCCESS: Binary attachment content preserved (1024 bytes, SHA-256 match)")
    }

    @Test
    fun `unicode filename survives round-trip`() {
        println("\n=== Test: unicode filename survives round-trip ===")

        // Filename with emoji and CJK characters
        val unicodeFilename = "\uD83D\uDCDD_notes_\u4E2D\u6587_\u0420\u0443\u0441.txt"
        println("  Unicode filename: $unicodeFilename")

        val attachment = AttachmentFixture(
            filename = unicodeFilename,
            content = "Unicode filename test content".toByteArray(Charsets.UTF_8)
        )

        val message = createAttachmentMessage(listOf(attachment))

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val pythonAttachments = parseAttachmentsFromFieldsHex(pythonResult)

        assertSoftly {
            pythonAttachments shouldHaveSize 1

            val pythonAttachment = pythonAttachments[0]
            println("  [Python] Decoded filename: ${pythonAttachment.filename}")

            pythonAttachment.filename shouldBe unicodeFilename
            pythonAttachment.content.contentEquals(attachment.content) shouldBe true
        }

        println("  SUCCESS: Unicode filename survives round-trip")
    }

    @Test
    fun `empty attachments list handled correctly`() {
        println("\n=== Test: empty attachments list handled correctly ===")

        // Create message with empty attachments list
        val message = createTestMessage(
            content = "Message with empty attachments",
            fields = mutableMapOf(
                LXMFConstants.FIELD_FILE_ATTACHMENTS to emptyList<List<ByteArray>>()
            )
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val pythonAttachments = parseAttachmentsFromFieldsHex(pythonResult)

        assertSoftly {
            // Empty list should result in empty attachments
            pythonAttachments shouldHaveSize 0
            println("  [Python] Empty attachments list handled: 0 attachments")
        }

        // Also verify the field is present in fields_hex
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject
        fieldsHex shouldNotBe null

        val attachmentFieldKey = LXMFConstants.FIELD_FILE_ATTACHMENTS.toString()
        val attachmentField = fieldsHex?.get(attachmentFieldKey)?.jsonObject
        attachmentField shouldNotBe null
        attachmentField?.getString("type") shouldBe "list"

        val items = attachmentField?.get("items")?.jsonArray
        items shouldNotBe null
        items?.size shouldBe 0

        println("  SUCCESS: Empty attachments list handled correctly (field present, empty list)")
    }
}
