package network.reticulum.interop.crypto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Test that Kotlin can decrypt data encrypted by Python's
 * Destination.encrypt() / Identity.encrypt() - the propagation path.
 *
 * Uses exact intermediate values from Python for debugging.
 */
@DisplayName("Propagation Decrypt Interop")
class PropagationDecryptTest {

    private val crypto = defaultCryptoProvider()

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    @DisplayName("Kotlin decrypts Python propagation-encrypted data")
    fun kotlinDecryptsPythonPropagationData() {
        // Values from Python test
        val privateKey = hexToBytes(
            "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2" +
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        )
        val ephemeralPub = hexToBytes("06dfc472f53b4fe3ab2e2865a6efb67108533f9f964ee0b11ce8ab9bbe937b6c")
        val pythonSharedKey = hexToBytes("f6729322d353c7f05665815ac8d6a69494e4ea9b54da6f0047ac84695a654312")
        val pythonDerivedKey = hexToBytes(
            "8cf7b55036fc7037bd95a4b2c622c30cb6b1b572315a63da78aaabdd49a85abe" +
            "753bbe5b96030f171c352511deb761d2f76482b91310895132f2da2c88ee5cc9"
        )
        val fullEncrypted = hexToBytes(
            "06dfc472f53b4fe3ab2e2865a6efb67108533f9f964ee0b11ce8ab9bbe937b6c" +
            "c4f27c892d9185b17859d2b99ef10166fea8eeb45000e4f2fc502456ee7b1321" +
            "ebb89a6d8edc8aa3404b13735cb304f630022c0b15ed1c568de2301cd268ee4c" +
            "27a9d8ee81c871f626eb765d03e348d1"
        )
        val expectedPlaintext = hexToBytes("48656c6c6f2066726f6d20507974686f6e2070726f7061676174696f6e21")

        // Create identity from private key
        val identity = Identity.fromPrivateKey(privateKey, crypto)
        
        // Step 1: Verify identity hash matches Python
        val expectedHash = "eb0e071a3f342bf3ee467fb22149eafd"
        println("Kotlin identity hash: ${identity.hash.toHexString()}")
        println("Python identity hash: $expectedHash")
        identity.hash.toHexString() shouldBe expectedHash
        
        // Step 2: Verify X25519 public key matches Python
        val expectedPubBytes = "d946390220202c7b7a195d6eb90553926fc7fa23c23322f18837c2e2b0fb8318"
        val x25519Public = identity.getPublicKey().copyOfRange(0, 32)
        println("Kotlin X25519 pub: ${x25519Public.toHexString()}")
        println("Python X25519 pub: $expectedPubBytes")
        x25519Public.toHexString() shouldBe expectedPubBytes
        
        // Step 3: Compute shared key and compare
        val x25519Private = privateKey.copyOfRange(0, 32)
        val sharedKey = crypto.x25519Exchange(x25519Private, ephemeralPub)
        println("Kotlin shared key: ${sharedKey.toHexString()}")
        println("Python shared key: ${pythonSharedKey.toHexString()}")
        sharedKey.toHexString() shouldBe pythonSharedKey.toHexString()
        
        // Step 4: HKDF and compare derived key
        val salt = identity.hash
        val derivedKey = crypto.hkdf(
            length = RnsConstants.DERIVED_KEY_LENGTH,
            ikm = sharedKey,
            salt = salt,
            info = null
        )
        println("Kotlin derived key: ${derivedKey.toHexString()}")
        println("Python derived key: ${pythonDerivedKey.toHexString()}")
        derivedKey.toHexString() shouldBe pythonDerivedKey.toHexString()
        
        // Step 5: Token decrypt
        val tokenData = fullEncrypted.copyOfRange(32, fullEncrypted.size)
        val token = Token(derivedKey, crypto)
        val decryptedFromToken = token.decrypt(tokenData)
        println("Token decrypt: ${String(decryptedFromToken)}")
        decryptedFromToken.contentEquals(expectedPlaintext) shouldBe true
        
        // Step 6: Full identity decrypt (the real test)
        val decrypted = identity.decrypt(fullEncrypted)
        decrypted shouldNotBe null
        println("Identity decrypt: ${String(decrypted!!)}")
        decrypted.contentEquals(expectedPlaintext) shouldBe true
    }
}
