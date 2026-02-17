package network.reticulum.discovery

/**
 * Data class representing a discovered interface from the network.
 *
 * Mirrors the Python `info` dict built in InterfaceAnnounceHandler.received_announce().
 * The [discoveryHash] uniquely identifies an interface across announces:
 * SHA256((transportId + name).toByteArray()).
 */
data class DiscoveredInterface(
    val type: String,
    val transport: Boolean,
    val name: String,
    val received: Long,         // epoch seconds
    val stampValue: Int,
    val transportId: String,    // hex
    val networkId: String,      // hex (announced identity hash)
    val hops: Int,
    val latitude: Double?,
    val longitude: Double?,
    val height: Double?,
    val reachableOn: String?,
    val port: Int?,
    val frequency: Long?,
    val bandwidth: Long?,
    val spreadingFactor: Int?,
    val codingRate: Int?,
    val modulation: String?,
    val channel: Int?,
    val ifacNetname: String?,
    val ifacNetkey: String?,
    val discoveryHash: ByteArray,
    // Persistence metadata — mutated by InterfaceDiscovery
    var discovered: Long = 0,
    var lastHeard: Long = 0,
    var heardCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredInterface) return false
        return discoveryHash.contentEquals(other.discoveryHash)
    }

    override fun hashCode(): Int = discoveryHash.contentHashCode()

    override fun toString(): String = "DiscoveredInterface[$type $name]"
}
