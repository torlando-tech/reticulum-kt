package network.reticulum.identity

import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider

/**
 * Identity is the core authentication primitive in Reticulum.
 *
 * An Identity contains an X25519 key pair for encryption/key exchange
 * and an Ed25519 key pair for signing. The identity hash is a truncated
 * SHA-256 of the full 64-byte public key.
 *
 * Key format (matching Python):
 * - Private key: X25519 private (32) || Ed25519 private (32) = 64 bytes
 * - Public key: X25519 public (32) || Ed25519 public (32) = 64 bytes
 */
class Identity private constructor(
    private val crypto: CryptoProvider,
    private val x25519Private: ByteArray?,    // 32 bytes, null if public-only
    private val x25519Public: ByteArray,      // 32 bytes
    private val ed25519Private: ByteArray?,   // 32 bytes, null if public-only
    private val ed25519Public: ByteArray      // 32 bytes
) {
    /**
     * The truncated hash of this identity's public key (16 bytes).
     */
    val hash: ByteArray = Hashes.truncatedHash(getPublicKey())

    /**
     * The hex-encoded hash.
     */
    val hexHash: String = hash.toHexString()

    /**
     * Whether this identity holds a private key (can sign/decrypt).
     */
    val hasPrivateKey: Boolean = x25519Private != null && ed25519Private != null

    companion object {
        /**
         * Create a new identity with randomly generated keys.
         */
        fun create(crypto: CryptoProvider = defaultCryptoProvider()): Identity {
            val x25519KeyPair = crypto.generateX25519KeyPair()
            val ed25519KeyPair = crypto.generateEd25519KeyPair()

            return Identity(
                crypto,
                x25519KeyPair.privateKey,
                x25519KeyPair.publicKey,
                ed25519KeyPair.privateKey,
                ed25519KeyPair.publicKey
            )
        }

        /**
         * Load an identity from a 64-byte private key.
         * The private key format is: X25519 private (32) || Ed25519 private (32)
         */
        fun fromPrivateKey(
            privateKey: ByteArray,
            crypto: CryptoProvider = defaultCryptoProvider()
        ): Identity {
            require(privateKey.size == RnsConstants.FULL_KEY_SIZE) {
                "Private key must be ${RnsConstants.FULL_KEY_SIZE} bytes, got ${privateKey.size}"
            }

            val x25519Private = privateKey.copyOfRange(0, RnsConstants.KEY_SIZE)
            val ed25519Private = privateKey.copyOfRange(RnsConstants.KEY_SIZE, RnsConstants.FULL_KEY_SIZE)

            val x25519Public = crypto.x25519PublicFromPrivate(x25519Private)
            val ed25519Public = crypto.ed25519PublicFromPrivate(ed25519Private)

            return Identity(
                crypto,
                x25519Private,
                x25519Public,
                ed25519Private,
                ed25519Public
            )
        }

        /**
         * Load an identity from a 64-byte public key (no private key).
         * The public key format is: X25519 public (32) || Ed25519 public (32)
         */
        fun fromPublicKey(
            publicKey: ByteArray,
            crypto: CryptoProvider = defaultCryptoProvider()
        ): Identity {
            require(publicKey.size == RnsConstants.FULL_KEY_SIZE) {
                "Public key must be ${RnsConstants.FULL_KEY_SIZE} bytes, got ${publicKey.size}"
            }

            val x25519Public = publicKey.copyOfRange(0, RnsConstants.KEY_SIZE)
            val ed25519Public = publicKey.copyOfRange(RnsConstants.KEY_SIZE, RnsConstants.FULL_KEY_SIZE)

            return Identity(
                crypto,
                null,
                x25519Public,
                null,
                ed25519Public
            )
        }

        /**
         * Recall a known identity by its hash.
         * Returns null if no identity is known for this hash.
         *
         * Note: This requires an IdentityStorage implementation to be set up.
         * In the initial implementation, this always returns null.
         */
        fun recall(hash: ByteArray): Identity? {
            // TODO: Implement identity storage lookup
            return null
        }
    }

    /**
     * Get the full 64-byte public key (X25519 || Ed25519).
     */
    fun getPublicKey(): ByteArray = x25519Public + ed25519Public

    /**
     * Get the full 64-byte private key (X25519 || Ed25519).
     * @throws IllegalStateException if this identity doesn't have a private key
     */
    fun getPrivateKey(): ByteArray {
        check(hasPrivateKey) { "Identity does not hold a private key" }
        return x25519Private!! + ed25519Private!!
    }

    /**
     * Get the salt used for HKDF key derivation.
     * By default, this is the identity hash.
     */
    fun getSalt(): ByteArray = hash

    /**
     * Get the context used for HKDF key derivation.
     * By default, this is null (no context).
     */
    fun getContext(): ByteArray? = null

    /**
     * Encrypt plaintext for this identity.
     *
     * Uses ephemeral ECDH key exchange with HKDF key derivation.
     * Output format: ephemeral_pub (32) || token (IV || ciphertext || HMAC)
     *
     * @param plaintext Data to encrypt
     * @param ratchet Optional ratchet public key to use instead of identity key
     * @return Encrypted token
     */
    fun encrypt(plaintext: ByteArray, ratchet: ByteArray? = null): ByteArray {
        // Generate ephemeral key pair
        val ephemeralKeyPair = crypto.generateX25519KeyPair()
        val ephemeralPrivate = ephemeralKeyPair.privateKey
        val ephemeralPublic = ephemeralKeyPair.publicKey

        // Determine target public key (use ratchet if provided)
        val targetPublicKey = ratchet ?: x25519Public

        // Perform ECDH
        val sharedKey = crypto.x25519Exchange(ephemeralPrivate, targetPublicKey)

        // Derive encryption key using HKDF
        val derivedKey = crypto.hkdf(
            length = RnsConstants.DERIVED_KEY_LENGTH,
            ikm = sharedKey,
            salt = getSalt(),
            info = getContext()
        )

        // Encrypt with Token
        val token = Token(derivedKey, crypto)
        val ciphertext = token.encrypt(plaintext)

        // Return ephemeral public + ciphertext
        return ephemeralPublic + ciphertext
    }

    /**
     * Decrypt ciphertext that was encrypted for this identity.
     *
     * @param ciphertext The ciphertext token (ephemeral_pub || token)
     * @param ratchets Optional list of ratchet private keys to try
     * @param enforceRatchets If true, only decrypt if a ratchet succeeds
     * @return Decrypted plaintext, or null if decryption fails
     * @throws IllegalStateException if this identity doesn't have a private key
     */
    fun decrypt(
        ciphertext: ByteArray,
        ratchets: List<ByteArray>? = null,
        enforceRatchets: Boolean = false
    ): ByteArray? {
        check(hasPrivateKey) { "Decryption failed because identity does not hold a private key" }

        if (ciphertext.size <= RnsConstants.KEY_SIZE) {
            return null // Token too small
        }

        val peerPublicBytes = ciphertext.copyOfRange(0, RnsConstants.KEY_SIZE)
        val tokenData = ciphertext.copyOfRange(RnsConstants.KEY_SIZE, ciphertext.size)

        var plaintext: ByteArray? = null

        // Try ratchets first
        if (ratchets != null) {
            for (ratchet in ratchets) {
                try {
                    val sharedKey = crypto.x25519Exchange(ratchet, peerPublicBytes)
                    plaintext = decryptWithSharedKey(sharedKey, tokenData)
                    if (plaintext != null) break
                } catch (e: Exception) {
                    // Try next ratchet
                }
            }
        }

        // If ratchet enforcement is on and we didn't decrypt, fail
        if (enforceRatchets && plaintext == null) {
            return null
        }

        // Try regular decryption if ratchets didn't work
        if (plaintext == null) {
            try {
                val sharedKey = crypto.x25519Exchange(x25519Private!!, peerPublicBytes)
                plaintext = decryptWithSharedKey(sharedKey, tokenData)
            } catch (e: Exception) {
                return null
            }
        }

        return plaintext
    }

    private fun decryptWithSharedKey(sharedKey: ByteArray, tokenData: ByteArray): ByteArray? {
        return try {
            val derivedKey = crypto.hkdf(
                length = RnsConstants.DERIVED_KEY_LENGTH,
                ikm = sharedKey,
                salt = getSalt(),
                info = getContext()
            )

            val token = Token(derivedKey, crypto)
            token.decrypt(tokenData)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sign a message with this identity's Ed25519 private key.
     *
     * @param message Message to sign
     * @return 64-byte signature
     * @throws IllegalStateException if this identity doesn't have a private key
     */
    fun sign(message: ByteArray): ByteArray {
        check(hasPrivateKey) { "Signing failed because identity does not hold a private key" }
        return crypto.ed25519Sign(ed25519Private!!, message)
    }

    /**
     * Validate a signature against a message using this identity's public key.
     *
     * @param signature Signature to verify (64 bytes)
     * @param message Original message
     * @return true if signature is valid
     */
    fun validate(signature: ByteArray, message: ByteArray): Boolean {
        return try {
            crypto.ed25519Verify(ed25519Public, message, signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create a copy of this identity with only the public key.
     * Useful for creating a public-only identity to share.
     */
    fun toPublicOnly(): Identity {
        return Identity(
            crypto,
            null,
            x25519Public.copyOf(),
            null,
            ed25519Public.copyOf()
        )
    }

    override fun toString(): String = hexHash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
