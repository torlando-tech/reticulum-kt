package network.reticulum.transport

import network.reticulum.common.toHexString

/**
 * State of a path in the path table.
 */
enum class PathState {
    /** Path is active and responding. */
    ACTIVE,

    /** Path has had failed transmissions but not enough to be considered stale. */
    UNRESPONSIVE,

    /** Path has too many failures and should be expired. */
    STALE
}

/**
 * Entry in the path table, storing routing information to a destination.
 *
 * The path table maps destination hashes to routing information needed
 * to reach that destination.
 */
data class PathEntry(
    /** When this path was learned (epoch millis). */
    val timestamp: Long,

    /** Next hop transport ID (16 bytes). For direct destinations, this is the destination hash. */
    val nextHop: ByteArray,

    /** Number of hops to reach the destination. */
    val hops: Int,

    /** When this path expires (epoch millis). */
    val expires: Long,

    /** Random blobs from announces for timing verification. */
    val randomBlobs: MutableList<ByteArray>,

    /** Interface this path was learned on. */
    val receivingInterfaceHash: ByteArray,

    /** Hash of the announce packet that created this path. */
    val announcePacketHash: ByteArray,

    /** Current state of this path. */
    var state: PathState = PathState.ACTIVE,

    /** Number of consecutive failures for this path. */
    var failureCount: Int = 0
) {
    /** Check if this path has expired. */
    fun isExpired(): Boolean = System.currentTimeMillis() > expires

    /** Update timestamp to current time. */
    fun touch(): PathEntry = copy(timestamp = System.currentTimeMillis())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathEntry) return false
        return nextHop.contentEquals(other.nextHop) &&
               hops == other.hops &&
               receivingInterfaceHash.contentEquals(other.receivingInterfaceHash)
    }

    override fun hashCode(): Int {
        var result = nextHop.contentHashCode()
        result = 31 * result + hops
        result = 31 * result + receivingInterfaceHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "PathEntry(nextHop=${nextHop.toHexString()}, hops=$hops, expires=${expires - System.currentTimeMillis()}ms)"
}

/**
 * Entry in the link table, storing routing information for active links.
 *
 * Used to route packets belonging to established links through
 * transport nodes.
 */
data class LinkEntry(
    /** When this entry was created (epoch millis). */
    val timestamp: Long,

    /** Next hop transport ID. */
    val nextHop: ByteArray,

    /** Interface for next hop. */
    val nextHopInterfaceHash: ByteArray,

    /** Remaining hops to destination. */
    val remainingHops: Int,

    /** Interface packet was received on. */
    val receivingInterfaceHash: ByteArray,

    /** Hops the packet has taken so far. */
    val takenHops: Int,

    /** Original destination hash. */
    val destinationHash: ByteArray,

    /** Whether this link has been validated. */
    var validated: Boolean,

    /** Proof timeout timestamp. */
    val proofTimeout: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkEntry) return false
        return nextHop.contentEquals(other.nextHop) &&
               destinationHash.contentEquals(other.destinationHash)
    }

    override fun hashCode(): Int {
        var result = nextHop.contentHashCode()
        result = 31 * result + destinationHash.contentHashCode()
        return result
    }

    override fun toString(): String =
        "LinkEntry(dest=${destinationHash.toHexString()}, hops=$remainingHops/$takenHops)"
}

/**
 * Entry in the reverse table, used to route proofs back to senders.
 *
 * When a packet is forwarded, an entry is made so proofs can be
 * routed back along the same path.
 */
data class ReverseEntry(
    /** Interface the packet was received on. */
    val receivingInterfaceHash: ByteArray,

    /** Interface the packet was forwarded to. */
    val outboundInterfaceHash: ByteArray,

    /** When this entry was created (epoch millis). */
    val timestamp: Long
) {
    /** Check if this entry has expired. */
    fun isExpired(): Boolean =
        System.currentTimeMillis() > timestamp + TransportConstants.REVERSE_TIMEOUT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReverseEntry) return false
        return receivingInterfaceHash.contentEquals(other.receivingInterfaceHash) &&
               outboundInterfaceHash.contentEquals(other.outboundInterfaceHash)
    }

    override fun hashCode(): Int {
        var result = receivingInterfaceHash.contentHashCode()
        result = 31 * result + outboundInterfaceHash.contentHashCode()
        return result
    }
}

/**
 * Entry in the announce table, storing announces waiting to be retransmitted.
 */
data class AnnounceEntry(
    /** The destination hash being announced. */
    val destinationHash: ByteArray,

    /** When the announce was received. */
    val timestamp: Long,

    /** Number of times this has been retransmitted. */
    var retransmits: Int,

    /** When the next retransmit should occur. */
    var retransmitTimeout: Long,

    /** The raw announce packet. */
    val raw: ByteArray,

    /** Number of hops the announce has taken. */
    val hops: Int,

    /** Interface the announce was received on. */
    val receivingInterfaceHash: ByteArray,

    /** Local rebroadcast count. */
    var localRebroadcasts: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnnounceEntry) return false
        return destinationHash.contentEquals(other.destinationHash)
    }

    override fun hashCode(): Int = destinationHash.contentHashCode()
}

/**
 * Queued announce waiting to be transmitted.
 */
data class QueuedAnnounce(
    /** Destination hash. */
    val destinationHash: ByteArray,

    /** When the announce was queued. */
    val time: Long,

    /** Number of hops. */
    val hops: Int,

    /** When the original announce was emitted. */
    val emitted: Long,

    /** Raw packet data. */
    val raw: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedAnnounce) return false
        return destinationHash.contentEquals(other.destinationHash)
    }

    override fun hashCode(): Int = destinationHash.contentHashCode()
}

/**
 * Entry storing a path discovered through a tunnel.
 *
 * When announces are received on tunnel interfaces, paths are stored
 * in the tunnel so they can be restored if the tunnel reconnects.
 */
data class TunnelPathEntry(
    /** When path was discovered (epoch millis). */
    val timestamp: Long,

    /** Next hop transport ID to reach the destination. */
    val receivedFrom: ByteArray,

    /** Number of hops to destination. */
    val hops: Int,

    /** When this path expires (epoch millis). */
    val expires: Long,

    /** Random blobs from announces for timing verification. */
    val randomBlobs: MutableList<ByteArray>,

    /** Hash of the announce packet that created this path (for cache lookup). */
    val packetHash: ByteArray
) {
    /** Check if this path has expired. */
    fun isExpired(): Boolean = System.currentTimeMillis() > expires

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelPathEntry) return false
        return receivedFrom.contentEquals(other.receivedFrom) &&
               hops == other.hops &&
               packetHash.contentEquals(other.packetHash)
    }

    override fun hashCode(): Int {
        var result = receivedFrom.contentHashCode()
        result = 31 * result + hops
        result = 31 * result + packetHash.contentHashCode()
        return result
    }
}

/**
 * Information about an active tunnel.
 *
 * Tunnels maintain routing paths across network disruptions. When a node
 * receives announces through an interface with an associated tunnel, those
 * paths are stored. If the connection drops and reconnects, the paths are
 * automatically restored.
 */
data class TunnelInfo(
    /** Unique tunnel ID: SHA256(public_key + interface_hash). */
    val tunnelId: ByteArray,

    /** Interface this tunnel operates on. Null when persisted/disconnected. */
    var interface_: InterfaceRef?,

    /** When this tunnel was created (epoch millis). */
    val createdAt: Long = System.currentTimeMillis(),

    /** Last activity timestamp (epoch millis). */
    var lastActivity: Long = System.currentTimeMillis(),

    /** When this tunnel expires (epoch millis). */
    var expires: Long = System.currentTimeMillis() + TransportConstants.DESTINATION_TIMEOUT,

    /** Total bytes transmitted through this tunnel. */
    var txBytes: Long = 0,

    /** Total bytes received through this tunnel. */
    var rxBytes: Long = 0,

    /** Paths discovered through this tunnel: destHash -> TunnelPathEntry. */
    val paths: MutableMap<ByteArrayKey, TunnelPathEntry> = mutableMapOf()
) {
    /** Check if this tunnel has expired. */
    fun isExpired(): Boolean = System.currentTimeMillis() > expires

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelInfo) return false
        return tunnelId.contentEquals(other.tunnelId)
    }

    override fun hashCode(): Int = tunnelId.contentHashCode()
}
