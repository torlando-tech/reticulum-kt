package network.reticulum.crypto

import network.reticulum.common.AesMode

/**
 * X25519 key pair for ECDH key exchange.
 *
 * @property privateKey 32-byte private key (little-endian per RFC 7748)
 * @property publicKey 32-byte public key (little-endian per RFC 7748)
 */
data class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    init {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is X25519KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Ed25519 key pair for digital signatures.
 *
 * @property privateKey 32-byte private seed
 * @property publicKey 32-byte public key
 */
data class Ed25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    init {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ed25519KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Interface for cryptographic operations.
 *
 * This abstraction allows different backends (BouncyCastle on JVM,
 * platform crypto on Android, etc.) while ensuring consistent behavior.
 *
 * IMPORTANT: X25519 keys use little-endian byte ordering to match
 * the Python Reticulum implementation.
 */
interface CryptoProvider {
    /**
     * Generate a new X25519 key pair using secure random.
     */
    fun generateX25519KeyPair(): X25519KeyPair

    /**
     * Generate an X25519 key pair from a 32-byte seed.
     * The seed is clamped per RFC 7748 before use.
     */
    fun x25519KeyPairFromSeed(seed: ByteArray): X25519KeyPair

    /**
     * Derive X25519 public key from private key.
     */
    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray

    /**
     * Perform X25519 ECDH key exchange.
     *
     * @param privateKey Our 32-byte private key (little-endian)
     * @param publicKey Their 32-byte public key (little-endian)
     * @return 32-byte shared secret
     */
    fun x25519Exchange(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /**
     * Generate a new Ed25519 key pair using secure random.
     */
    fun generateEd25519KeyPair(): Ed25519KeyPair

    /**
     * Generate an Ed25519 key pair from a 32-byte seed.
     */
    fun ed25519KeyPairFromSeed(seed: ByteArray): Ed25519KeyPair

    /**
     * Derive Ed25519 public key from private seed.
     */
    fun ed25519PublicFromPrivate(privateKey: ByteArray): ByteArray

    /**
     * Sign a message with Ed25519.
     *
     * @param privateKey 32-byte private seed
     * @param message Message to sign
     * @return 64-byte signature
     */
    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify an Ed25519 signature.
     *
     * @param publicKey 32-byte public key
     * @param message Original message
     * @param signature 64-byte signature
     * @return true if signature is valid
     */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /**
     * Compute SHA-256 hash.
     *
     * @param data Data to hash
     * @return 32-byte hash
     */
    fun sha256(data: ByteArray): ByteArray

    /**
     * Compute SHA-512 hash.
     *
     * @param data Data to hash
     * @return 64-byte hash
     */
    fun sha512(data: ByteArray): ByteArray

    /**
     * Compute HMAC-SHA256.
     *
     * @param key HMAC key (any length, will be hashed if > 64 bytes)
     * @param data Data to authenticate
     * @return 32-byte HMAC
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /**
     * Derive key using HKDF-SHA256.
     *
     * @param length Desired output length in bytes
     * @param ikm Input keying material
     * @param salt Optional salt (if null, uses 32 zero bytes)
     * @param info Optional context info (if null, uses empty bytes)
     * @return Derived key of specified length
     */
    fun hkdf(length: Int, ikm: ByteArray, salt: ByteArray?, info: ByteArray?): ByteArray

    /**
     * Encrypt data with AES-CBC.
     *
     * @param plaintext Data to encrypt (will be PKCS7 padded)
     * @param key Encryption key (16 or 32 bytes depending on mode)
     * @param iv 16-byte initialization vector
     * @param mode AES_128_CBC or AES_256_CBC
     * @return Ciphertext (PKCS7 padded)
     */
    fun aesEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray, mode: AesMode): ByteArray

    /**
     * Decrypt data with AES-CBC.
     *
     * @param ciphertext Data to decrypt (PKCS7 padded)
     * @param key Decryption key (16 or 32 bytes depending on mode)
     * @param iv 16-byte initialization vector
     * @param mode AES_128_CBC or AES_256_CBC
     * @return Plaintext (PKCS7 unpadded)
     */
    fun aesDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, mode: AesMode): ByteArray

    /**
     * Generate cryptographically secure random bytes.
     *
     * @param length Number of bytes to generate
     * @return Random bytes
     */
    fun randomBytes(length: Int): ByteArray
}

/**
 * Default crypto provider instance.
 * Uses BouncyCastle on JVM.
 */
fun defaultCryptoProvider(): CryptoProvider = BouncyCastleProvider()
