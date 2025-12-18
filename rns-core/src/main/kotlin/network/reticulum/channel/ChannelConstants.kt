package network.reticulum.channel

/**
 * Constants for Channel operations, matching Python RNS Channel.py.
 */
object ChannelConstants {
    // Window sizes
    const val WINDOW = 2
    const val WINDOW_MIN = 2
    const val WINDOW_MAX_SLOW = 5
    const val WINDOW_MAX_MEDIUM = 16
    const val WINDOW_MAX_FAST = 48
    const val WINDOW_MAX = WINDOW_MAX_FAST

    // Rate thresholds (bytes per second)
    const val RATE_FAST = (50 * 1000) / 8  // 50 Kbps
    const val RATE_MEDIUM = (15 * 1000) / 8  // 15 Kbps

    // Timing
    const val RTT_FAST = 0.25
    const val RTT_MEDIUM = 1.5
    const val RTT_SLOW = 4.0
    const val FAST_RATE_THRESHOLD = 10
    const val MEDIUM_RATE_THRESHOLD = 5

    // Sequence numbers
    const val SEQ_MAX = 0xFFFF
    const val SEQ_MODULUS = 0x10000

    // Reserved message types
    const val SYSTEM_MESSAGE_MIN = 0xF000

    // Retry settings
    const val MAX_TRIES = 5
}

/**
 * Message states for tracking delivery.
 */
object MessageState {
    const val NEW = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val FAILED = 3
}

/**
 * System message types (reserved range >= 0xF000).
 */
object SystemMessageTypes {
    const val SMT_STREAM_DATA = 0xFF00
}

/**
 * Channel exception types.
 */
enum class ChannelExceptionType {
    ME_NO_MSG_TYPE,
    ME_NOT_REGISTERED,
    ME_INVALID_MSG_TYPE,
    ME_ALREADY_REGISTERED,
    ME_LINK_NOT_READY,
    ME_WINDOW_FULL
}

/**
 * Exception thrown by Channel operations.
 */
class ChannelException(
    val type: ChannelExceptionType,
    message: String
) : Exception(message)
