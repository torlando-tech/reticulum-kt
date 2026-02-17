package network.reticulum.interfaces.i2p

import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.KISS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for I2PInterface and I2PInterfacePeer.
 *
 * These tests verify interface properties, framing, and data exchange
 * without requiring a running I2P router. We use localhost TCP sockets
 * to simulate the SAM tunnel endpoint.
 */
class I2PInterfaceTest {

    // ─── Interface constants match Python ────────────────────────────

    @Test
    fun `I2PInterface BITRATE_GUESS matches Python 256kbps`() {
        assertEquals(256_000, I2PInterface.BITRATE_GUESS)
    }

    @Test
    fun `I2PInterface HW_MTU matches Python 1064`() {
        assertEquals(1064, I2PInterface.HW_MTU)
    }

    @Test
    fun `I2PInterfacePeer timeout constants match Python`() {
        assertEquals(45, I2PInterfacePeer.I2P_USER_TIMEOUT_S)
        assertEquals(10, I2PInterfacePeer.I2P_PROBE_AFTER_S)
        assertEquals(9, I2PInterfacePeer.I2P_PROBE_INTERVAL_S)
        assertEquals(5, I2PInterfacePeer.I2P_PROBES)

        // I2P_READ_TIMEOUT = (PROBE_INTERVAL * PROBES + PROBE_AFTER) * 2
        // = (9 * 5 + 10) * 2 = 110
        val expectedTimeout = (I2PInterfacePeer.I2P_PROBE_INTERVAL_S * I2PInterfacePeer.I2P_PROBES +
                I2PInterfacePeer.I2P_PROBE_AFTER_S) * 2
        assertEquals(expectedTimeout, I2PInterfacePeer.I2P_READ_TIMEOUT_S)
        // Python: (9 * 5 + 10) * 2 = 110
        assertEquals(110, I2PInterfacePeer.I2P_READ_TIMEOUT_S)
    }

    @Test
    fun `I2PInterfacePeer RECONNECT_WAIT matches Python 15s`() {
        assertEquals(15_000L, I2PInterfacePeer.RECONNECT_WAIT_MS)
    }

    @Test
    fun `tunnel states match Python constants`() {
        assertEquals(0, I2PInterfacePeer.TUNNEL_STATE_INIT)
        assertEquals(1, I2PInterfacePeer.TUNNEL_STATE_ACTIVE)
        assertEquals(2, I2PInterfacePeer.TUNNEL_STATE_STALE)
    }

    // ─── Inbound peer framing ────────────────────────────────────────

    @Test
    fun `inbound peer receives HDLC framed data`() {
        // Set up a localhost socket pair to simulate an I2P tunnel
        val server = ServerSocket(0)
        val port = server.localPort

        val receivedData = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)

        // Create I2P interface (dummy, not actually connecting to I2P)
        val i2pInterface = I2PInterface(
            name = "test-i2p",
            storagePath = System.getProperty("java.io.tmpdir") + "/i2p-test-" + System.currentTimeMillis(),
        )

        // Connect a client socket
        val clientSocket = Socket("127.0.0.1", port)
        val serverSocket = server.accept()

        // Create peer from the server-side socket (simulates incoming I2P connection)
        val peer = I2PInterfacePeer(
            name = "test-peer",
            parentI2P = i2pInterface,
            connectedSocket = serverSocket,
        )

        peer.onPacketReceived = { data, _ ->
            receivedData.set(data)
            latch.countDown()
        }

        // Start the peer (begins read loop)
        peer.start()

        // Send HDLC-framed data through the client socket
        // Data must be > 19 bytes (HEADER_MIN_SIZE) to pass HDLC deframer
        val testPayload = ByteArray(32) { (it + 1).toByte() }
        val framedData = HDLC.frame(testPayload)
        clientSocket.getOutputStream().write(framedData)
        clientSocket.getOutputStream().flush()

        // Wait for data
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive data within 5 seconds")

        val received = receivedData.get()
        assertNotNull(received)
        assertArrayEquals(testPayload, received)

        // Verify interface properties
        assertTrue(peer.isInitiator == false, "Inbound peer should not be initiator")
        assertEquals(I2PInterfacePeer.TUNNEL_STATE_INIT, peer.tunnelState)

        // Cleanup
        peer.detach()
        clientSocket.close()
        server.close()
    }

    @Test
    fun `inbound peer receives KISS framed data`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val receivedData = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)

        val i2pInterface = I2PInterface(
            name = "test-i2p-kiss",
            storagePath = System.getProperty("java.io.tmpdir") + "/i2p-test-kiss-" + System.currentTimeMillis(),
        )

        val clientSocket = Socket("127.0.0.1", port)
        val serverSocket = server.accept()

        val peer = I2PInterfacePeer(
            name = "test-peer-kiss",
            parentI2P = i2pInterface,
            connectedSocket = serverSocket,
            useKissFraming = true,
        )

        peer.onPacketReceived = { data, _ ->
            receivedData.set(data)
            latch.countDown()
        }

        peer.start()

        val testPayload = ByteArray(50) { (it + 0x10).toByte() }
        val framedData = KISS.frame(testPayload)
        clientSocket.getOutputStream().write(framedData)
        clientSocket.getOutputStream().flush()

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive KISS data within 5 seconds")

        val received = receivedData.get()
        assertNotNull(received)
        assertArrayEquals(testPayload, received)

        peer.detach()
        clientSocket.close()
        server.close()
    }

    @Test
    fun `peer processOutgoing sends HDLC framed data`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val i2pInterface = I2PInterface(
            name = "test-i2p-out",
            storagePath = System.getProperty("java.io.tmpdir") + "/i2p-test-out-" + System.currentTimeMillis(),
        )

        val clientSocket = Socket("127.0.0.1", port)
        val serverSocket = server.accept()

        val peer = I2PInterfacePeer(
            name = "test-peer-out",
            parentI2P = i2pInterface,
            connectedSocket = serverSocket,
        )

        // Set callback before start
        peer.onPacketReceived = { _, _ -> }
        peer.start()

        // Send data through the peer
        val testPayload = ByteArray(40) { (it + 5).toByte() }
        peer.processOutgoing(testPayload)

        // Read the framed data from the other end
        val buffer = ByteArray(4096)
        clientSocket.soTimeout = 5000
        val bytesRead = clientSocket.getInputStream().read(buffer)
        assertTrue(bytesRead > 0, "Should have received framed data")

        // Deframe it
        val receivedData = AtomicReference<ByteArray?>(null)
        val deframer = HDLC.createDeframer { data -> receivedData.set(data) }
        deframer.process(buffer.copyOf(bytesRead))

        assertNotNull(receivedData.get(), "HDLC deframer should have produced data")
        assertArrayEquals(testPayload, receivedData.get())

        // Verify TX bytes were tracked
        assertTrue(peer.txBytes.get() > 0, "TX bytes should be tracked")

        peer.detach()
        clientSocket.close()
        server.close()
    }

    // ─── Interface properties ────────────────────────────────────────

    @Test
    fun `I2PInterface has correct defaults`() {
        val iface = I2PInterface(
            name = "test",
            storagePath = System.getProperty("java.io.tmpdir"),
        )

        assertEquals(256_000, iface.bitrate)
        assertEquals(1064, iface.hwMtu)
        assertFalse(iface.supportsLinkMtuDiscovery)
        assertTrue(iface.canReceive)
        assertFalse(iface.canSend) // Server delegates to spawned peers
        assertTrue(iface.i2pTunneled)
        assertNull(iface.b32) // Not yet connected
    }

    @Test
    fun `I2PInterfacePeer IFAC delegates to parent`() {
        val parent = I2PInterface(
            name = "parent",
            storagePath = System.getProperty("java.io.tmpdir"),
            ifacNetname = "testnet",
            ifacNetkey = "testkey",
        )

        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSocket = server.accept()

        val peer = I2PInterfacePeer(
            name = "peer",
            parentI2P = parent,
            connectedSocket = serverSocket,
        )

        // IFAC properties should delegate to parent
        assertEquals("testnet", peer.ifacNetname)
        assertEquals("testkey", peer.ifacNetkey)
        assertEquals(parent.ifacSize, peer.ifacSize)
        assertArrayEquals(parent.ifacKey, peer.ifacKey)

        peer.detach()
        clientSocket.close()
        server.close()
    }

    @Test
    fun `outbound peer is initiator`() {
        val parent = I2PInterface(
            name = "parent",
            storagePath = System.getProperty("java.io.tmpdir"),
        )

        // Create outbound peer (with a fake I2P destination)
        val peer = I2PInterfacePeer(
            name = "outbound",
            parentI2P = parent,
            targetI2pDest = "fake-destination-base64-string",
        )

        assertTrue(peer.isInitiator, "Outbound peer should be initiator")

        peer.detach()
    }

    @Test
    fun `inbound peer is not initiator`() {
        val parent = I2PInterface(
            name = "parent",
            storagePath = System.getProperty("java.io.tmpdir"),
        )

        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSocket = server.accept()

        val peer = I2PInterfacePeer(
            name = "inbound",
            parentI2P = parent,
            connectedSocket = serverSocket,
        )

        assertFalse(peer.isInitiator, "Inbound peer should not be initiator")

        peer.detach()
        clientSocket.close()
        server.close()
    }
}
