package tech.torlando.reticulumkt.link

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.tcp.TCPServerInterface
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
 * Instrumented test that runs in the Android emulator as a link responder.
 *
 * This test starts a TCP server and waits for link requests from a host-side
 * test (or Python script). It validates that Kotlin Transport running on
 * Android can accept and establish links.
 *
 * ## Usage
 *
 * 1. Set up port forwarding:
 *    ```
 *    adb forward tcp:4242 tcp:4242
 *    ```
 *
 * 2. Run this test on the emulator:
 *    ```
 *    ./gradlew :rns-sample-app:connectedAndroidTest --tests "*LinkResponderTest*"
 *    ```
 *
 * 3. From the host, connect and establish a link:
 *    - Use Python: `python test-scripts/link_initiator.py`
 *    - Or run the host-side JUnit test
 *
 * The test will wait for a link to be established and then verify it's active.
 */
@RunWith(AndroidJUnit4::class)
class LinkResponderTest {

    companion object {
        private const val TAG = "LinkResponderTest"
        private const val TEST_PORT = 4242
        private const val LINK_TIMEOUT_SECONDS = 60L
    }

    private var serverInterface: TCPServerInterface? = null
    private var destination: Destination? = null
    private var identity: Identity? = null

    @Before
    fun setup() {
        Log.i(TAG, "Setting up link responder test")

        // Stop any existing Transport
        Transport.stop()
        Thread.sleep(200)

        // Start fresh Transport
        Transport.start(enableTransport = false)
        Log.i(TAG, "Transport started")
    }

    @After
    fun teardown() {
        Log.i(TAG, "Tearing down link responder test")

        serverInterface?.let {
            Transport.deregisterInterface(InterfaceAdapter.getOrCreate(it))
            it.detach()
        }
        serverInterface = null

        Transport.stop()
        Thread.sleep(200)
    }

    @Test
    fun linkResponderAcceptsConnection() {
        // Create identity and destination
        identity = Identity.create()
        Log.i(TAG, "Created identity: ${identity!!.hexHash}")

        destination = Destination.create(
            identity = identity!!,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "linktest",
            aspects = arrayOf("responder")
        )
        Log.i(TAG, "Created destination: ${destination!!.hexHash}")

        // Enable link requests on this destination
        destination!!.acceptLinkRequests = true

        // Track established links
        val establishedLink = AtomicReference<Link?>(null)
        val linkLatch = CountDownLatch(1)

        destination!!.setLinkEstablishedCallback { linkAny ->
            val link = linkAny as Link
            Log.i(TAG, "Link established! ID: ${link.linkId.toHexString()}")
            establishedLink.set(link)
            linkLatch.countDown()
        }

        // Register destination with Transport
        Transport.registerDestination(destination!!)

        // Start TCP server interface
        serverInterface = TCPServerInterface(
            name = "TestServer",
            bindAddress = "0.0.0.0",
            bindPort = TEST_PORT
        )

        serverInterface!!.onPacketReceived = { data, fromInterface ->
            Log.d(TAG, "Received packet: ${data.size} bytes from ${fromInterface.name}")
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        serverInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(serverInterface!!))

        Log.i(TAG, "TCP server listening on port $TEST_PORT")
        Log.i(TAG, "Destination hash: ${destination!!.hexHash}")
        Log.i(TAG, "Waiting for link establishment (${LINK_TIMEOUT_SECONDS}s timeout)...")

        // Announce our destination so initiator can find us
        destination!!.announce()
        Log.i(TAG, "Announced destination")

        // Wait for link to be established
        val linked = linkLatch.await(LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue("Link should be established within timeout", linked)

        val link = establishedLink.get()
        assertNotNull("Established link should not be null", link)
        assertEquals("Link should be in ACTIVE state", LinkConstants.ACTIVE, link!!.status)
        assertTrue("Responder should not be initiator", !link.initiator)

        Log.i(TAG, "Link test passed! Link ID: ${link.linkId.toHexString()}")

        // Keep the link alive briefly to allow initiator to verify
        Thread.sleep(2000)
    }

    @Test
    fun linkResponderWaitsForConnection() {
        // Simpler test that just sets up the responder and waits
        // Useful for manual testing with Python initiator

        identity = Identity.create()
        destination = Destination.create(
            identity = identity!!,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "linktest",
            aspects = arrayOf("manual")
        )

        destination!!.acceptLinkRequests = true

        val links = mutableListOf<Link>()
        destination!!.setLinkEstablishedCallback { linkAny ->
            val link = linkAny as Link
            Log.i(TAG, "Link established: ${link.linkId.toHexString()}")
            links.add(link)
        }

        Transport.registerDestination(destination!!)

        serverInterface = TCPServerInterface(
            name = "ManualTestServer",
            bindAddress = "0.0.0.0",
            bindPort = TEST_PORT
        )

        serverInterface!!.onPacketReceived = { data, fromInterface ->
            Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
        }

        serverInterface!!.start()
        Transport.registerInterface(InterfaceAdapter.getOrCreate(serverInterface!!))

        Log.i(TAG, "=".repeat(60))
        Log.i(TAG, "LINK RESPONDER READY")
        Log.i(TAG, "Port: $TEST_PORT")
        Log.i(TAG, "Destination: ${destination!!.hexHash}")
        Log.i(TAG, "")
        Log.i(TAG, "To connect from host:")
        Log.i(TAG, "  adb forward tcp:$TEST_PORT tcp:$TEST_PORT")
        Log.i(TAG, "  python test-scripts/link_initiator.py ${destination!!.hexHash}")
        Log.i(TAG, "=".repeat(60))

        // Announce destination periodically so late-connecting clients can find us
        val announceThread = Thread {
            var count = 0
            while (count < 30) { // Announce every 5 seconds for up to 2.5 minutes
                try {
                    destination!!.announce()
                    Log.i(TAG, "Announced destination (${count + 1})")
                    Thread.sleep(5000)
                    count++
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        announceThread.start()

        // Wait for connections (long timeout for manual testing)
        Thread.sleep(150_000)

        announceThread.interrupt()

        Log.i(TAG, "Test complete. ${links.size} links established.")
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
