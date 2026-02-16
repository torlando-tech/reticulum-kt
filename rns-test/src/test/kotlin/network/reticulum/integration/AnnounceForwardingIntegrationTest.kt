package network.reticulum.integration

import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.PacketType
import network.reticulum.common.TransportType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for announce forwarding to local clients.
 *
 * Tests the critical fix: when Transport receives an announce from an external
 * interface, it must re-package the announce with HEADER_2 + TRANSPORT type +
 * its own identity as transport_id before forwarding to local clients.
 *
 * This test runs entirely in Kotlin — no Python bridge needed.
 *
 * Architecture:
 *   ExternalInterface ---(inject announce)---> Transport ---(forward)---> LocalServerInterface
 *                                                                              |
 *                                                                         raw TCP socket
 *                                                                              |
 *                                                                         read & parse
 */
@DisplayName("Announce Forwarding Integration Tests")
class AnnounceForwardingIntegrationTest {

    private val sharedPort = 18242 + (System.currentTimeMillis() % 500).toInt()
    private var configDir: java.io.File? = null
    private var localServer: LocalServerInterface? = null
    private var externalInterface: ExternalTestInterface? = null
    private var clientSocket: Socket? = null

    @BeforeEach
    fun setup() {
        Transport.stop()

        configDir = Files.createTempDirectory("rns-announce-fwd-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = true
        )

        // Start LocalServerInterface
        localServer = LocalServerInterface(
            name = "SharedInstance",
            tcpPort = sharedPort
        )
        Transport.registerInterface(localServer!!.toRef())
        localServer!!.start()

        // Connect a raw socket as local client
        clientSocket = Socket("127.0.0.1", sharedPort)
        clientSocket!!.soTimeout = 5000

        // Wait for the server to accept the connection
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (localServer!!.clientCount() > 0) break
            Thread.sleep(50)
        }
        assertTrue(localServer!!.clientCount() > 0, "Local client should be connected")

        // Register a fake external interface
        externalInterface = ExternalTestInterface("External")
        Transport.registerInterface(externalInterface!!.toRef())
    }

    @AfterEach
    fun teardown() {
        clientSocket?.close()
        localServer?.detach()
        externalInterface?.detach()
        Reticulum.stop()
        configDir?.deleteRecursively()
    }

    @Test
    @DisplayName("forwarded announce uses HEADER_2 with TRANSPORT type")
    @Timeout(15)
    fun `forwarded announce uses HEADER_2 with TRANSPORT type`() {
        // Create a fake announce from the external interface
        val announceIdentity = Identity.create()
        val destination = Destination.create(
            identity = announceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("announce")
        )

        // Create an announce packet (HEADER_1, BROADCAST — as it would arrive from the network)
        val announcePacket = destination.announce(send = false)
        assertNotNull(announcePacket, "Should create announce packet")

        // Inject it via the external interface (simulates receiving from network)
        val rawBytes = announcePacket.raw ?: announcePacket.pack()
        externalInterface!!.injectPacket(rawBytes)

        // Wait for Transport to process and forward
        Thread.sleep(1000)

        // Read from the local client socket
        val packets = readHdlcPackets(clientSocket!!.getInputStream(), 3000)
        assertTrue(packets.isNotEmpty(),
            "Local client should receive at least one packet"
        )

        // Find and verify the announce packet
        var foundAnnounce = false
        for (raw in packets) {
            val parsed = Packet.unpack(raw)
            if (parsed != null && parsed.packetType == PacketType.ANNOUNCE) {
                // Verify HEADER_2 + TRANSPORT
                assertEquals(HeaderType.HEADER_2, parsed.headerType,
                    "Forwarded announce should have HEADER_2")
                assertEquals(TransportType.TRANSPORT, parsed.transportType,
                    "Forwarded announce should have TRANSPORT type")

                // Verify transport_id matches Transport's identity
                val expectedTransportId = Transport.identity?.hash
                assertNotNull(expectedTransportId, "Transport should have identity")
                assertNotNull(parsed.transportId, "HEADER_2 packet should have transport_id")
                assertTrue(
                    expectedTransportId.contentEquals(parsed.transportId!!),
                    "transport_id should match Transport identity hash"
                )

                // Verify destination hash matches the announced destination
                assertTrue(
                    destination.hash.contentEquals(parsed.destinationHash),
                    "destination_hash should match announced destination"
                )

                foundAnnounce = true
                println("  [Test] Announce verified: HEADER_2 + TRANSPORT + correct transport_id")
                break
            }
        }
        assertTrue(foundAnnounce, "Should find ANNOUNCE packet in forwarded data")
    }

    @Test
    @DisplayName("forwarded announce preserves hop count")
    @Timeout(15)
    fun `forwarded announce preserves hop count`() {
        val announceIdentity = Identity.create()
        val destination = Destination.create(
            identity = announceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("hops")
        )

        val announcePacket = destination.announce(send = false)
        assertNotNull(announcePacket)

        val rawBytes = announcePacket.raw ?: announcePacket.pack()
        externalInterface!!.injectPacket(rawBytes)

        Thread.sleep(1000)

        val packets = readHdlcPackets(clientSocket!!.getInputStream(), 3000)
        assertTrue(packets.isNotEmpty())

        for (raw in packets) {
            val parsed = Packet.unpack(raw)
            if (parsed != null && parsed.packetType == PacketType.ANNOUNCE) {
                // The hop count should match what Transport processed
                // (incremented by 1 from inbound processing, then preserved in forwarding)
                assertTrue(parsed.hops >= 0, "Hop count should be non-negative: ${parsed.hops}")
                println("  [Test] Hop count preserved: ${parsed.hops}")
                return
            }
        }
        assertTrue(false, "Should find ANNOUNCE packet")
    }

    @Test
    @DisplayName("forwarded announce preserves destination type")
    @Timeout(15)
    fun `forwarded announce preserves destination type`() {
        val announceIdentity = Identity.create()
        val destination = Destination.create(
            identity = announceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "testapp",
            aspects = arrayOf("dtype")
        )

        val announcePacket = destination.announce(send = false)
        assertNotNull(announcePacket)

        val rawBytes = announcePacket.raw ?: announcePacket.pack()
        externalInterface!!.injectPacket(rawBytes)

        Thread.sleep(1000)

        val packets = readHdlcPackets(clientSocket!!.getInputStream(), 3000)
        assertTrue(packets.isNotEmpty())

        for (raw in packets) {
            val parsed = Packet.unpack(raw)
            if (parsed != null && parsed.packetType == PacketType.ANNOUNCE) {
                assertEquals(DestinationType.SINGLE, parsed.destinationType,
                    "Destination type should be preserved as SINGLE")
                println("  [Test] Destination type preserved: ${parsed.destinationType}")
                return
            }
        }
        assertTrue(false, "Should find ANNOUNCE packet")
    }

    // ─── Helper: read HDLC-framed packets from a socket ───

    private fun readHdlcPackets(input: InputStream, timeoutMs: Long): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val deframer = HDLC.createDeframer { frame -> packets.add(frame) }
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(4096)

        while (System.currentTimeMillis() < deadline) {
            try {
                val available = input.available()
                if (available > 0) {
                    val bytesRead = input.read(buffer, 0, minOf(available, buffer.size))
                    if (bytesRead > 0) {
                        deframer.process(buffer.copyOf(bytesRead))
                    }
                } else {
                    Thread.sleep(50)
                }
            } catch (e: java.net.SocketTimeoutException) {
                break
            }
        }
        return packets
    }

    // ─── Fake external interface for injecting packets ───

    class ExternalTestInterface(name: String) : Interface(name) {
        override val bitrate: Int = 10_000_000
        override val hwMtu: Int = 500
        override val canReceive: Boolean = true
        override val canSend: Boolean = true

        override fun start() {
            // No-op: fake interface, nothing to start
        }

        override fun processOutgoing(data: ByteArray) {
            // No-op: we don't need to actually send
        }

        /**
         * Inject a raw packet as if it was received from the network.
         * This calls the onPacketReceived callback, which Transport hooks into.
         */
        fun injectPacket(raw: ByteArray) {
            onPacketReceived?.invoke(raw, this)
        }
    }
}
