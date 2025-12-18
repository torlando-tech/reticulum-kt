package network.reticulum.integration

import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.resource.ResourceConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Resource transfers.
 */
@DisplayName("Resource Integration Tests")
class ResourceIntegrationTest {

    @Test
    @DisplayName("Resource constants match Python reference")
    @Timeout(5)
    fun `resource constants match python reference`() {
        // Verify critical constants
        assertEquals(4, ResourceConstants.WINDOW, "WINDOW should be 4")
        assertEquals(2, ResourceConstants.WINDOW_MIN, "WINDOW_MIN should be 2")
        assertEquals(75, ResourceConstants.WINDOW_MAX_FAST, "WINDOW_MAX_FAST should be 75")
        assertEquals(4, ResourceConstants.MAPHASH_LEN, "MAPHASH_LEN should be 4")
        assertEquals(4, ResourceConstants.RANDOM_HASH_SIZE, "RANDOM_HASH_SIZE should be 4")
        assertEquals(16, ResourceConstants.MAX_RETRIES, "MAX_RETRIES should be 16")

        // Status constants
        assertEquals(0x00, ResourceConstants.NONE, "NONE should be 0x00")
        assertEquals(0x01, ResourceConstants.QUEUED, "QUEUED should be 0x01")
        assertEquals(0x02, ResourceConstants.ADVERTISED, "ADVERTISED should be 0x02")
        assertEquals(0x03, ResourceConstants.TRANSFERRING, "TRANSFERRING should be 0x03")
        assertEquals(0x06, ResourceConstants.COMPLETE, "COMPLETE should be 0x06")
        assertEquals(0x07, ResourceConstants.FAILED, "FAILED should be 0x07")
    }

    @Test
    @DisplayName("ResourceAdvertisement can pack and unpack")
    @Timeout(5)
    fun `resource advertisement can pack and unpack`() {
        // Create a mock advertisement
        val adv = ResourceAdvertisement.unpack(createMockAdvertisement())

        assertNotNull(adv, "Should be able to unpack advertisement")
        assertEquals(1000, adv.transferSize, "Transfer size should match")
        assertEquals(1000, adv.dataSize, "Data size should match")
        assertEquals(5, adv.numParts, "Num parts should match")
        assertEquals(1, adv.segmentIndex, "Segment index should match")
        assertEquals(1, adv.totalSegments, "Total segments should match")
    }

    @Test
    @DisplayName("ResourceAdvertisement encodes flags correctly")
    @Timeout(5)
    fun `resource advertisement encodes flags correctly`() {
        // Test flag decoding (flags = 0x03 = encrypted + compressed)
        val adv = ResourceAdvertisement.unpack(createMockAdvertisement(flags = 0x03))

        assertNotNull(adv)
        assertTrue(adv.encrypted, "encrypted flag should be set")
        assertTrue(adv.compressed, "compressed flag should be set")
        assertEquals(false, adv.split, "split flag should not be set")
        assertEquals(false, adv.isRequest, "request flag should not be set")
        assertEquals(false, adv.isResponseFlag, "response flag should not be set")
        assertEquals(false, adv.hasMetadata, "metadata flag should not be set")
    }

    @Test
    @DisplayName("ResourceAdvertisement round-trip preserves data")
    @Timeout(5)
    fun `resource advertisement round trip preserves data`() {
        val original = ResourceAdvertisement.unpack(createMockAdvertisement())
        assertNotNull(original)

        // Pack and unpack
        val packed = original.pack()
        val unpacked = ResourceAdvertisement.unpack(packed)

        assertNotNull(unpacked)
        assertEquals(original.transferSize, unpacked.transferSize)
        assertEquals(original.dataSize, unpacked.dataSize)
        assertEquals(original.numParts, unpacked.numParts)
        assertEquals(original.segmentIndex, unpacked.segmentIndex)
        assertEquals(original.totalSegments, unpacked.totalSegments)
        assertEquals(original.flags, unpacked.flags)
        assertTrue(original.hash.contentEquals(unpacked.hash))
        assertTrue(original.randomHash.contentEquals(unpacked.randomHash))
    }

    /**
     * Create a mock advertisement for testing.
     */
    private fun createMockAdvertisement(flags: Int = 0x00): ByteArray {
        val output = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(output)

        packer.packMapHeader(11)

        packer.packString("t")
        packer.packInt(1000)  // transfer size

        packer.packString("d")
        packer.packInt(1000)  // data size

        packer.packString("n")
        packer.packInt(5)     // num parts

        packer.packString("h")
        val hash = ByteArray(16) { it.toByte() }
        packer.packBinaryHeader(hash.size)
        packer.writePayload(hash)

        packer.packString("r")
        val randomHash = ByteArray(4) { (it + 100).toByte() }
        packer.packBinaryHeader(randomHash.size)
        packer.writePayload(randomHash)

        packer.packString("o")
        packer.packBinaryHeader(hash.size)
        packer.writePayload(hash)

        packer.packString("i")
        packer.packInt(1)  // segment index

        packer.packString("l")
        packer.packInt(1)  // total segments

        packer.packString("q")
        packer.packNil()   // request ID

        packer.packString("f")
        packer.packInt(flags)

        packer.packString("m")
        val hashmap = ByteArray(20)  // 5 parts * 4 bytes
        packer.packBinaryHeader(hashmap.size)
        packer.writePayload(hashmap)

        packer.close()
        return output.toByteArray()
    }
}
