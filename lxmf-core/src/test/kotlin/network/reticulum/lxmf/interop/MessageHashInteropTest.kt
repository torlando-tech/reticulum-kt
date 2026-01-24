package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for LXMF message hash computation interoperability between Kotlin and Python.
 *
 * Message hash is computed as: SHA256(destination_hash + source_hash + packed_payload)
 * where packed_payload = msgpack([timestamp, title_bytes, content_bytes, fields])
 *
 * Correct hash computation is prerequisite for signature validation since the signature
 * is computed over hashedPart + hash.
 */
class MessageHashInteropTest : LXMFInteropTestBase() {

    /**
     * Verify message in Python using lxmf_unpack_with_fields command.
     * This handles binary field values properly for JSON serialization.
     */
    private fun verifyInPythonWithFields(lxmfBytes: ByteArray): JsonObject {
        val startTime = System.currentTimeMillis()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to lxmfBytes.toHex())

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_unpack_with_fields completed in ${elapsed}ms")

        return result
    }

    @Nested
    @DisplayName("BasicHashComputation")
    inner class BasicHashComputation {

        @Test
        fun `message hash matches Python for simple message`() {
            println("\n=== Test: message hash matches Python for simple message ===")

            val message = createTestMessage(content = "Hello from Kotlin!")

            val packStart = System.currentTimeMillis()
            val packed = message.pack()
            val packTime = System.currentTimeMillis() - packStart
            println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

            val pythonResult = verifyInPython(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Kotlin hash:  ${message.hash?.toHex()}")
            println("  Python hash:  ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Simple message hash matches Python")
        }

        @Test
        fun `message hash matches Python for message with title`() {
            println("\n=== Test: message hash matches Python for message with title ===")

            val message = createTestMessage(
                content = "Message body content",
                title = "Important Subject"
            )

            val packStart = System.currentTimeMillis()
            val packed = message.pack()
            val packTime = System.currentTimeMillis() - packStart
            println("  [Kotlin] pack() completed in ${packTime}ms, size=${packed.size} bytes")

            val pythonResult = verifyInPython(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.title shouldBe pythonResult.getString("title")
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Title: '${message.title}'")
            println("  Kotlin hash:  ${message.hash?.toHex()}")
            println("  Python hash:  ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Message with title hash matches Python")
        }
    }

    @Nested
    @DisplayName("HashIdempotency")
    inner class HashIdempotency {

        @Test
        fun `hash is idempotent after pack-unpack round-trip`() {
            println("\n=== Test: hash is idempotent after pack-unpack round-trip ===")

            val message = createTestMessage(content = "Idempotency test message")

            // First pack
            val packed1 = message.pack()
            val hash1 = message.hash?.toHex()
            println("  [Kotlin] First pack hash: $hash1")

            // Verify in Python
            val pythonResult = verifyInPython(packed1)
            val pythonHash = pythonResult.getString("message_hash")
            println("  [Python] Unpacked hash:   $pythonHash")

            // Second pack (should use cached packed bytes and same hash)
            val packed2 = message.pack()
            val hash2 = message.hash?.toHex()
            println("  [Kotlin] Second pack hash: $hash2")

            assertSoftly {
                hash1 shouldBe hash2
                hash1 shouldBe pythonHash
                packed1.size shouldBe packed2.size
            }

            println("  SUCCESS: Hash is idempotent across pack calls")
        }

        @Test
        fun `Kotlin hash computation matches Python lxmf_hash command`() {
            println("\n=== Test: Kotlin hash computation matches Python lxmf_hash command ===")

            // Create message and pack to get timestamp and hash
            val message = createTestMessage(content = "Hash verification test")
            message.pack()

            println("  [Kotlin] destination_hash: ${message.destinationHash.toHex()}")
            println("  [Kotlin] source_hash: ${message.sourceHash.toHex()}")
            println("  [Kotlin] timestamp: ${message.timestamp}")
            println("  [Kotlin] title: '${message.title}'")
            println("  [Kotlin] content: '${message.content}'")

            // Call Python's lxmf_hash with same components
            val pythonResult = python(
                "lxmf_hash",
                "destination_hash" to message.destinationHash.toHex(),
                "source_hash" to message.sourceHash.toHex(),
                "timestamp" to message.timestamp!!,
                "title" to message.title,
                "content" to message.content,
                "fields" to mapOf<String, Any>()
            )

            val kotlinHash = message.hash?.toHex()
            val pythonHash = pythonResult.getString("message_hash")

            println("  [Kotlin] computed hash: $kotlinHash")
            println("  [Python] lxmf_hash:     $pythonHash")

            kotlinHash shouldBe pythonHash

            println("  SUCCESS: Kotlin hash computation matches Python lxmf_hash command")
        }
    }

    @Nested
    @DisplayName("EdgeCases")
    inner class EdgeCases {

        @Test
        fun `empty content produces valid hash`() {
            println("\n=== Test: empty content produces valid hash ===")

            val message = createTestMessage(content = "")

            val packed = message.pack()
            println("  [Kotlin] Packed ${packed.size} bytes with empty content")

            val pythonResult = verifyInPython(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.content shouldBe ""
                pythonResult.getString("content") shouldBe ""
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Kotlin hash: ${message.hash?.toHex()}")
            println("  Python hash: ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Empty content produces valid matching hash")
        }

        @Test
        fun `empty title and content produces valid hash`() {
            println("\n=== Test: empty title and content produces valid hash ===")

            val message = createTestMessage(content = "", title = "")

            val packed = message.pack()
            println("  [Kotlin] Packed ${packed.size} bytes with empty title and content")

            val pythonResult = verifyInPython(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.title shouldBe ""
                message.content shouldBe ""
                pythonResult.getString("title") shouldBe ""
                pythonResult.getString("content") shouldBe ""
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Kotlin hash: ${message.hash?.toHex()}")
            println("  Python hash: ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Empty title and content produce valid matching hash")
        }

        @Test
        fun `Unicode content produces matching hash`() {
            println("\n=== Test: Unicode content produces matching hash ===")

            // Include emoji, CJK characters, and other Unicode
            val unicodeContent = "Hello World! Emoji test and CJK chars"
            val message = createTestMessage(content = unicodeContent)

            val packed = message.pack()
            println("  [Kotlin] Packed ${packed.size} bytes with Unicode content")
            println("  Content: $unicodeContent")

            val pythonResult = verifyInPython(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.content shouldBe pythonResult.getString("content")
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Kotlin hash: ${message.hash?.toHex()}")
            println("  Python hash: ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Unicode content produces matching hash")
        }

        @Test
        fun `message with fields produces matching hash`() {
            println("\n=== Test: message with fields produces matching hash ===")

            val message = createTestMessage(
                content = "Message with fields for hash test",
                fields = mutableMapOf(
                    LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_MARKDOWN,
                    LXMFConstants.FIELD_DEBUG to "debug-value"
                )
            )

            val packed = message.pack()
            println("  [Kotlin] Packed ${packed.size} bytes with fields")
            println("  Fields: FIELD_RENDERER=${LXMFConstants.RENDERER_MARKDOWN}, FIELD_DEBUG='debug-value'")

            // Use lxmf_unpack_with_fields because fields contain bytes that need serialization
            val pythonResult = verifyInPythonWithFields(packed)

            assertSoftly {
                message.hash shouldNotBe null
                message.hash?.toHex() shouldBe pythonResult.getString("message_hash")
            }

            println("  Kotlin hash: ${message.hash?.toHex()}")
            println("  Python hash: ${pythonResult.getString("message_hash")}")
            println("  SUCCESS: Message with fields produces matching hash")
        }
    }
}
