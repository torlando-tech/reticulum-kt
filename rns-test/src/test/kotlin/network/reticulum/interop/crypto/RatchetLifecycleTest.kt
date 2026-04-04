package network.reticulum.interop.crypto

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import network.reticulum.interop.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Ratchet lifecycle diagnostic test.
 *
 * Tests the full ratchet lifecycle:
 * 1. Create Kotlin destination with ratchets
 * 2. Verify ratchet private/public consistency
 * 3. Verify announce contains correct ratchet public key
 * 4. Have Python encrypt with that ratchet public key
 * 5. Decrypt in Kotlin with the matching ratchet private key
 */
@DisplayName("Ratchet Lifecycle Interop")
class RatchetLifecycleTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @TempDir
    lateinit var tempDir: File

    @Test
    @DisplayName("Step 1: Ratchet private key derives correct public key (Kotlin vs Python)")
    fun `ratchet key derivation matches Python`() {
        // Generate a ratchet private key (same as Destination.rotateRatchets does)
        val ratchetPrivate = crypto.randomBytes(32)

        // Derive public key in Kotlin
        val kotlinPublic = crypto.x25519PublicFromPrivate(ratchetPrivate)

        // Derive public key in Python
        val pythonResult = python(
            "ratchet_public_from_private",
            "ratchet_private" to ratchetPrivate
        )
        val pythonPublic = pythonResult.getBytes("ratchet_public")

        println("Ratchet private:  ${ratchetPrivate.toHex()}")
        println("Kotlin public:    ${kotlinPublic.toHex()}")
        println("Python public:    ${pythonPublic.toHex()}")

        assertBytesEqual(pythonPublic, kotlinPublic, "Ratchet public key derivation")
    }

    @Test
    @DisplayName("Step 2: Python encrypts with ratchet pub, Kotlin decrypts with ratchet priv")
    fun `python encrypts with ratchet Kotlin decrypts`() {
        // Create a Kotlin identity
        val identity = Identity.create(crypto)
        val identityHash = identity.hash

        // Generate a ratchet private key
        val ratchetPrivate = crypto.randomBytes(32)
        val ratchetPublic = crypto.x25519PublicFromPrivate(ratchetPrivate)

        val plaintext = "Hello from Python with ratchet!".toByteArray()

        println("Identity hash:    ${identityHash.toHex()}")
        println("Ratchet private:  ${ratchetPrivate.toHex()}")
        println("Ratchet public:   ${ratchetPublic.toHex()}")

        // Python encrypts using ratchet public key + identity hash as salt
        val pythonResult = python(
            "ratchet_encrypt",
            "ratchet_public" to ratchetPublic,
            "plaintext" to plaintext,
            "identity_hash" to identityHash
        )
        val ciphertext = pythonResult.getBytes("ciphertext")
        val pythonSharedKey = pythonResult.getBytes("shared_key")
        val pythonDerivedKey = pythonResult.getBytes("derived_key")
        val ephemeralPub = pythonResult.getBytes("ephemeral_public")

        println("Python ephemeral pub: ${ephemeralPub.toHex()}")
        println("Python shared key:    ${pythonSharedKey.toHex()}")
        println("Python derived key:   ${pythonDerivedKey.toHex()}")
        println("Python ciphertext:    ${ciphertext.toHex()}")

        // Compute Kotlin shared key for comparison
        val kotlinSharedKey = crypto.x25519Exchange(ratchetPrivate, ephemeralPub)
        println("Kotlin shared key:    ${kotlinSharedKey.toHex()}")
        assertBytesEqual(pythonSharedKey, kotlinSharedKey, "Shared key (ratchet DH)")

        // Compute Kotlin derived key for comparison
        val kotlinDerivedKey = crypto.hkdf(
            length = RnsConstants.DERIVED_KEY_LENGTH,
            ikm = kotlinSharedKey,
            salt = identityHash,
            info = null
        )
        println("Kotlin derived key:   ${kotlinDerivedKey.toHex()}")
        assertBytesEqual(pythonDerivedKey, kotlinDerivedKey, "Derived key (HKDF)")

        // Decrypt token in Kotlin
        val tokenData = ciphertext.copyOfRange(32, ciphertext.size) // skip ephemeral pub
        val token = Token(kotlinDerivedKey, crypto)
        val decrypted = token.decrypt(tokenData)
        decrypted shouldNotBe null
        assertBytesEqual(plaintext, decrypted!!, "Decrypted plaintext")
    }

    @Test
    @DisplayName("Step 3: Full Identity.decrypt with ratchet list works")
    fun `identity decrypt with ratchet list`() {
        val identity = Identity.create(crypto)
        val ratchetPrivate = crypto.randomBytes(32)
        val ratchetPublic = crypto.x25519PublicFromPrivate(ratchetPrivate)
        val plaintext = "Test ratchet decrypt".toByteArray()

        // Python encrypts with ratchet
        val pythonResult = python(
            "ratchet_encrypt",
            "ratchet_public" to ratchetPublic,
            "plaintext" to plaintext,
            "identity_hash" to identity.hash
        )
        val ciphertext = pythonResult.getBytes("ciphertext")

        // Decrypt using Identity.decrypt with ratchet list
        val decrypted = identity.decrypt(ciphertext, listOf(ratchetPrivate), enforceRatchets = true)
        decrypted shouldNotBe null
        assertBytesEqual(plaintext, decrypted!!, "Identity.decrypt with ratchet")
    }

    @Test
    @DisplayName("Step 4: Destination ratchet lifecycle - enableRatchets + announce + decrypt")
    fun `destination ratchet lifecycle`() {
        val identity = Identity.create(crypto)

        // Create a SINGLE/IN destination (the kind that enables ratchets)
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "ratchetlifecycle"
        )

        // Enable ratchets
        val ratchetsPath = File(tempDir, "ratchets").absolutePath
        destination.enableRatchets(ratchetsPath)

        // Inspect ratchet state
        val ratchetKey = destination.getRatchetKey()
        println("Destination ratchet key (public): ${ratchetKey?.toHex()}")
        ratchetKey shouldNotBe null

        // Get the ratchet private keys via reflection (they're private)
        val ratchetsField = Destination::class.java.getDeclaredField("ratchets")
        ratchetsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ratchetPrivates = ratchetsField.get(destination) as MutableList<ByteArray>
        println("Number of ratchets: ${ratchetPrivates.size}")
        ratchetPrivates.size shouldBe 1

        val ratchetPrivate = ratchetPrivates[0]
        val derivedPublic = crypto.x25519PublicFromPrivate(ratchetPrivate)

        println("Ratchet private[0]:  ${ratchetPrivate.toHex()}")
        println("Derived public:      ${derivedPublic.toHex()}")
        println("getRatchetKey():     ${ratchetKey!!.toHex()}")

        // Verify: ratchetKey == x25519PublicFromPrivate(ratchets[0])
        assertBytesEqual(derivedPublic, ratchetKey, "getRatchetKey matches derived public key")

        // Generate announce data and extract ratchet public key
        val announceMethod = Destination::class.java.getDeclaredMethod(
            "generateAnnounceData",
            Identity::class.java,
            ByteArray::class.java
        )
        announceMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = announceMethod.invoke(destination, identity, null) as Pair<ByteArray, Boolean>
        val (announceData, hasRatchet) = result

        hasRatchet shouldBe true
        println("Announce data size: ${announceData.size}")
        println("Has ratchet: $hasRatchet")

        // Extract ratchet public key from announce data
        // Format: public_key(64) + name_hash(10) + random_hash(10) + ratchet(32) + signature(64) + app_data
        val announceRatchetPub = announceData.copyOfRange(84, 116)
        println("Announce ratchet pub: ${announceRatchetPub.toHex()}")

        // Verify: announce ratchet pub == getRatchetKey()
        assertBytesEqual(ratchetKey, announceRatchetPub, "Announce ratchet matches getRatchetKey()")

        // Now simulate what Python does: encrypt using the announce ratchet pub
        val plaintext = "Propagated message for ratchet destination".toByteArray()

        val pythonResult = python(
            "ratchet_encrypt",
            "ratchet_public" to announceRatchetPub,
            "plaintext" to plaintext,
            "identity_hash" to identity.hash
        )
        val ciphertext = pythonResult.getBytes("ciphertext")
        println("Python ciphertext size: ${ciphertext.size}")

        // Try to decrypt using Destination.decrypt
        val decrypted = destination.decrypt(ciphertext)
        decrypted shouldNotBe null
        assertBytesEqual(plaintext, decrypted!!, "Destination.decrypt with ratchet")
    }

    @Test
    @DisplayName("Step 5: rotateRatchets during announce preserves decryptability")
    fun `rotate ratchets during announce preserves decryptability`() {
        val identity = Identity.create(crypto)

        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            "rotatetest"
        )

        // Enable ratchets with very short interval to force rotation
        val ratchetsPath = File(tempDir, "ratchets2").absolutePath
        destination.enableRatchets(ratchetsPath)

        // Force lastRatchetRotation to 0 so rotateRatchets will create a new one
        val lastRotationField = Destination::class.java.getDeclaredField("lastRatchetRotation")
        lastRotationField.isAccessible = true
        lastRotationField.setLong(destination, 0L)

        // Get pre-announce ratchets
        val ratchetsField = Destination::class.java.getDeclaredField("ratchets")
        ratchetsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ratchetsBefore = (ratchetsField.get(destination) as MutableList<ByteArray>).map { it.copyOf() }
        println("Ratchets before announce: ${ratchetsBefore.size}")
        for (i in ratchetsBefore.indices) {
            println("  [$i] ${ratchetsBefore[i].toHex()}")
        }

        // Generate announce (this triggers rotateRatchets inside)
        val announceMethod = Destination::class.java.getDeclaredMethod(
            "generateAnnounceData",
            Identity::class.java,
            ByteArray::class.java
        )
        announceMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = announceMethod.invoke(destination, identity, null) as Pair<ByteArray, Boolean>
        val (announceData, _) = result

        // Get post-announce ratchets
        @Suppress("UNCHECKED_CAST")
        val ratchetsAfter = (ratchetsField.get(destination) as MutableList<ByteArray>).map { it.copyOf() }
        println("Ratchets after announce: ${ratchetsAfter.size}")
        for (i in ratchetsAfter.indices) {
            println("  [$i] ${ratchetsAfter[i].toHex()}")
        }

        // The announce ratchet pub should be ratchetsAfter[0]'s public key
        val announceRatchetPub = announceData.copyOfRange(84, 116)
        val expectedPub = crypto.x25519PublicFromPrivate(ratchetsAfter[0])
        println("Announce ratchet pub:   ${announceRatchetPub.toHex()}")
        println("ratchetsAfter[0] pub:   ${expectedPub.toHex()}")
        assertBytesEqual(expectedPub, announceRatchetPub, "Announce ratchet matches newest ratchet")

        // Python encrypts with the announced ratchet
        val plaintext = "After rotation message".toByteArray()
        val pythonResult = python(
            "ratchet_encrypt",
            "ratchet_public" to announceRatchetPub,
            "plaintext" to plaintext,
            "identity_hash" to identity.hash
        )
        val ciphertext = pythonResult.getBytes("ciphertext")

        // Kotlin destination should be able to decrypt
        val decrypted = destination.decrypt(ciphertext)
        decrypted shouldNotBe null
        assertBytesEqual(plaintext, decrypted!!, "Decrypt after ratchet rotation")
    }

    @Test
    @DisplayName("Step 6: Python Destination.encrypt with ratchet")
    fun `python destination encrypt with ratchet`() {
        val identity = Identity.create(crypto)
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val ratchetsPath = File(tempDir, "ratchets3").absolutePath
        destination.enableRatchets(ratchetsPath)

        val ratchetKey = destination.getRatchetKey()!!
        val ratchetsField = Destination::class.java.getDeclaredField("ratchets")
        ratchetsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ratchetPrivates = ratchetsField.get(destination) as MutableList<ByteArray>

        println("Kotlin identity hash: ${identity.hash.toHex()}")
        println("Kotlin dest hash:     ${destination.hash.toHex()}")
        println("Ratchet public:       ${ratchetKey.toHex()}")
        println("Ratchets count:       ${ratchetPrivates.size}")

        val plaintext = "Test Destination.encrypt with ratchet".toByteArray()

        // Test 1: Python Destination.encrypt WITH ratchet remembered
        val encResult = python(
            "destination_encrypt_debug",
            "public_key" to identity.getPublicKey(),
            "destination_hash" to destination.hash,
            "plaintext" to plaintext,
            "ratchet_public" to ratchetKey
        )

        println("Python dest_hash_computed: ${encResult.getString("dest_hash_computed")}")
        println("Python dest_hash_matches: ${encResult.getString("dest_hash_matches")}")
        println("Python used_ratchet:      ${encResult.getString("used_ratchet")}")
        println("Python ratchet_used:      ${encResult.getString("ratchet_used")}")
        println("Python identity_hash:     ${encResult.getString("identity_hash")}")
        println("Python ratchet_id:        ${encResult.getString("ratchet_id")}")

        val ciphertext = encResult.getBytes("ciphertext")
        println("Ciphertext size: ${ciphertext.size}")
        println("Ciphertext[0:32] (ephemeral pub): ${ciphertext.copyOfRange(0, 32).toHex()}")

        // Verify Python computed correct dest hash
        encResult.getString("dest_hash_matches") shouldBe "true"

        // Try decryption
        val decrypted = destination.decrypt(ciphertext)
        if (decrypted != null) {
            println("Destination.decrypt: SUCCESS")
            assertBytesEqual(plaintext, decrypted, "Decrypted plaintext")
        } else {
            println("Destination.decrypt: FAILED")
            // Debug: try each ratchet manually with intermediate values
            val ephemeralPub = ciphertext.copyOfRange(0, 32)
            val tokenData = ciphertext.copyOfRange(32, ciphertext.size)
            for ((i, ratchetPriv) in ratchetPrivates.withIndex()) {
                val ratchetPub = crypto.x25519PublicFromPrivate(ratchetPriv)
                val sharedKey = crypto.x25519Exchange(ratchetPriv, ephemeralPub)
                val derivedKey = crypto.hkdf(
                    length = RnsConstants.DERIVED_KEY_LENGTH,
                    ikm = sharedKey,
                    salt = identity.hash,
                    info = null
                )
                println("  Ratchet[$i] pub:  ${ratchetPub.toHex()}")
                println("  Ratchet[$i] shared: ${sharedKey.toHex()}")
                println("  Ratchet[$i] derived: ${derivedKey.toHex()}")
                try {
                    val token = Token(derivedKey, crypto)
                    val dec = token.decrypt(tokenData)
                    println("  Ratchet[$i] decrypt: ${if (dec != null) "SUCCESS" else "FAILED (null)"}")
                } catch (e: Exception) {
                    println("  Ratchet[$i] decrypt: FAILED (${e.message})")
                }
            }
            // Also try identity key
            val identityShared = crypto.x25519Exchange(identity.getPrivateKey().copyOfRange(0, 32), ephemeralPub)
            val identityDerived = crypto.hkdf(
                length = RnsConstants.DERIVED_KEY_LENGTH,
                ikm = identityShared,
                salt = identity.hash,
                info = null
            )
            println("  Identity shared: ${identityShared.toHex()}")
            println("  Identity derived: ${identityDerived.toHex()}")
            try {
                val token = Token(identityDerived, crypto)
                val dec = token.decrypt(tokenData)
                println("  Identity decrypt: ${if (dec != null) "SUCCESS" else "FAILED (null)"}")
            } catch (e: Exception) {
                println("  Identity decrypt: FAILED (${e.message})")
            }
        }
        decrypted shouldNotBe null

        // Test 2: Python Destination.encrypt WITHOUT ratchet (identity key fallback)
        val noRatchetResult = python(
            "destination_encrypt_debug",
            "public_key" to identity.getPublicKey(),
            "destination_hash" to destination.hash,
            "plaintext" to plaintext
            // no ratchet_public → Python should encrypt without ratchet
        )
        println("\nWithout ratchet:")
        println("Python used_ratchet: ${noRatchetResult.getString("used_ratchet")}")

        val noRatchetCiphertext = noRatchetResult.getBytes("ciphertext")
        val noRatchetDecrypt = destination.decrypt(noRatchetCiphertext)
        if (noRatchetDecrypt != null) {
            println("Destination.decrypt (no ratchet): SUCCESS")
        } else {
            println("Destination.decrypt (no ratchet): FAILED")
        }
        noRatchetDecrypt shouldNotBe null
    }
}
