package network.reticulum.channel

/**
 * Interface for the underlying transport used by a Channel.
 * Typically implemented by Link to provide encrypted delivery.
 */
interface ChannelOutlet {
    /**
     * Send raw bytes over the outlet.
     * @param raw The data to send
     * @return An opaque packet identifier for tracking
     */
    fun send(raw: ByteArray): Any?

    /**
     * Resend a previously sent packet.
     * @param packet The packet to resend
     * @return The new packet identifier
     */
    fun resend(packet: Any): Any?

    /**
     * Maximum data unit size for this outlet.
     */
    val mdu: Int

    /**
     * Current round-trip time estimate in milliseconds.
     */
    val rtt: Long?

    /**
     * Whether the outlet is currently usable.
     */
    val isUsable: Boolean

    /**
     * Whether the outlet has timed out.
     */
    val timedOut: Boolean

    /**
     * Get the current state of a packet.
     * @param packet The packet to check
     * @return The message state
     */
    fun getPacketState(packet: Any): Int

    /**
     * Set a callback for when a packet times out.
     */
    fun setPacketTimeoutCallback(packet: Any, callback: ((Any) -> Unit)?, timeout: Long? = null)

    /**
     * Set a callback for when a packet is delivered.
     */
    fun setPacketDeliveredCallback(packet: Any, callback: ((Any) -> Unit)?)

    /**
     * Get an identifier for a packet.
     */
    fun getPacketId(packet: Any): Any
}
