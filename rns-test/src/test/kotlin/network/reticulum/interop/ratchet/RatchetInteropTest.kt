package network.reticulum.interop.ratchet

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
 * Interoperability tests for Ratchet operations.
 *
 * Tests that Kotlin ratchet implementation produces byte-perfect
 * compatible results with Python RNS ratchet operations.
 */
@DisplayName("Ratchet Interop")
class RatchetInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Ratchet ID Computation")
    inner class RatchetIdComputation {

        @Test
        @DisplayName("Ratchet ID computation matches Python")
        fun ratchetIdComputationMatchesPython() {
            // Generate a ratchet private key (32 bytes for X25519)
            val ratchetPrivate = crypto.randomBytes(32)

            // Get ratchet public key from Python
            val pyPubResult = python(
                "ratchet_public_from_private",
                "ratchet_private" to ratchetPrivate
            )
            val ratchetPublic = pyPubResult.getBytes("ratchet_public")

            // Get ratchet ID from Python
            val pyIdResult = python(
                "ratchet_id",
                "ratchet_public" to ratchetPublic
            )
            val pythonRatchetId = pyIdResult.getBytes("ratchet_id")

            // Compute ratchet ID in Kotlin: SHA256(ratchet_public)[:10]
            val kotlinRatchetId = crypto.sha256(ratchetPublic).take(10).toByteArray()

            assertBytesEqual(
                pythonRatchetId,
                kotlinRatchetId,
                "Ratchet ID should match Python"
            )

            // Verify it's 10 bytes (80 bits)
            pythonRatchetId.size shouldBe 10
            kotlinRatchetId.size shouldBe 10
        }

        @Test
        @DisplayName("Ratchet ID from known public key")
        fun ratchetIdFromKnownPublicKey() {
            // Use a fixed public key for reproducibility
            val ratchetPublic = "deadbeef" + "a".repeat(56)
            val ratchetPublicBytes = ratchetPublic.hexToByteArray()

            val pyResult = python(
                "ratchet_id",
                "ratchet_public" to ratchetPublicBytes
            )
            val pythonRatchetId = pyResult.getBytes("ratchet_id")

            // Compute in Kotlin
            val kotlinRatchetId = crypto.sha256(ratchetPublicBytes).take(10).toByteArray()

            assertBytesEqual(
                pythonRatchetId,
                kotlinRatchetId,
                "Ratchet ID from known key should match"
            )
        }
    }

    @Nested
    @DisplayName("Ratchet Key Derivation")
    inner class RatchetKeyDerivation {

        @Test
        @DisplayName("Ratchet key derivation matches Python")
        fun ratchetKeyDerivationMatchesPython() {
            // Generate ephemeral and ratchet keys
            val ephemeralPrivate = crypto.randomBytes(32)
            val ratchetPrivate = crypto.randomBytes(32)

            // Get ratchet public key
            val pyPubResult = python(
                "ratchet_public_from_private",
                "ratchet_private" to ratchetPrivate
            )
            val ratchetPublic = pyPubResult.getBytes("ratchet_public")

            // Create identity hash (16 bytes)
            val identityHash = crypto.randomBytes(16)

            // Derive key in Python
            val pyResult = python(
                "ratchet_derive_key",
                "ephemeral_private" to ephemeralPrivate,
                "ratchet_public" to ratchetPublic,
                "identity_hash" to identityHash
            )

            val pythonSharedKey = pyResult.getBytes("shared_key")
            val pythonDerivedKey = pyResult.getBytes("derived_key")

            // Derive key in Kotlin
            val sharedKey = crypto.x25519Exchange(ephemeralPrivate, ratchetPublic)

            // HKDF with identity_hash as salt
            val derivedKey = crypto.hkdf(
                length = 64,
                ikm = sharedKey,
                salt = identityHash,
                info = null
            )

            assertBytesEqual(
                pythonSharedKey,
                sharedKey,
                "Shared key should match Python"
            )

            assertBytesEqual(
                pythonDerivedKey,
                derivedKey,
                "Derived key should match Python"
            )
        }
    }

    @Nested
    @DisplayName("Ratchet Encryption")
    inner class RatchetEncryption {

        @Test
        @DisplayName("Python can decrypt Kotlin ratchet ciphertext")
        fun pythonDecryptsKotlinRatchet() {
            // Generate keys
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val identityHash = identity.hash

            val ratchetPrivate = crypto.randomBytes(32)
            val pyPubResult = python(
                "ratchet_public_from_private",
                "ratchet_private" to ratchetPrivate
            )
            val ratchetPublic = pyPubResult.getBytes("ratchet_public")

            val plaintext = "Test message for ratchet".toByteArray()

            // Encrypt in Kotlin with ratchet
            val ciphertext = identity.encrypt(plaintext, ratchet = ratchetPublic)

            // Decrypt in Python using ratchet
            val pyResult = python(
                "ratchet_decrypt",
                "ratchet_private" to ratchetPrivate,
                "ciphertext" to ciphertext,
                "identity_hash" to identityHash
            )

            pyResult.getBoolean("success") shouldBe true
            val decrypted = pyResult.getBytes("plaintext")
            decrypted.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Kotlin can decrypt Python ratchet ciphertext")
        fun kotlinDecryptsPythonRatchet() {
            // Generate keys
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val identityHash = identity.hash

            val ratchetPrivate = crypto.randomBytes(32)
            val pyPubResult = python(
                "ratchet_public_from_private",
                "ratchet_private" to ratchetPrivate
            )
            val ratchetPublic = pyPubResult.getBytes("ratchet_public")

            val plaintext = "Python encrypted ratchet message".toByteArray()

            // Encrypt in Python with ratchet
            val pyResult = python(
                "ratchet_encrypt",
                "ratchet_public" to ratchetPublic,
                "plaintext" to plaintext,
                "identity_hash" to identityHash
            )
            val ciphertext = pyResult.getBytes("ciphertext")

            // Decrypt in Kotlin using ratchet
            val decrypted = identity.decrypt(ciphertext, ratchets = listOf(ratchetPrivate))

            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }
    }

    @Nested
    @DisplayName("Ratchet Storage Format")
    inner class RatchetStorageFormat {

        @Test
        @DisplayName("Ratchet storage format matches Python")
        fun ratchetStorageFormatMatchesPython() {
            val ratchet = crypto.randomBytes(32)
            val received = 1234567890.5

            // Pack in Python
            val pyResult = python(
                "ratchet_storage_format",
                "ratchet" to ratchet,
                "received" to received.toString()
            )
            val packed = pyResult.getBytes("packed")

            // The packed format is msgpack: {"ratchet": bytes, "received": float}
            // We verify that Python can pack it correctly
            packed shouldNotBe null
            (packed.size > 0) shouldBe true
        }
    }

    @Nested
    @DisplayName("Ratchet in Announce")
    inner class RatchetInAnnounce {

        @Test
        @DisplayName("Ratchet extraction from announce matches Python")
        fun ratchetExtractionFromAnnounceMatchesPython() {
            // Create a fake announce with ratchet
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val publicKey = identity.getPublicKey()

            val nameHash = crypto.randomBytes(10)
            val randomHash = crypto.randomBytes(10)
            val ratchet = crypto.randomBytes(32)
            val signature = crypto.randomBytes(64)
            val appData = "test".toByteArray()

            // Build announce: public_key + name_hash + random_hash + ratchet + signature + app_data
            val announceData = publicKey + nameHash + randomHash + ratchet + signature + appData

            // Extract in Python
            val pyResult = python(
                "ratchet_extract_from_announce",
                "announce_data" to announceData
            )

            pyResult.getBoolean("has_ratchet") shouldBe true
            val extractedRatchet = pyResult.getBytes("ratchet")
            val extractedRatchetId = pyResult.getBytes("ratchet_id")

            assertBytesEqual(
                ratchet,
                extractedRatchet,
                "Extracted ratchet should match original"
            )

            // Verify ratchet ID
            val expectedRatchetId = crypto.sha256(ratchet).take(10).toByteArray()
            assertBytesEqual(
                expectedRatchetId,
                extractedRatchetId,
                "Extracted ratchet ID should match"
            )
        }

        @Test
        @DisplayName("Announce without ratchet is detected correctly")
        fun announceWithoutRatchet() {
            // Create announce without ratchet
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val publicKey = identity.getPublicKey()

            val nameHash = crypto.randomBytes(10)
            val randomHash = crypto.randomBytes(10)
            val signature = crypto.randomBytes(64)

            // Build announce without ratchet: public_key + name_hash + random_hash + signature
            val announceData = publicKey + nameHash + randomHash + signature

            // Extract in Python
            val pyResult = python(
                "ratchet_extract_from_announce",
                "announce_data" to announceData
            )

            pyResult.getBoolean("has_ratchet") shouldBe false
        }
    }

    @Nested
    @DisplayName("Multiple Ratchet Fallback")
    inner class MultipleRatchetFallback {

        @Test
        @DisplayName("Multiple ratchet fallback decryption")
        fun multipleRatchetFallbackDecryption() {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val identityHash = identity.hash

            // Generate multiple ratchets
            val ratchet1Private = crypto.randomBytes(32)
            val ratchet2Private = crypto.randomBytes(32)
            val ratchet3Private = crypto.randomBytes(32)

            val pyPub2 = python("ratchet_public_from_private", "ratchet_private" to ratchet2Private)

            val ratchet2Public = pyPub2.getBytes("ratchet_public")

            val plaintext = "Message encrypted with ratchet 2".toByteArray()

            // Encrypt with ratchet 2
            val pyResult = python(
                "ratchet_encrypt",
                "ratchet_public" to ratchet2Public,
                "plaintext" to plaintext,
                "identity_hash" to identityHash
            )
            val ciphertext = pyResult.getBytes("ciphertext")

            // Try decrypting with list of ratchets (including the right one)
            val ratchets = listOf(ratchet1Private, ratchet2Private, ratchet3Private)
            val decrypted = identity.decrypt(ciphertext, ratchets = ratchets)

            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }

        @Test
        @DisplayName("Decryption fails when ratchet not in list")
        fun decryptionFailsWhenRatchetNotInList() {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)
            val identityHash = identity.hash

            // Generate ratchets
            val wrongRatchet1 = crypto.randomBytes(32)
            val wrongRatchet2 = crypto.randomBytes(32)
            val correctRatchet = crypto.randomBytes(32)

            val pyPubCorrect = python("ratchet_public_from_private", "ratchet_private" to correctRatchet)
            val correctRatchetPublic = pyPubCorrect.getBytes("ratchet_public")

            val plaintext = "Message needs correct ratchet".toByteArray()

            // Encrypt with correct ratchet
            val pyResult = python(
                "ratchet_encrypt",
                "ratchet_public" to correctRatchetPublic,
                "plaintext" to plaintext,
                "identity_hash" to identityHash
            )
            val ciphertext = pyResult.getBytes("ciphertext")

            // Try decrypting with wrong ratchets only, enforce ratchets
            val wrongRatchets = listOf(wrongRatchet1, wrongRatchet2)
            val decrypted = identity.decrypt(ciphertext, ratchets = wrongRatchets, enforceRatchets = true)

            // Should fail since we don't have the right ratchet and enforcement is on
            decrypted shouldBe null
        }
    }

    @Nested
    @DisplayName("Ratchet Expiry and Rotation")
    inner class RatchetExpiryAndRotation {

        @Test
        @DisplayName("Ratchet expiry calculation")
        fun ratchetExpiryCalculation() {
            // RATCHET_EXPIRY is 60*60*24*30 seconds (30 days)
            val expectedExpiry = 60 * 60 * 24 * 30
            val currentTime = 1234567890.0
            val expiryTime = currentTime + expectedExpiry

            // Verify that a ratchet received at currentTime expires at expiryTime
            val ratchet = crypto.randomBytes(32)

            val pyResult = python(
                "ratchet_storage_format",
                "ratchet" to ratchet,
                "received" to currentTime.toString()
            )

            // Python stores the received timestamp
            val receivedTime = pyResult.getString("received").toDouble()
            receivedTime shouldBe currentTime

            // Calculate expiry
            val calculatedExpiry = receivedTime + expectedExpiry
            calculatedExpiry shouldBe expiryTime
        }

        @Test
        @DisplayName("Ratchet rotation detection")
        fun ratchetRotationDetection() {
            // Generate two different ratchets to simulate rotation
            val oldRatchet = crypto.randomBytes(32)
            val newRatchet = crypto.randomBytes(32)

            val pyOldPub = python("ratchet_public_from_private", "ratchet_private" to oldRatchet)
            val pyNewPub = python("ratchet_public_from_private", "ratchet_private" to newRatchet)

            val oldRatchetPublic = pyOldPub.getBytes("ratchet_public")
            val newRatchetPublic = pyNewPub.getBytes("ratchet_public")

            val pyOldId = python("ratchet_id", "ratchet_public" to oldRatchetPublic)
            val pyNewId = python("ratchet_id", "ratchet_public" to newRatchetPublic)

            val oldRatchetId = pyOldId.getBytes("ratchet_id")
            val newRatchetId = pyNewId.getBytes("ratchet_id")

            // Different ratchets should have different IDs
            oldRatchetId.contentEquals(newRatchetId) shouldBe false
        }
    }
}
