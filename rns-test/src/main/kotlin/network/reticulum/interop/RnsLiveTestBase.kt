package network.reticulum.interop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * Base class for live Reticulum protocol E2E tests.
 *
 * Sets up a Kotlin Reticulum instance connected to a Python Reticulum
 * instance over TCP. No LXMF — purely Reticulum-level testing for
 * links, resources, and ratchets.
 *
 * Python side:
 *   - Starts RNS with TCP server via [rns_start]
 *   - Creates a destination via [rns_create_destination]
 *   - Announces the destination
 *
 * Kotlin side:
 *   - Starts Reticulum with temp config dir
 *   - Creates TCP client interface to Python
 *   - Registers Python identity for later recall
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RnsLiveTestBase : InteropTestBase() {

    // Python-side state
    protected var pythonDestHash: ByteArray? = null
    protected var pythonIdentityHash: ByteArray? = null
    protected var pythonIdentityPublicKey: ByteArray? = null

    // Kotlin-side state
    protected var kotlinTcpClient: TCPClientInterface? = null
    protected var configDir: File? = null

    // App name/aspects for the test destination
    protected open val appName: String = "testapp"
    protected open val aspects: Array<String> = arrayOf("link", "test")

    // TCP port for testing
    protected val tcpPort: Int = 15242 + (System.currentTimeMillis() % 1000).toInt()

    @BeforeAll
    open fun setupLiveRns() {
        // 1. Start Python Reticulum with TCP server
        println("  [Setup] Starting Python RNS on port $tcpPort...")
        val rnsResult = python("rns_start", "tcp_port" to tcpPort)
        val ready = rnsResult.getString("ready")
        require(ready == "true") { "Python RNS failed to start: $rnsResult" }
        println("  [Setup] Python RNS started successfully")

        // 2. Create Python destination for link testing
        println("  [Setup] Creating Python destination ($appName/${aspects.joinToString(".")})...")
        val destResult = python(
            "rns_create_destination",
            "app_name" to appName,
            "aspects" to aspects.toList()
        )
        pythonDestHash = destResult.getBytes("destination_hash")
        pythonIdentityHash = destResult.getBytes("identity_hash")
        pythonIdentityPublicKey = destResult.getBytes("identity_public_key")
        println("  [Setup] Python destination created: ${pythonDestHash?.toHex()}")

        // 3. Start Kotlin Reticulum
        println("  [Setup] Starting Kotlin Reticulum...")
        configDir = Files.createTempDirectory("reticulum-e2e-test-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = true
        )
        println("  [Setup] Kotlin Reticulum started")

        // 4. Create TCP client interface to Python
        println("  [Setup] Creating TCP client to Python on 127.0.0.1:$tcpPort...")
        kotlinTcpClient = TCPClientInterface(
            name = "E2E Test Client",
            targetHost = "127.0.0.1",
            targetPort = tcpPort
        )

        // 5. Register interface with Transport
        val interfaceRef = kotlinTcpClient!!.toRef()
        Transport.registerInterface(interfaceRef)
        kotlinTcpClient!!.start()
        println("  [Setup] TCP client interface registered and started")

        // 6. Wait for TCP connection to establish
        println("  [Setup] Waiting for TCP connection...")
        val connectionDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < connectionDeadline) {
            if (kotlinTcpClient!!.online.get()) {
                println("  [Setup] TCP connection established")
                break
            }
            Thread.sleep(100)
        }
        require(kotlinTcpClient!!.online.get()) {
            "TCP connection to Python not established within 10 seconds"
        }

        // 7. Register Python's identity with Kotlin
        if (pythonDestHash != null && pythonIdentityPublicKey != null) {
            println("  [Setup] Registering Python identity with Kotlin...")
            Identity.remember(
                packetHash = pythonDestHash!!,
                destHash = pythonDestHash!!,
                publicKey = pythonIdentityPublicKey!!,
                appData = null
            )
            println("  [Setup] Python identity registered")
        }

        // 8. Have Python announce its destination so Kotlin learns the path
        println("  [Setup] Having Python announce its destination...")
        val announceResult = python("rns_announce_destination")
        println("  [Setup] Python announced: ${announceResult.getString("announced")}")

        // Wait for announce propagation
        println("  [Setup] Waiting for network to stabilize...")
        Thread.sleep(2000)

        println("  [Setup] RNS live test infrastructure ready")
    }

    @AfterAll
    fun teardownLiveRns() {
        println("  [Teardown] Stopping TCP client...")
        kotlinTcpClient?.detach()

        println("  [Teardown] Stopping Kotlin Reticulum...")
        Reticulum.stop()

        println("  [Teardown] Cleaning up temp directory...")
        configDir?.deleteRecursively()

        // Stop Python side
        println("  [Teardown] Stopping Python RNS...")
        try {
            python("rns_stop")
        } catch (e: Exception) {
            println("  [Teardown] Warning: rns_stop failed: ${e.message}")
        }

        println("  [Teardown] Complete")
    }

    // ─── Helper methods ───

    /**
     * Create a Kotlin OUT destination targeting the Python destination.
     * Used for initiating links from Kotlin to Python.
     */
    protected fun createPythonOutDestination(): Destination {
        val pythonIdentity = Identity.recall(pythonDestHash!!)
            ?: throw IllegalStateException("Cannot recall Python identity from dest hash")

        return Destination.create(
            identity = pythonIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = aspects
        )
    }

    /**
     * Poll Python bridge until a link is established (status == 'active').
     *
     * @param timeoutMs Maximum time to wait
     * @return The link info JsonObject, or null if timeout
     */
    protected fun waitForPythonLink(timeoutMs: Long = 15_000): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = python("rns_get_established_link")
            val status = result.getString("status")
            if (status == "active") {
                return result
            }
            Thread.sleep(200)
        }
        return null
    }

    /**
     * Get packets received by Python over the link.
     */
    protected fun getPythonPackets(): List<ByteArray> {
        val result = python("rns_link_get_packets")
        val packetsList = result["packets"]
        if (packetsList !is JsonArray) return emptyList()
        return packetsList.map { it.jsonPrimitive.content.hexToByteArray() }
    }

    /**
     * Send data from Python over the established link.
     */
    protected fun sendFromPython(data: ByteArray): Boolean {
        val result = python("rns_link_send", "data" to data)
        return result.getBoolean("sent")
    }

    /**
     * Get resources received by Python over the link.
     */
    protected fun getPythonResources(): List<ReceivedResource> {
        val result = python("rns_resource_get_received")
        val resourcesList = result["resources"]
        if (resourcesList !is JsonArray) return emptyList()

        return resourcesList.map { elem ->
            val obj = elem as JsonObject
            ReceivedResource(
                hash = obj.getString("hash"),
                data = obj.getString("data").hexToByteArray(),
                size = obj["size"]?.jsonPrimitive?.content?.toInt() ?: 0,
            )
        }
    }

    /**
     * Data class for a received resource from Python.
     */
    data class ReceivedResource(
        val hash: String,
        val data: ByteArray,
        val size: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReceivedResource) return false
            return hash == other.hash && data.contentEquals(other.data) && size == other.size
        }

        override fun hashCode(): Int {
            var result = hash.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + size
            return result
        }
    }

    /**
     * Helper extension to convert ByteArray to hex string.
     */
    protected fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
