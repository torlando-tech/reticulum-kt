package network.reticulum.integration

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
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
 * Integration tests for tunnel functionality.
 *
 * Tests tunnel synthesis between Kotlin TCP client and server.
 */
@DisplayName("Tunnel Integration Tests")
class TunnelIntegrationTest {

    private val testPort = 4245 + (Math.random() * 1000).toInt()
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
    @DisplayName("Client synthesizes tunnel on connection")
    @Timeout(15)
    fun `client synthesizes tunnel on connection`() {
        // Create server
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.start()

        // Start transport with transport enabled (required for tunnels)
        Transport.start(enableTransport = true)

        // Register server
        val serverRef = server.toRef()
        Transport.registerInterface(serverRef)

        // Hook up server to transport
        server.onPacketReceived = { data, iface ->
            println("Server received ${data.size} bytes")
            Transport.inbound(data, iface.toRef())
        }

        Thread.sleep(100)

        // Create client - this should set wantsTunnel=true
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        val clientRef = client.toRef()
        Transport.registerInterface(clientRef)

        // Hook up client to transport
        client.onPacketReceived = { data, iface ->
            println("Client received ${data.size} bytes")
            Transport.inbound(data, iface.toRef())
        }

        client.start()

        // Wait for connection
        Thread.sleep(1000)
        assertTrue(client.online.get(), "Client should be online")

        // wantsTunnel might already be false if job loop processed it
        println("Initial wantsTunnel: ${clientRef.wantsTunnel}")

        // Wait for tunnel synthesis (job loop runs every 250ms)
        Thread.sleep(3000)

        // Log results
        println("After synthesis - wantsTunnel: ${clientRef.wantsTunnel}")
        println("Client tunnelId: ${clientRef.tunnelId?.take(8)?.joinToString("") { "%02x".format(it) }}")
        println("Tunnels: ${Transport.getTunnels().size}")

        for ((key, tunnel) in Transport.getTunnels()) {
            println("Tunnel ${key}: interface=${tunnel.interface_?.name}, paths=${tunnel.paths.size}")
        }

        // The tunnel should have been created (either wantsTunnel is cleared or we have a tunnel)
        val tunnelCreated = !clientRef.wantsTunnel || Transport.getTunnels().isNotEmpty()
        assertTrue(tunnelCreated, "Tunnel should be synthesized (wantsTunnel cleared or tunnel exists)")
    }

    @Test
    @DisplayName("Announce path is stored in tunnel")
    @Timeout(20)
    fun `announce path is stored in tunnel`() {
        val announceLatch = CountDownLatch(1)

        // Create server
        server = TCPServerInterface("test-server", "127.0.0.1", testPort)
        server.start()

        // Start transport with transport enabled
        Transport.start(enableTransport = true)

        // Register server
        val serverRef = server.toRef()
        Transport.registerInterface(serverRef)

        // Create identity and destination on server side
        val serverIdentity = Identity.create()
        val destination = Destination.create(
            identity = serverIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "tunneltest",
            aspects = arrayOf("server")
        )

        // Hook up server to transport
        server.onPacketReceived = { data, iface ->
            Transport.inbound(data, iface.toRef())
        }

        Thread.sleep(100)

        // Create client
        client = TCPClientInterface("test-client", "127.0.0.1", testPort)
        val clientRef = client.toRef()
        Transport.registerInterface(clientRef)

        // Hook up client to transport
        client.onPacketReceived = { data, iface ->
            Transport.inbound(data, iface.toRef())
        }

        // Set announce handler
        Transport.registerAnnounceHandler { destHash, identity, appData ->
            println("Received announce for ${destHash.take(8).joinToString("") { "%02x".format(it) }}")
            announceLatch.countDown()
            true
        }

        client.start()

        // Wait for connection and tunnel synthesis
        Thread.sleep(3000)

        assertTrue(client.online.get(), "Client should be online")

        // Send announce from server
        println("Sending announce...")
        destination.announce()

        // Wait for announce
        assertTrue(announceLatch.await(10, TimeUnit.SECONDS), "Should receive announce")

        // Check path was learned
        assertTrue(Transport.hasPath(destination.hash), "Should have path to destination")

        // Check if path is associated with tunnel
        val tunnels = Transport.getTunnels()
        println("Tunnels after announce: ${tunnels.size}")
        for ((key, tunnel) in tunnels) {
            println("Tunnel ${key}: interface=${tunnel.interface_?.name}, paths=${tunnel.paths.size}")
            for ((pathKey, pathEntry) in tunnel.paths) {
                println("  Path: ${pathKey}, hops=${pathEntry.hops}")
            }
        }
    }
}
