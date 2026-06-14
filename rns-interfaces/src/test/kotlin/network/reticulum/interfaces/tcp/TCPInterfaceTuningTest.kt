package network.reticulum.interfaces.tcp

import network.reticulum.interfaces.Interface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tuning-knob mirror tests for the Phase-5g config threading: the bitrate floor
 * (python Reticulum.py:765-768), the fixed-MTU / AUTOCONFIGURE_MTU posture
 * (python TCPInterface + Interface.AUTOCONFIGURE_MTU/FIXED_MTU), and the IFAC
 * size bits->bytes floor (python Reticulum.py:719-723). These drive the real
 * interface constructors, no sockets are opened (start() is never called).
 */
class TCPInterfaceTuningTest {

    // --- bitrate floor (Reticulum.py:765-768) ---

    @Test
    @DisplayName("sub-minimum bitrate is ignored, keeping BITRATE_GUESS")
    fun `bitrate below minimum falls back to guess`() {
        val server = TCPServerInterface(name = "s", bindPort = 0, bitrate = 3)
        assertEquals(TCPServerInterface.BITRATE_GUESS, server.bitrate)

        val client = TCPClientInterface(name = "c", targetHost = "127.0.0.1", targetPort = 1, bitrate = 3)
        assertEquals(TCPClientInterface.BITRATE_GUESS, client.bitrate)
    }

    @Test
    @DisplayName("in-range bitrate (>= MINIMUM_BITRATE) is applied verbatim")
    fun `in range bitrate is applied`() {
        val server = TCPServerInterface(name = "s", bindPort = 0, bitrate = 1000)
        assertEquals(1000, server.bitrate)

        // Boundary: exactly MINIMUM_BITRATE (5) is accepted (>=).
        val client = TCPClientInterface(name = "c", targetHost = "127.0.0.1", targetPort = 1, bitrate = 5)
        assertEquals(5, client.bitrate)
    }

    @Test
    @DisplayName("no bitrate config keeps the class BITRATE_GUESS")
    fun `default bitrate is guess`() {
        assertEquals(
            TCPServerInterface.BITRATE_GUESS,
            TCPServerInterface(name = "s", bindPort = 0).bitrate,
        )
    }

    // --- fixed_mtu / AUTOCONFIGURE_MTU posture ---

    @Test
    @DisplayName("default mode: HW_MTU is the bitrate-optimised value, AUTOCONFIGURE on, FIXED off")
    fun `default mtu posture`() {
        val server = TCPServerInterface(name = "s", bindPort = 0)
        // python interface_post_init runs optimise_mtu() on every non-fixed
        // interface (Reticulum.py:780); the 10 Mbps BITRATE_GUESS maps to 8192,
        // NOT the class HW_MTU=262144 (that is only the pre-optimise default).
        assertEquals(
            Interface.optimiseMtu(server.bitrate.toLong()) ?: TCPServerInterface.HW_MTU,
            server.hwMtu,
        )
        assertEquals(8192, server.hwMtu)
        assertTrue(server.autoconfigureMtu)
        assertFalse(server.fixedMtu)
        assertTrue(server.supportsLinkMtuDiscovery)
    }

    @Test
    @DisplayName("fixed_mtu pins HW_MTU, disables AUTOCONFIGURE, sets FIXED")
    fun `fixed mtu posture`() {
        val server = TCPServerInterface(name = "s", bindPort = 0, fixedMtuBytes = 500)
        assertEquals(500, server.hwMtu)
        assertFalse(server.autoconfigureMtu)
        assertTrue(server.fixedMtu)
        // supportsLinkMtuDiscovery stays true so the negotiated MTU still signals
        // the pinned value (AUTOCONFIGURE || FIXED).
        assertTrue(server.supportsLinkMtuDiscovery)

        val client = TCPClientInterface(name = "c", targetHost = "127.0.0.1", targetPort = 1, fixedMtuBytes = 500)
        assertEquals(500, client.hwMtu)
        assertFalse(client.autoconfigureMtu)
        assertTrue(client.fixedMtu)
    }

    // --- IFAC size bits -> bytes floor (Reticulum.py:719-723) ---

    @Test
    @DisplayName("ifac_size in bits resolves to bytes when >= IFAC_MIN_SIZE*8")
    fun `ifac size bits divide by eight`() {
        // 16 bits / 8 == 2 bytes; IFAC must be active (network name set).
        val iface = TCPServerInterface(name = "s", bindPort = 0, ifacNetname = "net", ifacNetkey = "pw", ifacSizeBits = 16)
        assertEquals(2, iface.ifacSize)
    }

    @Test
    @DisplayName("sub-minimum ifac_size (< 8 bits) floors to DEFAULT_IFAC_SIZE")
    fun `ifac size below minimum floors to default`() {
        val iface = TCPServerInterface(name = "s", bindPort = 0, ifacNetname = "net", ifacNetkey = "pw", ifacSizeBits = 4)
        assertEquals(TCPServerInterface.DEFAULT_IFAC_SIZE, iface.ifacSize)
    }

    @Test
    @DisplayName("ifac_size is 0 when IFAC is inactive (no network name/key)")
    fun `ifac size zero when inactive`() {
        val iface = TCPServerInterface(name = "s", bindPort = 0, ifacSizeBits = 16)
        assertEquals(0, iface.ifacSize)
    }
}
