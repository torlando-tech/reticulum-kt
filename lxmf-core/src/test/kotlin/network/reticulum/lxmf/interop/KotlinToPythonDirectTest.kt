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
 * Tests for Kotlin packing LXMF messages that can be received by Python via DIRECT delivery.
 *
 * These tests verify that Kotlin-packed LXMF messages have the correct format
 * for Python to unpack and process, which is the core requirement for E2E-01.
 *
 * Note: Full live TCP delivery tests are pending resolution of TCP interface
 * compatibility issues between Kotlin and Python Reticulum implementations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinToPythonDirectTest : InteropTestBase() {

    private val sourceIdentity: Identity = Identity.create()
    private val sourceDestination: Destination = Destination.create(
        identity = sourceIdentity,
        direction = DestinationDirection.IN,
        type = DestinationType.SINGLE,
        appName = "lxmf",
        "delivery"
    )

    private val destIdentity: Identity = Identity.create()
    private val destDestination: Destination = Destination.create(
        identity = destIdentity,
        direction = DestinationDirection.OUT,
        type = DestinationType.SINGLE,
        appName = "lxmf",
        "delivery"
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `Kotlin packed message can be unpacked by Python`() {
        // Create and pack a message in Kotlin
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Hello from Kotlin!",
            title = "Test Message",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()
        packed.size shouldNotBe 0

        // Have Python unpack it
        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        // Verify Python successfully unpacked the message
        result.getString("content") shouldBe "Hello from Kotlin!"
        result.getString("title") shouldBe "Test Message"

        // Verify destination hash matches
        result.getBytes("destination_hash").toHex() shouldBe destDestination.hash.toHex()

        // Verify source hash matches
        result.getBytes("source_hash").toHex() shouldBe sourceDestination.hash.toHex()
    }

    @Test
    fun `message source hash correctly identifies Kotlin sender`() {
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Source identification test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        // Source hash in unpacked message should match Kotlin's LXMF delivery destination hash
        result.getBytes("source_hash").toHex() shouldBe sourceDestination.hash.toHex()
    }

    @Test
    fun `message with title is correctly preserved through pack and unpack`() {
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Content with title",
            title = "Important Title",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        result.getString("title") shouldBe "Important Title"
        result.getString("content") shouldBe "Content with title"
    }

    @Test
    fun `multiple messages have unique hashes`() {
        val message1 = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Message 1",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val message2 = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Message 2",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed1 = message1.pack()
        val packed2 = message2.pack()

        // Unpack both with Python to verify
        val result1 = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed1.toHex())
        val result2 = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed2.toHex())

        // Messages should have different content
        result1.getString("content") shouldBe "Message 1"
        result2.getString("content") shouldBe "Message 2"

        // Message hashes should be different
        result1.getString("message_hash") shouldNotBe result2.getString("message_hash")
    }

    @Test
    fun `message hash computed correctly by Python`() {
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Hash computation test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        // Python computes message hash as SHA256(dest_hash + source_hash + packed_payload)
        // Verify it returns a valid 32-byte hash as hex
        val pythonHash = result.getString("message_hash")
        pythonHash.length shouldBe 64 // 32 bytes as hex

        // Verify Kotlin's hash matches Python's
        message.hash?.toHex() shouldBe pythonHash
    }

    @Test
    fun `message with empty content packs correctly`() {
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "",
            title = "Empty Content Message",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        result.getString("content") shouldBe ""
        result.getString("title") shouldBe "Empty Content Message"
    }

    @Test
    fun `message with empty title packs correctly`() {
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Content with no title",
            title = "",
            desiredMethod = DeliveryMethod.DIRECT
        )

        val packed = message.pack()

        val result = python("lxmf_unpack_with_fields", "lxmf_bytes" to packed.toHex())

        result.getString("content") shouldBe "Content with no title"
        result.getString("title") shouldBe ""
    }
}
