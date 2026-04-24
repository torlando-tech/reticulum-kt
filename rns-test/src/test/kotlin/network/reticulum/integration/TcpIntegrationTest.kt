package network.reticulum.integration

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import network.reticulum.transport.AnnounceHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Kotlin-to-Kotlin communication over TCP.
 */
@DisplayName("TCP Integration Tests")
class TcpIntegrationTest {

    private val testPort = 4242 + (Math.random() * 1000).toInt()
    private lateinit var server: TCPServerInterface
    private lateinit var client: TCPClientInterface

    @BeforeEach
    fun setup() {
        // Reset transport before each test
        Transport.stop()
    }

    @AfterEach
    fun teardown() {
        if (::client.isInitialized) {
            try { client.detach() } catch (e: Exception) {}
        }
        if (::server.isInitialized) {
            try { server.detach() } catch (e: Exception) {}
        }
        Transport.stop()
    }

    @Test
    @DisplayName("Server and client can connect")
    @Timeout(10)
    fun `server and client can connect`() {
        // Create server
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.start()

        // Wait for server to start
        Thread.sleep(100)

        // Create client
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        client.start()

        // Wait for connection
        Thread.sleep(500)

        assertTrue(server.online.value, "Server should be online")
        assertTrue(client.online.value, "Client should be online")
        assertEquals(1, server.clientCount(), "Server should have one client")
    }

    @Test
    @DisplayName("Client can send packet to server")
    @Timeout(10)
    fun `client can send packet to server`() {
        val receivedLatch = CountDownLatch(1)
        var receivedData: ByteArray? = null

        // Create server with callback
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.onPacketReceived = { data, iface ->
            receivedData = data
            receivedLatch.countDown()
        }
        server.start()

        Thread.sleep(100)

        // Create client
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        client.start()

        Thread.sleep(300)

        // Send a test packet — must be > HEADER_MIN_SIZE (19 bytes) to pass HDLC deframer
        val testData = ByteArray(24) { (it + 1).toByte() }
        client.processOutgoing(testData)

        // Wait for receive
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "Should receive packet")
        assertNotNull(receivedData, "Received data should not be null")
        assertTrue(testData.contentEquals(receivedData!!), "Data should match")
    }

    @Test
    @DisplayName("Server can send packet to client")
    @Timeout(10)
    fun `server can send packet to client`() {
        val receivedLatch = CountDownLatch(1)
        var receivedData: ByteArray? = null

        // Create server
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.start()

        Thread.sleep(100)

        // Create client with callback
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        client.onPacketReceived = { data, iface ->
            receivedData = data
            receivedLatch.countDown()
        }
        client.start()

        Thread.sleep(300)

        // Send a test packet from server to the connected client.
        // Matches Python semantics (TCPInterface.py:627-628): parent
        // `process_outgoing` is a pass — the spawned child interfaces
        // are what actually write to the wire, addressed individually by
        // Transport.outbound via `Transport.interfaces`. The pre-#46
        // fan-out loop that let `server.processOutgoing(data)` reach
        // every client at once is gone; production code reaches a peer
        // through its spawned child, which is what we exercise here.
        // Must be > HEADER_MIN_SIZE (19 bytes) to pass HDLC deframer.
        val testData = ByteArray(24) { (it + 0x10).toByte() }
        val spawnedChild = server.getClients().firstOrNull()
        assertNotNull(spawnedChild, "Server should have a spawned child for the connected client")
        spawnedChild!!.processOutgoing(testData)

        // Wait for receive
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "Should receive packet")
        assertNotNull(receivedData, "Received data should not be null")
        assertTrue(testData.contentEquals(receivedData!!), "Data should match")
    }

    @Test
    @DisplayName("Announce can be sent and received")
    @Timeout(10)
    fun `announce can be sent and received`() {
        val announceLatch = CountDownLatch(1)
        var receivedAnnounce = false

        // Create server
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.start()

        // Start transport and register server interface
        Transport.start(enableTransport = false)
        val serverRef = server.toRef()
        Transport.registerInterface(serverRef)

        // Set up announce handler
        Transport.registerAnnounceHandler(handler = AnnounceHandler { destHash, identity, appData ->
            println("Received announce for ${destHash.map { String.format("%02x", it) }.joinToString("")}")
            receivedAnnounce = true
            announceLatch.countDown()
            true
        })

        // Hook up server received packets to transport
        server.onPacketReceived = { data, iface ->
            Transport.inbound(data, iface.toRef())
        }

        Thread.sleep(100)

        // Create client
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        client.start()

        Thread.sleep(300)

        // Create identity and destination on client side
        val clientIdentity = Identity.create()
        val destination = Destination.create(
            identity = clientIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("test")
        )

        // Create and send announce
        val announcePacket = Packet.createAnnounce(destination)
        assertNotNull(announcePacket, "Should create announce packet")

        // Deregister so server-side Transport doesn't skip it as local
        // (in a real setup, client and server have separate Transport instances)
        Transport.deregisterDestination(destination)

        // Send the announce
        val packedAnnounce = announcePacket.pack()
        client.processOutgoing(packedAnnounce)

        // Wait for announce to be received
        assertTrue(announceLatch.await(5, TimeUnit.SECONDS), "Should receive announce")
        assertTrue(receivedAnnounce, "Announce handler should be called")

        // Verify path was learned
        assertTrue(Transport.hasPath(destination.hash), "Should have learned path to destination")
    }

    @Test
    @DisplayName("Bidirectional packet exchange")
    @Timeout(15)
    fun `bidirectional packet exchange`() {
        val serverReceivedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val clientReceivedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val completeLatch = CountDownLatch(10) // 5 each way

        // Create server with callback
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.onPacketReceived = { data, iface ->
            serverReceivedCount.incrementAndGet()
            completeLatch.countDown()
        }
        server.start()

        Thread.sleep(100)

        // Create client with callback
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        client.onPacketReceived = { data, iface ->
            clientReceivedCount.incrementAndGet()
            completeLatch.countDown()
        }
        client.start()

        Thread.sleep(300)

        // Send 5 packets from client to server
        // Must be > HEADER_MIN_SIZE (19 bytes) to pass HDLC deframer
        for (i in 0 until 5) {
            val testData = ByteArray(24) { (it + i).toByte() }
            client.processOutgoing(testData)
            Thread.sleep(50)
        }

        // Send 5 packets from server to client through the spawned child
        // (server parent's processOutgoing is a no-op per Python semantics,
        // see `Server can send packet to client` above for the full note).
        val spawnedChild = server.getClients().firstOrNull()
        assertNotNull(spawnedChild, "Server should have a spawned child for the connected client")
        for (i in 0 until 5) {
            val testData = ByteArray(24) { (it + i + 100).toByte() }
            spawnedChild!!.processOutgoing(testData)
            Thread.sleep(50)
        }

        // Wait for all packets
        assertTrue(completeLatch.await(10, TimeUnit.SECONDS), "Should receive all packets")
        assertEquals(5, serverReceivedCount.get(), "Server should receive 5 packets")
        assertEquals(5, clientReceivedCount.get(), "Client should receive 5 packets")
    }
}
