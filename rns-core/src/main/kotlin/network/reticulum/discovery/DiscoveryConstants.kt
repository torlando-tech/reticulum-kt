package network.reticulum.discovery

/**
 * Constants for interface discovery, matching Python Discovery.py.
 *
 * Msgpack field keys are integers (not strings) for compact wire format.
 * Keys 0x00-0x0E are type-specific fields, 0xFE-0xFF are base fields.
 */
object DiscoveryConstants {
    const val APP_NAME = "rnstransport"

    // Msgpack field keys (Discovery.py lines 8-24)
    const val NAME            = 0xFF
    const val TRANSPORT_ID    = 0xFE
    const val INTERFACE_TYPE  = 0x00
    const val TRANSPORT       = 0x01
    const val REACHABLE_ON    = 0x02
    const val LATITUDE        = 0x03
    const val LONGITUDE       = 0x04
    const val HEIGHT          = 0x05
    const val PORT            = 0x06
    const val IFAC_NETNAME    = 0x07
    const val IFAC_NETKEY     = 0x08
    const val FREQUENCY       = 0x09
    const val BANDWIDTH       = 0x0A
    const val SPREADING_FACTOR = 0x0B
    const val CODING_RATE     = 0x0C
    const val MODULATION      = 0x0D
    const val CHANNEL         = 0x0E

    // Flags byte
    const val FLAG_SIGNED: Byte    = 0x01
    const val FLAG_ENCRYPTED: Byte = 0x02

    // PoW parameters
    const val DEFAULT_STAMP_VALUE = 14
    const val WORKBLOCK_EXPAND_ROUNDS = 20

    // Status thresholds (seconds, matching Python)
    const val THRESHOLD_UNKNOWN = 24 * 60 * 60L        // 1 day
    const val THRESHOLD_STALE   = 3 * 24 * 60 * 60L    // 3 days
    const val THRESHOLD_REMOVE  = 7 * 24 * 60 * 60L    // 7 days

    // Status codes (matching Python)
    const val STATUS_STALE     = 0
    const val STATUS_UNKNOWN   = 100
    const val STATUS_AVAILABLE = 1000

    // Announce timing (seconds)
    const val JOB_INTERVAL = 60L
    const val DEFAULT_ANNOUNCE_INTERVAL = 6 * 60 * 60L  // 6 hours
    const val MIN_ANNOUNCE_INTERVAL = 5 * 60L            // 5 minutes

    // Auto-connect monitoring (seconds)
    const val MONITOR_INTERVAL = 5L
    const val DETACH_THRESHOLD = 12L

    // Interface types that support discovery
    val DISCOVERABLE_INTERFACE_TYPES = setOf(
        "TCPServerInterface", "TCPClientInterface",
        "RNodeInterface", "I2PInterface"
    )

    // Interface types that support auto-connect
    val AUTOCONNECT_TYPES = setOf("TCPServerInterface")
}
