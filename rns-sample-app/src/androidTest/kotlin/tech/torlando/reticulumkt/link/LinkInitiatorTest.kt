package tech.torlando.reticulumkt.link

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented test that runs in the Android emulator as a link initiator.
 *
 * This test connects to a Python RNS responder running on the host and
 * initiates a link. It validates that Kotlin Transport running on Android
 * can initiate and establish links to Python destinations.
 *
 * ## Usage
 *
 * 1. Start the Python responder on the host:
 *    ```
 *    python test-scripts/link_responder.py
 *    ```
 *
 * 2. Note the destination hash printed by the responder.
 *
 * 3. Set up port forwarding (Python listens on 4243):
 *    ```
 *    adb forward tcp:4243 tcp:4243
 *    ```
 *
 * 4. Run this test with the destination hash:
 *    ```
 *    ./gradlew :rns-sample-app:connectedAndroidTest \
 *      --tests "*LinkInitiatorTest*" \
 *      -Pandroid.testInstrumentationRunnerArguments.destHash=<hash>
 *    ```
 *
 * The test will connect to the Python responder and establish a link.
 */
@RunWith(AndroidJUnit4::class)
class LinkInitiatorTest {

    companion object {
        private const val TAG = "LinkInitiatorTest"
        private const val PYTHON_PORT = 4243
        private const val LINK_TIMEOUT_SECONDS = 60L
        private const val PATH_TIMEOUT_SECONDS = 30L
    }

    private var clientInterface: TCPClientInterface? = null
    private var link: Link? = null

    @Before
    fun setup() {
        Log.i(TAG, "Setting up link initiator test")

        // Stop any existing Transport
        Transport.stop()
        Thread.sleep(200)

        // Start fresh Transport
        Transport.start(enableTransport = false)
        Log.i(TAG, "Transport started")
    }

    @After
    fun teardown() {
        Log.i(TAG, "Tearing down link initiator test")

        link?.teardown()
        link = null

        clientInterface?.let {
            Transport.deregisterInterface(InterfaceAdapter.getOrCreate(it))
            it.detach()
        }
        clientInterface = null

        Transport.stop()
        Thread.sleep(200)
    }

    @Test
    fun linkInitiatorConnectsToPython() {
        // Get destination hash from test arguments
        val args = InstrumentationRegistry.getArguments()
        val destHashHex = args.getString("destHash")

        if (destHashHex.isNullOrBlank()) {
            Log.w(TAG, "No destHash provided, running in discovery mode")
            runDiscoveryMode()
            return
        }

        Log.i(TAG, "Target destination: $destHashHex")

        // Parse destination hash
        val destHash = destHashHex.hexToByteArray()

        // Create TCP client interface to connect to Python responder
        // Note: From emulator, host is at 10.0.2.2 (special Android emulator address)
        clientInterface = TCPClientInterface(
            name = "PythonResponder",
            targetHost = "10.0.2.2",  // Host from emulator's perspective
            targetPort = PYTHON_PORT
        )

        clientInterface!!.onPacketReceived = { data, fromInterface ->
            Log.d(TAG, "Received packet: ${data.size} bytes from ${fromInterface.name}")
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        clientInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(clientInterface!!))

        Log.i(TAG, "TCP client connected to Python responder")

        // Wait for path to destination
        Log.i(TAG, "Waiting for path to destination...")
        val pathFound = waitForPath(destHash, PATH_TIMEOUT_SECONDS)
        assertTrue("Path to destination should be found", pathFound)

        Log.i(TAG, "Path found, recalling identity...")

        // Recall the destination's identity
        val identity = Identity.recall(destHash)
        assertNotNull("Identity should be recalled from announce", identity)

        Log.i(TAG, "Identity recalled: ${identity!!.hexHash}")

        // Create destination object for the remote identity
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "linktest",
            aspects = arrayOf("responder")
        )

        Log.i(TAG, "Created destination: ${destination.hexHash}")

        // Set up link callbacks
        val linkEstablished = CountDownLatch(1)
        val establishedLink = AtomicReference<Link?>(null)

        // Create and establish link
        link = Link.create(destination)
        link!!.setLinkEstablishedCallback { linkAny ->
            val l = linkAny as Link
            Log.i(TAG, "=" .repeat(60))
            Log.i(TAG, "LINK ESTABLISHED!")
            Log.i(TAG, "Link ID: ${l.linkId.toHexString()}")
            Log.i(TAG, "RTT: ${l.rtt}ms")
            Log.i(TAG, "=" .repeat(60))
            establishedLink.set(l)
            linkEstablished.countDown()
        }

        link!!.setLinkClosedCallback { linkAny ->
            val l = linkAny as Link
            Log.i(TAG, "Link closed. Reason: ${l.teardownReason}")
        }

        Log.i(TAG, "Establishing link...")

        // Wait for link establishment
        val linked = linkEstablished.await(LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue("Link should be established within timeout", linked)

        val established = establishedLink.get()
        assertNotNull("Established link should not be null", established)
        assertEquals("Link should be in ACTIVE state", LinkConstants.ACTIVE, established!!.status)
        assertTrue("We should be the initiator", established.initiator)

        Log.i(TAG, "Link test passed! Link ID: ${established.linkId.toHexString()}")

        // Keep the link alive briefly to allow responder to verify
        Thread.sleep(2000)
    }

    /**
     * Discovery mode - just connect and wait for announces.
     * Useful for debugging when you don't have the destination hash yet.
     */
    private fun runDiscoveryMode() {
        Log.i(TAG, "=" .repeat(60))
        Log.i(TAG, "DISCOVERY MODE")
        Log.i(TAG, "No destHash provided - will connect and wait for announces")
        Log.i(TAG, "=" .repeat(60))

        // Create TCP client interface
        clientInterface = TCPClientInterface(
            name = "PythonResponder",
            targetHost = "10.0.2.2",
            targetPort = PYTHON_PORT
        )

        clientInterface!!.onPacketReceived = { data, fromInterface ->
            Log.d(TAG, "Received packet: ${data.size} bytes")
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        clientInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(clientInterface!!))

        Log.i(TAG, "Connected to Python responder, waiting for announces...")

        // Register announce handler to log discovered destinations
        Transport.registerAnnounceHandler { destHash, announcedIdentity, appData ->
            Log.i(TAG, "=" .repeat(60))
            Log.i(TAG, "ANNOUNCE RECEIVED!")
            Log.i(TAG, "Destination: ${destHash.toHexString()}")
            Log.i(TAG, "Identity: ${announcedIdentity.hexHash}")
            Log.i(TAG, "=" .repeat(60))
            true // Accept announce
        }

        // Wait for announces
        Thread.sleep(60_000)

        Log.i(TAG, "Discovery mode complete")
    }

    private fun waitForPath(destHash: ByteArray, timeoutSeconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000)
        while (System.currentTimeMillis() < deadline) {
            if (Transport.hasPath(destHash)) {
                return true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
