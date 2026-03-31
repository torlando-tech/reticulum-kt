package network.reticulum.storage

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.identity.Identity.IdentityData
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory IdentityStore implementation for testing.
 */
class InMemoryIdentityStore : IdentityStore {
    val destinations = ConcurrentHashMap<ByteArrayKey, IdentityData>()
    val ratchets = ConcurrentHashMap<ByteArrayKey, Pair<ByteArray, Long>>()

    override fun upsertKnownDestination(destHash: ByteArray, data: IdentityData) {
        destinations[destHash.toKey()] = data
    }

    override fun getKnownDestination(destHash: ByteArray): IdentityData? {
        return destinations[destHash.toKey()]
    }

    override fun loadAllKnownDestinations(): Map<ByteArrayKey, IdentityData> = HashMap(destinations)

    override fun knownDestinationCount(): Int = destinations.size

    override fun upsertRatchet(destHash: ByteArray, ratchet: ByteArray, timestampMs: Long) {
        ratchets[destHash.toKey()] = ratchet.copyOf() to timestampMs
    }

    override fun getRatchet(destHash: ByteArray): Pair<ByteArray, Long>? {
        return ratchets[destHash.toKey()]
    }

    override fun removeExpiredRatchets(maxAgeMs: Long) {
        val threshold = System.currentTimeMillis() - maxAgeMs
        ratchets.entries.removeIf { it.value.second < threshold }
    }
}
