package network.reticulum.announce

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for announce creation and validation.
 */
@DisplayName("Announce Validation")
class AnnounceValidationTest {

    @Test
    @DisplayName("Create and validate announce without ratchet")
    fun `create and validate announce without ratchet`() {
        // Create identity and destination
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "app"
        )

        // Create announce packet
        val appData = "Hello, world!".toByteArray()
        val announcePacket = destination.announce(appData = appData, send = false)

        announcePacket shouldNotBe null

        // Validate the announce
        val validatedIdentity = Identity.validateAnnounce(announcePacket!!)

        validatedIdentity shouldNotBe null
        validatedIdentity!!.hash.contentEquals(identity.hash) shouldBe true

        // Verify the identity was stored
        Identity.isKnown(destination.hash) shouldBe true

        // Recall the identity
        val recalledIdentity = Identity.recall(destination.hash)
        recalledIdentity shouldNotBe null
        recalledIdentity!!.hash.contentEquals(identity.hash) shouldBe true

        // Recall app data
        val recalledAppData = Identity.recallAppData(destination.hash)
        recalledAppData shouldNotBe null
        recalledAppData!!.contentEquals(appData) shouldBe true
    }

    @Test
    @DisplayName("Create and validate announce with ratchet")
    fun `create and validate announce with ratchet`() {
        // Create identity and destination
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "ratchet"
        )

        // Enable ratchets with a non-existent temp file path (this sets ratchetsEnabled=true and auto-generates ratchets)
        val tempDir = System.getProperty("java.io.tmpdir")
        val tempRatchetPath = "$tempDir/ratchet_test_${System.nanoTime()}.dat"
        destination.enableRatchets(tempRatchetPath)

        // Create announce packet (will include the auto-generated ratchet)
        val announcePacket = destination.announce(send = false)

        announcePacket shouldNotBe null

        // Validate the announce
        val validatedIdentity = Identity.validateAnnounce(announcePacket!!)

        validatedIdentity shouldNotBe null
        validatedIdentity!!.hash.contentEquals(identity.hash) shouldBe true

        // Verify ratchet was stored
        val storedRatchet = Destination.getRatchetForDestination(destination.hash)
        storedRatchet shouldNotBe null
    }

    @Test
    @DisplayName("Signature-only validation works")
    fun `signature only validation works`() {
        // Create identity and destination
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "sigonly"
        )

        // Create announce packet
        val announcePacket = destination.announce(send = false)

        announcePacket shouldNotBe null

        // Validate signature only (should not store)
        val validatedIdentity = Identity.validateAnnounce(announcePacket!!, onlyValidateSignature = true)

        validatedIdentity shouldNotBe null

        // Verify the identity was NOT stored
        Identity.isKnown(destination.hash) shouldBe false
    }

    @Test
    @DisplayName("Invalid signature is rejected")
    fun `invalid signature is rejected`() {
        // Create identity and destination
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "invalid"
        )

        // Create announce packet
        val announcePacket = destination.announce(send = false)

        announcePacket shouldNotBe null

        // Corrupt the signature (last 64 bytes of the data)
        val corruptedData = announcePacket!!.data.copyOf()
        corruptedData[corruptedData.size - 1] = (corruptedData[corruptedData.size - 1].toInt() xor 0xFF).toByte()

        // Create a new packet with corrupted data
        val corruptedPacket = network.reticulum.packet.Packet.createRaw(
            destinationHash = announcePacket.destinationHash,
            data = corruptedData,
            packetType = network.reticulum.common.PacketType.ANNOUNCE,
            destinationType = destination.type,
            contextFlag = announcePacket.contextFlag
        )

        // Validation should fail
        val validatedIdentity = Identity.validateAnnounce(corruptedPacket)

        validatedIdentity shouldBe null
    }
}
