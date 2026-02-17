package network.reticulum.discovery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
}
