package network.reticulum.interop.crypto

import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.interop.InteropTestBase
import network.reticulum.interop.getBytes
import network.reticulum.interop.getBoolean
import network.reticulum.interop.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Token (modified Fernet) interoperability tests with Python RNS.
 */
@DisplayName("Token Interop")
class TokenInteropTest : InteropTestBase() {

    private val crypto = defaultCryptoProvider()

    @Test
    @DisplayName("Token encryption format matches Python (AES-256)")
    fun `token encryption format matches Python aes256`() {
        // 64-byte key for AES-256
        val key = ByteArray(64) { it.toByte() }
        val plaintext = "Hello, Reticulum!".toByteArray()
        val fixedIv = ByteArray(16) { (it + 100).toByte() }

        // Encrypt in Kotlin with fixed IV
        val kotlinToken = Token(key, crypto).encryptWithIv(plaintext, fixedIv)

        // Encrypt in Python with same fixed IV
        val pythonResult = python(
            "token_encrypt",
            "key" to key,
            "plaintext" to plaintext,
            "iv" to fixedIv
        )

        assertBytesEqual(
            pythonResult.getBytes("token"),
            kotlinToken,
            "Token encryption (AES-256)"
        )
    }

    @Test
    @DisplayName("Token encryption format matches Python (AES-128)")
    fun `token encryption format matches Python aes128`() {
        // 32-byte key for AES-128
        val key = ByteArray(32) { (it * 2).toByte() }
        val plaintext = "Test message".toByteArray()
        val fixedIv = ByteArray(16) { (it + 50).toByte() }

        val kotlinToken = Token(key, crypto).encryptWithIv(plaintext, fixedIv)

        val pythonResult = python(
            "token_encrypt",
            "key" to key,
            "plaintext" to plaintext,
            "iv" to fixedIv
        )

        assertBytesEqual(
            pythonResult.getBytes("token"),
            kotlinToken,
            "Token encryption (AES-128)"
        )
    }

    @Test
    @DisplayName("Kotlin can decrypt Python token")
    fun `kotlin can decrypt Python token`() {
        val key = ByteArray(64) { (it * 3).toByte() }
        val plaintext = "Secret message from Python".toByteArray()

        // Encrypt in Python
        val pythonResult = python(
            "token_encrypt",
            "key" to key,
            "plaintext" to plaintext
        )
        val pythonToken = pythonResult.getBytes("token")

        // Decrypt in Kotlin
        val decrypted = Token(key, crypto).decrypt(pythonToken)

        assertBytesEqual(plaintext, decrypted, "Kotlin decrypting Python token")
    }

    @Test
    @DisplayName("Python can decrypt Kotlin token")
    fun `python can decrypt Kotlin token`() {
        val key = ByteArray(64) { (it * 5).toByte() }
        val plaintext = "Secret message from Kotlin".toByteArray()

        // Encrypt in Kotlin
        val kotlinToken = Token(key, crypto).encrypt(plaintext)

        // Decrypt in Python
        val pythonResult = python(
            "token_decrypt",
            "key" to key,
            "token" to kotlinToken
        )

        assertBytesEqual(plaintext, pythonResult.getBytes("plaintext"), "Python decrypting Kotlin token")
    }

    @Test
    @DisplayName("HMAC verification matches Python")
    fun `hmac verification matches Python`() {
        val key = ByteArray(64) { it.toByte() }
        val plaintext = "Test".toByteArray()

        // Create valid token in Kotlin
        val validToken = Token(key, crypto).encrypt(plaintext)

        // Verify in Python
        val pythonValid = python(
            "token_verify_hmac",
            "key" to key,
            "token" to validToken
        )
        assert(pythonValid.getBoolean("valid")) { "Python should verify valid Kotlin token" }

        // Corrupt token and verify both reject it
        val corruptToken = validToken.copyOf()
        corruptToken[corruptToken.size - 1] = (corruptToken.last().toInt() xor 0xFF).toByte()

        val kotlinRejects = !Token(key, crypto).verifyHmac(corruptToken)
        val pythonRejects = !python(
            "token_verify_hmac",
            "key" to key,
            "token" to corruptToken
        ).getBoolean("valid")

        assert(kotlinRejects) { "Kotlin should reject corrupted token" }
        assert(pythonRejects) { "Python should reject corrupted token" }
    }

    @Test
    @DisplayName("Token overhead is 48 bytes")
    fun `token overhead is 48 bytes`() {
        val key = ByteArray(64) { it.toByte() }

        // Test with various plaintext sizes
        val sizes = listOf(1, 15, 16, 17, 32, 100)

        for (size in sizes) {
            val plaintext = ByteArray(size) { it.toByte() }
            val token = Token(key, crypto).encrypt(plaintext)

            // Token = IV (16) + ciphertext (padded) + HMAC (32)
            // Ciphertext is padded to block boundary (16 bytes)
            val paddedSize = ((size / 16) + 1) * 16
            val expectedSize = 16 + paddedSize + 32

            assert(token.size == expectedSize) {
                "Token for $size bytes should be $expectedSize bytes, got ${token.size}"
            }
        }
    }

    @Test
    @DisplayName("Round-trip encryption/decryption")
    fun `round trip encryption decryption`() {
        val key = ByteArray(64) { (it * 7).toByte() }

        val testCases = listOf(
            ByteArray(0),  // Empty
            ByteArray(1) { 42 },  // Single byte
            "Hello, World!".toByteArray(),  // ASCII
            ByteArray(256) { it.toByte() },  // Binary
            ByteArray(1000) { (it % 256).toByte() }  // Larger
        )

        for (plaintext in testCases) {
            // Kotlin round-trip
            val token = Token(key, crypto).encrypt(plaintext)
            val decrypted = Token(key, crypto).decrypt(token)

            assertBytesEqual(plaintext, decrypted, "Round-trip for ${plaintext.size} bytes")

            // Cross-implementation round-trip
            val pythonToken = python(
                "token_encrypt",
                "key" to key,
                "plaintext" to plaintext
            ).getBytes("token")

            val kotlinDecrypted = Token(key, crypto).decrypt(pythonToken)
            assertBytesEqual(plaintext, kotlinDecrypted, "Cross-impl round-trip for ${plaintext.size} bytes")
        }
    }
}
