package network.reticulum.lxmf.interop

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getDouble
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import network.reticulum.lxmf.LXMFConstants
import network.reticulum.lxmf.LXMessage
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Base class for LXMF interoperability tests with Python.
 *
 * Provides shared test fixtures for LXMF message testing including:
 * - Pre-configured source and destination identities
 * - Helper methods for creating test messages
 * - Helper methods for verifying messages in Python via bridge
 * - Soft assertion helpers for field-by-field comparison
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LXMFInteropTestBase : InteropTestBase() {

    /** Source identity for test messages */
    protected lateinit var testSourceIdentity: Identity

    /** Destination identity for test messages */
    protected lateinit var testDestIdentity: Identity

    /** Source destination with lxmf/delivery app name */
    protected lateinit var sourceDestination: Destination

    /** Destination destination with lxmf/delivery app name */
    protected lateinit var destDestination: Destination

    @BeforeAll
    fun setupLXMFFixtures() {
        // Create test identities
        testSourceIdentity = Identity.create()
        testDestIdentity = Identity.create()

        // Create destinations for lxmf/delivery
        sourceDestination = Destination.create(
            identity = testSourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = LXMFConstants.APP_NAME,
            "delivery"
        )

        destDestination = Destination.create(
            identity = testDestIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = LXMFConstants.APP_NAME,
            "delivery"
        )

        // Remember source identity for signature validation during unpacking
        val dummyPacketHash = ByteArray(32) { it.toByte() }
        Identity.remember(
            packetHash = dummyPacketHash,
            destHash = sourceDestination.hash,
            publicKey = testSourceIdentity.getPublicKey()
        )
    }

    /**
     * Create a test LXMessage with the given content and optional fields.
     *
     * @param content Message content text
     * @param title Message title (default empty)
     * @param fields Extended fields map (default empty)
     * @return New LXMessage ready for packing
     */
    protected fun createTestMessage(
        content: String,
        title: String = "",
        fields: MutableMap<Int, Any> = mutableMapOf()
    ): LXMessage {
        return LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = content,
            title = title,
            fields = fields
        )
    }

    /**
     * Verify an LXMF message in Python via the bridge.
     *
     * Sends the packed bytes to Python's lxmf_unpack command and returns
     * the parsed result. Logs timing information.
     *
     * @param lxmfBytes Packed LXMF message bytes
     * @return JsonObject with unpacked message fields
     */
    protected fun verifyInPython(lxmfBytes: ByteArray): JsonObject {
        val startTime = System.currentTimeMillis()

        val result = python("lxmf_unpack", "lxmf_bytes" to lxmfBytes.toHex())

        val elapsed = System.currentTimeMillis() - startTime
        println("  [Python] lxmf_unpack completed in ${elapsed}ms")

        return result
    }

    /**
     * Assert that a Kotlin LXMessage matches the Python unpacked result.
     *
     * Uses soft assertions to collect all failures before throwing.
     * Compares: destinationHash, sourceHash, timestamp, title, content, messageHash
     *
     * @param kotlinMessage The original Kotlin message (must be packed)
     * @param pythonResult The result from verifyInPython()
     * @param message Optional message prefix for assertion failures
     */
    protected fun assertMessageMatchesPython(
        kotlinMessage: LXMessage,
        pythonResult: JsonObject,
        message: String = ""
    ) {
        val prefix = if (message.isNotEmpty()) "$message: " else ""

        assertSoftly {
            // Compare destination hash
            kotlinMessage.destinationHash.toHex() shouldBe pythonResult.getString("destination_hash")
                .also { println("  ${prefix}destination_hash: ${kotlinMessage.destinationHash.toHex()}") }

            // Compare source hash
            kotlinMessage.sourceHash.toHex() shouldBe pythonResult.getString("source_hash")
                .also { println("  ${prefix}source_hash: ${kotlinMessage.sourceHash.toHex()}") }

            // Compare timestamp (float64 precision)
            kotlinMessage.timestamp shouldBe pythonResult.getDouble("timestamp")
                .also { println("  ${prefix}timestamp: ${kotlinMessage.timestamp}") }

            // Compare title
            kotlinMessage.title shouldBe pythonResult.getString("title")
                .also { println("  ${prefix}title: '${kotlinMessage.title}'") }

            // Compare content
            kotlinMessage.content shouldBe pythonResult.getString("content")
                .also { println("  ${prefix}content: '${kotlinMessage.content.take(50)}${if (kotlinMessage.content.length > 50) "..." else ""}'") }

            // Compare message hash
            kotlinMessage.hash?.toHex() shouldBe pythonResult.getString("message_hash")
                .also { println("  ${prefix}message_hash: ${kotlinMessage.hash?.toHex()}") }
        }
    }
}
