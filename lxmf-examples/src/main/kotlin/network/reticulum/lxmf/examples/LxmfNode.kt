package network.reticulum.lxmf.examples

import kotlinx.coroutines.*
import network.reticulum.Reticulum
import network.reticulum.common.toHexString
import network.reticulum.identity.Identity
import network.reticulum.interfaces.toRef
import network.reticulum.interfaces.udp.UDPInterface
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import network.reticulum.transport.Transport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Simple LXMF Node for testing the Kotlin LXMF implementation.
 *
 * Features:
 * - Starts Reticulum with transport enabled
 * - Creates a UDP broadcast interface for local network discovery
 * - Registers an LXMF delivery identity
 * - Announces every 60 seconds
 * - Keyboard commands:
 *   - 'a' + Enter: Announce immediately
 *   - 'i' + Enter: Show identity info
 *   - 's' + Enter: Show status
 *   - 'q' + Enter: Quit
 *
 * Usage:
 *   java -jar lxmf-node.jar [displayName]
 */

private const val ANNOUNCE_INTERVAL_MS = 60_000L
private const val UDP_PORT = 4242

fun main(args: Array<String>) {
    val displayName = args.firstOrNull() ?: "KotlinNode"

    println("=".repeat(60))
    println("LXMF Node - Kotlin Implementation")
    println("=".repeat(60))
    println()

    val node = LxmfNode(displayName)
    node.run()
}

class LxmfNode(private val displayName: String) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var udpInterface: UDPInterface

    @Volatile
    private var running = true

    private var announceJob: Job? = null

    fun run() {
        try {
            initialize()
            printStatus()
            printHelp()

            // Start announcement coroutine
            announceJob = CoroutineScope(Dispatchers.Default).launch {
                announceLoop()
            }

            // Run input loop
            inputLoop()

        } catch (e: Exception) {
            println("[ERROR] ${e.message}")
            e.printStackTrace()
        } finally {
            shutdown()
        }
    }

    private fun initialize() {
        log("Initializing Reticulum...")

        // Start Reticulum with transport enabled
        Reticulum.start(enableTransport = true)

        log("Creating UDP interface on port $UDP_PORT...")

        // Create UDP broadcast interface for local testing
        // This allows multiple nodes on the same machine or LAN to discover each other
        udpInterface = UDPInterface(
            name = "LXMFNode-UDP",
            bindPort = UDP_PORT,
            forwardIp = "255.255.255.255",
            forwardPort = UDP_PORT,
            broadcast = true
        )

        // Wire up the interface to Transport
        val ifaceRef = udpInterface.toRef()
        udpInterface.onPacketReceived = { data, _ ->
            Transport.inbound(data, ifaceRef)
        }

        // Start interface and register with Transport
        udpInterface.start()
        Transport.registerInterface(ifaceRef)

        log("Creating identity...")

        // Create a new identity for this node
        identity = Identity.create()

        log("Creating LXMF router...")

        // Create the LXMF router
        router = LXMRouter(identity = identity)

        // Register a delivery identity with our display name
        val destination = router.registerDeliveryIdentity(identity, displayName)

        // Register callback for incoming messages
        router.registerDeliveryCallback { message ->
            handleIncomingMessage(message)
        }

        // Start the router's background processing
        router.start()

        log("Node initialized!")
        log("  Address: <${destination.hexHash}>")
        log("  Display name: $displayName")

        // Send initial announce
        log("Sending initial announce...")
        router.announce(destination)
    }

    private fun handleIncomingMessage(message: LXMessage) {
        println()
        println("=".repeat(40))
        println("[MESSAGE RECEIVED]")
        println("  From: ${message.sourceHash.toHexString()}")
        println("  Title: ${message.title.ifEmpty { "(no title)" }}")
        println("  Content: ${message.content.ifEmpty { "(no content)" }}")
        val timestamp = message.timestamp
        println("  Timestamp: ${if (timestamp != null) formatTimestamp(timestamp) else "unknown"}")
        println("=".repeat(40))
        println()
        print("> ")
        System.out.flush()
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
        val destinations = router.getDeliveryDestinations()
        destinations.forEach { dest ->
            router.announce(dest.destination)
        }
        log("Announce sent")
    }

    private fun inputLoop() {
        val reader = System.`in`.bufferedReader()

        while (running) {
            print("> ")
            System.out.flush()

            val line = reader.readLine()
            if (line == null) {
                running = false
                break
            }

            when (line.trim().lowercase()) {
                "a", "announce" -> {
                    announce()
                }
                "i", "info" -> {
                    printIdentityInfo()
                }
                "s", "status" -> {
                    printStatus()
                }
                "h", "help" -> {
                    printHelp()
                }
                "q", "quit", "exit" -> {
                    running = false
                }
                "" -> {
                    // Ignore empty input
                }
                else -> {
                    println("Unknown command: $line")
                    println("Type 'h' for help")
                }
            }
        }
    }

    private fun printStatus() {
        println()
        println("-".repeat(40))
        println("Status:")
        val destinations = router.getDeliveryDestinations()
        println("  Registered destinations: ${destinations.size}")
        destinations.forEach { dest ->
            println("    - ${dest.displayName ?: "unnamed"}: <${dest.destination.hexHash}>")
        }
        val pendingCount = runBlocking { router.pendingOutboundCount() }
        println("  Pending outbound: $pendingCount")
        println("  UDP Interface: ${if (udpInterface.online.get()) "online" else "offline"}")
        println("-".repeat(40))
        println()
    }

    private fun printIdentityInfo() {
        println()
        println("-".repeat(40))
        println("Identity Info:")
        println("  Public key hash: ${identity.hash.toHexString()}")
        val destinations = router.getDeliveryDestinations()
        destinations.forEach { dest ->
            println("  LXMF Address: <${dest.destination.hexHash}>")
        }
        println("-".repeat(40))
        println()
    }

    private fun printHelp() {
        println()
        println("Commands:")
        println("  a, announce  - Send an announce immediately")
        println("  i, info      - Show identity information")
        println("  s, status    - Show current status")
        println("  h, help      - Show this help")
        println("  q, quit      - Quit the node")
        println()
        println("Announces are also sent automatically every 60 seconds.")
        println()
    }

    private fun shutdown() {
        log("Shutting down...")

        running = false
        announceJob?.cancel()

        if (::router.isInitialized) {
            router.stop()
        }

        if (::udpInterface.isInitialized) {
            udpInterface.detach()
        }

        Reticulum.stop()

        log("Shutdown complete")
    }

    private fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        println("[$timestamp] $message")
    }

    private fun formatTimestamp(unixSeconds: Double): String {
        val instant = Instant.ofEpochMilli((unixSeconds * 1000).toLong())
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}
