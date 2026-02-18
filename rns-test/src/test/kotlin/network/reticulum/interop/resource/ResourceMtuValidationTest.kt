package network.reticulum.interop.resource

import network.reticulum.common.RnsConstants
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.link.LinkConstants
import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.resource.ResourceConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates that all resource protocol packets fit within the network MTU.
 *
 * This test suite verifies the mathematical constraints that prevent
 * resource packets from exceeding the 500-byte MTU:
 *
 * - Data parts: HEADER_1(19) + part_data(≤SDU) ≤ MTU
 * - Advertisements: HEADER_1(19) + Token(adv_data) ≤ MTU
 *   where adv_data ≤ Link.MDU (which accounts for Token overhead)
 *
 * The SDU (Service Data Unit) is the maximum data per resource part:
 *   SDU = MTU - HEADER_MAX_SIZE - IFAC_MIN_SIZE = 500 - 35 - 1 = 464
 *
 * Resource data is bulk-encrypted BEFORE splitting into parts, so each
 * part is raw encrypted bytes — no per-part Token encryption. This is
 * why SDU uses HEADER_MAX_SIZE (35) not the Token-aware Link.MDU.
 */
@DisplayName("Resource MTU Validation")
class ResourceMtuValidationTest {

    private val crypto = defaultCryptoProvider()

    companion object {
        // Protocol constants (matching Python RNS exactly)
        const val MTU = RnsConstants.MTU                          // 500
        const val HEADER_MIN_SIZE = RnsConstants.HEADER_MIN_SIZE  // 19 (HEADER_1)
        const val HEADER_MAX_SIZE = RnsConstants.HEADER_MAX_SIZE  // 35 (HEADER_2)
        const val IFAC_MIN_SIZE = RnsConstants.IFAC_MIN_SIZE      // 1
        const val TOKEN_OVERHEAD = RnsConstants.TOKEN_OVERHEAD     // 48
        const val AES_BLOCK_SIZE = RnsConstants.AES_BLOCK_SIZE    // 16
        const val MAPHASH_LEN = ResourceConstants.MAPHASH_LEN    // 4
    }

    @Nested
    @DisplayName("SDU Calculation")
    inner class SduCalculationTests {

        @Test
        @DisplayName("Resource SDU matches Python formula: MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE")
        fun `resource sdu matches python formula`() {
            val sdu = ResourceConstants.calculateSdu()
            val expected = MTU - HEADER_MAX_SIZE - IFAC_MIN_SIZE
            assertEquals(expected, sdu, "SDU should be MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE")
            assertEquals(464, sdu, "SDU should be 464 for default MTU of 500")
        }

        @Test
        @DisplayName("Resource SDU + HEADER_1 packet overhead fits in MTU")
        fun `resource sdu plus header fits in mtu`() {
            val sdu = ResourceConstants.calculateSdu()
            val maxPacketSize = HEADER_MIN_SIZE + sdu  // flags(1) + hops(1) + dest(16) + context(1) + data
            assertTrue(maxPacketSize <= MTU,
                "Max resource data packet ($maxPacketSize bytes) should fit in MTU ($MTU bytes)")
            assertEquals(483, maxPacketSize, "Max resource data packet should be 483 bytes")
        }

        @Test
        @DisplayName("Python-compatible SDU formula: SDU = RNS.Packet.MDU = RNS.Reticulum.MDU")
        fun `python compatible sdu`() {
            // In Python: Resource.SDU = RNS.Packet.MDU = RNS.Reticulum.MDU
            // RNS.Reticulum.MDU = MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE
            val pythonMdu = MTU - HEADER_MAX_SIZE - IFAC_MIN_SIZE
            val kotlinSdu = ResourceConstants.calculateSdu()
            assertEquals(pythonMdu, kotlinSdu,
                "Kotlin Resource SDU must match Python Reticulum.MDU")
        }
    }

    @Nested
    @DisplayName("Link MDU Calculation")
    inner class LinkMduTests {

        @Test
        @DisplayName("Link MDU matches Python formula with IFAC_MIN_SIZE")
        fun `link mdu matches python formula`() {
            // Python: MDU = floor((MTU - IFAC_MIN_SIZE - HEADER_MINSIZE - TOKEN_OVERHEAD) / AES_BLOCKSIZE) * AES_BLOCKSIZE - 1
            val pythonMdu = ((MTU - IFAC_MIN_SIZE - HEADER_MIN_SIZE - TOKEN_OVERHEAD) / AES_BLOCK_SIZE) * AES_BLOCK_SIZE - 1
            val kotlinMdu = LinkConstants.calculateMdu()
            assertEquals(pythonMdu, kotlinMdu,
                "Kotlin Link MDU must match Python Link.MDU. " +
                "Python: floor((${MTU}-${IFAC_MIN_SIZE}-${HEADER_MIN_SIZE}-${TOKEN_OVERHEAD})/${AES_BLOCK_SIZE})*${AES_BLOCK_SIZE}-1 = $pythonMdu, " +
                "Kotlin: $kotlinMdu")
        }

        @Test
        @DisplayName("Link MDU is 431 for default MTU")
        fun `link mdu default value`() {
            assertEquals(431, LinkConstants.MDU, "Link MDU should be 431 for MTU=500")
        }
    }

    @Nested
    @DisplayName("Data Part Sizing")
    inner class DataPartSizingTests {

        @Test
        @DisplayName("All parts of various data sizes fit within MTU")
        fun `all parts fit within mtu`() {
            val sdu = ResourceConstants.calculateSdu()
            val testSizes = listOf(
                1, 16, 100, 463, 464, 465,        // Near SDU boundary
                928, 1000, 2000, 5000, 10000,      // Multi-part
                464 * 74, 464 * 74 + 1,            // Near max hashmap boundary
                ResourceConstants.MAX_EFFICIENT_SIZE  // ~1MB max efficient
            )

            for (dataSize in testSizes) {
                val numParts = ceil(dataSize.toDouble() / sdu).toInt()
                for (i in 0 until numParts) {
                    val partStart = i * sdu
                    val partEnd = minOf(partStart + sdu, dataSize)
                    val partSize = partEnd - partStart

                    assertTrue(partSize <= sdu,
                        "Part $i of $dataSize byte resource: part size $partSize exceeds SDU $sdu")

                    val packetSize = HEADER_MIN_SIZE + partSize
                    assertTrue(packetSize <= MTU,
                        "Part $i of $dataSize byte resource: packet size $packetSize exceeds MTU $MTU")
                }
            }
        }

        @Test
        @DisplayName("Token-encrypted data stream produces correctly-sized parts")
        fun `encrypted data parts fit within mtu`() {
            // Simulate what Resource.initializeForSending() does:
            // 1. Build prefixed data = random_hash(4) + content
            // 2. Encrypt entire stream with Token
            // 3. Split encrypted data into SDU-sized parts
            val sdu = ResourceConstants.calculateSdu()
            val key = crypto.randomBytes(64) // AES-256-CBC derived key
            val token = Token(key)

            val testSizes = listOf(1, 100, 464, 465, 1000, 5000)

            for (contentSize in testSizes) {
                val content = ByteArray(contentSize) { (it % 256).toByte() }
                val prefix = crypto.randomBytes(ResourceConstants.RANDOM_HASH_SIZE) // 4 bytes
                val prefixedData = prefix + content

                // Encrypt the entire stream (same as Resource does)
                val encrypted = token.encrypt(prefixedData)

                // Split into parts
                val numParts = ceil(encrypted.size.toDouble() / sdu).toInt()
                for (i in 0 until numParts) {
                    val start = i * sdu
                    val end = minOf(start + sdu, encrypted.size)
                    val partSize = end - start

                    assertTrue(partSize <= sdu,
                        "Encrypted part $i ($partSize bytes) exceeds SDU ($sdu) for content size $contentSize")

                    val packetSize = HEADER_MIN_SIZE + partSize
                    assertTrue(packetSize <= MTU,
                        "Encrypted part $i packet ($packetSize bytes) exceeds MTU ($MTU) for content size $contentSize")
                }
            }
        }
    }

    @Nested
    @DisplayName("Advertisement Sizing")
    inner class AdvertisementSizingTests {

        @Test
        @DisplayName("HASHMAP_MAX_LEN is correctly bounded by Link MDU")
        fun `hashmap max len bounded by link mdu`() {
            val linkMdu = LinkConstants.MDU
            val overhead = ResourceAdvertisement.OVERHEAD
            val maxLen = ResourceAdvertisement.HASHMAP_MAX_LEN

            // Python: HASHMAP_MAX_LEN = floor((Link.MDU - OVERHEAD) / MAPHASH_LEN)
            val expectedMaxLen = floor((linkMdu - overhead).toDouble() / MAPHASH_LEN).toInt()
            assertEquals(expectedMaxLen, maxLen,
                "HASHMAP_MAX_LEN should be floor((MDU=$linkMdu - OVERHEAD=$overhead) / MAPHASH_LEN=$MAPHASH_LEN)")

            assertTrue(maxLen > 0, "HASHMAP_MAX_LEN must be positive")
        }

        @Test
        @DisplayName("Maximum advertisement data fits within Link MDU")
        fun `max advertisement fits within link mdu`() {
            val linkMdu = LinkConstants.MDU
            val overhead = ResourceAdvertisement.OVERHEAD
            val maxHashmapBytes = ResourceAdvertisement.HASHMAP_MAX_LEN * MAPHASH_LEN

            val maxAdvDataSize = overhead + maxHashmapBytes
            assertTrue(maxAdvDataSize <= linkMdu,
                "Max advertisement data ($maxAdvDataSize bytes) should fit within Link MDU ($linkMdu bytes)")
        }

        @Test
        @DisplayName("Token-encrypted max advertisement fits within MTU")
        fun `encrypted max advertisement fits within mtu`() {
            // Simulate the worst-case advertisement packet:
            // packet = HEADER_1(19) + Token(adv_data)
            // where adv_data is at most Link.MDU bytes
            val linkMdu = LinkConstants.MDU
            val key = crypto.randomBytes(64)
            val token = Token(key)

            // Create max-size plaintext (Link.MDU bytes)
            val maxPlaintext = ByteArray(linkMdu) { (it % 256).toByte() }
            val encrypted = token.encrypt(maxPlaintext)

            val packetSize = HEADER_MIN_SIZE + encrypted.size
            assertTrue(packetSize <= MTU,
                "Max encrypted advertisement packet ($packetSize bytes) should fit within MTU ($MTU bytes). " +
                "plaintext=$linkMdu, encrypted=${encrypted.size}, header=$HEADER_MIN_SIZE")
        }

        @Test
        @DisplayName("Theoretical max advertisement size stays within bounds")
        fun `theoretical max advertisement fits within mtu`() {
            // The advertisement contains fixed overhead (134 bytes) + hashmap.
            // HASHMAP_MAX_LEN limits how many hashmap entries fit within Link.MDU.
            // For resources with more parts than HASHMAP_MAX_LEN, subsequent
            // hashmap segments are sent via RESOURCE_HMU messages.

            val linkMdu = LinkConstants.MDU
            val overhead = ResourceAdvertisement.OVERHEAD
            val maxHashmapEntries = ResourceAdvertisement.HASHMAP_MAX_LEN
            val maxHashmapBytes = maxHashmapEntries * MAPHASH_LEN

            val maxAdvDataSize = overhead + maxHashmapBytes
            println("Link.MDU=$linkMdu, OVERHEAD=$overhead, HASHMAP_MAX_LEN=$maxHashmapEntries")
            println("Max adv data size = $overhead + ${maxHashmapBytes} = $maxAdvDataSize")
            println("Headroom = ${linkMdu - maxAdvDataSize} bytes")

            assertTrue(maxAdvDataSize <= linkMdu,
                "Max advertisement data ($maxAdvDataSize) exceeds Link MDU ($linkMdu)")

            // Verify Token encryption of max-size advertisement fits in MTU
            val key = crypto.randomBytes(64)
            val token = Token(key)

            // Create a buffer of exactly maxAdvDataSize bytes
            val maxAdvData = ByteArray(maxAdvDataSize) { (it % 256).toByte() }
            val encrypted = token.encrypt(maxAdvData)
            val packetSize = HEADER_MIN_SIZE + encrypted.size

            println("Encrypted adv: ${encrypted.size} bytes, packet: $packetSize bytes (MTU=$MTU)")

            assertTrue(packetSize <= MTU,
                "Max encrypted advertisement packet ($packetSize bytes) exceeds MTU ($MTU)")
        }
    }

    @Nested
    @DisplayName("Cross-Validation with Python Constants")
    inner class PythonCrossValidationTests {

        @Test
        @DisplayName("All protocol constants match Python values")
        fun `constants match python`() {
            assertEquals(500, MTU, "MTU must be 500")
            assertEquals(19, HEADER_MIN_SIZE, "HEADER_MIN_SIZE must be 19")
            assertEquals(35, HEADER_MAX_SIZE, "HEADER_MAX_SIZE must be 35")
            assertEquals(1, IFAC_MIN_SIZE, "IFAC_MIN_SIZE must be 1")
            assertEquals(48, TOKEN_OVERHEAD, "TOKEN_OVERHEAD must be 48")
            assertEquals(16, AES_BLOCK_SIZE, "AES_BLOCK_SIZE must be 16")
            assertEquals(4, MAPHASH_LEN, "MAPHASH_LEN must be 4")
            assertEquals(4, ResourceConstants.RANDOM_HASH_SIZE, "RANDOM_HASH_SIZE must be 4")
            assertEquals(134, ResourceAdvertisement.OVERHEAD, "Advertisement OVERHEAD must be 134")
        }

        @Test
        @DisplayName("Token overhead is exactly IV + HMAC = 16 + 32 = 48 (before padding)")
        fun `token overhead matches expected`() {
            val key = crypto.randomBytes(64)
            val token = Token(key)

            // For plaintext that's an exact multiple of block size,
            // PKCS7 adds a full block of padding. So overhead = 16 + 16 + 32 = 64
            val blockAligned = ByteArray(16) { it.toByte() }
            val encBlockAligned = token.encrypt(blockAligned)
            assertEquals(16 + 32 + 32, encBlockAligned.size,
                "Block-aligned encryption: IV(16) + ciphertext(16+16padding) + HMAC(32)")

            // For plaintext of 1 byte, ciphertext is 16 (padded to block size)
            val oneByte = byteArrayOf(0x42)
            val encOneByte = token.encrypt(oneByte)
            assertEquals(16 + 16 + 32, encOneByte.size,
                "1-byte encryption: IV(16) + ciphertext(16) + HMAC(32)")

            // TOKEN_OVERHEAD (48) accounts for IV(16) + HMAC(32), NOT padding.
            // The Link.MDU formula divides by AES_BLOCK_SIZE to account for padding.
            assertEquals(48, TOKEN_OVERHEAD)
        }

        @Test
        @DisplayName("Link MDU ensures encrypted payload fits in MTU minus HEADER_1")
        fun `link mdu ensures encrypted fits`() {
            // The Link MDU formula guarantees:
            // HEADER_MIN(19) + Token(MDU bytes) ≤ MTU(500)
            // Token(N bytes) = IV(16) + ceil(N/16)*16 + HMAC(32) = N + padding + 48
            // For N = MDU: MDU is aligned to block boundary minus 1,
            // so padding = 1 byte, ciphertext = MDU + 1 bytes... let me verify.

            val key = crypto.randomBytes(64)
            val token = Token(key)
            val linkMdu = LinkConstants.MDU

            val plaintext = ByteArray(linkMdu)
            val encrypted = token.encrypt(plaintext)
            val totalPacketSize = HEADER_MIN_SIZE + encrypted.size

            assertTrue(totalPacketSize <= MTU,
                "HEADER_1($HEADER_MIN_SIZE) + Token($linkMdu bytes → ${encrypted.size} bytes) = $totalPacketSize ≤ MTU($MTU)")
        }
    }
}
