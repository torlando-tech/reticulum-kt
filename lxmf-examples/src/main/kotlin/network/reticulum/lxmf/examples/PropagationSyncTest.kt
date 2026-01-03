package network.reticulum.lxmf.examples

import network.reticulum.Reticulum
import network.reticulum.common.toHexString
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.LXMRouter.PropagationTransferState
import network.reticulum.transport.Transport
import java.io.File

/**
 * Test propagation sync from a Python propagation node.
 *
 * Prerequisites:
 * 1. Run: python3 test-scripts/python_propagation_node.py
 * 2. It will create test messages and save a client identity
 * 3. Run this test to sync messages from the propagation node
 */

private const val TCP_HOST = "127.0.0.1"
private const val TCP_PORT = 4242
private const val IDENTITY_FILE = "/tmp/lxmf_prop_node_test/client_identity"
private const val DISPLAY_NAME = "Kotlin Propagation Sync Test"

fun main(args: Array<String>) {
    println("=".repeat(60))
    println("Propagation Sync Test - Kotlin Client")
    println("=".repeat(60))
    println()

    val test = PropagationSyncTest()
    test.run()
}

class PropagationSyncTest {
    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var tcpClient: TCPClientInterface
    private var messagesReceived = 0

    fun run() {
        try {
            initialize()
            waitForPropagationNode()
            syncMessages()
            printResults()
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun initialize() {
        println("Initializing...")

        // Load identity from file (created by Python test script)
        val identityFile = File(IDENTITY_FILE)
        if (!identityFile.exists()) {
            throw RuntimeException(
                "Identity file not found: $IDENTITY_FILE\n" +
                "Please run the Python propagation node first:\n" +
                "  python3 test-scripts/python_propagation_node.py"
            )
        }

        identity = Identity.fromFile(IDENTITY_FILE)
            ?: throw RuntimeException("Failed to load identity from $IDENTITY_FILE")
        println("Loaded identity: ${identity.hash.toHexString()}")

        // Initialize Reticulum
        Reticulum.start(enableTransport = true)

        // Create TCP client to connect to Python propagation node
        tcpClient = TCPClientInterface(
            name = "TCP Client to Python Prop Node",
            targetHost = TCP_HOST,
            targetPort = TCP_PORT
        )
        tcpClient.start()  // Start the interface to establish connection
        Transport.registerInterface(tcpClient.toRef())

        // Wait for connection to establish
        Thread.sleep(500)
        println("Connected to TCP $TCP_HOST:$TCP_PORT (online=${tcpClient.online.get()})")

        // Create LXMF router
        router = LXMRouter()

        // Register our delivery identity
        val deliveryDest = router.registerDeliveryIdentity(
            identity = identity,
            displayName = DISPLAY_NAME
        )
        println("Registered delivery destination: ${deliveryDest.hash.toHexString()}")

        // Register message callback
        router.registerDeliveryCallback { message ->
            messagesReceived++
            println("\n${"=".repeat(50)}")
            println("MESSAGE RECEIVED (#$messagesReceived)")
            println("${"=".repeat(50)}")
            println("From: ${message.sourceHash?.toHexString() ?: "unknown"}")
            println("Title: ${message.title}")
            println("Content: ${message.content}")
            println("Signature valid: ${message.signatureValidated}")
            println("${"=".repeat(50)}\n")
        }

        // Announce ourselves
        router.announce(deliveryDest)
        println("Announced ourselves")
        println()
    }

    private fun waitForPropagationNode() {
        println("Waiting for propagation node discovery...")

        // Wait for propagation node to be discovered
        var waited = 0
        while (router.getPropagationNodes().isEmpty() && waited < 30) {
            Thread.sleep(1000)
            waited++
            print(".")
            System.out.flush()
        }
        println()

        val nodes = router.getPropagationNodes()
        if (nodes.isEmpty()) {
            throw RuntimeException("No propagation nodes discovered after 30 seconds")
        }

        println("Found ${nodes.size} propagation node(s):")
        for (node in nodes) {
            println("  - ${node.destHash.toHexString()}")
        }

        // Set the first one as active
        val node = nodes.first()
        router.setActivePropagationNode(node.destHash.toHexString())
        println("Set active propagation node: ${node.destHash.toHexString()}")
        println()
    }

    private fun syncMessages() {
        println("Requesting messages from propagation node...")
        router.requestMessagesFromPropagationNode()

        // Wait for sync to complete
        var waited = 0
        while (router.propagationTransferState != PropagationTransferState.COMPLETE &&
               router.propagationTransferState != PropagationTransferState.FAILED &&
               waited < 60) {
            Thread.sleep(500)
            waited++

            val progress = (router.propagationTransferProgress * 100).toInt()
            print("\rProgress: $progress% (${router.propagationTransferState})")
            System.out.flush()
        }
        println()
    }

    private fun printResults() {
        println()
        println("=".repeat(60))
        println("SYNC COMPLETE")
        println("=".repeat(60))
        println("Final state: ${router.propagationTransferState}")
        println("Messages received: ${router.propagationTransferLastResult}")
        println("Callback messages: $messagesReceived")
        println("=".repeat(60))
    }
}
