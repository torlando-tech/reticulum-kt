package network.reticulum.interop.ifac

import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interoperability tests for IFAC (Interface Access Code) operations.
 *
 * IFAC is used to authenticate packets on specific interfaces.
 * Key formats:
 * - IFAC_SALT: 32-byte constant used for HKDF derivation
 * - IFAC key: 64-byte key derived via HKDF(salt=IFAC_SALT, ikm=origin_hash)
 * - IFAC tag: Last N bytes of Ed25519 signature (N = ifac_size, typically 8-16)
 */
@DisplayName("IFAC Interop")
class IfacInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    companion object {
        // IFAC_SALT from Python RNS (must match exactly)
        val IFAC_SALT = "adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Nested
    @DisplayName("IFAC Key Derivation")
    inner class IfacKeyDerivation {

        @Test
        @DisplayName("IFAC_SALT matches Python")
        fun `ifac salt matches python`() {
            val pythonResult = python(
                "ifac_derive_key",
                "ifac_origin" to ByteArray(16) { it.toByte() }
            )

            val pythonSalt = pythonResult.getBytes("ifac_salt")

            assertBytesEqual(IFAC_SALT, pythonSalt, "IFAC_SALT should match Python")
        }

        @Test
        @DisplayName("IFAC key derivation matches Python")
        fun `ifac key derivation matches python`() {
            // Use a network name hash as the origin
            val ifacOrigin = Hashes.fullHash("test_network".toByteArray())

            // Derive key in Python
            val pythonResult = python(
                "ifac_derive_key",
                "ifac_origin" to ifacOrigin
            )

            val pythonIfacKey = pythonResult.getBytes("ifac_key")

            // Kotlin derives the same key
            val kotlinIfacKey = crypto.hkdf(64, ifacOrigin, IFAC_SALT, null)

            assertEquals(64, kotlinIfacKey.size, "IFAC key should be 64 bytes")
            assertBytesEqual(pythonIfacKey, kotlinIfacKey, "IFAC key derivation should match Python")
        }

        @Test
        @DisplayName("Different origins produce different keys")
        fun `different origins produce different keys`() {
            val origin1 = Hashes.fullHash("network_one".toByteArray())
            val origin2 = Hashes.fullHash("network_two".toByteArray())

            val key1 = crypto.hkdf(64, origin1, IFAC_SALT, null)
            val key2 = crypto.hkdf(64, origin2, IFAC_SALT, null)

            assertTrue(!key1.contentEquals(key2), "Different origins should produce different keys")
        }

        @Test
        @DisplayName("IFAC key derivation is deterministic")
        fun `ifac key derivation is deterministic`() {
            val ifacOrigin = Hashes.fullHash("consistent_network".toByteArray())

            val key1 = crypto.hkdf(64, ifacOrigin, IFAC_SALT, null)
            val key2 = crypto.hkdf(64, ifacOrigin, IFAC_SALT, null)

            assertBytesEqual(key1, key2, "Same origin should produce same key")
        }
    }

    @Nested
    @DisplayName("IFAC Computation")
    inner class IfacComputation {

        @Test
        @DisplayName("IFAC computation matches Python")
        fun `ifac computation matches python`() {
            // Derive an IFAC key
            val ifacOrigin = Hashes.fullHash("test_ifac_network".toByteArray())
            val ifacKeyResult = python(
                "ifac_derive_key",
                "ifac_origin" to ifacOrigin
            )
            val ifacKey = ifacKeyResult.getBytes("ifac_key")

            // Create test packet data
            val packetData = ByteArray(100) { (it * 3).toByte() }

            // Compute IFAC in Python
            val pythonResult = python(
                "ifac_compute",
                "ifac_key" to ifacKey,
                "packet_data" to packetData,
                "ifac_size" to 16
            )

            val pythonIfac = pythonResult.getBytes("ifac")

            // Verify IFAC is the expected size
            assertEquals(16, pythonIfac.size, "IFAC should be 16 bytes")
        }

        @Test
        @DisplayName("IFAC verification round-trip")
        fun `ifac verification round trip`() {
            // Derive an IFAC key
            val ifacOrigin = Hashes.fullHash("verify_network".toByteArray())
            val ifacKeyResult = python(
                "ifac_derive_key",
                "ifac_origin" to ifacOrigin
            )
            val ifacKey = ifacKeyResult.getBytes("ifac_key")

            // Create test packet data
            val packetData = ByteArray(50) { it.toByte() }

            // Compute IFAC
            val computeResult = python(
                "ifac_compute",
                "ifac_key" to ifacKey,
                "packet_data" to packetData,
                "ifac_size" to 8
            )
            val ifac = computeResult.getBytes("ifac")

            // Verify IFAC
            val verifyResult = python(
                "ifac_verify",
                "ifac_key" to ifacKey,
                "packet_data" to packetData,
                "expected_ifac" to ifac
            )

            assertTrue(verifyResult.getBoolean("valid"), "IFAC should verify correctly")
        }

        @Test
        @DisplayName("Wrong IFAC key fails verification")
        fun `wrong ifac key fails verification`() {
            // Derive two different IFAC keys
            val ifacOrigin1 = Hashes.fullHash("network_alpha".toByteArray())
            val ifacOrigin2 = Hashes.fullHash("network_beta".toByteArray())

            val ifacKey1Result = python("ifac_derive_key", "ifac_origin" to ifacOrigin1)
            val ifacKey2Result = python("ifac_derive_key", "ifac_origin" to ifacOrigin2)

            val ifacKey1 = ifacKey1Result.getBytes("ifac_key")
            val ifacKey2 = ifacKey2Result.getBytes("ifac_key")

            // Create test packet data
            val packetData = ByteArray(30) { it.toByte() }

            // Compute IFAC with key1
            val computeResult = python(
                "ifac_compute",
                "ifac_key" to ifacKey1,
                "packet_data" to packetData,
                "ifac_size" to 16
            )
            val ifac = computeResult.getBytes("ifac")

            // Verify with key2 (should fail)
            val verifyResult = python(
                "ifac_verify",
                "ifac_key" to ifacKey2,
                "packet_data" to packetData,
                "expected_ifac" to ifac
            )

            assertTrue(!verifyResult.getBoolean("valid"), "Wrong IFAC key should fail verification")
        }

        @Test
        @DisplayName("Modified packet fails IFAC verification")
        fun `modified packet fails ifac verification`() {
            // Derive an IFAC key
            val ifacOrigin = Hashes.fullHash("tamper_test".toByteArray())
            val ifacKeyResult = python(
                "ifac_derive_key",
                "ifac_origin" to ifacOrigin
            )
            val ifacKey = ifacKeyResult.getBytes("ifac_key")

            // Create test packet data
            val packetData = ByteArray(40) { it.toByte() }

            // Compute IFAC
            val computeResult = python(
                "ifac_compute",
                "ifac_key" to ifacKey,
                "packet_data" to packetData,
                "ifac_size" to 16
            )
            val ifac = computeResult.getBytes("ifac")

            // Modify packet data
            val modifiedPacketData = packetData.copyOf()
            modifiedPacketData[20] = (modifiedPacketData[20].toInt() xor 0xFF).toByte()

            // Verify with modified data (should fail)
            val verifyResult = python(
                "ifac_verify",
                "ifac_key" to ifacKey,
                "packet_data" to modifiedPacketData,
                "expected_ifac" to ifac
            )

            assertTrue(!verifyResult.getBoolean("valid"), "Modified packet should fail IFAC verification")
        }

        @Test
        @DisplayName("Various IFAC sizes work correctly")
        fun `various ifac sizes work correctly`() {
            val ifacOrigin = Hashes.fullHash("size_test".toByteArray())
            val ifacKeyResult = python("ifac_derive_key", "ifac_origin" to ifacOrigin)
            val ifacKey = ifacKeyResult.getBytes("ifac_key")

            val packetData = ByteArray(100) { it.toByte() }

            // Test common IFAC sizes
            for (size in listOf(1, 8, 16)) {
                val computeResult = python(
                    "ifac_compute",
                    "ifac_key" to ifacKey,
                    "packet_data" to packetData,
                    "ifac_size" to size
                )
                val ifac = computeResult.getBytes("ifac")

                assertEquals(size, ifac.size, "IFAC should be $size bytes")

                // Verify it
                val verifyResult = python(
                    "ifac_verify",
                    "ifac_key" to ifacKey,
                    "packet_data" to packetData,
                    "expected_ifac" to ifac
                )
                assertTrue(verifyResult.getBoolean("valid"), "IFAC size $size should verify")
            }
        }
    }
}
