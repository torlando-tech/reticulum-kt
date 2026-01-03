package network.reticulum.lxmf.examples

import kotlinx.coroutines.*
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import network.reticulum.transport.Transport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * LXMF Echo Bot - A simple bot that echoes back any message it receives.
 *
 * Features:
 * - Uses AutoInterface for automatic peer discovery on local network
 * - Advertises itself as "Kotlin Echo Bot"
 * - Announces every 60 seconds
 * - Replies to every incoming message with the exact same content
 *
 * Usage:
 *   java -cp lxmf-node.jar network.reticulum.lxmf.examples.LxmfEchoBotKt
 */

private const val ANNOUNCE_INTERVAL_MS = 60_000L
private const val DISPLAY_NAME = "Kotlin Echo Bot"
private const val IDENTITY_FILE = "echobot_identity"
private const val TCP_PORT = 4242

fun main(args: Array<String>) {
    println("=".repeat(60))
    println("LXMF Echo Bot - Kotlin Implementation (AutoInterface)")
    println("=".repeat(60))
    println()

    val bot = LxmfEchoBot()
    bot.run()
}

class LxmfEchoBot {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var autoInterface: AutoInterface
    private lateinit var tcpServer: TCPServerInterface
    private lateinit var myDestination: Destination

    @Volatile
    private var running = true

    private var announceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun run() {
        try {
            initialize()
            printStatus()

            // Start announcement coroutine
            announceJob = scope.launch {
                announceLoop()
            }

            // Run daemon loop
            daemonLoop()

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
        } finally {
            shutdown()
        }
    }

    private fun initialize() {
        log("Initializing Reticulum...")

        // Start Reticulum with transport enabled
        Reticulum.start(enableTransport = true)

        log("Starting AutoInterface for peer discovery...")

        // Create AutoInterface for automatic peer discovery
        autoInterface = AutoInterface(name = "EchoBot AutoInterface")
        autoInterface.start()
        Transport.registerInterface(autoInterface.toRef())

        log("Starting TCP server on port $TCP_PORT...")
        tcpServer = TCPServerInterface(name = "EchoBot TCP", bindPort = TCP_PORT)
        tcpServer.start()
        Transport.registerInterface(tcpServer.toRef())

        log("Loading or creating identity...")

        // Try to load existing identity, or create a new one
        identity = Identity.fromFile(IDENTITY_FILE) ?: run {
            log("No existing identity found, creating new one...")
            val newIdentity = Identity.create()
            newIdentity.toFile(IDENTITY_FILE)
            newIdentity
        }

        log("Creating LXMF router...")

        // Create the LXMF router
        router = LXMRouter(identity = identity)

        // Register a delivery identity with our display name
        myDestination = router.registerDeliveryIdentity(identity, DISPLAY_NAME)

        // Register callback for incoming messages
        router.registerDeliveryCallback { message ->
            handleIncomingMessage(message)
        }

        // Start the router's background processing
        router.start()

        log("Echo Bot initialized!")
        log("  Address: <${myDestination.hexHash}>")
        log("  Display name: $DISPLAY_NAME")

        // Wait briefly for AutoInterface to discover peers
        log("Waiting for peer discovery...")
        Thread.sleep(3000)

        // Send initial announce
        log("Sending initial announce...")
        router.announce(myDestination)
    }

    private fun handleIncomingMessage(message: LXMessage) {
        val senderHash = message.sourceHash.toHexString()
        log("Message received from <$senderHash>")
        log("  Title: ${message.title.ifEmpty { "(none)" }}")
        log("  Content: ${message.content.ifEmpty { "(empty)" }}")

        // Try to recall sender's identity
        var senderIdentity = Identity.recall(message.sourceHash)
        if (senderIdentity == null) {
            log("  Sender identity not known, requesting path...")

            // Request a path to the sender - this should trigger network to send their announce
            Transport.requestPath(message.sourceHash) { found ->
                if (found) {
                    log("  Path request succeeded for <$senderHash>")
                } else {
                    log("  Path request timed out for <$senderHash>")
                }
            }

            // Wait for the announce to arrive (path requests can take several seconds)
            // Try multiple times with short waits
            for (attempt in 1..15) {
                Thread.sleep(1000)
                senderIdentity = Identity.recall(message.sourceHash)
                if (senderIdentity != null) {
                    log("  Sender identity found after ${attempt}s")
                    break
                }
            }

            if (senderIdentity == null) {
                log("  Cannot echo: sender identity still not known after path request")
                log("  (Note: If you're on the same hub, announces may not be forwarded between direct clients)")
                return
            }
        }

        // Create outbound destination to sender
        val replyDestination = Destination.create(
            identity = senderIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Create reply with exact same content
        // Use OPPORTUNISTIC delivery - single encrypted packet, no link required
        val reply = LXMessage.create(
            destination = replyDestination,
            source = myDestination,
            content = message.content,
            title = message.title,
            desiredMethod = DeliveryMethod.OPPORTUNISTIC
        )

        // Send via coroutine
        scope.launch {
            try {
                router.handleOutbound(reply)
                log("Echo sent to <$senderHash>")
            } catch (e: Exception) {
                log("Failed to send echo: ${e.message}")
            }
        }
    }

    private suspend fun announceLoop() {
        while (running) {
            delay(ANNOUNCE_INTERVAL_MS)
            if (running) {
                announce()
            }
        }
    }

    private fun announce() {
        log("Sending announce...")
        router.announce(myDestination)
        log("Announce sent")
    }

    private fun daemonLoop() {
        log("Running in daemon mode. Press Ctrl+C to exit.")

        // Install shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            running = false
        })

        // Sleep loop until shutdown
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun printStatus() {
        println()
        println("-".repeat(40))
        println("Echo Bot Status:")
        println("  Address: <${myDestination.hexHash}>")
        println("  Display name: $DISPLAY_NAME")
        println("  Interfaces:")
        println("    - AutoInterface (peer discovery)")
        println("    - TCP Server on port $TCP_PORT")
        println("-".repeat(40))
        println()
    }

    private fun shutdown() {
        log("Shutting down...")

        running = false
        announceJob?.cancel()
        scope.cancel()

        if (::router.isInitialized) {
            router.stop()
        }

        if (::autoInterface.isInitialized) {
            autoInterface.detach()
        }

        if (::tcpServer.isInitialized) {
            tcpServer.detach()
        }

        Reticulum.stop()

        log("Shutdown complete")
    }

    private fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        println("[$timestamp] $message")
    }
}
