package network.reticulum.integration

import network.reticulum.interfaces.tcp.TCPClientInterface
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal Python TCP interop tests.
 *
 * Tests Kotlin TCPClientInterface against a minimal Python TCP server
 * that uses RNS-compatible HDLC framing. Bypasses complex bridge infrastructure
 * to isolate TCP transport layer issues.
 *
 * Enable debug logging with: -Dreticulum.tcp.debug=true
 */
@DisplayName("TCP Python Interop Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpPythonInteropTest {

    companion object {
        private const val PYTHON_SCRIPT = "python_tcp_test_server.py"
        private const val CONNECTION_TIMEOUT_MS = 5000
        private const val PACKET_TIMEOUT_SECONDS = 10L
    }

    private var pythonProcess: Process? = null
    private var pythonStdin: BufferedWriter? = null
    private var pythonStdout: BufferedReader? = null
    private var pythonStderr: BufferedReader? = null
    private var testPort: Int = 0
    private var client: TCPClientInterface? = null
    private val receivedPackets = CopyOnWriteArrayList<ByteArray>()
    private val pythonReady = AtomicBoolean(false)
    private var stdoutReaderThread: Thread? = null
    private val pythonOutputLines = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun setup() {
        // Enable debug logging for diagnostics
        System.setProperty("reticulum.tcp.debug", "true")

        // Find available port
        testPort = 14000 + (Math.random() * 1000).toInt()

        // Get path to Python script (in test resources)
        val scriptUrl = javaClass.classLoader.getResource(PYTHON_SCRIPT)
        if (scriptUrl == null) {
            println("WARNING: Python script not found in resources, tests will be skipped")
            return
        }
        val scriptPath = scriptUrl.path

        // Check if Python is available
        if (!isPythonAvailable()) {
            println("WARNING: Python not available, tests will be skipped")
            return
        }

        // Start Python process
        try {
            val processBuilder = ProcessBuilder("python3", scriptPath, testPort.toString())
            processBuilder.redirectErrorStream(false)
            pythonProcess = processBuilder.start()

            pythonStdin = BufferedWriter(OutputStreamWriter(pythonProcess!!.outputStream))
            pythonStdout = BufferedReader(InputStreamReader(pythonProcess!!.inputStream))
            pythonStderr = BufferedReader(InputStreamReader(pythonProcess!!.errorStream))

            // Start thread to read stdout
            stdoutReaderThread = Thread {
                try {
                    var line: String?
                    while (pythonStdout?.readLine().also { line = it } != null) {
                        val outputLine = line!!
                        pythonOutputLines.add(outputLine)
                        println("[Python] $outputLine")

                        if (outputLine == "READY") {
                            pythonReady.set(true)
                        }
                    }
                } catch (e: Exception) {
                    if (pythonProcess?.isAlive == true) {
                        println("[Python stdout] Error: ${e.message}")
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            // Start thread to read stderr
            Thread {
                try {
                    var line: String?
                    while (pythonStderr?.readLine().also { line = it } != null) {
                        println("[Python stderr] $line")
                    }
                } catch (e: Exception) {
                    if (pythonProcess?.isAlive == true) {
                        println("[Python stderr] Error: ${e.message}")
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            // Wait for Python server to be ready
            val startTime = System.currentTimeMillis()
            while (!pythonReady.get() && System.currentTimeMillis() - startTime < 5000) {
                Thread.sleep(100)
            }

            if (!pythonReady.get()) {
                println("WARNING: Python server did not become ready within 5 seconds")
                stopPython()
                return
            }

            println("Python TCP server ready on port $testPort")

        } catch (e: Exception) {
            println("WARNING: Failed to start Python process: ${e.message}")
            pythonProcess = null
        }
    }

    @AfterAll
    fun teardown() {
        stopClient()
        stopPython()
    }

    private fun stopClient() {
        try {
            client?.detach()
        } catch (e: Exception) {
            // Ignore
        }
        client = null
        receivedPackets.clear()
    }

    private fun stopPython() {
        try {
            pythonStdin?.write("QUIT\n")
            pythonStdin?.flush()
        } catch (e: Exception) {
            // Ignore
        }

        try {
            pythonProcess?.waitFor(2, TimeUnit.SECONDS)
            pythonProcess?.destroyForcibly()
        } catch (e: Exception) {
            // Ignore
        }

        pythonProcess = null
        pythonStdin = null
        pythonStdout = null
        pythonReady.set(false)
    }

    private fun isPythonAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("python3", "--version").start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isPythonServerRunning(): Boolean {
        return pythonProcess?.isAlive == true && pythonReady.get()
    }

    private fun connectClient(): TCPClientInterface {
        stopClient()

        val newClient = TCPClientInterface(
            name = "test-client",
            targetHost = "127.0.0.1",
            targetPort = testPort,
            connectTimeoutMs = CONNECTION_TIMEOUT_MS
        )

        newClient.onPacketReceived = { data, _ ->
            receivedPackets.add(data)
            println("[Kotlin] Received packet: ${data.size} bytes, hex=${data.toHex()}")
        }

        newClient.start()

        // Wait for connection
        val startTime = System.currentTimeMillis()
        while (!newClient.online.get() && System.currentTimeMillis() - startTime < CONNECTION_TIMEOUT_MS) {
            Thread.sleep(50)
        }

        client = newClient
        return newClient
    }

    private fun sendToPython(command: String) {
        pythonStdin?.write("$command\n")
        pythonStdin?.flush()
    }

    private fun waitForPythonReceived(expectedHex: String, timeoutSeconds: Long = PACKET_TIMEOUT_SECONDS): Boolean {
        val expectedLine = "RECEIVED: $expectedHex"
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (pythonOutputLines.any { it == expectedLine }) {
                return true
            }
            Thread.sleep(50)
        }
        return false
    }

    private fun waitForPythonSent(expectedHex: String, timeoutSeconds: Long = PACKET_TIMEOUT_SECONDS): Boolean {
        val expectedLine = "SENT: $expectedHex"
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (pythonOutputLines.any { it == expectedLine }) {
                return true
            }
            Thread.sleep(50)
        }
        return false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ===========================================
    // Tests
    // ===========================================

    @Test
    @DisplayName("Test 1: Connection holds for 5 seconds")
    @Timeout(30)
    @EnabledIf("isPythonServerRunning")
    fun `connection holds for 5 seconds`() {
        val client = connectClient()
        assertTrue(client.online.get(), "Client should be online after connect")

        // Wait 5 seconds
        println("Waiting 5 seconds to verify connection stability...")
        Thread.sleep(5000)

        assertTrue(client.online.get(), "Client should still be online after 5 seconds")
        println("Connection held stable for 5 seconds")
    }

    @Test
    @DisplayName("Test 2: Kotlin to Python packet delivery")
    @Timeout(30)
    @EnabledIf("isPythonServerRunning")
    fun `kotlin to python packet delivery`() {
        val client = connectClient()
        assertTrue(client.online.get(), "Client should be online")

        // Clear previous output
        pythonOutputLines.clear()

        // Send a test packet from Kotlin to Python
        // Using 20 bytes to ensure it passes HEADER_MINSIZE check
        val testData = ByteArray(20) { it.toByte() }
        val expectedHex = testData.toHex()

        println("Sending ${testData.size} bytes from Kotlin to Python...")
        client.processOutgoing(testData)

        // Wait for Python to receive
        val received = waitForPythonReceived(expectedHex)
        assertTrue(received, "Python should receive the packet. Output lines: $pythonOutputLines")
        println("Python received packet successfully")
    }

    @Test
    @DisplayName("Test 3: Python to Kotlin packet delivery")
    @Timeout(30)
    @EnabledIf("isPythonServerRunning")
    fun `python to kotlin packet delivery`() {
        val client = connectClient()
        assertTrue(client.online.get(), "Client should be online")

        // Clear received packets
        receivedPackets.clear()
        pythonOutputLines.clear()

        // Send a test packet from Python to Kotlin
        // Using 20 bytes to ensure it passes HEADER_MINSIZE check
        val testData = ByteArray(20) { (it + 0x10).toByte() }
        val hexData = testData.toHex()

        println("Sending ${testData.size} bytes from Python to Kotlin...")
        sendToPython("SEND $hexData")

        // Wait for Python confirmation
        assertTrue(waitForPythonSent(hexData), "Python should confirm send")

        // Wait for Kotlin to receive
        val startTime = System.currentTimeMillis()
        while (receivedPackets.isEmpty() && System.currentTimeMillis() - startTime < PACKET_TIMEOUT_SECONDS * 1000) {
            Thread.sleep(50)
        }

        assertTrue(receivedPackets.isNotEmpty(), "Kotlin should receive the packet")
        assertTrue(testData.contentEquals(receivedPackets[0]), "Received data should match sent data")
        println("Kotlin received packet successfully")
    }

    @Test
    @DisplayName("Test 4: Bidirectional exchange (10 packets each way)")
    @Timeout(60)
    @EnabledIf("isPythonServerRunning")
    fun `bidirectional exchange`() {
        val client = connectClient()
        assertTrue(client.online.get(), "Client should be online")

        // Clear state
        receivedPackets.clear()
        pythonOutputLines.clear()

        val packetCount = 10
        val kotlinToPhytonPackets = mutableListOf<ByteArray>()
        val pythonToKotlinPackets = mutableListOf<ByteArray>()

        // Prepare packets
        for (i in 0 until packetCount) {
            // K->P: 20 bytes starting with 0xAA
            kotlinToPhytonPackets.add(ByteArray(20) { j -> (0xAA + i + j).toByte() })
            // P->K: 20 bytes starting with 0xBB
            pythonToKotlinPackets.add(ByteArray(20) { j -> (0xBB + i + j).toByte() })
        }

        println("Exchanging $packetCount packets each direction...")

        // Send all K->P packets
        for ((i, packet) in kotlinToPhytonPackets.withIndex()) {
            println("K->P packet $i: ${packet.toHex()}")
            client.processOutgoing(packet)
            Thread.sleep(100) // Small delay between packets
        }

        // Send all P->K packets
        for ((i, packet) in pythonToKotlinPackets.withIndex()) {
            val hexData = packet.toHex()
            println("P->K packet $i: $hexData")
            sendToPython("SEND $hexData")
            Thread.sleep(100) // Small delay between packets
        }

        // Wait for all K->P packets to be received by Python
        println("Waiting for Python to receive all packets...")
        for (packet in kotlinToPhytonPackets) {
            val expectedHex = packet.toHex()
            assertTrue(
                waitForPythonReceived(expectedHex, 15),
                "Python should receive packet: $expectedHex"
            )
        }
        println("Python received all $packetCount packets")

        // Wait for all P->K packets to be received by Kotlin
        println("Waiting for Kotlin to receive all packets...")
        val startTime = System.currentTimeMillis()
        while (receivedPackets.size < packetCount && System.currentTimeMillis() - startTime < 15000) {
            Thread.sleep(100)
        }

        assertEquals(packetCount, receivedPackets.size, "Kotlin should receive $packetCount packets")

        // Verify content matches
        for ((i, expected) in pythonToKotlinPackets.withIndex()) {
            val actual = receivedPackets.find { it.contentEquals(expected) }
            assertTrue(actual != null, "Should receive packet $i with content ${expected.toHex()}")
        }
        println("Kotlin received all $packetCount packets")

        println("Bidirectional exchange complete: $packetCount packets each way")
    }
}
