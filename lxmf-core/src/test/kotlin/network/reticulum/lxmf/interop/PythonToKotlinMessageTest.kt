package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.Test

/**
 * Tests for Python-to-Kotlin LXMF message interoperability.
 *
 * Verifies that LXMF messages created and packed by Python can be
 * correctly unpacked by Kotlin's LXMessage implementation.
 *
 * This proves LXMF-02 requirement: Python-packed messages are byte-compatible
 * with Kotlin LXMF.
 */
class PythonToKotlinMessageTest : LXMFInteropTestBase() {

    /**
     * Create an LXMF message in Python and return the packed bytes.
     *
     * Uses lxmf_pack to build the payload and hash, then ed25519_sign to sign.
     * Assembles the full message: dest_hash + source_hash + signature + packed_payload
     *
     * @param destinationHash 16-byte destination hash
     * @param sourceHash 16-byte source hash
     * @param ed25519PrivateKey 32-byte Ed25519 private key (seed) for signing
     * @param timestamp UNIX timestamp (float64 seconds)
     * @param title Message title
     * @param content Message content
     * @param fields Extended fields map (string keys for JSON)
     * @return Packed LXMF message bytes
     */
    private fun createMessageInPython(
        destinationHash: ByteArray,
        sourceHash: ByteArray,
        ed25519PrivateKey: ByteArray,
        timestamp: Double,
        title: String,
        content: String,
        fields: Map<String, Any> = emptyMap()
    ): ByteArray {
        val startTime = System.currentTimeMillis()

        // Call lxmf_pack to get packed payload and compute hash
        val packResult = python(
            "lxmf_pack",
            "destination_hash" to destinationHash,
            "source_hash" to sourceHash,
            "timestamp" to timestamp,
            "title" to title,
            "content" to content,
            "fields" to fields
        )

        val packedPayload = packResult.getBytes("packed_payload")
        val signedPart = packResult.getBytes("signed_part")
        val messageHash = packResult.getString("message_hash")

        // Sign the signed_part with Ed25519 private key (seed)
        val signResult = python(
            "ed25519_sign",
            "private_key" to ed25519PrivateKey,
            "message" to signedPart
        )

        val signature = signResult.getBytes("signature")

        // Assemble full message: dest_hash + source_hash + signature + packed_payload
        val packedMessage = destinationHash + sourceHash + signature + packedPayload

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_pack + ed25519_sign completed in ${elapsed}ms")
        println("  [Python] message_hash: $messageHash")
        println("  [Python] packed size: ${packedMessage.size} bytes")

        return packedMessage
    }

    @Test
    fun `python message unpacks in Kotlin with all fields preserved`() {
        println("\n=== Test: python message unpacks in Kotlin with all fields preserved ===")

        // Create message in Python
        val timestamp = System.currentTimeMillis() / 1000.0
        val title = "Hello from Python"
        val content = "This message was created by Python LXMF"

        val packedBytes = createMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            ed25519PrivateKey = testSourceIdentity.sigPrv,
            timestamp = timestamp,
            title = title,
            content = content
        )

        // Unpack in Kotlin
        val unpackStart = System.currentTimeMillis()
        val message = LXMessage.unpackFromBytes(packedBytes)
        val unpackTime = System.currentTimeMillis() - unpackStart
        println("  [Kotlin] unpackFromBytes() completed in ${unpackTime}ms")

        // Verify message was unpacked successfully
        message shouldNotBe null
        message!!

        // Use soft assertions to check all fields
        assertSoftly {
            message.destinationHash.toHex() shouldBe destDestination.hash.toHex()
            println("  destination_hash: ${message.destinationHash.toHex()}")

            message.sourceHash.toHex() shouldBe sourceDestination.hash.toHex()
            println("  source_hash: ${message.sourceHash.toHex()}")

            message.timestamp shouldBe timestamp
            println("  timestamp: ${message.timestamp}")

            message.title shouldBe title
            println("  title: '${message.title}'")

            message.content shouldBe content
            println("  content: '${message.content}'")

            // Signature should be validated (source identity is remembered)
            message.signatureValidated shouldBe true
            println("  signatureValidated: ${message.signatureValidated}")
        }

        println("  SUCCESS: All fields preserved from Python to Kotlin")
    }

    @Test
    fun `empty message from Python unpacks correctly`() {
        println("\n=== Test: empty message from Python unpacks correctly ===")

        val timestamp = System.currentTimeMillis() / 1000.0

        val packedBytes = createMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            ed25519PrivateKey = testSourceIdentity.sigPrv,
            timestamp = timestamp,
            title = "",
            content = ""
        )

        // Unpack in Kotlin
        val unpackStart = System.currentTimeMillis()
        val message = LXMessage.unpackFromBytes(packedBytes)
        val unpackTime = System.currentTimeMillis() - unpackStart
        println("  [Kotlin] unpackFromBytes() completed in ${unpackTime}ms")

        message shouldNotBe null
        message!!

        assertSoftly {
            message.title shouldBe ""
            println("  title: '${message.title}' (empty)")

            message.content shouldBe ""
            println("  content: '${message.content}' (empty)")

            message.signatureValidated shouldBe true
        }

        println("  SUCCESS: Empty message from Python unpacks correctly")
    }

    @Test
    fun `unicode content from Python unpacks correctly`() {
        println("\n=== Test: unicode content from Python unpacks correctly ===")

        val timestamp = System.currentTimeMillis() / 1000.0
        val unicodeTitle = "Unicode Test / \u4E2D\u6587 / \u0420\u0443\u0441\u0441\u043A\u0438\u0439"
        val unicodeContent = "Hello, World! / Hej verden! / Hola mundo! / Ciao mondo! / \uD83D\uDE80\uD83C\uDF0D"

        val packedBytes = createMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            ed25519PrivateKey = testSourceIdentity.sigPrv,
            timestamp = timestamp,
            title = unicodeTitle,
            content = unicodeContent
        )

        // Unpack in Kotlin
        val unpackStart = System.currentTimeMillis()
        val message = LXMessage.unpackFromBytes(packedBytes)
        val unpackTime = System.currentTimeMillis() - unpackStart
        println("  [Kotlin] unpackFromBytes() completed in ${unpackTime}ms")

        message shouldNotBe null
        message!!

        assertSoftly {
            message.title shouldBe unicodeTitle
            println("  title: '${message.title}'")

            message.content shouldBe unicodeContent
            println("  content: '${message.content}'")

            message.signatureValidated shouldBe true
        }

        println("  SUCCESS: Unicode content from Python preserved correctly")
    }

    @Test
    fun `message with fields from Python unpacks correctly`() {
        println("\n=== Test: message with fields from Python unpacks correctly ===")

        val timestamp = System.currentTimeMillis() / 1000.0

        // Use FIELD_RENDERER with RENDERER_MARKDOWN value
        // JSON requires string keys, which lxmf_pack converts to int
        val fields = mapOf(
            LXMFConstants.FIELD_RENDERER.toString() to LXMFConstants.RENDERER_MARKDOWN
        )

        val packedBytes = createMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            ed25519PrivateKey = testSourceIdentity.sigPrv,
            timestamp = timestamp,
            title = "Field Test",
            content = "Markdown formatted content",
            fields = fields
        )

        // Unpack in Kotlin
        val unpackStart = System.currentTimeMillis()
        val message = LXMessage.unpackFromBytes(packedBytes)
        val unpackTime = System.currentTimeMillis() - unpackStart
        println("  [Kotlin] unpackFromBytes() completed in ${unpackTime}ms")

        message shouldNotBe null
        message!!

        println("  [Kotlin] fields: ${message.fields}")

        assertSoftly {
            // Fields should be populated
            message.fields.size shouldBe 1
            println("  fields count: ${message.fields.size}")

            // FIELD_RENDERER (15) should have value RENDERER_MARKDOWN (2)
            // Note: msgpack unpacks integers as Long
            val rendererValue = message.fields[LXMFConstants.FIELD_RENDERER]
            println("  FIELD_RENDERER (key=${LXMFConstants.FIELD_RENDERER}): $rendererValue")

            // Value could be Int or Long depending on msgpack deserialization
            when (rendererValue) {
                is Int -> rendererValue shouldBe LXMFConstants.RENDERER_MARKDOWN
                is Long -> rendererValue.toInt() shouldBe LXMFConstants.RENDERER_MARKDOWN
                else -> throw AssertionError("Expected Int or Long, got ${rendererValue?.javaClass}")
            }

            message.signatureValidated shouldBe true
        }

        println("  SUCCESS: Message with fields from Python unpacks correctly")
    }

    @Test
    fun `message hash computed identically in both implementations`() {
        println("\n=== Test: message hash computed identically ===")

        // Use fixed timestamp for reproducible hash
        val timestamp = 1700000000.123456

        // Create same message in Python
        val packResult = python(
            "lxmf_pack",
            "destination_hash" to destDestination.hash,
            "source_hash" to sourceDestination.hash,
            "timestamp" to timestamp,
            "title" to "Hash Test",
            "content" to "Testing hash computation"
        )
        val pythonHash = packResult.getString("message_hash")
        println("  [Python] message_hash: $pythonHash")

        // Create same message in Kotlin
        val kotlinMessage = createTestMessage(
            content = "Testing hash computation",
            title = "Hash Test"
        )
        kotlinMessage.timestamp = timestamp

        // Pack to compute hash
        val packStart = System.currentTimeMillis()
        kotlinMessage.pack()
        val packTime = System.currentTimeMillis() - packStart
        println("  [Kotlin] pack() completed in ${packTime}ms")

        val kotlinHash = kotlinMessage.hash?.toHex()
        println("  [Kotlin] message_hash: $kotlinHash")

        // Compare hashes
        assertSoftly {
            kotlinHash shouldNotBe null
            kotlinHash shouldBe pythonHash
        }

        println("  SUCCESS: Message hashes match between Kotlin and Python")
    }

    @Test
    fun `timestamp precision preserved from Python`() {
        println("\n=== Test: timestamp precision preserved from Python ===")

        // Use a timestamp with high precision
        val preciseTimestamp = 1700000000.123456789

        val packedBytes = createMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            ed25519PrivateKey = testSourceIdentity.sigPrv,
            timestamp = preciseTimestamp,
            title = "Timestamp Test",
            content = "Testing timestamp precision"
        )

        // Unpack in Kotlin
        val unpackStart = System.currentTimeMillis()
        val message = LXMessage.unpackFromBytes(packedBytes)
        val unpackTime = System.currentTimeMillis() - unpackStart
        println("  [Kotlin] unpackFromBytes() completed in ${unpackTime}ms")

        message shouldNotBe null
        message!!

        println("  [Python] timestamp: $preciseTimestamp")
        println("  [Kotlin] timestamp: ${message.timestamp}")

        // Float64 should preserve precision
        assertSoftly {
            message.timestamp shouldBe preciseTimestamp
            message.signatureValidated shouldBe true
        }

        println("  SUCCESS: Timestamp precision preserved (delta=${preciseTimestamp - message.timestamp!!})")
    }
}
