package network.reticulum.interfaces

import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity

/**
 * IFAC (Interface Access Code) utilities for network isolation.
 *
 * IFAC allows different Reticulum networks to coexist without interference.
 * Packets are cryptographically signed using credentials derived from the
 * network name and/or passphrase, and interfaces will reject packets
 * with invalid or missing signatures.
 *
 * Key derivation follows the Python RNS algorithm:
 * 1. Concatenate fullHash(netname) + fullHash(netkey)
 * 2. Hash the concatenation
 * 3. Derive 64-byte key via HKDF with IFAC_SALT
 * 4. Create Identity from the derived key for signing
 */
object IfacUtils {
    /**
     * IFAC_SALT constant from Python RNS - must match exactly for interoperability.
     * This salt is used in the HKDF derivation of the IFAC key.
     */
    val IFAC_SALT: ByteArray = "adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Derive IFAC credentials from network name and/or passphrase.
     *
     * The derivation follows the Python RNS algorithm:
     * - If both netname and netkey are provided: ifac_origin = fullHash(netname) + fullHash(netkey)
     * - If only netname: ifac_origin = fullHash(netname)
     * - If only netkey: ifac_origin = fullHash(netkey)
     * - The origin is hashed again and used as input to HKDF
     *
     * @param netname Network name (ifac_netname in Python)
     * @param netkey Network passphrase (ifac_netkey in Python)
     * @return IfacCredentials with derived key and identity, or null if both inputs are null
     */
    fun deriveIfacCredentials(netname: String?, netkey: String?): IfacCredentials? {
        if (netname == null && netkey == null) return null

        val crypto = defaultCryptoProvider()

        // Build ifac_origin by concatenating hashes
        // This matches: ifac_origin = full_hash(netname) + full_hash(netkey)
        val ifacOrigin = buildList {
            netname?.let { add(Hashes.fullHash(it.toByteArray(Charsets.UTF_8))) }
            netkey?.let { add(Hashes.fullHash(it.toByteArray(Charsets.UTF_8))) }
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        // Hash the concatenated origin
        // This matches: ifac_origin_hash = full_hash(ifac_origin)
        val ifacOriginHash = Hashes.fullHash(ifacOrigin)

        // Derive 64-byte key via HKDF
        // This matches: ifac_key = hkdf(length=64, derive_from=ifac_origin_hash, salt=IFAC_SALT, context=None)
        val ifacKey = crypto.hkdf(64, ifacOriginHash, IFAC_SALT, null)

        // Create identity from key
        // This matches: ifac_identity = Identity.from_bytes(ifac_key)
        val ifacIdentity = Identity.fromBytes(ifacKey)
            ?: throw IllegalStateException("Failed to create IFAC identity from derived key")

        return IfacCredentials(ifacKey, ifacIdentity)
    }
}

/**
 * IFAC credentials derived from network name/passphrase.
 *
 * @property key The 64-byte IFAC key used for packet signing
 * @property identity The Identity used to sign outgoing packets and verify incoming ones
 */
data class IfacCredentials(
    val key: ByteArray,
    val identity: Identity
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IfacCredentials) return false
        return key.contentEquals(other.key)
    }

    override fun hashCode(): Int = key.contentHashCode()
}
