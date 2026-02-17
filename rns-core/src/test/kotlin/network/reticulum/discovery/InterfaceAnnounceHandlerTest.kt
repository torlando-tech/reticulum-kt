package network.reticulum.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

@DisplayName("InterfaceAnnounceHandler")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterfaceAnnounceHandlerTest {

    private lateinit var testIdentity: Identity
    private lateinit var discoveryDestHash: ByteArray

    @BeforeAll
    fun setup() {
        testIdentity = Identity.create()

        // Compute the discovery destination hash for this identity
        discoveryDestHash = Destination.computeHash(
            DiscoveryConstants.APP_NAME,
            listOf("discovery", "interface"),
            testIdentity.hash
        )
    }

    private fun buildDiscoveryPayload(
        info: Map<Int, Any?>,
        stampCost: Int = 8,
        flags: Byte = 0x00
    ): ByteArray {
        // Pack info dict
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)
        packer.packMapHeader(info.size)
        for ((key, value) in info) {
            packer.packInt(key)
            when (value) {
                null -> packer.packNil()
                is Boolean -> packer.packBoolean(value)
                is Int -> packer.packInt(value)
                is Long -> packer.packLong(value)
                is Double -> packer.packDouble(value)
                is String -> packer.packString(value)
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                else -> packer.packString(value.toString())
            }
        }
        packer.flush()
        val packed = out.toByteArray()

        // Generate stamp
        val infohash = Hashes.fullHash(packed)
        val workblock = Stamper.generateWorkblock(infohash, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
        val result = runBlocking { Stamper.generateStamp(workblock, stampCost) }
        val stamp = result.stamp!!

        return byteArrayOf(flags) + packed + stamp
    }

    @Test
    @DisplayName("Self-filtering rejects non-discovery announces")
    fun `rejects non-discovery announces`() {
        var callbackInvoked = false
        val handler = InterfaceAnnounceHandler(
            requiredValue = 8,
            callback = { callbackInvoked = true }
        )

        // Use a random destination hash that doesn't match discovery
        val randomHash = ByteArray(16) { 0xDE.toByte() }
        val result = handler.handleAnnounce(randomHash, testIdentity, ByteArray(100))

        result shouldBe false
        callbackInvoked shouldBe false
    }

    @Test
    @DisplayName("Parses valid discovery announce")
    fun `parses valid discovery announce`() {
        var discovered: DiscoveredInterface? = null
        val handler = InterfaceAnnounceHandler(
            requiredValue = 8,
            callback = { discovered = it }
        )

        // Ensure Transport has an identity for hopsTo()
        try {
            Transport.start(Identity.create(), enableTransport = false)
        } catch (_: Exception) { /* may already be started */ }

        val transportId = ByteArray(16) { (it + 1).toByte() }
        val info = mapOf<Int, Any?>(
            DiscoveryConstants.INTERFACE_TYPE to "TCPServerInterface",
            DiscoveryConstants.TRANSPORT to true,
            DiscoveryConstants.TRANSPORT_ID to transportId,
            DiscoveryConstants.NAME to "Test TCP Server",
            DiscoveryConstants.LATITUDE to null,
            DiscoveryConstants.LONGITUDE to null,
            DiscoveryConstants.HEIGHT to null,
            DiscoveryConstants.REACHABLE_ON to "192.168.1.100",
            DiscoveryConstants.PORT to 4242,
        )

        val payload = buildDiscoveryPayload(info, stampCost = 8)
        val result = handler.handleAnnounce(discoveryDestHash, testIdentity, payload)

        result shouldBe true
        discovered shouldNotBe null
        discovered!!.type shouldBe "TCPServerInterface"
        discovered!!.name shouldBe "Test TCP Server"
        discovered!!.transport shouldBe true
        discovered!!.transportId shouldBe transportId.toHexString()
        discovered!!.reachableOn shouldBe "192.168.1.100"
        discovered!!.port shouldBe 4242
    }

    @Test
    @DisplayName("Rejects payload with invalid stamp")
    fun `rejects invalid stamp`() {
        var callbackInvoked = false
        val handler = InterfaceAnnounceHandler(
            requiredValue = 8,
            callback = { callbackInvoked = true }
        )

        val info = mapOf<Int, Any?>(
            DiscoveryConstants.INTERFACE_TYPE to "TCPServerInterface",
            DiscoveryConstants.TRANSPORT to false,
            DiscoveryConstants.TRANSPORT_ID to ByteArray(16),
            DiscoveryConstants.NAME to "Bad Stamp",
            DiscoveryConstants.LATITUDE to null,
            DiscoveryConstants.LONGITUDE to null,
            DiscoveryConstants.HEIGHT to null,
        )

        // Pack but with a bad stamp
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)
        packer.packMapHeader(info.size)
        for ((key, value) in info) {
            packer.packInt(key)
            when (value) {
                null -> packer.packNil()
                is Boolean -> packer.packBoolean(value)
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                else -> packer.packString(value.toString())
            }
        }
        packer.flush()
        val packed = out.toByteArray()

        // Use all-zeros stamp (almost certainly invalid for cost 8)
        val badStamp = ByteArray(Stamper.STAMP_SIZE)
        val payload = byteArrayOf(0x00) + packed + badStamp

        val result = handler.handleAnnounce(discoveryDestHash, testIdentity, payload)
        result shouldBe false
        callbackInvoked shouldBe false
    }

    @Test
    @DisplayName("Rejects too-small payload")
    fun `rejects payload too small for stamp`() {
        val handler = InterfaceAnnounceHandler(requiredValue = 8)
        // Payload must be > STAMP_SIZE + 1
        val tinyPayload = ByteArray(Stamper.STAMP_SIZE + 1)
        val result = handler.handleAnnounce(discoveryDestHash, testIdentity, tinyPayload)
        result shouldBe false
    }
}
