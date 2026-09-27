package network.reticulum.transport

import network.reticulum.common.ByteArrayKey
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
 * A blackholed-identity record, mirroring python's
 * `Transport.blackholed_identities[hash]` dict
 * `{"source", "until", "reason"}` (RNS/Transport.py:3417).
 */
data class BlackholeEntry(
    /** Identity hash that blackholed this one (own hash for local entries). */
    val source: ByteArray,
    /** Optional expiry as an epoch-millis timestamp; null = permanent. */
    val until: Long?,
    /** Optional human-readable reason. */
    val reason: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlackholeEntry) return false
        return source.contentEquals(other.source) && until == other.until && reason == other.reason
    }

    override fun hashCode(): Int {
        var result = source.contentHashCode()
        result = 31 * result + (until?.hashCode() ?: 0)
        result = 31 * result + (reason?.hashCode() ?: 0)
        return result
    }
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
/**
 * Per-destination announce-rate state for one rate-limited interface
 * (python `Transport.announce_rate_table`, `Transport.py:2304-2330`).
 *
 * The mechanism is a tolerance, not a hard gate. Each announce that arrives sooner than
 * `announce_rate_target` since the last ACCEPTED one adds a violation, and each one that
 * arrives later removes one; only when violations exceed `announce_rate_grace` does the
 * destination get blocked, and only until `last + target + penalty`. A destination that
 * announces fast once is forgiven; one that will not slow down is not.
 *
 * Note which field the block is measured from: `last` is the last announce that was
 * ACCEPTED, and it deliberately stops advancing once blocking begins, so a destination
 * cannot push its own unblock time further out by continuing to announce.
 */
data class AnnounceRateEntry(
    /** When the last accepted announce arrived. Frozen while blocked. */
    var last: Long,

    /** Running violation count, incremented and decremented as the rate moves. */
    var rateViolations: Int = 0,

    /** Announces are suppressed until this time; 0 when not blocked. */
    var blockedUntil: Long = 0,

    /** Arrival times, newest last, capped at [TransportConstants.MAX_RATE_TIMESTAMPS]. */
    val timestamps: MutableList<Long> = mutableListOf(),
)

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
    var localRebroadcasts: Int,

    /**
     * When set, the rebroadcast goes out as a PATH_RESPONSE rather than a plain announce
     * (python `block_rebroadcasts`, `Transport.py:3494`, applied at `:783-786`). A path
     * response answers one requester; a plain announce invites every hearer to rebroadcast
     * it onward, which is exactly what must not happen when we are replying to a request.
     */
    var blockRebroadcasts: Boolean = false,

    /**
     * Interface the rebroadcast is pinned to, or null to emit on all eligible interfaces
     * (python `attached_interface`, `Transport.py:3519`). A path request is answered only
     * on the interface it arrived on.
     */
    var attachedInterfaceHash: ByteArray? = null,

    /**
     * Who this announce came from: the announcing transport node's ID when the packet
     * carried one, otherwise the destination's own hash (python `received_from`,
     * `Transport.py:2178`/`:2202`). It is a HASH, not an interface, despite the reference
     * naming its slot IDX_AT_RCVD_IF.
     */
    var receivedFrom: ByteArray = destinationHash,
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
    var expires: Long = System.currentTimeMillis() + TransportConstants.TUNNEL_TIMEOUT,

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

/**
 * One way of reaching a destination, as an observer sees it.
 *
 * A snapshot, not a handle: the arrays are copies and nothing here writes back. Exists so
 * a consumer can show which interface a destination is currently reached over and what
 * else is available, without reaching into the routing tables.
 */
data class PathRow(
    /** Interface this path was learned on, and would be sent over. */
    val interfaceHash: ByteArray,
    /** Resolved name, or null if the interface is no longer registered. */
    val interfaceName: String?,
    /** Whether that interface is online right now. */
    val interfaceOnline: Boolean,
    /** Next hop transport id, or the destination itself when it is one hop away. */
    val nextHop: ByteArray,
    val hops: Int,
    val learnedAt: Long,
    val expiresAt: Long,
    val state: PathState,
    val failureCount: Int,
    /** True for the row traffic is using; exactly one row in a set has it. */
    val selected: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathRow) return false
        return interfaceHash.contentEquals(other.interfaceHash) &&
            nextHop.contentEquals(other.nextHop) &&
            interfaceName == other.interfaceName &&
            interfaceOnline == other.interfaceOnline &&
            hops == other.hops && learnedAt == other.learnedAt &&
            expiresAt == other.expiresAt && state == other.state &&
            failureCount == other.failureCount && selected == other.selected
    }

    override fun hashCode(): Int =
        31 * interfaceHash.contentHashCode() + nextHop.contentHashCode()
}

/**
 * Every way this node knows of reaching one destination, selected row first.
 *
 * [rows] always holds at least the selected row. Additional rows are alternates, one per
 * interface, which is what bounds the set: an announcing peer cannot add rows, because a
 * row is keyed by the interface it arrived on.
 */
data class PathReachability(
    val destinationHash: ByteArray,
    val rows: List<PathRow>,
) {
    val selected: PathRow get() = rows.first()

    /** Alternates that could carry traffic now: a registered, online interface. */
    val usableAlternates: List<PathRow>
        get() = rows.drop(1).filter { it.interfaceOnline && it.state != PathState.STALE }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathReachability) return false
        return destinationHash.contentEquals(other.destinationHash) && rows == other.rows
    }

    override fun hashCode(): Int = 31 * destinationHash.contentHashCode() + rows.hashCode()
}

/**
 * How much of this node's path table has a second way through.
 *
 * The question behind it is whether failover has anything to fall back on in practice.
 * A node can hold thousands of paths and still have an alternate for none of them, in
 * which case the machinery that selects between them is machinery for a case that does
 * not arise. That is a property of a real deployment over days, not of a test.
 *
 * Aggregate on purpose. A count is safe to put in a status screen or a bug report; a
 * list of destination hashes is a map of who the operator can reach.
 */
data class AlternatePathStats(
    /** Destinations holding at least one alternate row. */
    val destinationsWithAlternate: Int,
    /** Alternate rows in total, never counting the selected row. */
    val alternateRows: Int,
)
