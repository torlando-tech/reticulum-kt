package network.reticulum.channel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * Callback type for message handlers.
 * Return true if the message was handled and should not be passed to other handlers.
 */
typealias MessageCallback = (MessageBase) -> Boolean

/**
 * Provides reliable delivery of structured messages over a Link.
 *
 * Channel differs from Resource in important ways:
 * - **Continuous**: Messages can be sent/received as long as the Link is open
 * - **Bi-directional**: Messages can flow in either direction
 * - **Size-constrained**: Messages must fit in a single packet
 *
 * Channel provides automatic retries and sequencing for reliable delivery.
 *
 * Usage:
 * ```kotlin
 * // Get channel from link
 * val channel = link.getChannel()
 *
 * // Register message type
 * channel.registerMessageType(MyMessage::class) { MyMessage() }
 *
 * // Add handler
 * channel.addMessageHandler { message ->
 *     when (message) {
 *         is MyMessage -> {
 *             println("Received: ${message.data}")
 *             true
 *         }
 *         else -> false
 *     }
 * }
 *
 * // Send message
 * channel.send(MyMessage().apply { data = "Hello" })
 * ```
 */
class Channel(
    internal val outlet: ChannelOutlet
) : AutoCloseable {
    companion object {
        // Window configuration
        const val WINDOW = 2
        const val WINDOW_MIN = 2
        const val WINDOW_MIN_LIMIT_SLOW = 2
        const val WINDOW_MIN_LIMIT_MEDIUM = 5
        const val WINDOW_MIN_LIMIT_FAST = 16

        const val WINDOW_MAX_SLOW = 5
        const val WINDOW_MAX_MEDIUM = 12
        const val WINDOW_MAX_FAST = 48
        const val WINDOW_MAX = WINDOW_MAX_FAST
        const val WINDOW_FLEXIBILITY = 4

        // Rate thresholds
        const val FAST_RATE_THRESHOLD = 10
        const val RTT_FAST = 180.0  // ms
        const val RTT_MEDIUM = 750.0  // ms
        const val RTT_SLOW = 1450.0  // ms

        // Sequence management
        const val SEQ_MAX = 0xFFFF
        const val SEQ_MODULUS = 0x10000

        // Retry configuration
        const val MAX_TRIES = 5
    }

    // Message type registry
    private val messageFactories = ConcurrentHashMap<Int, MessageFactory>()

    // Message handlers
    private val messageHandlers = CopyOnWriteArrayList<MessageCallback>()

    // Sequence tracking
    private val nextSequence = AtomicInteger(0)
    private val nextRxSequence = AtomicInteger(0)

    // Send/receive rings (deques for ordered delivery)
    private val txRing = LinkedBlockingDeque<Envelope>()
    private val rxRing = LinkedBlockingDeque<Envelope>()

    // Window management
    @Volatile
    private var window: Int
    @Volatile
    private var windowMax: Int
    @Volatile
    private var windowMin: Int
    @Volatile
    private var windowFlexibility: Int
    @Volatile
    private var fastRateRounds = 0
    @Volatile
    private var mediumRateRounds = 0

    // Tracking lock
    private val lock = ReentrantLock()

    // State
    @Volatile
    private var isShutdown = false

    init {
        // Initialize window based on RTT
        val rtt = outlet.rtt ?: 0L
        if (rtt > RTT_SLOW) {
            window = 1
            windowMax = 1
            windowMin = 1
            windowFlexibility = 1
        } else {
            window = WINDOW
            windowMax = WINDOW_MAX_SLOW
            windowMin = WINDOW_MIN
            windowFlexibility = WINDOW_FLEXIBILITY
        }
    }

    /**
     * Register a message type for sending/receiving.
     *
     * @param msgType The unique message type identifier
     * @param factory Factory to create new message instances
     * @param isSystemType Whether this is a system-reserved message type
     */
    fun registerMessageType(msgType: Int, factory: MessageFactory, isSystemType: Boolean = false) {
        lock.withLock {
            if (msgType >= 0xF000 && !isSystemType) {
                throw ChannelException(
                    ChannelExceptionType.ME_INVALID_MSG_TYPE,
                    "Message type ${String.format("0x%04X", msgType)} is in system-reserved range (>= 0xF000)"
                )
            }

            if (messageFactories.containsKey(msgType)) {
                throw ChannelException(
                    ChannelExceptionType.ME_INVALID_MSG_TYPE,
                    "Message type ${String.format("0x%04X", msgType)} already registered"
                )
            }

            // Validate factory by creating an instance
            try {
                factory.create()
            } catch (e: Exception) {
                throw ChannelException(
                    ChannelExceptionType.ME_INVALID_MSG_TYPE,
                    "Factory raised exception when creating instance: ${e.message}"
                )
            }

            messageFactories[msgType] = factory
        }
    }

    /**
     * Register a message type using reflection.
     */
    inline fun <reified T : MessageBase> registerMessageType(noinline factory: () -> T) {
        val instance = factory()
        registerMessageType(instance.msgType, factory)
    }

    /**
     * Add a message handler callback.
     * Handlers are called in order until one returns true.
     */
    fun addMessageHandler(callback: MessageCallback) {
        messageHandlers.add(callback)
    }

    /**
     * Remove a message handler callback.
     */
    fun removeMessageHandler(callback: MessageCallback) {
        messageHandlers.remove(callback)
    }

    /**
     * Check if the channel is ready to send messages.
     */
    fun isReadyToSend(): Boolean {
        if (!outlet.isUsable) {
            return false
        }

        lock.withLock {
            var outstanding = 0
            for (envelope in txRing) {
                if (envelope.outlet == outlet) {
                    val packet = envelope.packet
                    if (packet == null || outlet.getPacketState(packet) != MessageState.DELIVERED) {
                        outstanding++
                    }
                }
            }

            return outstanding < window
        }
    }

    /**
     * Send a message over the channel.
     *
     * @param message The message to send
     * @return The envelope tracking this message
     * @throws ChannelException if channel is not ready or message is too big
     */
    fun send(message: MessageBase): Envelope {
        lock.withLock {
            if (!isReadyToSend()) {
                throw ChannelException(
                    ChannelExceptionType.ME_LINK_NOT_READY,
                    "Link is not ready"
                )
            }

            // Create envelope with next sequence number
            val sequence = nextSequence.getAndUpdate { (it + 1) % SEQ_MODULUS }
            val envelope = Envelope(outlet, message, sequence = sequence)

            // Add to TX ring
            emplaceEnvelope(envelope, txRing)

            // Pack the message
            envelope.pack()

            // Check size
            val raw = envelope.raw!!
            if (raw.size > outlet.mdu) {
                throw ChannelException(
                    ChannelExceptionType.ME_INVALID_MSG_TYPE,
                    "Packed message too big for packet: ${raw.size} > ${outlet.mdu}"
                )
            }

            // Send via outlet
            val packet = outlet.send(raw)
            envelope.packet = packet
            envelope.tries++

            // Set up callbacks if packet was sent
            if (packet != null) {
                outlet.setPacketDeliveredCallback(packet) { pkt ->
                    packetDelivered(pkt)
                }

                val timeout = getPacketTimeoutTime(envelope.tries)
                outlet.setPacketTimeoutCallback(packet, { pkt ->
                    packetTimeout(pkt)
                }, timeout)
            }

            updatePacketTimeouts()

            return envelope
        }
    }

    /**
     * Receive raw data from the outlet.
     * This is called by the Link when data arrives on the channel.
     */
    fun receive(raw: ByteArray) {
        try {
            val envelope = Envelope(outlet, raw = raw)

            lock.withLock {
                val message = envelope.unpack(messageFactories)

                // Validate sequence number
                val currentRx = nextRxSequence.get()
                if (envelope.sequence < currentRx) {
                    // Check if it's within the window overflow range
                    val windowOverflow = (currentRx + WINDOW_MAX) % SEQ_MODULUS
                    if (windowOverflow < currentRx) {
                        // Wrapped around
                        if (envelope.sequence > windowOverflow) {
                            // Invalid sequence - drop it
                            return
                        }
                    } else {
                        // Invalid sequence - drop it
                        return
                    }
                }

                // Try to add to RX ring
                val isNew = emplaceEnvelope(envelope, rxRing)

                if (!isNew) {
                    // Duplicate - drop it
                    return
                }
            }

            // Process any contiguous messages
            processContiguousMessages()

        } catch (e: ChannelException) {
            println("[Channel] Error receiving message: ${e.message}")
        } catch (e: Exception) {
            println("[Channel] Unexpected error receiving message: ${e.message}")
        }
    }

    /**
     * Process contiguous messages from the RX ring.
     */
    private fun processContiguousMessages() {
        val contiguous = mutableListOf<Envelope>()

        lock.withLock {
            for (envelope in rxRing) {
                if (envelope.sequence == nextRxSequence.get()) {
                    contiguous.add(envelope)
                    nextRxSequence.set((nextRxSequence.get() + 1) % SEQ_MODULUS)

                    // Handle sequence wrap-around
                    if (nextRxSequence.get() == 0) {
                        // Continue processing after wrap
                        for (e in rxRing) {
                            if (e.sequence == nextRxSequence.get()) {
                                contiguous.add(e)
                                nextRxSequence.set((nextRxSequence.get() + 1) % SEQ_MODULUS)
                            }
                        }
                    }
                } else {
                    break
                }
            }

            // Remove processed envelopes from ring
            for (envelope in contiguous) {
                rxRing.remove(envelope)
            }
        }

        // Run callbacks outside of lock
        for (envelope in contiguous) {
            val message = if (!envelope.unpacked) {
                envelope.unpack(messageFactories)
            } else {
                envelope.message
            }

            message?.let { runCallbacks(it) }
        }
    }

    /**
     * Add envelope to a ring in sequence order.
     * Returns true if the envelope was added, false if it was a duplicate.
     */
    private fun emplaceEnvelope(envelope: Envelope, ring: LinkedBlockingDeque<Envelope>): Boolean {
        lock.withLock {
            var insertIndex = 0

            for (existing in ring) {
                // Check for duplicate
                if (envelope.sequence == existing.sequence) {
                    // Duplicate envelope
                    return false
                }

                // Find insertion point - sequences are ordered, accounting for wrap-around
                if (envelope.sequence < existing.sequence &&
                    !((nextRxSequence.get() - envelope.sequence) > (SEQ_MAX / 2))
                ) {
                    // Insert here
                    val list = ring.toMutableList()
                    list.add(insertIndex, envelope)
                    ring.clear()
                    ring.addAll(list)
                    envelope.tracked = true
                    return true
                }

                insertIndex++
            }

            // Add at the end
            envelope.tracked = true
            ring.add(envelope)
            return true
        }
    }

    /**
     * Run message callbacks.
     */
    private fun runCallbacks(message: MessageBase) {
        // Make a copy to avoid concurrent modification
        val callbacks = messageHandlers.toList()

        for (callback in callbacks) {
            try {
                if (callback(message)) {
                    break
                }
            } catch (e: Exception) {
                println("[Channel] Error in message callback: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Handle packet delivery confirmation.
     */
    private fun packetDelivered(packet: Any) {
        packetTxOp(packet) { envelope ->
            // Packet was delivered successfully
            true
        }
    }

    /**
     * Handle packet timeout and retry logic.
     */
    private fun packetTimeout(packet: Any) {
        // Only retry if not already delivered
        if (outlet.getPacketState(packet) != MessageState.DELIVERED) {
            packetTxOp(packet) { envelope ->
                retryEnvelope(envelope)
            }
        }
    }

    /**
     * Execute an operation on a packet in the TX ring.
     */
    private fun packetTxOp(packet: Any, op: (Envelope) -> Boolean) {
        lock.withLock {
            val packetId = outlet.getPacketId(packet)
            val envelope = txRing.find { env ->
                env.packet?.let { outlet.getPacketId(it) == packetId } ?: false
            }

            if (envelope != null && op(envelope)) {
                envelope.tracked = false
                if (txRing.remove(envelope)) {
                    // Increase window on success
                    if (window < windowMax) {
                        window++
                    }

                    // Update window limits based on RTT
                    val rtt = outlet.rtt ?: 0L
                    if (rtt > 0) {
                        if (rtt > RTT_FAST) {
                            fastRateRounds = 0

                            if (rtt > RTT_MEDIUM) {
                                mediumRateRounds = 0
                            } else {
                                mediumRateRounds++
                                if (windowMax < WINDOW_MAX_MEDIUM && mediumRateRounds == FAST_RATE_THRESHOLD) {
                                    windowMax = WINDOW_MAX_MEDIUM
                                    windowMin = WINDOW_MIN_LIMIT_MEDIUM
                                }
                            }
                        } else {
                            fastRateRounds++
                            if (windowMax < WINDOW_MAX_FAST && fastRateRounds == FAST_RATE_THRESHOLD) {
                                windowMax = WINDOW_MAX_FAST
                                windowMin = WINDOW_MIN_LIMIT_FAST
                            }
                        }
                    }
                } else {
                    // Envelope not found in TX ring (already removed)
                }
            } else if (envelope == null) {
                // Spurious message (packet not in our TX ring)
            }
        }
    }

    /**
     * Retry an envelope that timed out.
     * Returns true if max retries reached (should be removed from ring).
     */
    private fun retryEnvelope(envelope: Envelope): Boolean {
        if (envelope.tries >= MAX_TRIES) {
            println("[Channel] Retry count exceeded, tearing down Link")
            shutdown()
            // Signal outlet that we timed out
            if (outlet.timedOut) {
                // Outlet is already timed out
            }
            return true
        }

        envelope.tries++
        val packet = envelope.packet ?: return true

        outlet.resend(packet)
        outlet.setPacketDeliveredCallback(packet) { pkt -> packetDelivered(pkt) }
        outlet.setPacketTimeoutCallback(
            packet,
            { pkt -> packetTimeout(pkt) },
            getPacketTimeoutTime(envelope.tries)
        )

        updatePacketTimeouts()

        // Decrease window on timeout
        if (window > windowMin) {
            window--

            if (windowMax > (windowMin + windowFlexibility)) {
                windowMax--
            }
        }

        return false
    }

    /**
     * Update timeouts for all pending packets.
     */
    private fun updatePacketTimeouts() {
        for (envelope in txRing) {
            val updatedTimeout = getPacketTimeoutTime(envelope.tries)
            val packet = envelope.packet

            if (packet != null) {
                // Update timeout if it needs to be increased
                outlet.setPacketTimeoutCallback(
                    packet,
                    { pkt -> packetTimeout(pkt) },
                    updatedTimeout
                )
            }
        }
    }

    /**
     * Calculate packet timeout based on RTT and number of tries.
     */
    private fun getPacketTimeoutTime(tries: Int): Long {
        val rtt = outlet.rtt ?: 25L
        val ringSize = txRing.size
        val timeout = 1.5.pow(tries - 1) * maxOf(rtt * 2.5, 25.0) * (ringSize + 1.5)
        return timeout.toLong()
    }

    /**
     * Maximum data unit size for messages.
     */
    val mdu: Int
        get() = outlet.mdu - 6  // Subtract envelope header

    /**
     * Shutdown the channel and clear all callbacks.
     */
    fun shutdown() {
        lock.withLock {
            messageHandlers.clear()
            clearRings()
        }
    }

    /**
     * Clear all pending messages and reset callbacks.
     */
    private fun clearRings() {
        lock.withLock {
            // Clear callbacks for all pending packets
            for (envelope in txRing) {
                val packet = envelope.packet
                if (packet != null) {
                    outlet.setPacketTimeoutCallback(packet, null)
                    outlet.setPacketDeliveredCallback(packet, null)
                }
            }

            txRing.clear()
            rxRing.clear()
        }
    }

    override fun close() {
        shutdown()
    }
}
