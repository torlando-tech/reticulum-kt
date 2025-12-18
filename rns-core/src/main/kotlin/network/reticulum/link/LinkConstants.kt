package network.reticulum.link

import network.reticulum.common.RnsConstants

/**
 * Constants for Link operations, matching Python RNS Link.py.
 */
object LinkConstants {
    // Key sizes
    const val ECPUBSIZE = 64  // 32 + 32 (X25519 + Ed25519 public)
    const val KEYSIZE = 32

    // Link states
    const val PENDING = 0x00
    const val HANDSHAKE = 0x01
    const val ACTIVE = 0x02
    const val STALE = 0x03
    const val CLOSED = 0x04

    // Close reasons
    const val TIMEOUT = 0x01
    const val INITIATOR_CLOSED = 0x02
    const val DESTINATION_CLOSED = 0x03

    // Resource strategies
    const val ACCEPT_NONE = 0x00
    const val ACCEPT_APP = 0x01
    const val ACCEPT_ALL = 0x02

    // Encryption modes
    const val MODE_AES128_CBC = 0x00
    const val MODE_AES256_CBC = 0x01
    const val MODE_AES256_GCM = 0x02
    val ENABLED_MODES = listOf(MODE_AES256_CBC)
    const val MODE_DEFAULT = MODE_AES256_CBC

    // MTU signalling
    const val LINK_MTU_SIZE = 3
    const val MTU_BYTEMASK = 0x1FFFFF
    const val MODE_BYTEMASK = 0xE0

    // Timeouts (in milliseconds)
    const val ESTABLISHMENT_TIMEOUT_PER_HOP = 6_000L  // 6 seconds

    // Traffic timeout
    const val TRAFFIC_TIMEOUT_MIN_MS = 5L
    const val TRAFFIC_TIMEOUT_FACTOR = 6

    // Keepalive
    const val KEEPALIVE_MAX_RTT = 1.75
    const val KEEPALIVE_TIMEOUT_FACTOR = 4
    const val STALE_GRACE = 5_000L  // 5 seconds
    const val KEEPALIVE_MAX = 360_000L  // 360 seconds
    const val KEEPALIVE_MIN = 5_000L   // 5 seconds
    const val KEEPALIVE = KEEPALIVE_MAX
    const val STALE_FACTOR = 2
    val STALE_TIME = STALE_FACTOR * KEEPALIVE

    const val WATCHDOG_MAX_SLEEP = 5_000L  // 5 seconds

    /**
     * Calculate the Link MDU (Maximum Data Unit).
     * This accounts for encryption overhead.
     */
    fun calculateMdu(mtu: Int = RnsConstants.MTU): Int {
        // MDU = floor((MTU - header_size - token_overhead) / block_size) * block_size - 1
        val headerMin = RnsConstants.HEADER_MIN_SIZE
        val tokenOverhead = RnsConstants.TOKEN_OVERHEAD
        val blockSize = RnsConstants.AES_BLOCK_SIZE
        return ((mtu - headerMin - tokenOverhead) / blockSize) * blockSize - 1
    }

    /**
     * Get the derived key length for a given mode.
     */
    fun derivedKeyLength(mode: Int): Int {
        return when (mode) {
            MODE_AES128_CBC -> 32
            MODE_AES256_CBC -> 64
            else -> throw IllegalArgumentException("Invalid link mode: $mode")
        }
    }

    /**
     * Get mode description string.
     */
    fun modeDescription(mode: Int): String {
        return when (mode) {
            MODE_AES128_CBC -> "AES_128_CBC"
            MODE_AES256_CBC -> "AES_256_CBC"
            MODE_AES256_GCM -> "AES_256_GCM"
            else -> "UNKNOWN_MODE_$mode"
        }
    }
}
