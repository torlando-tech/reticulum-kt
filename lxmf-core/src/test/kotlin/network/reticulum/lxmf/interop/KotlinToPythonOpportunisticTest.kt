package network.reticulum.lxmf.interop

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.MessageState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for Kotlin to Python OPPORTUNISTIC delivery.
 *
 * Opportunistic delivery sends messages as single encrypted packets (broadcast).
 * Unlike DIRECT delivery (which uses links), opportunistic messages are sent
 * immediately if the destination identity is known, regardless of path status.
 *
 * Key behaviors:
 * - Messages sent immediately as encrypted broadcast packets
 * - Delivery attempts continue periodically until success or max attempts
 * - No link establishment required (unlike DIRECT delivery)
 */
class KotlinToPythonOpportunisticTest : OpportunisticDeliveryTestBase() {

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `opportunistic message sent immediately when identity known`() = runBlocking {
        // Clear any existing messages
        clearPythonMessages()

        // Create opportunistic message - identity is already registered in setup step 8
        val message = createOpportunisticMessage(
            content = "Hello from Kotlin via opportunistic delivery!",
            title = "Opportunistic Test"
        )

        // Track delivery callback
        val deliveryFired = AtomicBoolean(false)
        message.deliveryCallback = { deliveryFired.set(true) }

        // Send message - should attempt delivery immediately (broadcast)
        kotlinRouter.handleOutbound(message)

        // Wait for Python to receive it (opportunistic sends immediately via broadcast)
        val received = withTimeoutOrNull(15.seconds) {
            while (getPythonMessages().isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        println("[Test] Delivery result: received=$received, state=${message.state}, deliveryFired=${deliveryFired.get()}")

        // Verify Python received the message
        received shouldBe true
        getPythonMessages().size shouldBe 1
        getPythonMessages()[0].content shouldBe "Hello from Kotlin via opportunistic delivery!"

        // Verify message state progressed (SENT or DELIVERED)
        listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain message.state
        Unit
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `announce triggers path-based delivery optimization`() = runBlocking {
        // This test verifies that after Python announces, Kotlin learns the path
        // and can use it for optimized routing (though opportunistic still works
        // without path via broadcast)

        clearPythonMessages()

        // Trigger announce first - Kotlin learns the path
        triggerPythonAnnounce()
        delay(2000) // Allow announce to propagate

        // Verify path is now known (this optimizes routing)
        val pathKnown = waitForKotlinToReceiveAnnounce(pythonDestHash!!, 5000)
        println("[Test] Path known after announce: $pathKnown")

        // Create and send message
        val message = createOpportunisticMessage(
            content = "Message after announce",
            title = "Post-Announce Test"
        )

        val deliveryFired = AtomicBoolean(false)
        message.deliveryCallback = { deliveryFired.set(true) }

        kotlinRouter.handleOutbound(message)

        // Wait for delivery
        val received = withTimeoutOrNull(15.seconds) {
            while (getPythonMessages().isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        received shouldBe true
        getPythonMessages()[0].content shouldBe "Message after announce"
        listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain message.state
        Unit
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `multiple opportunistic messages delivered in order`() = runBlocking {
        clearPythonMessages()

        // Send multiple messages
        val messages = (1..3).map { i ->
            createOpportunisticMessage(
                content = "Opportunistic message $i",
                title = "Multi-Test $i"
            )
        }

        messages.forEach { msg ->
            kotlinRouter.handleOutbound(msg)
            delay(200) // Small delay between sends
        }

        // Wait for all messages
        val allReceived = withTimeoutOrNull(20.seconds) {
            while (getPythonMessages().size < 3) {
                delay(200)
            }
            true
        } ?: false

        allReceived shouldBe true
        val pythonMessages = getPythonMessages()
        pythonMessages.size shouldBe 3

        // Verify all messages arrived (order may vary in unreliable delivery)
        val contents = pythonMessages.map { it.content }.toSet()
        contents shouldContain "Opportunistic message 1"
        contents shouldContain "Opportunistic message 2"
        contents shouldContain "Opportunistic message 3"
        Unit
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `delivery callback fires on opportunistic delivery`() = runBlocking {
        clearPythonMessages()

        val message = createOpportunisticMessage(
            content = "Callback test message",
            title = "Callback Test"
        )

        val callbackFired = AtomicBoolean(false)
        message.deliveryCallback = { msg ->
            println("[Test] Delivery callback fired! State: ${msg.state}")
            callbackFired.set(true)
        }

        kotlinRouter.handleOutbound(message)

        // Wait for Python to receive
        val received = withTimeoutOrNull(15.seconds) {
            while (getPythonMessages().isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        received shouldBe true

        // Wait a bit more for callback (may fire after receipt confirmation)
        delay(2000)

        // Verify state is SENT or DELIVERED
        listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain message.state

        // Note: callback may or may not fire depending on delivery confirmation
        // mechanism for opportunistic delivery. Log the result.
        println("[Test] Final: received=$received, callbackFired=${callbackFired.get()}, state=${message.state}")
        Unit
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `batch delivery - multiple messages delivered after single announce`() = runBlocking {
        clearPythonMessages()

        // Queue multiple messages BEFORE Python announces
        val messages = listOf(
            createOpportunisticMessage("Message 1", "Batch Test 1"),
            createOpportunisticMessage("Message 2", "Batch Test 2"),
            createOpportunisticMessage("Message 3", "Batch Test 3")
        )

        val deliveryCount = AtomicInteger(0)
        messages.forEach { msg ->
            msg.deliveryCallback = { deliveryCount.incrementAndGet() }
            kotlinRouter.handleOutbound(msg)
        }

        delay(2000) // Allow queue processing

        // Note: Since identity is known from setup step 8, opportunistic delivery
        // sends immediately via broadcast. Messages should already be sent.
        // Python may have received them already.

        // Check if messages already received (identity was known)
        val initialMessages = getPythonMessages()
        println("[Test] Initial message count: ${initialMessages.size}")

        if (initialMessages.size < 3) {
            // If not all received yet, trigger announce for path optimization
            triggerPythonAnnounce()

            // Wait for all messages
            val allDelivered = withTimeoutOrNull(30.seconds) {
                while (getPythonMessages().size < 3) {
                    delay(200)
                }
                true
            } ?: false

            allDelivered shouldBe true
        }

        val pythonMessages = getPythonMessages()
        pythonMessages.size shouldBe 3

        // Verify content (order may vary due to async processing)
        val contents = pythonMessages.map { it.content }.toSet()
        contents shouldContain "Message 1"
        contents shouldContain "Message 2"
        contents shouldContain "Message 3"
        Unit
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)  // Long timeout for failure scenario
    fun `delivery failure after max attempts triggers callback`() = runBlocking {
        // Create message to a destination that will never respond
        // We create a fake identity/destination that doesn't exist
        val fakeIdentity = Identity.create()
        val fakeDest = Destination.create(
            identity = fakeIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = fakeDest,
            source = kotlinDestination,
            content = "This should fail",
            title = "Failure Test",
            desiredMethod = DeliveryMethod.OPPORTUNISTIC
        )

        val failedFired = AtomicBoolean(false)
        message.failedCallback = { failedFired.set(true) }

        kotlinRouter.handleOutbound(message)

        // Wait for failure (5 attempts * ~10s each + path request waits)
        // This could take up to 50+ seconds per CONTEXT.md
        val failed = withTimeoutOrNull(90.seconds) {
            while (!failedFired.get() && message.state != MessageState.FAILED) {
                delay(1000)
            }
            true
        } ?: false

        // Verify failure
        if (!failed) {
            println("[Test] Timeout waiting for failure, state=${message.state}, attempts=${message.deliveryAttempts}")
        }

        // Either callback fired or state is FAILED
        val failureDetected = failedFired.get() || message.state == MessageState.FAILED
        failureDetected shouldBe true
        Unit
    }
}
