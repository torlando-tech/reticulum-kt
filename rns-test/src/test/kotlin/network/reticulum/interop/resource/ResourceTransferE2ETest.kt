package network.reticulum.interop.resource

import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.resource.Resource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for Resource transfer between Kotlin and Python over a Link.
 *
 * Resources handle automatic chunking, compression, and retransmission
 * for reliable transfer of arbitrary-sized data over an encrypted link.
 */
@DisplayName("Resource Transfer E2E Tests")
class ResourceTransferE2ETest : RnsLiveTestBase() {

    /**
     * Establish a link and wait for both sides to be ready.
     * Returns the active link.
     */
    private fun establishLink(): Link {
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
        waitForPythonLink(5000)
        assertNotNull(link)
        assertEquals(LinkConstants.ACTIVE, link!!.status)

        return link!!
    }

    @Test
    @DisplayName("Kotlin can send resource to Python over link")
    @Timeout(60)
    fun `kotlin can send resource to python over link`() {
        val link = establishLink()

        // Set resource strategy to accept all on Kotlin side too
        link.setResourceStrategy(Link.ACCEPT_ALL)

        val testData = "Hello Resource from Kotlin! This is a test payload.".toByteArray()
        val completedLatch = CountDownLatch(1)
        val completedResource = AtomicReference<Resource>()

        println("  [Test] Sending ${testData.size} byte resource from Kotlin to Python...")

        val resource = Resource.create(
            data = testData,
            link = link,
            callback = { r ->
                completedResource.set(r)
                completedLatch.countDown()
            }
        )

        assertNotNull(resource)

        // Wait for transfer to complete on Kotlin side
        val completed = completedLatch.await(30, TimeUnit.SECONDS)
        assertTrue(completed, "Resource transfer should complete within 30 seconds")

        // Poll Python for received resource
        val deadline = System.currentTimeMillis() + 15_000
        var pythonResources: List<ReceivedResource> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            pythonResources = getPythonResources()
            if (pythonResources.isNotEmpty()) break
            Thread.sleep(500)
        }

        assertTrue(pythonResources.isNotEmpty(), "Python should receive the resource")
        assertTrue(
            testData.contentEquals(pythonResources[0].data),
            "Received resource data should match sent data"
        )
        println("  [Test] Kotlin → Python resource transfer verified!")

        link.teardown()
    }

    @Test
    @DisplayName("Python can send resource to Kotlin over link")
    @Timeout(60)
    fun `python can send resource to kotlin over link`() {
        val link = establishLink()

        // Configure Kotlin side to accept resources
        link.setResourceStrategy(Link.ACCEPT_ALL)

        val receivedResources = CopyOnWriteArrayList<ByteArray>()
        val receivedLatch = CountDownLatch(1)

        link.callbacks.resourceConcluded = { resourceObj ->
            val res = resourceObj as? Resource
            if (res != null) {
                val data = res.data
                if (data != null) {
                    receivedResources.add(data)
                    receivedLatch.countDown()
                }
            }
        }

        // Send resource from Python
        val testData = "Hello Resource from Python! This is a test payload.".toByteArray()
        println("  [Test] Sending ${testData.size} byte resource from Python to Kotlin...")
        val sendResult = python("rns_resource_send", "data" to testData)
        assertTrue(sendResult.getBoolean("sent"), "Python resource send should succeed")

        // Wait for Kotlin to receive
        val received = receivedLatch.await(30, TimeUnit.SECONDS)
        assertTrue(received, "Kotlin should receive the resource within 30 seconds")

        assertTrue(receivedResources.isNotEmpty(), "Kotlin should have received resource data")
        assertTrue(
            testData.contentEquals(receivedResources[0]),
            "Received resource data should match sent data"
        )
        println("  [Test] Python → Kotlin resource transfer verified!")

        link.teardown()
    }

    @Test
    @DisplayName("Large resource from Python triggers multi-part transfer")
    @Timeout(90)
    fun `large resource from python triggers multi-part transfer`() {
        val link = establishLink()

        // Configure Kotlin side to accept resources
        link.setResourceStrategy(Link.ACCEPT_ALL)

        val receivedResources = CopyOnWriteArrayList<ByteArray>()
        val receivedLatch = CountDownLatch(1)

        link.callbacks.resourceConcluded = { resourceObj ->
            val res = resourceObj as? Resource
            if (res != null) {
                val data = res.data
                if (data != null) {
                    receivedResources.add(data)
                    receivedLatch.countDown()
                }
            }
        }

        // Create data larger than a single SDU to force multi-part transfer
        // Link MDU is ~431 bytes, so 2000 bytes should force 5+ parts
        val testData = ByteArray(2000) { (it % 256).toByte() }

        println("  [Test] Sending ${testData.size} byte resource from Python (should be multi-part)...")
        val sendResult = python("rns_resource_send", "data" to testData)
        assertTrue(sendResult.getBoolean("sent"), "Python resource send should succeed")

        // Wait for Kotlin to receive
        val received = receivedLatch.await(60, TimeUnit.SECONDS)
        assertTrue(received, "Kotlin should receive large resource within 60 seconds")

        assertTrue(receivedResources.isNotEmpty(), "Kotlin should have received resource data")
        assertTrue(
            testData.contentEquals(receivedResources[0]),
            "Received large resource data should match (${testData.size} bytes)"
        )
        println("  [Test] Multi-part resource transfer verified! (${testData.size} bytes)")

        link.teardown()
    }
}
