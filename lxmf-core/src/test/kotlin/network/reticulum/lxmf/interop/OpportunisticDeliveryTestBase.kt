package network.reticulum.lxmf.interop

import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMessage
import network.reticulum.transport.Transport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

/**
 * Base class for opportunistic delivery interop tests.
 *
 * Extends DirectDeliveryTestBase but overrides setup to NOT announce
 * the Python destination automatically. This allows tests to control
 * exactly when the announce happens to verify opportunistic delivery behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OpportunisticDeliveryTestBase : DirectDeliveryTestBase() {

    @BeforeAll
    override fun setupDirectDelivery() {
        // Duplicate steps 1-8 from DirectDeliveryTestBase, but SKIP step 9 (announce)
        // This allows tests to control when Python announces

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
                packetHash = pythonDestHash!!,
                destHash = pythonDestHash!!,
                publicKey = pythonIdentityPublicKey!!,
                appData = null
            )
            println("  [Setup] Python identity registered")
        }

        // NOTE: Step 9 (announce) is INTENTIONALLY SKIPPED
        // Tests can trigger announce via triggerPythonAnnounce() when needed
        println("  [Setup] Opportunistic delivery infrastructure ready (Python NOT yet announced)")

        // Wait for network to stabilize (without announce)
        Thread.sleep(2000)
        println("  [Setup] Final connection status: online=${kotlinTcpClient!!.online.get()}")
    }

    /**
     * Trigger Python to announce its LXMF destination.
     * Call this when test needs path to become available.
     */
    protected fun triggerPythonAnnounce() {
        val result = python("lxmf_announce")
        println("[Test] Python announced: ${result.getString("announced")}")
    }

    /**
     * Wait for Kotlin to receive and process the Python announce.
     */
    protected fun waitForKotlinToReceiveAnnounce(destHash: ByteArray, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Transport.hasPath(destHash)) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Create an opportunistic message to Python destination.
     */
    protected fun createOpportunisticMessage(
        content: String,
        title: String = "Opportunistic Test"
    ): LXMessage {
        val pythonIdentity = Identity.recall(pythonDestHash!!)
            ?: error("Cannot recall Python identity")

        val dest = Destination.create(
            identity = pythonIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        return LXMessage.create(
            destination = dest,
            source = kotlinDestination,
            content = content,
            title = title,
            desiredMethod = DeliveryMethod.OPPORTUNISTIC
        )
    }
}
