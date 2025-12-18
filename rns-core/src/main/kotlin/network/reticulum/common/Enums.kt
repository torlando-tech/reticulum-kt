package network.reticulum.common

/**
 * Destination type constants matching Python implementation.
 */
enum class DestinationType(val value: Int) {
    SINGLE(0x00),   // Single recipient with individual encryption
    GROUP(0x01),    // Group destination with pre-shared key
    PLAIN(0x02),    // Unencrypted plaintext destination
    LINK(0x03);     // Link-specific destination

    companion object {
        fun fromValue(value: Int): DestinationType =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown destination type: $value")
    }
}

/**
 * Destination direction constants.
 */
enum class DestinationDirection(val value: Int) {
    IN(0x11),   // Incoming destination (listens for packets)
    OUT(0x12);  // Outgoing destination (sends to remote)

    companion object {
        fun fromValue(value: Int): DestinationDirection =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown destination direction: $value")
    }
}

/**
 * Packet type constants.
 */
enum class PacketType(val value: Int) {
    DATA(0x00),         // Data packets
    ANNOUNCE(0x01),     // Destination announcements
    LINKREQUEST(0x02),  // Link establishment requests
    PROOF(0x03);        // Delivery proofs

    companion object {
        fun fromValue(value: Int): PacketType =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown packet type: $value")
    }
}

/**
 * Header type constants.
 */
enum class HeaderType(val value: Int) {
    HEADER_1(0x00),  // Normal format (no transport_id)
    HEADER_2(0x01);  // Transport format (with transport_id)

    companion object {
        fun fromValue(value: Int): HeaderType =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown header type: $value")
    }
}

/**
 * Transport type constants.
 */
enum class TransportType(val value: Int) {
    BROADCAST(0x00),  // Unrouted broadcast
    TRANSPORT(0x01),  // Routed transport
    RELAY(0x02),      // Relay mode
    TUNNEL(0x03);     // Tunnel mode

    companion object {
        fun fromValue(value: Int): TransportType =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown transport type: $value")
    }
}

/**
 * Context flag for packets.
 */
enum class ContextFlag(val value: Int) {
    UNSET(0x00),
    SET(0x01);

    companion object {
        fun fromValue(value: Int): ContextFlag =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown context flag: $value")
    }
}

/**
 * Packet context types.
 */
enum class PacketContext(val value: Int) {
    NONE(0x00),
    RESOURCE(0x01),
    RESOURCE_ADV(0x02),
    RESOURCE_REQ(0x03),
    RESOURCE_HMU(0x04),
    RESOURCE_PRF(0x05),
    RESOURCE_ICL(0x06),
    RESOURCE_RCL(0x07),
    CACHE_REQUEST(0x08),
    REQUEST(0x09),
    RESPONSE(0x0A),
    PATH_RESPONSE(0x0B),
    COMMAND(0x0C),
    COMMAND_STATUS(0x0D),
    CHANNEL(0x0E),
    KEEPALIVE(0xFA),
    LINKIDENTIFY(0xFB),
    LINKCLOSE(0xFC),
    LINKPROOF(0xFD),
    LRRTT(0xFE),
    LRPROOF(0xFF);

    companion object {
        fun fromValue(value: Int): PacketContext =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown packet context: $value")
    }
}

/**
 * Link state machine states.
 */
enum class LinkState {
    PENDING,    // Awaiting establishment
    HANDSHAKE,  // Handshake in progress
    ACTIVE,     // Fully established
    STALE,      // No recent traffic
    CLOSED      // Connection closed
}

/**
 * Resource transfer states.
 */
enum class ResourceState {
    NONE,
    QUEUED,
    ADVERTISED,
    TRANSFERRING,
    AWAITING_PROOF,
    ASSEMBLING,
    COMPLETE,
    FAILED,
    CORRUPT
}

/**
 * Packet receipt status.
 */
enum class ReceiptStatus {
    FAILED,
    SENT,
    DELIVERED,
    CULLED
}

/**
 * Proof strategy for destinations.
 */
enum class ProofStrategy {
    NONE,  // No proofs
    APP,   // Application decides
    ALL    // Always prove receipt
}

/**
 * AES encryption modes.
 */
enum class AesMode(val keySize: Int) {
    AES_128_CBC(16),
    AES_256_CBC(32);
}

/**
 * Interface operational modes.
 */
enum class InterfaceMode {
    FULL,           // Full access point with routing
    POINT_TO_POINT, // Direct peer-to-peer
    ACCESS_POINT,   // WiFi-like access point
    ROAMING,        // Mobile roaming interface
    BOUNDARY,       // Network boundary
    GATEWAY         // Gateway between networks
}
