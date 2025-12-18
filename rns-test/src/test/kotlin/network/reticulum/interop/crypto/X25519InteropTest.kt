package network.reticulum.interop.crypto

import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * X25519 interoperability tests with Python RNS.
 *
 * CRITICAL: X25519 uses little-endian byte ordering.
 * These tests verify byte-perfect compatibility.
 */
@DisplayName("X25519 Interop")
class X25519InteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Test
    @DisplayName("Public key generation matches Python")
    fun `public key generation matches Python`() {
        // Use deterministic seed
        val seed = ByteArray(32) { it.toByte() }

        // Generate in Kotlin
        val kotlinKeyPair = crypto.x25519KeyPairFromSeed(seed)

        // Generate in Python
        val pythonResult = python("x25519_generate", "seed" to seed)
        val pythonPublicKey = pythonResult.getBytes("public_key")

        assertBytesEqual(
            pythonPublicKey,
            kotlinKeyPair.publicKey,
            "X25519 public key from seed"
        )
    }

    @Test
    @DisplayName("Public key derivation matches Python")
    fun `public key derivation matches Python`() {
        // Test with various seeds
        val seeds = listOf(
            ByteArray(32) { 0 },
            ByteArray(32) { 0xFF.toByte() },
            ByteArray(32) { it.toByte() },
            ByteArray(32) { (255 - it).toByte() }
        )

        for (seed in seeds) {
            val kotlinKeyPair = crypto.x25519KeyPairFromSeed(seed)
            val pythonResult = python("x25519_generate", "seed" to seed)

            assertBytesEqual(
                pythonResult.getBytes("public_key"),
                kotlinKeyPair.publicKey,
                "X25519 public key for seed ${seed.toHex().take(16)}..."
            )
        }
    }

    @Test
    @DisplayName("Key exchange produces identical shared secret")
    fun `key exchange produces identical shared secret`() {
        // Alice's seed
        val aliceSeed = ByteArray(32) { it.toByte() }
        // Bob's seed
        val bobSeed = ByteArray(32) { (it + 32).toByte() }

        // Generate Alice's keypair in Kotlin
        val aliceKotlin = crypto.x25519KeyPairFromSeed(aliceSeed)

        // Generate Bob's keypair in Python
        val bobPython = python("x25519_generate", "seed" to bobSeed)
        val bobPublicKey = bobPython.getBytes("public_key")

        // Kotlin Alice exchanges with Python Bob's public key
        val sharedKotlin = crypto.x25519Exchange(aliceKotlin.privateKey, bobPublicKey)

        // Python Bob exchanges with Kotlin Alice's public key
        val sharedPython = python(
            "x25519_exchange",
            "private_key" to bobSeed,
            "peer_public_key" to aliceKotlin.publicKey
        )

        assertBytesEqual(
            sharedPython.getBytes("shared_secret"),
            sharedKotlin,
            "X25519 shared secret"
        )
    }

    @Test
    @DisplayName("Bidirectional exchange produces same secret")
    fun `bidirectional exchange produces same secret`() {
        val aliceSeed = ByteArray(32) { (it * 3).toByte() }
        val bobSeed = ByteArray(32) { (it * 7 + 11).toByte() }

        // Generate both keypairs in Kotlin
        val aliceKotlin = crypto.x25519KeyPairFromSeed(aliceSeed)
        val bobKotlin = crypto.x25519KeyPairFromSeed(bobSeed)

        // Exchange in both directions
        val aliceShared = crypto.x25519Exchange(aliceKotlin.privateKey, bobKotlin.publicKey)
        val bobShared = crypto.x25519Exchange(bobKotlin.privateKey, aliceKotlin.publicKey)

        // Should be identical
        assertBytesEqual(aliceShared, bobShared, "Bidirectional shared secret")

        // Also verify against Python
        val pythonAliceShared = python(
            "x25519_exchange",
            "private_key" to aliceSeed,
            "peer_public_key" to bobKotlin.publicKey
        )

        assertBytesEqual(
            pythonAliceShared.getBytes("shared_secret"),
            aliceShared,
            "Python verification of shared secret"
        )
    }

    @Test
    @DisplayName("Cross-implementation key exchange")
    fun `cross implementation key exchange`() {
        // Generate Kotlin keypair
        val kotlinSeed = ByteArray(32) { (it * 5).toByte() }
        val kotlinKeyPair = crypto.x25519KeyPairFromSeed(kotlinSeed)

        // Generate Python keypair with different seed
        val pythonSeed = ByteArray(32) { (it * 13).toByte() }
        val pythonKeyPair = python("x25519_generate", "seed" to pythonSeed)

        // Kotlin performs exchange with Python's public key
        val kotlinShared = crypto.x25519Exchange(
            kotlinKeyPair.privateKey,
            pythonKeyPair.getBytes("public_key")
        )

        // Python performs exchange with Kotlin's public key
        val pythonShared = python(
            "x25519_exchange",
            "private_key" to pythonSeed,
            "peer_public_key" to kotlinKeyPair.publicKey
        )

        assertBytesEqual(
            pythonShared.getBytes("shared_secret"),
            kotlinShared,
            "Cross-implementation ECDH shared secret"
        )
    }
}
