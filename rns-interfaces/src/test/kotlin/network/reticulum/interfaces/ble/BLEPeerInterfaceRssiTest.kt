package network.reticulum.interfaces.ble

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for BLE per-packet RSSI annotation.
 *
 * [BLEPeerInterface.startRssiPolling] seeds the base-class `rStatRssi` from
 * `discoveryRssi` and then updates it via [BLEPeerInterface.pollAndApplyRssi]
 * every 10 seconds on central (outgoing) connections. Transport.inbound later
 * reads `rStatRssi` onto each received packet; LXMF surfaces it as
 * `LXMessage.receivedRssi`. Without these annotations, BLE messages render
 * with no signal info in downstream UIs.
 */
@DisplayName("BLEPeerInterface RSSI annotation")
class BLEPeerInterfaceRssiTest {

    private val liveInterfaces = mutableListOf<BLEPeerInterface>()

    @AfterEach
    fun cleanup() {
        // Cancels internal scopes (and the rssiJob inside) to avoid leaking
        // coroutines across test cases.
        liveInterfaces.forEach { runCatching { it.detach() } }
        liveInterfaces.clear()
    }

    @Test
    fun `peripheral connection leaves rStatRssi null - Android has no peripheral-side RSSI`() {
        val peer = newPeerInterface(isOutgoing = false, discoveryRssi = -65)

        peer.startRssiPolling()

        // Peripheral side (GATT server) has no readRemoteRssi equivalent,
        // so rStatRssi must stay null — packets on that interface stay
        // un-annotated rather than carry fabricated values.
        peer.rStatRssi shouldBe null
    }

    @Test
    fun `central connection seeds rStatRssi from discoveryRssi on start`() {
        val peer = newPeerInterface(isOutgoing = true, discoveryRssi = -72)

        peer.startRssiPolling()

        // First 10-second polling window: packets received before the first
        // readRemoteRssi poll completes must still be annotated, so we seed
        // from the scan-time RSSI captured at peer discovery.
        peer.rStatRssi shouldBe -72
    }

    @Test
    fun `poll updates both currentRssi and rStatRssi from readRemoteRssi`() =
        runTest {
            val conn = FakeBLEPeerConnection(rssiSupplier = { -58 })
            val peer = newPeerInterface(connection = conn, isOutgoing = true)

            peer.pollAndApplyRssi()

            // currentRssi: used by peer-scoring.
            // rStatRssi: read by Transport.inbound to annotate every packet.
            // Both must be updated in lockstep.
            peer.currentRssi shouldBe -58
            peer.rStatRssi shouldBe -58
        }

    @Test
    fun `poll exception is swallowed and prior rStatRssi is retained`() =
        runTest {
            val conn = FakeBLEPeerConnection(rssiSupplier = {
                throw RuntimeException("GATT read failed")
            })
            val peer = newPeerInterface(connection = conn, isOutgoing = true)
            peer.rStatRssi = -80 // prior valid reading from an earlier poll

            peer.pollAndApplyRssi()

            // A flaky readRemoteRssi must not clobber the last-known-good
            // value; leaving rStatRssi null would mean the next annotated
            // packet silently drops RSSI for no good reason.
            peer.rStatRssi shouldBe -80
        }

    private fun newPeerInterface(
        connection: FakeBLEPeerConnection = FakeBLEPeerConnection(rssiSupplier = { -70 }),
        isOutgoing: Boolean = true,
        discoveryRssi: Int = -70,
    ): BLEPeerInterface {
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
        peer.isOutgoing = isOutgoing
        peer.discoveryRssi = discoveryRssi
        liveInterfaces += peer
        return peer
    }

    /**
     * Minimal [BLEPeerConnection] fake. `readRemoteRssi` behavior is
     * controlled by the caller-supplied [rssiSupplier] to exercise both
     * happy-path and exception-path poll outcomes.
     */
    private class FakeBLEPeerConnection(
        private val rssiSupplier: () -> Int,
    ) : BLEPeerConnection {
        override val address: String = "00:11:22:33:44:55"
        override val mtu: Int = 185
        override val identity: ByteArray? = ByteArray(16)
        override val receivedFragments: SharedFlow<ByteArray> = MutableSharedFlow()
        override suspend fun sendFragment(data: ByteArray) = Unit
        override suspend fun readIdentity(): ByteArray = ByteArray(16)
        override suspend fun writeIdentity(identity: ByteArray) = Unit
        override suspend fun readRemoteRssi(): Int = rssiSupplier()
        override fun close() = Unit
    }

    /**
     * Minimal [BLEDriver] stub sufficient to construct a [BLEInterface]
     * parent; no method on the driver is called during these tests.
     */
    private class StubBLEDriver : BLEDriver {
        override suspend fun startAdvertising() = Unit
        override suspend fun stopAdvertising() = Unit
        override suspend fun startScanning() = Unit
        override suspend fun stopScanning() = Unit
        override suspend fun connect(address: String): BLEPeerConnection =
            error("unused in RSSI tests")

        override suspend fun disconnect(address: String) = Unit
        override fun shutdown() = Unit
        override val discoveredPeers: SharedFlow<DiscoveredPeer> = MutableSharedFlow()
        override val incomingConnections: SharedFlow<BLEPeerConnection> = MutableSharedFlow()
        override val connectionLost: SharedFlow<String> = MutableSharedFlow()
        override val localAddress: String? = null
        override val isRunning: Boolean = false
    }
}
