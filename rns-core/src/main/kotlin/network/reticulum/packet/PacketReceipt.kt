package network.reticulum.packet

import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import network.reticulum.transport.TransportConstants
import kotlin.concurrent.thread

/**
 * Callbacks for packet receipt events.
 */
class PacketReceiptCallbacks {
    var delivery: ((PacketReceipt) -> Unit)? = null
    var timeout: ((PacketReceipt) -> Unit)? = null
}

/**
 * A PacketReceipt is used to track delivery confirmation for sent packets.
 *
 * Instances are created automatically when sending packets with create_receipt=true.
 * The receipt provides:
 * - Status tracking (SENT, DELIVERED, FAILED, CULLED)
 * - Delivery callbacks
 * - Timeout handling
 * - RTT measurement
 * - Proof validation
 */
class PacketReceipt internal constructor(
    private val packet: Packet
) {
    companion object {
        // Receipt status constants
        const val FAILED: Int = 0x00
        const val SENT: Int = 0x01
        const val DELIVERED: Int = 0x02
        const val CULLED: Int = 0xFF

        // Proof lengths
        val EXPL_LENGTH = RnsConstants.FULL_HASH_BYTES + RnsConstants.SIGNATURE_SIZE
        val IMPL_LENGTH = RnsConstants.SIGNATURE_SIZE
    }

    /** The full hash of the packet. */
    val hash: ByteArray = packet.packetHash

    /** The truncated hash of the packet. */
    val truncatedHash: ByteArray = packet.truncatedHash

    /** Whether the packet has been sent. */
    var sent: Boolean = true
        private set

    /** The timestamp when the packet was sent (in milliseconds). */
    val sentAt: Long = System.currentTimeMillis()

    /** Whether the delivery has been proven. */
    var proved: Boolean = false
        private set

    /** The current status of the receipt. */
    var status: Int = SENT
        private set

    /** The destination this packet was sent to. */
    private val destination: Destination? = packet.destination

    /** The link this packet was sent over (if applicable). */
    private var link: Link? = null

    /** Callbacks for delivery and timeout events. */
    val callbacks = PacketReceiptCallbacks()

    /** The timestamp when the receipt was concluded (delivered or failed). */
    var concludedAt: Long? = null
        private set

    /** The proof packet that delivered this receipt (if any). */
    var proofPacket: Packet? = null
        private set

    /** The timeout for this receipt in seconds. */
    var timeout: Double = calculateTimeout()
        private set

    /** Number of retries attempted. */
    var retries: Int = 0
        private set

    /**
     * Calculate the timeout based on destination type and hops.
     */
    private fun calculateTimeout(): Double {
        // For link destinations, use the link's timeout
        if (destination?.type == DestinationType.LINK) {
            // Link timeout is handled differently - for now use default
            // In full implementation, would access link.rtt * link.trafficTimeoutFactor
            return TransportConstants.DEFAULT_PER_HOP_TIMEOUT / 1000.0
        }

        // For other destinations, calculate based on hops
        val firstHopTimeout = Transport.firstHopTimeout(destination?.hash ?: packet.destinationHash)
        val hops = Transport.hopsTo(destination?.hash ?: packet.destinationHash) ?: 1
        val perHopTimeout = TransportConstants.DEFAULT_PER_HOP_TIMEOUT / 1000.0

        return (firstHopTimeout / 1000.0) + (perHopTimeout * hops)
    }

    /**
     * Get the round-trip time in seconds.
     *
     * @return RTT in seconds, or null if not yet delivered
     */
    fun getRtt(): Double? {
        return if (proved && concludedAt != null) {
            (concludedAt!! - sentAt) / 1000.0
        } else {
            null
        }
    }

    /**
     * Check if the receipt has timed out.
     *
     * @return true if the receipt has timed out
     */
    fun isTimedOut(): Boolean {
        return (sentAt + (timeout * 1000).toLong()) < System.currentTimeMillis()
    }

    /**
     * Check and handle timeout.
     * Updates status and fires timeout callback if timed out.
     *
     * @return true if timed out, false otherwise
     */
    fun checkTimeout(): Boolean {
        if (status == SENT && isTimedOut()) {
            status = if (retries > 0) {
                CULLED
            } else {
                FAILED
            }

            concludedAt = System.currentTimeMillis()

            // Fire timeout callback in separate thread
            callbacks.timeout?.let { callback ->
                thread(isDaemon = true) {
                    try {
                        callback(this)
                    } catch (e: Exception) {
                        // Log error but don't propagate
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * Set a custom timeout.
     *
     * @param timeout The timeout in seconds
     */
    fun setTimeout(timeout: Double) {
        this.timeout = timeout
    }

    /**
     * Set a callback to be called when delivery is confirmed.
     *
     * @param callback A function that takes this PacketReceipt as parameter
     */
    fun setDeliveryCallback(callback: (PacketReceipt) -> Unit) {
        callbacks.delivery = callback
    }

    /**
     * Set a callback to be called when the receipt times out.
     *
     * @param callback A function that takes this PacketReceipt as parameter
     */
    fun setTimeoutCallback(callback: (PacketReceipt) -> Unit) {
        callbacks.timeout = callback
    }

    /**
     * Set the link associated with this receipt.
     * Called internally by Link class.
     */
    internal fun setLink(link: Link) {
        this.link = link
    }

    /**
     * Validate a proof packet.
     *
     * @param proofPacket The proof packet to validate
     * @return true if the proof is valid
     */
    fun validateProofPacket(proofPacket: Packet): Boolean {
        // Check if this is a link proof
        val packetLink = link
        return if (packetLink != null) {
            validateLinkProof(proofPacket.data, packetLink, proofPacket)
        } else {
            validateProof(proofPacket.data, proofPacket)
        }
    }

    /**
     * Validate a raw proof for a link.
     *
     * @param proof The raw proof data
     * @param link The link to validate against
     * @param proofPacket Optional proof packet for reference
     * @return true if the proof is valid
     */
    fun validateLinkProof(proof: ByteArray, link: Link, proofPacket: Packet? = null): Boolean {
        // For now, only handle explicit proofs
        if (proof.size == EXPL_LENGTH) {
            // Extract proof components
            val proofHash = proof.copyOfRange(0, RnsConstants.FULL_HASH_BYTES)
            val signature = proof.copyOfRange(
                RnsConstants.FULL_HASH_BYTES,
                RnsConstants.FULL_HASH_BYTES + RnsConstants.SIGNATURE_SIZE
            )

            // Verify hash matches
            if (!proofHash.contentEquals(hash)) {
                return false
            }

            // Validate signature with link
            val proofValid = link.validate(signature, hash)

            if (proofValid) {
                status = DELIVERED
                proved = true
                concludedAt = System.currentTimeMillis()
                this.proofPacket = proofPacket

                // Fire delivery callback
                callbacks.delivery?.let { callback ->
                    try {
                        callback(this)
                    } catch (e: Exception) {
                        // Log error but don't propagate
                    }
                }
            }

            return proofValid
        }

        // Implicit proofs not yet supported
        return false
    }

    /**
     * Validate a raw proof.
     *
     * @param proof The raw proof data
     * @param proofPacket Optional proof packet for reference
     * @return true if the proof is valid
     */
    fun validateProof(proof: ByteArray, proofPacket: Packet? = null): Boolean {
        when (proof.size) {
            EXPL_LENGTH -> {
                // Explicit proof
                val proofHash = proof.copyOfRange(0, RnsConstants.FULL_HASH_BYTES)
                val signature = proof.copyOfRange(
                    RnsConstants.FULL_HASH_BYTES,
                    RnsConstants.FULL_HASH_BYTES + RnsConstants.SIGNATURE_SIZE
                )

                // Verify hash matches and destination has identity
                if (!proofHash.contentEquals(hash)) {
                    return false
                }

                val destIdentity = destination?.identity ?: return false

                // Validate signature
                val proofValid = destIdentity.validate(signature, hash)

                if (proofValid) {
                    status = DELIVERED
                    proved = true
                    concludedAt = System.currentTimeMillis()
                    this.proofPacket = proofPacket

                    // Fire delivery callback
                    callbacks.delivery?.let { callback ->
                        try {
                            callback(this)
                        } catch (e: Exception) {
                            // Log error but don't propagate
                        }
                    }
                }

                return proofValid
            }

            IMPL_LENGTH -> {
                // Implicit proof
                val destIdentity = destination?.identity ?: return false

                val signature = proof.copyOfRange(0, RnsConstants.SIGNATURE_SIZE)
                val proofValid = destIdentity.validate(signature, hash)

                if (proofValid) {
                    status = DELIVERED
                    proved = true
                    concludedAt = System.currentTimeMillis()
                    this.proofPacket = proofPacket

                    // Fire delivery callback
                    callbacks.delivery?.let { callback ->
                        try {
                            callback(this)
                        } catch (e: Exception) {
                            // Log error but don't propagate
                        }
                    }
                }

                return proofValid
            }

            else -> return false
        }
    }

    override fun toString(): String {
        val statusStr = when (status) {
            SENT -> "SENT"
            DELIVERED -> "DELIVERED"
            FAILED -> "FAILED"
            CULLED -> "CULLED"
            else -> "UNKNOWN"
        }
        return "PacketReceipt(hash=${hash.toHexString()}, status=$statusStr, rtt=${getRtt()})"
    }
}
