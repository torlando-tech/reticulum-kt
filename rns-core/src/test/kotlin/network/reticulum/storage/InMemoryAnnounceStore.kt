package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory AnnounceStore implementation for testing.
 */
class InMemoryAnnounceStore : AnnounceStore {
    val data = ConcurrentHashMap<ByteArrayKey, Pair<ByteArray, String>>()

    override fun cacheAnnounce(packetHash: ByteArray, raw: ByteArray, interfaceName: String) {
        data[packetHash.toKey()] = raw.copyOf() to interfaceName
    }

    override fun getAnnounce(packetHash: ByteArray): Pair<ByteArray, String?>? {
        return data[packetHash.toKey()]
    }

    override fun removeAnnounce(packetHash: ByteArray) {
        data.remove(packetHash.toKey())
    }

    override fun removeAllExcept(activeHashes: Set<ByteArrayKey>) {
        data.keys.removeIf { it !in activeHashes }
    }
}
