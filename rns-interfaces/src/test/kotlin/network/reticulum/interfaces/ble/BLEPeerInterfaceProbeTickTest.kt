package network.reticulum.interfaces.ble

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BLEPeerInterface.probeTick], the per-tick data-path liveness decision
 * extracted from [BLEPeerInterface.dataPathProbeLoop] so it can be exercised without
 * driving the 10-second polling delay.
 *
 * Covers the three behaviors the code review called out:
 * - a dead path (timed out, probe-capable) reconnects and sends no PING;
 * - a throwing driver disconnect is retried on the next tick (probeCapable is not cleared);
 * - a successful disconnect stops further reconnects (probeCapable is cleared).
 *
 * Plus the mutually-exclusive `else if`: a PING is only sent on an idle-but-not-dead path,
 * never on a dead one.
 */
@DisplayName("BLEPeerInterface data-path probe tick")
class BLEPeerInterfaceProbeTickTest {

    private val liveInterfaces = mutableListOf<BLEPeerInterface>()

    @AfterEach
    fun cleanup() {
        liveInterfaces.forEach { runCatching { it.detach() } }
        liveInterfaces.clear()
    }

    @Test
    fun `dead path reconnects and sends no PING`() = runTest {
        val conn = CapturingConn()
        val driver = ControllableDriver(disconnectSucceeds = true)
        val peer = newPeerInterface(conn, driver)

        // Mark the peer probe-capable and backdate the liveness clock past the
        // 45s data-path timeout.
        peer.setProbeCapableForTest(true)
        peer.setLastRealDataForTest(System.currentTimeMillis() - (BLEConstants.DATA_PATH_TIMEOUT_MS + 1_000))

        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.Reconnected

        // The dead-path branch fired: the driver was asked to disconnect, and NO PING
        // was sent (the PING would be discarded on disconnect).
        driver.disconnectCount shouldBe 1
        conn.sent.size shouldBe 0
    }

    @Test
    fun `throwing disconnect is retried on the next tick`() = runTest {
        val conn = CapturingConn()
        val driver = ControllableDriver(disconnectSucceeds = false)
        val peer = newPeerInterface(conn, driver)

        peer.setProbeCapableForTest(true)
        peer.setLastRealDataForTest(System.currentTimeMillis() - (BLEConstants.DATA_PATH_TIMEOUT_MS + 1_000))

        // First tick: the driver disconnect throws, so the tick reports failure and
        // must NOT clear probeCapable (the peer is still connected).
        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.DisconnectFailed
        driver.disconnectCount shouldBe 1

        // Second tick: because probeCapable survived the failed disconnect, the
        // dead-path branch fires again and retries the reconnect rather than giving up.
        // (No PING is sent on either tick - the path is dead, not merely idle.)
        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.DisconnectFailed
        driver.disconnectCount shouldBe 2
        conn.sent.size shouldBe 0
    }

    @Test
    fun `successful disconnect stops further reconnects`() = runTest {
        val conn = CapturingConn()
        val driver = ControllableDriver(disconnectSucceeds = true)
        val peer = newPeerInterface(conn, driver)

        peer.setProbeCapableForTest(true)
        peer.setLastRealDataForTest(System.currentTimeMillis() - (BLEConstants.DATA_PATH_TIMEOUT_MS + 1_000))

        // First tick: successful disconnect clears probeCapable.
        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.Reconnected
        driver.disconnectCount shouldBe 1

        // Second tick: probeCapable is now false, so the dead-path (reconnect) branch no
        // longer fires - the driver is NOT asked to disconnect a second time. (The
        // driver.disconnect from the first tick triggers the connectionLost teardown, so
        // in practice the loop ends; here we just prove the reconnect does not repeat.)
        peer.probeTick()
        driver.disconnectCount shouldBe 1
    }

    @Test
    fun `idle but not dead path sends a PING and does not reconnect`() = runTest {
        val conn = CapturingConn()
        val driver = ControllableDriver(disconnectSucceeds = true)
        val peer = newPeerInterface(conn, driver)

        // Idle past the 15s probe interval but within the 45s timeout: a PING goes out,
        // no reconnect. (probeCapable false is the pre-probe state; it would also hold
        // for a peer that has not yet seen a PING/PONG.)
        peer.setLastRealDataForTest(System.currentTimeMillis() - (BLEConstants.DATA_PATH_PROBE_INTERVAL_MS + 1_000))

        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.Pinged

        // Exactly one PING (2-byte probe frame), no driver disconnect.
        conn.sent.size shouldBe 1
        conn.sent[0][0] shouldBe BLEConstants.PROBE_PING_BYTE
        driver.disconnectCount shouldBe 0
    }

    @Test
    fun `healthy path (recent real data) takes no action`() = runTest {
        val conn = CapturingConn()
        val driver = ControllableDriver(disconnectSucceeds = true)
        val peer = newPeerInterface(conn, driver)

        // Real data arrived moments ago: neither a PING nor a reconnect.
        peer.setLastRealDataForTest(System.currentTimeMillis() - 1_000)

        peer.probeTick() shouldBe BLEPeerInterface.ProbeTickResult.None

        conn.sent.size shouldBe 0
        driver.disconnectCount shouldBe 0
    }

    private fun newPeerInterface(
        connection: CapturingConn,
        driver: ControllableDriver,
    ): BLEPeerInterface {
        val parent =
            BLEInterface(
                name = "BLE-test",
                driver = driver,
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

    /** [BLEPeerConnection] fake that records every fragment sent, for PING assertions. */
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

    /**
     * [BLEDriver] stub that records every [disconnect] call and can be configured to
     * throw, so tests can exercise both the confirmed-disconnect (success) and the
     * thrown-disconnect (retry) paths of [BLEPeerInterface.probeTick].
     */
    private class ControllableDriver(
        private val disconnectSucceeds: Boolean,
    ) : BLEDriver {
        var disconnectCount = 0
        val disconnectedAddresses = mutableListOf<String>()

        override suspend fun disconnect(address: String) {
            disconnectCount += 1
            disconnectedAddresses += address
            if (!disconnectSucceeds) error("simulated driver disconnect failure")
        }

        override suspend fun startAdvertising() = Unit
        override suspend fun stopAdvertising() = Unit
        override suspend fun startScanning() = Unit
        override suspend fun stopScanning() = Unit
        override suspend fun connect(address: String): BLEPeerConnection =
            error("unused in probe-tick tests")

        override fun shutdown() = Unit
        override val discoveredPeers: SharedFlow<DiscoveredPeer> = MutableSharedFlow()
        override val incomingConnections: SharedFlow<BLEPeerConnection> = MutableSharedFlow()
        override val connectionLost: SharedFlow<String> = MutableSharedFlow()
        override val localAddress: String? = null
        override val isRunning: Boolean = false
    }
}
