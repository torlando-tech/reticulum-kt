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

    // Send serialization lock. Mirrors python RNS Channel._send_lock
    // (Channel.py:288): send() takes this OUTER lock so the reserve/pack/
    // size-check/advance sequence is atomic with the outlet transmit, even
    // though outlet.send runs outside the inner state lock.
    private val sendLock = ReentrantLock()

    // State
    @Volatile
    private var isShutdown = false

    /**
     * Conformance test seam: when set, the next [send] treats the outlet as if
     * it failed to transmit (returns no packet), driving the
     * "outlet did not transmit" branch (sequence restore + ME_LINK_NOT_READY,
     * mirrors python Channel.py:621-626). One-shot — cleared on use. Set by the
     * wire bridge's wire_channel_send(fail_outlet=true). Not used in production.
     */
    @Volatile
    var failNextSendForTest: Boolean = false

    /**
     * Conformance test seam: invoked inside [send] after a StreamDataMessage/
     * MessageBase is packed and handed to the outlet, with the message and its
     * Envelope. The wire bridge installs a tap to build the per-message Buffer
     * manifest (the kotlin analog of the reference wrapping `channel.send`).
     * Null in production.
     */
    @Volatile
    var outboundMessageTapForTest: ((MessageBase, Envelope) -> Unit)? = null

    /**
     * Conformance test seam: invoked from [receive]'s catch when unpacking an
     * inbound envelope raises (e.g. the bz2 decompression-bomb guard in
     * StreamDataMessage.unpack). Lets the wire listener surface
     * `buffer_state.aborted/error`, since [receive] otherwise swallows the
     * exception. Null in production.
     */
    @Volatile
    var receiveErrorTapForTest: ((Throwable) -> Unit)? = null

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
        // Mirror python RNS Channel.send (Channel.py:599-637): the whole send is
        // serialized by _send_lock; the reserve/pack/size-check/advance happens
        // under the inner state lock, then outlet.send runs OUTSIDE it, then the
        // emplace/callback wiring re-takes the inner lock.
        sendLock.withLock {
            val reservedSequence: Int
            val envelope: Envelope
            lock.withLock {
                if (!isReadyToSend()) {
                    throw ChannelException(
                        ChannelExceptionType.ME_LINK_NOT_READY,
                        "Link is not ready"
                    )
                }

                // Reserve (but do NOT yet advance) the next sequence number.
                reservedSequence = nextSequence.get()
                envelope = Envelope(outlet, message, sequence = reservedSequence)

                // Pack the message FIRST, then size-check BEFORE advancing the
                // sequence or emplacing — python Channel.py:613-617 raises
                // ME_TOO_BIG here, leaving _next_sequence untouched and the tx
                // ring clean (no stale envelope).
                envelope.pack()
                val raw = envelope.raw!!
                if (raw.size > outlet.mdu) {
                    throw ChannelException(
                        ChannelExceptionType.ME_TOO_BIG,
                        "Packed message too big for packet: ${raw.size} > ${outlet.mdu}"
                    )
                }

                // Only now advance the transmit sequence (Channel.py:617).
                nextSequence.set((reservedSequence + 1) % SEQ_MODULUS)
            }

            // Transmit via the outlet OUTSIDE the inner lock (Channel.py:619).
            // failNextSendForTest fault-injects the "outlet did not transmit"
            // path used by wire_channel_send(fail_outlet=true).
            val packet: Any? = if (failNextSendForTest) {
                failNextSendForTest = false
                null
            } else {
                outlet.send(envelope.raw!!)
            }
            envelope.packet = packet

            // Outlet did not transmit (null packet / no receipt): restore the
            // reserved sequence and raise ME_LINK_NOT_READY so the next send
            // reuses the freed sequence with no gap (python Channel.py:621-626).
            if (packet == null || !packetHasReceipt(packet)) {
                lock.withLock { nextSequence.set(reservedSequence) }
                throw ChannelException(
                    ChannelExceptionType.ME_LINK_NOT_READY,
                    "Outlet did not transmit packet"
                )
            }

            val alreadyDelivered: Boolean
            lock.withLock {
                // Add to TX ring and wire up the delivery/timeout callbacks
                // (Channel.py:628-633).
                emplaceEnvelope(envelope, txRing)
                envelope.tries++

                outlet.setPacketDeliveredCallback(packet) { pkt ->
                    packetDelivered(pkt)
                }
                outlet.setPacketTimeoutCallback(packet, { pkt ->
                    packetTimeout(pkt)
                }, getPacketTimeoutTime(envelope.tries))

                updatePacketTimeouts()
                alreadyDelivered = outlet.getPacketState(packet) == MessageState.DELIVERED
            }

            // Prevent a tx_ring leak when the proof round-tripped before the
            // delivery callback was wired (loopback can deliver in microseconds).
            // Mirrors python Channel.py:634-637.
            if (alreadyDelivered) {
                packetDelivered(packet)
            }

            // Conformance manifest tap (StreamDataMessage Buffer streaming).
            outboundMessageTapForTest?.let { tap -> runCatching { tap(message, envelope) } }

            return envelope
        }
    }

    /**
     * Whether a transmitted packet carries a delivery receipt — mirrors the
     * python guard `getattr(envelope.packet, "receipt", None) is None`
     * (Channel.py:621-623). A packet built but not actually sent (e.g. on a
     * non-ACTIVE link) has no receipt, so the send is treated as a non-transmit.
     */
    private fun packetHasReceipt(packet: Any): Boolean =
        outlet.getPacketState(packet) != MessageState.FAILED

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
            // Python Channel._receive swallows the exception (Channel.py:467-468)
            // and never advances the rx sequence. Surface it to the test tap so a
            // listener can observe e.g. the bz2 decompression-bomb abort.
            receiveErrorTapForTest?.let { tap -> runCatching { tap(e) } }
            println("[Channel] Error receiving message: ${e.message}")
        } catch (e: Exception) {
            receiveErrorTapForTest?.let { tap -> runCatching { tap(e) } }
            println("[Channel] Unexpected error receiving message: ${e.message}")
        }
    }

    /**
     * Process contiguous messages from the RX ring.
     */
    private fun processContiguousMessages() {
        val contiguous = mutableListOf<Envelope>()

        lock.withLock {
            // Mirror python Channel._receive's contiguous-delivery loop
            // (Channel.py:444-466): it iterates the WHOLE rx ring, appending each
            // envelope whose sequence == next_rx_sequence — it does NOT break on
            // the first mismatch. Across the 0xFFFF->0 wrap the ring sorts as
            // [0, 0xFFFF] (the half-space rule cannot place 0xFFFF before 0), so a
            // break-on-mismatch would never reach the 0xFFFF that unblocks the
            // run. Skipping (not breaking) matches python and still stops
            // appending naturally once the contiguous run ends.
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
            // Mirror python Channel._packet_timeout (Channel.py:578-583): after
            // the channel shuts down, tell the outlet it timed out, which tears
            // the underlying Link DOWN (LinkChannelOutlet.timed_out ->
            // link.teardown(), Channel.py:707-708). The previous no-op left the
            // link ACTIVE, so drop_acks teardown never became observable.
            outlet.notifyTimedOut()
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
        // Mirror python RNS Channel.mdu (Channel.py:643-654): outlet.mdu minus the
        // 6-byte envelope header, capped at 0xFFFF (the envelope length field is
        // 16-bit). Previously uncapped, which over-reported mdu on large-MTU links.
        get() = minOf(outlet.mdu - 6, 0xFFFF)

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

    // -----------------------------------------------------------------------
    // Conformance test seams (read-only snapshots / pure helpers + callback
    // re-fire hooks). These expose private state for the wire bridge's
    // channel/buffer commands; none change production behavior.
    // -----------------------------------------------------------------------

    /** Immutable snapshot of the channel's window / sequence / ring state. */
    class ChannelStateForTest(
        val window: Int, val windowMin: Int, val windowMax: Int, val windowFlexibility: Int,
        val nextSequence: Int, val nextRxSequence: Int,
        val rxRing: Int, val txRing: Int,
        val txTries: Int, val txEnvelopes: List<Pair<Int, Int>>,
        val mdu: Int, val outletMdu: Int,
        val messageHandlers: Int, val mediumRateRounds: Int, val fastRateRounds: Int,
    )

    /** Snapshot the live window/sequence/ring state (the wire_channel_window /
     *  wire_listener_channel_rx observable). */
    fun stateForTest(): ChannelStateForTest = lock.withLock {
        val tx = txRing.toList()
        ChannelStateForTest(
            window, windowMin, windowMax, windowFlexibility,
            nextSequence.get(), nextRxSequence.get(),
            rxRing.size, tx.size,
            tx.maxOfOrNull { it.tries } ?: 0,
            tx.map { it.sequence to it.tries },
            mdu, outlet.mdu, messageHandlers.size, mediumRateRounds, fastRateRounds,
        )
    }

    /** Pack a real Envelope wrapping [message] at [sequence] via this channel's
     *  outlet (the wire_channel_inject / handler_chain feed). */
    fun packEnvelopeForTest(message: MessageBase, sequence: Int): ByteArray =
        Envelope(outlet, message, sequence = sequence).pack()

    /** The un-truncated (Double, milliseconds) retransmit-timeout formula value.
     *  Production [getPacketTimeoutTime] truncates to a Long ms; this returns the
     *  exact `pow(1.5,tries-1) * max(rtt*2.5, 25) * (txRing+1.5)` so the formula
     *  test can compare against python's float-seconds re-derivation. */
    fun getPacketTimeoutTimeDoubleForTest(tries: Int): Double {
        val r = outlet.rtt ?: 25L
        return 1.5.pow(tries - 1) * maxOf(r * 2.5, 25.0) * (txRing.size + 1.5)
    }

    /** Pad the tx ring with [depth] placeholder Envelopes so the timeout formula
     *  scales by (depth+1.5), mirroring the reference padding _tx_ring. */
    fun padTxRingForTest(depth: Int) {
        lock.withLock { repeat(depth) { txRing.add(Envelope(outlet, sequence = it)) } }
    }

    /** Whether [env]'s packet is in the DELIVERED state per the real outlet. */
    fun isDeliveredForTest(env: Envelope): Boolean =
        env.packet?.let { outlet.getPacketState(it) == MessageState.DELIVERED } ?: false

    /** Re-fire the private delivery callback for [packet] (spurious-proof test). */
    fun firePacketDeliveredForTest(packet: Any) = packetDelivered(packet)

    /** Re-fire the private timeout callback for [packet] (stale-timeout test). */
    fun firePacketTimeoutForTest(packet: Any) = packetTimeout(packet)
}
