package network.reticulum.storage

import network.reticulum.discovery.DiscoveredInterface

/**
 * Persistent storage for discovered network interfaces.
 *
 * Discovered interfaces are learned from announces and persisted so they
 * can be reconnected on app restart without waiting for a new announce.
 */
interface DiscoveryStore {
    /** Insert or update a discovered interface. */
    fun upsertDiscovered(info: DiscoveredInterface)

    /** Retrieve a discovered interface by its discovery hash, or null. */
    fun getDiscovered(discoveryHash: ByteArray): DiscoveredInterface?

    /** Load all discovered interfaces. */
    fun loadAllDiscovered(): List<DiscoveredInterface>

    /** Remove a discovered interface by its discovery hash. */
    fun removeDiscovered(discoveryHash: ByteArray)

    /** Remove all entries whose lastHeard is older than the given threshold (epoch seconds). */
    fun removeOlderThan(thresholdSeconds: Long)
}
