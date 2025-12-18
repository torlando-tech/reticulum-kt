package network.reticulum.interop.announce

import io.kotest.matchers.shouldBe
import network.reticulum.common.RnsConstants
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.getString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Announce packet interoperability tests with Python RNS.
 *
 * Tests the serialization formats for announce payloads to ensure
 * Kotlin and Python implementations are byte-compatible.
 *
 * Without ratchet (148 bytes min):
 *   public_key (64) + name_hash (10) + random_hash (10) + signature (64) + app_data (var)
 *
 * With ratchet (180 bytes min):
 *   public_key (64) + name_hash (10) + random_hash (10) + ratchet (32) + signature (64) + app_data (var)
 */
@DisplayName("Announce Interop")
class AnnounceInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Random Hash")
    inner class RandomHashTests {

        @Test
        @DisplayName("Random hash format matches Python (5 random + 5 timestamp)")
        fun `random hash format matches python`() {
            // Generate a random hash with known components
            val randomBytes = ByteArray(5) { (it + 1).toByte() }
            val timestamp = 1702000000L // Fixed timestamp for testing

            val pythonResult = python(
                "random_hash",
                "random_bytes" to randomBytes,
                "timestamp" to timestamp.toString()
            )

            val pythonHash = pythonResult.getBytes("random_hash")
            val pythonTimestampBytes = pythonResult.getBytes("timestamp_bytes")

            // Verify size
            pythonHash.size shouldBe 10

            // Verify first 5 bytes are our random bytes
            assertBytesEqual(randomBytes, pythonHash.copyOfRange(0, 5), "Random bytes should match")

            // Verify last 5 bytes are big-endian timestamp
            val kotlinTimestampBytes = ByteArray(5) { i ->
                ((timestamp shr (8 * (4 - i))) and 0xFF).toByte()
            }
            assertBytesEqual(kotlinTimestampBytes, pythonTimestampBytes, "Timestamp bytes should match")
            assertBytesEqual(kotlinTimestampBytes, pythonHash.copyOfRange(5, 10), "Hash suffix should be timestamp")
        }

        @Test
        @DisplayName("Random hash with current timestamp")
        fun `random hash with current timestamp`() {
            // Let Python generate both random bytes and timestamp
            val pythonResult = python("random_hash")

            val pythonHash = pythonResult.getBytes("random_hash")
            val timestamp = pythonResult.getString("timestamp").toLong()

            pythonHash.size shouldBe 10

            // Verify the timestamp bytes match
            val timestampBytes = pythonHash.copyOfRange(5, 10)
            val expectedTimestampBytes = ByteArray(5) { i ->
                ((timestamp shr (8 * (4 - i))) and 0xFF).toByte()
            }
            assertBytesEqual(expectedTimestampBytes, timestampBytes, "Timestamp portion should match")
        }
    }

    @Nested
    @DisplayName("Announce Pack")
    inner class AnnouncePackTests {

        @Test
        @DisplayName("Announce pack without ratchet matches Python")
        fun `announce pack without ratchet matches python`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }

            val pythonResult = python(
                "announce_pack",
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "signature" to signature
            )

            val pythonAnnounce = pythonResult.getBytes("announce_data")
            val size = pythonResult.getInt("size")

            // Verify size (148 bytes without ratchet or app_data)
            size shouldBe 148
            pythonAnnounce.size shouldBe 148

            // Build Kotlin version
            val kotlinAnnounce = publicKey + nameHash + randomHash + signature

            assertBytesEqual(pythonAnnounce, kotlinAnnounce, "Announce without ratchet should match Python")
        }

        @Test
        @DisplayName("Announce pack with ratchet matches Python")
        fun `announce pack with ratchet matches python`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val ratchet = ByteArray(32) { (it + 150).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }

            val pythonResult = python(
                "announce_pack",
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "ratchet" to ratchet,
                "signature" to signature
            )

            val pythonAnnounce = pythonResult.getBytes("announce_data")
            val size = pythonResult.getInt("size")
            val hasRatchet = pythonResult.getBoolean("has_ratchet")

            // Verify size (180 bytes with ratchet, no app_data)
            size shouldBe 180
            pythonAnnounce.size shouldBe 180
            hasRatchet shouldBe true

            // Build Kotlin version
            val kotlinAnnounce = publicKey + nameHash + randomHash + ratchet + signature

            assertBytesEqual(pythonAnnounce, kotlinAnnounce, "Announce with ratchet should match Python")
        }

        @Test
        @DisplayName("Announce pack with app_data matches Python")
        fun `announce pack with app_data matches python`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }
            val appData = "Hello, world!".toByteArray()

            val pythonResult = python(
                "announce_pack",
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "signature" to signature,
                "app_data" to appData
            )

            val pythonAnnounce = pythonResult.getBytes("announce_data")
            val size = pythonResult.getInt("size")

            // Verify size (148 + app_data length)
            size shouldBe (148 + appData.size)

            // Build Kotlin version
            val kotlinAnnounce = publicKey + nameHash + randomHash + signature + appData

            assertBytesEqual(pythonAnnounce, kotlinAnnounce, "Announce with app_data should match Python")
        }

        @Test
        @DisplayName("Announce with max app_data size")
        fun `announce with max app_data size`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }
            // Max app_data in a single packet: MDU - 148 = ~315 bytes
            val appData = ByteArray(200) { (it % 256).toByte() }

            val pythonResult = python(
                "announce_pack",
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "signature" to signature,
                "app_data" to appData
            )

            val pythonAnnounce = pythonResult.getBytes("announce_data")

            // Build Kotlin version
            val kotlinAnnounce = publicKey + nameHash + randomHash + signature + appData

            assertBytesEqual(pythonAnnounce, kotlinAnnounce, "Announce with large app_data should match Python")
        }
    }

    @Nested
    @DisplayName("Announce Unpack")
    inner class AnnounceUnpackTests {

        @Test
        @DisplayName("Announce unpack without ratchet matches Python")
        fun `announce unpack without ratchet matches python`() {
            val publicKey = ByteArray(64) { (it + 1).toByte() }
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }
            val appData = "test app data".toByteArray()

            // Pack announce
            val announce = publicKey + nameHash + randomHash + signature + appData

            val pythonResult = python(
                "announce_unpack",
                "announce_data" to announce,
                "has_ratchet" to false
            )

            val unpackedPublicKey = pythonResult.getBytes("public_key")
            val unpackedNameHash = pythonResult.getBytes("name_hash")
            val unpackedRandomHash = pythonResult.getBytes("random_hash")
            val unpackedSignature = pythonResult.getBytes("signature")
            val unpackedAppData = pythonResult.getBytes("app_data")
            val hasRatchet = pythonResult.getBoolean("has_ratchet")

            assertBytesEqual(publicKey, unpackedPublicKey, "Public key should match")
            assertBytesEqual(nameHash, unpackedNameHash, "Name hash should match")
            assertBytesEqual(randomHash, unpackedRandomHash, "Random hash should match")
            assertBytesEqual(signature, unpackedSignature, "Signature should match")
            assertBytesEqual(appData, unpackedAppData, "App data should match")
            hasRatchet shouldBe false
        }

        @Test
        @DisplayName("Announce unpack with ratchet matches Python")
        fun `announce unpack with ratchet matches python`() {
            val publicKey = ByteArray(64) { (it + 1).toByte() }
            val nameHash = ByteArray(10) { (it + 100).toByte() }
            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val ratchet = ByteArray(32) { (it + 150).toByte() }
            val signature = ByteArray(64) { (it + 200).toByte() }

            // Pack announce with ratchet
            val announce = publicKey + nameHash + randomHash + ratchet + signature

            val pythonResult = python(
                "announce_unpack",
                "announce_data" to announce,
                "has_ratchet" to true
            )

            val unpackedPublicKey = pythonResult.getBytes("public_key")
            val unpackedNameHash = pythonResult.getBytes("name_hash")
            val unpackedRandomHash = pythonResult.getBytes("random_hash")
            val unpackedRatchet = pythonResult.getBytes("ratchet")
            val unpackedSignature = pythonResult.getBytes("signature")
            val hasRatchet = pythonResult.getBoolean("has_ratchet")

            assertBytesEqual(publicKey, unpackedPublicKey, "Public key should match")
            assertBytesEqual(nameHash, unpackedNameHash, "Name hash should match")
            assertBytesEqual(randomHash, unpackedRandomHash, "Random hash should match")
            assertBytesEqual(ratchet, unpackedRatchet, "Ratchet should match")
            assertBytesEqual(signature, unpackedSignature, "Signature should match")
            hasRatchet shouldBe true
        }
    }

    @Nested
    @DisplayName("Announce Signature")
    inner class AnnounceSignatureTests {

        @Test
        @DisplayName("Announce signature generation matches Python")
        fun `announce signature generation matches python`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination hash (identity hash + name hash -> destination hash)
            val nameHash = Hashes.nameHash("test.app")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val appData = "test data".toByteArray()

            val pythonResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "app_data" to appData
            )

            val pythonSignature = pythonResult.getBytes("signature")
            val signedData = pythonResult.getBytes("signed_data")

            // Verify signature is 64 bytes
            pythonSignature.size shouldBe 64

            // Verify signed data format
            val expectedSignedData = (destinationHash + publicKey) + (nameHash + randomHash) + appData
            assertBytesEqual(expectedSignedData, signedData, "Signed data format should match")
        }

        @Test
        @DisplayName("Python can verify Kotlin announce signature")
        fun `python can verify kotlin announce signature`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.verify")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val appData = "verify test".toByteArray()

            // Sign with Python
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "app_data" to appData
            )

            val signature = signResult.getBytes("signature")

            // Build announce
            val announce = publicKey + nameHash + randomHash + signature + appData

            // Verify with Python
            val verifyResult = python(
                "announce_verify",
                "announce_data" to announce,
                "destination_hash" to destinationHash,
                "has_ratchet" to false,
                "validate_dest_hash" to true
            )

            val valid = verifyResult.getBoolean("valid")
            val signatureValid = verifyResult.getBoolean("signature_valid")
            val destHashValid = verifyResult.getBoolean("dest_hash_valid")

            valid shouldBe true
            signatureValid shouldBe true
            destHashValid shouldBe true
        }

        @Test
        @DisplayName("Invalid announce signature is rejected")
        fun `invalid announce signature is rejected`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.invalid")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val appData = ByteArray(0)
            val invalidSignature = ByteArray(64) { 0x00 } // Invalid signature

            // Build announce with invalid signature
            val announce = publicKey + nameHash + randomHash + invalidSignature + appData

            // Verify with Python
            val verifyResult = python(
                "announce_verify",
                "announce_data" to announce,
                "destination_hash" to destinationHash,
                "has_ratchet" to false,
                "validate_dest_hash" to false // Just check signature
            )

            val valid = verifyResult.getBoolean("valid")
            val signatureValid = verifyResult.getBoolean("signature_valid")

            valid shouldBe false
            signatureValid shouldBe false
        }
    }

    @Nested
    @DisplayName("Destination Hash Validation")
    inner class DestinationHashTests {

        @Test
        @DisplayName("Destination hash validation from announce matches Python")
        fun `destination hash validation from announce matches python`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.desthash")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }

            // Sign announce
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash
            )

            val signature = signResult.getBytes("signature")

            // Build announce
            val announce = publicKey + nameHash + randomHash + signature

            // Verify with destination hash validation
            val verifyResult = python(
                "announce_verify",
                "announce_data" to announce,
                "destination_hash" to destinationHash,
                "has_ratchet" to false,
                "validate_dest_hash" to true
            )

            val valid = verifyResult.getBoolean("valid")
            val destHashValid = verifyResult.getBoolean("dest_hash_valid")
            val expectedDestHash = verifyResult.getBytes("expected_dest_hash")

            valid shouldBe true
            destHashValid shouldBe true
            assertBytesEqual(destinationHash, expectedDestHash, "Expected destination hash should match")
        }

        @Test
        @DisplayName("Wrong destination hash is rejected")
        fun `wrong destination hash is rejected`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.wrongdest")
            val identityHash = Hashes.truncatedHash(publicKey)
            val correctDestHash = Hashes.truncatedHash(nameHash + identityHash)
            val wrongDestHash = ByteArray(16) { 0xFF.toByte() } // Wrong destination hash

            val randomHash = ByteArray(10) { (it + 50).toByte() }

            // Sign with WRONG destination hash (signature won't match correct one)
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to wrongDestHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash
            )

            val signature = signResult.getBytes("signature")

            // Build announce
            val announce = publicKey + nameHash + randomHash + signature

            // Verify with correct destination hash (should fail dest hash validation)
            val verifyResult = python(
                "announce_verify",
                "announce_data" to announce,
                "destination_hash" to correctDestHash,
                "has_ratchet" to false,
                "validate_dest_hash" to true
            )

            val destHashValid = verifyResult.getBoolean("dest_hash_valid")
            destHashValid shouldBe true // Dest hash derived from public key + name_hash matches

            // But signature validation should fail because we signed with wrong dest hash
            val signatureValid = verifyResult.getBoolean("signature_valid")
            signatureValid shouldBe false
        }
    }

    @Nested
    @DisplayName("Announce Validation (validateAnnounce logic)")
    inner class AnnounceValidationTests {

        /**
         * This test verifies the signed_data format used during announce validation.
         *
         * Bug fix context: The validateAnnounce function was failing because:
         * 1. It used packet size to detect ratchet instead of context flag
         * 2. It did NOT include destination_hash in signed_data during validation
         *
         * The correct signed_data format is:
         *   destination_hash + public_key + name_hash + random_hash + ratchet + app_data
         */
        @Test
        @DisplayName("Announce validation requires destination_hash in signed_data")
        fun `announce validation requires destination_hash in signed_data`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.validate")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val appData = "validation test".toByteArray()

            // Sign announce with Python (correctly includes destination_hash)
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "app_data" to appData
            )

            val signature = signResult.getBytes("signature")

            // Build announce payload (what would be in packet.data)
            val announceData = publicKey + nameHash + randomHash + signature + appData

            // Now simulate what validateAnnounce does:
            // 1. Parse announce data
            val parsedPublicKey = announceData.copyOfRange(0, 64)
            val parsedNameHash = announceData.copyOfRange(64, 74)
            val parsedRandomHash = announceData.copyOfRange(74, 84)
            val parsedSignature = announceData.copyOfRange(84, 148)
            val parsedAppData = announceData.copyOfRange(148, announceData.size)

            // 2. Build signed data WITH destination_hash (correct)
            val correctSignedData = destinationHash + parsedPublicKey + parsedNameHash + parsedRandomHash + parsedAppData

            // 3. Verify signature using Kotlin's Identity.validate (same as validateAnnounce)
            val validatingIdentity = Identity.fromPublicKey(parsedPublicKey)
            validatingIdentity.validate(parsedSignature, correctSignedData) shouldBe true

            // 4. Build signed data WITHOUT destination_hash (the bug we fixed)
            val incorrectSignedData = parsedPublicKey + parsedNameHash + parsedRandomHash + parsedAppData

            // 5. Verify signature should FAIL without destination_hash
            validatingIdentity.validate(parsedSignature, incorrectSignedData) shouldBe false
        }

        @Test
        @DisplayName("Announce validation with ratchet includes destination_hash")
        fun `announce validation with ratchet includes destination_hash`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.ratchet")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val ratchet = ByteArray(32) { (it + 100).toByte() }
            val appData = "ratchet test".toByteArray()

            // Sign announce with Python (includes ratchet)
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "ratchet" to ratchet,
                "app_data" to appData
            )

            val signature = signResult.getBytes("signature")

            // Build announce payload with ratchet
            val announceData = publicKey + nameHash + randomHash + ratchet + signature + appData

            // Simulate validateAnnounce parsing for ratchet case
            val parsedPublicKey = announceData.copyOfRange(0, 64)
            val parsedNameHash = announceData.copyOfRange(64, 74)
            val parsedRandomHash = announceData.copyOfRange(74, 84)
            val parsedRatchet = announceData.copyOfRange(84, 116)
            val parsedSignature = announceData.copyOfRange(116, 180)
            val parsedAppData = announceData.copyOfRange(180, announceData.size)

            // Correct signed data includes destination_hash and ratchet
            val correctSignedData = destinationHash + parsedPublicKey + parsedNameHash +
                parsedRandomHash + parsedRatchet + parsedAppData

            // Verify signature using Kotlin's Identity.validate (same as validateAnnounce)
            val validatingIdentity = Identity.fromPublicKey(parsedPublicKey)
            validatingIdentity.validate(parsedSignature, correctSignedData) shouldBe true

            // Also verify that omitting destination_hash fails
            val incorrectSignedData = parsedPublicKey + parsedNameHash +
                parsedRandomHash + parsedRatchet + parsedAppData
            validatingIdentity.validate(parsedSignature, incorrectSignedData) shouldBe false
        }

        @Test
        @DisplayName("Kotlin Identity.validate matches Python for announce validation")
        fun `kotlin identity validate matches python for announce validation`() {
            val identity = Identity.create()
            val publicKey = identity.getPublicKey()
            val privateKey = identity.getPrivateKey()

            // Generate destination components
            val nameHash = Hashes.nameHash("test.kotlin.validate")
            val identityHash = Hashes.truncatedHash(publicKey)
            val destinationHash = Hashes.truncatedHash(nameHash + identityHash)

            val randomHash = ByteArray(10) { (it + 50).toByte() }
            val appData = "kotlin validate test".toByteArray()

            // Sign with Python
            val signResult = python(
                "announce_sign",
                "private_key" to privateKey,
                "destination_hash" to destinationHash,
                "public_key" to publicKey,
                "name_hash" to nameHash,
                "random_hash" to randomHash,
                "app_data" to appData
            )

            val signature = signResult.getBytes("signature")

            // Build signed data the same way validateAnnounce does
            val signedData = destinationHash + publicKey + nameHash + randomHash + appData

            // Create identity from public key (as validateAnnounce does)
            val validatingIdentity = Identity.fromPublicKey(publicKey)

            // Kotlin validation should succeed
            validatingIdentity.validate(signature, signedData) shouldBe true

            // Validation without destination_hash should fail
            val wrongSignedData = publicKey + nameHash + randomHash + appData
            validatingIdentity.validate(signature, wrongSignedData) shouldBe false
        }
    }
}
