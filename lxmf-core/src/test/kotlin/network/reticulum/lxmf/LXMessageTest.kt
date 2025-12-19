package network.reticulum.lxmf

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for LXMessage pack/unpack functionality.
 */
class LXMessageTest {

    @Test
    fun `test message constants match Python values`() {
        // Verify critical constants match Python implementation
        assertEquals(16, LXMFConstants.DESTINATION_LENGTH)
        assertEquals(64, LXMFConstants.SIGNATURE_LENGTH)
        assertEquals(112, LXMFConstants.LXMF_OVERHEAD)
        assertEquals(295, LXMFConstants.ENCRYPTED_PACKET_MAX_CONTENT)
        assertEquals(319, LXMFConstants.LINK_PACKET_MAX_CONTENT)

        // Field identifiers
        assertEquals(0x01, LXMFConstants.FIELD_EMBEDDED_LXMS)
        assertEquals(0x05, LXMFConstants.FIELD_FILE_ATTACHMENTS)
        assertEquals(0x07, LXMFConstants.FIELD_AUDIO)

        // Message states
        assertEquals(0x00, LXMFConstants.STATE_GENERATING)
        assertEquals(0x08, LXMFConstants.STATE_DELIVERED)
        assertEquals(0xFF, LXMFConstants.STATE_FAILED)

        // Delivery methods
        assertEquals(0x01, LXMFConstants.METHOD_OPPORTUNISTIC)
        assertEquals(0x02, LXMFConstants.METHOD_DIRECT)
        assertEquals(0x03, LXMFConstants.METHOD_PROPAGATED)
    }

    @Test
    fun `test message state enum`() {
        assertEquals(MessageState.GENERATING, MessageState.fromValue(0x00))
        assertEquals(MessageState.DELIVERED, MessageState.fromValue(0x08))
        assertEquals(MessageState.FAILED, MessageState.fromValue(0xFF))
    }

    @Test
    fun `test delivery method enum`() {
        assertEquals(DeliveryMethod.OPPORTUNISTIC, DeliveryMethod.fromValue(0x01))
        assertEquals(DeliveryMethod.DIRECT, DeliveryMethod.fromValue(0x02))
        assertEquals(DeliveryMethod.PROPAGATED, DeliveryMethod.fromValue(0x03))
    }

    /**
     * Helper function to remember a source identity for validation.
     * Uses the proper Identity.remember() signature.
     */
    private fun rememberSourceIdentity(sourceIdentity: Identity, sourceDestination: Destination) {
        // Generate a dummy packet hash for testing
        val dummyPacketHash = ByteArray(32) { it.toByte() }
        Identity.remember(
            packetHash = dummyPacketHash,
            destHash = sourceDestination.hash,
            publicKey = sourceIdentity.getPublicKey()
        )
    }

    @Test
    fun `test message pack and unpack roundtrip`() {
        // Create identities for source and destination
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        // Create destinations
        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Store source identity so unpack can validate signature
        rememberSourceIdentity(sourceIdentity, sourceDestination)

        // Create message
        val originalMessage = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Hello, World!",
            title = "Test Message",
            fields = mutableMapOf(
                LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_PLAIN
            ),
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Pack the message
        val packed = originalMessage.pack()
        assertNotNull(packed)
        assertTrue(packed.size >= LXMFConstants.LXMF_OVERHEAD)

        // Verify hash was computed
        assertNotNull(originalMessage.hash)
        assertEquals(32, originalMessage.hash!!.size)

        // Verify signature was computed
        assertNotNull(originalMessage.signature)
        assertEquals(64, originalMessage.signature!!.size)

        // Unpack the message
        val unpacked = LXMessage.unpackFromBytes(packed)
        assertNotNull(unpacked)

        // Verify unpacked message matches original
        assertEquals(originalMessage.title, unpacked.title)
        assertEquals(originalMessage.content, unpacked.content)
        assertNotNull(unpacked.timestamp)

        // Verify hash matches
        assertTrue(originalMessage.hash!!.contentEquals(unpacked.hash!!))

        // Verify signature was validated (source identity was remembered)
        assertTrue(unpacked.signatureValidated)

        // Verify destination hash matches
        assertTrue(destDestination.hash.contentEquals(unpacked.destinationHash))

        // Verify source hash matches
        assertTrue(sourceDestination.hash.contentEquals(unpacked.sourceHash))

        // Verify fields
        assertEquals(1, unpacked.fields.size)
        assertEquals(LXMFConstants.RENDERER_PLAIN.toLong(), unpacked.fields[LXMFConstants.FIELD_RENDERER])
    }

    @Test
    fun `test message with empty content`() {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        rememberSourceIdentity(sourceIdentity, sourceDestination)

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "",
            title = ""
        )

        val packed = message.pack()
        val unpacked = LXMessage.unpackFromBytes(packed)

        assertNotNull(unpacked)
        assertEquals("", unpacked.title)
        assertEquals("", unpacked.content)
        assertTrue(unpacked.signatureValidated)
    }

    @Test
    fun `test message with unicode content`() {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        rememberSourceIdentity(sourceIdentity, sourceDestination)

        val unicodeContent = "Hello 你好 مرحبا 🚀 émojis"
        val unicodeTitle = "测试 Test تست"

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = unicodeContent,
            title = unicodeTitle
        )

        val packed = message.pack()
        val unpacked = LXMessage.unpackFromBytes(packed)

        assertNotNull(unpacked)
        assertEquals(unicodeTitle, unpacked.title)
        assertEquals(unicodeContent, unpacked.content)
        assertTrue(unpacked.signatureValidated)
    }

    @Test
    fun `test message with multiple fields`() {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        rememberSourceIdentity(sourceIdentity, sourceDestination)

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test content",
            title = "Test",
            fields = mutableMapOf(
                LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_MARKDOWN,
                LXMFConstants.FIELD_THREAD to "thread-id-123".toByteArray(),
                LXMFConstants.FIELD_DEBUG to 42
            )
        )

        val packed = message.pack()
        val unpacked = LXMessage.unpackFromBytes(packed)

        assertNotNull(unpacked)
        assertEquals(3, unpacked.fields.size)
        assertTrue(LXMFConstants.FIELD_RENDERER in unpacked.fields)
        assertTrue(LXMFConstants.FIELD_THREAD in unpacked.fields)
        assertTrue(LXMFConstants.FIELD_DEBUG in unpacked.fields)
    }

    @Test
    fun `test signature validation fails with wrong source`() {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()
        val wrongIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Remember WRONG identity for source hash
        val dummyPacketHash = ByteArray(32) { it.toByte() }
        Identity.remember(
            packetHash = dummyPacketHash,
            destHash = sourceDestination.hash,
            publicKey = wrongIdentity.getPublicKey()  // Wrong identity's public key!
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test"
        )

        val packed = message.pack()
        val unpacked = LXMessage.unpackFromBytes(packed)

        assertNotNull(unpacked)
        // Signature should fail because wrong identity was remembered
        assertTrue(!unpacked.signatureValidated)
        assertEquals(UnverifiedReason.SIGNATURE_INVALID, unpacked.unverifiedReason)
    }

    @Test
    fun `test delivery method selection`() {
        val sourceIdentity = Identity.create()
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = sourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Small message should fit in PACKET representation
        val smallMessage = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Small",
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )
        smallMessage.pack()
        assertEquals(MessageRepresentation.PACKET, smallMessage.representation)

        // Large message should use RESOURCE representation
        val largeContent = "X".repeat(500)  // > LINK_PACKET_MAX_CONTENT
        val largeMessage = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = largeContent,
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )
        largeMessage.pack()
        assertEquals(MessageRepresentation.RESOURCE, largeMessage.representation)
    }
}
