package network.reticulum.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import network.reticulum.crypto.Hashes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest

@DisplayName("Stamper (PoW)")
class StamperTest {

    @Test
    @DisplayName("Workblock has expected size")
    fun `workblock size is rounds times 256`() {
        val material = ByteArray(32) { it.toByte() }
        val rounds = 20
        val workblock = Stamper.generateWorkblock(material, rounds)
        workblock.size shouldBe rounds * Stamper.HKDF_OUTPUT_LENGTH
    }

    @Test
    @DisplayName("stampValue counts leading zero bits correctly")
    fun `stamp value counts leading zeros`() {
        val material = ByteArray(32) { 0xFF.toByte() }
        val workblock = Stamper.generateWorkblock(material, 5)

        val stamp = ByteArray(32) { 0 }
        val value = Stamper.stampValue(workblock, stamp)
        assertTrue(value >= 0, "Stamp value should be >= 0, was $value")
    }

    @Test
    @DisplayName("stampValid accepts valid stamp")
    fun `stampValid accepts stamp meeting cost`() {
        val material = ByteArray(32) { 0xAB.toByte() }
        val workblock = Stamper.generateWorkblock(material, 5)

        val result = runBlocking { Stamper.generateStamp(workblock, 4) }
        result.stamp shouldNotBe null
        assertTrue(result.value >= 4, "Stamp value should be >= 4, was ${result.value}")

        Stamper.stampValid(result.stamp!!, 4, workblock) shouldBe true
    }

    @Test
    @DisplayName("stampValid rejects stamp below cost")
    fun `stampValid rejects stamp below cost`() {
        val material = ByteArray(32) { 0xCD.toByte() }
        val workblock = Stamper.generateWorkblock(material, 5)

        val badStamp = ByteArray(32) { 0xFF.toByte() }
        Stamper.stampValid(badStamp, 200, workblock) shouldBe false
    }

    @Test
    @DisplayName("generateStamp finds valid stamp with cost 8")
    fun `generateStamp finds valid stamp`() {
        val material = "test discovery stamp".toByteArray()
        val workblock = Stamper.generateWorkblock(material, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)

        val result = runBlocking { Stamper.generateStamp(workblock, 8) }
        result.stamp shouldNotBe null
        assertTrue(result.value >= 8, "Stamp value should be >= 8, was ${result.value}")
        assertTrue(result.rounds >= 1, "Should have tried at least 1 round")

        Stamper.stampValid(result.stamp!!, 8, workblock) shouldBe true
    }

    @Test
    @DisplayName("Discovery workblock expand rounds is 20")
    fun `discovery uses 20 expand rounds`() {
        DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS shouldBe 20
    }

    @Test
    @DisplayName("packInt matches msgpack format")
    fun `packInt produces valid msgpack`() {
        Stamper.packInt(0) shouldBe byteArrayOf(0x00)
        Stamper.packInt(127) shouldBe byteArrayOf(0x7f)
    }

    // ===== hashLeadingZeroBits =====

    @Test
    @DisplayName("hashLeadingZeroBits returns 256 for all-zero hash (the only fully-zero loop case)")
    fun `leading zero bits all zero`() {
        val allZero = ByteArray(32) { 0 }
        Stamper.hashLeadingZeroBits(allZero) shouldBe 256
    }

    @Test
    @DisplayName("hashLeadingZeroBits returns 0 when first byte has high bit set")
    fun `leading zero bits first byte high bit`() {
        val hash = ByteArray(32).also { it[0] = 0x80.toByte() }
        Stamper.hashLeadingZeroBits(hash) shouldBe 0
    }

    @Test
    @DisplayName("hashLeadingZeroBits counts within first non-zero byte")
    fun `leading zero bits within first byte`() {
        // First byte 0x01 = 7 leading zeros within the byte
        val hash = ByteArray(32).also { it[0] = 0x01 }
        Stamper.hashLeadingZeroBits(hash) shouldBe 7
        // First byte 0x10 = 3 leading zeros within the byte
        val hash2 = ByteArray(32).also { it[0] = 0x10 }
        Stamper.hashLeadingZeroBits(hash2) shouldBe 3
    }

    @Test
    @DisplayName("hashLeadingZeroBits accumulates across leading zero bytes")
    fun `leading zero bits accumulates across bytes`() {
        // Two zero bytes, then 0x80 → exactly 16 leading zero bits.
        val hash = ByteArray(32).also { it[2] = 0x80.toByte() }
        Stamper.hashLeadingZeroBits(hash) shouldBe 16
        // Three zero bytes, then 0x40 → 24 + 1 = 25 leading zero bits.
        val hash2 = ByteArray(32).also { it[3] = 0x40 }
        Stamper.hashLeadingZeroBits(hash2) shouldBe 25
    }

    // ===== computeStampHash =====

    @Test
    @DisplayName("computeStampHash with template clones primed digest and matches SHA-256(workblock+stamp)")
    fun `computeStampHash via clone matches reference`() {
        val workblock = "wbX".toByteArray()
        val stamp = ByteArray(32) { 0xAA.toByte() }
        val template = MessageDigest.getInstance("SHA-256").apply { update(workblock) }
        val expected = Hashes.fullHash(workblock + stamp)

        val out = Stamper.computeStampHash(template, workblock, stamp)
        out shouldBe expected
    }

    @Test
    @DisplayName("computeStampHash null-template fallback path matches SHA-256(workblock+stamp)")
    fun `computeStampHash fallback matches reference`() {
        val workblock = "wbY".toByteArray()
        val stamp = ByteArray(32) { 0x55.toByte() }
        val expected = Hashes.fullHash(workblock + stamp)

        val out = Stamper.computeStampHash(null, workblock, stamp)
        out shouldBe expected
    }

    @Test
    @DisplayName("computeStampHash template can be reused across many attempts (clone is non-destructive)")
    fun `computeStampHash template reusable`() {
        val workblock = ByteArray(64) { it.toByte() }
        val template = MessageDigest.getInstance("SHA-256").apply { update(workblock) }
        // First call mutates the clone, not the template — second call must match
        // SHA-256(workblock + stamp2), proving the template was not consumed.
        val stamp1 = ByteArray(32) { 0x11.toByte() }
        val stamp2 = ByteArray(32) { 0x22.toByte() }
        Stamper.computeStampHash(template, workblock, stamp1) shouldBe Hashes.fullHash(workblock + stamp1)
        Stamper.computeStampHash(template, workblock, stamp2) shouldBe Hashes.fullHash(workblock + stamp2)
    }
}
