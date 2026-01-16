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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Link error handling and edge cases.
 *
 * Note: Tests that require full link establishment are not possible with
 * the singleton Transport design. These tests focus on error handling
 * during link creation and validation.
 */
@DisplayName("Link Error Handling Tests")
class LinkErrorTest {

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
    @DisplayName("Link request with invalid payload size is rejected")
    @Timeout(5)
    fun `link request with invalid payload size is rejected`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "error",
            aspects = arrayOf("test", "size")
        )
        Transport.registerDestination(destination)

        // Create request data with wrong size (should be 64 or 67 bytes)
        val invalidData = ByteArray(32) { 0x42 }  // Wrong size

        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = invalidData,
            packetType = PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE
        )
        packet.pack()

        // validateRequest should reject invalid payload
        val link = Link.validateRequest(destination, invalidData, packet)

        assertNull(link, "validateRequest should return null for invalid payload size")
    }

    @Test
    @DisplayName("Link teardown sets CLOSED state")
    @Timeout(5)
    fun `link teardown sets CLOSED state`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "error",
            aspects = arrayOf("test", "teardown")
        )

        val link = Link.create(destination)
        assertEquals(LinkConstants.PENDING, link.status)

        // Teardown the link
        link.teardown()

        assertEquals(LinkConstants.CLOSED, link.status,
            "Link should be CLOSED after teardown")
    }

    @Test
    @DisplayName("Double teardown does not throw")
    @Timeout(5)
    fun `double teardown does not throw`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "error",
            aspects = arrayOf("test", "double")
        )

        val link = Link.create(destination)

        // First teardown
        link.teardown()
        assertEquals(LinkConstants.CLOSED, link.status)

        // Second teardown should not throw
        var exceptionThrown = false
        try {
            link.teardown()
        } catch (e: Exception) {
            exceptionThrown = true
        }

        assertFalse(exceptionThrown,
            "Double teardown should not throw an exception")
        assertEquals(LinkConstants.CLOSED, link.status)
    }

    @Test
    @DisplayName("Send on non-active link returns false")
    @Timeout(5)
    fun `send on non-active link returns false`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "error",
            aspects = arrayOf("test", "send")
        )

        val link = Link.create(destination)
        assertEquals(LinkConstants.PENDING, link.status)

        // Try to send on PENDING link
        val result = link.send("test data".toByteArray())

        assertFalse(result, "send should return false on non-active link")
    }

    @Test
    @DisplayName("Teardown reason is set correctly for initiator close")
    @Timeout(5)
    fun `teardown reason is set correctly for initiator close`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "error",
            aspects = arrayOf("test", "reason")
        )

        val link = Link.create(destination)
        assertTrue(link.initiator)

        // Teardown from initiator
        link.teardown()

        assertEquals(LinkConstants.INITIATOR_CLOSED, link.teardownReason,
            "Teardown reason should be INITIATOR_CLOSED for initiator")
    }
}
