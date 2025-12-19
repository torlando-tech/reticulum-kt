package network.reticulum.lxmf

import network.reticulum.common.RnsConstants

/**
 * LXMF Protocol Constants.
 *
 * Matches Python LXMF/LXMF.py and LXMF/LXMessage.py exactly for interoperability.
 */
object LXMFConstants {
    const val APP_NAME = "lxmf"

    // ===== Message Field Types =====
    // Core fields (0x01-0x0F) for structured data exchange

    /** Nested LXMF messages */
    const val FIELD_EMBEDDED_LXMS = 0x01

    /** Sensor/status telemetry data */
    const val FIELD_TELEMETRY = 0x02

    /** Continuous telemetry stream */
    const val FIELD_TELEMETRY_STREAM = 0x03

    /** UI/icon appearance metadata */
    const val FIELD_ICON_APPEARANCE = 0x04

    /** File attachment data */
    const val FIELD_FILE_ATTACHMENTS = 0x05

    /** Image data */
    const val FIELD_IMAGE = 0x06

    /** Audio data with codec specs */
    const val FIELD_AUDIO = 0x07

    /** Message threading info */
    const val FIELD_THREAD = 0x08

    /** Command structures */
    const val FIELD_COMMANDS = 0x09

    /** Command results */
    const val FIELD_RESULTS = 0x0A

    /** Group metadata */
    const val FIELD_GROUP = 0x0B

    /** Delivery tickets */
    const val FIELD_TICKET = 0x0C

    /** Event notifications */
    const val FIELD_EVENT = 0x0D

    /** References to other messages */
    const val FIELD_RNR_REFS = 0x0E

    /** Rendering hints */
    const val FIELD_RENDERER = 0x0F

    // Custom fields (0xFB-0xFF) for extensibility

    /** Custom format/type/protocol identifier */
    const val FIELD_CUSTOM_TYPE = 0xFB

    /** Custom embedded payload */
    const val FIELD_CUSTOM_DATA = 0xFC

    /** Custom metadata */
    const val FIELD_CUSTOM_META = 0xFD

    /** Non-specific field for development */
    const val FIELD_NON_SPECIFIC = 0xFE

    /** Debug field for testing */
    const val FIELD_DEBUG = 0xFF

    // ===== Audio Modes for FIELD_AUDIO =====

    // Codec2 Audio Modes
    const val AM_CODEC2_450PWB = 0x01
    const val AM_CODEC2_450 = 0x02
    const val AM_CODEC2_700C = 0x03
    const val AM_CODEC2_1200 = 0x04
    const val AM_CODEC2_1300 = 0x05
    const val AM_CODEC2_1400 = 0x06
    const val AM_CODEC2_1600 = 0x07
    const val AM_CODEC2_2400 = 0x08
    const val AM_CODEC2_3200 = 0x09

    // Opus Audio Modes
    const val AM_OPUS_OGG = 0x10
    const val AM_OPUS_LBW = 0x11
    const val AM_OPUS_MBW = 0x12
    const val AM_OPUS_PTT = 0x13
    const val AM_OPUS_RT_HDX = 0x14
    const val AM_OPUS_RT_FDX = 0x15
    const val AM_OPUS_STANDARD = 0x16
    const val AM_OPUS_HQ = 0x17
    const val AM_OPUS_BROADCAST = 0x18
    const val AM_OPUS_LOSSLESS = 0x19

    /** Custom audio mode - client determines format */
    const val AM_CUSTOM = 0xFF

    // ===== Renderer Specifications =====

    /** Plain text rendering */
    const val RENDERER_PLAIN = 0x00

    /** Micron markup rendering */
    const val RENDERER_MICRON = 0x01

    /** Markdown rendering */
    const val RENDERER_MARKDOWN = 0x02

    /** BBCode rendering */
    const val RENDERER_BBCODE = 0x03

    // ===== Propagation Node Metadata Fields =====

    const val PN_META_VERSION = 0x00
    const val PN_META_NAME = 0x01
    const val PN_META_SYNC_STRATUM = 0x02
    const val PN_META_SYNC_THROTTLE = 0x03
    const val PN_META_AUTH_BAND = 0x04
    const val PN_META_UTIL_PRESSURE = 0x05
    const val PN_META_CUSTOM = 0xFF

    // ===== Message States =====

    /** Message is being generated */
    const val STATE_GENERATING = 0x00

    /** Message is queued for outbound delivery */
    const val STATE_OUTBOUND = 0x01

    /** Message is currently being sent */
    const val STATE_SENDING = 0x02

    /** Message has been sent */
    const val STATE_SENT = 0x04

    /** Message has been delivered */
    const val STATE_DELIVERED = 0x08

    /** Message was rejected */
    const val STATE_REJECTED = 0xFD

    /** Message was cancelled */
    const val STATE_CANCELLED = 0xFE

    /** Message delivery failed */
    const val STATE_FAILED = 0xFF

    // ===== Message Representations =====

    /** Unknown representation */
    const val REPRESENTATION_UNKNOWN = 0x00

    /** Message fits in a single packet */
    const val REPRESENTATION_PACKET = 0x01

    /** Message requires Resource transfer */
    const val REPRESENTATION_RESOURCE = 0x02

    // ===== Delivery Methods =====

    /** Single encrypted packet, no link needed */
    const val METHOD_OPPORTUNISTIC = 0x01

    /** Link-based delivery with forward secrecy */
    const val METHOD_DIRECT = 0x02

    /** Via propagation node network */
    const val METHOD_PROPAGATED = 0x03

    /** QR code / URI encoding for offline transport */
    const val METHOD_PAPER = 0x05

    // ===== Unverified Reasons =====

    /** Source identity is unknown */
    const val UNVERIFIED_SOURCE_UNKNOWN = 0x01

    /** Signature validation failed */
    const val UNVERIFIED_SIGNATURE_INVALID = 0x02

    // ===== Size Constants =====

    /** Destination hash length in bytes (16) */
    const val DESTINATION_LENGTH = RnsConstants.TRUNCATED_HASH_BYTES

    /** Ed25519 signature length in bytes (64) */
    const val SIGNATURE_LENGTH = 64

    /** Ticket length in bytes (16) */
    const val TICKET_LENGTH = RnsConstants.TRUNCATED_HASH_BYTES

    /** Timestamp size in msgpack (8 bytes for float64) */
    const val TIMESTAMP_SIZE = 8

    /** Msgpack structure overhead */
    const val STRUCT_OVERHEAD = 8

    /**
     * LXMF overhead per message:
     * - 16 bytes destination hash
     * - 16 bytes source hash
     * - 64 bytes signature
     * - 8 bytes timestamp
     * - 8 bytes msgpack structure
     * = 112 bytes total
     */
    const val LXMF_OVERHEAD = 2 * DESTINATION_LENGTH + SIGNATURE_LENGTH + TIMESTAMP_SIZE + STRUCT_OVERHEAD

    /**
     * Maximum content for encrypted single-packet message (295 bytes).
     * Calculated as: encrypted MDU - LXMF overhead + destination hash (inferred)
     */
    const val ENCRYPTED_PACKET_MAX_CONTENT = 295

    /**
     * Maximum content for link-based single-packet message (319 bytes).
     * If larger, LXMF will use Resource transfer.
     */
    const val LINK_PACKET_MAX_CONTENT = 319

    /**
     * Maximum content for plain (unencrypted) packet (368 bytes).
     */
    const val PLAIN_PACKET_MAX_CONTENT = 368

    // ===== Ticket Constants =====

    /** Ticket expiry time in seconds (21 days) */
    const val TICKET_EXPIRY = 21L * 24 * 60 * 60

    /** Ticket grace period in seconds (5 days) */
    const val TICKET_GRACE = 5L * 24 * 60 * 60

    /** Ticket renewal threshold in seconds (14 days before expiry) */
    const val TICKET_RENEW = 14L * 24 * 60 * 60

    /** Ticket check interval in seconds (1 day) */
    const val TICKET_INTERVAL = 1L * 24 * 60 * 60

    /** Cost ticket field marker */
    const val COST_TICKET = 0x100

    // ===== Message Expiry =====

    /** Message expiry time in seconds (30 days) */
    const val MESSAGE_EXPIRY = 30L * 24 * 60 * 60

    // ===== Encryption Descriptions =====

    const val ENCRYPTION_DESCRIPTION_AES = "AES-128"
    const val ENCRYPTION_DESCRIPTION_EC = "Curve25519"
    const val ENCRYPTION_DESCRIPTION_UNENCRYPTED = "Unencrypted"

    // ===== Propagation Constants =====

    /** Minimum allowed propagation stamp cost */
    const val PROPAGATION_COST_MIN = 13

    /** Default propagation stamp cost flexibility */
    const val PROPAGATION_COST_FLEX = 3

    /** Default propagation stamp cost target */
    const val PROPAGATION_COST = 16

    /** Default peering stamp cost */
    const val PEERING_COST = 18

    /** Maximum acceptable peering cost */
    const val MAX_PEERING_COST = 26

    /** Maximum KB per propagation transfer */
    const val PROPAGATION_LIMIT_KB = 256

    /** Maximum KB per sync transfer */
    const val SYNC_LIMIT_KB = PROPAGATION_LIMIT_KB * 40

    /** Maximum KB per delivery transfer */
    const val DELIVERY_LIMIT_KB = 1000

    // ===== Propagation Peer Error Codes =====

    /** Error: client not identified */
    const val ERROR_NO_IDENTITY = 0xf0

    /** Error: access denied */
    const val ERROR_NO_ACCESS = 0xf1

    /** Error: invalid propagation stamp */
    const val ERROR_INVALID_STAMP = 0xf5

    // ===== Propagation Request Paths =====

    /** Request path for getting messages from propagation node */
    const val MESSAGE_GET_PATH = "/get"

    /** Request path for offering messages to peers */
    const val OFFER_REQUEST_PATH = "/offer"
}

/**
 * Message state enum for type safety.
 */
enum class MessageState(val value: Int) {
    GENERATING(LXMFConstants.STATE_GENERATING),
    OUTBOUND(LXMFConstants.STATE_OUTBOUND),
    SENDING(LXMFConstants.STATE_SENDING),
    SENT(LXMFConstants.STATE_SENT),
    DELIVERED(LXMFConstants.STATE_DELIVERED),
    REJECTED(LXMFConstants.STATE_REJECTED),
    CANCELLED(LXMFConstants.STATE_CANCELLED),
    FAILED(LXMFConstants.STATE_FAILED);

    companion object {
        fun fromValue(value: Int): MessageState? = entries.find { it.value == value }
    }
}

/**
 * Message representation enum.
 */
enum class MessageRepresentation(val value: Int) {
    UNKNOWN(LXMFConstants.REPRESENTATION_UNKNOWN),
    PACKET(LXMFConstants.REPRESENTATION_PACKET),
    RESOURCE(LXMFConstants.REPRESENTATION_RESOURCE);

    companion object {
        fun fromValue(value: Int): MessageRepresentation? = entries.find { it.value == value }
    }
}

/**
 * Delivery method enum.
 */
enum class DeliveryMethod(val value: Int) {
    OPPORTUNISTIC(LXMFConstants.METHOD_OPPORTUNISTIC),
    DIRECT(LXMFConstants.METHOD_DIRECT),
    PROPAGATED(LXMFConstants.METHOD_PROPAGATED),
    PAPER(LXMFConstants.METHOD_PAPER);

    companion object {
        fun fromValue(value: Int): DeliveryMethod? = entries.find { it.value == value }
    }
}

/**
 * Reason why a message could not be verified.
 */
enum class UnverifiedReason(val value: Int) {
    SOURCE_UNKNOWN(LXMFConstants.UNVERIFIED_SOURCE_UNKNOWN),
    SIGNATURE_INVALID(LXMFConstants.UNVERIFIED_SIGNATURE_INVALID);

    companion object {
        fun fromValue(value: Int): UnverifiedReason? = entries.find { it.value == value }
    }
}

/**
 * Audio mode for FIELD_AUDIO.
 */
enum class AudioMode(val value: Int) {
    // Codec2 modes
    CODEC2_450PWB(LXMFConstants.AM_CODEC2_450PWB),
    CODEC2_450(LXMFConstants.AM_CODEC2_450),
    CODEC2_700C(LXMFConstants.AM_CODEC2_700C),
    CODEC2_1200(LXMFConstants.AM_CODEC2_1200),
    CODEC2_1300(LXMFConstants.AM_CODEC2_1300),
    CODEC2_1400(LXMFConstants.AM_CODEC2_1400),
    CODEC2_1600(LXMFConstants.AM_CODEC2_1600),
    CODEC2_2400(LXMFConstants.AM_CODEC2_2400),
    CODEC2_3200(LXMFConstants.AM_CODEC2_3200),

    // Opus modes
    OPUS_OGG(LXMFConstants.AM_OPUS_OGG),
    OPUS_LBW(LXMFConstants.AM_OPUS_LBW),
    OPUS_MBW(LXMFConstants.AM_OPUS_MBW),
    OPUS_PTT(LXMFConstants.AM_OPUS_PTT),
    OPUS_RT_HDX(LXMFConstants.AM_OPUS_RT_HDX),
    OPUS_RT_FDX(LXMFConstants.AM_OPUS_RT_FDX),
    OPUS_STANDARD(LXMFConstants.AM_OPUS_STANDARD),
    OPUS_HQ(LXMFConstants.AM_OPUS_HQ),
    OPUS_BROADCAST(LXMFConstants.AM_OPUS_BROADCAST),
    OPUS_LOSSLESS(LXMFConstants.AM_OPUS_LOSSLESS),

    CUSTOM(LXMFConstants.AM_CUSTOM);

    companion object {
        fun fromValue(value: Int): AudioMode? = entries.find { it.value == value }
    }
}

/**
 * Renderer specification for FIELD_RENDERER.
 */
enum class Renderer(val value: Int) {
    PLAIN(LXMFConstants.RENDERER_PLAIN),
    MICRON(LXMFConstants.RENDERER_MICRON),
    MARKDOWN(LXMFConstants.RENDERER_MARKDOWN),
    BBCODE(LXMFConstants.RENDERER_BBCODE);

    companion object {
        fun fromValue(value: Int): Renderer? = entries.find { it.value == value }
    }
}
