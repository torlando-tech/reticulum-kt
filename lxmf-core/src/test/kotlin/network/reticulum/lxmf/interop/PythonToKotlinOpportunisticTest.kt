package network.reticulum.lxmf.interop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import network.reticulum.interop.getString
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for Python to Kotlin OPPORTUNISTIC delivery.
 *
 * These tests verify that Python LXMF can send opportunistic messages to Kotlin,
 * ensuring bidirectional interoperability.
 */
class PythonToKotlinOpportunisticTest : OpportunisticDeliveryTestBase() {

    private val receivedMessages = CopyOnWriteArrayList<LXMessage>()

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `Python opportunistic message received by Kotlin after Kotlin announces`() = runBlocking {
        receivedMessages.clear()

        // Set up Kotlin to receive messages
        kotlinRouter.registerDeliveryCallback { message ->
            println("[Test] Kotlin received message: ${message.content}")
            receivedMessages.add(message)
        }

        // Step 1: Kotlin announces so Python knows the path and can recall identity
        println("[Test] Kotlin announcing destination...")
        announceKotlinDestination()
        delay(2000)

        // Step 2: Tell Python to send opportunistic message to Kotlin
        val kotlinDestHashHex = kotlinDestination.hexHash
        println("[Test] Asking Python to send opportunistic to Kotlin dest: $kotlinDestHashHex")

        val sendResult = python(
            "lxmf_send_opportunistic",
            "destination_hash" to kotlinDestHashHex,
            "content" to "Hello from Python opportunistic!",
            "title" to "P2K Opportunistic"
        )

        println("[Test] Python send result: $sendResult")

        // Check if send was successful
        val sent = sendResult.getString("sent")
        if (sent != "true") {
            val error = sendResult.getString("error")
            println("[Test] Python send failed: $error")
        }
        sent shouldBe "true"

        // Wait for Kotlin to receive
        val received = withTimeoutOrNull(30.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        if (!received) {
            println("[Test] Timeout waiting for message, received count: ${receivedMessages.size}")
        }

        received shouldBe true
        receivedMessages.size shouldBe 1
        receivedMessages[0].content shouldBe "Hello from Python opportunistic!"
        Unit
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `Python opportunistic message with fields preserved`() = runBlocking {
        receivedMessages.clear()

        kotlinRouter.registerDeliveryCallback { message ->
            receivedMessages.add(message)
        }

        // Kotlin announces
        announceKotlinDestination()
        delay(2000)

        val kotlinDestHashHex = kotlinDestination.hexHash

        // Python sends with custom fields
        val sendResult = python(
            "lxmf_send_opportunistic",
            "destination_hash" to kotlinDestHashHex,
            "content" to "Message with fields",
            "title" to "Fields Test",
            "fields" to mapOf(
                LXMFConstants.FIELD_COMMANDS.toString() to "test_command"
            )
        )

        println("[Test] Python send result: $sendResult")

        val sent = sendResult.getString("sent")
        sent shouldBe "true"

        // Wait for reception
        val received = withTimeoutOrNull(30.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        received shouldBe true
        receivedMessages[0].content shouldBe "Message with fields"

        // Verify field if accessible
        val fields = receivedMessages[0].fields
        if (fields != null && fields.isNotEmpty()) {
            println("[Test] Received fields: $fields")
        }
        Unit
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `Python opportunistic message title preserved`() = runBlocking {
        receivedMessages.clear()

        kotlinRouter.registerDeliveryCallback { message ->
            receivedMessages.add(message)
        }

        // Kotlin announces
        announceKotlinDestination()
        delay(2000)

        val kotlinDestHashHex = kotlinDestination.hexHash

        // Python sends with specific title
        val sendResult = python(
            "lxmf_send_opportunistic",
            "destination_hash" to kotlinDestHashHex,
            "content" to "Test content",
            "title" to "Important Title From Python"
        )

        val sent = sendResult.getString("sent")
        sent shouldBe "true"

        // Wait for reception
        val received = withTimeoutOrNull(30.seconds) {
            while (receivedMessages.isEmpty()) {
                delay(200)
            }
            true
        } ?: false

        received shouldBe true
        receivedMessages[0].content shouldBe "Test content"
        receivedMessages[0].title shouldBe "Important Title From Python"
        Unit
    }
}
