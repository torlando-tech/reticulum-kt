package network.reticulum.destination

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import org.msgpack.core.MessagePack
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Request access control policy constants.
 */
object RequestPolicy {
    /** No requests are allowed. */
    const val ALLOW_NONE = 0x00

    /** All requests are allowed. */
    const val ALLOW_ALL = 0x01

    /** Only requests from identities in the allowed list are allowed. */
    const val ALLOW_LIST = 0x02
}

/**
 * Request handler configuration.
 *
 * @property path The request path (for reference)
 * @property responseGenerator Function to generate response:
 *   (path: String, data: ByteArray?, requestId: ByteArray, linkId: ByteArray,
 *    remoteIdentity: Identity?, requestedAt: Long) -> ByteArray?
 * @property allow Access control policy (ALLOW_NONE, ALLOW_ALL, ALLOW_LIST)
 * @property allowedList List of allowed identity hashes (only used if allow == ALLOW_LIST)
 * @property autoCompress Whether to automatically compress large responses
 */
data class RequestHandler(
    val path: String,
    val responseGenerator: (
        path: String,
        data: ByteArray?,
        requestId: ByteArray,
        linkId: ByteArray,
        remoteIdentity: Identity?,
        requestedAt: Long
    ) -> ByteArray?,
    val allow: Int,
    val allowedList: List<ByteArray>?,
    val autoCompress: Boolean
)

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
     * Callback for link established events.
     */
    private var linkEstablishedCallback: ((Any) -> Unit)? = null

    /**
     * Default app_data to include in announces.
     * Can be ByteArray or () -> ByteArray.
     */
    private var defaultAppData: Any? = null

    /**
     * Proof strategy for this destination.
     */
    private var proofStrategy: Int = PROVE_NONE

    /**
     * Callback for proof requests (used with PROVE_APP strategy).
     */
    private var proofRequestedCallback: ((Any) -> Boolean)? = null

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

    // ===== Ratchet State =====

    /**
     * Whether ratchets are enabled for this destination.
     */
    private var ratchetsEnabled: Boolean = false

    /**
     * Path to the file where ratchets are persisted.
     */
    private var ratchetsPath: String? = null

    /**
     * Lock for file operations on ratchets.
     */
    private val ratchetFileLock = ReentrantLock()

    /**
     * Maximum number of ratchets to retain.
     */
    private var retainedRatchets: Int = RATCHET_COUNT

    /**
     * Minimum interval between ratchet rotations (milliseconds).
     */
    private var ratchetInterval: Long = RATCHET_ROTATION_INTERVAL

    /**
     * Timestamp of the last ratchet rotation (milliseconds).
     */
    private var lastRatchetRotation: Long = 0L

    /**
     * Current ratchet public key (32 bytes).
     */
    private var ratchetKey: ByteArray? = null

    /**
     * Current ratchet ID (first 10 bytes of ratchet hash).
     */
    private var ratchetId: ByteArray? = null

    /**
     * Request handlers registered for this destination.
     * Maps path hash to RequestHandler.
     */
    private val requestHandlers = ConcurrentHashMap<ByteArrayKey, RequestHandler>()

    /**
     * Path response tag cache for tracking recent path responses.
     * Maps tag (ByteArray) to (timestamp, announce_data).
     */
    private val pathResponses = mutableMapOf<ByteArrayKey, Pair<Long, ByteArray>>()

    /**
     * Lock for path response tag access.
     */
    private val pathResponseLock = ReentrantLock()

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

    /**
     * Enable ratchets on this destination.
     *
     * When ratchets are enabled, Reticulum will automatically rotate the keys used
     * to encrypt packets to this destination, and include the latest ratchet key in
     * announces.
     *
     * Enabling ratchets provides forward secrecy for packets sent to this destination,
     * even when sent outside a Link. Note that normal Link establishment already
     * performs ephemeral key exchange, so ratchets are not necessary for Links.
     *
     * Enabling ratchets will add 32 bytes to every sent announce.
     *
     * @param ratchetsPath The path to a file to store ratchet data in
     * @return true if the operation succeeded, false otherwise
     * @throws IllegalStateException if not a SINGLE/IN destination
     */
    fun enableRatchets(ratchetsPath: String): Boolean {
        require(type == DestinationType.SINGLE) { "Only SINGLE destinations support ratchets" }
        require(direction == DestinationDirection.IN) { "Only IN destinations can enable ratchets" }
        require(ratchetsPath.isNotEmpty()) { "No ratchet file path specified" }

        this.ratchetsPath = ratchetsPath
        this.lastRatchetRotation = 0L
        this.ratchetsEnabled = true  // Enable before calling rotateRatchets

        // Try to reload existing ratchets from disk
        reloadRatchets()

        // If no ratchets exist, create the first one
        if (ratchets.isEmpty()) {
            rotateRatchets()
        } else {
            // Update ratchetKey and ratchetId from current ratchet
            updateRatchetKeyAndId()
        }

        return true
    }

    /**
     * Rotate ratchets by generating a new ratchet key.
     *
     * This creates a new X25519 key pair, prepends it to the ratchets list,
     * trims the list to the maximum size, persists to disk, and updates
     * the current ratchet key and ID.
     *
     * @return true if rotation succeeded, false otherwise
     * @throws IllegalStateException if ratchets are not enabled
     */
    fun rotateRatchets(): Boolean {
        require(ratchetsEnabled) { "Cannot rotate ratchets on $this, ratchets are not enabled" }

        val now = System.currentTimeMillis()

        // Check if enough time has passed since last rotation (skip check on first rotation)
        if (lastRatchetRotation > 0 && now <= lastRatchetRotation + ratchetInterval) {
            return false
        }

        // Generate new ratchet (X25519 private key)
        val crypto = defaultCryptoProvider()
        val newRatchet = crypto.randomBytes(RATCHET_SIZE)

        // Prepend to list
        ratchets.add(0, newRatchet)

        // Trim if too many
        cleanRatchets()

        // Update current ratchet key and ID
        updateRatchetKeyAndId()

        // Persist to disk
        persistRatchets()

        // Update timestamp
        lastRatchetRotation = now

        return true
    }

    /**
     * Persist ratchets to disk.
     *
     * The file format is:
     * - signature (64 bytes): Ed25519 signature of the packed data
     * - packed data: MessagePack-encoded list of ratchet private keys
     *
     * The signature is computed over: identity_hash + packed_ratchets
     *
     * @throws IllegalStateException if ratchets are not enabled or identity lacks private key
     * @throws java.io.IOException if file write fails
     */
    private fun persistRatchets() {
        val path = ratchetsPath ?: throw IllegalStateException("No ratchets path set")
        val id = identity ?: throw IllegalStateException("Cannot persist ratchets without identity")
        require(id.hasPrivateKey) { "Cannot persist ratchets without private key" }

        ratchetFileLock.withLock {
            try {
                // Serialize ratchets list using MessagePack
                val packer = MessagePack.newDefaultBufferPacker()
                packer.packArrayHeader(ratchets.size)
                for (ratchet in ratchets) {
                    packer.packBinaryHeader(ratchet.size)
                    packer.writePayload(ratchet)
                }
                val packedRatchets = packer.toByteArray()

                // Create signed data: identity_hash + packed_ratchets
                val signedData = hash + packedRatchets

                // Sign with identity private key
                val signature = id.sign(signedData)

                // Write to temporary file first
                val file = File(path)
                val tempFile = File("$path.tmp")

                // Ensure parent directories exist
                file.parentFile?.mkdirs()

                // Write signature + packed data
                tempFile.writeBytes(signature + packedRatchets)

                // Atomic rename
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                throw java.io.IOException("Could not write ratchet file for $this: ${e.message}", e)
            }
        }
    }

    /**
     * Reload ratchets from disk.
     *
     * Reads the ratchet file, verifies the signature, and deserializes the ratchet list.
     *
     * @return true if ratchets were successfully loaded, false if file doesn't exist
     * @throws IllegalStateException if identity is missing
     * @throws java.io.IOException if file read or verification fails
     */
    private fun reloadRatchets(): Boolean {
        val path = ratchetsPath ?: throw IllegalStateException("No ratchets path set")
        val id = identity ?: throw IllegalStateException("Cannot reload ratchets without identity")

        val file = File(path)
        if (!file.exists()) {
            return false
        }

        ratchetFileLock.withLock {
            try {
                // Read file
                val fileData = file.readBytes()

                // Extract signature (first 64 bytes) and packed data
                if (fileData.size < 64) {
                    throw java.io.IOException("Ratchet file too small")
                }

                val signature = fileData.copyOfRange(0, 64)
                val packedRatchets = fileData.copyOfRange(64, fileData.size)

                // Verify signature against identity_hash + packed_ratchets
                val signedData = hash + packedRatchets
                if (!id.validate(signature, signedData)) {
                    throw SecurityException("Invalid ratchet file signature for $this")
                }

                // Deserialize ratchet list
                val unpacker = MessagePack.newDefaultUnpacker(packedRatchets)
                val arraySize = unpacker.unpackArrayHeader()
                val loadedRatchets = mutableListOf<ByteArray>()
                for (i in 0 until arraySize) {
                    val binarySize = unpacker.unpackBinaryHeader()
                    val ratchet = ByteArray(binarySize)
                    unpacker.readPayload(ratchet)
                    loadedRatchets.add(ratchet)
                }

                // Replace current ratchets
                ratchets.clear()
                ratchets.addAll(loadedRatchets)

                return true
            } catch (e: Exception) {
                throw java.io.IOException("Could not read ratchet file for $this: ${e.message}", e)
            }
        }
    }

    /**
     * Clean ratchets by trimming to the maximum retained count.
     */
    private fun cleanRatchets() {
        while (ratchets.size > retainedRatchets) {
            ratchets.removeAt(ratchets.size - 1)
        }
    }

    /**
     * Update ratchetKey and ratchetId from the current (first) ratchet.
     */
    private fun updateRatchetKeyAndId() {
        if (ratchets.isEmpty()) {
            ratchetKey = null
            ratchetId = null
            return
        }

        // Get the first (most recent) ratchet private key
        val ratchetPrivate = ratchets[0]

        // Derive public key
        val crypto = defaultCryptoProvider()
        val ratchetPublic = crypto.x25519PublicFromPrivate(ratchetPrivate)

        // Set ratchetKey
        ratchetKey = ratchetPublic

        // Compute ratchetId (first 10 bytes of full hash)
        ratchetId = Hashes.fullHash(ratchetPublic).copyOf(RATCHET_ID_SIZE)
    }

    /**
     * Get the current ratchet ID.
     *
     * @return The ratchet ID (10 bytes) or null if ratchets are disabled
     */
    fun getRatchetId(): ByteArray? = ratchetId?.copyOf()

    /**
     * Get the current ratchet public key.
     *
     * @return The ratchet public key (32 bytes) or null if ratchets are disabled
     */
    fun getRatchetKey(): ByteArray? = ratchetKey?.copyOf()

    /**
     * Set the number of retained ratchets.
     *
     * This determines how many old ratchet keys are kept for decryption.
     * Defaults to RATCHET_COUNT (512).
     *
     * @param count The number of ratchets to retain (must be > 0)
     * @return true if successful, false otherwise
     */
    fun setRetainedRatchets(count: Int): Boolean {
        if (count <= 0) return false
        retainedRatchets = count
        cleanRatchets()
        return true
    }

    /**
     * Set the ratchet rotation interval.
     *
     * This is the minimum time between ratchet rotations.
     * Defaults to RATCHET_ROTATION_INTERVAL (30 days).
     *
     * @param interval The interval in milliseconds (must be > 0)
     * @return true if successful, false otherwise
     */
    fun setRatchetInterval(interval: Long): Boolean {
        if (interval <= 0) return false
        ratchetInterval = interval
        return true
    }

    // ===== Request Handler Methods =====

    /**
     * Register a request handler for a specific path.
     *
     * The response generator function will be called when a request is received
     * with the matching path hash. The function should return the response data
     * as ByteArray, or null if no response should be sent.
     *
     * @param path The request path (will be hashed)
     * @param responseGenerator Function to generate response. Receives:
     *   - path: The original path string
     *   - data: The request data (may be null)
     *   - requestId: Unique identifier for this request
     *   - linkId: The link ID the request came from
     *   - remoteIdentity: The identity of the requester (may be null)
     *   - requestedAt: Timestamp when the request was made (milliseconds since epoch)
     * @param allow Access control policy (RequestPolicy.ALLOW_NONE/ALL/LIST)
     * @param allowedList List of allowed identity hashes (only used if allow == ALLOW_LIST)
     * @param autoCompress Whether to automatically compress large responses
     * @return true if registered successfully
     * @throws IllegalArgumentException if path is empty or allow policy is invalid
     */
    fun registerRequestHandler(
        path: String,
        responseGenerator: (
            path: String,
            data: ByteArray?,
            requestId: ByteArray,
            linkId: ByteArray,
            remoteIdentity: Identity?,
            requestedAt: Long
        ) -> ByteArray?,
        allow: Int = RequestPolicy.ALLOW_NONE,
        allowedList: List<ByteArray>? = null,
        autoCompress: Boolean = true
    ): Boolean {
        require(path.isNotEmpty()) { "Invalid path specified" }
        require(allow in listOf(RequestPolicy.ALLOW_NONE, RequestPolicy.ALLOW_ALL, RequestPolicy.ALLOW_LIST)) {
            "Invalid request policy"
        }

        val pathHash = Hashes.truncatedHash(path.toByteArray(Charsets.UTF_8))
        val handler = RequestHandler(
            path = path,
            responseGenerator = responseGenerator,
            allow = allow,
            allowedList = allowedList,
            autoCompress = autoCompress
        )

        requestHandlers[pathHash.toKey()] = handler
        return true
    }

    /**
     * Deregister a request handler for a specific path.
     *
     * @param path The request path to deregister
     * @return true if a handler was removed, false if no handler was registered
     */
    fun deregisterRequestHandler(path: String): Boolean {
        val pathHash = Hashes.truncatedHash(path.toByteArray(Charsets.UTF_8))
        return requestHandlers.remove(pathHash.toKey()) != null
    }

    /**
     * Get a request handler by path hash.
     *
     * @param pathHash The truncated hash of the request path
     * @return The RequestHandler if found, null otherwise
     */
    internal fun getRequestHandler(pathHash: ByteArray): RequestHandler? {
        return requestHandlers[pathHash.toKey()]
    }

    companion object {
        /**
         * Ratchet key size in bytes (32 bytes for X25519).
         */
        const val RATCHET_SIZE = 32

        /**
         * Ratchet ID size in bytes (first 10 bytes of hash).
         */
        const val RATCHET_ID_SIZE = 10

        /**
         * Default ratchet rotation interval (30 minutes in milliseconds).
         */
        const val RATCHET_ROTATION_INTERVAL = 1_800_000L  // 30 minutes (matches Python RNS)

        /**
         * Default maximum number of ratchets to retain.
         */
        const val RATCHET_COUNT = 512

        /**
         * Path response tag window (30 seconds in milliseconds).
         * Tags within this window are considered duplicates.
         */
        const val PR_TAG_WINDOW = 30_000L  // 30 seconds

        /**
         * Proof strategy: Never send proofs.
         */
        const val PROVE_NONE = 0x21

        /**
         * Proof strategy: Let the application decide via callback.
         */
        const val PROVE_APP = 0x22

        /**
         * Proof strategy: Always send proofs when requested.
         */
        const val PROVE_ALL = 0x23

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

        // ===== Static Utility Methods =====

        /**
         * Expand a destination name to its full human-readable form.
         *
         * This builds the full destination name from an app name, aspects, and
         * optionally an identity. The format is:
         *   "app_name.aspect1.aspect2...[.identity_hexhash]"
         *
         * @param identity The identity to include (optional)
         * @param appName The application name
         * @param aspects Additional aspects for the destination
         * @return The full destination name
         * @throws IllegalArgumentException if app name or aspects contain dots
         */
        fun expandName(identity: Identity?, appName: String, vararg aspects: String): String {
            require(!appName.contains('.')) { "Dots can't be used in app names" }

            val sb = StringBuilder(appName)
            aspects.forEach { aspect ->
                require(!aspect.contains('.')) { "Dots can't be used in aspects" }
                sb.append('.').append(aspect)
            }

            if (identity != null) {
                sb.append('.').append(identity.hexHash)
            }

            return sb.toString()
        }

        /**
         * Compute a destination hash from identity, app name, and aspects.
         *
         * The hash is computed as:
         *   truncated_hash(name_hash || identity_hash)
         *
         * Where name_hash is the first 10 bytes of SHA-256(app_name.aspect1.aspect2...)
         *
         * @param identity The identity (can be null for PLAIN destinations)
         * @param appName The application name
         * @param aspects Additional aspects
         * @return The 16-byte destination hash
         * @throws IllegalArgumentException if app name or aspects contain dots
         */
        fun hash(identity: Identity?, appName: String, vararg aspects: String): ByteArray {
            // Compute name hash (without identity)
            val nameHash = computeNameHash(appName, aspects.toList())

            // Combine with identity hash if present
            val hashMaterial = if (identity != null) {
                nameHash + identity.hash
            } else {
                nameHash
            }

            // Return truncated hash (16 bytes)
            return Hashes.truncatedHash(hashMaterial)
        }

        /**
         * Compute a destination hash from a full name and identity.
         *
         * This parses the full name into app name and aspects, then computes
         * the hash using the standard algorithm.
         *
         * @param fullName The full destination name (without identity hex hash)
         * @param identity The identity (can be null)
         * @return The 16-byte destination hash
         * @throws IllegalArgumentException if full name is invalid
         */
        fun hashFromNameAndIdentity(fullName: String, identity: Identity?): ByteArray {
            val parsed = appAndAspectsFromName(fullName)
                ?: throw IllegalArgumentException("Invalid full name: $fullName")
            return hash(identity, parsed.first, *parsed.second.toTypedArray())
        }

        /**
         * Parse a full destination name into app name and aspects.
         *
         * The input should be in the format "app_name.aspect1.aspect2..."
         * (without the identity hex hash).
         *
         * @param fullName The full destination name
         * @return A Pair of (appName, list of aspects), or null if invalid
         */
        fun appAndAspectsFromName(fullName: String): Pair<String, List<String>>? {
            if (fullName.isEmpty()) return null

            val parts = fullName.split('.')
            return if (parts.isEmpty()) {
                null
            } else {
                Pair(parts[0], parts.drop(1))
            }
        }
    }

    /**
     * Encrypt data for this destination.
     *
     * For SINGLE destinations, uses the stored ratchet public key if available.
     * The ratchet is retrieved from the destination hash and used to modify the
     * encryption key derivation.
     *
     * @param plaintext The data to encrypt
     * @return The encrypted ciphertext
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

                // Check for stored ratchet public key for this destination
                val ratchet = getRatchetForDestination(hash)

                // Debug logging
                println("[Destination] encrypt() for ${hash.toHexString()}: ratchet=${if (ratchet != null) "present (${ratchet.size} bytes)" else "null"}")

                // If a ratchet is available, store its ID for tracking
                if (ratchet != null) {
                    latestRatchetId = getRatchetId(ratchet)
                }

                // Encrypt using the identity, passing the ratchet if available
                // The Identity.encrypt() method will use the ratchet to modify key derivation
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
     * For SINGLE destinations, tries ratchet private keys first (if available),
     * then falls back to the identity private key (unless enforceRatchets is true).
     *
     * The decryption process:
     * 1. If this destination has ratchet private keys, try each one in order (newest first)
     * 2. If a ratchet successfully decrypts, store its ID in latestRatchetId
     * 3. If all ratchets fail and enforceRatchets is false, try the base identity key
     * 4. If enforceRatchets is true and no ratchet works, return null
     *
     * @param ciphertext The encrypted data to decrypt
     * @return The decrypted plaintext, or null if decryption fails
     * @throws IllegalStateException if identity doesn't have private key
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        return when (type) {
            DestinationType.PLAIN -> ciphertext

            DestinationType.SINGLE -> {
                val id = identity ?: throw IllegalStateException(
                    "Cannot decrypt for SINGLE destination without identity"
                )

                var plaintext: ByteArray? = null

                // If we have ratchet private keys for decryption, try them individually
                // so we can track which one worked
                if (ratchets.isNotEmpty()) {
                    val crypto = defaultCryptoProvider()

                    // Try each ratchet in order (newest first)
                    for (ratchet in ratchets) {
                        try {
                            // Try decrypting with this single ratchet
                            plaintext = id.decrypt(ciphertext, listOf(ratchet), enforceRatchets = true)

                            if (plaintext != null) {
                                // Success! Compute and store the ratchet ID
                                val ratchetPublic = crypto.x25519PublicFromPrivate(ratchet)
                                latestRatchetId = Hashes.fullHash(ratchetPublic).copyOf(RATCHET_ID_SIZE)
                                break
                            }
                        } catch (e: Exception) {
                            // Try next ratchet
                            continue
                        }
                    }

                    // If ratchets didn't work and enforcement is off, try base key
                    if (plaintext == null && !enforceRatchets) {
                        try {
                            plaintext = id.decrypt(ciphertext, null, enforceRatchets = false)
                            // If base key worked, clear ratchet ID
                            if (plaintext != null) {
                                latestRatchetId = null
                            }
                        } catch (e: Exception) {
                            // Decryption failed
                        }
                    }
                } else {
                    // No ratchets available, use base identity decryption
                    plaintext = id.decrypt(ciphertext, null, enforceRatchets)
                    // Clear ratchet ID since we used base key
                    if (plaintext != null) {
                        latestRatchetId = null
                    }
                }

                plaintext
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

    // ===== Default App Data Methods =====

    /**
     * Set default app_data to include in announces.
     *
     * If set, the default app_data will be included in every announce sent by
     * this destination, unless other app_data is specified in the announce() method.
     *
     * @param appData The default app_data as ByteArray
     */
    fun setDefaultAppData(appData: ByteArray?) {
        defaultAppData = appData
    }

    /**
     * Set default app_data to include in announces using a callback.
     *
     * The callback will be invoked each time an announce is sent to get the
     * current app_data.
     *
     * @param appDataCallback A function that returns ByteArray for app_data
     */
    fun setDefaultAppData(appDataCallback: (() -> ByteArray)?) {
        defaultAppData = appDataCallback
    }

    /**
     * Clear any default app_data previously set.
     */
    fun clearDefaultAppData() {
        defaultAppData = null
    }

    /**
     * Get the current default app_data.
     *
     * If a callback was set, this will invoke it and return the result.
     *
     * @return The app_data ByteArray, or null if not set
     */
    fun getDefaultAppData(): ByteArray? {
        return when (val data = defaultAppData) {
            is ByteArray -> data
            is Function0<*> -> {
                @Suppress("UNCHECKED_CAST")
                (data as? (() -> ByteArray))?.invoke()
            }
            else -> null
        }
    }

    // ===== Proof Strategy Methods =====

    /**
     * Set the proof strategy for this destination.
     *
     * The proof strategy determines whether this destination will send proofs
     * when requested by incoming packets.
     *
     * @param strategy One of PROVE_NONE, PROVE_APP, or PROVE_ALL
     * @return true if the strategy was set successfully
     */
    fun setProofStrategy(strategy: Int): Boolean {
        if (strategy !in listOf(PROVE_NONE, PROVE_APP, PROVE_ALL)) {
            return false
        }
        proofStrategy = strategy
        return true
    }

    /**
     * Get the current proof strategy.
     *
     * @return The current proof strategy (PROVE_NONE, PROVE_APP, or PROVE_ALL)
     */
    fun getProofStrategy(): Int = proofStrategy

    /**
     * Set the callback for proof requests.
     *
     * When the proof strategy is PROVE_APP, this callback will be called to
     * determine whether a proof should be sent for a specific packet.
     *
     * @param callback A function that receives a packet and returns true to send
     *                 a proof or false to not send one
     */
    fun setProofRequestedCallback(callback: ((Any) -> Boolean)?) {
        proofRequestedCallback = callback
    }

    // ===== Callback Setters =====

    /**
     * Register a callback for link established events.
     *
     * @param callback A function to be called when a link is established.
     *                 Receives the Link object
     */
    fun setLinkEstablishedCallback(callback: ((Any) -> Unit)?) {
        linkEstablishedCallback = callback
    }

    /**
     * Check whether this destination accepts incoming link requests.
     *
     * @return true if the destination accepts links
     */
    fun acceptsLinks(): Boolean = acceptLinkRequests

    /**
     * Invoke the link established callback with the given link.
     *
     * @param link The established link
     */
    fun invokeLinkEstablished(link: Any) {
        linkEstablishedCallback?.invoke(link)
    }

    // ===== Ratchet Configuration Setters =====

    /**
     * Enable or disable ratchet enforcement.
     *
     * When enabled, this destination will only accept packets encrypted with
     * ratchet keys, not the base identity key.
     *
     * @param enforce true to enable enforcement, false to disable
     */
    fun enforceRatchets(enforce: Boolean) {
        enforceRatchets = enforce
    }

    /**
     * Check if a path response with this tag was recently processed.
     * Returns true if duplicate with cached data, false if new.
     *
     * @param tag The path response tag to check
     * @return true if duplicate, false if new
     */
    fun isDuplicatePathResponse(tag: ByteArray): Boolean {
        pathResponseLock.withLock {
            cleanStalePathResponses()

            // Check if tag exists with non-empty cached data
            val key = tag.toKey()
            val cached = pathResponses[key]
            return cached != null && cached.second.isNotEmpty()
        }
    }

    /**
     * Get cached announce data for a path response tag if available.
     *
     * @param tag The path response tag
     * @return Cached announce data, or null if not available
     */
    fun getCachedPathResponse(tag: ByteArray): ByteArray? {
        pathResponseLock.withLock {
            cleanStalePathResponses()
            val key = tag.toKey()
            val cached = pathResponses[key]
            return if (cached != null && cached.second.isNotEmpty()) {
                cached.second.copyOf()
            } else {
                null
            }
        }
    }

    /**
     * Store announce data for a path response tag.
     *
     * @param tag The path response tag
     * @param announceData The announce data to cache
     */
    fun cachePathResponse(tag: ByteArray, announceData: ByteArray) {
        pathResponseLock.withLock {
            val key = tag.toKey()
            pathResponses[key] = Pair(System.currentTimeMillis(), announceData.copyOf())
        }
    }

    /**
     * Clean expired path response entries.
     */
    private fun cleanStalePathResponses() {
        val now = System.currentTimeMillis()
        val staleKeys = pathResponses.entries
            .filter { now - it.value.first > PR_TAG_WINDOW }
            .map { it.key }
            .toList()
        staleKeys.forEach { pathResponses.remove(it) }
    }

    /**
     * Generate announce data for this destination.
     *
     * @param id The identity to use for signing
     * @param appData Optional application data to include
     * @return Pair of (announceData, hasRatchet)
     */
    private fun generateAnnounceData(id: Identity, appData: ByteArray?): Pair<ByteArray, Boolean> {
        // Generate random hash component (5 random bytes + 5 timestamp bytes)
        val randomPart = Hashes.getRandomHash().copyOf(5)
        val timestamp = System.currentTimeMillis() / 1000 // Convert to seconds
        val timestampBytes = ByteArray(5) { i ->
            ((timestamp shr (8 * (4 - i))) and 0xFF).toByte()
        }
        val randomHash = randomPart + timestampBytes

        // Use provided app_data or fall back to default app_data
        val actualAppData = appData ?: getDefaultAppData()

        // Check if we should include a ratchet
        val ratchet: ByteArray
        val hasRatchet: Boolean

        if (ratchetsEnabled && ratchets.isNotEmpty()) {
            // Rotate ratchets if interval has passed
            rotateRatchets()

            // Use the first (most recent) ratchet
            val ratchetPrivate = ratchets[0]
            // Get public key from private key
            val ratchetPub = network.reticulum.crypto.defaultCryptoProvider().x25519PublicFromPrivate(ratchetPrivate)
            ratchet = ratchetPub
            hasRatchet = true

            // Store ratchet for this destination
            setRatchetForDestination(hash, ratchet)
        } else {
            ratchet = byteArrayOf()
            hasRatchet = false
        }

        // Build signed data: destination_hash + public_key + name_hash + random_hash + ratchet + app_data
        val publicKey = id.getPublicKey()
        val signedData = hash + publicKey + nameHash + randomHash + ratchet + (actualAppData ?: byteArrayOf())

        // Sign the data
        val signature = id.sign(signedData)

        // Build announce data: public_key + name_hash + random_hash + [ratchet] + signature + [app_data]
        val announceData = if (hasRatchet) {
            publicKey + nameHash + randomHash + ratchet + signature + (actualAppData ?: byteArrayOf())
        } else {
            publicKey + nameHash + randomHash + signature + (actualAppData ?: byteArrayOf())
        }

        return Pair(announceData, hasRatchet)
    }

    /**
     * Create and send an announce packet for this destination.
     *
     * Announces broadcast the destination's public keys and optional application data
     * to the network. Only SINGLE/IN destinations can announce.
     *
     * @param appData Optional application-specific data to include in the announce
     * @param pathResponse Whether this is a path response (used internally by Transport)
     * @param tag Optional tag for path response deduplication
     * @param send If true, send the packet immediately; if false, return the packet
     * @return The announce Packet if send=false, null otherwise
     * @throws IllegalStateException if destination cannot announce
     */
    fun announce(appData: ByteArray? = null, pathResponse: Boolean = false, tag: ByteArray? = null, send: Boolean = true): network.reticulum.packet.Packet? {
        require(type == DestinationType.SINGLE) { "Only SINGLE destination types can be announced" }
        require(direction == DestinationDirection.IN) { "Only IN destination types can be announced" }

        val id = identity ?: throw IllegalStateException("Cannot announce destination without identity")
        require(id.hasPrivateKey) { "Cannot announce destination without private key" }

        // For path responses with a tag, check for cached announce data
        val announceData: ByteArray
        val hasRatchet: Boolean

        if (pathResponse && tag != null) {
            val cachedData = getCachedPathResponse(tag)
            if (cachedData != null) {
                // Use cached announce data for multi-path support
                println("Using cached announce data for path response with tag ${tag.toHexString()}")
                announceData = cachedData
                // Determine hasRatchet from cached data - if ratchets are enabled, assume it has one
                hasRatchet = ratchetsEnabled && ratchets.isNotEmpty()
            } else {
                // Generate new announce data
                val result = generateAnnounceData(id, appData)
                announceData = result.first
                hasRatchet = result.second

                // Cache the announce data for this tag
                cachePathResponse(tag, announceData)
            }
        } else {
            // Generate new announce data
            val result = generateAnnounceData(id, appData)
            announceData = result.first
            hasRatchet = result.second

            // Cache for path responses with a tag
            if (tag != null) {
                cachePathResponse(tag, announceData)
            }
        }

        // Determine context and context flag
        val context = if (pathResponse) {
            network.reticulum.common.PacketContext.PATH_RESPONSE
        } else {
            network.reticulum.common.PacketContext.NONE
        }

        val contextFlag = if (hasRatchet) {
            network.reticulum.common.ContextFlag.SET
        } else {
            network.reticulum.common.ContextFlag.UNSET
        }

        // Create announce packet
        val announcePacket = network.reticulum.packet.Packet.createRaw(
            destinationHash = hash,
            data = announceData,
            packetType = network.reticulum.common.PacketType.ANNOUNCE,
            destinationType = type,
            context = context,
            transportType = network.reticulum.common.TransportType.BROADCAST,
            contextFlag = contextFlag
        )

        if (send) {
            // Send via Transport
            network.reticulum.transport.Transport.outbound(announcePacket)
            return null
        } else {
            return announcePacket
        }
    }

    /**
     * Process an incoming packet for this destination.
     *
     * This method handles decryption and routing of packets to the appropriate
     * callbacks based on packet type.
     *
     * @param packet The incoming packet
     * @return true if the packet was processed successfully
     */
    fun receive(packet: network.reticulum.packet.Packet): Boolean {
        return try {
            when (packet.packetType) {
                network.reticulum.common.PacketType.LINKREQUEST -> {
                    // Link requests are not encrypted
                    val plaintext = packet.data
                    // TODO: Handle incoming link request
                    // For now, just invoke callback if set
                    linkRequestCallback?.invoke(packet.destinationHash) ?: false
                }

                network.reticulum.common.PacketType.DATA -> {
                    // Decrypt the packet data
                    val plaintext = decrypt(packet.data)
                    if (plaintext == null) {
                        false
                    } else {
                        // Invoke packet callback if set
                        packetCallback?.invoke(plaintext, packet)
                        true
                    }
                }

                else -> {
                    // Other packet types (ANNOUNCE, PROOF) are not delivered to destinations
                    false
                }
            }
        } catch (e: Exception) {
            false
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
