package network.reticulum.lxmf.interop

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
}
