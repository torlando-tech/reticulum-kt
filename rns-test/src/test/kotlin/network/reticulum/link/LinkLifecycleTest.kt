package network.reticulum.link

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.PacketType
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Link lifecycle: creation, validation, and state transitions.
 *
 * Note: Full end-to-end link establishment requires two separate Transport
 * instances, which is not possible with the current singleton Transport design.
 * These tests focus on testable components: link creation, validation, and
 * local state management.
 */
@DisplayName("Link Lifecycle Tests")
class LinkLifecycleTest {

    @BeforeEach
    fun setup() {
        Transport.stop()
        Thread.sleep(100)
        Transport.start(enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        Transport.stop()
        Thread.sleep(100)
    }

    @Test
    @DisplayName("Link creation starts in PENDING state")
    @Timeout(5)
    fun `link creation starts in PENDING state`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("test", "pending")
        )

        val link = Link.create(destination)

        assertEquals(LinkConstants.PENDING, link.status,
            "New link should start in PENDING state")
        assertTrue(link.initiator, "Created link should be initiator")
        assertTrue(link.linkId.isNotEmpty(), "Link should have a link ID")
        assertEquals(16, link.linkId.size, "Link ID should be 16 bytes")
    }

    @Test
    @DisplayName("Link request validation creates HANDSHAKE link")
    @Timeout(5)
    fun `link request validation creates HANDSHAKE link`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("test", "validate")
        )
        Transport.registerDestination(destination)

        // Create initiator keys
        val crypto = defaultCryptoProvider()
        val initiatorX25519 = crypto.generateX25519KeyPair()
        val initiatorEd25519 = crypto.generateEd25519KeyPair()

        val requestData = initiatorX25519.publicKey + initiatorEd25519.publicKey

        // Create link request packet
        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        // Validate the request
        val link = Link.validateRequest(destination, requestData, packet)

        assertNotNull(link, "validateRequest should create a link")
        assertEquals(LinkConstants.HANDSHAKE, link.status,
            "Validated link should be in HANDSHAKE state")
        assertTrue(!link.initiator, "Validated link should not be initiator")
    }

    @Test
    @DisplayName("Link ID is computed deterministically")
    @Timeout(5)
    fun `link id is computed deterministically`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("test", "linkid")
        )

        val crypto = defaultCryptoProvider()
        val x25519 = crypto.generateX25519KeyPair()
        val ed25519 = crypto.generateEd25519KeyPair()

        val requestData = x25519.publicKey + ed25519.publicKey

        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        // Compute link ID twice
        val linkId1 = Link.linkIdFromLrPacket(packet)
        val linkId2 = Link.linkIdFromLrPacket(packet)

        assertEquals(16, linkId1.size, "Link ID should be 16 bytes")
        assertTrue(linkId1.contentEquals(linkId2),
            "Link ID computation should be deterministic")
    }

    @Test
    @DisplayName("Link correctly identifies initiator role")
    @Timeout(5)
    fun `link correctly identifies initiator role`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("test", "role")
        )
        Transport.registerDestination(destination)

        // Create initiator link
        val initiatorLink = Link.create(destination)
        assertTrue(initiatorLink.initiator, "Created link should be initiator")

        // Create responder link via validation
        val crypto = defaultCryptoProvider()
        val x25519 = crypto.generateX25519KeyPair()
        val ed25519 = crypto.generateEd25519KeyPair()
        val requestData = x25519.publicKey + ed25519.publicKey

        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        val responderLink = Link.validateRequest(destination, requestData, packet)
        assertNotNull(responderLink)
        assertTrue(!responderLink.initiator, "Validated link should not be initiator")
    }

    @Test
    @DisplayName("Destination link-established callback does NOT fire during LINKREQUEST handling")
    @Timeout(5)
    fun `destination link established callback does not fire during linkrequest`() {
        // Regression test: Transport's LINKREQUEST handler previously invoked
        // destination.invokeLinkEstablished() immediately after Link.validateRequest()
        // succeeded — while link.status was still HANDSHAKE. Any link.send() from
        // inside that callback would then silently fail because sendWithReceipt
        // rejects non-ACTIVE links. Python RNS only fires the owner callback from
        // rtt_packet() after status = ACTIVE (RNS/Link.py line 550–551), and the
        // Kotlin port now matches by invoking from Link.rttPacket().
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("test", "callback-timing")
        )
        destination.acceptLinkRequests = true
        Transport.registerDestination(destination)

        val callbackInvocations = AtomicInteger(0)
        val statusAtCallback = AtomicReference<Int?>(null)
        destination.setLinkEstablishedCallback { link ->
            callbackInvocations.incrementAndGet()
            if (link is Link) {
                statusAtCallback.set(link.status)
            }
        }

        val crypto = defaultCryptoProvider()
        val initiatorX25519 = crypto.generateX25519KeyPair()
        val initiatorEd25519 = crypto.generateEd25519KeyPair()
        val requestData = initiatorX25519.publicKey + initiatorEd25519.publicKey

        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        val link = Link.validateRequest(destination, requestData, packet)
        assertNotNull(link, "validateRequest should create a link")
        assertEquals(
            LinkConstants.HANDSHAKE, link.status,
            "Link should be in HANDSHAKE state immediately after validateRequest"
        )

        // Give any daemon-thread callback a chance to race; it should never fire
        // because nothing has transitioned the link to ACTIVE yet.
        Thread.sleep(100)

        assertEquals(
            0, callbackInvocations.get(),
            "Destination link-established callback must not fire while link is in HANDSHAKE"
        )
        assertNull(
            statusAtCallback.get(),
            "No callback should have observed a status yet"
        )
    }
}
