package network.reticulum.integration

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.PacketType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Link establishment and encrypted communication.
 */
@DisplayName("Link Integration Tests")
class LinkIntegrationTest {

    private val testPort = 5242 + (Math.random() * 1000).toInt()
    private lateinit var server: TCPServerInterface
    private lateinit var client: TCPClientInterface

    @BeforeEach
    fun setup() {
        Transport.stop()
    }

    @AfterEach
    fun teardown() {
        if (::client.isInitialized) {
            try { client.detach() } catch (_: Exception) {}
        }
        if (::server.isInitialized) {
            try { server.detach() } catch (_: Exception) {}
        }
        Transport.stop()
    }

    @Test
    @DisplayName("Link request packet can be created and parsed")
    @Timeout(5)
    fun `link request packet can be created and parsed`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("link", "test")
        )

        // Create a link (this will create a link request packet)
        Transport.start(enableTransport = false)

        // Just verify the link can be created without errors
        val link = Link.create(destination)

        assertNotNull(link)
        assertEquals(LinkConstants.PENDING, link.status)
        assertTrue(link.linkId.isNotEmpty())

        // Clean up
        link.teardown()
    }

    @Test
    @DisplayName("Link request can be validated")
    @Timeout(5)
    fun `link request can be validated`() {
        val receiverIdentity = Identity.create()
        val receiverDestination = Destination.create(
            identity = receiverIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("link", "receiver")
        )

        Transport.start(enableTransport = false)
        Transport.registerDestination(receiverDestination)

        // Create initiator and generate link request data
        val initiatorX25519 = network.reticulum.crypto.defaultCryptoProvider().generateX25519KeyPair()
        val initiatorEd25519 = network.reticulum.crypto.defaultCryptoProvider().generateEd25519KeyPair()

        val requestData = initiatorX25519.publicKey + initiatorEd25519.publicKey

        // Create a mock link request packet
        val packet = Packet.createRaw(
            destinationHash = receiverDestination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        // Validate the request
        val link = Link.validateRequest(receiverDestination, requestData, packet)

        assertNotNull(link, "Link should be created from valid request")
        assertTrue(link.linkId.isNotEmpty())
        assertEquals(LinkConstants.HANDSHAKE, link.status)
    }

    @Test
    @DisplayName("Link can encrypt and decrypt data")
    @Timeout(5)
    fun `link can encrypt and decrypt data`() {
        val receiverIdentity = Identity.create()
        val receiverDestination = Destination.create(
            identity = receiverIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("crypto", "test")
        )

        Transport.start(enableTransport = false)
        Transport.registerDestination(receiverDestination)

        // Create initiator keys
        val crypto = network.reticulum.crypto.defaultCryptoProvider()
        val initiatorX25519 = crypto.generateX25519KeyPair()
        val initiatorEd25519 = crypto.generateEd25519KeyPair()

        val requestData = initiatorX25519.publicKey + initiatorEd25519.publicKey

        // Create packet and validate
        val packet = Packet.createRaw(
            destinationHash = receiverDestination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        val link = Link.validateRequest(receiverDestination, requestData, packet)
        assertNotNull(link)

        // Test encryption/decryption
        val plaintext = "Hello, encrypted world!".toByteArray()
        val ciphertext = link.encrypt(plaintext)
        val decrypted = link.decrypt(ciphertext)

        assertNotNull(decrypted)
        assertTrue(plaintext.contentEquals(decrypted), "Decrypted data should match plaintext")
    }

    @Test
    @DisplayName("Link ID is computed correctly")
    @Timeout(5)
    fun `link id is computed correctly`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "linkid",
            aspects = arrayOf("test")
        )

        Transport.start(enableTransport = false)

        // Create link request packet
        val crypto = network.reticulum.crypto.defaultCryptoProvider()
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

        // Compute link ID
        val linkId = Link.linkIdFromLrPacket(packet)

        assertEquals(16, linkId.size, "Link ID should be 16 bytes (truncated hash)")

        // Compute again to verify determinism
        val linkId2 = Link.linkIdFromLrPacket(packet)
        assertTrue(linkId.contentEquals(linkId2), "Link ID computation should be deterministic")
    }

    @Test
    @DisplayName("Link signalling bytes encode MTU and mode")
    @Timeout(5)
    fun `link signalling bytes encode mtu and mode`() {
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
    @DisplayName("Link constants match Python reference")
    @Timeout(5)
    fun `link constants match python reference`() {
        // Verify critical constants
        assertEquals(64, LinkConstants.ECPUBSIZE, "ECPUBSIZE should be 64")
        assertEquals(32, LinkConstants.KEYSIZE, "KEYSIZE should be 32")
        assertEquals(3, LinkConstants.LINK_MTU_SIZE, "LINK_MTU_SIZE should be 3")

        assertEquals(0x00, LinkConstants.PENDING, "PENDING state should be 0x00")
        assertEquals(0x01, LinkConstants.HANDSHAKE, "HANDSHAKE state should be 0x01")
        assertEquals(0x02, LinkConstants.ACTIVE, "ACTIVE state should be 0x02")
        assertEquals(0x03, LinkConstants.STALE, "STALE state should be 0x03")
        assertEquals(0x04, LinkConstants.CLOSED, "CLOSED state should be 0x04")

        assertEquals(0x01, LinkConstants.MODE_AES256_CBC, "MODE_AES256_CBC should be 0x01")
    }
}
