package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.transport.PathEntry

/**
 * Persistent storage for the path table.
 *
 * Implementations must be thread-safe. Write methods may be called from the
 * Transport job loop thread; read methods are called at startup.
 */
interface PathStore {
    /** Insert or update a path entry for the given destination hash. */
    fun upsertPath(destHash: ByteArray, entry: PathEntry)

    /** Remove the path entry for the given destination hash. */
    fun removePath(destHash: ByteArray)

    /** Load all stored path entries (called once at startup). */
    fun loadAllPaths(): Map<ByteArrayKey, PathEntry>

    /** Remove all entries with expires before the given timestamp. */
    fun removeExpiredBefore(timestampMs: Long)
}
