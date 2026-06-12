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

        // python Token.encrypt: ciphertext = mode.encrypt(PKCS7.pad(data), ...)
        // — padding belongs to the Token layer, the AES layer is the bare
        // block cipher (Token.py:86-95). Byte-identical to a padded cipher on
        // encrypt; split out so decrypt can use python's LAX unpad.
        val ciphertext = crypto.aesEncryptNoPadding(PKCS7.pad(plaintext), encryptionKey, iv, mode)

        // signed_parts = IV + ciphertext
        val signedParts = iv + ciphertext

        // HMAC over IV + ciphertext
        val hmac = crypto.hmacSha256(signingKey, signedParts)

        return signedParts + hmac
    }

    /**
     * Verify the HMAC of a token.
     *
     * python Token.verify_hmac (Token.py:76-83): a token of 32 bytes or
     * fewer cannot carry both an HMAC and a body and is REJECTED by raise,
     * not by returning false.
     *
     * @param token The token to verify
     * @return true if HMAC is valid
     * @throws CryptoException if the token is 32 bytes or fewer
     */
    fun verifyHmac(token: ByteArray): Boolean {
        if (token.size <= 32) {
            throw CryptoException("Cannot verify HMAC on token of only ${token.size} bytes")
        }

        val receivedHmac = token.copyOfRange(token.size - 32, token.size)
        val dataToVerify = token.copyOfRange(0, token.size - 32)
        val expectedHmac = crypto.hmacSha256(signingKey, dataToVerify)

        return receivedHmac.constantTimeEquals(expectedHmac)
    }

    /**
     * Decrypt a token, mirroring python Token.decrypt (Token.py:98-114):
     * authenticate FIRST (verifyHmac, which raises on a <=32-byte token),
     * then bare-AES decrypt and python's LAX PKCS7 unpad; any failure in
     * that stage raises "Could not decrypt token".
     *
     * @param token Token: IV (16) || ciphertext || HMAC (32)
     * @return Decrypted plaintext
     * @throws CryptoException if HMAC verification fails or decryption fails
     */
    fun decrypt(token: ByteArray): ByteArray {
        if (!verifyHmac(token)) {
            throw CryptoException("Token HMAC was invalid")
        }

        val iv = token.copyOfRange(0, 16)
        val ciphertext = token.copyOfRange(16, token.size - 32)

        return try {
            PKCS7.unpad(crypto.aesDecryptNoPadding(ciphertext, encryptionKey, iv, mode))
        } catch (e: Exception) {
            throw CryptoException("Could not decrypt token: ${e.message}", e)
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
