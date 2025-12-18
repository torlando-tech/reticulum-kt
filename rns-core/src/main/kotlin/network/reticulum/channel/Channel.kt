package network.reticulum.channel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val outlet: ChannelOutlet
) : AutoCloseable {
    companion object {
        private fun log(message: String) {
            println("[Channel] $message")
        }
    }

    // Message type registry
    private val messageFactories = ConcurrentHashMap<Int, MessageFactory>()

    // Message handlers
    private val messageHandlers = CopyOnWriteArrayList<MessageCallback>()

    // Sequence tracking
    private val txSequence = AtomicInteger(0)
    private val rxSequence = AtomicInteger(0)

    // Send/receive rings (deques for ordered delivery)
    private val txRing = LinkedBlockingDeque<Envelope>()
    private val rxRing = LinkedBlockingDeque<Envelope>()

    // Window management
    private var window = ChannelConstants.WINDOW
    private var windowMax = ChannelConstants.WINDOW_MAX_SLOW
    private var windowMin = ChannelConstants.WINDOW_MIN
    private var windowFlexibility = 4
    private var fastRateRounds = 0
    private var mediumRateRounds = 0

    // Tracking
    private val pendingEnvelopes = ConcurrentHashMap<Any, Envelope>()
    private val lock = ReentrantLock()

    // State
    @Volatile
    private var isShutdown = false

    /**
     * Register a message type for sending/receiving.
     *
     * @param msgType The unique message type identifier
     * @param factory Factory to create new message instances
     */
    fun registerMessageType(msgType: Int, factory: MessageFactory) {
        if (msgType >= ChannelConstants.SYSTEM_MESSAGE_MIN) {
            throw ChannelException(
                ChannelExceptionType.ME_INVALID_MSG_TYPE,
                "Message type ${String.format("0x%04X", msgType)} is in reserved range"
            )
        }

        if (messageFactories.containsKey(msgType)) {
            throw ChannelException(
                ChannelExceptionType.ME_ALREADY_REGISTERED,
                "Message type ${String.format("0x%04X", msgType)} already registered"
            )
        }

        messageFactories[msgType] = factory
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
    val isReadyToSend: Boolean
        get() {
            if (isShutdown || !outlet.isUsable) return false

            // Check if we have room in the window
            return pendingEnvelopes.size < window
        }

    /**
     * Send a message over the channel.
     *
     * @param message The message to send
     * @return The envelope tracking this message
     * @throws ChannelException if channel is not ready or window is full
     */
    fun send(message: MessageBase): Envelope {
        if (isShutdown) {
            throw ChannelException(
                ChannelExceptionType.ME_LINK_NOT_READY,
                "Channel is shut down"
            )
        }

        if (!outlet.isUsable) {
            throw ChannelException(
                ChannelExceptionType.ME_LINK_NOT_READY,
                "Outlet is not usable"
            )
        }

        if (pendingEnvelopes.size >= window) {
            throw ChannelException(
                ChannelExceptionType.ME_WINDOW_FULL,
                "Send window is full (${pendingEnvelopes.size}/$window)"
            )
        }

        // Create envelope with next sequence number
        val sequence = txSequence.getAndUpdate { (it + 1) % ChannelConstants.SEQ_MODULUS }
        val envelope = Envelope(outlet, message, sequence = sequence)

        // Pack and send
        val raw = envelope.pack()

        lock.withLock {
            txRing.addLast(envelope)

            // Send via outlet
            val packet = outlet.send(raw)
            if (packet != null) {
                envelope.packet = packet
                envelope.tries = 1
                envelope.tracked = true
                pendingEnvelopes[outlet.getPacketId(packet)] = envelope

                // Set up delivery callback
                outlet.setPacketDeliveredCallback(packet) { pkt ->
                    onPacketDelivered(pkt)
                }

                // Set up timeout callback
                val timeout = calculateTimeout()
                outlet.setPacketTimeoutCallback(packet, { pkt ->
                    onPacketTimeout(pkt)
                }, timeout)
            }
        }

        return envelope
    }

    /**
     * Receive raw data from the outlet.
     * This is called by the Link when data arrives on the channel.
     */
    fun receive(raw: ByteArray) {
        if (isShutdown) return

        try {
            val envelope = Envelope(outlet, raw = raw)
            val message = envelope.unpack(messageFactories)

            // Check sequence ordering
            val expectedSeq = rxSequence.get()
            if (envelope.sequence != expectedSeq) {
                // Out of order - add to ring for reordering
                emplaceEnvelope(envelope, rxRing)
                processRxRing()
            } else {
                // In order - process immediately
                rxSequence.getAndUpdate { (it + 1) % ChannelConstants.SEQ_MODULUS }
                runCallbacks(message)
                processRxRing()
            }

        } catch (e: ChannelException) {
            log("Error receiving message: ${e.message}")
        } catch (e: Exception) {
            log("Unexpected error receiving message: ${e.message}")
        }
    }

    /**
     * Process any queued messages that are now in order.
     */
    private fun processRxRing() {
        while (rxRing.isNotEmpty()) {
            val expected = rxSequence.get()
            val envelope = rxRing.peekFirst()

            if (envelope != null && envelope.sequence == expected) {
                rxRing.pollFirst()
                rxSequence.getAndUpdate { (it + 1) % ChannelConstants.SEQ_MODULUS }
                envelope.message?.let { runCallbacks(it) }
            } else {
                break
            }
        }
    }

    /**
     * Add envelope to a ring in sequence order.
     */
    private fun emplaceEnvelope(envelope: Envelope, ring: LinkedBlockingDeque<Envelope>): Boolean {
        lock.withLock {
            // Find correct position
            val iterator = ring.iterator()
            var index = 0
            while (iterator.hasNext()) {
                val existing = iterator.next()
                if (sequenceCompare(envelope.sequence, existing.sequence) < 0) {
                    break
                }
                if (envelope.sequence == existing.sequence) {
                    // Duplicate - ignore
                    return false
                }
                index++
            }

            // Insert at position
            val list = ring.toMutableList()
            list.add(index, envelope)
            ring.clear()
            ring.addAll(list)
            return true
        }
    }

    /**
     * Compare sequence numbers accounting for wrap-around.
     */
    private fun sequenceCompare(a: Int, b: Int): Int {
        val half = ChannelConstants.SEQ_MODULUS / 2
        val diff = (a - b + ChannelConstants.SEQ_MODULUS) % ChannelConstants.SEQ_MODULUS
        return if (diff < half) diff else diff - ChannelConstants.SEQ_MODULUS
    }

    /**
     * Run message callbacks.
     */
    private fun runCallbacks(message: MessageBase) {
        for (handler in messageHandlers) {
            try {
                if (handler(message)) {
                    break
                }
            } catch (e: Exception) {
                log("Error in message handler: ${e.message}")
            }
        }
    }

    /**
     * Handle packet delivery confirmation.
     */
    private fun onPacketDelivered(packet: Any) {
        val packetId = outlet.getPacketId(packet)
        val envelope = pendingEnvelopes.remove(packetId)
        if (envelope != null) {
            envelope.tracked = false
            updateWindow(delivered = true)
        }
    }

    /**
     * Handle packet timeout.
     */
    private fun onPacketTimeout(packet: Any) {
        val packetId = outlet.getPacketId(packet)
        val envelope = pendingEnvelopes[packetId]

        if (envelope != null && envelope.tracked) {
            envelope.tries++

            if (envelope.tries >= ChannelConstants.MAX_TRIES) {
                // Give up
                pendingEnvelopes.remove(packetId)
                envelope.tracked = false
                log("Message delivery failed after ${envelope.tries} tries")
                updateWindow(delivered = false)
            } else {
                // Retry
                val newPacket = outlet.resend(packet)
                if (newPacket != null) {
                    pendingEnvelopes.remove(packetId)
                    envelope.packet = newPacket
                    val newId = outlet.getPacketId(newPacket)
                    pendingEnvelopes[newId] = envelope

                    outlet.setPacketDeliveredCallback(newPacket) { pkt ->
                        onPacketDelivered(pkt)
                    }

                    val timeout = calculateTimeout()
                    outlet.setPacketTimeoutCallback(newPacket, { pkt ->
                        onPacketTimeout(pkt)
                    }, timeout)
                }
            }
        }
    }

    /**
     * Update window size based on delivery success.
     */
    private fun updateWindow(delivered: Boolean) {
        if (delivered) {
            fastRateRounds++
            if (fastRateRounds >= ChannelConstants.FAST_RATE_THRESHOLD) {
                windowMax = ChannelConstants.WINDOW_MAX_FAST
            } else if (fastRateRounds >= ChannelConstants.MEDIUM_RATE_THRESHOLD) {
                windowMax = ChannelConstants.WINDOW_MAX_MEDIUM
            }

            // Grow window slowly
            if (window < windowMax) {
                window++
            }
        } else {
            // Shrink window on failure
            fastRateRounds = 0
            mediumRateRounds = 0
            windowMax = ChannelConstants.WINDOW_MAX_SLOW
            window = maxOf(windowMin, window - 1)
        }
    }

    /**
     * Calculate timeout based on RTT.
     */
    private fun calculateTimeout(): Long {
        val rtt = outlet.rtt ?: 5000L
        return rtt * 3
    }

    /**
     * Maximum data unit size for messages.
     */
    val mdu: Int
        get() = outlet.mdu - 6  // Subtract envelope header

    /**
     * Shutdown the channel.
     */
    fun shutdown() {
        isShutdown = true
        clearRings()
    }

    /**
     * Clear all pending messages.
     */
    private fun clearRings() {
        lock.withLock {
            txRing.clear()
            rxRing.clear()
            pendingEnvelopes.clear()
        }
    }

    override fun close() {
        shutdown()
    }
}
