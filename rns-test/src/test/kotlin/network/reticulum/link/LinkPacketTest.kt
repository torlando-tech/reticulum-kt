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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for packet handling over links.
 *
 * Note: Full end-to-end packet exchange testing requires two separate Transport
 * instances for complete link establishment. These tests verify local packet
 * operations and encryption. Full end-to-end tests should use Python interop.
 */
@DisplayName("Link Packet Tests")
class LinkPacketTest {

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
    @DisplayName("Link can encrypt and decrypt data")
    @Timeout(5)
    fun `link can encrypt and decrypt data`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "packets",
            aspects = arrayOf("test", "crypto")
        )
        Transport.registerDestination(destination)

        // Create a responder link via validateRequest
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
        assertNotNull(link)

        // Test encryption/decryption
        val plaintext = "Hello, encrypted world!".toByteArray()
        val ciphertext = link.encrypt(plaintext)
        val decrypted = link.decrypt(ciphertext)

        assertNotNull(decrypted)
        assertTrue(plaintext.contentEquals(decrypted),
            "Decrypted data should match plaintext")
    }

    @Test
    @DisplayName("Link encryption mode is AES-256-CBC")
    @Timeout(5)
    fun `link encryption mode is AES-256-CBC`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "packets",
            aspects = arrayOf("test", "mode")
        )

        val link = Link.create(destination)

        assertEquals(LinkConstants.MODE_AES256_CBC, link.mode,
            "Link should use AES-256-CBC mode")
    }

    @Test
    @DisplayName("Link MDU is calculated correctly")
    @Timeout(5)
    fun `link MDU is calculated correctly`() {
        // MDU should be calculated based on MTU minus encryption overhead
        val mdu = LinkConstants.MDU

        assertTrue(mdu > 0, "MDU should be positive")
        assertTrue(mdu < 500, "MDU should be less than 500 bytes")

        // MDU is typically around 383 bytes for standard MTU
        assertTrue(mdu > 350, "MDU should be greater than 350 bytes")
    }

    @Test
    @DisplayName("Signalling bytes encode MTU and mode correctly")
    @Timeout(5)
    fun `signalling bytes encode MTU and mode correctly`() {
        val mtu = 500
        val mode = LinkConstants.MODE_AES256_CBC

        val signallingBytes = Link.signallingBytes(mtu, mode)

        assertEquals(3, signallingBytes.size, "Signalling bytes should be 3 bytes")

        // Decode and verify
        val decodedMtu = ((signallingBytes[0].toInt() and 0xFF) shl 16) or
                         ((signallingBytes[1].toInt() and 0xFF) shl 8) or
                         (signallingBytes[2].toInt() and 0xFF)
        val maskedMtu = decodedMtu and LinkConstants.MTU_BYTEMASK
        val decodedMode = (signallingBytes[0].toInt() and LinkConstants.MODE_BYTEMASK) shr 5

        assertEquals(mtu, maskedMtu, "Decoded MTU should match")
        assertEquals(mode, decodedMode, "Decoded mode should match")
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("Packet sent on link is received by peer")
    @Timeout(10)
    fun `packet sent on link is received by peer`() {
        // This test requires full end-to-end link establishment.
        // See LinkInteropTest for end-to-end testing.
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("Bidirectional packet exchange works")
    @Timeout(10)
    fun `bidirectional packet exchange works`() {
        // This test requires full end-to-end link establishment.
        // See LinkInteropTest for end-to-end testing.
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("Multiple packets delivered in order")
    @Timeout(15)
    fun `multiple packets delivered in order`() {
        // This test requires full end-to-end link establishment.
        // See LinkInteropTest for end-to-end testing.
    }
}
