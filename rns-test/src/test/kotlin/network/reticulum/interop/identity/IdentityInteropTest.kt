package network.reticulum.interop.identity

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Interoperability tests for Identity operations.
 *
 * Tests that Kotlin Identity implementation produces byte-perfect
 * compatible results with Python RNS Identity.
 */
@DisplayName("Identity Interop")
class IdentityInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Key Generation")
    inner class KeyGeneration {

        @Test
        @DisplayName("Public key derivation matches Python")
        fun publicKeyDerivationMatchesPython() {
            // Create a deterministic 64-byte private key
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)

            // Create identity in Kotlin
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val kotlinPublicKey = identity.getPublicKey()

            // Create identity in Python
            val pyResult = python(
                "identity_from_private_key",
                "private_key" to privateKey
            )

            val pythonPublicKey = pyResult.getBytes("public_key")

            assertBytesEqual(
                pythonPublicKey,
                kotlinPublicKey,
                "Public key derivation should match Python"
            )
        }

        @Test
        @DisplayName("Identity hash matches Python")
        fun identityHashMatchesPython() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)

            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val kotlinHash = identity.hash

            val pyResult = python(
                "identity_from_private_key",
                "private_key" to privateKey
            )

            val pythonHash = pyResult.getBytes("hash")

            assertBytesEqual(
                pythonHash,
                kotlinHash,
                "Identity hash should match Python"
            )
        }

        @Test
        @DisplayName("Identity hexhash matches Python")
        fun identityHexHashMatchesPython() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)

            val identity = Identity.fromPrivateKey(privateKey, crypto)

            val pyResult = python(
                "identity_from_private_key",
                "private_key" to privateKey
            )

            identity.hexHash shouldBe pyResult.getString("hexhash")
        }

        @Test
        @DisplayName("Public-only identity has correct hash")
        fun publicOnlyIdentityHash() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)

            // Create full identity, extract public key
            val fullIdentity = Identity.fromPrivateKey(privateKey, crypto)
            val publicKey = fullIdentity.getPublicKey()

            // Create public-only identity
            val publicIdentity = Identity.fromPublicKey(publicKey, crypto)

            // Hashes should match
            fullIdentity.hash.contentEquals(publicIdentity.hash) shouldBe true
            publicIdentity.hasPrivateKey shouldBe false
        }
    }

    @Nested
    @DisplayName("Encryption/Decryption")
    inner class EncryptionDecryption {

        @Test
        @DisplayName("Kotlin can decrypt Python-encrypted data")
        fun kotlinDecryptsPython() {
            // Generate identity keys
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Have Python encrypt some data
            val plaintext = "Hello from Python!".toByteArray()
            val pyResult = python(
                "identity_encrypt",
                "public_key" to identity.getPublicKey(),
                "plaintext" to plaintext
            )

            val pythonCiphertext = pyResult.getBytes("ciphertext")

            // Kotlin should be able to decrypt it
            val decrypted = identity.decrypt(pythonCiphertext)
            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Python can decrypt Kotlin-encrypted data")
        fun pythonDecryptsKotlin() {
            // Generate identity keys
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Encrypt with Kotlin
            val plaintext = "Hello from Kotlin!".toByteArray()
            val ciphertext = identity.encrypt(plaintext)

            // Python should be able to decrypt it
            val pyResult = python(
                "identity_decrypt",
                "private_key" to privateKey,
                "ciphertext" to ciphertext
            )

            val decryptedHex = pyResult.getString("plaintext")
            val decrypted = decryptedHex.hexToByteArray()

            decrypted.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Encryption with fixed ephemeral key matches Python")
        fun encryptionWithFixedEphemeralMatchesPython() {
            // Use deterministic keys for reproducibility
            val privateKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2".hexToByteArray() +
                    "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef".hexToByteArray()
            val ephemeralPrivate = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1ff8".hexToByteArray()
            val iv = "fedcba9876543210fedcba9876543210".hexToByteArray()
            val plaintext = "Test message for deterministic encryption".toByteArray()

            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Get Python's encryption result with same ephemeral key and IV
            val pyResult = python(
                "identity_encrypt",
                "public_key" to identity.getPublicKey(),
                "plaintext" to plaintext,
                "ephemeral_private" to ephemeralPrivate,
                "iv" to iv
            )

            // Compare intermediate values for debugging
            val pySharedKey = pyResult.getString("shared_key")
            val pyDerivedKey = pyResult.getString("derived_key")
            val pyIdentityHash = pyResult.getString("identity_hash")

            // Verify identity hash matches
            identity.hash.toHex() shouldBe pyIdentityHash

            // Get ephemeral public for Kotlin encryption
            val ephemeralKeyPair = crypto.x25519KeyPairFromSeed(ephemeralPrivate)

            // Compute shared key manually for comparison
            val sharedKey = crypto.x25519Exchange(ephemeralPrivate, identity.getPublicKey().copyOfRange(0, 32))
            sharedKey.toHex() shouldBe pySharedKey

            // Compute derived key manually for comparison
            val derivedKey = crypto.hkdf(
                length = RnsConstants.DERIVED_KEY_LENGTH,
                ikm = sharedKey,
                salt = identity.hash,
                info = null
            )
            derivedKey.toHex() shouldBe pyDerivedKey
        }

        @Test
        @DisplayName("Round-trip encryption works correctly")
        fun roundTripEncryption() {
            val identity = Identity.create(crypto)
            val plaintext = "Secret message for round-trip test".toByteArray()

            val ciphertext = identity.encrypt(plaintext)
            val decrypted = identity.decrypt(ciphertext)

            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Encryption produces expected overhead")
        fun encryptionOverhead() {
            val identity = Identity.create(crypto)
            val plaintext = ByteArray(100) { it.toByte() }

            val ciphertext = identity.encrypt(plaintext)

            // Overhead = ephemeral_pub (32) + IV (16) + padding (up to 16) + HMAC (32)
            // For 100 bytes plaintext: padding = 12 bytes to reach 112 (7 blocks * 16)
            // Total = 32 + 16 + 112 + 32 = 192
            val expectedSize = 32 + RnsConstants.TOKEN_OVERHEAD + ((plaintext.size / 16) + 1) * 16

            ciphertext.size shouldBe expectedSize
        }
    }

    @Nested
    @DisplayName("Signing/Verification")
    inner class SigningVerification {

        @Test
        @DisplayName("Kotlin signature validates in Python")
        fun kotlinSignatureValidatesInPython() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            val message = "Message to sign".toByteArray()
            val signature = identity.sign(message)

            // Verify in Python
            val pyResult = python(
                "identity_verify",
                "public_key" to identity.getPublicKey(),
                "message" to message,
                "signature" to signature
            )

            pyResult.getBoolean("valid") shouldBe true
        }

        @Test
        @DisplayName("Python signature validates in Kotlin")
        fun pythonSignatureValidatesInKotlin() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            val message = "Message to sign".toByteArray()

            // Sign in Python
            val pyResult = python(
                "identity_sign",
                "private_key" to privateKey,
                "message" to message
            )

            val pythonSignature = pyResult.getBytes("signature")

            // Verify in Kotlin
            identity.validate(pythonSignature, message) shouldBe true
        }

        @Test
        @DisplayName("Signature bytes match Python exactly")
        fun signatureBytesMatchPython() {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            val message = "Deterministic message".toByteArray()

            val kotlinSignature = identity.sign(message)

            val pyResult = python(
                "identity_sign",
                "private_key" to privateKey,
                "message" to message
            )

            val pythonSignature = pyResult.getBytes("signature")

            // Ed25519 signatures are deterministic, so they should match exactly
            assertBytesEqual(
                pythonSignature,
                kotlinSignature,
                "Ed25519 signatures should be deterministic and match"
            )
        }

        @Test
        @DisplayName("Invalid signature is rejected")
        fun invalidSignatureRejected() {
            val identity = Identity.create(crypto)
            val message = "Original message".toByteArray()
            val signature = identity.sign(message)

            // Corrupt the signature
            val badSignature = signature.copyOf()
            badSignature[0] = (badSignature[0].toInt() xor 0xFF).toByte()

            identity.validate(badSignature, message) shouldBe false
        }

        @Test
        @DisplayName("Wrong message is rejected")
        fun wrongMessageRejected() {
            val identity = Identity.create(crypto)
            val message = "Original message".toByteArray()
            val signature = identity.sign(message)

            val wrongMessage = "Wrong message".toByteArray()

            identity.validate(signature, wrongMessage) shouldBe false
        }
    }

    @Nested
    @DisplayName("Public-only Identity")
    inner class PublicOnlyIdentity {

        @Test
        @DisplayName("Can encrypt to public-only identity")
        fun canEncryptToPublicOnly() {
            // Create full identity
            val fullIdentity = Identity.create(crypto)

            // Create public-only copy
            val publicIdentity = fullIdentity.toPublicOnly()

            publicIdentity.hasPrivateKey shouldBe false

            // Encrypt to public-only
            val plaintext = "Secret for public-only".toByteArray()
            val ciphertext = publicIdentity.encrypt(plaintext)

            // Decrypt with full identity
            val decrypted = fullIdentity.decrypt(ciphertext)
            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Can verify with public-only identity")
        fun canVerifyWithPublicOnly() {
            val fullIdentity = Identity.create(crypto)
            val publicIdentity = fullIdentity.toPublicOnly()

            val message = "Message to verify".toByteArray()
            val signature = fullIdentity.sign(message)

            publicIdentity.validate(signature, message) shouldBe true
        }
    }
}
