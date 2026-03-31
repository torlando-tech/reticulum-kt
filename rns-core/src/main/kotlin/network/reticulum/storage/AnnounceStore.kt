package network.reticulum.storage

import network.reticulum.common.ByteArrayKey

/**
 * Persistent storage for cached announce packets.
 *
 * Announce packets are cached so they can be replayed when tunnels reconnect
 * or when path responses need to reference the original announce data.
 */
interface AnnounceStore {
    /** Cache an announce packet's raw data and receiving interface name. */
    fun cacheAnnounce(packetHash: ByteArray, raw: ByteArray, interfaceName: String)

    /** Retrieve a cached announce by packet hash. Returns (raw, interfaceName) or null. */
    fun getAnnounce(packetHash: ByteArray): Pair<ByteArray, String?>?

    /** Remove a single cached announce. */
    fun removeAnnounce(packetHash: ByteArray)

    /** Remove all cached announces except those in the active set. */
    fun removeAllExcept(activeHashes: Set<ByteArrayKey>)
}
