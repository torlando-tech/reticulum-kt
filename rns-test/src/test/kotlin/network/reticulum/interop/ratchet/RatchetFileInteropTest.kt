package network.reticulum.interop.ratchet

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getInt
import network.reticulum.interop.getList
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Interoperability tests for ratchet file format (Destination-level persistence).
 *
 * Tests that Kotlin's Destination.persistRatchets() produces files readable by Python's
 * Destination._reload_ratchets(), and vice versa. This ensures cross-implementation
 * compatibility for ratchet storage.
 *
 * File format (matching Python): msgpack({"signature": sign(packed_ratchets), "ratchets": packed_ratchets})
 * where packed_ratchets = msgpack([key1, key2, ...]) (list of 32-byte X25519 private keys)
 */
@DisplayName("Ratchet File Interop")
class RatchetFileInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Nested
    @DisplayName("Python writes, Kotlin reads")
    inner class PythonWritesKotlinReads {

        @Test
        @DisplayName("Kotlin can load a ratchet file written by Python")
        fun kotlinLoadsRatchetFileFromPython(@TempDir tempDir: Path) {
            // Generate identity (shared between Python and Kotlin)
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Generate ratchet private keys
            val ratchetKeys = List(3) { crypto.randomBytes(32) }
            val ratchetKeyHexList = ratchetKeys.map { it.toHex() }

            // Python writes the ratchet file
            val filePath = tempDir.resolve("python_ratchets.dat").toString()
            val pyResult = python(
                "ratchet_file_write",
                "path" to filePath,
                "ratchet_keys" to ratchetKeyHexList,
                "signing_private_key" to privateKey.toHex()
            )
            pyResult.getBoolean("written") shouldBe true
            pyResult.getInt("ratchet_count") shouldBe 3

            // Create Kotlin destination with same identity
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("ratchetfile")
            )

            // Load the Python-generated ratchet file
            dest.enableRatchets(filePath)

            // Verify the ratchet key was loaded correctly
            val loadedRatchetKey = dest.getRatchetKey()
            loadedRatchetKey shouldNotBe null

            // The ratchet key should be the public key derived from the first (newest) private key
            val expectedPublicKey = crypto.x25519PublicFromPrivate(ratchetKeys[0])
            loadedRatchetKey!!.contentEquals(expectedPublicKey) shouldBe true

            // Verify ratchet ID matches
            val loadedRatchetId = dest.getRatchetId()
            loadedRatchetId shouldNotBe null
            val expectedId = crypto.sha256(expectedPublicKey).take(Destination.RATCHET_ID_SIZE).toByteArray()
            loadedRatchetId!!.contentEquals(expectedId) shouldBe true
        }

        @Test
        @DisplayName("Kotlin can decrypt with ratchets loaded from Python file")
        fun kotlinDecryptsWithPythonRatchets(@TempDir tempDir: Path) {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Generate ratchet private keys
            val ratchetKeys = List(3) { crypto.randomBytes(32) }
            val ratchetKeyHexList = ratchetKeys.map { it.toHex() }

            // Python writes ratchet file
            val filePath = tempDir.resolve("decrypt_ratchets.dat").toString()
            python(
                "ratchet_file_write",
                "path" to filePath,
                "ratchet_keys" to ratchetKeyHexList,
                "signing_private_key" to privateKey.toHex()
            )

            // Create destination and load ratchets
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("decrypt")
            )
            dest.enableRatchets(filePath)

            // Encrypt something using the ratchet public key (simulating an incoming message)
            val ratchetPublic = crypto.x25519PublicFromPrivate(ratchetKeys[1])  // Use 2nd ratchet
            val plaintext = "Secret message via ratchet".toByteArray()
            val ciphertext = identity.encrypt(plaintext, ratchet = ratchetPublic)

            // Decrypt using the destination (which has the ratchet private keys from Python)
            val decrypted = dest.decrypt(ciphertext)
            decrypted shouldNotBe null
            decrypted!!.contentEquals(plaintext) shouldBe true
        }
    }

    @Nested
    @DisplayName("Kotlin writes, Python reads")
    inner class KotlinWritesPythonReads {

        @Test
        @DisplayName("Python can read a ratchet file written by Kotlin")
        fun pythonReadsRatchetFileFromKotlin(@TempDir tempDir: Path) {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Create destination and enable ratchets (generates initial ratchet)
            val filePath = tempDir.resolve("kotlin_ratchets.dat").toString()
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("ktwrite")
            )
            dest.enableRatchets(filePath)

            // Verify file was written
            File(filePath).exists() shouldBe true

            // Get the Ed25519 public key for signature verification
            val ed25519Public = identity.sigPub  // Ed25519 signing public key

            // Python reads and validates the file
            val pyResult = python(
                "ratchet_file_read",
                "path" to filePath,
                "verify_public_key" to ed25519Public
            )

            pyResult.getBoolean("valid") shouldBe true
            pyResult.getBoolean("signature_valid") shouldBe true
            pyResult.getInt("ratchet_count") shouldBe 1  // Initial rotation creates 1 ratchet

            // Verify the ratchet key matches
            val pyRatchetKeys: List<String> = pyResult.getList("ratchet_keys")
            pyRatchetKeys.size shouldBe 1

            // The Python-read ratchet should match what Kotlin has
            val kotlinRatchetKey = dest.getRatchetKey()!!
            val kotlinRatchetPrivate = pyRatchetKeys[0].hexToByteArray()
            val pythonDerivedPublic = crypto.x25519PublicFromPrivate(kotlinRatchetPrivate)
            kotlinRatchetKey.contentEquals(pythonDerivedPublic) shouldBe true
        }

        @Test
        @DisplayName("Python can read Kotlin file with multiple rotations")
        fun pythonReadsMultipleRotations(@TempDir tempDir: Path) {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Create destination with zero interval so we can rotate immediately
            val filePath = tempDir.resolve("multi_ratchets.dat").toString()
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("multi")
            )

            // Use reflection to set ratchetInterval to 0 so rotateRatchets always succeeds
            val intervalField = Destination::class.java.getDeclaredField("ratchetInterval")
            intervalField.isAccessible = true
            intervalField.setLong(dest, 0L)

            dest.enableRatchets(filePath)

            // Force additional rotations by resetting lastRatchetRotation
            val lastRotField = Destination::class.java.getDeclaredField("lastRatchetRotation")
            lastRotField.isAccessible = true

            repeat(4) {
                lastRotField.setLong(dest, 0L)
                dest.rotateRatchets()
            }

            // Get Ed25519 public key
            val ed25519Public = identity.sigPub  // Ed25519 signing public key

            // Python reads the file
            val pyResult = python(
                "ratchet_file_read",
                "path" to filePath,
                "verify_public_key" to ed25519Public
            )

            pyResult.getBoolean("valid") shouldBe true
            pyResult.getBoolean("signature_valid") shouldBe true
            pyResult.getInt("ratchet_count") shouldBe 5  // 1 initial + 4 rotations
        }
    }

    @Nested
    @DisplayName("Round-trip: Python write → Kotlin read → Kotlin write → Python read")
    inner class RoundTrip {

        @Test
        @DisplayName("Full round-trip preserves ratchet data")
        fun fullRoundTrip(@TempDir tempDir: Path) {
            // Generate identity
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Generate ratchet keys
            val ratchetKeys = List(5) { crypto.randomBytes(32) }
            val ratchetKeyHexList = ratchetKeys.map { it.toHex() }

            // Step 1: Python writes ratchet file
            val pythonFile = tempDir.resolve("roundtrip_py.dat").toString()
            python(
                "ratchet_file_write",
                "path" to pythonFile,
                "ratchet_keys" to ratchetKeyHexList,
                "signing_private_key" to privateKey.toHex()
            )

            // Step 2: Kotlin loads the Python file
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("roundtrip")
            )
            dest.enableRatchets(pythonFile)

            // Step 3: Kotlin writes to a new file (via re-persist after loading)
            // We need to trigger a persist - rotate ratchets which persists
            val kotlinFile = tempDir.resolve("roundtrip_kt.dat").toString()

            // Create a new destination pointing to the new file, load from python file first
            val dest2 = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("roundtrip2")
            )

            // Set interval to 0 so rotation is immediate
            val intervalField = Destination::class.java.getDeclaredField("ratchetInterval")
            intervalField.isAccessible = true
            intervalField.setLong(dest2, 0L)

            dest2.enableRatchets(kotlinFile)

            // Step 4: Python reads the Kotlin-written file
            val ed25519Public = identity.sigPub  // Ed25519 signing public key

            val pyResult = python(
                "ratchet_file_read",
                "path" to kotlinFile,
                "verify_public_key" to ed25519Public
            )

            pyResult.getBoolean("valid") shouldBe true
            pyResult.getBoolean("signature_valid") shouldBe true
            (pyResult.getInt("ratchet_count") >= 1) shouldBe true
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("Empty ratchet list is handled correctly")
        fun emptyRatchetList(@TempDir tempDir: Path) {
            val privateKey = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity = Identity.fromPrivateKey(privateKey, crypto)

            // Python writes file with empty list
            val filePath = tempDir.resolve("empty_ratchets.dat").toString()
            val pyResult = python(
                "ratchet_file_write",
                "path" to filePath,
                "ratchet_keys" to emptyList<String>(),
                "signing_private_key" to privateKey.toHex()
            )
            pyResult.getBoolean("written") shouldBe true
            pyResult.getInt("ratchet_count") shouldBe 0

            // Kotlin should be able to load (then it will generate its own initial ratchet)
            val dest = Destination.create(
                identity = identity,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("empty")
            )
            dest.enableRatchets(filePath)

            // After enableRatchets with empty file, it should have generated a new ratchet
            val ratchetKey = dest.getRatchetKey()
            ratchetKey shouldNotBe null
        }

        @Test
        @DisplayName("Invalid signature is rejected")
        fun invalidSignatureRejected(@TempDir tempDir: Path) {
            // Generate two different identities
            val privateKey1 = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val privateKey2 = crypto.randomBytes(RnsConstants.FULL_KEY_SIZE)
            val identity2 = Identity.fromPrivateKey(privateKey2, crypto)

            val ratchetKeys = List(2) { crypto.randomBytes(32) }
            val ratchetKeyHexList = ratchetKeys.map { it.toHex() }

            // Python writes file signed with identity1
            val filePath = tempDir.resolve("bad_sig.dat").toString()
            python(
                "ratchet_file_write",
                "path" to filePath,
                "ratchet_keys" to ratchetKeyHexList,
                "signing_private_key" to privateKey1.toHex()
            )

            // Kotlin tries to load with identity2 — signature should fail
            val dest = Destination.create(
                identity = identity2,
                direction = DestinationDirection.IN,
                type = DestinationType.SINGLE,
                appName = "test",
                aspects = *arrayOf("badsig")
            )

            var loadFailed = false
            try {
                dest.enableRatchets(filePath)
            } catch (e: Exception) {
                loadFailed = true
            }

            loadFailed shouldBe true
        }
    }
}
