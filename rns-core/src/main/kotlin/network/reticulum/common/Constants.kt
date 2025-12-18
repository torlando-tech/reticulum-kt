package network.reticulum.common

/**
 * Core constants for the Reticulum Network Stack.
 * These values must match the Python reference implementation exactly.
 */
object RnsConstants {
    // Wire protocol
    const val MTU = 500                              // Maximum Transmission Unit
    const val HEADER_MIN_SIZE = 19                   // 2 + 1 + 16 (flags + hops + dest_hash)
    const val HEADER_MAX_SIZE = 35                   // 2 + 1 + 16 + 16 (with transport_id)
    const val MDU = MTU - HEADER_MAX_SIZE - 1        // Maximum Data Unit (464)
    const val ENCRYPTED_MDU = 383                    // Max encrypted payload

    // Hash lengths (in bits)
    const val TRUNCATED_HASH_LENGTH = 128            // 16 bytes
    const val FULL_HASH_LENGTH = 256                 // 32 bytes
    const val NAME_HASH_LENGTH = 80                  // 10 bytes

    // Hash lengths (in bytes)
    const val TRUNCATED_HASH_BYTES = TRUNCATED_HASH_LENGTH / 8  // 16
    const val FULL_HASH_BYTES = FULL_HASH_LENGTH / 8            // 32
    const val NAME_HASH_BYTES = NAME_HASH_LENGTH / 8            // 10

    // Key sizes (in bytes)
    const val KEY_SIZE = 32                          // Single key (X25519 or Ed25519)
    const val FULL_KEY_SIZE = 64                     // Combined public key (X25519 + Ed25519)
    const val SIGNATURE_SIZE = 64                    // Ed25519 signature

    // Cryptographic overhead
    const val TOKEN_OVERHEAD = 48                    // 16 IV + 32 HMAC
    const val DERIVED_KEY_LENGTH = 64                // HKDF output for Token keys

    // AES
    const val AES_BLOCK_SIZE = 16                    // AES block size in bytes
    const val AES_IV_SIZE = 16                       // AES IV size in bytes

    // Transport
    const val PATHFINDER_M = 128                     // Maximum hops
    const val PATH_REQUEST_TIMEOUT = 15_000L         // 15 seconds in ms
    const val DESTINATION_TIMEOUT = 604_800_000L     // 7 days in ms

    // Link
    const val LINK_ESTABLISHMENT_TIMEOUT_PER_HOP = 6_000L  // 6 seconds per hop
    const val LINK_KEEPALIVE = 360_000L              // 360 seconds in ms
    const val LINK_STALE_TIME = 288_000L             // 288 seconds in ms
    const val LINK_MDU = 325                         // Approximate link MDU

    // Ratchet
    const val RATCHET_SIZE = 32                      // 256 bits
    const val RATCHET_EXPIRY = 2_592_000_000L        // 30 days in ms
    const val RATCHET_ROTATION_INTERVAL = 1_800_000L // 30 minutes in ms
    const val MAX_RATCHETS = 512                     // Maximum stored ratchets
}
