package network.reticulum.interfaces.ble

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Comprehensive tests for BLE fragmentation and reassembly.
 *
 * Ported from Python test_fragmentation.py with additional wire-format
 * verification tests to ensure byte-identical output with the Python
 * BLEFragmentation.py reference implementation.
 */
class BLEFragmentationTest {

    // ========================================================================
    // BLEFragmenter Tests
    // ========================================================================

    @Test
    fun `small packet produces single fragment with header`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = "Hello, Reticulum!".toByteArray()

        val fragments = fragmenter.fragment(packet)

        assertEquals(1, fragments.size)
        // Even small packets get headers for uniform protocol handling
        assertEquals(packet.size + 5, fragments[0].size)
    }

    @Test
    fun `packet exactly payload size produces single fragment`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        // maxPayload = 185 - 5 = 180
        val packet = ByteArray(180) { 0x41 }

        val fragments = fragmenter.fragment(packet)

        assertEquals(1, fragments.size)
        assertEquals(185, fragments[0].size) // exactly MTU
    }

    @Test
    fun `packet one byte over payload size produces two fragments`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        // maxPayload = 180, so 181 bytes needs 2 fragments
        val packet = ByteArray(181) { 0x41 }

        val fragments = fragmenter.fragment(packet)

        assertEquals(2, fragments.size)
        assertEquals(185, fragments[0].size) // full MTU
        assertEquals(6, fragments[1].size)   // 5 header + 1 data
    }

    @Test
    fun `exact MTU sized packet needs two fragments due to header`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = ByteArray(185) { 0x58 } // 'X'

        val fragments = fragmenter.fragment(packet)

        // With 5-byte header, 185-byte packet needs 2 fragments
        // Fragment 0: 5 header + 180 data = 185
        // Fragment 1: 5 header + 5 data = 10
        assertEquals(2, fragments.size)
    }

    @Test
    fun `large packet fragmentation - 500 bytes typical reticulum`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = ByteArray(500) { 0x41 } // 'A'

        val fragments = fragmenter.fragment(packet)

        // 500 / 180 = 2.77 -> ceil = 3 fragments
        assertEquals(3, fragments.size)

        // All but last fragment should be full MTU size
        for (frag in fragments.dropLast(1)) {
            assertEquals(185, frag.size)
        }
        // Last fragment may be smaller
        assertTrue(fragments.last().size <= 185)
    }

    @Test
    fun `fragment headers have correct types`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val packet = ByteArray(300) { 0x42 } // 'B'

        val fragments = fragmenter.fragment(packet)

        // First fragment is START
        assertEquals(BLEFragmenter.TYPE_START, fragments[0][0])

        // Middle fragments are CONTINUE
        if (fragments.size > 2) {
            for (frag in fragments.subList(1, fragments.size - 1)) {
                assertEquals(BLEFragmenter.TYPE_CONTINUE, frag[0])
            }
        }

        // Last fragment is END
        assertEquals(BLEFragmenter.TYPE_END, fragments.last()[0])
    }

    @Test
    fun `single fragment uses START type`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = "Short".toByteArray()

        val fragments = fragmenter.fragment(packet)

        assertEquals(1, fragments.size)
        assertEquals(BLEFragmenter.TYPE_START, fragments[0][0])
    }

    @Test
    fun `sequence numbers are sequential and big-endian`() {
        val fragmenter = BLEFragmenter(mtu = 50)
        val packet = ByteArray(200) { 0x43 } // 'C'

        val fragments = fragmenter.fragment(packet)

        for ((i, frag) in fragments.withIndex()) {
            // Extract sequence number (bytes 1-2, big endian)
            val seq = ((frag[1].toInt() and 0xFF) shl 8) or (frag[2].toInt() and 0xFF)
            assertEquals(i, seq, "Fragment $i has wrong sequence number")
        }
    }

    @Test
    fun `total count is correct in all fragments`() {
        val fragmenter = BLEFragmenter(mtu = 50)
        val packet = ByteArray(200) { 0x44 } // 'D'

        val fragments = fragmenter.fragment(packet)
        val expectedTotal = fragments.size

        for (frag in fragments) {
            // Extract total count (bytes 3-4, big endian)
            val total = ((frag[3].toInt() and 0xFF) shl 8) or (frag[4].toInt() and 0xFF)
            assertEquals(expectedTotal, total)
        }
    }

    @Test
    fun `single fragment has seq=0 total=1`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = "Hello".toByteArray()

        val fragments = fragmenter.fragment(packet)
        assertEquals(1, fragments.size)

        val frag = fragments[0]
        // seq = 0
        val seq = ((frag[1].toInt() and 0xFF) shl 8) or (frag[2].toInt() and 0xFF)
        assertEquals(0, seq)
        // total = 1
        val total = ((frag[3].toInt() and 0xFF) shl 8) or (frag[4].toInt() and 0xFF)
        assertEquals(1, total)
    }

    @Test
    fun `overhead calculation is accurate`() {
        val fragmenter = BLEFragmenter(mtu = 185)

        // Small packet (1 fragment)
        val (numFrags1, overhead1, pct1) = fragmenter.fragmentOverhead(100)
        assertEquals(1, numFrags1)
        assertEquals(5, overhead1) // 1 fragment * 5 byte header
        assertEquals((5.0 / 100.0) * 100.0, pct1, 0.001)

        // Large packet (3 fragments)
        val (numFrags2, overhead2, pct2) = fragmenter.fragmentOverhead(500)
        assertEquals(3, numFrags2)
        assertEquals(15, overhead2) // 3 fragments * 5 byte header
        assertEquals((15.0 / 500.0) * 100.0, pct2, 0.001)
    }

    @Test
    fun `empty packet throws IllegalArgumentException`() {
        val fragmenter = BLEFragmenter(mtu = 185)

        assertThrows<IllegalArgumentException> {
            fragmenter.fragment(ByteArray(0))
        }
    }

    @Test
    fun `MTU too small throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            BLEFragmenter(mtu = 5) // HEADER_SIZE + 0 = not enough
        }
    }

    @Test
    fun `minimum valid MTU is 6`() {
        val fragmenter = BLEFragmenter(mtu = 6) // HEADER_SIZE + 1 = 6
        assertEquals(1, fragmenter.maxPayload)

        val fragments = fragmenter.fragment(byteArrayOf(0x42))
        assertEquals(1, fragments.size)
        assertEquals(6, fragments[0].size)
    }

    @Test
    fun `packet requiring over 65535 fragments throws`() {
        // With MTU=6, payload=1, so 65536 bytes would need 65536 fragments
        val fragmenter = BLEFragmenter(mtu = 6)

        assertThrows<IllegalArgumentException> {
            fragmenter.fragment(ByteArray(65536))
        }
    }

    // ========================================================================
    // Wire Format Verification (byte-identical to Python struct.pack("!BHH",...))
    // ========================================================================

    @Test
    fun `wire format - single fragment START seq=0 total=1`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = byteArrayOf(0xAA.toByte())

        val fragments = fragmenter.fragment(packet)
        val header = fragments[0].sliceArray(0 until 5)

        // Python: struct.pack("!BHH", 0x01, 0, 1)
        // Expected: 01 00 00 00 01
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x01),
            header,
            "Single fragment header mismatch"
        )
    }

    @Test
    fun `wire format - first of 3 START seq=0 total=3`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        // Need exactly 3 fragments: 3 * 180 = 540 max, so 361-540 range
        val packet = ByteArray(500) { 0xBB.toByte() }

        val fragments = fragmenter.fragment(packet)
        assertEquals(3, fragments.size)

        val header = fragments[0].sliceArray(0 until 5)
        // Python: struct.pack("!BHH", 0x01, 0, 3)
        // Expected: 01 00 00 00 03
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x03),
            header,
            "First-of-3 header mismatch"
        )
    }

    @Test
    fun `wire format - middle of 3 CONTINUE seq=1 total=3`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = ByteArray(500) { 0xBB.toByte() }

        val fragments = fragmenter.fragment(packet)
        assertEquals(3, fragments.size)

        val header = fragments[1].sliceArray(0 until 5)
        // Python: struct.pack("!BHH", 0x02, 1, 3)
        // Expected: 02 00 01 00 03
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x01, 0x00, 0x03),
            header,
            "Middle-of-3 header mismatch"
        )
    }

    @Test
    fun `wire format - last of 3 END seq=2 total=3`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = ByteArray(500) { 0xBB.toByte() }

        val fragments = fragmenter.fragment(packet)
        assertEquals(3, fragments.size)

        val header = fragments[2].sliceArray(0 until 5)
        // Python: struct.pack("!BHH", 0x03, 2, 3)
        // Expected: 03 00 02 00 03
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x02, 0x00, 0x03),
            header,
            "Last-of-3 header mismatch"
        )
    }

    @Test
    fun `wire format - large sequence and total values`() {
        // Verify big-endian encoding for larger values
        // seq=256 (0x0100), total=1000 (0x03E8)
        // We can't easily create a fragmenter that produces seq=256,
        // so we verify the ByteBuffer encoding directly
        val buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        buf.put(BLEFragmenter.TYPE_START)
        buf.putShort(256.toShort())
        buf.putShort(1000.toShort())

        val expected = byteArrayOf(
            0x01,
            0x01, 0x00,       // 256 big-endian
            0x03, 0xE8.toByte() // 1000 big-endian
        )
        assertArrayEquals(expected, buf.array())
    }

    @Test
    fun `wire format - payload data preserved after header`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val packet = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        val fragments = fragmenter.fragment(packet)
        assertEquals(1, fragments.size)

        // Header: 5 bytes, then data
        val payload = fragments[0].sliceArray(5 until fragments[0].size)
        assertArrayEquals(packet, payload)
    }

    // ========================================================================
    // BLEReassembler Tests
    // ========================================================================

    @Test
    fun `reassemble single fragment packet`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = "Short message".toByteArray()
        val fragments = fragmenter.fragment(original)
        assertEquals(1, fragments.size)

        val result = reassembler.receiveFragment(fragments[0], "device1")
        assertArrayEquals(original, result)
    }

    @Test
    fun `reassemble multi-fragment packet in order`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val reassembler = BLEReassembler()

        val original = ByteArray(300) { 0x45 } // 'E'
        val fragments = fragmenter.fragment(original)
        assertTrue(fragments.size > 1)

        // Send all but last
        for (frag in fragments.dropLast(1)) {
            val result = reassembler.receiveFragment(frag, "device1")
            assertNull(result, "Should not be complete yet")
        }

        // Send last fragment
        val result = reassembler.receiveFragment(fragments.last(), "device1")
        assertArrayEquals(original, result)
    }

    @Test
    fun `reassemble out-of-order fragments`() {
        val fragmenter = BLEFragmenter(mtu = 50)
        val reassembler = BLEReassembler()

        // 150 bytes with payload=45 -> ceil(150/45)=4 fragments
        val original = ByteArray(150) { 0x46 } // 'F'
        val fragments = fragmenter.fragment(original)
        assertEquals(4, fragments.size, "Expected 4 fragments")

        // Send in scrambled order: 0, 2, 1, 3
        val order = intArrayOf(0, 2, 1, 3)
        for (idx in order.dropLast(1)) {
            val result = reassembler.receiveFragment(fragments[idx], "device1")
            assertNull(result, "Should not be complete yet")
        }

        val result = reassembler.receiveFragment(fragments[order.last()], "device1")
        assertArrayEquals(original, result, "Out-of-order reassembly failed")
    }

    @Test
    fun `reassemble with multiple concurrent senders`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val reassembler = BLEReassembler()

        val packetA = ByteArray(300) { 0x41 } // 'A'
        val packetB = ByteArray(300) { 0x42 } // 'B'

        val fragmentsA = fragmenter.fragment(packetA)
        val fragmentsB = fragmenter.fragment(packetB)

        // Interleave fragments from two senders
        val maxFrags = maxOf(fragmentsA.size, fragmentsB.size)
        var resultA: ByteArray? = null
        var resultB: ByteArray? = null

        for (i in 0 until maxFrags) {
            if (i < fragmentsA.size) {
                val r = reassembler.receiveFragment(fragmentsA[i], "device1")
                if (r != null) resultA = r
            }
            if (i < fragmentsB.size) {
                val r = reassembler.receiveFragment(fragmentsB[i], "device2")
                if (r != null) resultB = r
            }
        }

        assertArrayEquals(packetA, resultA, "Packet A reassembly failed")
        assertArrayEquals(packetB, resultB, "Packet B reassembly failed")
    }

    @Test
    fun `timeout cleanup removes stale buffers`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val reassembler = BLEReassembler(timeoutMs = 100L) // 100ms timeout

        val original = ByteArray(300) { 0x47 } // 'G'
        val fragments = fragmenter.fragment(original)

        // Send only first fragment
        val result = reassembler.receiveFragment(fragments[0], "device1")
        assertNull(result)
        assertEquals(1, reassembler.statistics.pendingPackets)

        // Wait for timeout
        Thread.sleep(200)

        // Cleanup should remove stale buffer
        val removed = reassembler.cleanupStale()
        assertEquals(1, removed)
        assertEquals(0, reassembler.statistics.pendingPackets)
    }

    @Test
    fun `statistics tracked correctly`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val reassembler = BLEReassembler()

        val packet = ByteArray(300) { 0x48 } // 'H'
        val fragments = fragmenter.fragment(packet)

        for (frag in fragments) {
            reassembler.receiveFragment(frag, "device1")
        }

        val stats = reassembler.statistics
        assertEquals(1, stats.packetsReassembled)
        assertEquals(fragments.size, stats.fragmentsReceived)
        assertEquals(0, stats.pendingPackets)
        assertEquals(0, stats.packetsTimedOut)
    }

    @Test
    fun `statistics tracks timeouts`() {
        val reassembler = BLEReassembler(timeoutMs = 50L)
        val fragmenter = BLEFragmenter(mtu = 100)

        val packet = ByteArray(300) { 0x49 }
        val fragments = fragmenter.fragment(packet)

        // Send only first fragment
        reassembler.receiveFragment(fragments[0], "device1")

        Thread.sleep(100)
        reassembler.cleanupStale()

        assertEquals(1, reassembler.statistics.packetsTimedOut)
        assertEquals(0, reassembler.statistics.packetsReassembled)
    }

    @Test
    fun `fragment too short throws IllegalArgumentException`() {
        val reassembler = BLEReassembler()

        assertThrows<IllegalArgumentException> {
            reassembler.receiveFragment(byteArrayOf(0x01, 0x02), "device1")
        }
    }

    @Test
    fun `invalid fragment type throws IllegalArgumentException`() {
        val reassembler = BLEReassembler()
        // type=0xFF is invalid, seq=0, total=1
        val badFragment = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0x01, 0x42)

        assertThrows<IllegalArgumentException> {
            reassembler.receiveFragment(badFragment, "device1")
        }
    }

    @Test
    fun `sequence greater than or equal to total throws`() {
        val reassembler = BLEReassembler()
        // type=START, seq=1, total=1 (seq >= total)
        val badFragment = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x01, 0x42)

        assertThrows<IllegalArgumentException> {
            reassembler.receiveFragment(badFragment, "device1")
        }
    }

    @Test
    fun `benign duplicate fragment is ignored`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = "Duplicate test data!".toByteArray()
        val fragments = fragmenter.fragment(original)
        assertEquals(1, fragments.size)

        // First send completes the packet
        val result1 = reassembler.receiveFragment(fragments[0], "device1")
        assertArrayEquals(original, result1)

        // Now send a multi-fragment packet and duplicate a fragment
        val fragmenter2 = BLEFragmenter(mtu = 50)
        val packet2 = ByteArray(100) { 0x50 }
        val fragments2 = fragmenter2.fragment(packet2)
        assertTrue(fragments2.size > 1)

        // Send first fragment
        reassembler.receiveFragment(fragments2[0], "device2")

        // Send first fragment again (benign duplicate) - should return null
        val dupResult = reassembler.receiveFragment(fragments2[0], "device2")
        assertNull(dupResult)

        // Complete the rest
        for (frag in fragments2.drop(1)) {
            reassembler.receiveFragment(frag, "device2")
        }
    }

    @Test
    fun `conflicting duplicate fragment throws and discards buffer`() {
        val reassembler = BLEReassembler()

        // Manually create two fragments with same seq but different data
        // type=START, seq=0, total=2
        val header = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02)
        val frag1 = header + byteArrayOf(0x41, 0x41, 0x41) // AAA
        val frag2 = header + byteArrayOf(0x42, 0x42, 0x42) // BBB (different data!)

        // First fragment is fine
        val result1 = reassembler.receiveFragment(frag1, "device1")
        assertNull(result1)

        // Same seq with different data should throw
        assertThrows<IllegalArgumentException> {
            reassembler.receiveFragment(frag2, "device1")
        }

        // Buffer should be discarded
        assertEquals(0, reassembler.statistics.pendingPackets)
    }

    @Test
    fun `total mismatch across fragments throws and discards buffer`() {
        val reassembler = BLEReassembler()

        // First fragment: type=START, seq=0, total=3
        val frag0 = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x03, 0x41)

        // Second fragment claims total=5 instead of 3
        val frag1 = byteArrayOf(0x02, 0x00, 0x01, 0x00, 0x05, 0x42)

        reassembler.receiveFragment(frag0, "device1")

        assertThrows<IllegalArgumentException> {
            reassembler.receiveFragment(frag1, "device1")
        }

        assertEquals(0, reassembler.statistics.pendingPackets)
    }

    // ========================================================================
    // Round-trip Tests
    // ========================================================================

    @Test
    fun `round trip - small packet`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = "Hello, BLE!".toByteArray()
        val fragments = fragmenter.fragment(original)

        var result: ByteArray? = null
        for (frag in fragments) {
            result = reassembler.receiveFragment(frag, "device1")
        }

        assertArrayEquals(original, result)
    }

    @Test
    fun `round trip - large packet 500 bytes`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = ByteArray(500) { it.toByte() }
        val fragments = fragmenter.fragment(original)

        var result: ByteArray? = null
        for (frag in fragments) {
            result = reassembler.receiveFragment(frag, "device1")
        }

        assertArrayEquals(original, result)
    }

    @Test
    fun `round trip - various MTU sizes`() {
        for (mtu in listOf(20, 23, 50, 100, 185, 251, 512)) {
            val fragmenter = BLEFragmenter(mtu = mtu)
            val reassembler = BLEReassembler()

            val original = ByteArray(500) { (it % 256).toByte() }
            val fragments = fragmenter.fragment(original)

            var result: ByteArray? = null
            for (frag in fragments) {
                result = reassembler.receiveFragment(frag, "device1")
            }

            assertArrayEquals(original, result, "Round-trip failed for MTU=$mtu")
        }
    }

    @Test
    fun `round trip - out of order delivery`() {
        val fragmenter = BLEFragmenter(mtu = 50)
        val reassembler = BLEReassembler()

        val original = ByteArray(500) { (it % 256).toByte() }
        val fragments = fragmenter.fragment(original)

        // Reverse order delivery
        val reversed = fragments.reversed()
        var result: ByteArray? = null
        for (frag in reversed) {
            result = reassembler.receiveFragment(frag, "device1")
        }

        assertArrayEquals(original, result, "Reverse-order round-trip failed")
    }

    @Test
    fun `round trip - single byte payload`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = byteArrayOf(0xFF.toByte())
        val fragments = fragmenter.fragment(original)
        assertEquals(1, fragments.size)

        val result = reassembler.receiveFragment(fragments[0], "device1")
        assertArrayEquals(original, result)
    }

    @Test
    fun `round trip - maximum single fragment payload`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        // Exactly 180 bytes = one fragment
        val original = ByteArray(180) { (it % 256).toByte() }
        val fragments = fragmenter.fragment(original)
        assertEquals(1, fragments.size)

        val result = reassembler.receiveFragment(fragments[0], "device1")
        assertArrayEquals(original, result)
    }

    @Test
    fun `round trip - binary data with all byte values`() {
        val fragmenter = BLEFragmenter(mtu = 100)
        val reassembler = BLEReassembler()

        // Packet containing all 256 byte values
        val original = ByteArray(256) { it.toByte() }
        val fragments = fragmenter.fragment(original)

        var result: ByteArray? = null
        for (frag in fragments) {
            result = reassembler.receiveFragment(frag, "device1")
        }

        assertArrayEquals(original, result)
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `minimum MTU 20 still works`() {
        val fragmenter = BLEFragmenter(mtu = 20)
        assertEquals(15, fragmenter.maxPayload)

        val original = ByteArray(100) { 0x42 }
        val fragments = fragmenter.fragment(original)

        val reassembler = BLEReassembler()
        var result: ByteArray? = null
        for (frag in fragments) {
            result = reassembler.receiveFragment(frag, "device1")
        }

        assertArrayEquals(original, result)
    }

    @Test
    fun `default sender id works`() {
        val fragmenter = BLEFragmenter(mtu = 185)
        val reassembler = BLEReassembler()

        val original = "Default sender".toByteArray()
        val fragments = fragmenter.fragment(original)

        // Use default sender (no senderId argument)
        val result = reassembler.receiveFragment(fragments[0])
        assertArrayEquals(original, result)
    }

    @Test
    fun `concurrent senders with different fragment counts`() {
        val reassembler = BLEReassembler()

        // Sender A: small packet (1 fragment)
        val fragA = BLEFragmenter(mtu = 185)
        val packetA = "Short A".toByteArray()
        val fragmentsA = fragA.fragment(packetA)
        assertEquals(1, fragmentsA.size)

        // Sender B: large packet (3 fragments)
        val fragB = BLEFragmenter(mtu = 185)
        val packetB = ByteArray(500) { 0x42 }
        val fragmentsB = fragB.fragment(packetB)
        assertEquals(3, fragmentsB.size)

        // Interleave: B[0], A[0](complete), B[1], B[2](complete)
        assertNull(reassembler.receiveFragment(fragmentsB[0], "B"))
        assertArrayEquals(packetA, reassembler.receiveFragment(fragmentsA[0], "A"))
        assertNull(reassembler.receiveFragment(fragmentsB[1], "B"))
        assertArrayEquals(packetB, reassembler.receiveFragment(fragmentsB[2], "B"))
    }
}
