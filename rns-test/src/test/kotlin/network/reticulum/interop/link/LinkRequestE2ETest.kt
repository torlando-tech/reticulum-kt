package network.reticulum.interop.link

import network.reticulum.destination.RequestPolicy
import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getInt
import network.reticulum.interop.getList
import network.reticulum.interop.getString
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.link.RequestReceipt
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
 * E2E tests for Link request/response between Kotlin and Python.
 *
 * Tests the full request lifecycle implemented in Phases 2 and 3:
 * - Small requests (< MDU): sent as packets
 * - Large requests (> MDU): sent as Resources with requestId
 * - Response handling via callbacks
 * - Request timeout monitoring
 *
 * Python side registers request handlers that echo data or return
 * static/large responses.
 */
@DisplayName("Link Request/Response E2E Tests")
class LinkRequestE2ETest : RnsLiveTestBase() {

    /**
     * Establish a link with request handlers registered on both sides.
     */
    private fun establishLinkWithRequestHandlers(
        kotlinHandlerPath: String = "/test/echo",
        pythonHandlerPath: String = "/test/echo"
    ): Link {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                l.setResourceStrategy(Link.ACCEPT_ALL)
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)
        assertNotNull(link)
        assertEquals(LinkConstants.ACTIVE, link!!.status)

        // Register request handler on Python side
        val regResult = python(
            "rns_register_request_handler",
            "path" to pythonHandlerPath
        )
        assertTrue(regResult.getBoolean("registered"), "Python handler should register")

        return link!!
    }

    @Test
    @DisplayName("Small request from Kotlin gets response from Python")
    @Timeout(30)
    fun `small request from kotlin gets response from python`() {
        val link = establishLinkWithRequestHandlers()

        val responseLatch = CountDownLatch(1)
        val responseRef = AtomicReference<RequestReceipt>()

        val requestData = "echo this data".toByteArray()
        println("  [Test] Sending small request K→P (${requestData.size} bytes)...")

        val receipt = link.request(
            path = "/test/echo",
            data = requestData,
            responseCallback = { r ->
                responseRef.set(r)
                responseLatch.countDown()
            },
            failedCallback = { _ ->
                println("  [Test] Request FAILED")
                responseLatch.countDown()
            }
        )

        assertNotNull(receipt, "Request should return a receipt")

        val gotResponse = responseLatch.await(20, TimeUnit.SECONDS)
        assertTrue(gotResponse, "Should get response within 20 seconds")

        val response = responseRef.get()
        assertNotNull(response, "Response receipt should not be null")

        val responseData = response.response as? ByteArray
        assertNotNull(responseData, "Response data should not be null")
        assertTrue(
            requestData.contentEquals(responseData),
            "Echo response should match request data"
        )
        println("  [Test] Small request/response verified! (${responseData.size} bytes)")

        link.teardown()
    }

    @Test
    @DisplayName("Request with null data gets response")
    @Timeout(30)
    fun `request with null data gets response`() {
        // Register handler that returns static data
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                l.setResourceStrategy(Link.ACCEPT_ALL)
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Register handler with static response
        val staticResponse = "static response data".toByteArray()
        python(
            "rns_register_request_handler",
            "path" to "/test/static",
            "response_data" to staticResponse
        )

        val responseLatch = CountDownLatch(1)
        val responseRef = AtomicReference<RequestReceipt>()

        println("  [Test] Sending request with null data...")

        link!!.request(
            path = "/test/static",
            data = null,
            responseCallback = { r ->
                responseRef.set(r)
                responseLatch.countDown()
            },
            failedCallback = { r ->
                println("  [Test] Request FAILED")
                responseLatch.countDown()
            }
        )

        val gotResponse = responseLatch.await(20, TimeUnit.SECONDS)
        assertTrue(gotResponse, "Should get response within 20 seconds")

        val response = responseRef.get()
        assertNotNull(response?.response, "Should get static response data")

        println("  [Test] Null-data request/response verified!")

        link!!.teardown()
    }

    @Test
    @DisplayName("Request from Python to Kotlin handler")
    @Timeout(30)
    fun `request from python to kotlin handler`() {
        val destination = createPythonOutDestination()

        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(
            destination,
            establishedCallback = { l ->
                link = l
                l.setResourceStrategy(Link.ACCEPT_ALL)
                establishedLatch.countDown()
            }
        )

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // For initiator links, attachedDestination is null by default.
        // Attach the OUT destination so the link can find request handlers on it.
        // This matches Python where link.attached_interface is publicly writable.
        link!!.attachedDestination = destination
        val kotlinDest = link!!.attachedDestination
        assertNotNull(kotlinDest, "Link should have an attached destination")

        kotlinDest!!.registerRequestHandler(
            path = "/test/kotlin-echo",
            responseGenerator = { _, data, _, _, _, _ ->
                // Echo back the data with a prefix
                val prefix = "Kotlin echoes: ".toByteArray()
                if (data != null) prefix + data else prefix
            },
            allow = RequestPolicy.ALLOW_ALL
        )

        // Send request from Python
        val requestData = "Hello from Python".toByteArray()
        println("  [Test] Sending request P→K (${requestData.size} bytes)...")

        python("rns_clear_request_responses")
        val reqResult = python(
            "rns_link_request",
            "path" to "/test/kotlin-echo",
            "data" to requestData
        )
        assertTrue(reqResult.getBoolean("sent"), "Python request should send")

        // Poll for Python receiving the response
        val deadline = System.currentTimeMillis() + 20_000
        var responses: Int = 0
        while (System.currentTimeMillis() < deadline) {
            val result = python("rns_get_request_responses")
            responses = result.getInt("count")
            if (responses > 0) break
            Thread.sleep(300)
        }

        assertTrue(responses > 0, "Python should receive response from Kotlin")
        println("  [Test] P→K request/response verified!")

        link!!.teardown()
    }

    @Test
    @DisplayName("Multiple sequential requests")
    @Timeout(45)
    fun `multiple sequential requests`() {
        val link = establishLinkWithRequestHandlers()

        val requestCount = 3
        val responses = mutableListOf<ByteArray>()
        val allDoneLatch = CountDownLatch(requestCount)

        println("  [Test] Sending $requestCount sequential requests...")

        for (i in 0 until requestCount) {
            val data = "Request number $i".toByteArray()

            link.request(
                path = "/test/echo",
                data = data,
                responseCallback = { r ->
                    val resp = r.response
                    if (resp is ByteArray) {
                        synchronized(responses) { responses.add(resp) }
                    }
                    allDoneLatch.countDown()
                },
                failedCallback = { _ ->
                    allDoneLatch.countDown()
                }
            )

            // Small delay between requests
            Thread.sleep(500)
        }

        val allDone = allDoneLatch.await(30, TimeUnit.SECONDS)
        assertTrue(allDone, "All $requestCount requests should complete")
        assertEquals(requestCount, responses.size, "Should get $requestCount responses")

        println("  [Test] All $requestCount sequential requests completed!")

        link.teardown()
    }
}
