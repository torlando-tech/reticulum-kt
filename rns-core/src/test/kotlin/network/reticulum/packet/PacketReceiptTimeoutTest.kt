package network.reticulum.packet

import io.kotest.matchers.shouldBe
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.transport.TransportConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests pinning the PacketReceipt timeout convergences made for the
 * conformance wire phase (5f), mirroring python RNS:
 *
 *  - the non-link receipt timeout is get_first_hop_timeout(dest) +
 *    Packet.TIMEOUT_PER_HOP * Transport.hops_to(dest) (Packet.py:432-433), and
 *    hops_to returns PATHFINDER_M for an unknown path (Transport.py:2641-2648),
 *    so a fresh path-less destination is 6 + 6*128 == 774s — NOT the old
 *    per-hop=1 floor (12s);
 *  - check_timeout concludes a timed-out receipt CULLED iff timeout == -1, else
 *    FAILED (Packet.py:561-565), keyed on the timeout value (not on retries).
 */
@DisplayName("PacketReceipt timeout arithmetic + check_timeout branch")
class PacketReceiptTimeoutTest {

    private fun pathlessReceipt(): PacketReceipt {
        // A fresh destination hash with no path-table entry -> hops_to unknown.
        val destHash = defaultCryptoProvider().randomBytes(16)
        val packet =
            Packet.createRaw(
                destinationHash = destHash,
                data = "timeout-probe".toByteArray(),
                packetType = PacketType.DATA,
                context = PacketContext.NONE,
            )
        packet.pack()
        return PacketReceipt.forPacketForTest(packet)
    }

    @Test
    fun `path-less non-link receipt timeout is first_hop + TIMEOUT_PER_HOP times PATHFINDER_M`() {
        val receipt = pathlessReceipt()
        // get_first_hop_timeout == DEFAULT_PER_HOP_TIMEOUT (6s) for an unknown path,
        // TIMEOUT_PER_HOP == 6, hops_to == PATHFINDER_M (128) -> 6 + 6*128 == 774.
        val expected =
            (TransportConstants.DEFAULT_PER_HOP_TIMEOUT / 1000.0) +
                (TransportConstants.DEFAULT_PER_HOP_TIMEOUT / 1000.0) * TransportConstants.PATHFINDER_M
        receipt.timeout shouldBe 774.0
        receipt.timeout shouldBe expected
    }

    @Test
    fun `check_timeout concludes CULLED when timeout is the -1 sentinel`() {
        val receipt = pathlessReceipt()
        receipt.setTimeout(-1.0)
        // Back-date sent_at far enough that is_timed_out() is true regardless of timeout.
        receipt.setSentAtForTest(receipt.sentAt - 100_000_000L)
        receipt.checkTimeout() shouldBe true
        receipt.status shouldBe PacketReceipt.CULLED
    }

    @Test
    fun `check_timeout concludes FAILED for a finite timed-out timeout`() {
        val receipt = pathlessReceipt()
        receipt.setTimeout(0.0)
        receipt.setSentAtForTest(receipt.sentAt - 100_000_000L)
        receipt.checkTimeout() shouldBe true
        receipt.status shouldBe PacketReceipt.FAILED
    }
}
