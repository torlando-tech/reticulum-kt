package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.transport.PathEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory PathStore implementation for testing.
 * Mirrors what RoomPathStore does but without Room.
 */
class InMemoryPathStore : PathStore {
    val data = ConcurrentHashMap<ByteArrayKey, PathEntry>()

    override fun upsertPath(destHash: ByteArray, entry: PathEntry) {
        data[destHash.toKey()] = entry
    }

    override fun removePath(destHash: ByteArray) {
        data.remove(destHash.toKey())
    }

    override fun loadAllPaths(): Map<ByteArrayKey, PathEntry> = HashMap(data)

    override fun removeExpiredBefore(timestampMs: Long) {
        data.entries.removeIf { it.value.expires < timestampMs }
    }
}
