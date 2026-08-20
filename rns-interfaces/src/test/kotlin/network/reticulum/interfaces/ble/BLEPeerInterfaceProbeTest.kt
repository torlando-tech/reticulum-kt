package network.reticulum.interfaces.ble

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the BLE data-path liveness probe frame handling (protocol v0.4.0).
 *
 * [BLEPeerInterface.handleProbeFrame] is the inbound half of the probe: a 2-byte
 * PING(0x04)/PONG(0x05) over the real data path. Receiving a PING must be answered
 * with a PONG echoing the same nonce byte; any probe frame marks the peer
 * probe-capable. Non-probe fragments must pass through untouched (returns false) so
 * normal reassembly still runs. Mirrors python ble-reticulum `_handle_probe_frame`.
 */
@DisplayName("BLEPeerInterface data-path probe")
class BLEPeerInterfaceProbeTest {

    private val liveInterfaces = mutableListOf<BLEPeerInterface>()

    @AfterEach
    fun cleanup() {
        liveInterfaces.forEach { runCatching { it.detach() } }
        liveInterfaces.clear()
    }

    @Test
    fun `inbound PING is answered with a PONG echoing the nonce`() = runTest {
        val conn = CapturingConn()
        val peer = newPeerInterface(conn)

        val consumed = peer.handleProbeFrame(byteArrayOf(BLEConstants.PROBE_PING_BYTE, 0x2A))

        // The PING is consumed (not handed to the reassembler)...
        consumed shouldBe true
        // ...and answered with exactly one PONG that echoes the PING's nonce byte.
        conn.sent.size shouldBe 1
        conn.sent[0].toList() shouldBe listOf(BLEConstants.PROBE_PONG_BYTE, 0x2A.toByte())
    }

    @Test
    fun `inbound PONG is consumed without a reply`() = runTest {
        val conn = CapturingConn()
        val peer = newPeerInterface(conn)

        // A PONG (the echo we asked for) proves the data path but must NOT
        // trigger another probe frame, or two peers would ping-pong forever.
        peer.handleProbeFrame(byteArrayOf(BLEConstants.PROBE_PONG_BYTE, 0x07)) shouldBe true
        conn.sent.size shouldBe 0
    }

    @Test
    fun `non-probe fragments are not consumed`() = runTest {
        val conn = CapturingConn()
        val peer = newPeerInterface(conn)

        // Wrong length (a real fragment carries the 5-byte header) and wrong
        // first byte must both fall through to normal reassembly.
        peer.handleProbeFrame(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x01)) shouldBe false
        peer.handleProbeFrame(byteArrayOf(0x09, 0x00)) shouldBe false
        conn.sent.size shouldBe 0
    }

    private fun newPeerInterface(connection: CapturingConn): BLEPeerInterface {
        val parent =
            BLEInterface(
                name = "BLE-test",
                driver = StubBLEDriver(),
                transportIdentity = ByteArray(16),
            )
        val peer =
            BLEPeerInterface(
                name = "BLE|test",
                connection = connection,
                parentBleInterface = parent,
                peerIdentity = ByteArray(16),
            )
        liveInterfaces += peer
        return peer
    }

    /** [BLEPeerConnection] fake that records every fragment sent, for PONG assertions. */
    private class CapturingConn : BLEPeerConnection {
        val sent = mutableListOf<ByteArray>()
        override val address: String = "00:11:22:33:44:55"
        override val mtu: Int = 185
        override val identity: ByteArray? = ByteArray(16)
        override val receivedFragments: SharedFlow<ByteArray> = MutableSharedFlow()
        override suspend fun sendFragment(data: ByteArray) { sent += data }
        override suspend fun readIdentity(): ByteArray = ByteArray(16)
        override suspend fun writeIdentity(identity: ByteArray) = Unit
        override suspend fun readRemoteRssi(): Int = -70
        override fun close() = Unit
    }

    /** Minimal [BLEDriver] stub sufficient to construct a [BLEInterface] parent. */
    private class StubBLEDriver : BLEDriver {
        override suspend fun startAdvertising() = Unit
        override suspend fun stopAdvertising() = Unit
        override suspend fun startScanning() = Unit
        override suspend fun stopScanning() = Unit
        override suspend fun connect(address: String): BLEPeerConnection =
            error("unused in probe tests")

        override suspend fun disconnect(address: String) = Unit
        override fun shutdown() = Unit
        override val discoveredPeers: SharedFlow<DiscoveredPeer> = MutableSharedFlow()
        override val incomingConnections: SharedFlow<BLEPeerConnection> = MutableSharedFlow()
        override val connectionLost: SharedFlow<String> = MutableSharedFlow()
        override val localAddress: String? = null
        override val isRunning: Boolean = false
    }
}
