package network.reticulum.crypto

import network.reticulum.common.AesMode
import network.reticulum.common.CryptoException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import java.security.SecureRandom

/**
 * CryptoProvider implementation using BouncyCastle.
 *
 * IMPORTANT: X25519 operations use little-endian byte ordering
 * to match the Python Reticulum implementation.
 */
class BouncyCastleProvider : CryptoProvider {
    private val secureRandom = SecureRandom()

    override fun generateX25519KeyPair(): X25519KeyPair {
        val seed = ByteArray(32)
        secureRandom.nextBytes(seed)
        return x25519KeyPairFromSeed(seed)
    }

    override fun x25519KeyPairFromSeed(seed: ByteArray): X25519KeyPair {
        require(seed.size == 32) { "X25519 seed must be 32 bytes" }

        // Clamp the private key per RFC 7748
        val privateKey = seed.copyOf()
        clampX25519PrivateKey(privateKey)

        // Derive public key
        val publicKey = x25519PublicFromPrivate(privateKey)

        return X25519KeyPair(privateKey, publicKey)
    }

    override fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }

        val privateParams = X25519PrivateKeyParameters(privateKey, 0)
        val publicParams = privateParams.generatePublicKey()

        val publicKey = ByteArray(32)
        publicParams.encode(publicKey, 0)
        return publicKey
    }

    override fun x25519Exchange(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }

        try {
            val privateParams = X25519PrivateKeyParameters(privateKey, 0)
            val publicParams = X25519PublicKeyParameters(publicKey, 0)

            val agreement = X25519Agreement()
            agreement.init(privateParams)

            val sharedSecret = ByteArray(32)
            agreement.calculateAgreement(publicParams, sharedSecret, 0)

            return sharedSecret
        } catch (e: Exception) {
            throw CryptoException("X25519 key exchange failed", e)
        }
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val privateParams = keyPair.private as Ed25519PrivateKeyParameters
        val publicParams = keyPair.public as Ed25519PublicKeyParameters

        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)

        // BouncyCastle's Ed25519 uses the seed as the private key
        privateParams.encode(privateKey, 0)
        publicParams.encode(publicKey, 0)

        return Ed25519KeyPair(privateKey, publicKey)
    }

    override fun ed25519KeyPairFromSeed(seed: ByteArray): Ed25519KeyPair {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }

        val privateParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicParams = privateParams.generatePublicKey()

        val publicKey = ByteArray(32)
        publicParams.encode(publicKey, 0)

        return Ed25519KeyPair(seed, publicKey)
    }

    override fun ed25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }

        val privateParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val publicParams = privateParams.generatePublicKey()

        val publicKey = ByteArray(32)
        publicParams.encode(publicKey, 0)
        return publicKey
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }

        try {
            val privateParams = Ed25519PrivateKeyParameters(privateKey, 0)
            val signer = Ed25519Signer()
            signer.init(true, privateParams)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        } catch (e: Exception) {
            throw CryptoException("Ed25519 signing failed", e)
        }
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }

        return try {
            val publicParams = Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    override fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        val hash = ByteArray(32)
        digest.update(data, 0, data.size)
        digest.doFinal(hash, 0)
        return hash
    }

    override fun sha512(data: ByteArray): ByteArray {
        val digest = SHA512Digest()
        val hash = ByteArray(64)
        digest.update(data, 0, data.size)
        digest.doFinal(hash, 0)
        return hash
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())

        // If key is longer than block size (64), hash it first
        val actualKey = if (key.size > 64) sha256(key) else key

        hmac.init(KeyParameter(actualKey))
        hmac.update(data, 0, data.size)

        val result = ByteArray(32)
        hmac.doFinal(result, 0)
        return result
    }

    override fun hkdf(length: Int, ikm: ByteArray, salt: ByteArray?, info: ByteArray?): ByteArray {
        // HKDF implementation matching Python's RNS/Cryptography/HKDF.py
        val actualSalt = salt ?: ByteArray(32) // Default to 32 zero bytes
        val actualInfo = info ?: ByteArray(0)

        // Extract phase: PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(actualSalt, ikm)

        // Expand phase
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        require(n <= 255) { "HKDF output too long" }

        val result = ByteArray(length)
        var previousBlock = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            // block = HMAC(PRK, previous_block || info || counter_byte)
            val counterByte = ((i) % 256).toByte()  // Matches Python: (i + 1)%(0xFF+1) where i is 0-indexed
            val input = previousBlock + actualInfo + byteArrayOf(counterByte)
            previousBlock = hmacSha256(prk, input)

            val toCopy = minOf(hashLen, length - offset)
            previousBlock.copyInto(result, offset, 0, toCopy)
            offset += toCopy
        }

        return result
    }

    override fun aesEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, mode: AesMode): ByteArray {
        require(key.size == mode.keySize) { "Key must be ${mode.keySize} bytes for $mode" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        try {
            val cipher = PaddedBufferedBlockCipher(
                CBCBlockCipher.newInstance(AESEngine.newInstance()),
                PKCS7Padding()
            )
            cipher.init(true, ParametersWithIV(KeyParameter(key), iv))

            val output = ByteArray(cipher.getOutputSize(plaintext.size))
            var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
            len += cipher.doFinal(output, len)

            return output.copyOf(len)
        } catch (e: Exception) {
            throw CryptoException("AES encryption failed", e)
        }
    }

    override fun aesDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, mode: AesMode): ByteArray {
        require(key.size == mode.keySize) { "Key must be ${mode.keySize} bytes for $mode" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        try {
            val cipher = PaddedBufferedBlockCipher(
                CBCBlockCipher.newInstance(AESEngine.newInstance()),
                PKCS7Padding()
            )
            cipher.init(false, ParametersWithIV(KeyParameter(key), iv))

            val output = ByteArray(cipher.getOutputSize(ciphertext.size))
            var len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
            len += cipher.doFinal(output, len)

            return output.copyOf(len)
        } catch (e: Exception) {
            throw CryptoException("AES decryption failed", e)
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Clamp X25519 private key per RFC 7748.
     *
     * This matches Python's _fix_secret() in X25519.py:
     * - Clear bits 0, 1, 2 (n &= ~7)
     * - Clear bit 255 (n &= ~(128 << 8*31))
     * - Set bit 254 (n |= 64 << 8*31)
     */
    private fun clampX25519PrivateKey(key: ByteArray) {
        key[0] = (key[0].toInt() and 0xF8).toByte()       // Clear bits 0,1,2
        key[31] = (key[31].toInt() and 0x7F).toByte()     // Clear bit 255
        key[31] = (key[31].toInt() or 0x40).toByte()      // Set bit 254
    }
}
