package network.reticulum.storage

import network.reticulum.common.ByteArrayKey

/**
 * Persistent storage for the packet hashlist used for deduplication.
 *
 * The hashlist uses a two-generation rotation scheme: a "current" set and a
 * "previous" set. When the current set exceeds a size threshold, it becomes
 * the previous set and a new empty current set is created.
 *
 * This store uses batch persistence (not write-through) because the hashlist
 * can contain up to 1M entries with nanosecond lookup requirements. Losing
 * entries on crash just causes harmless packet reprocessing.
 */
interface PacketHashStore {
    /** Save a full generation of hashes (0 = current, 1 = previous). */
    fun saveAll(hashes: Set<ByteArrayKey>, generation: Int)

    /** Load both generations. Returns (current, previous). */
    fun loadAll(): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>>

    /** Clear all stored hashes. */
    fun clear()
}
