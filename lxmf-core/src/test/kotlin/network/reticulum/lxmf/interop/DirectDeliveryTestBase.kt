package network.reticulum.lxmf.interop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMRouter
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * Base class for direct delivery interop tests.
 *
 * Manages Kotlin and Python Reticulum instances connected via TCP.
 * Python runs TCP server, Kotlin connects as client.
 *
 * This enables end-to-end testing of LXMF DIRECT delivery method
 * with real Reticulum networking between Kotlin and Python.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DirectDeliveryTestBase : InteropTestBase() {

    // Python-side state
    protected var pythonDestHash: ByteArray? = null
    protected var pythonIdentityHash: ByteArray? = null
    protected var pythonIdentityPublicKey: ByteArray? = null

    // Kotlin-side state
    protected lateinit var kotlinIdentity: Identity
    protected lateinit var kotlinRouter: LXMRouter
    protected lateinit var kotlinDestination: Destination
    protected var kotlinTcpClient: TCPClientInterface? = null
    protected var configDir: File? = null

    // TCP port for testing (use high port with offset to avoid conflicts)
    protected val tcpPort: Int = 14242 + (System.currentTimeMillis() % 1000).toInt()

    @BeforeAll
    fun setupDirectDelivery() {
        // 1. Start Python Reticulum with TCP server
        println("  [Setup] Starting Python RNS on port $tcpPort...")
        val rnsResult = python("rns_start", "tcp_port" to tcpPort)
        val ready = rnsResult.getString("ready")
        require(ready == "true") { "Python RNS failed to start: $rnsResult" }
        println("  [Setup] Python RNS started successfully")

        // 2. Start Python LXMF router
        println("  [Setup] Starting Python LXMF router...")
        val routerResult = python("lxmf_start_router")
        pythonDestHash = routerResult.getBytes("destination_hash")
        pythonIdentityHash = routerResult.getBytes("identity_hash")
        pythonIdentityPublicKey = routerResult.getBytes("identity_public_key")
        println("  [Setup] Python LXMF router started, dest_hash: ${pythonDestHash?.toHex()}")

        // 3. Start Kotlin Reticulum
        println("  [Setup] Starting Kotlin Reticulum...")
        configDir = Files.createTempDirectory("reticulum-test-").toFile()
        Reticulum.start(
            configDir = configDir!!.absolutePath,
            enableTransport = true
        )
        println("  [Setup] Kotlin Reticulum started")

        // 4. Create TCP client interface to Python
        println("  [Setup] Creating TCP client to Python on 127.0.0.1:$tcpPort...")
        kotlinTcpClient = TCPClientInterface(
            name = "Test Client",
            targetHost = "127.0.0.1",
            targetPort = tcpPort
        )

        // 5. Register interface with Transport
        val interfaceRef = kotlinTcpClient!!.toRef()
        Transport.registerInterface(interfaceRef)
        kotlinTcpClient!!.start()
        println("  [Setup] TCP client interface registered and started")

        // 6. Create Kotlin LXMF router and delivery destination
        println("  [Setup] Creating Kotlin LXMF router...")
        kotlinIdentity = Identity.create()
        kotlinRouter = LXMRouter(
            identity = kotlinIdentity,
            storagePath = configDir!!.absolutePath
        )

        // Register delivery identity
        kotlinDestination = kotlinRouter.registerDeliveryIdentity(kotlinIdentity)
        kotlinRouter.start()
        println("  [Setup] Kotlin LXMF router started, dest_hash: ${kotlinDestination.hexHash}")

        // 7. Wait for TCP connection to establish
        println("  [Setup] Waiting for TCP connection to establish...")
        val connectionDeadline = System.currentTimeMillis() + 10000 // 10 second timeout
        while (System.currentTimeMillis() < connectionDeadline) {
            if (kotlinTcpClient!!.online.get()) {
                println("  [Setup] TCP connection established successfully")
                break
            }
            Thread.sleep(100)
        }

        if (!kotlinTcpClient!!.online.get()) {
            println("  [Setup] WARNING: TCP connection not established within timeout")
        }

        // 8. Register Python's identity with Kotlin so we can create destinations
        if (pythonDestHash != null && pythonIdentityPublicKey != null) {
            println("  [Setup] Registering Python identity with Kotlin...")
            Identity.remember(
                packetHash = pythonDestHash!!,  // Use dest hash as packet hash for simplicity
                destHash = pythonDestHash!!,
                publicKey = pythonIdentityPublicKey!!,
                appData = null
            )
            println("  [Setup] Python identity registered")
        }

        // 9. Have Python announce its LXMF destination so Kotlin can learn the path
        println("  [Setup] Having Python announce its LXMF destination...")
        val announceResult = python("lxmf_announce")
        println("  [Setup] Python announced: ${announceResult.getString("announced")}")

        // Wait for announce to propagate and connection to stabilize
        println("  [Setup] Waiting for network to stabilize...")
        Thread.sleep(2000)

        // Re-check connection status
        val finalDeadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < finalDeadline) {
            if (kotlinTcpClient!!.online.get()) {
                break
            }
            Thread.sleep(100)
        }
        println("  [Setup] Final connection status: online=${kotlinTcpClient!!.online.get()}")
        println("  [Setup] Direct delivery infrastructure ready")
    }

    @AfterAll
    fun teardownDirectDelivery() {
        println("  [Teardown] Stopping Kotlin LXMF router...")
        if (::kotlinRouter.isInitialized) {
            kotlinRouter.stop()
        }

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
            // Ignore cleanup errors
            println("  [Teardown] Warning: rns_stop failed: ${e.message}")
        }

        println("  [Teardown] Complete")
    }

    /**
     * Get messages received by Python LXMF router.
     */
    protected fun getPythonMessages(): List<ReceivedMessage> {
        val result = python("lxmf_get_messages")
        val messagesJson = result["messages"] ?: return emptyList()

        return when (messagesJson) {
            is JsonArray -> messagesJson.map { elem ->
                val obj = elem as JsonObject
                ReceivedMessage(
                    sourceHash = obj.getBytes("source_hash"),
                    destinationHash = obj.getBytes("destination_hash"),
                    content = obj.getString("content"),
                    title = obj.getString("title"),
                    timestamp = obj.getString("timestamp").toDouble()
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Clear Python received messages.
     */
    protected fun clearPythonMessages() {
        python("lxmf_clear_messages")
    }

    /**
     * Wait for Python to receive at least N messages.
     */
    protected fun waitForPythonMessages(count: Int, timeoutMs: Long = 10000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (getPythonMessages().size >= count) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Data class representing a received LXMF message.
     */
    data class ReceivedMessage(
        val sourceHash: ByteArray,
        val destinationHash: ByteArray,
        val content: String,
        val title: String,
        val timestamp: Double
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ReceivedMessage

            if (!sourceHash.contentEquals(other.sourceHash)) return false
            if (!destinationHash.contentEquals(other.destinationHash)) return false
            if (content != other.content) return false
            if (title != other.title) return false
            if (timestamp != other.timestamp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = sourceHash.contentHashCode()
            result = 31 * result + destinationHash.contentHashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    /**
     * Helper extension to convert ByteArray to hex string.
     */
    protected fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Create an outbound destination for Python's LXMF router.
     * This is used for sending messages from Kotlin to Python.
     */
    protected fun createPythonDestination(): Destination? {
        val pythonIdentity = Identity.recall(pythonDestHash!!)
        if (pythonIdentity == null) {
            println("[Test] Cannot recall Python identity from dest hash")
            return null
        }

        return Destination.create(
            identity = pythonIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
    }

    /**
     * Announce Kotlin's LXMF destination so Python can discover it.
     */
    protected fun announceKotlinDestination() {
        kotlinDestination.announce()
    }
}
