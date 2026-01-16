package network.reticulum.lxmf.examples

import kotlinx.coroutines.*
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import network.reticulum.transport.Transport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * LXMF Sender - Sends a message to a destination and waits for echo reply.
 *
 * Usage:
 *   java -cp lxmf-node.jar network.reticulum.lxmf.examples.LxmfSenderKt <dest_hash> [opportunistic|direct] [host] [port]
 *
 * Examples:
 *   java -cp lxmf-node.jar network.reticulum.lxmf.examples.LxmfSenderKt abc123... opportunistic
 *   java -cp lxmf-node.jar network.reticulum.lxmf.examples.LxmfSenderKt abc123... direct 127.0.0.1 4243
 */

private const val DISPLAY_NAME = "Kotlin Test Sender"

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: LxmfSenderKt <dest_hash> [opportunistic|direct] [host] [port]")
        println()
        println("Examples:")
        println("  LxmfSenderKt abc123def456... opportunistic")
        println("  LxmfSenderKt abc123def456... direct 127.0.0.1 4243")
        return
    }

    val destHash = args[0]
    val method = if (args.size > 1) args[1].lowercase() else "opportunistic"
    val host = if (args.size > 2) args[2] else "127.0.0.1"
    val port = if (args.size > 3) args[3].toIntOrNull() ?: 4243 else 4243

    println("=" .repeat(60))
    println("LXMF Sender - Kotlin Implementation")
    println("=".repeat(60))
    println()
    println("  Destination: $destHash")
    println("  Method: ${method.uppercase()}")
    println("  Target: $host:$port")
    println()

    val sender = LxmfSender(destHash, method, host, port)
    sender.run()
}

class LxmfSender(
    private val destHashHex: String,
    private val method: String,
    private val targetHost: String,
    private val targetPort: Int
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var tcpClient: TCPClientInterface
    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var myDestination: Destination

    @Volatile
    private var echoReceived = false

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })

        initialize()
        sendMessage()
        waitForEcho()
        shutdown()
    }

    private fun initialize() {
        log("Initializing Reticulum...")
        Reticulum.start(enableTransport = true)

        log("Creating TCP client to $targetHost:$targetPort...")
        tcpClient = TCPClientInterface(
            name = "Sender TCP",
            targetHost = targetHost,
            targetPort = targetPort
        )

        tcpClient.onPacketReceived = { data, iface ->
            Transport.inbound(data, iface.toRef())
        }

        tcpClient.start()
        Transport.registerInterface(tcpClient.toRef())

        Thread.sleep(1000) // Wait for connection

        if (!tcpClient.online.get()) {
            log("ERROR: Could not connect to $targetHost:$targetPort")
            System.exit(1)
        }

        log("Connected!")

        // Create identity
        identity = Identity.create()

        // Create LXMF router
        log("Creating LXMF router...")
        router = LXMRouter(identity = identity)
        myDestination = router.registerDeliveryIdentity(identity, DISPLAY_NAME)

        // Register for incoming messages (echo replies)
        router.registerDeliveryCallback { message ->
            handleIncomingMessage(message)
        }

        router.start()

        log("  Our address: <${myDestination.hash.toHexString()}>")

        // Announce ourselves
        log("Announcing...")
        myDestination.announce()

        Thread.sleep(2000) // Wait for network setup
    }

    private fun handleIncomingMessage(message: LXMessage) {
        val senderHash = message.sourceHash.toHexString()

        println()
        println("=".repeat(60))
        println("RECEIVED ECHO REPLY!")
        println("  From: <$senderHash>")
        println("  Title: ${message.title ?: "(no title)"}")
        println("  Content: ${message.content}")
        println("  Signature valid: ${message.signatureValidated}")
        println("=".repeat(60))
        println()

        echoReceived = true
    }

    private fun sendMessage() {
        val destHashBytes = destHashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        log("Checking path to $destHashHex...")

        // Request path if needed
        if (!Transport.hasPath(destHashBytes)) {
            log("  Path not known, requesting...")
            var pathFound = false
            Transport.requestPath(destHashBytes) { found ->
                pathFound = found
            }

            // Wait for path
            val timeout = 30000L
            val start = System.currentTimeMillis()
            while (!Transport.hasPath(destHashBytes) && System.currentTimeMillis() - start < timeout) {
                Thread.sleep(500)
            }

            if (!Transport.hasPath(destHashBytes)) {
                log("  ERROR: Could not find path to destination!")
                System.exit(1)
            }
        }

        log("  Path found!")

        // Recall identity
        log("Recalling recipient identity...")
        val recipientIdentity = Identity.recall(destHashBytes)
        if (recipientIdentity == null) {
            log("  ERROR: Could not recall recipient identity!")
            System.exit(1)
        }

        log("  Identity recalled successfully")

        // Create destination
        val dest = Destination.create(
            identity = recipientIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            aspects = arrayOf("delivery")
        )

        // Create message
        val content = "Hello from Kotlin! This is a test message."
        val title = "Kotlin Test"

        val deliveryMethod = if (method == "direct") {
            log("\nSending DIRECT message (link will be established)...")
            DeliveryMethod.DIRECT
        } else {
            log("\nSending OPPORTUNISTIC message (single packet)...")
            DeliveryMethod.OPPORTUNISTIC
        }

        val lxm = LXMessage.create(
            destination = dest,
            source = myDestination,
            content = content,
            title = title,
            desiredMethod = deliveryMethod
        )

        log("  Message hash: <${lxm.hash?.toHexString() ?: "unknown"}>")

        runBlocking {
            router.handleOutbound(lxm)
        }

        log("  Message sent!")
    }

    private fun waitForEcho() {
        log("\nWaiting for echo reply (30 seconds)...")

        val timeout = 30
        for (i in timeout downTo 0) {
            if (echoReceived) {
                log("Echo received successfully!")
                return
            }
            Thread.sleep(1000)
            if (i % 5 == 0 && i > 0) {
                log("  $i seconds remaining...")
            }
        }

        if (!echoReceived) {
            log("Timeout - no echo received")
        }
    }

    private fun shutdown() {
        log("Shutting down...")
        scope.cancel()
        try {
            tcpClient.detach()
        } catch (e: Exception) { }
        Transport.stop()
    }

    private fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        println("[$timestamp] $message")
    }
}
