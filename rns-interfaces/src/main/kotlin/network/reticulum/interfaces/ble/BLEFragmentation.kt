package network.reticulum.interfaces.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE Fragmentation Protocol
 *
 * Handles fragmentation and reassembly of Reticulum packets for BLE transport.
 *
 * BLE has MTU limitations (typically 20-512 bytes) while Reticulum packets
 * can be up to 500 bytes. This module splits packets into fragments with
 * headers for reassembly.
 *
 * Fragment Header Format (5 bytes):
 *     [Type: 1 byte][Sequence: 2 bytes][Total: 2 bytes][Data: variable]
 *
 * Fragment Types:
 *     0x01 = START    - First fragment
 *     0x02 = CONTINUE - Middle fragment
 *     0x03 = END      - Last fragment
 *
 * Wire format is byte-identical to Python BLEFragmentation.py
 * (struct.pack("!BHH", type, seq, total) = big-endian).
 */

/**
 * Fragments Reticulum packets into BLE-sized chunks.
 *
 * Each fragment includes a 5-byte header with type, sequence number,
 * and total fragment count to enable reassembly on the receiving end.
 *
 * @param mtu Maximum transmission unit for BLE (default 185 for BLE 4.2).
 *            Enforces minimum of HEADER_SIZE + 1 = 6 bytes.
 * @throws IllegalArgumentException if MTU is too small for fragmentation
 */
class BLEFragmenter(mtu: Int = 185) {

    companion object {
        /** Fragment header size: 1 byte type + 2 bytes sequence + 2 bytes total */
        const val HEADER_SIZE = BLEConstants.FRAGMENT_HEADER_SIZE

        /** First fragment */
        const val TYPE_START: Byte = BLEConstants.FRAGMENT_TYPE_START

        /** Middle fragment(s) */
        const val TYPE_CONTINUE: Byte = BLEConstants.FRAGMENT_TYPE_CONTINUE

        /** Last fragment */
        const val TYPE_END: Byte = BLEConstants.FRAGMENT_TYPE_END

        /** Maximum number of fragments (16-bit unsigned) */
        const val MAX_FRAGMENTS = 65535
    }

    /** Data payload per fragment = MTU - header */
    val maxPayload: Int

    init {
        require(mtu >= HEADER_SIZE + 1) {
            "MTU $mtu too small for fragmentation (min ${HEADER_SIZE + 1})"
        }
        maxPayload = mtu - HEADER_SIZE
    }

    /**
     * Split a Reticulum packet into BLE fragments.
     *
     * Each fragment: [type:1][sequence:2][total:2][payload:variable] -- all big-endian.
     *
     * @param packet The full Reticulum packet to fragment
     * @return List of fragment byte arrays, each with 5-byte header + payload
     * @throws IllegalArgumentException if packet is empty or too large
     */
    fun fragment(packet: ByteArray): List<ByteArray> {
        require(packet.isNotEmpty()) { "Cannot fragment empty packet" }

        val numFragments = (packet.size + maxPayload - 1) / maxPayload

        require(numFragments <= MAX_FRAGMENTS) {
            "Packet requires $numFragments fragments, exceeds max ($MAX_FRAGMENTS). " +
                "Packet size too large for BLE MTU ${maxPayload + HEADER_SIZE}. " +
                "Maximum supported: ${MAX_FRAGMENTS.toLong() * maxPayload} bytes"
        }

        val fragments = ArrayList<ByteArray>(numFragments)

        for (i in 0 until numFragments) {
            // Determine fragment type
            val fragType: Byte = when {
                numFragments == 1 -> TYPE_START        // single fragment
                i == 0 -> TYPE_START                   // first of many
                i == numFragments - 1 -> TYPE_END      // last
                else -> TYPE_CONTINUE                  // middle
            }

            // Extract data for this fragment
            val startIdx = i * maxPayload
            val endIdx = minOf(startIdx + maxPayload, packet.size)
            val dataLen = endIdx - startIdx

            // Build fragment: header + data
            val fragment = ByteArray(HEADER_SIZE + dataLen)
            val buf = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
            buf.put(fragType)
            buf.putShort(i.toShort())           // sequence number (big-endian)
            buf.putShort(numFragments.toShort()) // total fragments (big-endian)

            // Copy payload data
            System.arraycopy(packet, startIdx, fragment, HEADER_SIZE, dataLen)

            fragments.add(fragment)
        }

        return fragments
    }

    /**
     * Calculate fragmentation overhead for a given packet size.
     *
     * @param packetSize Size of packet in bytes
     * @return Triple of (numFragments, overheadBytes, overheadPercent)
     */
    fun fragmentOverhead(packetSize: Int): Triple<Int, Int, Double> {
        val numFragments = (packetSize + maxPayload - 1) / maxPayload
        val overheadBytes = numFragments * HEADER_SIZE
        val overheadPct = if (packetSize > 0) {
            (overheadBytes.toDouble() / packetSize.toDouble()) * 100.0
        } else {
            0.0
        }
        return Triple(numFragments, overheadBytes, overheadPct)
    }
}

/**
 * Reassembly statistics.
 */
data class ReassemblyStatistics(
    val packetsReassembled: Int = 0,
    val packetsTimedOut: Int = 0,
    val fragmentsReceived: Int = 0,
    val pendingPackets: Int = 0
)

/**
 * Internal buffer for tracking fragments of an in-progress reassembly.
 */
private class ReassemblyBuffer(
    val total: Int,
    val senderId: String,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    /** Received fragments indexed by sequence number */
    val fragments: MutableMap<Int, ByteArray> = mutableMapOf()
}

/**
 * Reassembles fragmented BLE packets into complete Reticulum packets.
 *
 * Maintains reassembly buffers per sender and handles timeouts for
 * incomplete packets. Thread-safe via synchronized access.
 *
 * @param timeoutMs Milliseconds to wait for complete packet before discarding (default 30000)
 */
class BLEReassembler(private val timeoutMs: Long = 30_000L) {

    /** Reassembly buffers keyed by sender ID */
    private val buffers: MutableMap<String, ReassemblyBuffer> = mutableMapOf()

    /** Statistics counters */
    private var _packetsReassembled: Int = 0
    private var _packetsTimedOut: Int = 0
    private var _fragmentsReceived: Int = 0

    /** Current reassembly statistics (snapshot). */
    val statistics: ReassemblyStatistics
        @Synchronized get() = ReassemblyStatistics(
            packetsReassembled = _packetsReassembled,
            packetsTimedOut = _packetsTimedOut,
            fragmentsReceived = _fragmentsReceived,
            pendingPackets = buffers.size
        )

    /**
     * Process an incoming fragment and reassemble if complete.
     *
     * @param fragment One BLE fragment (5-byte header + payload data)
     * @param senderId Identifier of the sending device (default "default")
     * @return Complete packet when all fragments received, null if still waiting
     * @throws IllegalArgumentException if fragment is malformed
     */
    @Synchronized
    fun receiveFragment(fragment: ByteArray, senderId: String = "default"): ByteArray? {
        require(fragment.size >= BLEFragmenter.HEADER_SIZE) {
            "Fragment too short: ${fragment.size} bytes (min ${BLEFragmenter.HEADER_SIZE})"
        }

        _fragmentsReceived++

        // Parse header (big-endian, matching Python struct.pack("!BHH",...))
        val buf = ByteBuffer.wrap(fragment, 0, BLEFragmenter.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        val fragType = buf.get()
        val sequence = buf.short.toInt() and 0xFFFF  // unsigned
        val total = buf.short.toInt() and 0xFFFF     // unsigned

        // Validate fragment type
        require(
            fragType == BLEFragmenter.TYPE_START ||
            fragType == BLEFragmenter.TYPE_CONTINUE ||
            fragType == BLEFragmenter.TYPE_END
        ) {
            "Invalid fragment type: 0x${String.format("%02x", fragType)}"
        }

        // Validate sequence and total
        require(total > 0) { "Total fragments cannot be zero" }
        require(sequence < total) { "Invalid sequence $sequence >= total $total" }

        // Extract payload data
        val data = fragment.sliceArray(BLEFragmenter.HEADER_SIZE until fragment.size)

        // Find or create buffer for this sender
        val buffer = buffers[senderId]

        if (buffer != null) {
            // Validate total consistency
            if (buffer.total != total) {
                buffers.remove(senderId)
                throw IllegalArgumentException(
                    "Fragment total mismatch for $senderId: " +
                        "expected ${buffer.total}, got $total"
                )
            }

            // Check for duplicate fragments
            val existing = buffer.fragments[sequence]
            if (existing != null) {
                if (existing.contentEquals(data)) {
                    // Benign duplicate -- ignore
                    return null
                } else {
                    // Data mismatch -- corruption
                    buffers.remove(senderId)
                    throw IllegalArgumentException(
                        "Fragment $sequence from $senderId received twice with " +
                            "different data! Possible corruption."
                    )
                }
            }

            // Store fragment
            buffer.fragments[sequence] = data
        } else {
            // Create new buffer
            val newBuffer = ReassemblyBuffer(
                total = total,
                senderId = senderId
            )
            newBuffer.fragments[sequence] = data
            buffers[senderId] = newBuffer
        }

        // Check if reassembly is complete
        val currentBuffer = buffers[senderId]!!
        if (currentBuffer.fragments.size == total) {
            // Verify all sequence numbers present
            for (i in 0 until total) {
                if (i !in currentBuffer.fragments) {
                    return null // still missing fragments
                }
            }

            // Reassemble: concatenate fragments in sequence order
            val totalSize = (0 until total).sumOf { currentBuffer.fragments[it]!!.size }
            val packet = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until total) {
                val fragData = currentBuffer.fragments[i]!!
                System.arraycopy(fragData, 0, packet, offset, fragData.size)
                offset += fragData.size
            }

            // Clean up
            buffers.remove(senderId)
            _packetsReassembled++

            return packet
        }

        return null
    }

    /**
     * Remove packets that timed out.
     *
     * @return Number of buffers removed
     */
    @Synchronized
    fun cleanupStale(): Int {
        val now = System.currentTimeMillis()
        val staleKeys = buffers.entries
            .filter { (_, buffer) -> now - buffer.startTimeMs > timeoutMs }
            .map { it.key }

        for (key in staleKeys) {
            buffers.remove(key)
            _packetsTimedOut++
        }

        return staleKeys.size
    }
}
