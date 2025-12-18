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

    /** Reverse table entry timeout in milliseconds (8 minutes). */
    const val REVERSE_TIMEOUT = 8L * 60 * 1000

    /** Link proof timeout in milliseconds (10 minutes). */
    const val LINK_PROOF_TIMEOUT = 10L * 60 * 1000

    /** Destination table entry timeout (1 week in milliseconds). */
    const val DESTINATION_TIMEOUT = 7L * 24 * 60 * 60 * 1000

    /** Maximum receipts to track. */
    const val MAX_RECEIPTS = 1024

    /** Maximum announce rate timestamps per destination. */
    const val MAX_RATE_TIMESTAMPS = 16

    /** Maximum random blobs to persist per destination. */
    const val PERSIST_RANDOM_BLOBS = 32

    /** Maximum random blobs to keep in memory per destination. */
    const val MAX_RANDOM_BLOBS = 64

    /** Maximum size of packet hashlist. */
    const val HASHLIST_MAXSIZE = 1_000_000

    /** Job loop interval in milliseconds. */
    const val JOB_INTERVAL = 250L

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

    /** Interface jobs interval in milliseconds. */
    const val INTERFACE_JOBS_INTERVAL = 5000L

    /** Application name for transport destinations. */
    const val APP_NAME = "rnstransport"

    // ===== Latency and Timeout Constants =====

    /** Default per-hop timeout in milliseconds (6 seconds). */
    const val DEFAULT_PER_HOP_TIMEOUT = 6000L

    /** Minimum first hop timeout in milliseconds (3 seconds). */
    const val MIN_FIRST_HOP_TIMEOUT = 3000L

    /** Held announce timeout in milliseconds (30 seconds). */
    const val HELD_ANNOUNCE_TIMEOUT = 30_000L

    /** Traffic speed update interval in milliseconds (1 second). */
    const val SPEED_UPDATE_INTERVAL = 1000L
}
