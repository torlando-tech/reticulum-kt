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
 * LXMF Echo Bot - A simple bot that echoes back any message it receives.
 *
 * Features:
 * - Connects to the Reticulum testnet via TCP
 * - Advertises itself as "Kotlin Echo Bot"
 * - Announces every 60 seconds
 * - Replies to every incoming message with the exact same content
 *
 * Usage:
 *   java -cp lxmf-node.jar network.reticulum.lxmf.examples.LxmfEchoBotKt [host] [port]
 *
 * Defaults:
 *   host: amsterdam.connect.reticulum.network
 *   port: 4965
 */

private const val ANNOUNCE_INTERVAL_MS = 60_000L
private const val DEFAULT_HOST = "amsterdam.connect.reticulum.network"
private const val DEFAULT_PORT = 4965
private const val DISPLAY_NAME = "Kotlin Echo Bot"

fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: DEFAULT_HOST
    val port = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT

    println("=".repeat(60))
    println("LXMF Echo Bot - Kotlin Implementation")
    println("=".repeat(60))
    println()

    val bot = LxmfEchoBot(host, port)
    bot.run()
}

class LxmfEchoBot(
    private val targetHost: String,
    private val targetPort: Int
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var tcpInterface: TCPClientInterface
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

        log("Connecting to $targetHost:$targetPort...")

        // Create TCP interface to testnet
        tcpInterface = TCPClientInterface(
            name = "Testnet-TCP",
            targetHost = targetHost,
            targetPort = targetPort
        )

        // Wire up the interface to Transport
        val ifaceRef = tcpInterface.toRef()
        tcpInterface.onPacketReceived = { data, _ ->
            Transport.inbound(data, ifaceRef)
        }

        // Start interface and register with Transport
        tcpInterface.start()
        Transport.registerInterface(ifaceRef)

        log("Creating identity...")

        // Create a new identity for this bot
        identity = Identity.create()

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
        log("  Connected to: $targetHost:$targetPort")

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
        val senderIdentity = Identity.recall(message.sourceHash)
        if (senderIdentity == null) {
            log("  Cannot echo: sender identity not known")
            return
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
        val reply = LXMessage.create(
            destination = replyDestination,
            source = myDestination,
            content = message.content,
            title = message.title,
            desiredMethod = DeliveryMethod.DIRECT
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
        println("  Interface: ${if (tcpInterface.online.get()) "connected" else "connecting..."}")
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

        if (::tcpInterface.isInitialized) {
            tcpInterface.detach()
        }

        Reticulum.stop()

        log("Shutdown complete")
    }

    private fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        println("[$timestamp] $message")
    }
}
