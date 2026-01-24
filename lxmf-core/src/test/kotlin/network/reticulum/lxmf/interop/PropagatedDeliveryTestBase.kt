package network.reticulum.lxmf.interop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import network.reticulum.identity.Identity
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.getString
import network.reticulum.lxmf.LXMRouter
import org.junit.jupiter.api.BeforeAll

/**
 * Base class for propagated delivery interop tests.
 *
 * Extends DirectDeliveryTestBase to add Python propagation node management.
 * The propagation node allows testing of LXMF PROPAGATED delivery method
 * where messages are stored on an intermediary node for later retrieval.
 *
 * Test architecture:
 * - Kotlin LXMRouter connects via TCP to Python RNS
 * - Python LXMRouter runs a propagation node (enabled via enable_propagation())
 * - Kotlin can submit messages to the propagation node
 * - Kotlin can retrieve messages from the propagation node
 */
abstract class PropagatedDeliveryTestBase : DirectDeliveryTestBase() {

    // Propagation node state
    protected var propagationNodeHash: ByteArray? = null
    protected var propagationNodeIdentityPubKey: ByteArray? = null
    protected var propagationStampCost: Int = 8 // Default low cost for fast tests

    @BeforeAll
    override fun setupDirectDelivery() {
        // First set up the base direct delivery infrastructure
        super.setupDirectDelivery()

        // Start propagation node on Python LXMF router
        println("  [Setup] Starting Python propagation node with stamp_cost=$propagationStampCost...")
        val nodeResult = python("propagation_node_start", "stamp_cost" to propagationStampCost)

        propagationNodeHash = nodeResult.getBytes("propagation_hash")
        propagationNodeIdentityPubKey = nodeResult.getBytes("identity_public_key")

        println("  [Setup] Propagation node started: ${propagationNodeHash?.toHex()}")
        println("  [Setup] Propagation stamp cost: ${nodeResult.getInt("stamp_cost")}")

        // Register the propagation node's identity with Kotlin so we can create destinations
        if (propagationNodeHash != null && propagationNodeIdentityPubKey != null) {
            println("  [Setup] Registering propagation node identity with Kotlin...")
            Identity.remember(
                packetHash = propagationNodeHash!!,
                destHash = propagationNodeHash!!,
                publicKey = propagationNodeIdentityPubKey!!,
                appData = null
            )

            // Create a PropagationNode entry in the Kotlin router
            // This simulates receiving an announce from the propagation node
            val nodeIdentity = Identity.recall(propagationNodeHash!!)
            if (nodeIdentity != null) {
                val node = LXMRouter.PropagationNode(
                    destHash = propagationNodeHash!!,
                    identity = nodeIdentity,
                    displayName = "Test Propagation Node",
                    stampCost = propagationStampCost,
                    stampCostFlexibility = 0,
                    isActive = true
                )

                // Set as active propagation node
                // Note: setActivePropagationNode requires the node to be in propagationNodes map
                // We need to manually add it since we're not receiving a real announce
                // Use reflection or the handlePropagationAnnounce method with fake appData
                try {
                    kotlinRouter.setActivePropagationNode(propagationNodeHash!!.toHex())
                } catch (e: Exception) {
                    println("  [Setup] Note: Could not set active propagation node via setActivePropagationNode")
                    println("  [Setup] Propagation node hash: ${propagationNodeHash!!.toHex()}")
                }
            }

            println("  [Setup] Propagation node identity registered")
        }

        // Announce the propagation node so Kotlin can discover it
        println("  [Setup] Announcing propagation node...")
        val announceResult = python("propagation_node_announce")
        println("  [Setup] Propagation node announced: ${announceResult.getString("announced")}")

        // Wait for announce to propagate
        Thread.sleep(2000)

        println("  [Setup] Propagated delivery infrastructure ready")
    }

    /**
     * Get messages stored on the Python propagation node.
     */
    protected fun getPropagationNodeMessages(): List<PropagationEntry> {
        val result = python("propagation_node_get_messages")
        val messagesJson = result["messages"] ?: return emptyList()

        return when (messagesJson) {
            is JsonArray -> messagesJson.map { elem ->
                val obj = elem as JsonObject
                PropagationEntry(
                    transientId = obj.getBytes("transient_id"),
                    destinationHash = obj.getBytes("destination_hash"),
                    receivedTime = obj.getString("received_time").toDouble(),
                    size = obj.getInt("size"),
                    stampValue = obj.getInt("stamp_value")
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Wait for at least one message to appear in the propagation node.
     */
    protected fun waitForMessageInPropagationNode(timeoutMs: Long = 30000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = getPropagationNodeMessages()
            if (messages.isNotEmpty()) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Submit a test message for a Kotlin recipient via the Python propagation node.
     *
     * This stores a message in the propagation node that Kotlin can retrieve.
     * The recipient must have been announced first so Python knows their identity.
     */
    protected fun submitTestMessageForKotlin(content: String, title: String = ""): SubmissionResult {
        // First announce Kotlin's destination so Python can encrypt for it
        println("  [Test] Announcing Kotlin destination for message encryption...")
        kotlinDestination.announce()
        Thread.sleep(1000) // Wait for announce

        val result = python(
            "propagation_node_submit_for_recipient",
            "recipient_hash" to kotlinDestination.hexHash,
            "content" to content,
            "title" to title
        )

        val submitted = result.getString("submitted") == "true"
        val transientId = if (submitted) result.getBytes("transient_id") else null
        val error = if (!submitted) result.getString("error") else null

        return SubmissionResult(
            submitted = submitted,
            transientId = transientId,
            error = error
        )
    }

    /**
     * Data class representing an entry in the propagation node's message store.
     */
    data class PropagationEntry(
        val transientId: ByteArray,
        val destinationHash: ByteArray,
        val receivedTime: Double,
        val size: Int,
        val stampValue: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PropagationEntry

            if (!transientId.contentEquals(other.transientId)) return false
            if (!destinationHash.contentEquals(other.destinationHash)) return false
            if (receivedTime != other.receivedTime) return false
            if (size != other.size) return false
            if (stampValue != other.stampValue) return false

            return true
        }

        override fun hashCode(): Int {
            var result = transientId.contentHashCode()
            result = 31 * result + destinationHash.contentHashCode()
            result = 31 * result + receivedTime.hashCode()
            result = 31 * result + size
            result = 31 * result + stampValue
            return result
        }
    }

    /**
     * Result of submitting a test message to the propagation node.
     */
    data class SubmissionResult(
        val submitted: Boolean,
        val transientId: ByteArray?,
        val error: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SubmissionResult

            if (submitted != other.submitted) return false
            if (transientId != null) {
                if (other.transientId == null) return false
                if (!transientId.contentEquals(other.transientId)) return false
            } else if (other.transientId != null) return false
            if (error != other.error) return false

            return true
        }

        override fun hashCode(): Int {
            var result = submitted.hashCode()
            result = 31 * result + (transientId?.contentHashCode() ?: 0)
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }
}
