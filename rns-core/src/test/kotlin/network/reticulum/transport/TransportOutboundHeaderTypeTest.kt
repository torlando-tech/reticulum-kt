package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression guard for PR #39 ("Transport: don't HEADER_2-wrap link DATA
 * packets on multi-hop paths").
 *
 * The bug: in [Transport.processOutbound], the multi-hop path-routing branch
 * used to unconditionally wrap HEADER_1 DATA packets in HEADER_2 with the
 * path's nextHop as transport_id. For a LINK destination, the
 * destination_hash IS the linkId, which no intermediate transport's identity
 * hash matches, so intermediates dropped the packet as "other transport
 * instance". The fix added an `isLink` guard: link DATA packets are
 * transmitted as-is (HEADER_1) even when `pathEntry.hops > 1`, so
 * intermediates' link_table forwarding can match on linkId.
 *
 * This test pins that behavior at the byte level: the bytes written to the
 * outbound interface must start with a HEADER_1 flag byte (bit 6 == 0), and
 * packet size must equal the original packed size (no transport_id prepended).
 *
 * The companion test verifies a non-link DATA packet with hops>1 still gets
 * HEADER_2-wrapped, so anyone widening the `!isLink` guard to skip transport
 * wrapping entirely on multi-hop paths gets a fast red test.
 *
 * The slow E2E equivalent is
 * `reticulum-conformance/tests/wire/test_link_multihop.py::test_link_data_reaches_receiver_multihop`.
 */
@DisplayName("Transport outbound header type on multi-hop paths")
class TransportOutboundHeaderTypeTest {

    /**
     * A minimal [InterfaceRef] that captures bytes handed to [send] so the
     * test can inspect what Transport actually wrote on the wire.
     *
     * Lives inline in the test rather than depending on the
     * `conformance-bridge` module's `MockInterface`, because `rns-core`'s
     * test sourceset can't see types from downstream modules.
     */
    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        // Deterministic hash distinct from the dest-hash pattern used below.
        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    private lateinit var iface: CapturingInterface

    @BeforeEach
    fun setup() {
        // Clear any state leaked from other tests in the same JVM.
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort — a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()

        // Transport.outbound() short-circuits when !started, so we must start
        // it. enableTransport = false keeps this lightweight (no tunnel
        // table load, no forwarding logic engaged for our injected path).
        Transport.start(Identity.create(), enableTransport = false)

        iface = CapturingInterface(name = "capture-${System.nanoTime()}")
        Transport.registerInterface(iface)
    }

    @AfterEach
    fun teardown() {
        try {
            Transport.deregisterInterface(iface)
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /**
     * Install a 2-hop path for [destHash] that routes out through [iface].
     * nextHop is a made-up transport_id — its only role in this test is to
     * be the value that insertIntoTransport would wrap with IF the bug were
     * back. Its specific bytes don't matter for the assertion.
     */
    private fun installTwoHopPath(destHash: ByteArray) {
        val now = System.currentTimeMillis()
        val entry = PathEntry(
            timestamp = now,
            nextHop = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xBB.toByte() },
            hops = 2,
            expires = now + TransportConstants.PATHFINDER_E,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = iface.hash,
            announcePacketHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xCC.toByte() },
            state = PathState.ACTIVE,
            failureCount = 0,
        )
        Transport.pathTable[destHash.toKey()] = entry
    }

    @Test
    @DisplayName("link DATA packet on 2-hop path stays HEADER_1 (no transport_id wrap)")
    fun linkDataOnMultihopPathStaysHeader1() {
        // The linkId IS the destination_hash for a LINK destination.
        val linkId = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { (it + 1).toByte() }
        installTwoHopPath(linkId)

        // Build a link DATA packet — mirrors what Link.send() ultimately
        // produces for a request on an established link.
        val payload = ByteArray(32) { 0x11.toByte() }
        val packet = Packet.createRaw(
            destinationHash = linkId,
            data = payload,
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = PacketContext.REQUEST,
            headerType = HeaderType.HEADER_1,
            createReceipt = false,
        )
        val originalPackedSize = packet.pack().size

        Transport.outbound(packet) shouldBe true

        iface.sent.size shouldBe 1
        val wire = iface.sent[0]

        // HEADER_1 vs HEADER_2 lives in bit 6 of the flags byte. If the
        // pre-fix behavior returns, this byte has bit 6 set (HEADER_2) and
        // the wire packet is 16 bytes longer thanks to the inserted
        // transport_id.
        val flags = wire[0].toInt() and 0xFF
        val headerBit = flags and 0x40
        headerBit shouldBe 0                  // HEADER_1
        wire.size shouldBe originalPackedSize // no transport_id insertion
    }

    @Test
    @DisplayName("non-link DATA packet on 2-hop path IS HEADER_2-wrapped")
    fun nonLinkDataOnMultihopPathGetsHeader2Wrapped() {
        // SINGLE destination — the normal multi-hop data case that must
        // still get HEADER_2 wrapping for the Python-parity forward path.
        // This is the guard against "just drop transport wrapping on
        // hops>1" being the naive fix for the link bug.
        val destHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { (it + 2).toByte() }
        installTwoHopPath(destHash)

        val payload = ByteArray(32) { 0x22.toByte() }
        val packet = Packet.createRaw(
            destinationHash = destHash,
            data = payload,
            packetType = PacketType.DATA,
            destinationType = DestinationType.SINGLE,
            context = PacketContext.NONE,
            headerType = HeaderType.HEADER_1,
            createReceipt = false,
        )
        val originalPackedSize = packet.pack().size

        Transport.outbound(packet) shouldBe true

        iface.sent.size shouldBe 1
        val wire = iface.sent[0]

        val flags = wire[0].toInt() and 0xFF
        val headerBit = flags and 0x40
        headerBit shouldBe 0x40                                     // HEADER_2
        wire.size shouldBe originalPackedSize + RnsConstants.TRUNCATED_HASH_BYTES
    }
}
