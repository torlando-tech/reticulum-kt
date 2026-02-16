package network.reticulum.packet

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for PHY stats fields on Packet.
 *
 * Phase 4 added rssi/snr/q fields to Packet so physical layer stats
 * can flow from Interface → InterfaceRef → Transport.processInbound → Packet → Link.
 * These tests verify the fields exist and can be set via internal access.
 */
@DisplayName("Packet PHY Stats Fields")
class PacketPhyStatsTest {

    private fun createTestPacket(): Packet {
        val destHash = ByteArray(16) { it.toByte() }
        return Packet.createRaw(
            destinationHash = destHash,
            data = "test".toByteArray(),
            packetType = PacketType.DATA,
            context = PacketContext.NONE
        )
    }

    @Test
    fun `packet has null PHY stats by default`() {
        val packet = createTestPacket()
        packet.rssi shouldBe null
        packet.snr shouldBe null
        packet.q shouldBe null
    }

    @Test
    fun `packet PHY stats can be read after being set`() {
        val packet = createTestPacket()

        // These fields have `internal set` — accessible within the module
        packet.rssi = -85
        packet.snr = 7.5f
        packet.q = 0.8f

        packet.rssi shouldBe -85
        packet.snr shouldBe 7.5f
        packet.q shouldBe 0.8f
    }

    @Test
    fun `packet PHY stats are independent of packet data`() {
        val packet1 = createTestPacket()
        val packet2 = createTestPacket()

        packet1.rssi = -100
        packet2.rssi = -50

        packet1.rssi shouldBe -100
        packet2.rssi shouldBe -50
    }

    @Test
    fun `unpacked packet has null PHY stats`() {
        val original = createTestPacket()
        original.rssi = -75
        val raw = original.pack()

        // Unpack from raw — PHY stats aren't in the wire format
        val unpacked = Packet.unpack(raw)
        unpacked shouldNotBe null
        unpacked!!.rssi shouldBe null
        unpacked.snr shouldBe null
        unpacked.q shouldBe null
    }
}
