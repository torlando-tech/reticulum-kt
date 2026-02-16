package network.reticulum.interop.transport

import network.reticulum.Reticulum
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getInt
import network.reticulum.interop.getList
import network.reticulum.interop.getString
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for announce forwarding to local clients in a shared instance.
 *
 * Architecture:
 * ```
 *   Python External Node        Kotlin Shared Instance        Python Local Client Reader
 *   (TCP Server)                                              (raw socket)
 *        |                           |                              |
 *        |-- TCP ------------------>|<-- LocalServerInterface ------|
 *        |                          |    (TCP on localhost)         |
 *        |  announce dest A         |                              |
 *        |=========================>|  Transport processes announce |
 *        |                          |  forwards to local clients   |
 *        |                          |==============================>|
 *        |                          |  (HDLC-framed packet)        |
 *        |                          |                              |  read & parse
 *        |                          |                              |  verify headers
 * ```
 *
 * Validates the critical fix: announces forwarded to local clients must be
 * re-packaged with HEADER_2 + TRANSPORT type + the shared instance's identity
 * as transport_id. Without this, local clients would think the destination is
 * directly reachable when it's actually behind a transport node.
 */
@DisplayName("Announce Forwarding E2E Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnounceForwardingE2ETest : InteropTestBase() {

    private var configDir: File? = null
    private var kotlinTcpClient: TCPClientInterface? = null
    private var localServer: LocalServerInterface? = null

    // Ports for test — randomize to avoid conflicts
    private val externalPort: Int = 17242 + (System.currentTimeMillis() % 500).toInt()
    private val sharedPort: Int = externalPort + 500

    // Python destination info
    private var pythonDestHash: String? = null

    // Track whether setup succeeded fully
    private var setupComplete = false

    @BeforeAll
    fun setup() {
        // 1. Start Python external RNS with TCP server
        println("  [Setup] Starting Python RNS on port $externalPort...")
        val rnsResult = python("rns_start", "tcp_port" to externalPort)
        require(rnsResult.getString("ready") == "true") {
            "Python RNS failed to start: $rnsResult"
        }

        // 2. Create Python destination FIRST (before Kotlin connects)
        println("  [Setup] Creating Python destination...")
        val destResult = python(
            "rns_create_destination",
            "app_name" to "announcetest",
            "aspects" to listOf("forward")
        )
        pythonDestHash = destResult.getString("destination_hash")
        println("  [Setup] Python destination: $pythonDestHash")

        // 3. Start Kotlin Reticulum with transport enabled
        println("  [Setup] Starting Kotlin Reticulum with transport...")
        configDir = Files.createTempDirectory("rns-announce-fwd-test-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = true
        )

        // 4. Start Kotlin LocalServerInterface (shared instance)
        // Start this BEFORE the external connection so it's ready for the local client
        println("  [Setup] Starting LocalServerInterface on port $sharedPort...")
        localServer = LocalServerInterface(
            name = "SharedInstance",
            tcpPort = sharedPort
        )
        Transport.registerInterface(localServer!!.toRef())
        localServer!!.start()
        println("  [Setup] LocalServerInterface started")

        // 5. Python bridge connects raw socket as local client
        println("  [Setup] Connecting Python local client reader to port $sharedPort...")
        val connectResult = python(
            "local_client_connect",
            "host" to "127.0.0.1",
            "port" to sharedPort
        )
        require(connectResult.getBoolean("connected")) {
            "Local client failed to connect"
        }

        // Wait for Kotlin to accept the connection
        val clientDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < clientDeadline) {
            if (localServer!!.clientCount() > 0) break
            Thread.sleep(100)
        }
        require(localServer!!.clientCount() > 0) {
            "LocalServerInterface did not accept client connection"
        }
        println("  [Setup] Local client connected (${localServer!!.clientCount()} clients)")

        // 6. Create Kotlin TCPClientInterface to Python (external)
        println("  [Setup] Connecting to Python via TCP on port $externalPort...")
        kotlinTcpClient = TCPClientInterface(
            name = "External",
            targetHost = "127.0.0.1",
            targetPort = externalPort
        )
        Transport.registerInterface(kotlinTcpClient!!.toRef())
        kotlinTcpClient!!.start()

        // Wait for TCP connection
        val tcpDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < tcpDeadline) {
            if (kotlinTcpClient!!.online.get()) break
            Thread.sleep(100)
        }

        if (!kotlinTcpClient!!.online.get()) {
            println("  [Setup] WARNING: TCP to Python did not establish, TCP E2E tests may fail")
            println("  [Setup] (Known issue: tunnel synthesis packets cause Python to drop connection)")
        } else {
            println("  [Setup] TCP external connection established")
            // Disable tunnel synthesis to prevent connection flapping
            kotlinTcpClient!!.wantsTunnel = false
        }

        // 7. Announce Python destination
        println("  [Setup] Having Python announce its destination...")
        python("rns_announce_destination")

        // Wait for announce propagation
        println("  [Setup] Waiting for announce propagation...")
        Thread.sleep(3000)

        setupComplete = true
        println("  [Setup] Infrastructure ready")
    }

    @AfterAll
    fun teardown() {
        println("  [Teardown] Disconnecting local client...")
        try { python("local_client_disconnect") } catch (_: Exception) {}

        println("  [Teardown] Stopping local server...")
        localServer?.detach()

        println("  [Teardown] Stopping TCP client...")
        kotlinTcpClient?.detach()

        println("  [Teardown] Stopping Kotlin Reticulum...")
        Reticulum.stop()

        println("  [Teardown] Cleaning up temp dir...")
        configDir?.deleteRecursively()

        println("  [Teardown] Stopping Python RNS...")
        try { python("rns_stop") } catch (_: Exception) {}

        println("  [Teardown] Complete")
    }

    /**
     * Re-announce and poll until the local client reader has at least one
     * HEADER_2 ANNOUNCE packet. Returns the list of all received packets.
     *
     * This handles timing variability in the announce propagation chain:
     * Python announce → TCP → Kotlin Transport → LocalServerInterface → Python socket reader
     */
    private fun getForwardedAnnouncePackets(maxAttempts: Int = 3): List<String> {
        repeat(maxAttempts) { attempt ->
            // Drain any stale packets first, then re-announce
            python("local_client_read_packets", "timeout_ms" to 100)
            python("rns_announce_destination")
            Thread.sleep(2000)

            val result = python("local_client_read_packets", "timeout_ms" to 5000)
            val packets = result.getList<String>("packets")
            if (packets.isNotEmpty()) {
                // Check if any are HEADER_2 announce packets
                for (packetHex in packets) {
                    val parsed = python("packet_parse_header", "raw" to packetHex)
                    if (parsed.getInt("packet_type") == 1 && parsed.getInt("header_type") == 1) {
                        return packets
                    }
                }
            }
            println("  [Poll] Attempt ${attempt + 1}/$maxAttempts: no announce packets yet")
        }
        return emptyList()
    }

    @Test
    @DisplayName("forwarded announce has HEADER_2 transport headers")
    @Timeout(30)
    fun `forwarded announce has HEADER_2 transport headers`() {
        val packets = getForwardedAnnouncePackets()
        assertTrue(packets.isNotEmpty(), "Local client should receive forwarded announce packets")

        for (packetHex in packets) {
            val parsed = python("packet_parse_header", "raw" to packetHex)
            if (parsed.getInt("packet_type") == 1) { // ANNOUNCE
                assertEquals(1, parsed.getInt("header_type"),
                    "Forwarded announce should use HEADER_2 (transport header)")
                assertEquals(1, parsed.getInt("transport_type"),
                    "Forwarded announce should use TRANSPORT type")
                println("  [Test] Announce found with correct HEADER_2 + TRANSPORT headers")
                return
            }
        }
        assertTrue(false, "Should find at least one ANNOUNCE packet in forwarded data")
    }

    @Test
    @DisplayName("transport_id is Kotlin shared instance identity")
    @Timeout(30)
    fun `transport_id is kotlin shared instance identity`() {
        val packets = getForwardedAnnouncePackets()
        assertTrue(packets.isNotEmpty(), "Local client should receive packets")

        val kotlinTransportHash = Transport.identity?.hash
        assertNotNull(kotlinTransportHash, "Kotlin Transport should have an identity")
        val kotlinTransportHex = kotlinTransportHash.joinToString("") { "%02x".format(it) }

        for (packetHex in packets) {
            val parsed = python("packet_parse_header", "raw" to packetHex)
            if (parsed.getInt("packet_type") == 1 && parsed.getInt("header_type") == 1) {
                val transportId = parsed.getString("transport_id")
                assertEquals(
                    kotlinTransportHex, transportId,
                    "transport_id should match Kotlin Transport's identity hash"
                )
                println("  [Test] transport_id matches: $transportId")
                return
            }
        }
        assertTrue(false, "Should find a HEADER_2 ANNOUNCE packet")
    }

    @Test
    @DisplayName("hop count preserved in forwarded announce")
    @Timeout(30)
    fun `hop count preserved in forwarded announce`() {
        val packets = getForwardedAnnouncePackets()
        assertTrue(packets.isNotEmpty(), "Local client should receive packets")

        for (packetHex in packets) {
            val parsed = python("packet_parse_header", "raw" to packetHex)
            if (parsed.getInt("packet_type") == 1 && parsed.getInt("header_type") == 1) {
                val hops = parsed.getInt("hops")
                // Announce traveled: Python → TCP → Kotlin Transport
                // Then forwarded to local client (hops preserved, not incremented again)
                assertTrue(hops >= 1, "Hop count should be >= 1")
                println("  [Test] Hop count preserved: $hops")
                return
            }
        }
        assertTrue(false, "Should find a HEADER_2 ANNOUNCE packet")
    }

    @Test
    @DisplayName("destination hash matches announced destination")
    @Timeout(30)
    fun `destination hash matches announced destination`() {
        val packets = getForwardedAnnouncePackets()
        assertTrue(packets.isNotEmpty(), "Local client should receive packets")

        for (packetHex in packets) {
            val parsed = python("packet_parse_header", "raw" to packetHex)
            if (parsed.getInt("packet_type") == 1 && parsed.getInt("header_type") == 1) {
                val destHash = parsed.getString("destination_hash")
                assertEquals(
                    pythonDestHash, destHash,
                    "Forwarded announce destination_hash should match Python's destination"
                )
                println("  [Test] Destination hash matches: $destHash")
                return
            }
        }
        assertTrue(false, "Should find a HEADER_2 ANNOUNCE packet")
    }
}
