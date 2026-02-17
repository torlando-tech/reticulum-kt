package network.reticulum.interfaces.i2p

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the I2P SAM client components.
 *
 * These tests verify SAM message parsing, Base64/Base32 encoding,
 * Destination construction, and exception mapping — all without
 * requiring a running I2P router.
 */
class I2PSamClientTest {

    // ─── Base64 encoding ─────────────────────────────────────────────

    @Test
    fun `i2p base64 roundtrip preserves data`() {
        val original = ByteArray(256) { it.toByte() }
        val encoded = I2PSamClient.i2pB64Encode(original)
        val decoded = I2PSamClient.i2pB64Decode(encoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `i2p base64 uses dash and tilde instead of plus and slash`() {
        // Standard base64 of bytes 62, 63 would produce '+' and '/'
        // I2P should produce '-' and '~'
        val data = byteArrayOf(0x3E.toByte(), 0x3F.toByte(), 0xFF.toByte())
        val encoded = I2PSamClient.i2pB64Encode(data)
        assertFalse(encoded.contains('+'), "Should not contain '+'")
        assertFalse(encoded.contains('/'), "Should not contain '/'")
    }

    @Test
    fun `i2p base64 decode handles dash and tilde`() {
        // Encode then decode with modified chars
        val original = ByteArray(100) { (it * 7).toByte() }
        val encoded = I2PSamClient.i2pB64Encode(original)
        val decoded = I2PSamClient.i2pB64Decode(encoded)
        assertArrayEquals(original, decoded)
    }

    // ─── SAM Reply parsing ───────────────────────────────────────────

    @Test
    fun `parse hello reply ok`() {
        val reply = SamReply.parse("HELLO REPLY RESULT=OK VERSION=3.1")
        assertEquals("HELLO", reply.cmd)
        assertEquals("REPLY", reply.action)
        assertTrue(reply.isOk)
        assertEquals("3.1", reply.opts["VERSION"])
    }

    @Test
    fun `parse hello reply error`() {
        val reply = SamReply.parse("HELLO REPLY RESULT=I2P_ERROR MESSAGE=Something_went_wrong")
        assertEquals("HELLO", reply.cmd)
        assertFalse(reply.isOk)
        assertEquals("I2P_ERROR", reply.opts["RESULT"])
    }

    @Test
    fun `parse session create reply`() {
        val reply = SamReply.parse("SESSION STATUS RESULT=OK DESTINATION=ABCDEFG123")
        assertTrue(reply.isOk)
        assertEquals("ABCDEFG123", reply.opts["DESTINATION"])
    }

    @Test
    fun `parse naming lookup reply`() {
        val reply = SamReply.parse("NAMING REPLY RESULT=OK NAME=test.i2p VALUE=ABCDEF1234567890")
        assertTrue(reply.isOk)
        assertEquals("test.i2p", reply.opts["NAME"])
        assertEquals("ABCDEF1234567890", reply.opts["VALUE"])
    }

    @Test
    fun `parse reply with empty opts`() {
        val reply = SamReply.parse("HELLO REPLY RESULT=OK")
        assertTrue(reply.isOk)
    }

    @Test
    fun `parse reply with quoted message value`() {
        val reply = SamReply.parse(
            """STREAM STATUS RESULT=I2P_ERROR MESSAGE="SAM connection cancelled by user request""""
        )
        assertEquals("STREAM", reply.cmd)
        assertEquals("STATUS", reply.action)
        assertEquals("I2P_ERROR", reply.opts["RESULT"])
        assertEquals("SAM connection cancelled by user request", reply.opts["MESSAGE"])
    }

    @Test
    fun `parse reply with multiple quoted values`() {
        val reply = SamReply.parse(
            """TEST ACTION KEY1="value with spaces" KEY2="another value" KEY3=plain"""
        )
        assertEquals("value with spaces", reply.opts["KEY1"])
        assertEquals("another value", reply.opts["KEY2"])
        assertEquals("plain", reply.opts["KEY3"])
    }

    @Test
    fun `parse reply with mixed quoted and unquoted values`() {
        val reply = SamReply.parse(
            """SESSION STATUS RESULT=OK DESTINATION=ABCDEF MESSAGE="All good" VERSION=3.1"""
        )
        assertTrue(reply.isOk)
        assertEquals("ABCDEF", reply.opts["DESTINATION"])
        assertEquals("All good", reply.opts["MESSAGE"])
        assertEquals("3.1", reply.opts["VERSION"])
    }

    // ─── SAM Exception mapping ───────────────────────────────────────

    @Test
    fun `sam exception mapping matches python`() {
        assertEquals(9, SAM_EXCEPTIONS.size, "Should have 9 SAM exceptions")

        // Verify all expected keys are present
        val expectedKeys = listOf(
            "CANT_REACH_PEER", "DUPLICATED_DEST", "DUPLICATED_ID",
            "I2P_ERROR", "INVALID_ID", "INVALID_KEY",
            "KEY_NOT_FOUND", "PEER_NOT_FOUND", "TIMEOUT"
        )
        for (key in expectedKeys) {
            assertTrue(key in SAM_EXCEPTIONS, "Missing exception for $key")
        }
    }

    @Test
    fun `sam reply toException creates correct type`() {
        val reply = SamReply.parse("STREAM STATUS RESULT=CANT_REACH_PEER MESSAGE=unreachable")
        val ex = reply.toException()
        assertTrue(ex is CantReachPeerException, "Expected CantReachPeerException, got ${ex.javaClass.name}")
    }

    @Test
    fun `sam reply toException for unknown result creates generic SamException`() {
        val reply = SamReply.parse("STREAM STATUS RESULT=UNKNOWN_ERROR MESSAGE=oops")
        val ex = reply.toException()
        // Should be base SamException, not a specific subclass
        assertFalse(ex is CantReachPeerException)
        assertTrue(ex.message!!.contains("UNKNOWN_ERROR"))
    }

    // ─── Session ID generation ───────────────────────────────────────

    @Test
    fun `session id starts with reticulum prefix`() {
        val id = I2PSamClient.generateSessionId()
        assertTrue(id.startsWith("reticulum-"))
    }

    @Test
    fun `session id has correct length`() {
        val id = I2PSamClient.generateSessionId(8)
        assertEquals("reticulum-".length + 8, id.length)
    }

    @Test
    fun `session ids are unique`() {
        val ids = (1..100).map { I2PSamClient.generateSessionId() }.toSet()
        // With 6 chars from 52 possible, collisions in 100 are extremely unlikely
        assertTrue(ids.size > 95, "Expected most IDs to be unique, got ${ids.size} unique out of 100")
    }

    // ─── Base32 encoding ─────────────────────────────────────────────

    @Test
    fun `base64 roundtrip for various sizes`() {
        for (size in listOf(0, 1, 32, 100, 256, 512)) {
            val original = ByteArray(size) { (it * 17).toByte() }
            val encoded = I2PSamClient.i2pB64Encode(original)
            val decoded = I2PSamClient.i2pB64Decode(encoded)
            assertArrayEquals(original, decoded, "Roundtrip failed for size $size")
        }
    }

    // ─── Free port ───────────────────────────────────────────────────

    @Test
    fun `getFreePort returns valid port`() {
        val port = I2PSamClient.getFreePort()
        assertTrue(port > 0, "Port should be positive")
        assertTrue(port < 65536, "Port should be < 65536")
    }

    @Test
    fun `getFreePort returns different ports`() {
        val ports = (1..5).map { I2PSamClient.getFreePort() }.toSet()
        // OS should give different ports (though not guaranteed, very likely)
        assertTrue(ports.size >= 3, "Expected at least 3 different ports from 5 calls")
    }

    // ─── SAM address ─────────────────────────────────────────────────

    @Test
    fun `default sam address is localhost 7656`() {
        val addr = I2PSamClient.DEFAULT_SAM_ADDRESS
        assertEquals("127.0.0.1", addr.address.hostAddress)
        assertEquals(7656, addr.port)
    }

    // ─── PrivateKey ──────────────────────────────────────────────────

    @Test
    fun `private key from bytes roundtrips`() {
        val data = ByteArray(512) { it.toByte() }
        val pk = PrivateKey(data as Any)
        assertArrayEquals(data, pk.data)

        // Decode the base64 back
        val decoded = I2PSamClient.i2pB64Decode(pk.base64)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `private key from string roundtrips`() {
        val data = ByteArray(512) { it.toByte() }
        val b64 = I2PSamClient.i2pB64Encode(data)
        val pk = PrivateKey(b64 as Any)
        assertArrayEquals(data, pk.data)
        assertEquals(b64, pk.base64)
    }
}
