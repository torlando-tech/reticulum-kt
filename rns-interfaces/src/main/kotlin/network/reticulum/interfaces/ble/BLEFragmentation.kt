package network.reticulum.interfaces.ble

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
 */
class BLEFragmenter(mtu: Int = 185) {

    companion object {
        const val HEADER_SIZE = 5
        const val TYPE_START: Byte = 0x01
        const val TYPE_CONTINUE: Byte = 0x02
        const val TYPE_END: Byte = 0x03
    }

    val maxPayload: Int = TODO("Not implemented yet")

    fun fragment(packet: ByteArray): List<ByteArray> {
        TODO("Not implemented yet")
    }

    fun fragmentOverhead(packetSize: Int): Triple<Int, Int, Double> {
        TODO("Not implemented yet")
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
 * Reassembles fragmented BLE packets into complete Reticulum packets.
 */
class BLEReassembler(private val timeoutMs: Long = 30_000L) {

    val statistics: ReassemblyStatistics get() = TODO("Not implemented yet")

    fun receiveFragment(fragment: ByteArray, senderId: String = "default"): ByteArray? {
        TODO("Not implemented yet")
    }

    fun cleanupStale(): Int {
        TODO("Not implemented yet")
    }
}
