package network.reticulum.storage

import network.reticulum.common.ByteArrayKey

/**
 * Persistent storage for the packet hashlist used for deduplication.
 *
 * The hashlist uses a two-generation rotation scheme: a "current" set and a
 * "previous" set. When the current set exceeds half of the platform hashlist
 * budget, it becomes the previous set and a new empty current set is created,
 * matching `RNS/Transport.py:831-834@1.5.5`.
 *
 * This store uses batch persistence (not write-through) because the hashlist
 * can hold the whole platform budget (1M entries on a JVM) with nanosecond
 * lookup requirements. Losing entries on crash just causes harmless packet
 * reprocessing; loading trims each generation back to one window.
 */
interface PacketHashStore {
    /** Save a full generation of hashes (0 = current, 1 = previous). */
    fun saveAll(hashes: Set<ByteArrayKey>, generation: Int)

    /** Load both generations. Returns (current, previous). */
    fun loadAll(): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>>

    /** Clear all stored hashes. */
    fun clear()
}
