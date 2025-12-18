package network.reticulum.destination

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.identity.Identity
import java.util.concurrent.ConcurrentHashMap

/**
 * A Destination describes an endpoint in a Reticulum Network.
 *
 * Destinations are used both to create outgoing and incoming endpoints.
 * The destination type determines if encryption (and what type) is used
 * in communication with the endpoint.
 *
 * A destination can announce its presence on the network, which will
 * distribute the necessary keys for encrypted communication with it.
 *
 * The destination hash is computed as:
 *   full_hash(name_hash || identity_hash)[:16]
 *
 * Where name_hash is:
 *   full_hash(app_name.aspect1.aspect2...encode())[:10]
 */
class Destination private constructor(
    /** The identity associated with this destination (null for PLAIN). */
    val identity: Identity?,
    /** Direction: IN for incoming, OUT for outgoing. */
    val direction: DestinationDirection,
    /** Type: SINGLE, GROUP, PLAIN, or LINK. */
    val type: DestinationType,
    /** The application name. */
    val appName: String,
    /** Additional aspects for the destination name. */
    val aspects: List<String>
) {
    /**
     * The full human-readable destination name.
     * Format: "app_name.aspect1.aspect2....[identity_hexhash]"
     */
    val name: String = buildName(appName, aspects, identity)

    /**
     * The name hash (first 10 bytes of SHA-256 of name without identity).
     */
    val nameHash: ByteArray = computeNameHash(appName, aspects)

    /**
     * The destination hash (first 16 bytes of combined hash).
     */
    val hash: ByteArray = computeHash(nameHash, identity?.hash)

    /**
     * The hex-encoded destination hash.
     */
    val hexHash: String = hash.toHexString()

    /**
     * Whether this destination accepts incoming link requests.
     */
    var acceptLinkRequests: Boolean = true

    /**
     * Callback for incoming packets. Set this to receive packets.
     */
    var packetCallback: ((data: ByteArray, packet: Any) -> Unit)? = null

    /**
     * Callback for incoming link requests. Set this to handle links.
     */
    var linkRequestCallback: ((linkId: ByteArray) -> Boolean)? = null

    /**
     * Symmetric key for GROUP destinations (null for other types).
     */
    private var groupKey: ByteArray? = null

    /**
     * Token for GROUP encryption/decryption (null for other types).
     */
    private var groupToken: Token? = null

    /**
     * List of ratchet private keys for decryption (SINGLE destinations only).
     */
    private val ratchets: MutableList<ByteArray> = mutableListOf()

    /**
     * The ID of the latest ratchet used in encryption (10 bytes, truncated hash).
     */
    var latestRatchetId: ByteArray? = null
        private set

    /**
     * If true, only decrypt with ratchets (fail if identity key needed).
     */
    var enforceRatchets: Boolean = false

    /**
     * Create symmetric keys for a GROUP destination.
     * This generates a random key that can be shared with group members.
     *
     * @throws IllegalStateException if not a GROUP destination
     */
    fun createKeys() {
        require(type == DestinationType.GROUP) { "Only GROUP destinations can have keys created" }
        groupKey = Token.generateKey()
        groupToken = Token(groupKey!!)
    }

    /**
     * Load a private key for a GROUP destination.
     * Use this to join an existing group with a shared key.
     *
     * @param key The shared group key
     * @throws IllegalStateException if not a GROUP destination
     */
    fun loadPrivateKey(key: ByteArray) {
        require(type == DestinationType.GROUP) { "Only GROUP destinations can load private keys" }
        groupKey = key.copyOf()
        groupToken = Token(groupKey!!)
    }

    /**
     * Get the private key for a GROUP destination.
     * Use this to share the key with other group members.
     *
     * @return The group key
     * @throws IllegalStateException if not a GROUP destination or no key set
     */
    fun getPrivateKey(): ByteArray {
        require(type == DestinationType.GROUP) { "Only GROUP destinations have private keys" }
        return groupKey?.copyOf()
            ?: throw IllegalStateException("No private key set for GROUP destination")
    }

    // ===== Ratchet Methods =====

    /**
     * Add a ratchet private key for decryption.
     *
     * @param ratchetPrivateKey The X25519 private key (32 bytes)
     */
    fun addRatchet(ratchetPrivateKey: ByteArray) {
        require(type == DestinationType.SINGLE) { "Only SINGLE destinations support ratchets" }
        require(ratchetPrivateKey.size == RnsConstants.KEY_SIZE) {
            "Ratchet key must be ${RnsConstants.KEY_SIZE} bytes"
        }
        ratchets.add(ratchetPrivateKey.copyOf())
    }

    /**
     * Clear all ratchet private keys.
     */
    fun clearRatchets() {
        ratchets.clear()
    }

    /**
     * Get the number of active ratchets.
     */
    fun ratchetCount(): Int = ratchets.size

    /**
     * Set the ratchet public key for encryption to this destination.
     * This is called when receiving an announce with a ratchet.
     *
     * @param ratchetPublicKey The X25519 public key (32 bytes)
     */
    fun setRatchet(ratchetPublicKey: ByteArray) {
        require(ratchetPublicKey.size == RnsConstants.KEY_SIZE) {
            "Ratchet key must be ${RnsConstants.KEY_SIZE} bytes"
        }
        ratchetStorage[hash.toKey()] = ratchetPublicKey.copyOf()
    }

    /**
     * Get the stored ratchet public key for encrypting to this destination.
     */
    fun getRatchet(): ByteArray? = ratchetStorage[hash.toKey()]?.copyOf()

    companion object {
        /**
         * Storage for ratchet public keys: destination_hash -> ratchet_public_key
         */
        private val ratchetStorage = ConcurrentHashMap<ByteArrayKey, ByteArray>()

        /**
         * Get a ratchet ID (10-byte truncated hash of public key).
         */
        fun getRatchetId(ratchetPublicKey: ByteArray): ByteArray {
            return Hashes.fullHash(ratchetPublicKey).copyOf(RnsConstants.NAME_HASH_BYTES)
        }

        /**
         * Get the stored ratchet for a destination by hash.
         */
        fun getRatchetForDestination(destinationHash: ByteArray): ByteArray? {
            return ratchetStorage[destinationHash.toKey()]?.copyOf()
        }

        /**
         * Store a ratchet for a destination.
         */
        fun setRatchetForDestination(destinationHash: ByteArray, ratchetPublicKey: ByteArray) {
            ratchetStorage[destinationHash.toKey()] = ratchetPublicKey.copyOf()
        }

        /**
         * Clear stored ratchet for a destination.
         */
        fun clearRatchetForDestination(destinationHash: ByteArray) {
            ratchetStorage.remove(destinationHash.toKey())
        }

        /**
         * Create a new destination.
         *
         * @param identity The identity to associate (required for SINGLE/GROUP, must be null for PLAIN)
         * @param direction IN or OUT
         * @param type SINGLE, GROUP, PLAIN, or LINK
         * @param appName Application name (cannot contain dots)
         * @param aspects Additional aspects (none can contain dots)
         * @throws IllegalArgumentException if parameters are invalid
         */
        fun create(
            identity: Identity?,
            direction: DestinationDirection,
            type: DestinationType,
            appName: String,
            vararg aspects: String
        ): Destination {
            // Validate app name
            require(!appName.contains('.')) { "App name cannot contain dots" }

            // Validate aspects
            aspects.forEach { aspect ->
                require(!aspect.contains('.')) { "Aspects cannot contain dots" }
            }

            // Validate identity vs type
            when (type) {
                DestinationType.PLAIN -> {
                    require(identity == null) { "PLAIN destinations cannot hold an identity" }
                }
                DestinationType.SINGLE, DestinationType.GROUP -> {
                    if (direction == DestinationDirection.OUT) {
                        require(identity != null) { "Outbound SINGLE/GROUP destinations require an identity" }
                    }
                }
                DestinationType.LINK -> {
                    // Links handle identity differently
                }
            }

            return Destination(
                identity = identity,
                direction = direction,
                type = type,
                appName = appName,
                aspects = aspects.toList()
            )
        }

        /**
         * Compute a destination hash from app name, aspects, and identity hash.
         *
         * This is a static helper that can be used to compute hashes without
         * creating a full destination.
         */
        fun computeHash(appName: String, aspects: List<String>, identityHash: ByteArray?): ByteArray {
            val nameHash = computeNameHash(appName, aspects)
            return computeHash(nameHash, identityHash)
        }

        /**
         * Compute a destination hash from name hash and identity hash.
         */
        fun computeHash(nameHash: ByteArray, identityHash: ByteArray?): ByteArray {
            val hashMaterial = if (identityHash != null) {
                nameHash + identityHash
            } else {
                nameHash
            }
            return Hashes.truncatedHash(hashMaterial)
        }

        /**
         * Compute the name hash (first 10 bytes of SHA-256).
         */
        fun computeNameHash(appName: String, aspects: List<String>): ByteArray {
            val name = buildNameWithoutIdentity(appName, aspects)
            return Hashes.fullHash(name.toByteArray(Charsets.UTF_8))
                .copyOf(RnsConstants.NAME_HASH_BYTES)
        }

        /**
         * Build the name string without identity.
         */
        private fun buildNameWithoutIdentity(appName: String, aspects: List<String>): String {
            val sb = StringBuilder(appName)
            aspects.forEach { aspect ->
                sb.append('.').append(aspect)
            }
            return sb.toString()
        }

        /**
         * Build the full name string including identity hex hash if present.
         */
        private fun buildName(appName: String, aspects: List<String>, identity: Identity?): String {
            val sb = StringBuilder(appName)
            aspects.forEach { aspect ->
                sb.append('.').append(aspect)
            }
            if (identity != null) {
                sb.append('.').append(identity.hexHash)
            }
            return sb.toString()
        }

        /**
         * Parse app name and aspects from a full name string.
         *
         * @return Pair of (appName, aspects list)
         */
        fun parseFullName(fullName: String): Pair<String, List<String>> {
            val parts = fullName.split('.')
            return if (parts.isEmpty()) {
                Pair("", emptyList())
            } else {
                Pair(parts[0], parts.drop(1))
            }
        }
    }

    /**
     * Encrypt data for this destination.
     *
     * For SINGLE destinations, uses the stored ratchet public key if available.
     *
     * @throws IllegalStateException if destination type doesn't support encryption
     * @throws IllegalStateException if identity is not available
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        return when (type) {
            DestinationType.PLAIN -> plaintext

            DestinationType.SINGLE -> {
                val id = identity ?: throw IllegalStateException(
                    "Cannot encrypt for SINGLE destination without identity"
                )
                // Check for stored ratchet
                val ratchet = getRatchetForDestination(hash)
                if (ratchet != null) {
                    latestRatchetId = getRatchetId(ratchet)
                }
                id.encrypt(plaintext, ratchet)
            }

            DestinationType.GROUP -> {
                val token = groupToken ?: throw IllegalStateException(
                    "Cannot encrypt for GROUP destination without private key. Call createKeys() or loadPrivateKey() first."
                )
                token.encrypt(plaintext)
            }

            DestinationType.LINK -> {
                // Links handle encryption differently
                throw IllegalStateException("Link encryption must be done through the Link class")
            }
        }
    }

    /**
     * Decrypt data from this destination.
     *
     * For SINGLE destinations, tries ratchet keys first, then the identity key
     * (unless enforceRatchets is true).
     *
     * @throws IllegalStateException if identity doesn't have private key
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        return when (type) {
            DestinationType.PLAIN -> ciphertext

            DestinationType.SINGLE -> {
                val id = identity ?: throw IllegalStateException(
                    "Cannot decrypt for SINGLE destination without identity"
                )
                // Use ratchets if available
                if (ratchets.isNotEmpty()) {
                    id.decrypt(ciphertext, ratchets, enforceRatchets)
                } else {
                    id.decrypt(ciphertext)
                }
            }

            DestinationType.GROUP -> {
                val token = groupToken ?: throw IllegalStateException(
                    "Cannot decrypt for GROUP destination without private key. Call createKeys() or loadPrivateKey() first."
                )
                token.decrypt(ciphertext)
            }

            DestinationType.LINK -> {
                throw IllegalStateException("Link decryption must be done through the Link class")
            }
        }
    }

    /**
     * Sign a message using this destination's identity.
     *
     * @throws IllegalStateException if identity is not available or doesn't have private key
     */
    fun sign(message: ByteArray): ByteArray? {
        return when (type) {
            DestinationType.SINGLE -> {
                val id = identity ?: return null
                if (!id.hasPrivateKey) return null
                id.sign(message)
            }
            else -> null
        }
    }

    override fun toString(): String = "<$name:$hexHash>"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Destination) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
