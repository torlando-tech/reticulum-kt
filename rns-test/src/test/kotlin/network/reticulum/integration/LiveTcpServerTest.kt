package network.reticulum.integration

import network.reticulum.Reticulum
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Live integration test against an external Reticulum TCP server.
 *
 * Set environment variable RNS_LIVE_HOST=10.0.4.63 (or any host) to enable.
 * Default port: 4243.
 *
 * This test connects the full Kotlin Reticulum stack to a real server and
 * verifies TCP transport, announce reception, and path discovery.
 */
@DisplayName("Live TCP Server Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RNS_LIVE_HOST", matches = ".+")
class LiveTcpServerTest {

    private var configDir: File? = null
    private var tcpClient: TCPClientInterface? = null
    private val discoveredPaths = CopyOnWriteArrayList<String>()

    private val host: String get() = System.getenv("RNS_LIVE_HOST") ?: "10.0.4.63"
    private val port: Int get() = System.getenv("RNS_LIVE_PORT")?.toIntOrNull() ?: 4243

    @BeforeAll
    fun setup() {
        println("  [Setup] Connecting to live RNS server at $host:$port...")

        configDir = Files.createTempDirectory("rns-live-test-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = false
        )

        Transport.registerAnnounceHandler { destHash, _, _ ->
            val hex = destHash.joinToString("") { "%02x".format(it) }
            println("  [Announce] Discovered destination: $hex")
            discoveredPaths.add(hex)
            true
        }

        tcpClient = TCPClientInterface(
            name = "LiveTest",
            targetHost = host,
            targetPort = port
        )
        Transport.registerInterface(tcpClient!!.toRef())
        tcpClient!!.start()

        // Wait for TCP connection
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (tcpClient!!.online.get()) break
            Thread.sleep(100)
        }

        assertTrue(tcpClient!!.online.get(), "Should connect to $host:$port")
        println("  [Setup] Connected to live server")
    }

    @AfterAll
    fun teardown() {
        tcpClient?.detach()
        Reticulum.stop()
        configDir?.deleteRecursively()
    }

    @Test
    @DisplayName("TCP connection stays online")
    @Timeout(10)
    fun `tcp connection stays online`() {
        assertTrue(tcpClient!!.online.get(), "TCP connection should be online")
        Thread.sleep(2000)
        assertTrue(tcpClient!!.online.get(), "TCP connection should stay online after 2s")
        println("  [Test] TCP connection stable")
    }

    @Test
    @DisplayName("Receives announces from network")
    @Timeout(30)
    fun `receives announces from network`() {
        // Wait up to 25 seconds for at least one announce
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (discoveredPaths.isNotEmpty()) break
            Thread.sleep(500)
        }

        println("  [Test] Discovered ${discoveredPaths.size} paths: ${discoveredPaths.take(5)}")
        assertTrue(discoveredPaths.isNotEmpty(),
            "Should discover at least one destination from the live network")
    }

    @Test
    @DisplayName("TX/RX byte counters increase")
    @Timeout(15)
    fun `tx rx byte counters increase`() {
        Thread.sleep(5000)
        val rx = tcpClient!!.rxBytes.get()
        val tx = tcpClient!!.txBytes.get()
        println("  [Test] TX: $tx bytes, RX: $rx bytes")
        assertTrue(rx > 0 || tx > 0, "Should have some traffic with the live server")
    }
}
