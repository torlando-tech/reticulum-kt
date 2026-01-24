package network.reticulum.lxmf.interop

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import network.reticulum.interop.getString
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.MessageState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Live delivery tests over real TCP connection.
 *
 * Tests actual LXMF message delivery in both directions:
 * - Kotlin -> Python (DIRECT delivery)
 * - Python -> Kotlin (DIRECT delivery)
 */
class LiveDeliveryTest : DirectDeliveryTestBase() {

    private val receivedMessages = CopyOnWriteArrayList<LXMessage>()

    private fun registerDeliveryCallback() {
        // Register delivery callback for Python -> Kotlin messages
        kotlinRouter.registerDeliveryCallback { message ->
            println("[KT] Received message: ${message.title} - ${message.content}")
            receivedMessages.add(message)
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `Kotlin can send LXMF message to Python via DIRECT delivery`() = runBlocking {
        println("\n=== KOTLIN -> PYTHON DELIVERY TEST ===\n")

        // Clear any existing messages
        clearPythonMessages()

        // Create destination for Python
        val pythonDest = createPythonDestination()
        pythonDest shouldNotBe null

        // Create and send message
        val message = LXMessage.create(
            destination = pythonDest!!,
            source = kotlinDestination,
            content = "Hello from Kotlin live test!",
            title = "Live Test K->P",
            desiredMethod = DeliveryMethod.DIRECT
        )

        println("[KT] Sending message to Python...")
        kotlinRouter.handleOutbound(message)

        // Wait for Python to receive it
        val received = withTimeoutOrNull(10.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(100)
                messages = getPythonMessages()
            }
            messages
        }

        received shouldNotBe null
        received!!.size shouldBe 1
        received[0].title shouldBe "Live Test K->P"
        received[0].content shouldBe "Hello from Kotlin live test!"

        println("\n✅ Kotlin -> Python delivery successful!")
        println("   Title: ${received[0].title}")
        println("   Content: ${received[0].content}")
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `Python can send LXMF message to Kotlin via DIRECT delivery`() = runBlocking {
        println("\n=== PYTHON -> KOTLIN DELIVERY TEST ===\n")

        registerDeliveryCallback()
        receivedMessages.clear()

        // First announce Kotlin's destination so Python can discover it
        println("[KT] Announcing Kotlin destination: ${kotlinDestination.hexHash}...")
        kotlinDestination.announce()
        delay(2000) // Wait for announce to propagate

        // Have Python send a message to Kotlin
        println("[PY] Sending message from Python to Kotlin...")
        val result = python("lxmf_send_direct",
            "destination_hash" to kotlinDestination.hexHash,
            "content" to "Hello from Python live test!",
            "title" to "Live Test P->K"
        )
        println("[PY] Send result: ${result.getString("status")}")

        // Wait for Kotlin to receive it
        val received = withTimeoutOrNull(15.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(100)
            }
            receivedMessages.toList()
        }

        received shouldNotBe null
        received!!.size shouldBe 1
        received[0].title shouldBe "Live Test P->K"
        received[0].content shouldBe "Hello from Python live test!"

        println("\n✅ Python -> Kotlin delivery successful!")
        println("   Title: ${received[0].title}")
        println("   Content: ${received[0].content}")
    }

    @Test
    @Timeout(90, unit = TimeUnit.SECONDS)
    fun `bidirectional LXMF delivery works`() = runBlocking {
        println("\n=== BIDIRECTIONAL DELIVERY TEST ===\n")

        registerDeliveryCallback()
        receivedMessages.clear()
        clearPythonMessages()

        // 1. Kotlin -> Python
        println("[Test] Step 1: Kotlin -> Python")
        val pythonDest = createPythonDestination()!!
        val k2pMessage = LXMessage.create(
            destination = pythonDest,
            source = kotlinDestination,
            content = "Bidirectional test K->P",
            title = "BiDi K->P",
            desiredMethod = DeliveryMethod.DIRECT
        )
        kotlinRouter.handleOutbound(k2pMessage)

        val pythonReceived = withTimeoutOrNull(10.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(100)
                messages = getPythonMessages()
            }
            messages
        }
        pythonReceived shouldNotBe null
        pythonReceived!!.size shouldBe 1
        println("   ✅ Kotlin -> Python: received")

        // 2. Python -> Kotlin
        println("[Test] Step 2: Python -> Kotlin")
        kotlinDestination.announce()
        delay(2000)

        python("lxmf_send_direct",
            "destination_hash" to kotlinDestination.hexHash,
            "content" to "Bidirectional test P->K",
            "title" to "BiDi P->K"
        )

        val kotlinReceived = withTimeoutOrNull(15.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(100)
            }
            receivedMessages.toList()
        }
        kotlinReceived shouldNotBe null
        kotlinReceived!!.size shouldBe 1
        println("   ✅ Python -> Kotlin: received")

        println("\n✅ Bidirectional delivery verified!")
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `delivery callback fires on successful K to P delivery`() = runBlocking {
        println("\n=== DELIVERY CALLBACK TEST ===\n")

        // Clear any existing messages
        clearPythonMessages()

        // Create destination for Python
        val pythonDest = createPythonDestination()
        pythonDest shouldNotBe null

        // Track callback invocation
        val callbackFired = AtomicBoolean(false)
        val callbackMessage = AtomicReference<LXMessage?>(null)

        // Create message with delivery callback
        val message = LXMessage.create(
            destination = pythonDest!!,
            source = kotlinDestination,
            content = "Testing delivery callback",
            title = "Callback Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Register delivery callback BEFORE sending
        message.deliveryCallback = { msg ->
            println("[KT] Delivery callback fired! Message state: ${msg.state}")
            callbackFired.set(true)
            callbackMessage.set(msg)
        }

        // Capture initial state
        val initialState = message.state
        println("[KT] Initial state: $initialState")
        initialState shouldBe MessageState.GENERATING

        // Send message
        println("[KT] Sending message with callback...")
        kotlinRouter.handleOutbound(message)

        // Wait for Python to receive it
        val received = withTimeoutOrNull(10.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(100)
                messages = getPythonMessages()
            }
            messages
        }

        received shouldNotBe null
        received!!.size shouldBe 1
        println("[KT] Python received message")

        // Wait a bit for delivery confirmation (callback may fire after receipt)
        delay(2000)

        // Verify callback fired
        callbackFired.get() shouldBe true
        println("[KT] Callback was fired: ${callbackFired.get()}")

        // Verify message state is DELIVERED or SENT
        val finalState = callbackMessage.get()?.state ?: message.state
        println("[KT] Final state: $finalState")
        listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain finalState

        println("\n✅ Delivery callback test passed!")
        println("   Initial state: $initialState")
        println("   Final state: $finalState")
        println("   Callback fired: ${callbackFired.get()}")
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `MessageState transitions correctly during delivery lifecycle`() = runBlocking {
        println("\n=== MESSAGE STATE LIFECYCLE TEST ===\n")

        // Clear any existing messages
        clearPythonMessages()

        // Create destination for Python
        val pythonDest = createPythonDestination()
        pythonDest shouldNotBe null

        // Track state transitions
        val stateTransitions = CopyOnWriteArrayList<MessageState>()

        // Create message
        val message = LXMessage.create(
            destination = pythonDest!!,
            source = kotlinDestination,
            content = "Testing state transitions",
            title = "State Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Capture initial state
        stateTransitions.add(message.state)
        println("[KT] State 1 (creation): ${message.state}")

        // Register delivery callback to capture final state
        message.deliveryCallback = { msg ->
            stateTransitions.add(msg.state)
            println("[KT] State (callback): ${msg.state}")
        }

        // Send message - this should transition state to OUTBOUND
        println("[KT] Sending message...")
        kotlinRouter.handleOutbound(message)

        // Capture state after outbound
        delay(100) // Brief delay to let state update
        stateTransitions.add(message.state)
        println("[KT] State 2 (after handleOutbound): ${message.state}")

        // Wait for Python to receive it
        val received = withTimeoutOrNull(10.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(100)
                messages = getPythonMessages()
            }
            messages
        }

        received shouldNotBe null
        println("[KT] Python received message")

        // Wait for delivery confirmation
        delay(2000)

        // Capture final state
        stateTransitions.add(message.state)
        println("[KT] State 3 (final): ${message.state}")

        // Verify state transitions
        println("\n[KT] All state transitions: $stateTransitions")

        // Initial state should be GENERATING
        stateTransitions[0] shouldBe MessageState.GENERATING

        // After handleOutbound, state should progress (OUTBOUND or beyond)
        // State after handleOutbound should be at least OUTBOUND
        val afterOutboundState = stateTransitions[1]
        listOf(
            MessageState.OUTBOUND,
            MessageState.SENDING,
            MessageState.SENT,
            MessageState.DELIVERED
        ) shouldContain afterOutboundState

        // Final state should be SENT or DELIVERED
        val finalState = stateTransitions.last()
        listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain finalState

        println("\n✅ MessageState lifecycle test passed!")
        println("   Transitions: ${stateTransitions.joinToString(" -> ")}")
    }
}
