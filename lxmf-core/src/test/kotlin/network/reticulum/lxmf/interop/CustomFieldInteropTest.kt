package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Tests for custom LXMF fields (FIELD_RENDERER, FIELD_THREAD, arbitrary fields)
 * round-trip between Kotlin and Python.
 *
 * Verifies that:
 * - Integer field values (like FIELD_RENDERER) survive round-trip
 * - Byte array field values (like FIELD_THREAD) survive round-trip
 * - Multiple custom fields survive round-trip together
 * - High field key values (0xFB-0xFE) work correctly
 * - Empty fields dictionary is handled correctly
 * - Msgpack Long/Int deserialization is handled properly
 */
class CustomFieldInteropTest : LXMFInteropTestBase() {

    /**
     * Verify message in Python using the lxmf_unpack_with_fields command.
     */
    private fun verifyInPythonWithFields(lxmfBytes: ByteArray): JsonObject {
        val startTime = System.currentTimeMillis()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to lxmfBytes.toHex())

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_unpack_with_fields completed in ${elapsed}ms")

        return result
    }

    /**
     * Parse an integer field value from the fields_hex structure.
     * Handles msgpack Long vs Int deserialization.
     */
    private fun parseIntFieldFromFieldsHex(pythonResult: JsonObject, fieldKey: Int): Int? {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject ?: return null
        val fieldObj = fieldsHex[fieldKey.toString()]?.jsonObject ?: return null

        val type = fieldObj.getString("type")
        return when (type) {
            "int" -> fieldObj["value"]?.jsonPrimitive?.content?.toInt()
            else -> throw AssertionError("Expected int type for field $fieldKey, got: $type")
        }
    }

    /**
     * Parse a bytes field value from the fields_hex structure.
     */
    private fun parseBytesFieldFromFieldsHex(pythonResult: JsonObject, fieldKey: Int): ByteArray? {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject ?: return null
        val fieldObj = fieldsHex[fieldKey.toString()]?.jsonObject ?: return null

        val type = fieldObj.getString("type")
        return when (type) {
            "bytes" -> {
                val hex = fieldObj.getString("hex")
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            else -> throw AssertionError("Expected bytes type for field $fieldKey, got: $type")
        }
    }

    /**
     * Parse a string field value from the fields_hex structure.
     *
     * Note: Kotlin's LXMessage.packValue() serializes strings as binary (bytes) to match
     * Python LXMF behavior. So Python will report type="bytes" and we decode as UTF-8.
     */
    private fun parseStringFieldFromFieldsHex(pythonResult: JsonObject, fieldKey: Int): String? {
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject ?: return null
        val fieldObj = fieldsHex[fieldKey.toString()]?.jsonObject ?: return null

        val type = fieldObj.getString("type")
        return when (type) {
            // Python str type (unlikely with LXMF serialization)
            "str" -> fieldObj["value"]?.jsonPrimitive?.content
            // Bytes type (expected - Kotlin serializes strings as binary)
            "bytes" -> {
                val hex = fieldObj.getString("hex")
                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                String(bytes, Charsets.UTF_8)
            }
            else -> throw AssertionError("Expected str or bytes type for field $fieldKey, got: $type")
        }
    }

    @Test
    fun `FIELD_RENDERER integer values round-trip correctly`() {
        println("\n=== Test: FIELD_RENDERER integer values round-trip correctly ===")

        // Test all 4 renderer values
        val rendererTests = listOf(
            LXMFConstants.RENDERER_PLAIN to "PLAIN",
            LXMFConstants.RENDERER_MICRON to "MICRON",
            LXMFConstants.RENDERER_MARKDOWN to "MARKDOWN",
            LXMFConstants.RENDERER_BBCODE to "BBCODE"
        )

        for ((rendererValue, rendererName) in rendererTests) {
            println("  Testing RENDERER_$rendererName ($rendererValue)...")

            val message = createTestMessage(
                content = "Content with $rendererName renderer",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_RENDERER to rendererValue
                )
            )

            val packStart = System.currentTimeMillis()
            val packed = message.pack()
            val packTime = System.currentTimeMillis() - packStart
            println("    [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

            val pythonResult = verifyInPythonWithFields(packed)

            val pythonRendererValue = parseIntFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_RENDERER)

            assertSoftly {
                pythonRendererValue shouldNotBe null
                pythonRendererValue shouldBe rendererValue
            }

            println("    RENDERER_$rendererName: Kotlin=$rendererValue, Python=$pythonRendererValue - MATCH")
        }

        println("  SUCCESS: All 4 FIELD_RENDERER values round-trip correctly")
    }

    @Test
    fun `FIELD_THREAD byte array round-trips correctly`() {
        println("\n=== Test: FIELD_THREAD byte array round-trips correctly ===")

        // Create a 16-byte thread ID (like a hash)
        val threadId = Random.nextBytes(16)
        println("  Thread ID: ${threadId.toHex()}")

        val message = createTestMessage(
            content = "Message in thread",
            fields = mutableMapOf(
                LXMFConstants.FIELD_THREAD to threadId
            )
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        val pythonThreadId = parseBytesFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_THREAD)

        assertSoftly {
            pythonThreadId shouldNotBe null
            pythonThreadId!!.size shouldBe threadId.size
            pythonThreadId.toHex() shouldBe threadId.toHex()
        }

        println("  [Python] Thread ID: ${pythonThreadId?.toHex()}")
        println("  SUCCESS: FIELD_THREAD byte array round-trips correctly (${threadId.size} bytes)")
    }

    @Test
    fun `multiple custom fields round-trip together`() {
        println("\n=== Test: multiple custom fields round-trip together ===")

        val rendererValue = LXMFConstants.RENDERER_MARKDOWN
        val threadId = Random.nextBytes(16)
        val debugValue = "debug-test-value"

        println("  Fields to send:")
        println("    FIELD_RENDERER = $rendererValue")
        println("    FIELD_THREAD = ${threadId.toHex()}")
        println("    FIELD_DEBUG = \"$debugValue\"")

        val message = createTestMessage(
            content = "Message with multiple custom fields",
            fields = mutableMapOf(
                LXMFConstants.FIELD_RENDERER to rendererValue,
                LXMFConstants.FIELD_THREAD to threadId,
                LXMFConstants.FIELD_DEBUG to debugValue
            )
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        assertSoftly {
            // Verify FIELD_RENDERER
            val pythonRenderer = parseIntFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_RENDERER)
            pythonRenderer shouldBe rendererValue
            println("  [Python] FIELD_RENDERER = $pythonRenderer - MATCH")

            // Verify FIELD_THREAD
            val pythonThread = parseBytesFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_THREAD)
            pythonThread shouldNotBe null
            pythonThread!!.toHex() shouldBe threadId.toHex()
            println("  [Python] FIELD_THREAD = ${pythonThread.toHex()} - MATCH")

            // Verify FIELD_DEBUG
            val pythonDebug = parseStringFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_DEBUG)
            pythonDebug shouldBe debugValue
            println("  [Python] FIELD_DEBUG = \"$pythonDebug\" - MATCH")
        }

        println("  SUCCESS: Multiple custom fields round-trip together")
    }

    @Test
    fun `arbitrary high field key values work`() {
        println("\n=== Test: arbitrary high field key values work ===")

        // Test with high field key values (0xFB-0xFE range)
        val customTypeValue = "custom-type-identifier"
        val customDataValue = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val nonSpecificValue = "non-specific-data"

        println("  Fields to send:")
        println("    FIELD_CUSTOM_TYPE (0xFB) = \"$customTypeValue\"")
        println("    FIELD_CUSTOM_DATA (0xFC) = ${customDataValue.toHex()}")
        println("    FIELD_NON_SPECIFIC (0xFE) = \"$nonSpecificValue\"")

        val message = createTestMessage(
            content = "Message with high field key values",
            fields = mutableMapOf(
                LXMFConstants.FIELD_CUSTOM_TYPE to customTypeValue,
                LXMFConstants.FIELD_CUSTOM_DATA to customDataValue,
                LXMFConstants.FIELD_NON_SPECIFIC to nonSpecificValue
            )
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        assertSoftly {
            // Verify FIELD_CUSTOM_TYPE (0xFB = 251)
            val pythonCustomType = parseStringFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_CUSTOM_TYPE)
            pythonCustomType shouldBe customTypeValue
            println("  [Python] FIELD_CUSTOM_TYPE = \"$pythonCustomType\" - MATCH")

            // Verify FIELD_CUSTOM_DATA (0xFC = 252)
            val pythonCustomData = parseBytesFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_CUSTOM_DATA)
            pythonCustomData shouldNotBe null
            pythonCustomData!!.toHex() shouldBe customDataValue.toHex()
            println("  [Python] FIELD_CUSTOM_DATA = ${pythonCustomData.toHex()} - MATCH")

            // Verify FIELD_NON_SPECIFIC (0xFE = 254)
            val pythonNonSpecific = parseStringFieldFromFieldsHex(pythonResult, LXMFConstants.FIELD_NON_SPECIFIC)
            pythonNonSpecific shouldBe nonSpecificValue
            println("  [Python] FIELD_NON_SPECIFIC = \"$pythonNonSpecific\" - MATCH")
        }

        println("  SUCCESS: High field key values (0xFB, 0xFC, 0xFE) work correctly")
    }

    @Test
    fun `empty fields dictionary handled correctly`() {
        println("\n=== Test: empty fields dictionary handled correctly ===")

        val message = createTestMessage(
            content = "Message with empty fields",
            fields = mutableMapOf()  // Explicitly empty
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)

        // Empty fields should result in empty or absent fields_hex
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject

        assertSoftly {
            // Either fields_hex is null/absent, or it's an empty object
            if (fieldsHex != null) {
                fieldsHex.size shouldBe 0
                println("  [Python] fields_hex is present but empty: {} - OK")
            } else {
                println("  [Python] fields_hex is absent - OK")
            }
        }

        println("  SUCCESS: Empty fields dictionary handled correctly")
    }

    @Test
    fun `null field value is serialized correctly`() {
        println("\n=== Test: null field value is serialized correctly ===")

        // Test what happens when we explicitly set a null value in fields
        // This documents Python's behavior with null/None values
        val fields = mutableMapOf<Int, Any>(
            LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_MARKDOWN
        )

        // We can't actually put null in MutableMap<Int, Any> directly,
        // so we test behavior with a map that might have missing keys
        val message = createTestMessage(
            content = "Message testing null handling",
            fields = fields
        )

        val packStart = System.currentTimeMillis()
        val packed = message.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

        val pythonResult = verifyInPythonWithFields(packed)
        val fieldsHex = pythonResult["fields_hex"]?.jsonObject

        assertSoftly {
            fieldsHex shouldNotBe null

            // FIELD_RENDERER should be present
            val rendererField = fieldsHex?.get(LXMFConstants.FIELD_RENDERER.toString())
            rendererField shouldNotBe null
            println("  [Python] FIELD_RENDERER present: true")

            // A field we didn't set should be absent (not null)
            val absentField = fieldsHex?.get(LXMFConstants.FIELD_THREAD.toString())
            absentField shouldBe null
            println("  [Python] FIELD_THREAD absent: true (as expected)")
        }

        // Document behavior: Kotlin Map<Int, Any> cannot have null values
        // so we test that absent keys remain absent in Python
        println("  Note: Kotlin Map<Int, Any> cannot have null values")
        println("  Behavior: absent fields in Kotlin remain absent in Python")
        println("  SUCCESS: Field presence/absence handled correctly")
    }
}
