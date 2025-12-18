package network.reticulum.crypto

import network.reticulum.common.AesMode
import network.reticulum.common.CryptoException
import network.reticulum.common.RnsConstants
import network.reticulum.common.constantTimeEquals

/**
 * Modified Fernet token implementation for Reticulum.
 *
 * Based on the Fernet spec (https://github.com/fernet/spec/blob/master/Spec.md)
 * but without VERSION and TIMESTAMP fields to reduce overhead and metadata leakage.
 *
 * Token format: IV (16 bytes) || ciphertext || HMAC (32 bytes)
 *
 * Key structure:
 * - AES-128-CBC: 32-byte key (16 signing + 16 encryption)
 * - AES-256-CBC: 64-byte key (32 signing + 32 encryption)
 */
class Token(
    private val key: ByteArray,
    private val crypto: CryptoProvider = defaultCryptoProvider()
) {
    private val mode: AesMode
    private val signingKey: ByteArray
    private val encryptionKey: ByteArray

    init {
        when (key.size) {
            32 -> {
                mode = AesMode.AES_128_CBC
                signingKey = key.copyOfRange(0, 16)
                encryptionKey = key.copyOfRange(16, 32)
            }
            64 -> {
                mode = AesMode.AES_256_CBC
                signingKey = key.copyOfRange(0, 32)
                encryptionKey = key.copyOfRange(32, 64)
            }
            else -> throw IllegalArgumentException(
                "Token key must be 32 bytes (AES-128) or 64 bytes (AES-256), got ${key.size}"
            )
        }
    }

    /**
     * Encrypt plaintext and return token.
     *
     * @param plaintext Data to encrypt
     * @return Token: IV (16) || ciphertext || HMAC (32)
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = crypto.randomBytes(16)
        return encryptWithIv(plaintext, iv)
    }

    /**
     * Encrypt plaintext with a specific IV (for testing reproducibility).
     *
     * @param plaintext Data to encrypt
     * @param iv 16-byte initialization vector
     * @return Token: IV (16) || ciphertext || HMAC (32)
     */
    fun encryptWithIv(plaintext: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == 16) { "IV must be 16 bytes" }

        val ciphertext = crypto.aesEncrypt(plaintext, encryptionKey, iv, mode)

        // signed_parts = IV + ciphertext
        val signedParts = iv + ciphertext

        // HMAC over IV + ciphertext
        val hmac = crypto.hmacSha256(signingKey, signedParts)

        return signedParts + hmac
    }

    /**
     * Verify the HMAC of a token.
     *
     * @param token The token to verify
     * @return true if HMAC is valid
     */
    fun verifyHmac(token: ByteArray): Boolean {
        if (token.size <= 32) return false

        val receivedHmac = token.copyOfRange(token.size - 32, token.size)
        val dataToVerify = token.copyOfRange(0, token.size - 32)
        val expectedHmac = crypto.hmacSha256(signingKey, dataToVerify)

        return receivedHmac.constantTimeEquals(expectedHmac)
    }

    /**
     * Decrypt a token.
     *
     * @param token Token: IV (16) || ciphertext || HMAC (32)
     * @return Decrypted plaintext
     * @throws CryptoException if HMAC verification fails or decryption fails
     */
    fun decrypt(token: ByteArray): ByteArray {
        if (token.size < TOKEN_OVERHEAD + 16) {  // Minimum: IV + 1 block + HMAC
            throw CryptoException("Token too short: ${token.size} bytes")
        }

        if (!verifyHmac(token)) {
            throw CryptoException("Token HMAC verification failed")
        }

        val iv = token.copyOfRange(0, 16)
        val ciphertext = token.copyOfRange(16, token.size - 32)

        return try {
            crypto.aesDecrypt(ciphertext, encryptionKey, iv, mode)
        } catch (e: Exception) {
            throw CryptoException("Token decryption failed", e)
        }
    }

    companion object {
        const val TOKEN_OVERHEAD = RnsConstants.TOKEN_OVERHEAD  // 48 bytes

        /**
         * Generate a new random token key.
         *
         * @param mode AES mode (determines key size)
         * @param crypto CryptoProvider to use
         * @return Random key suitable for Token
         */
        fun generateKey(
            mode: AesMode = AesMode.AES_256_CBC,
            crypto: CryptoProvider = defaultCryptoProvider()
        ): ByteArray {
            return when (mode) {
                AesMode.AES_128_CBC -> crypto.randomBytes(32)
                AesMode.AES_256_CBC -> crypto.randomBytes(64)
            }
        }
    }
}
