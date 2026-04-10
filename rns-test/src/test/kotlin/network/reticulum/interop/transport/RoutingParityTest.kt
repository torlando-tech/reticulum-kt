package network.reticulum.interop.transport

import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.pipe.PipeInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Routing parity tests using PipeInterface to connect Kotlin and Python
 * Transport instances without TCP.
 *
 * Architecture:
 * ```
 *   Kotlin JVM Process                    Python Subprocess
 *   ┌───────────────────┐    stdin/stdout  ┌──────────────────┐
 *   │ Transport          │◄══ HDLC frames ══►│ Transport         │
 *   │ PipeInterface      │                   │ StdioPipeInterface│
 *   │ test assertions    │    stderr (JSON)  │ status emitter    │
 *   │                    │◄── control msgs ──│                   │
 *   └───────────────────┘                   └──────────────────┘
 * ```
 *
 * Python side emits JSON status messages on stderr:
 *   {"type": "ready", ...}
 *   {"type": "announced", "destination_hash": "...", ...}
 *   {"type": "path_table", "entries": [...]}
 *   {"type": "announce_received", ...}
 */
@DisplayName("Routing Parity Tests (PipeInterface)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingParityTest {

    private var pythonProcess: Process? = null
    private var stderrReader: BufferedReader? = null
    private var pipeInterface: PipeInterface? = null
    private var configDir: File? = null

    // Python-side state parsed from stderr
    private var pythonIdentityHash: String? = null
    private var pythonDestHash: String? = null
    private var pythonIdentityPublicKey: String? = null

    // Collected stderr messages
    private val stderrMessages = mutableListOf<Map<String, Any?>>()
    private val stderrLock = Object()

    @BeforeAll
    fun setup() {
        // 1. Find paths
        val rnsPath = findReticulumPath()
            ?: throw IllegalStateException("Cannot find Reticulum repo. Set PYTHON_RNS_PATH env var.")
        val pipePeerScript = findPipePeerScript()
            ?: throw IllegalStateException("Cannot find pipe_peer.py")

        // 2. Start Kotlin Reticulum
        configDir = Files.createTempDirectory("rns-pipe-parity-test-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = false
        )

        // 3. Spawn Python pipe peer
        val processBuilder = ProcessBuilder("python3", pipePeerScript.absolutePath)
        processBuilder.environment()["PYTHON_RNS_PATH"] = rnsPath
        processBuilder.environment()["PIPE_PEER_ACTION"] = "announce"
        processBuilder.environment()["PIPE_PEER_APP_NAME"] = "pipetest"
        processBuilder.environment()["PIPE_PEER_ASPECTS"] = "routing"

        pythonProcess = processBuilder.start()

        // 4. Create PipeInterface connected to Python's stdin/stdout
        pipeInterface = PipeInterface(
            name = "PythonPipe",
            inputStream = pythonProcess!!.inputStream,
            outputStream = pythonProcess!!.outputStream
        )

        // 5. Register with Transport and start
        Transport.registerInterface(pipeInterface!!.toRef())
        pipeInterface!!.start()

        // 6. Start background stderr reader
        stderrReader = BufferedReader(InputStreamReader(pythonProcess!!.errorStream))
        Thread({
            readStderrLoop()
        }, "PipePeer-stderr").apply { isDaemon = true }.start()

        // 7. Wait for Python "ready" message
        val ready = waitForMessage("ready", timeoutMs = 15_000)
        assertNotNull(ready, "Python pipe peer should emit 'ready' message")
        pythonIdentityHash = ready["identity_hash"] as? String
        println("  [Setup] Python ready, identity: $pythonIdentityHash")

        // 8. Wait for Python "announced" message
        val announced = waitForMessage("announced", timeoutMs = 10_000)
        assertNotNull(announced, "Python pipe peer should announce a destination")
        pythonDestHash = announced["destination_hash"] as? String
        pythonIdentityPublicKey = announced["identity_public_key"] as? String
        println("  [Setup] Python announced dest: $pythonDestHash")

        // 9. Wait for announce to propagate through pipe to Kotlin
        println("  [Setup] Waiting for announce propagation...")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val destBytes = pythonDestHash!!.hexToByteArray()
            if (Transport.hasPath(destBytes)) {
                println("  [Setup] Kotlin learned path to Python destination")
                break
            }
            Thread.sleep(200)
        }

        println("  [Setup] Routing parity test infrastructure ready")
    }

    @AfterAll
    fun teardown() {
        println("  [Teardown] Stopping pipe interface...")
        pipeInterface?.detach()

        println("  [Teardown] Stopping Kotlin Reticulum...")
        Reticulum.stop()

        println("  [Teardown] Destroying Python process...")
        pythonProcess?.let { process ->
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }

        try {
            stderrReader?.close()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }

        println("  [Teardown] Cleaning up temp dir...")
        configDir?.deleteRecursively()

        println("  [Teardown] Complete")
    }

    // ─── Tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Kotlin learns path from Python announce via pipe")
    @Timeout(30)
    fun `kotlin learns path from python announce via pipe`() {
        val destBytes = pythonDestHash!!.hexToByteArray()

        assertTrue(Transport.hasPath(destBytes),
            "Kotlin should have a path to Python destination $pythonDestHash")

        val hops = Transport.hopsTo(destBytes)
        assertNotNull(hops, "hopsTo should return a value for known path")
        assertEquals(1, hops, "Direct pipe connection should be 1 hop")

        println("  [Test] Path learned: dest=$pythonDestHash, hops=$hops")
    }

    @Test
    @DisplayName("path table entry has correct structure")
    @Timeout(30)
    fun `path table entry has correct structure`() {
        val destBytes = pythonDestHash!!.hexToByteArray()
        val entry = Transport.pathTable[destBytes.toKey()]
        assertNotNull(entry, "Path table should contain entry for Python destination")

        // Verify path entry fields
        assertEquals(1, entry.hops, "Hops should be 1 for direct pipe")
        assertTrue(entry.timestamp > 0, "Timestamp should be set")
        assertTrue(!entry.isExpired(), "Path should not be expired")
        assertTrue(entry.randomBlobs.isNotEmpty(), "Random blobs should be present (from announce)")

        println("  [Test] Path entry: hops=${entry.hops}, nextHop=${entry.nextHop.toHexString()}")
    }

    @Test
    @DisplayName("Python learns path from Kotlin announce via pipe")
    @Timeout(30)
    fun `python learns path from kotlin announce via pipe`() {
        // Create Kotlin destination and announce it
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "pipetest",
            aspects = arrayOf("kotlin")
        )

        destination.announce()
        val kotlinDestHash = destination.hash.toHexString()
        println("  [Test] Kotlin announced dest: $kotlinDestHash")

        // Wait for Python to report receiving the announce
        val announceMsg = waitForMessage("announce_received", timeoutMs = 15_000) { msg ->
            (msg["destination_hash"] as? String) == kotlinDestHash
        }

        assertNotNull(announceMsg,
            "Python should receive Kotlin's announce via pipe")

        println("  [Test] Python received announce: ${announceMsg["destination_hash"]}")

        // Wait for Python's path table to include our destination
        val pathMsg = waitForMessage("path_table", timeoutMs = 10_000) { msg ->
            @Suppress("UNCHECKED_CAST")
            val entries = msg["entries"] as? List<Map<String, Any?>> ?: emptyList()
            entries.any { (it["destination_hash"] as? String) == kotlinDestHash }
        }

        assertNotNull(pathMsg,
            "Python's path table should contain Kotlin destination")

        @Suppress("UNCHECKED_CAST")
        val entries = pathMsg["entries"] as List<Map<String, Any?>>
        val kotlinEntry = entries.first { (it["destination_hash"] as? String) == kotlinDestHash }

        // Python should see 1 hop (direct pipe)
        val pythonHops = (kotlinEntry["hops"] as? Number)?.toInt()
        assertEquals(1, pythonHops,
            "Python should see 1 hop to Kotlin destination over pipe")

        println("  [Test] Python path table entry: hops=$pythonHops")
    }

    @Test
    @DisplayName("bidirectional announce produces matching hop counts")
    @Timeout(30)
    fun `bidirectional announce produces matching hop counts`() {
        // Python already announced in setup — verify Kotlin's hop count
        val pythonDest = pythonDestHash!!.hexToByteArray()
        val kotlinHops = Transport.hopsTo(pythonDest)
        assertNotNull(kotlinHops)

        // Create and announce Kotlin destination
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "pipetest",
            aspects = arrayOf("bidir")
        )
        destination.announce()
        val kotlinDestHash = destination.hash.toHexString()

        // Wait for Python to learn the path
        val pathMsg = waitForMessage("path_table", timeoutMs = 15_000) { msg ->
            @Suppress("UNCHECKED_CAST")
            val entries = msg["entries"] as? List<Map<String, Any?>> ?: emptyList()
            entries.any { (it["destination_hash"] as? String) == kotlinDestHash }
        }
        assertNotNull(pathMsg)

        @Suppress("UNCHECKED_CAST")
        val entries = pathMsg["entries"] as List<Map<String, Any?>>
        val pythonEntry = entries.first { (it["destination_hash"] as? String) == kotlinDestHash }
        val pythonHops = (pythonEntry["hops"] as? Number)?.toInt()

        // Both sides should agree: 1 hop over direct pipe
        assertEquals(kotlinHops, pythonHops,
            "Kotlin ($kotlinHops hops) and Python ($pythonHops hops) should agree on hop count")

        println("  [Test] Hop count parity: Kotlin=$kotlinHops, Python=$pythonHops")
    }

    @Test
    @DisplayName("identity can be recalled from announce")
    @Timeout(30)
    fun `identity can be recalled from announce`() {
        val destBytes = pythonDestHash!!.hexToByteArray()

        val recalled = Identity.recall(destBytes)
        assertNotNull(recalled, "Should be able to recall identity from Python announce")

        // The recalled identity's public key should match what Python told us
        if (pythonIdentityPublicKey != null) {
            val expectedPubKey = pythonIdentityPublicKey!!.hexToByteArray()
            assertTrue(
                recalled.getPublicKey().contentEquals(expectedPubKey),
                "Recalled identity public key should match Python's"
            )
        }

        println("  [Test] Identity recalled: hash=${recalled.hash.toHexString()}")
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun readStderrLoop() {
        try {
            val reader = stderrReader ?: return
            while (true) {
                val line = reader.readLine() ?: break
                try {
                    @Suppress("UNCHECKED_CAST")
                    val msg = jsonParse(line) as? Map<String, Any?> ?: continue
                    println("  [stderr] ${msg["type"]}: ${msg.entries.filter { it.key != "type" }.joinToString()}")
                    synchronized(stderrLock) {
                        stderrMessages.add(msg)
                        stderrLock.notifyAll()
                    }
                } catch (e: Exception) {
                    println("  [stderr-raw] $line")
                }
            }
        } catch (_: Exception) {
            // Stream closed
        }
    }

    /**
     * Wait for a message of the given type on stderr.
     * Optionally filter with a predicate.
     */
    private fun waitForMessage(
        type: String,
        timeoutMs: Long = 10_000,
        filter: ((Map<String, Any?>) -> Boolean)? = null
    ): Map<String, Any?>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(stderrLock) {
            while (System.currentTimeMillis() < deadline) {
                // Check existing messages
                val match = stderrMessages.firstOrNull { msg ->
                    msg["type"] == type && (filter == null || filter(msg))
                }
                if (match != null) {
                    stderrMessages.remove(match)
                    return match
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    stderrLock.wait(remaining.coerceAtMost(500))
                }
            }
        }
        return null
    }

    private fun findReticulumPath(): String? {
        val envPath = System.getenv("PYTHON_RNS_PATH")
        if (envPath != null) return envPath

        val userHome = System.getProperty("user.home")
        val candidates = listOf(
            File(userHome, "repos/Reticulum"),
            File(userHome, "repos/public/Reticulum"),
        )
        return candidates.find { it.exists() && File(it, "RNS").exists() }?.absolutePath
    }

    private fun findPipePeerScript(): File? {
        val candidates = listOf(
            File("python-bridge/pipe_peer.py"),
            File("../python-bridge/pipe_peer.py"),
        )

        // Also walk up from cwd
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "python-bridge/pipe_peer.py")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }

        return candidates.find { it.exists() }
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Minimal JSON parser for stderr messages.
     * Uses kotlinx.serialization's Json to avoid adding dependencies.
     */
    private fun jsonParse(json: String): Any? {
        // Use the built-in Kotlin JSON parsing available via kotlinx
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        return jsonElementToAny(element)
    }

    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content == "null" -> null
                    element.content.contains('.') -> element.content.toDouble()
                    else -> {
                        element.content.toLongOrNull() ?: element.content
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray ->
                element.map { jsonElementToAny(it) }
            is kotlinx.serialization.json.JsonObject ->
                element.map { (k, v) -> k to jsonElementToAny(v) }.toMap()
            else -> null
        }
    }
}
