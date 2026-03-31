package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.identity.Identity.IdentityData

/**
 * Persistent storage for known destination identities and per-peer ratchets.
 *
 * Implementations must be thread-safe. Write methods may be called from
 * announce processing threads; read methods are called at startup and
 * during identity recall.
 */
interface IdentityStore {

    // ===== Known Destinations =====

    /** Insert or update a known destination. */
    fun upsertKnownDestination(destHash: ByteArray, data: IdentityData)

    /** Retrieve a known destination by hash, or null if not found. */
    fun getKnownDestination(destHash: ByteArray): IdentityData?

    /** Load all known destinations (called once at startup). */
    fun loadAllKnownDestinations(): Map<ByteArrayKey, IdentityData>

    /** Return the number of stored known destinations. */
    fun knownDestinationCount(): Int

    // ===== Per-Peer Ratchets =====

    /** Store a ratchet received from a remote peer's announce. */
    fun upsertRatchet(destHash: ByteArray, ratchet: ByteArray, timestampMs: Long)

    /** Retrieve the most recent ratchet for a destination, or null. Returns (ratchet, timestampMs). */
    fun getRatchet(destHash: ByteArray): Pair<ByteArray, Long>?

    /** Remove all ratchets older than the given expiry threshold. */
    fun removeExpiredRatchets(maxAgeMs: Long)
}
