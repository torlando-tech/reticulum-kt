package network.reticulum.interfaces.ble

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a BLE peer discovered during scanning.
 *
 * Peers are initially identified by BLE MAC address. After the identity handshake
 * completes, the [identity] field is populated with the 16-byte Reticulum Transport
 * identity hash. All long-lived data structures should be keyed by identity (not
 * address) because Android rotates BLE MAC addresses approximately every 15 minutes.
 *
 * The [connectionScore] method computes a weighted priority score for peer selection,
 * matching the algorithm from the Python ble-reticulum reference implementation
 * (BLEInterface._score_peer). Higher scores indicate better connection candidates.
 *
 * Scoring weights:
 * - RSSI (signal strength): 60% -- strongest predictor of connection quality
 * - Connection history: 30% -- past reliability matters but doesn't override signal
 * - Recency: 10% -- recently seen peers are more likely to be available
 */
data class DiscoveredPeer(
    /** BLE MAC address of the peer. */
    val address: String,

    /** Signal strength in dBm. Typically -30 (excellent) to -100 (poor). */
    val rssi: Int,

    /** Timestamp when this peer was last seen (System.currentTimeMillis). */
    val lastSeen: Long = System.currentTimeMillis(),

    /**
     * Peer's 16-byte Reticulum Transport identity hash.
     * Null until the identity handshake completes.
     */
    val identity: ByteArray? = null,

    /** Number of connection attempts made to this peer. */
    val connectionAttempts: Int = 0,

    /** Number of successful connections to this peer. */
    val connectionSuccesses: Int = 0
) {

    /**
     * Compute a weighted connection priority score in the range [0.0, 1.0].
     *
     * The score combines three components:
     * 1. **RSSI** (weight 0.6): Signal strength normalized from [-100, -30] dBm to [0.0, 1.0].
     *    Values outside this range are clamped. Strong signal is the best predictor of
     *    connection success and throughput in BLE networks.
     *
     * 2. **History** (weight 0.3): Connection success rate = successes / max(attempts, 1).
     *    Peers with no history get a 0.5 "benefit of the doubt" score, matching the Python
     *    reference which gives new peers 25/50 points on the history component.
     *
     * 3. **Recency** (weight 0.1): Exponential decay with a 60-second half-life.
     *    Recently seen peers score higher because they're more likely to still be in range.
     *
     * @return Score in [0.0, 1.0] where higher is better
     */
    fun connectionScore(): Double {
        // RSSI component: normalize from [-100, -30] to [0.0, 1.0], then weight
        val rssiClamped = max(-100, min(-30, rssi))
        val rssiNormalized = (rssiClamped + 100).toDouble() / 70.0
        val rssiComponent = rssiNormalized * WEIGHT_RSSI

        // History component: success rate or 0.5 for new peers
        val historyRate = if (connectionAttempts > 0) {
            connectionSuccesses.toDouble() / max(connectionAttempts, 1).toDouble()
        } else {
            0.5 // Benefit of the doubt for unknown peers
        }
        val historyComponent = historyRate * WEIGHT_HISTORY

        // Recency component: exponential decay with 60-second half-life
        val ageMs = System.currentTimeMillis() - lastSeen
        val ageSeconds = max(0.0, ageMs.toDouble() / 1000.0)
        val recencyDecay = Math.pow(0.5, ageSeconds / RECENCY_HALF_LIFE_SECONDS)
        val recencyComponent = recencyDecay * WEIGHT_RECENCY

        return min(1.0, rssiComponent + historyComponent + recencyComponent)
    }

    // equals/hashCode by address only -- peers are identified by address before
    // identity is known, and the same peer may appear with different RSSI values
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredPeer) return false
        return address == other.address
    }

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String {
        val identityStr = identity?.let { it.take(4).joinToString("") { b -> "%02x".format(b) } + "..." } ?: "unknown"
        return "DiscoveredPeer(addr=${address.takeLast(8)}, rssi=$rssi, identity=$identityStr, " +
            "attempts=$connectionAttempts, successes=$connectionSuccesses)"
    }

    companion object {
        /** Weight for RSSI component in scoring (60%). */
        const val WEIGHT_RSSI = 0.6

        /** Weight for connection history component in scoring (30%). */
        const val WEIGHT_HISTORY = 0.3

        /** Weight for recency component in scoring (10%). */
        const val WEIGHT_RECENCY = 0.1

        /** Half-life in seconds for the recency exponential decay. */
        const val RECENCY_HALF_LIFE_SECONDS = 60.0
    }
}
