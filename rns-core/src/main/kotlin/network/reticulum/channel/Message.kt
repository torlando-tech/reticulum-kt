package network.reticulum.channel

/**
 * Base interface for messages sent over a Channel.
 *
 * Subclasses must:
 * - Define a unique MSGTYPE value (< 0xF000)
 * - Implement pack() to serialize the message
 * - Implement unpack() to deserialize the message
 *
 * Example:
 * ```kotlin
 * class MyMessage : MessageBase() {
 *     override val msgType = 0x0001
 *     var data: ByteArray = ByteArray(0)
 *
 *     override fun pack(): ByteArray = data
 *     override fun unpack(raw: ByteArray) { data = raw }
 * }
 * ```
 */
abstract class MessageBase {
    /**
     * Unique identifier for this message type.
     * Must be < 0xF000 (values >= 0xF000 are reserved for system messages).
     */
    abstract val msgType: Int

    /**
     * Serialize this message to bytes.
     */
    abstract fun pack(): ByteArray

    /**
     * Deserialize this message from bytes.
     */
    abstract fun unpack(raw: ByteArray)
}

/**
 * Factory interface for creating message instances.
 */
fun interface MessageFactory {
    fun create(): MessageBase
}

/**
 * Internal wrapper for transporting messages over a channel.
 * Tracks message state, sequence number, and delivery status.
 */
class Envelope(
    /** The outlet (link) this envelope is associated with. */
    val outlet: ChannelOutlet,
    /** The message being transported (for outgoing). */
    var message: MessageBase? = null,
    /** Raw packed data (for incoming). */
    var raw: ByteArray? = null,
    /** Sequence number for ordering. */
    var sequence: Int = 0
) {
    /** Timestamp when envelope was created. */
    val timestamp: Long = System.currentTimeMillis()

    /** Unique identifier for this envelope. */
    val id: Long = System.nanoTime()

    /** The packet used to send this envelope. */
    var packet: Any? = null

    /** Number of transmission attempts. */
    var tries: Int = 0

    /** Whether the message has been unpacked. */
    var unpacked: Boolean = false

    /** Whether the message has been packed. */
    var packed: Boolean = false

    /** Whether this envelope is being tracked for delivery. */
    var tracked: Boolean = false

    /**
     * Pack the message into wire format.
     * Format: [msgType:2][sequence:2][length:2][data:N]
     */
    fun pack(): ByteArray {
        val msg = message ?: throw ChannelException(
            ChannelExceptionType.ME_NO_MSG_TYPE,
            "No message to pack"
        )

        if (msg.msgType < 0 || msg.msgType > 0xFFFF) {
            throw ChannelException(
                ChannelExceptionType.ME_INVALID_MSG_TYPE,
                "Invalid message type: ${msg.msgType}"
            )
        }

        val data = msg.pack()
        val header = ByteArray(6)

        // Message type (big-endian)
        header[0] = ((msg.msgType shr 8) and 0xFF).toByte()
        header[1] = (msg.msgType and 0xFF).toByte()

        // Sequence number (big-endian)
        header[2] = ((sequence shr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()

        // Data length (big-endian)
        header[4] = ((data.size shr 8) and 0xFF).toByte()
        header[5] = (data.size and 0xFF).toByte()

        raw = header + data
        packed = true
        return raw!!
    }

    /**
     * Unpack the message from wire format.
     * @param factories Map of message type to factory
     * @return The unpacked message
     */
    fun unpack(factories: Map<Int, MessageFactory>): MessageBase {
        val data = raw ?: throw ChannelException(
            ChannelExceptionType.ME_NO_MSG_TYPE,
            "No raw data to unpack"
        )

        if (data.size < 6) {
            throw ChannelException(
                ChannelExceptionType.ME_NO_MSG_TYPE,
                "Raw data too short: ${data.size}"
            )
        }

        // Parse header
        val msgType = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val length = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

        // Get factory for this message type
        val factory = factories[msgType] ?: throw ChannelException(
            ChannelExceptionType.ME_NOT_REGISTERED,
            "No factory registered for message type ${String.format("0x%04X", msgType)}"
        )

        // Create and unpack message. Mirror python RNS Channel.Envelope.unpack
        // (Channel.py:180-181): the on-wire length field is parsed for the header
        // layout but NEVER consulted on receive — the payload is always raw[6:].
        // (Previously this truncated to raw[6:6+length], diverging from python and
        // dropping bytes when a crafted frame advertised a too-short length.)
        @Suppress("UNUSED_VARIABLE") val declaredLength = length
        val msg = factory.create()
        msg.unpack(data.copyOfRange(6, data.size))

        message = msg
        unpacked = true
        return msg
    }

    companion object {
        /**
         * Conformance test seam: pack an Envelope wrapping an ad-hoc message
         * whose msgType/pack() are supplied, without needing a real
         * ChannelOutlet. Envelope.pack reads only `message`, never the outlet,
         * so a no-op outlet stub is safe. Mirrors the reference's
         * `Envelope(outlet=None, message=_PackMessage(data), sequence=seq).pack()`
         * (wire_tcp.py cmd_wire_channel_envelope_pack). No port logic.
         */
        fun packForTest(msgType: Int, sequence: Int, data: ByteArray): ByteArray {
            val msg = object : MessageBase() {
                override val msgType = msgType
                override fun pack() = data
                override fun unpack(raw: ByteArray) {}
            }
            return Envelope(NoopOutletForTest, msg, sequence = sequence).pack()
        }

        /**
         * Conformance test seam: pack an Envelope with no message, exercising the
         * ME_NO_MSG_TYPE guard (Channel.py:193-194 raises when the wrapped message
         * lacks a MSGTYPE). Kotlin has no nullable msgType, so a null `message`
         * triggers the same guard ("No message to pack"). Used by
         * wire_channel_register(kind="envelope_pack_no_msgtype").
         */
        fun packNullMessageForTest(): ByteArray =
            Envelope(NoopOutletForTest, message = null, sequence = 0).pack()
    }
}

/**
 * A no-op [ChannelOutlet] used only by Envelope pack-only test seams
 * ([Envelope.packForTest] / [Envelope.packNullMessageForTest]). Envelope.pack
 * never reads the outlet, so every member is an inert stub. Internal so it
 * cannot leak into production code paths.
 */
internal object NoopOutletForTest : ChannelOutlet {
    override fun send(raw: ByteArray): Any? = null
    override fun resend(packet: Any): Any? = null
    override val mdu: Int get() = 0xFFFF
    override val rtt: Long? get() = null
    override val isUsable: Boolean get() = false
    override val timedOut: Boolean get() = false
    override fun getPacketState(packet: Any): Int = MessageState.FAILED
    override fun setPacketTimeoutCallback(packet: Any, callback: ((Any) -> Unit)?, timeout: Long?) {}
    override fun setPacketDeliveredCallback(packet: Any, callback: ((Any) -> Unit)?) {}
    override fun getPacketId(packet: Any): Any = packet
}
