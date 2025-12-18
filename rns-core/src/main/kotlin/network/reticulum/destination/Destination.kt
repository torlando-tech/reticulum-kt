package network.reticulum.destination

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.identity.Identity

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

    companion object {
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
                // TODO: Support ratchets
                id.encrypt(plaintext)
            }

            DestinationType.GROUP -> {
                // TODO: Implement group key encryption
                throw UnsupportedOperationException("GROUP encryption not yet implemented")
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
     * @throws IllegalStateException if identity doesn't have private key
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        return when (type) {
            DestinationType.PLAIN -> ciphertext

            DestinationType.SINGLE -> {
                val id = identity ?: throw IllegalStateException(
                    "Cannot decrypt for SINGLE destination without identity"
                )
                // TODO: Support ratchets
                id.decrypt(ciphertext)
            }

            DestinationType.GROUP -> {
                // TODO: Implement group key decryption
                throw UnsupportedOperationException("GROUP decryption not yet implemented")
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
