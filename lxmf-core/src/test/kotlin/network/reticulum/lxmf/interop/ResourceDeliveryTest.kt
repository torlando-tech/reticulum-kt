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
import network.reticulum.lxmf.MessageRepresentation
import network.reticulum.lxmf.MessageState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Resource transfer tests for large LXMF messages.
 *
 * Tests that messages exceeding LINK_PACKET_MAX_CONTENT (319 bytes)
 * are correctly transferred as Resources between Kotlin and Python.
 *
 * Threshold boundary:
 * - Content <= 319 bytes: PACKET representation
 * - Content > 319 bytes: RESOURCE representation
 *
 * Resource protocol verified working bidirectionally (Phase 9.3).
 */
class ResourceDeliveryTest : DirectDeliveryTestBase() {

    private val receivedMessages = CopyOnWriteArrayList<LXMessage>()

    private fun registerDeliveryCallback() {
        kotlinRouter.registerDeliveryCallback { message ->
            println("[KT] Received message: ${message.title} - ${message.content.take(50)}...")
            receivedMessages.add(message)
        }
    }

    /**
     * Create a test message with specified content size.
     */
    private fun createTestMessage(
        contentSize: Int,
        title: String = "Resource Test"
    ): LXMessage {
        val content = "X".repeat(contentSize)
        val pythonDest = createPythonDestination()!!
        return LXMessage.create(
            destination = pythonDest,
            source = kotlinDestination,
            content = content,
            title = title,
            desiredMethod = DeliveryMethod.DIRECT
        )
    }

    // ===== Threshold Boundary Tests =====
    //
    // The LXMF threshold calculation considers the "content_size" which is:
    //   content_size = len(packed_payload) - TIMESTAMP_SIZE - STRUCT_OVERHEAD
    // where packed_payload is msgpack([timestamp, title, content, fields]).
    //
    // This means content_size includes title + content + fields + msgpack overhead.
    // With empty title and empty fields:
    //   319-byte content -> content_size = 319 -> PACKET
    //   320-byte content -> content_size = 320 -> RESOURCE
    //
    // These tests use empty title to test the exact boundary.

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `message at 319 bytes content uses PACKET representation`() = runBlocking {
        println("\n=== THRESHOLD TEST: 319 bytes -> PACKET ===\n")

        // Create message with exactly 319 bytes content (at threshold)
        // Using empty title to test exact boundary
        val message = createTestMessage(
            contentSize = LXMFConstants.LINK_PACKET_MAX_CONTENT,  // 319
            title = ""  // Empty title for precise threshold testing
        )

        println("[KT] Content size: ${message.content.length} bytes")
        println("[KT] Title size: ${message.title.length} bytes")
        println("[KT] LINK_PACKET_MAX_CONTENT: ${LXMFConstants.LINK_PACKET_MAX_CONTENT}")

        // Pack the message (triggers representation detection)
        message.pack()

        println("[KT] Packed size: ${message.packedSize} bytes")
        println("[KT] Content size in packed: ${message.packedSize - LXMFConstants.LXMF_OVERHEAD} bytes")
        println("[KT] Representation: ${message.representation}")

        // Verify PACKET representation is selected
        message.representation shouldBe MessageRepresentation.PACKET

        println("\n[OK] 319 bytes content correctly uses PACKET representation")
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `message at 320 bytes content uses RESOURCE representation`() = runBlocking {
        println("\n=== THRESHOLD TEST: 320 bytes -> RESOURCE ===\n")

        // Create message with 320 bytes content (one byte over threshold)
        // Using empty title to test exact boundary
        val message = createTestMessage(
            contentSize = LXMFConstants.LINK_PACKET_MAX_CONTENT + 1,  // 320
            title = ""  // Empty title for precise threshold testing
        )

        println("[KT] Content size: ${message.content.length} bytes")
        println("[KT] Title size: ${message.title.length} bytes")
        println("[KT] LINK_PACKET_MAX_CONTENT: ${LXMFConstants.LINK_PACKET_MAX_CONTENT}")

        // Pack the message (triggers representation detection)
        message.pack()

        println("[KT] Packed size: ${message.packedSize} bytes")
        println("[KT] Content size in packed: ${message.packedSize - LXMFConstants.LXMF_OVERHEAD} bytes")
        println("[KT] Representation: ${message.representation}")

        // Verify RESOURCE representation is selected
        message.representation shouldBe MessageRepresentation.RESOURCE

        println("\n[OK] 320 bytes content correctly uses RESOURCE representation")
    }

    // ===== Bidirectional Large Message Delivery Tests =====
    //
    // Resource protocol verified working bidirectionally (Phase 9.3).
    // These tests verify:
    // 1. RESOURCE representation is correctly selected
    // 2. Message reaches DELIVERED state
    // 3. Content integrity is preserved

    @Test
    @Timeout(90, unit = TimeUnit.SECONDS)
    fun `Kotlin can send large message to Python via RESOURCE transfer`() = runBlocking {
        println("\n=== KOTLIN -> PYTHON RESOURCE DELIVERY TEST ===\n")

        // Clear any existing messages
        clearPythonMessages()

        // Create large message (500 bytes, well over threshold)
        val largeContent = "X".repeat(500)
        val pythonDest = createPythonDestination()
        pythonDest shouldNotBe null

        val message = LXMessage.create(
            destination = pythonDest!!,
            source = kotlinDestination,
            content = largeContent,
            title = "Large K->P",
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Track callback state
        val callbackFired = AtomicBoolean(false)
        val finalState = AtomicReference<MessageState>(MessageState.GENERATING)

        message.deliveryCallback = { msg ->
            println("[KT] Delivery callback! State: ${msg.state}")
            callbackFired.set(true)
            finalState.set(msg.state)
        }

        // Pack and verify RESOURCE representation selected
        message.pack()
        message.representation shouldBe MessageRepresentation.RESOURCE
        println("[KT] Message representation: ${message.representation}")
        println("[KT] Sending ${largeContent.length} byte message...")

        kotlinRouter.handleOutbound(message)

        // Wait for message to progress past OUTBOUND (indicates delivery attempt started)
        val progressedPastOutbound = withTimeoutOrNull(30.seconds) {
            while (message.state == MessageState.GENERATING || message.state == MessageState.OUTBOUND) {
                delay(200)
            }
            true
        }

        val currentState = if (callbackFired.get()) finalState.get() else message.state
        println("[KT] Message state: $currentState")

        // Verify message reached DELIVERED state (Resource proof received)
        currentState shouldBe MessageState.DELIVERED
        println("[KT] Message reached DELIVERED state")

        // Verify Python received the message with content intact
        val received = withTimeoutOrNull(20.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(500)
                messages = getPythonMessages()
            }
            messages
        }

        received shouldNotBe null
        received!!.isNotEmpty() shouldBe true
        received[0].title shouldBe "Large K->P"
        received[0].content shouldBe largeContent
        println("[KT] Python received message with content intact!")
        println("     Content: ${received[0].content.length} bytes")

        println("\n[OK] Kotlin -> Python RESOURCE delivery test complete")
        println("     Representation: RESOURCE (verified)")
        println("     State: $currentState")
        println("     Python received: true")
    }

    @Test
    @Timeout(90, unit = TimeUnit.SECONDS)
    fun `Python can send large message to Kotlin via RESOURCE transfer`() = runBlocking {
        println("\n=== PYTHON -> KOTLIN RESOURCE DELIVERY TEST ===\n")

        registerDeliveryCallback()
        receivedMessages.clear()

        // Announce Kotlin's destination so Python can discover it
        println("[KT] Announcing Kotlin destination: ${kotlinDestination.hexHash}...")
        kotlinDestination.announce()
        delay(2000)

        // Create large content for Python to send
        val largeContent = "Y".repeat(500)
        println("[PY] Sending ${largeContent.length} byte message from Python to Kotlin...")

        // Have Python send a large message to Kotlin
        val result = python("lxmf_send_direct",
            "destination_hash" to kotlinDestination.hexHash,
            "content" to largeContent,
            "title" to "Large P->K"
        )
        println("[PY] Send result: ${result.getString("status")}")

        // Wait for Kotlin to receive it
        val received = withTimeoutOrNull(45.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(500)
            }
            receivedMessages.toList()
        }

        // Verify Kotlin received the message
        received shouldNotBe null
        received!!.isNotEmpty() shouldBe true
        received[0].title shouldBe "Large P->K"
        received[0].content shouldBe largeContent
        println("\n[OK] Python -> Kotlin RESOURCE delivery successful!")
        println("     Title: ${received[0].title}")
        println("     Content: ${received[0].content.length} bytes")
        println("[OK] Python -> Kotlin RESOURCE delivery test complete")
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `bidirectional large message delivery works`() = runBlocking {
        println("\n=== BIDIRECTIONAL RESOURCE DELIVERY TEST ===\n")

        registerDeliveryCallback()
        receivedMessages.clear()
        clearPythonMessages()

        // Large content for both directions
        val k2pContent = "K".repeat(500)
        val p2kContent = "P".repeat(500)

        // 1. Kotlin -> Python
        println("[Test] Step 1: Kotlin -> Python (500 bytes)")
        val pythonDest = createPythonDestination()!!
        val k2pMessage = LXMessage.create(
            destination = pythonDest,
            source = kotlinDestination,
            content = k2pContent,
            title = "BiDi K->P Large",
            desiredMethod = DeliveryMethod.DIRECT
        )

        // Verify RESOURCE representation
        k2pMessage.pack()
        k2pMessage.representation shouldBe MessageRepresentation.RESOURCE
        println("     RESOURCE representation verified")

        kotlinRouter.handleOutbound(k2pMessage)

        // Wait for progress
        delay(5000)

        // Check if Python received
        val pythonReceived = withTimeoutOrNull(25.seconds) {
            var messages = getPythonMessages()
            while (messages.isEmpty()) {
                delay(500)
                messages = getPythonMessages()
            }
            messages
        }

        // Verify K->P delivery
        pythonReceived shouldNotBe null
        pythonReceived!!.isNotEmpty() shouldBe true
        pythonReceived[0].content shouldBe k2pContent
        println("     [OK] Kotlin -> Python: received ${pythonReceived[0].content.length} bytes")

        // 2. Python -> Kotlin
        println("[Test] Step 2: Python -> Kotlin (500 bytes)")
        kotlinDestination.announce()
        delay(2000)

        python("lxmf_send_direct",
            "destination_hash" to kotlinDestination.hexHash,
            "content" to p2kContent,
            "title" to "BiDi P->K Large"
        )

        val kotlinReceived = withTimeoutOrNull(30.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(500)
            }
            receivedMessages.toList()
        }

        // Verify P->K delivery
        kotlinReceived shouldNotBe null
        kotlinReceived!!.isNotEmpty() shouldBe true
        kotlinReceived[0].content shouldBe p2kContent
        println("     [OK] Python -> Kotlin: received ${kotlinReceived[0].content.length} bytes")

        println("\n[OK] Bidirectional RESOURCE delivery test complete")
        println("     K->P: delivered")
        println("     P->K: delivered")
    }
}
