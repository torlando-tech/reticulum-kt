package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import org.junit.jupiter.api.Test

/**
 * Tests for Kotlin-to-Python LXMF message interoperability.
 *
 * Verifies that LXMF messages created and packed in Kotlin can be
 * correctly unpacked by Python's LXMF implementation via the bridge.
 *
 * This proves LXMF-01 requirement: Kotlin-packed messages are byte-compatible
 * with Python LXMF.
 */
class KotlinToPythonMessageTest : LXMFInteropTestBase() {

    @Test
    fun `kotlin message unpacks in Python with all fields preserved`() {
        println("\n=== Test: kotlin message unpacks in Python with all fields preserved ===")

        // Create message with title and content
        val message = createTestMessage(
            content = "Hello, World!",
            title = "Test Title"
        )

        // Time the pack operation
        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        // Verify in Python
        val pythonResult = verifyInPython(packed)

        // Use soft assertions to check all fields
        assertMessageMatchesPython(message, pythonResult)

        // Also verify packed bytes can round-trip
        val kotlinHash = message.hash?.toHex()
        val pythonHash = pythonResult.getString("message_hash")
        kotlinHash shouldBe pythonHash

        println("  SUCCESS: All fields match between Kotlin and Python")
    }

    @Test
    fun `empty message round-trips correctly`() {
        println("\n=== Test: empty message round-trips correctly ===")

        // Create message with empty title and content
        val message = createTestMessage(
            content = "",
            title = ""
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        // Verify in Python
        val pythonResult = verifyInPython(packed)

        // Use soft assertions
        assertSoftly {
            // Verify empty strings are preserved
            pythonResult.getString("title") shouldBe ""
            pythonResult.getString("content") shouldBe ""

            // Verify hashes still match
            message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
        }

        println("  SUCCESS: Empty message round-trips correctly")
    }

    @Test
    fun `unicode content round-trips correctly`() {
        println("\n=== Test: unicode content round-trips correctly ===")

        // Create message with multi-language unicode content (including emoji)
        val unicodeContent = "Hello, World! / Hej verden! / Hola mundo! / Ciao mondo! / \uD83D\uDE80\uD83C\uDF0D"
        val unicodeTitle = "Unicode Test / \u4E2D\u6587 / \u0420\u0443\u0441\u0441\u043A\u0438\u0439"

        val message = createTestMessage(
            content = unicodeContent,
            title = unicodeTitle
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        // Verify in Python
        val pythonResult = verifyInPython(packed)

        // Use soft assertions
        assertSoftly {
            // Verify UTF-8 encoded content matches
            pythonResult.getString("title") shouldBe unicodeTitle
            pythonResult.getString("content") shouldBe unicodeContent

            // Verify hashes match (proves bytes are identical)
            message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
        }

        println("  SUCCESS: Unicode content round-trips correctly")
        println("    Title: $unicodeTitle")
        println("    Content: $unicodeContent")
    }

    @Test
    fun `message with fields round-trips correctly`() {
        println("\n=== Test: message with fields round-trips correctly ===")

        // Create message with FIELD_RENDERER set
        val message = createTestMessage(
            content = "Markdown formatted content",
            title = "Field Test",
            fields = mutableMapOf(
                LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_MARKDOWN
            )
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        // Verify in Python
        val pythonResult = verifyInPython(packed)

        // Check fields - JSON returns keys as strings, so convert appropriately
        val pythonFields = pythonResult["fields"]?.jsonObject

        // Field key is 0x0F (FIELD_RENDERER = 15), value is 0x02 (RENDERER_MARKDOWN = 2)
        // In JSON, the key "15" should map to value 2
        val rendererField = pythonFields?.get(LXMFConstants.FIELD_RENDERER.toString())
        println("  Python fields: $pythonFields")
        println("  Renderer field (key=${LXMFConstants.FIELD_RENDERER}): $rendererField")

        assertSoftly {
            // Verify fields is not empty
            (pythonFields?.size ?: 0) shouldBe 1

            // Verify hash matches
            message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
        }

        println("  SUCCESS: Message with fields round-trips correctly")
    }

    @Test
    fun `timestamp precision preserved`() {
        println("\n=== Test: timestamp precision preserved ===")

        // Create message with specific timestamp
        val message = createTestMessage(
            content = "Timestamp test",
            title = "Timestamp Precision"
        )

        // Set a specific timestamp with subsecond precision
        val specificTimestamp = System.currentTimeMillis() / 1000.0
        message.timestamp = specificTimestamp

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")
        println("  [Kotlin] timestamp: $specificTimestamp")

        // Verify in Python
        val pythonResult = verifyInPython(packed)

        // Compare timestamps (both should use IEEE 754 float64)
        val kotlinTimestamp = message.timestamp!!
        val pythonTimestamp = pythonResult["timestamp"]?.let {
            when {
                it is kotlinx.serialization.json.JsonPrimitive -> it.content.toDouble()
                else -> null
            }
        } ?: throw AssertionError("Missing timestamp in Python result")

        println("  [Python] timestamp: $pythonTimestamp")

        assertSoftly {
            // Float64 should have exact match
            kotlinTimestamp shouldBe pythonTimestamp

            // Verify hash matches
            message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
        }

        println("  SUCCESS: Timestamp precision preserved (delta=${kotlinTimestamp - pythonTimestamp})")
    }
}
