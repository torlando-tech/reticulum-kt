package network.reticulum.lxmf.interop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for Python packing LXMF messages that can be received by Kotlin via DIRECT delivery.
 *
 * These tests verify that Python-packed LXMF messages have the correct format
 * for Kotlin to unpack and process, which is the core requirement for E2E-02.
 *
 * Note: Full live TCP delivery tests are pending resolution of TCP interface
 * compatibility issues between Kotlin and Python Reticulum implementations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonToKotlinDirectTest : InteropTestBase() {

    private val destIdentity: Identity = Identity.create()
    private val destDestination: Destination = Destination.create(
        identity = destIdentity,
        direction = DestinationDirection.IN,
        type = DestinationType.SINGLE,
        appName = "lxmf",
        "delivery"
    )

    private val sourceIdentity: Identity = Identity.create()
    private val sourceDestination: Destination = Destination.create(
        identity = sourceIdentity,
        direction = DestinationDirection.OUT,
        type = DestinationType.SINGLE,
        appName = "lxmf",
        "delivery"
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Pack a message using Python's LXMF logic.
     * Returns the packed bytes and message hash.
     */
    private fun packMessageInPython(
        destinationHash: ByteArray,
        sourceHash: ByteArray,
        content: String,
        title: String = "",
        fields: Map<Int, Any> = emptyMap()
    ): Pair<ByteArray, ByteArray> {
        val params = mutableMapOf<String, Any>(
            "destination_hash" to destinationHash.toHex(),
            "source_hash" to sourceHash.toHex(),
            "content" to content,
            "title" to title,
            "timestamp" to (System.currentTimeMillis() / 1000.0)
        )
        if (fields.isNotEmpty()) {
            params["fields"] = fields.mapKeys { it.key.toString() }
        }

        // Get the packed components
        val packResult = python("lxmf_pack", *params.map { it.key to it.value }.toTypedArray())

        val packedPayload = packResult.getBytes("packed_payload")
        val signedPart = packResult.getBytes("signed_part")
        val messageHash = packResult.getBytes("message_hash")

        // Sign the message using Python
        val signResult = python(
            "identity_sign",
            "private_key" to sourceIdentity.getPrivateKey().toHex(),
            "message" to signedPart.toHex()
        )
        val signature = signResult.getBytes("signature")

        // Construct full packed message: dest_hash + source_hash + signature + packed_payload
        val packed = destinationHash + sourceHash + signature + packedPayload

        return Pair(packed, messageHash)
    }

    /**
     * Register source identity so signature validation can work.
     */
    private fun registerSourceIdentity() {
        Identity.remember(
            packetHash = sourceDestination.hash,
            destHash = sourceDestination.hash,
            publicKey = sourceIdentity.getPublicKey(),
            appData = null
        )
    }

    @Test
    fun `Python packed message can be unpacked by Kotlin`() {
        registerSourceIdentity()

        // Pack a message in Python
        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Hello from Python!",
            title = "Test Message"
        )

        packed.size shouldNotBe 0

        // Unpack in Kotlin
        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)
        message shouldNotBe null

        // Verify content
        message!!.content shouldBe "Hello from Python!"
        message.title shouldBe "Test Message"
    }

    @Test
    fun `message source hash correctly identifies Python sender`() {
        registerSourceIdentity()

        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Source identification test"
        )

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null
        message!!.sourceHash.toHex() shouldBe sourceDestination.hash.toHex()
    }

    @Test
    fun `message with title is correctly preserved through pack and unpack`() {
        registerSourceIdentity()

        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Content with title",
            title = "Important Title"
        )

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null
        message!!.title shouldBe "Important Title"
        message.content shouldBe "Content with title"
    }

    @Test
    fun `multiple messages have unique hashes`() {
        registerSourceIdentity()

        val (packed1, hash1) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Message 1"
        )

        // Small delay to ensure different timestamps
        Thread.sleep(10)

        val (packed2, hash2) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Message 2"
        )

        val message1 = LXMessage.unpackFromBytes(packed1, DeliveryMethod.DIRECT)
        val message2 = LXMessage.unpackFromBytes(packed2, DeliveryMethod.DIRECT)

        message1 shouldNotBe null
        message2 shouldNotBe null

        message1!!.content shouldBe "Message 1"
        message2!!.content shouldBe "Message 2"

        // Message hashes should be different
        hash1.toHex() shouldNotBe hash2.toHex()
    }

    @Test
    fun `message hash computed correctly by Kotlin`() {
        registerSourceIdentity()

        val (packed, pythonHash) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Hash computation test"
        )

        pythonHash.size shouldBe 32 // 32 bytes

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null

        // Verify Kotlin's hash matches Python's
        message!!.hash?.toHex() shouldBe pythonHash.toHex()
    }

    @Test
    fun `message with empty content unpacks correctly`() {
        registerSourceIdentity()

        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "",
            title = "Empty Content Message"
        )

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null
        message!!.content shouldBe ""
        message.title shouldBe "Empty Content Message"
    }

    @Test
    fun `message with empty title unpacks correctly`() {
        registerSourceIdentity()

        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Content with no title",
            title = ""
        )

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null
        message!!.content shouldBe "Content with no title"
        message.title shouldBe ""
    }

    @Test
    fun `signature validates on received message`() {
        registerSourceIdentity()

        val (packed, _) = packMessageInPython(
            destinationHash = destDestination.hash,
            sourceHash = sourceDestination.hash,
            content = "Signature validation test"
        )

        val message = LXMessage.unpackFromBytes(packed, DeliveryMethod.DIRECT)

        message shouldNotBe null

        // Signature should be validated during unpack (since we registered the source identity)
        message!!.signatureValidated shouldBe true
    }
}
