package network.reticulum.interop.link

import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getString
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for Link handshake between Kotlin initiator and Python responder.
 *
 * Tests the full LINKREQUEST → LINKRESPONSE → LINKPROOF protocol over TCP.
 * Kotlin creates a Link to a Python destination; Python's link_established_callback
 * fires when the handshake completes.
 */
@DisplayName("Link Handshake E2E Tests")
class LinkHandshakeE2ETest : RnsLiveTestBase() {

    @Test
    @DisplayName("Kotlin can establish link to Python destination")
    @Timeout(30)
    fun `kotlin can establish link to python destination`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var establishedLink: Link? = null

        val link = Link.create(
            destination,
            establishedCallback = { l ->
                establishedLink = l
                establishedLatch.countDown()
            }
        )

        assertNotNull(link)
        println("  [Test] Link created, waiting for handshake...")

        // Wait for Kotlin side to establish
        val kotlinEstablished = establishedLatch.await(15, TimeUnit.SECONDS)
        assertTrue(kotlinEstablished, "Link should establish within 15 seconds (Kotlin side)")
        assertEquals(LinkConstants.ACTIVE, establishedLink!!.status, "Link status should be ACTIVE")

        // Verify Python side also sees the link
        val pythonLink = waitForPythonLink(5000)
        assertNotNull(pythonLink, "Python should also see the established link")
        assertEquals("active", pythonLink.getString("status"), "Python link status should be active")

        println("  [Test] Link established on both sides!")

        // Cleanup
        link.teardown()
    }

    @Test
    @DisplayName("Data can be sent over established link from Kotlin to Python")
    @Timeout(30)
    fun `data can be sent from kotlin to python over link`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000) // Wait for Python side too

        // Send data from Kotlin to Python
        val testData = "Hello from Kotlin!".toByteArray()
        println("  [Test] Sending ${testData.size} bytes from Kotlin to Python...")
        val sent = link!!.send(testData)
        assertTrue(sent, "send() should return true")

        // Poll Python for received packets
        val deadline = System.currentTimeMillis() + 10_000
        var pythonPackets: List<ByteArray> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            pythonPackets = getPythonPackets()
            if (pythonPackets.isNotEmpty()) break
            Thread.sleep(200)
        }

        assertTrue(pythonPackets.isNotEmpty(), "Python should receive at least one packet")
        assertTrue(
            testData.contentEquals(pythonPackets[0]),
            "Received data should match sent data"
        )
        println("  [Test] Kotlin → Python packet delivery verified!")

        link!!.teardown()
    }

    @Test
    @DisplayName("Data can be sent over established link from Python to Kotlin")
    @Timeout(30)
    fun `data can be sent from python to kotlin over link`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null
        val receivedPackets = CopyOnWriteArrayList<ByteArray>()

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                l.callbacks.packet = { data, _ ->
                    receivedPackets.add(data)
                }
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Send data from Python to Kotlin
        val testData = "Hello from Python!".toByteArray()
        println("  [Test] Sending ${testData.size} bytes from Python to Kotlin...")
        val sent = sendFromPython(testData)
        assertTrue(sent, "Python send should succeed")

        // Wait for Kotlin to receive
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (receivedPackets.isNotEmpty()) break
            Thread.sleep(200)
        }

        assertTrue(receivedPackets.isNotEmpty(), "Kotlin should receive at least one packet")
        assertTrue(
            testData.contentEquals(receivedPackets[0]),
            "Received data should match sent data"
        )
        println("  [Test] Python → Kotlin packet delivery verified!")

        link!!.teardown()
    }

    @Test
    @DisplayName("Bidirectional data exchange over link")
    @Timeout(30)
    fun `bidirectional data exchange over link`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null
        val kotlinReceived = CopyOnWriteArrayList<ByteArray>()

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                l.callbacks.packet = { data, _ ->
                    kotlinReceived.add(data)
                }
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Clear any stale packets
        python("rns_link_clear_packets")

        // Send Kotlin → Python
        val k2pData = "Kotlin to Python message".toByteArray()
        assertTrue(link!!.send(k2pData), "Kotlin send should succeed")

        // Send Python → Kotlin
        val p2kData = "Python to Kotlin message".toByteArray()
        assertTrue(sendFromPython(p2kData), "Python send should succeed")

        // Verify both sides received data
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val pythonPackets = getPythonPackets()
            if (pythonPackets.isNotEmpty() && kotlinReceived.isNotEmpty()) break
            Thread.sleep(200)
        }

        // Verify Kotlin → Python
        val pythonPackets = getPythonPackets()
        assertTrue(pythonPackets.isNotEmpty(), "Python should receive K→P packet")
        assertTrue(k2pData.contentEquals(pythonPackets[0]), "K→P data should match")

        // Verify Python → Kotlin
        assertTrue(kotlinReceived.isNotEmpty(), "Kotlin should receive P→K packet")
        assertTrue(p2kData.contentEquals(kotlinReceived[0]), "P→K data should match")

        println("  [Test] Bidirectional exchange verified!")

        link!!.teardown()
    }

    @Test
    @DisplayName("Link teardown from Kotlin side")
    @Timeout(30)
    fun `link teardown from kotlin side`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                establishedLatch.countDown()
            },
            closedCallback = {
                closedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Teardown from Kotlin side
        println("  [Test] Tearing down link from Kotlin...")
        link!!.teardown()

        assertEquals(LinkConstants.CLOSED, link!!.status, "Kotlin link should be CLOSED")

        // Verify Python detects closure
        val deadline = System.currentTimeMillis() + 10_000
        var pythonClosed = false
        while (System.currentTimeMillis() < deadline) {
            val result = python("rns_get_established_link")
            val status = result.getString("status")
            if (status == "closed") {
                pythonClosed = true
                break
            }
            Thread.sleep(200)
        }

        assertTrue(pythonClosed, "Python should detect link closure")
        println("  [Test] Kotlin-side teardown verified on both sides!")
    }

    @Test
    @DisplayName("Link teardown from Python side")
    @Timeout(30)
    fun `link teardown from python side`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                establishedLatch.countDown()
            },
            closedCallback = {
                closedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Teardown from Python side
        println("  [Test] Tearing down link from Python...")
        val closeResult = python("rns_link_close")
        assertTrue(closeResult.getBoolean("closed"), "Python close should succeed")

        // Verify Kotlin detects closure via callback
        val kotlinClosed = closedLatch.await(10, TimeUnit.SECONDS)
        assertTrue(kotlinClosed, "Kotlin should detect link closure via callback")
        assertEquals(LinkConstants.CLOSED, link!!.status, "Kotlin link should be CLOSED")

        println("  [Test] Python-side teardown verified on both sides!")
    }
}
