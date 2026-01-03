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
import network.reticulum.lxmf.MessageState
import network.reticulum.transport.Transport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Test client for verifying LXMF delivery confirmations.
 * Connects to the echo bot via TCP and sends a message,
 * then waits for delivery confirmation.
 */

private const val ECHO_BOT_HOST = "127.0.0.1"
private const val ECHO_BOT_PORT = 4242
private const val ECHO_BOT_HASH = "af75975ced6ded2d7a811808a4f56383"

fun main() {
    println("=".repeat(60))
    println("LXMF Delivery Confirmation Test")
    println("=".repeat(60))
    println()

    val test = DeliveryConfirmTest()
    val result = test.run()
    exitProcess(if (result) 0 else 1)
}

class DeliveryConfirmTest {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private lateinit var identity: Identity
    private lateinit var router: LXMRouter
    private lateinit var tcpClient: TCPClientInterface
    private lateinit var myDestination: Destination

    fun run(): Boolean {
        try {
            initialize()

            // Wait for connection and announces
            log("Waiting 5 seconds for connection and path discovery...")
            Thread.sleep(5000)

            // Send message and wait for delivery confirmation
            return sendTestMessage()

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            shutdown()
        }
    }

    private fun initialize() {
        log("Initializing Reticulum...")
        Reticulum.start(enableTransport = false)

        log("Connecting to echo bot at $ECHO_BOT_HOST:$ECHO_BOT_PORT...")
        tcpClient = TCPClientInterface(
            name = "Test Client TCP",
            targetHost = ECHO_BOT_HOST,
            targetPort = ECHO_BOT_PORT
        )
        tcpClient.start()
        Transport.registerInterface(tcpClient.toRef())

        log("Creating identity...")
        identity = Identity.create()

        log("Creating LXMF router...")
        router = LXMRouter(identity = identity)
        myDestination = router.registerDeliveryIdentity(identity, "Delivery Test Client")

        // Register callback for incoming messages (echoes)
        router.registerDeliveryCallback { message ->
            log("Received echo: ${message.content}")
        }

        router.start()

        log("Test client initialized")
        log("  My address: <${myDestination.hexHash}>")
        log("  Target: <$ECHO_BOT_HASH>")

        // Wait for TCP connection to fully establish
        log("Waiting 2 seconds for TCP read loop to start...")
        Thread.sleep(2000)

        // Send announce so echo bot knows our identity
        log("Sending announce...")
        router.announce(myDestination)
    }

    private fun sendTestMessage(): Boolean {
        log("Looking up echo bot identity...")

        // Request path to echo bot
        val targetHash = ECHO_BOT_HASH.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Check if we have path
        if (!Transport.hasPath(targetHash)) {
            log("No path to echo bot, requesting...")
            Transport.requestPath(targetHash) { found ->
                log("Path request result: $found")
            }
            Thread.sleep(3000)
        }

        // Try to recall identity
        val echoIdentity = Identity.recall(targetHash)
        if (echoIdentity == null) {
            log("ERROR: Cannot find echo bot identity. Make sure echo bot is running and has announced.")
            return false
        }

        log("Found echo bot identity")

        // Create destination
        val echoDest = Destination.create(
            identity = echoIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        // Create message
        val content = "Test message at ${Instant.now()}"
        log("Creating message: $content")

        val message = LXMessage.create(
            destination = echoDest,
            source = myDestination,
            content = content,
            title = "Delivery Confirmation Test",
            desiredMethod = DeliveryMethod.OPPORTUNISTIC
        )

        // Set up delivery callbacks
        var delivered = false
        var failed = false

        message.deliveryCallback = {
            log("*** DELIVERY CONFIRMED! ***")
            delivered = true
        }
        message.failedCallback = {
            log("*** DELIVERY FAILED! ***")
            failed = true
        }

        // Send message
        log("Sending message (OPPORTUNISTIC)...")
        runBlocking { router.handleOutbound(message) }

        // Wait for delivery confirmation
        log("Waiting for delivery confirmation (30 second timeout)...")
        val startTime = System.currentTimeMillis()
        val timeout = 30_000L

        while (System.currentTimeMillis() - startTime < timeout) {
            if (delivered) {
                log("SUCCESS: Delivery confirmation received!")
                return true
            }
            if (failed) {
                log("FAILED: Message delivery failed")
                return false
            }

            // Also check message state directly
            when (message.state) {
                MessageState.DELIVERED -> {
                    log("SUCCESS: Message state is DELIVERED")
                    return true
                }
                MessageState.FAILED -> {
                    log("FAILED: Message state is FAILED")
                    return false
                }
                else -> {}
            }

            Thread.sleep(100)
        }

        log("TIMEOUT: No delivery confirmation after ${timeout/1000} seconds")
        log("Final message state: ${message.state}")
        return false
    }

    private fun shutdown() {
        log("Shutting down...")

        if (::router.isInitialized) {
            router.stop()
        }

        if (::tcpClient.isInitialized) {
            tcpClient.detach()
        }

        Reticulum.stop()

        log("Done")
    }

    private fun log(message: String) {
        val timestamp = timeFormatter.format(Instant.now())
        println("[$timestamp] $message")
    }
}
