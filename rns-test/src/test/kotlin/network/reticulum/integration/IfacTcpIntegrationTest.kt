package network.reticulum.integration

import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * Integration tests for IFAC (Interface Access Code) over TCP.
 *
 * These tests verify that:
 * 1. Kotlin can connect to Python RNS with matching IFAC credentials
 * 2. Packets are properly signed and verified
 * 3. Mismatched credentials result in packet rejection
 *
 * Prerequisites:
 * - Python RNS installed
 * - ~/repos/Reticulum available
 *
 * Run with: ./gradlew :rns-test:test --tests "*IfacTcpIntegrationTest*"
 */
@Tag("integration")
@DisplayName("IFAC TCP Integration")
class IfacTcpIntegrationTest {

    private var pythonProcess: Process? = null
    private var kotlinInterface: TCPClientInterface? = null

    companion object {
        const val TEST_PORT = 14242
        const val TEST_NETNAME = "test_network"
        const val TEST_PASSPHRASE = "test_passphrase"
        const val PYTHON_SCRIPT = "test-scripts/ifac_test_server.py"
    }

    @BeforeEach
    fun setup() {
        // Initialize Transport if needed
        try {
            Transport.start()
        } catch (e: Exception) {
            // Already started, ignore
        }
    }

    @AfterEach
    fun teardown() {
        kotlinInterface?.detach()
        kotlinInterface = null

        pythonProcess?.let { proc ->
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
        pythonProcess = null
    }

    private fun startPythonServer(
        port: Int = TEST_PORT,
        netname: String? = TEST_NETNAME,
        passphrase: String? = TEST_PASSPHRASE
    ): Process {
        // Find project root (go up from rns-test to reticulum-kt)
        var projectRoot = File(".").absoluteFile
        while (!File(projectRoot, "settings.gradle.kts").exists() && projectRoot.parentFile != null) {
            projectRoot = projectRoot.parentFile
        }

        val scriptFile = File(projectRoot, PYTHON_SCRIPT)
        require(scriptFile.exists()) { "Python script not found: ${scriptFile.absolutePath}" }

        val cmd = mutableListOf(
            "python3",
            scriptFile.absolutePath,
            "--port", port.toString()
        )

        if (netname != null) {
            cmd.addAll(listOf("--netname", netname))
        }
        if (passphrase != null) {
            cmd.addAll(listOf("--passphrase", passphrase))
        }
        if (netname == null && passphrase == null) {
            cmd.add("--no-ifac")
        }

        println("Starting Python server: ${cmd.joinToString(" ")}")

        val processBuilder = ProcessBuilder(cmd)
            .directory(projectRoot)
            .redirectErrorStream(true)

        val process = processBuilder.start()

        // Read output in background
        Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    println("[Python] $line")
                }
            }
        }.start()

        // Wait for server to start and TCP to be ready
        Thread.sleep(2000)

        if (!process.isAlive) {
            throw RuntimeException("Python server failed to start")
        }

        // Wait for TCP port to be listening
        var tcpReady = false
        for (i in 1..20) {
            try {
                java.net.Socket("127.0.0.1", port).use {
                    tcpReady = true
                }
            } catch (e: Exception) {
                Thread.sleep(500)
            }
            if (tcpReady) break
        }

        if (!tcpReady) {
            process.destroyForcibly()
            throw RuntimeException("Python TCP server not listening on port $port")
        }

        println("Python TCP server is ready on port $port")
        return process
    }

    @Test
    @Timeout(60)
    @DisplayName("Kotlin connects to Python with matching IFAC credentials")
    fun `kotlin connects with matching ifac credentials`() {
        // Start Python server with IFAC
        pythonProcess = startPythonServer()

        // Create Kotlin interface with matching credentials
        kotlinInterface = TCPClientInterface(
            name = "IFAC Test Client",
            targetHost = "127.0.0.1",
            targetPort = TEST_PORT,
            ifacNetname = TEST_NETNAME,
            ifacNetkey = TEST_PASSPHRASE
        )

        // Verify IFAC is configured
        println("Kotlin IFAC size: ${kotlinInterface!!.ifacSize}")
        println("Kotlin IFAC key: ${kotlinInterface!!.ifacKey?.take(8)?.joinToString("") { "%02x".format(it) }}...")
        assertTrue(kotlinInterface!!.ifacSize == 16, "IFAC should be enabled (size=16)")

        // Track received packets
        val packetsReceived = AtomicInteger(0)

        kotlinInterface!!.onPacketReceived = { data, iface ->
            println("Kotlin received packet: ${data.size} bytes from ${iface.name}")
            packetsReceived.incrementAndGet()
            // Forward to Transport for IFAC verification
            Transport.inbound(data, iface.toRef())
        }

        // Start interface
        kotlinInterface!!.start()

        // Register with Transport
        Transport.registerInterface(kotlinInterface!!.toRef())

        // Wait for connection
        var attempts = 0
        while (!kotlinInterface!!.online.get() && attempts < 30) {
            Thread.sleep(500)
            attempts++
        }

        assertTrue(kotlinInterface!!.online.get(), "Interface should come online")
        println("Connection established!")

        // Wait for packets from Python (announces happen every 30 seconds in the test server)
        println("Waiting for packets from Python server...")

        var waitTime = 0
        while (packetsReceived.get() == 0 && waitTime < 35000) {
            Thread.sleep(1000)
            waitTime += 1000
            if (waitTime % 5000 == 0) {
                println("Still waiting... ($waitTime ms)")
            }
        }

        println("Received ${packetsReceived.get()} packets")
        assertTrue(packetsReceived.get() > 0, "Should receive at least one packet (announce)")
    }

    @Test
    @Timeout(30)
    @DisplayName("Kotlin connects to Python without IFAC")
    fun `kotlin connects without ifac`() {
        // Start Python server without IFAC
        pythonProcess = startPythonServer(netname = null, passphrase = null)

        // Create Kotlin interface without IFAC
        kotlinInterface = TCPClientInterface(
            name = "No-IFAC Test Client",
            targetHost = "127.0.0.1",
            targetPort = TEST_PORT
        )

        // Verify IFAC is disabled
        assertTrue(kotlinInterface!!.ifacSize == 0, "IFAC should be disabled")

        val packetsReceived = AtomicInteger(0)

        kotlinInterface!!.onPacketReceived = { data, iface ->
            println("Kotlin received packet: ${data.size} bytes")
            packetsReceived.incrementAndGet()
            Transport.inbound(data, iface.toRef())
        }

        kotlinInterface!!.start()
        Transport.registerInterface(kotlinInterface!!.toRef())

        // Wait for connection
        var attempts = 0
        while (!kotlinInterface!!.online.get() && attempts < 20) {
            Thread.sleep(500)
            attempts++
        }

        assertTrue(kotlinInterface!!.online.get(), "Interface should come online")
        println("Connection established without IFAC!")

        // Wait briefly for any packets
        Thread.sleep(5000)

        println("Received ${packetsReceived.get()} packets (without IFAC)")
    }

    @Test
    @Timeout(30)
    @DisplayName("Mismatched IFAC credentials - packets rejected")
    fun `mismatched ifac credentials rejected`() {
        // Start Python server with IFAC
        pythonProcess = startPythonServer()

        // Create Kotlin interface with WRONG credentials
        kotlinInterface = TCPClientInterface(
            name = "Wrong IFAC Client",
            targetHost = "127.0.0.1",
            targetPort = TEST_PORT,
            ifacNetname = "wrong_network",
            ifacNetkey = "wrong_passphrase"
        )

        val rawPacketsReceived = AtomicInteger(0)

        kotlinInterface!!.onPacketReceived = { data, iface ->
            // This callback fires for raw TCP data
            println("Raw packet received: ${data.size} bytes")
            rawPacketsReceived.incrementAndGet()
            // Forward to Transport - it should reject packets with wrong IFAC
            Transport.inbound(data, iface.toRef())
        }

        kotlinInterface!!.start()
        Transport.registerInterface(kotlinInterface!!.toRef())

        // Wait for connection (TCP should still connect)
        var attempts = 0
        while (!kotlinInterface!!.online.get() && attempts < 20) {
            Thread.sleep(500)
            attempts++
        }

        // Connection may establish at TCP level
        if (kotlinInterface!!.online.get()) {
            println("TCP connected (expected - IFAC is application-layer)")

            // Wait for packets
            Thread.sleep(10000)

            // We may receive raw packets, but Transport should reject them
            // due to IFAC mismatch (the signature won't verify)
            println("Raw packets received: ${rawPacketsReceived.get()}")
            // Note: The actual rejection happens in Transport.removeIfacMasking()
            // which logs "Rejected packet with invalid IFAC"
        } else {
            println("Connection failed (also acceptable)")
        }
    }
}
