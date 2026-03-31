package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory PacketHashStore implementation for testing.
 */
class InMemoryPacketHashStore : PacketHashStore {
    val generations = ConcurrentHashMap<Int, MutableSet<ByteArrayKey>>()

    override fun saveAll(hashes: Set<ByteArrayKey>, generation: Int) {
        generations[generation] = hashes.toMutableSet()
    }

    override fun loadAll(): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>> {
        val current = generations[0] ?: emptySet()
        val prev = generations[1] ?: emptySet()
        return current to prev
    }

    override fun clear() {
        generations.clear()
    }
}
