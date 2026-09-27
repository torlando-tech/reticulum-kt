package network.reticulum.transport

/**
 * Transport-related constants from RNS Transport.py.
 */
object TransportConstants {
    /** Transport types. */
    const val BROADCAST = 0x00
    const val TRANSPORT = 0x01
    const val RELAY = 0x02
    const val TUNNEL = 0x03

    /** Reachability states. */
    const val REACHABILITY_UNREACHABLE = 0x00
    const val REACHABILITY_DIRECT = 0x01
    const val REACHABILITY_TRANSPORT = 0x02

    /** Path states. */
    const val STATE_UNKNOWN = 0x00
    const val STATE_UNRESPONSIVE = 0x01
    const val STATE_RESPONSIVE = 0x02

    /** Maximum number of hops. */
    const val PATHFINDER_M = 128

    /** Path retransmit retries. */
    const val PATHFINDER_R = 1

    /** Retry grace period in seconds. */
    const val PATHFINDER_G = 5

    /** Random window for announce rebroadcast in seconds. */
    const val PATHFINDER_RW = 0.5

    /** Path expiration time (1 week in milliseconds). */
    const val PATHFINDER_E = 7L * 24 * 60 * 60 * 1000

    /** Access Point path expiration (1 day in milliseconds). */
    const val AP_PATH_TIME = 24L * 60 * 60 * 1000

    /** Roaming path expiration (6 hours in milliseconds). */
    const val ROAMING_PATH_TIME = 6L * 60 * 60 * 1000

    /** Maximum local rebroadcasts of an announce. */
    const val LOCAL_REBROADCASTS_MAX = 2

    /** Default timeout for path requests in milliseconds. */
    const val PATH_REQUEST_TIMEOUT = 15_000L

    /** Grace time before path announcement in milliseconds. */
    const val PATH_REQUEST_GRACE = 400L

    /** Extra grace for roaming interfaces in milliseconds. */
    const val PATH_REQUEST_RG = 1500L

    /** Minimum interval for automated path requests in milliseconds. */
    const val PATH_REQUEST_MI = 20_000L

    /**
     * How long a destination stays in the in-flight path request table, in milliseconds
     * (python PATH_REQUEST_GATE_TIMEOUT = 45 s, Transport.py:135). While a destination is
     * in-flight, further path requests for it are batched onto the first rather than
     * queued and processed again. The entry is released early when the request is
     * answered or the matching announce arrives; this is the ceiling for the rest.
     */
    const val PATH_REQUEST_GATE_TIMEOUT = 45_000L

    // Inbound traffic classes (python Transport.py:111-114). The value is the index of the
    // queue a packet lands in, and the drainer serves queues in index order, so a lower
    // value is a higher priority: link and data traffic first, announces second, path
    // requests third, and anything from a peer under ingress limiting last.
    const val TC_DATA = 0x00
    const val TC_ANNOUNCE = 0x01
    const val TC_PATH_REQUEST = 0x02
    const val TC_INGRESS_LIMITED = 0x03

    // Inbound queue depth per traffic class (python Transport.py:143-146). A full queue drops
    // the new packet and never blocks the interface thread. The data queue is the deepest by
    // design — a storm of any other class can only fill its own queue.
    const val INBOUND_DA_QUEUE_LENGTH = 1024
    const val INBOUND_AN_QUEUE_LENGTH = 128
    const val INBOUND_PR_QUEUE_LENGTH = 128
    const val INBOUND_IL_QUEUE_LENGTH = 8

    /** Reverse table entry timeout in milliseconds (8 minutes). */
    const val REVERSE_TIMEOUT = 8L * 60 * 1000

    /** Link proof timeout in milliseconds (10 minutes). */
    const val LINK_PROOF_TIMEOUT = 10L * 60 * 1000

    /** Link table entry timeout for validated links: python `LINK_TIMEOUT = RNS.Link.STALE_TIME * 1.25` (Transport.py:152), 900 s. */
    val LINK_TIMEOUT: Long = (network.reticulum.link.LinkConstants.STALE_TIME * 1.25).toLong()

    /** python `TUNNEL_TIMEOUT` / `TUNNEL_PATH_TIMEOUT` (Transport.py:157-158): eight hours unused. */
    const val TUNNEL_TIMEOUT = 8L * 60 * 60 * 1000
    const val TUNNEL_PATH_TIMEOUT = 8L * 60 * 60 * 1000

    /** Destination table entry timeout (1 week in milliseconds). */
    const val DESTINATION_TIMEOUT = 7L * 24 * 60 * 60 * 1000

    /** Linger time for pathless, never-used known destinations before eviction
     *  (python Transport.UNUSED_DESTINATION_LINGER = 6*60 s). */
    const val UNUSED_DESTINATION_LINGER = 6L * 60 * 1000

    /** Maximum receipts to track. */
    const val MAX_RECEIPTS = 1024

    /** Maximum announce rate timestamps per destination. */
    const val MAX_RATE_TIMESTAMPS = 16

    /**
     * How long an idle announce-rate entry is kept before being culled. Long enough to
     * outlast any block it could still be serving; the block itself is also checked, so a
     * currently-blocked destination is never forgotten early.
     */
    const val ANNOUNCE_RATE_ENTRY_TTL = 60L * 60 * 1000

    /** Maximum random blobs to persist per destination. */
    const val PERSIST_RANDOM_BLOBS = 32

    /** Maximum random blobs to keep in memory per destination. */
    const val MAX_RANDOM_BLOBS = 64

    /** Maximum size of packet hashlist. */
    const val HASHLIST_MAXSIZE = 1_000_000

    /** Job loop interval in milliseconds. */
    const val JOB_INTERVAL = 250L

    /**
     * Pause after tearing down links on shutdown, so the LINKCLOSE packets reach the
     * interfaces before the tables are cleared (python Transport.py:3637).
     */
    const val LINK_TEARDOWN_DRAIN_MS = 150L

    /** Link check interval in milliseconds. */
    const val LINKS_CHECK_INTERVAL = 1000L

    /** Receipts check interval in milliseconds. */
    const val RECEIPTS_CHECK_INTERVAL = 1000L

    /** Announces check interval in milliseconds. */
    const val ANNOUNCES_CHECK_INTERVAL = 1000L

    /** Tables cull interval in milliseconds. */
    const val TABLES_CULL_INTERVAL = 5000L

    /** Cache clean interval in milliseconds. */
    const val CACHE_CLEAN_INTERVAL = 300_000L

    /** Packet cache timeout in milliseconds (1 hour). */
    const val PACKET_CACHE_TIMEOUT = 60L * 60 * 1000

    /** Interface jobs interval in milliseconds. */
    const val INTERFACE_JOBS_INTERVAL = 5000L

    /** Application name for transport destinations. */
    const val APP_NAME = "rnstransport"

    // ===== Latency and Timeout Constants =====

    /** Default per-hop timeout in milliseconds (6 seconds). */
    const val DEFAULT_PER_HOP_TIMEOUT = 6000L

    /** Minimum first hop timeout in milliseconds (3 seconds). */
    const val MIN_FIRST_HOP_TIMEOUT = 3000L

    /** Traffic speed update interval in milliseconds (1 second). */
    const val SPEED_UPDATE_INTERVAL = 1000L

    // ===== Announce Queue Constants =====

    /** Maximum number of announces that can be queued per interface (python Reticulum.py:111). */
    const val MAX_QUEUED_ANNOUNCES = 4096

    /** Time in ms after which a queued announce is stale; 3h (python Reticulum.py:112). */
    const val QUEUED_ANNOUNCE_LIFE = 3L * 60 * 60 * 1000

    /** Default announce capacity as percentage of interface bitrate (2%). */
    const val ANNOUNCE_CAP = 0.02

    /** Target announce rate for rate limiting. */
    const val ANNOUNCE_RATE_TARGET = 0.12

    /** Grace period multiplier for announce rate limiting. */
    const val ANNOUNCE_RATE_GRACE = 1.5

    /** Penalty multiplier for exceeding announce rate. */
    const val ANNOUNCE_RATE_PENALTY = 5.0

    // ===== Path State Constants =====

    /** Path state is unknown. */
    const val PATH_STATE_UNKNOWN = 0

    /** Path is unresponsive. */
    const val PATH_STATE_UNRESPONSIVE = 1

    /** Path is responsive. */
    const val PATH_STATE_RESPONSIVE = 2

    /** Path unresponsive timeout in milliseconds (15 minutes). */
    const val PATH_UNRESPONSIVE_TIMEOUT = 15L * 60 * 1000

    /** Base timeout for first hop in milliseconds (5 seconds). */
    const val FIRST_HOP_TIMEOUT_BASE = 5000L

    /** Additional timeout per hop in milliseconds (2 seconds). */
    const val FIRST_HOP_TIMEOUT_PER_HOP = 2000L

    // ===== Tunnel Constants =====

    /** Tunnel expiry time (same as DESTINATION_TIMEOUT - 1 week in milliseconds). */
    const val TUNNEL_EXPIRY = 7L * 24 * 60 * 60 * 1000

    /** Maximum number of tunnels to maintain. */
    const val MAX_TUNNELS = 10000

    /** Grace period after startup before interface-based path culling (30 seconds). */
    const val STARTUP_GRACE_PERIOD = 30_000L

    /**
     * Cap on alternate rows kept per destination, beyond the selected one.
     *
     * A node reaches a destination over an interface, so the natural bound is the number
     * of registered interfaces and the real figure is small — a phone runs three. This is
     * a backstop against a host with many interfaces multiplying a path table that already
     * holds thousands of destinations, not a routing decision. Alternates are keyed by
     * receiving interface, so a peer cannot push past it by announcing.
     */
    const val MAX_ALTERNATE_ROWS = 4
}
