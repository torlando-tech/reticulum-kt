package network.reticulum.interop.link

import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.link.LinkConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import network.reticulum.common.CryptoException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Edge case interoperability tests for link encryption.
 *
 * These tests focus on:
 * - AES block boundary edge cases (15, 16, 17 bytes)
 * - Maximum MDU size
 * - Error handling (corrupted data, wrong keys)
 */
@DisplayName("Link Encryption Edge Cases")
class LinkEncryptionEdgeCasesInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Block Boundary Tests")
    inner class BlockBoundaryTests {

        private val derivedKey = ByteArray(64) { it.toByte() }
        private val fixedIv = ByteArray(16) { (it + 50).toByte() }

        @Test
        @DisplayName("15 bytes (less than one AES block)")
        fun `15 bytes plaintext`() {
            val plaintext = ByteArray(15) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "15 bytes")
        }

        @Test
        @DisplayName("16 bytes (exactly one AES block)")
        fun `16 bytes plaintext`() {
            val plaintext = ByteArray(16) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "16 bytes")
        }

        @Test
        @DisplayName("17 bytes (one block plus one byte)")
        fun `17 bytes plaintext`() {
            val plaintext = ByteArray(17) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "17 bytes")
        }

        @Test
        @DisplayName("31 bytes (two blocks minus one)")
        fun `31 bytes plaintext`() {
            val plaintext = ByteArray(31) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "31 bytes")
        }

        @Test
        @DisplayName("32 bytes (exactly two AES blocks)")
        fun `32 bytes plaintext`() {
            val plaintext = ByteArray(32) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "32 bytes")
        }

        @Test
        @DisplayName("33 bytes (two blocks plus one)")
        fun `33 bytes plaintext`() {
            val plaintext = ByteArray(33) { it.toByte() }
            verifyEncryptionRoundTrip(plaintext, "33 bytes")
        }

        @Test
        @DisplayName("Ciphertext is identical with same IV for block-aligned data")
        fun `ciphertext identical with same iv for block aligned`() {
            val plaintext = ByteArray(32) { (it * 2).toByte() }

            val kotlinCiphertext = Token(derivedKey, crypto).encryptWithIv(plaintext, fixedIv)

            val pythonResult = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext,
                "iv" to fixedIv
            )

            assertBytesEqual(
                pythonResult.getBytes("ciphertext"),
                kotlinCiphertext,
                "32-byte plaintext with fixed IV"
            )
        }

        @Test
        @DisplayName("Ciphertext is identical with same IV for non-block-aligned data")
        fun `ciphertext identical with same iv for non block aligned`() {
            val plaintext = ByteArray(17) { (it * 3).toByte() }

            val kotlinCiphertext = Token(derivedKey, crypto).encryptWithIv(plaintext, fixedIv)

            val pythonResult = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext,
                "iv" to fixedIv
            )

            assertBytesEqual(
                pythonResult.getBytes("ciphertext"),
                kotlinCiphertext,
                "17-byte plaintext with fixed IV"
            )
        }

        private fun verifyEncryptionRoundTrip(plaintext: ByteArray, description: String) {
            // Kotlin encrypts, Python decrypts
            val kotlinCiphertext = Token(derivedKey, crypto).encrypt(plaintext)
            val pythonDecrypted = python(
                "link_decrypt",
                "derived_key" to derivedKey,
                "ciphertext" to kotlinCiphertext
            ).getBytes("plaintext")
            assertBytesEqual(plaintext, pythonDecrypted, "Kotlin->Python for $description")

            // Python encrypts, Kotlin decrypts
            val pythonCiphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext
            ).getBytes("ciphertext")
            val kotlinDecrypted = Token(derivedKey, crypto).decrypt(pythonCiphertext)
            assertBytesEqual(plaintext, kotlinDecrypted!!, "Python->Kotlin for $description")
        }
    }

    @Nested
    @DisplayName("Maximum Size Tests")
    inner class MaximumSizeTests {

        private val derivedKey = ByteArray(64) { (it * 7).toByte() }

        @Test
        @DisplayName("MDU-sized plaintext")
        fun `mdu sized plaintext`() {
            val plaintext = ByteArray(LinkConstants.MDU) { (it % 256).toByte() }

            // Kotlin -> Python
            val kotlinCiphertext = Token(derivedKey, crypto).encrypt(plaintext)
            val pythonDecrypted = python(
                "link_decrypt",
                "derived_key" to derivedKey,
                "ciphertext" to kotlinCiphertext
            ).getBytes("plaintext")
            assertBytesEqual(plaintext, pythonDecrypted, "Kotlin->Python for MDU size")

            // Python -> Kotlin
            val pythonCiphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext
            ).getBytes("ciphertext")
            val kotlinDecrypted = Token(derivedKey, crypto).decrypt(pythonCiphertext)
            assertBytesEqual(plaintext, kotlinDecrypted!!, "Python->Kotlin for MDU size")
        }

        @Test
        @DisplayName("Large plaintext (1KB)")
        fun `large plaintext 1kb`() {
            val plaintext = ByteArray(1024) { (it % 256).toByte() }

            val kotlinCiphertext = Token(derivedKey, crypto).encrypt(plaintext)
            val pythonDecrypted = python(
                "link_decrypt",
                "derived_key" to derivedKey,
                "ciphertext" to kotlinCiphertext
            ).getBytes("plaintext")

            assertBytesEqual(plaintext, pythonDecrypted, "1KB plaintext round-trip")
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        private val derivedKey = ByteArray(64) { (it * 11).toByte() }

        @Test
        @DisplayName("Corrupted HMAC is rejected by Kotlin")
        fun `corrupted hmac rejected by kotlin`() {
            val plaintext = "Test message".toByteArray()

            // Encrypt in Python
            val pythonCiphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext
            ).getBytes("ciphertext")

            // Corrupt the HMAC (last 32 bytes)
            val corrupted = pythonCiphertext.copyOf()
            corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

            // Kotlin should reject with exception
            assertFailsWith<CryptoException> {
                Token(derivedKey, crypto).decrypt(corrupted)
            }
        }

        @Test
        @DisplayName("Corrupted HMAC is rejected by Python")
        fun `corrupted hmac rejected by python`() {
            val plaintext = "Test message".toByteArray()

            // Encrypt in Kotlin
            val kotlinCiphertext = Token(derivedKey, crypto).encrypt(plaintext)

            // Corrupt the HMAC
            val corrupted = kotlinCiphertext.copyOf()
            corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

            // Python should reject with exception (propagated through bridge)
            val exception = assertFailsWith<AssertionError> {
                python(
                    "link_decrypt",
                    "derived_key" to derivedKey,
                    "ciphertext" to corrupted
                )
            }
            assertTrue(exception.message?.contains("HMAC") == true, "Error should mention HMAC")
        }

        @Test
        @DisplayName("Wrong key fails decryption in Kotlin")
        fun `wrong key fails decryption kotlin`() {
            val plaintext = "Secret message".toByteArray()
            val wrongKey = ByteArray(64) { (it + 100).toByte() }

            // Encrypt with correct key in Python
            val ciphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext
            ).getBytes("ciphertext")

            // Decrypt with wrong key in Kotlin - should fail with exception
            assertFailsWith<CryptoException> {
                Token(wrongKey, crypto).decrypt(ciphertext)
            }
        }

        @Test
        @DisplayName("Wrong key fails decryption in Python")
        fun `wrong key fails decryption python`() {
            val plaintext = "Secret message".toByteArray()
            val wrongKey = ByteArray(64) { (it + 100).toByte() }

            // Encrypt with correct key in Kotlin
            val ciphertext = Token(derivedKey, crypto).encrypt(plaintext)

            // Decrypt with wrong key in Python - should fail with exception
            val exception = assertFailsWith<AssertionError> {
                python(
                    "link_decrypt",
                    "derived_key" to wrongKey,
                    "ciphertext" to ciphertext
                )
            }
            assertTrue(exception.message?.contains("HMAC") == true, "Error should mention HMAC verification failure")
        }

        @Test
        @DisplayName("Truncated ciphertext is rejected")
        fun `truncated ciphertext rejected`() {
            val plaintext = "Test message".toByteArray()

            val ciphertext = Token(derivedKey, crypto).encrypt(plaintext)

            // Truncate (remove last 10 bytes)
            val truncated = ciphertext.copyOf(ciphertext.size - 10)

            assertFailsWith<CryptoException> {
                Token(derivedKey, crypto).decrypt(truncated)
            }
        }

        @Test
        @DisplayName("Empty ciphertext is rejected")
        fun `empty ciphertext rejected`() {
            assertFailsWith<CryptoException> {
                Token(derivedKey, crypto).decrypt(ByteArray(0))
            }
        }

        @Test
        @DisplayName("Ciphertext too short for HMAC is rejected")
        fun `ciphertext too short for hmac rejected`() {
            // Token format: IV (16) + ciphertext (at least 16) + HMAC (32) = minimum 64 bytes
            val tooShort = ByteArray(32) { it.toByte() }

            assertFailsWith<CryptoException> {
                Token(derivedKey, crypto).decrypt(tooShort)
            }
        }
    }

    @Nested
    @DisplayName("Full ECDH Cycle Tests")
    inner class FullEcdhCycleTests {

        @Test
        @DisplayName("Complete ECDH to encryption cycle matches Python")
        fun `complete ecdh to encryption cycle matches python`() {
            // Generate deterministic keys
            val initiatorSeed = ByteArray(32) { it.toByte() }
            val receiverSeed = ByteArray(32) { (it + 100).toByte() }

            val initiatorKeyPair = crypto.x25519KeyPairFromSeed(initiatorSeed)
            val receiverKeyPair = crypto.x25519KeyPairFromSeed(receiverSeed)

            // ECDH exchange
            val sharedKey = crypto.x25519Exchange(
                initiatorKeyPair.privateKey,
                receiverKeyPair.publicKey
            )

            // Link ID (simulated)
            val linkId = ByteArray(16) { (it * 5).toByte() }

            // Derive keys via HKDF
            val derivedKey = crypto.hkdf(64, sharedKey, linkId, null)

            // Verify Python derives same key
            val pythonKeyResult = python(
                "link_derive_key",
                "shared_key" to sharedKey,
                "link_id" to linkId,
                "mode" to "AES_256_CBC"
            )

            assertBytesEqual(
                pythonKeyResult.getBytes("derived_key"),
                derivedKey,
                "Derived key from ECDH cycle"
            )

            // Test encryption with derived key
            val plaintext = "Message after full ECDH cycle".toByteArray()
            val fixedIv = ByteArray(16) { (it + 77).toByte() }

            val kotlinCiphertext = Token(derivedKey, crypto).encryptWithIv(plaintext, fixedIv)
            val pythonCiphertext = python(
                "link_encrypt",
                "derived_key" to derivedKey,
                "plaintext" to plaintext,
                "iv" to fixedIv
            ).getBytes("ciphertext")

            assertBytesEqual(
                pythonCiphertext,
                kotlinCiphertext,
                "Ciphertext from full ECDH cycle"
            )
        }
    }
}
