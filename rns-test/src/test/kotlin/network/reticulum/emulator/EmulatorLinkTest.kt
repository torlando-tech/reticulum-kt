package network.reticulum.emulator

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host-side tests that connect to a Kotlin Transport running in the Android emulator.
 *
 * These tests require the emulator to be running with a link responder active.
 * They are disabled by default and can be enabled for integration testing.
 *
 * ## Prerequisites
 *
 * 1. Start Android emulator
 * 2. Set up port forwarding:
 *    ```
 *    adb forward tcp:4242 tcp:4242
 *    ```
 * 3. Run the link responder in the emulator:
 *    ```
 *    ./gradlew :rns-sample-app:connectedAndroidTest --tests "*linkResponderWaitsForConnection*"
 *    ```
 * 4. Copy the destination hash from logcat output
 * 5. Update EMULATOR_DESTINATION_HASH below
 * 6. Run this test
 *
 * ## Architecture
 *
 * ```
 * ┌─────────────────────────────┐     TCP     ┌─────────────────────────────┐
 * │  Host JVM (this test)       │◄───────────►│  Android Emulator           │
 * │  - Transport singleton      │   (adb fwd) │  - Transport singleton      │
 * │  - TCPClientInterface       │             │  - TCPServerInterface       │
 * │  - Link initiator           │             │  - Link responder           │
 * └─────────────────────────────┘             └─────────────────────────────┘
 * ```
 *
 * Both sides have their own Transport singleton with separate hashlists,
 * so link establishment works correctly.
 */
@DisplayName("Emulator Link Tests")
class EmulatorLinkTest {

    companion object {
        private const val EMULATOR_HOST = "127.0.0.1"
        private const val EMULATOR_PORT = 4242

        // Update this with the destination hash from the emulator's logcat output
        // Run: adb logcat -s LinkResponderTest | grep "Destination:"
        private const val EMULATOR_DESTINATION_HASH = ""

        private const val LINK_TIMEOUT_SECONDS = 30L
    }

    private var clientInterface: TCPClientInterface? = null

    @BeforeEach
    fun setup() {
        // Note: We start a NEW Transport here, separate from any in the emulator
        Transport.stop()
        Thread.sleep(200)
        Transport.start(enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        clientInterface?.let {
            Transport.deregisterInterface(InterfaceAdapter.getOrCreate(it))
            it.detach()
        }
        clientInterface = null

        Transport.stop()
        Thread.sleep(200)
    }

    @Test
    @Disabled("Requires Android emulator with LinkResponderTest running")
    @DisplayName("Can establish link to emulator responder")
    @Timeout(60)
    fun `can establish link to emulator responder`() {
        require(EMULATOR_DESTINATION_HASH.isNotEmpty()) {
            """
            EMULATOR_DESTINATION_HASH is not set.

            1. Run the emulator responder:
               ./gradlew :rns-sample-app:connectedAndroidTest --tests "*linkResponderWaitsForConnection*"

            2. Get destination hash from logcat:
               adb logcat -s LinkResponderTest | grep "Destination:"

            3. Update EMULATOR_DESTINATION_HASH in this file
            """.trimIndent()
        }

        // Connect to emulator via TCP
        clientInterface = TCPClientInterface(
            name = "EmulatorClient",
            targetHost = EMULATOR_HOST,
            targetPort = EMULATOR_PORT
        )

        clientInterface!!.onPacketReceived = { data, fromInterface ->
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        clientInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(clientInterface!!))

        // Wait for interface to connect
        val connectTimeout = System.currentTimeMillis() + 10_000
        while (!clientInterface!!.online.get()) {
            if (System.currentTimeMillis() > connectTimeout) {
                throw AssertionError("Failed to connect to emulator on $EMULATOR_HOST:$EMULATOR_PORT")
            }
            Thread.sleep(100)
        }

        println("Connected to emulator at $EMULATOR_HOST:$EMULATOR_PORT")

        // Parse destination hash
        val destHash = EMULATOR_DESTINATION_HASH.hexToByteArray()

        // Wait for announce/path from emulator
        println("Waiting for path to destination...")
        Transport.requestPath(destHash)

        val pathTimeout = System.currentTimeMillis() + 15_000
        while (!Transport.hasPath(destHash)) {
            if (System.currentTimeMillis() > pathTimeout) {
                throw AssertionError("No path to destination found")
            }
            Thread.sleep(100)
        }

        println("Path found, recalling identity...")

        // Recall identity
        val identity = Identity.recall(destHash)
        assertNotNull(identity, "Should be able to recall identity from announce")

        // Create destination
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "linktest",
            aspects = arrayOf("responder")
        )

        // Track link establishment
        val establishedLink = AtomicReference<Link?>(null)
        val linkLatch = CountDownLatch(1)

        // Create link
        val link = Link.create(
            destination = destination,
            establishedCallback = { l ->
                println("Link established! ID: ${l.linkId.toHexString()}")
                establishedLink.set(l)
                linkLatch.countDown()
            },
            closedCallback = { println("Link closed") }
        )

        println("Link created, waiting for establishment...")

        // Wait for link to be established
        val linked = linkLatch.await(LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(linked, "Link should be established within timeout")

        val estLink = establishedLink.get()
        assertNotNull(estLink, "Established link should not be null")
        assertEquals(LinkConstants.ACTIVE, estLink.status, "Link should be ACTIVE")
        assertTrue(estLink.initiator, "We should be the initiator")

        println("Link test PASSED!")

        // Keep link alive briefly
        Thread.sleep(2000)
    }

    @Test
    @Disabled("Requires Android emulator - use for manual testing")
    @DisplayName("Connect to emulator and wait for manual testing")
    @Timeout(300)
    fun `connect to emulator for manual testing`() {
        // Connect to emulator
        clientInterface = TCPClientInterface(
            name = "ManualTestClient",
            targetHost = EMULATOR_HOST,
            targetPort = EMULATOR_PORT
        )

        clientInterface!!.onPacketReceived = { data, fromInterface ->
            println("Received ${data.size} bytes from ${fromInterface.name}")
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        clientInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(clientInterface!!))

        // Wait for connection
        val connectTimeout = System.currentTimeMillis() + 10_000
        while (!clientInterface!!.online.get()) {
            if (System.currentTimeMillis() > connectTimeout) {
                throw AssertionError("Failed to connect to emulator")
            }
            Thread.sleep(100)
        }

        println("=" .repeat(60))
        println("CONNECTED TO EMULATOR")
        println("Host: $EMULATOR_HOST:$EMULATOR_PORT")
        println()
        println("Waiting for announces and activity...")
        println("=" .repeat(60))

        // Wait and observe
        Thread.sleep(120_000)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
