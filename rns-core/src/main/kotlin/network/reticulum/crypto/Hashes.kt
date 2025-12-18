package network.reticulum.crypto

import network.reticulum.common.RnsConstants

/**
 * Hash utility functions matching Python Reticulum's hash operations.
 */
object Hashes {
    private val crypto: CryptoProvider by lazy { defaultCryptoProvider() }

    /**
     * Compute full SHA-256 hash (32 bytes).
     */
    fun fullHash(data: ByteArray): ByteArray = crypto.sha256(data)

    /**
     * Compute truncated hash (first 16 bytes of SHA-256).
     * Used for destination hashes and packet hashes.
     */
    fun truncatedHash(data: ByteArray): ByteArray =
        crypto.sha256(data).copyOf(RnsConstants.TRUNCATED_HASH_BYTES)

    /**
     * Compute name hash (first 10 bytes of SHA-256).
     * Used for destination name hashing.
     */
    fun nameHash(name: String): ByteArray =
        crypto.sha256(name.toByteArray(Charsets.UTF_8)).copyOf(RnsConstants.NAME_HASH_BYTES)

    /**
     * Compute name hash from bytes (first 10 bytes of SHA-256).
     */
    fun nameHash(data: ByteArray): ByteArray =
        crypto.sha256(data).copyOf(RnsConstants.NAME_HASH_BYTES)
}
