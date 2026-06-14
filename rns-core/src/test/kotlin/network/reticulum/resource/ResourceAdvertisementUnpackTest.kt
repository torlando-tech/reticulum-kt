package network.reticulum.resource

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

/**
 * Pins [ResourceAdvertisement.unpack]'s required-key validation, mirroring
 * python `ResourceAdvertisement.unpack` (Resource.py:1363-1373), which accesses
 * every field as `dictionary["t"]`, `dictionary["h"]`, ... and raises KeyError
 * on a missing key — caught by `Resource.accept` and treated as a dropped
 * advertisement. A previous build tolerated missing keys, returning a degenerate
 * adv with an empty hash that would start a (bogus) transfer.
 */
class ResourceAdvertisementUnpackTest {

    /** Pack a msgpack map for an advertisement, optionally omitting [omit] keys. */
    private fun packAdvMap(omit: Set<String> = emptySet()): ByteArray {
        val intFields = mapOf("t" to 100, "d" to 90, "n" to 2, "f" to 0x01, "i" to 1, "l" to 1)
        val binFields = mapOf(
            "h" to ByteArray(32) { 1 },
            "r" to ByteArray(4) { 2 },
            "o" to ByteArray(32) { 3 },
            "m" to ByteArray(8) { 4 },
        )
        val keys = listOf("t", "d", "n", "h", "r", "o", "i", "l", "q", "f", "m").filter { it !in omit }

        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)
        packer.packMapHeader(keys.size)
        for (k in keys) {
            packer.packString(k)
            when {
                k == "q" -> packer.packNil()
                intFields.containsKey(k) -> packer.packInt(intFields.getValue(k))
                else -> {
                    val v = binFields.getValue(k)
                    packer.packBinaryHeader(v.size)
                    packer.writePayload(v)
                }
            }
        }
        packer.close()
        return out.toByteArray()
    }

    @Test
    fun `unpack accepts an advertisement with all required keys`() {
        val adv = ResourceAdvertisement.unpack(packAdvMap())
        assertNotNull(adv, "a complete advertisement map must unpack")
        // The required-key set is honoured: fields decoded as expected.
        assertNotNull(adv!!.hash)
        org.junit.jupiter.api.Assertions.assertEquals(32, adv.hash.size)
    }

    @Test
    fun `unpack rejects an advertisement missing the required hash key`() {
        // Mirrors the conformance missing_key injector (wire_tcp.py
        // cmd_wire_inject_malformed_resource_adv): a valid msgpack map missing
        // "h" must be dropped (unpack returns null), not silently accepted.
        val adv = ResourceAdvertisement.unpack(packAdvMap(omit = setOf("h")))
        assertNull(adv, "an advertisement missing the required 'h' key must be rejected")
    }

    @Test
    fun `unpack rejects undecodable msgpack`() {
        // 0xC1 is msgpack's reserved/never-used lead byte (the 'garbage' variant).
        val adv = ResourceAdvertisement.unpack(byteArrayOf(0xC1.toByte()) + ByteArray(8))
        assertNull(adv, "undecodable msgpack must be rejected")
    }
}
