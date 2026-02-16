package network.reticulum.identity

import network.reticulum.common.ByteArrayKey
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

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

    /**
     * The Ed25519 signing public key (32 bytes).
     */
    val sigPub: ByteArray
        get() = ed25519Public.copyOf()

    /**
     * The Ed25519 signing private key (32 bytes).
     * @throws IllegalStateException if this identity doesn't have a private key
     */
    val sigPrv: ByteArray
        get() {
            check(hasPrivateKey) { "Identity does not hold a private key" }
            return ed25519Private!!.copyOf()
        }

    /**
     * Data stored for known identities.
     */
    data class IdentityData(
        val timestamp: Long,
        val packetHash: ByteArray,
        val publicKey: ByteArray,
        val appData: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityData) return false
            return timestamp == other.timestamp &&
                packetHash.contentEquals(other.packetHash) &&
                publicKey.contentEquals(other.publicKey) &&
                (appData?.contentEquals(other.appData ?: byteArrayOf()) ?: (other.appData == null))
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + packetHash.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            result = 31 * result + (appData?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /**
         * Storage for known destinations: destination_hash -> IdentityData
         */
        private val knownDestinations = ConcurrentHashMap<ByteArrayKey, IdentityData>()

        /**
         * Index for identity hash lookups: identity_hash -> destination_hash
         */
        private val identityHashIndex = ConcurrentHashMap<ByteArrayKey, ByteArray>()

        /**
         * Storage for ratchets: destination_hash -> list of (ratchet, timestamp) pairs
         */
        private val destinationRatchets = ConcurrentHashMap<ByteArrayKey, MutableList<RatchetEntry>>()

        /**
         * Ratchet entry with timestamp for expiry tracking.
         */
        data class RatchetEntry(val ratchet: ByteArray, val timestamp: Long) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is RatchetEntry) return false
                return ratchet.contentEquals(other.ratchet) && timestamp == other.timestamp
            }

            override fun hashCode(): Int {
                var result = ratchet.contentHashCode()
                result = 31 * result + timestamp.hashCode()
                return result
            }
        }

        /**
         * Ratchet expiry time in milliseconds (30 days).
         */
        const val RATCHET_EXPIRY = 2_592_000_000L  // 30 days in ms

        /**
         * Lock for ratchet disk persistence.
         */
        private val ratchetPersistLock = ReentrantLock()

        /**
         * Storage path for persisting known destinations.
         * Set by Reticulum during initialization.
         */
        @Volatile
        var storagePath: String = System.getProperty("user.home") + "/.reticulum"
            private set

        /**
         * Get the ratchet storage directory path.
         */
        private val ratchetPath: String
            get() = "$storagePath/ratchets"

        /**
         * Flag to prevent concurrent saves.
         */
        @Volatile
        private var savingKnownDestinations = false

        /**
         * Set the storage path for known destinations.
         * Called by Reticulum during initialization.
         *
         * @param path The storage directory path
         */
        internal fun setStoragePath(path: String) {
            storagePath = path
        }

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
         * Create an identity from private key bytes.
         *
         * The private key format is: X25519 private (32) || Ed25519 private (32) = 64 bytes
         *
         * @param prvBytes The private key bytes (64 bytes)
         * @param crypto The crypto provider to use
         * @return The loaded identity, or null if the bytes are invalid
         */
        fun fromBytes(
            prvBytes: ByteArray,
            crypto: CryptoProvider = defaultCryptoProvider()
        ): Identity? {
            return try {
                fromPrivateKey(prvBytes, crypto)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Load an identity from a file.
         *
         * @param path The path to the identity file
         * @param crypto The crypto provider to use
         * @return The loaded identity, or null if the file doesn't exist or is invalid
         */
        fun fromFile(
            path: String,
            crypto: CryptoProvider = defaultCryptoProvider()
        ): Identity? {
            return try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    return null
                }
                val prvBytes = file.readBytes()
                fromBytes(prvBytes, crypto)
            } catch (e: Exception) {
                null
            }
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
         * Recall a known identity by its destination hash.
         * Returns null if no identity is known for this hash.
         *
         * This method first checks the known destinations cache, then falls back
         * to checking registered destinations in Transport.
         *
         * @param hash The destination hash to look up
         * @return The Identity, or null if not found
         */
        fun recall(hash: ByteArray): Identity? {
            // Check known destinations cache first
            val data = knownDestinations[hash.toKey()]
            if (data != null) {
                return try {
                    fromPublicKey(data.publicKey)
                } catch (e: Exception) {
                    null
                }
            }

            // Fallback: check Transport registered destinations
            try {
                val transport = network.reticulum.transport.Transport
                for (destination in transport.getDestinations()) {
                    if (hash.contentEquals(destination.hash)) {
                        val destIdentity = destination.identity
                        if (destIdentity != null) {
                            return try {
                                fromPublicKey(destIdentity.getPublicKey())
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Transport might not be initialized
            }

            return null
        }

        /**
         * Recall a known identity by its identity hash (truncated hash of public key).
         * Returns null if no identity is known for this hash.
         *
         * @param identityHash The identity hash to look up
         * @return The Identity, or null if not found
         */
        fun recallByIdentityHash(identityHash: ByteArray): Identity? {
            val destHash = identityHashIndex[identityHash.toKey()] ?: return null
            return recall(destHash)
        }

        /**
         * Recall app_data for a known destination.
         *
         * @param hash The destination hash
         * @return The app_data, or null if not found
         */
        fun recallAppData(hash: ByteArray): ByteArray? {
            return knownDestinations[hash.toKey()]?.appData?.copyOf()
        }

        /**
         * Remember an identity after successful announce validation.
         * This stores the identity data for later recall.
         *
         * @param packetHash The hash of the announce packet
         * @param destHash The destination hash
         * @param publicKey The 64-byte public key
         * @param appData Optional application data from the announce
         */
        fun remember(
            packetHash: ByteArray,
            destHash: ByteArray,
            publicKey: ByteArray,
            appData: ByteArray? = null
        ) {
            val data = IdentityData(
                timestamp = System.currentTimeMillis(),
                packetHash = packetHash.copyOf(),
                publicKey = publicKey.copyOf(),
                appData = appData?.copyOf()
            )
            knownDestinations[destHash.toKey()] = data

            // Index by identity hash for reverse lookups
            val identityHash = Hashes.truncatedHash(publicKey)
            identityHashIndex[identityHash.toKey()] = destHash.copyOf()
        }

        /**
         * Check if a destination is known.
         *
         * @param hash The destination hash
         * @return true if the identity is stored
         */
        fun isKnown(hash: ByteArray): Boolean {
            return knownDestinations.containsKey(hash.toKey())
        }

        /**
         * Clear all stored identities.
         * Useful for testing or resetting state.
         */
        fun clearKnownDestinations() {
            knownDestinations.clear()
            identityHashIndex.clear()
        }

        /**
         * Get the number of known destinations.
         */
        fun knownDestinationCount(): Int = knownDestinations.size

        /**
         * Validate an announce packet and extract the announced identity.
         *
         * This method verifies the signature of an announce packet and, if valid,
         * stores the identity for later recall. It matches the Python RNS
         * Identity.validate_announce() method.
         *
         * Announce packet data format (without ratchet):
         *   public_key (64) + name_hash (10) + random_hash (10) + signature (64) + app_data (var)
         *
         * Announce packet data format (with ratchet):
         *   public_key (64) + name_hash (10) + random_hash (10) + ratchet (32) + signature (64) + app_data (var)
         *
         * Signed data format:
         *   destination_hash + public_key + name_hash + random_hash + ratchet + app_data
         *
         * @param packet The announce packet to validate
         * @param onlyValidateSignature If true, only validate signature without storing
         * @return The announced Identity if valid, null otherwise
         */
        fun validateAnnounce(packet: network.reticulum.packet.Packet, onlyValidateSignature: Boolean = false): Identity? {
            return try {
                if (packet.packetType != network.reticulum.common.PacketType.ANNOUNCE) {
                    return null
                }

                val keySize = RnsConstants.FULL_KEY_SIZE
                val ratchetSize = RnsConstants.KEY_SIZE
                val nameHashLen = RnsConstants.NAME_HASH_BYTES
                val sigLen = RnsConstants.SIGNATURE_SIZE
                val destinationHash = packet.destinationHash

                val data = packet.data
                if (data.size < keySize + nameHashLen + 10 + sigLen) {
                    return null // Packet too small
                }

                // Extract public key
                val publicKey = data.copyOfRange(0, keySize)

                // Check context flag to determine if ratchet is present
                val hasRatchet = packet.contextFlag == network.reticulum.common.ContextFlag.SET

                val nameHash: ByteArray
                val randomHash: ByteArray
                val ratchet: ByteArray
                val signature: ByteArray
                val appData: ByteArray?

                if (hasRatchet) {
                    // With ratchet: public_key (64) + name_hash (10) + random_hash (10) + ratchet (32) + signature (64) + app_data
                    if (data.size < keySize + nameHashLen + 10 + ratchetSize + sigLen) {
                        return null // Too small for ratchet announce
                    }

                    nameHash = data.copyOfRange(keySize, keySize + nameHashLen)
                    randomHash = data.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + 10)
                    ratchet = data.copyOfRange(keySize + nameHashLen + 10, keySize + nameHashLen + 10 + ratchetSize)
                    signature = data.copyOfRange(
                        keySize + nameHashLen + 10 + ratchetSize,
                        keySize + nameHashLen + 10 + ratchetSize + sigLen
                    )

                    appData = if (data.size > keySize + nameHashLen + 10 + ratchetSize + sigLen) {
                        data.copyOfRange(keySize + nameHashLen + 10 + ratchetSize + sigLen, data.size)
                    } else {
                        null
                    }
                } else {
                    // Without ratchet: public_key (64) + name_hash (10) + random_hash (10) + signature (64) + app_data
                    nameHash = data.copyOfRange(keySize, keySize + nameHashLen)
                    randomHash = data.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + 10)
                    ratchet = byteArrayOf()
                    signature = data.copyOfRange(keySize + nameHashLen + 10, keySize + nameHashLen + 10 + sigLen)

                    appData = if (data.size > keySize + nameHashLen + 10 + sigLen) {
                        data.copyOfRange(keySize + nameHashLen + 10 + sigLen, data.size)
                    } else {
                        null
                    }
                }

                // Build signed data: destination_hash + public_key + name_hash + random_hash + ratchet + app_data
                val signedData = destinationHash + publicKey + nameHash + randomHash + ratchet + (appData ?: byteArrayOf())

                // Create identity from public key and validate signature
                val announcedIdentity = fromPublicKey(publicKey)

                if (!announcedIdentity.validate(signature, signedData)) {
                    return null // Invalid signature
                }

                if (onlyValidateSignature) {
                    return announcedIdentity
                }

                // Verify destination hash matches the announced identity
                val hashMaterial = nameHash + announcedIdentity.hash
                val expectedHash = Hashes.truncatedHash(hashMaterial)

                if (!destinationHash.contentEquals(expectedHash)) {
                    return null // Destination hash mismatch
                }

                // Check if we already know this destination and verify the public key hasn't changed
                val existingData = knownDestinations[destinationHash.toKey()]
                if (existingData != null) {
                    if (!publicKey.contentEquals(existingData.publicKey)) {
                        // Hash collision or attack - reject
                        return null
                    }
                }

                // Store the announced identity
                remember(
                    packetHash = packet.packetHash,
                    destHash = destinationHash,
                    publicKey = publicKey,
                    appData = appData
                )

                // Store ratchet if present
                if (hasRatchet && ratchet.isNotEmpty()) {
                    network.reticulum.destination.Destination.setRatchetForDestination(destinationHash, ratchet)
                    rememberRatchet(destinationHash, ratchet)
                }

                announcedIdentity
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Save known destinations to disk.
         * Serializes the known destinations map to msgpack format.
         * Thread-safe with a lock to prevent concurrent saves.
         */
        fun saveKnownDestinations() {
            try {
                // Wait for any ongoing save to complete
                val waitInterval = 200L // milliseconds
                val waitTimeout = 5000L // 5 seconds
                val waitStart = System.currentTimeMillis()

                while (savingKnownDestinations) {
                    Thread.sleep(waitInterval)
                    if (System.currentTimeMillis() > waitStart + waitTimeout) {
                        println("Could not save known destinations to storage, waiting for previous save operation timed out.")
                        return
                    }
                }

                savingKnownDestinations = true
                val saveStart = System.currentTimeMillis()

                // Prepare storage directory
                val storageDir = java.io.File(storagePath)
                if (!storageDir.exists()) {
                    storageDir.mkdirs()
                }

                val destFile = java.io.File(storagePath, "known_destinations")

                // Load existing data from disk to merge
                val storageKnownDestinations = mutableMapOf<ByteArray, List<Any?>>()
                if (destFile.exists()) {
                    try {
                        val packer = org.msgpack.core.MessagePack.newDefaultUnpacker(destFile.readBytes())
                        val mapSize = packer.unpackMapHeader()
                        repeat(mapSize) {
                            val keyLen = packer.unpackBinaryHeader()
                            val key = ByteArray(keyLen)
                            packer.readPayload(key)

                            packer.unpackArrayHeader() // Skip array size, we know the format
                            val timestamp = packer.unpackLong()

                            val packetHashLen = packer.unpackBinaryHeader()
                            val packetHash = ByteArray(packetHashLen)
                            packer.readPayload(packetHash)

                            val publicKeyLen = packer.unpackBinaryHeader()
                            val publicKey = ByteArray(publicKeyLen)
                            packer.readPayload(publicKey)

                            val appData = if (packer.tryUnpackNil()) {
                                null
                            } else {
                                val appDataLen = packer.unpackBinaryHeader()
                                val data = ByteArray(appDataLen)
                                packer.readPayload(data)
                                data
                            }

                            storageKnownDestinations[key] = listOf(timestamp, packetHash, publicKey, appData)
                        }
                        packer.close()
                    } catch (e: Exception) {
                        // Ignore errors loading existing data
                    }
                }

                // Merge storage data with in-memory data (prefer in-memory)
                for ((destHash, value) in storageKnownDestinations) {
                    val key = destHash.toKey()
                    if (!knownDestinations.containsKey(key)) {
                        // Convert list format to IdentityData
                        val timestamp = value[0] as Long
                        val packetHash = value[1] as ByteArray
                        val publicKey = value[2] as ByteArray
                        val appData = value[3] as ByteArray?

                        knownDestinations[key] = IdentityData(timestamp, packetHash, publicKey, appData)
                    }
                }

                println("Saving ${knownDestinations.size} known destinations to storage...")

                // Serialize to msgpack
                val buffer = java.io.ByteArrayOutputStream()
                val packer = org.msgpack.core.MessagePack.newDefaultPacker(buffer)

                packer.packMapHeader(knownDestinations.size)
                for ((key, data) in knownDestinations) {
                    // Pack destination hash (key)
                    packer.packBinaryHeader(key.bytes.size)
                    packer.writePayload(key.bytes)

                    // Pack value as array: [timestamp, packet_hash, public_key, app_data]
                    packer.packArrayHeader(4)
                    packer.packLong(data.timestamp)
                    packer.packBinaryHeader(data.packetHash.size)
                    packer.writePayload(data.packetHash)
                    packer.packBinaryHeader(data.publicKey.size)
                    packer.writePayload(data.publicKey)

                    if (data.appData == null) {
                        packer.packNil()
                    } else {
                        packer.packBinaryHeader(data.appData.size)
                        packer.writePayload(data.appData)
                    }
                }
                packer.close()

                // Write to file atomically
                val tempFile = java.io.File(storagePath, "known_destinations.tmp")
                tempFile.writeBytes(buffer.toByteArray())
                tempFile.renameTo(destFile)

                val saveTime = System.currentTimeMillis() - saveStart
                val timeStr = if (saveTime < 1000) {
                    String.format("%.2fms", saveTime.toDouble())
                } else {
                    String.format("%.2fs", saveTime / 1000.0)
                }
                println("Saved known destinations to storage in $timeStr")

            } catch (e: Exception) {
                println("Error while saving known destinations to disk: ${e.message}")
                e.printStackTrace()
            } finally {
                savingKnownDestinations = false
            }
        }

        /**
         * Load known destinations from disk.
         * Deserializes the msgpack file to populate the known destinations map.
         * Called during Reticulum startup.
         */
        fun loadKnownDestinations() {
            val destFile = java.io.File(storagePath, "known_destinations")

            if (!destFile.exists()) {
                println("Destinations file does not exist, no known destinations loaded")
                return
            }

            try {
                val packer = org.msgpack.core.MessagePack.newDefaultUnpacker(destFile.readBytes())
                val mapSize = packer.unpackMapHeader()

                var loadedCount = 0
                repeat(mapSize) {
                    try {
                        val keyLen = packer.unpackBinaryHeader()
                        val destHash = ByteArray(keyLen)
                        packer.readPayload(destHash)

                        // Only load if it's the correct hash length (16 bytes)
                        if (destHash.size != RnsConstants.TRUNCATED_HASH_BYTES) {
                            // Skip this entry - unpack but don't store
                            packer.unpackArrayHeader()
                            packer.unpackLong()
                            val phLen = packer.unpackBinaryHeader()
                            packer.readPayload(ByteArray(phLen))
                            val pkLen = packer.unpackBinaryHeader()
                            packer.readPayload(ByteArray(pkLen))
                            if (!packer.tryUnpackNil()) {
                                val adLen = packer.unpackBinaryHeader()
                                packer.readPayload(ByteArray(adLen))
                            }
                            return@repeat
                        }

                        packer.unpackArrayHeader() // Skip array size, we know the format
                        val timestamp = packer.unpackLong()

                        val packetHashLen = packer.unpackBinaryHeader()
                        val packetHash = ByteArray(packetHashLen)
                        packer.readPayload(packetHash)

                        val publicKeyLen = packer.unpackBinaryHeader()
                        val publicKey = ByteArray(publicKeyLen)
                        packer.readPayload(publicKey)

                        val appData = if (packer.tryUnpackNil()) {
                            null
                        } else {
                            val appDataLen = packer.unpackBinaryHeader()
                            val data = ByteArray(appDataLen)
                            packer.readPayload(data)
                            data
                        }

                        val data = IdentityData(timestamp, packetHash, publicKey, appData)
                        knownDestinations[destHash.toKey()] = data

                        // Index by identity hash
                        val identityHash = Hashes.truncatedHash(publicKey)
                        identityHashIndex[identityHash.toKey()] = destHash

                        loadedCount++
                    } catch (e: Exception) {
                        // Skip corrupted entry
                        println("Warning: Skipped corrupted entry in known_destinations: ${e.message}")
                    }
                }
                packer.close()

                println("Loaded $loadedCount known destinations from storage")

            } catch (e: Exception) {
                println("Error loading known destinations from disk, file will be recreated on exit: ${e.message}")
                e.printStackTrace()
            }
        }

        /**
         * Remember a ratchet for a destination.
         * Stores the ratchet with the current timestamp and adds it to the front of the list (newest first).
         * Automatically cleans expired ratchets for this destination.
         *
         * @param destHash The destination hash
         * @param ratchet The ratchet public key bytes (32 bytes)
         */
        fun rememberRatchet(destHash: ByteArray, ratchet: ByteArray) {
            require(ratchet.size == RnsConstants.KEY_SIZE) {
                "Ratchet must be ${RnsConstants.KEY_SIZE} bytes, got ${ratchet.size}"
            }

            val key = destHash.toKey()
            val now = System.currentTimeMillis()
            val entry = RatchetEntry(ratchet.copyOf(), now)
            var shouldPersist = false

            synchronized(destinationRatchets) {
                val entries = destinationRatchets.getOrPut(key) { mutableListOf() }

                // Check if this ratchet already exists
                if (entries.any { it.ratchet.contentEquals(ratchet) }) {
                    return // Already stored
                }

                // Prepend (newest first)
                entries.add(0, entry)
                shouldPersist = true

                // Clean expired entries for this destination
                entries.removeIf { now - it.timestamp > RATCHET_EXPIRY }
            }

            // Persist to disk
            if (shouldPersist) {
                persistRatchet(destHash, ratchet, now)
            }
        }

        /**
         * Persist a ratchet to disk.
         * Uses atomic write (write to temp file, then rename) for safety.
         */
        private fun persistRatchet(destHash: ByteArray, ratchet: ByteArray, timestamp: Long) {
            try {
                ratchetPersistLock.lock()
                try {
                    // Create ratchet directory if needed
                    val ratchetDir = File(ratchetPath)
                    if (!ratchetDir.exists()) {
                        ratchetDir.mkdirs()
                    }

                    // Serialize ratchet data with msgpack: {"ratchet": bytes, "received": timestamp}
                    val buffer = ByteArrayOutputStream()
                    val packer = MessagePack.newDefaultPacker(buffer)
                    packer.packMapHeader(2)
                    packer.packString("ratchet")
                    packer.packBinaryHeader(ratchet.size)
                    packer.writePayload(ratchet)
                    packer.packString("received")
                    // Store as seconds (like Python) for compatibility
                    packer.packDouble(timestamp / 1000.0)
                    packer.close()

                    // Write atomically
                    val hexHash = destHash.toHexString()
                    val outFile = File(ratchetDir, "$hexHash.out")
                    val finalFile = File(ratchetDir, hexHash)
                    outFile.writeBytes(buffer.toByteArray())
                    outFile.renameTo(finalFile)
                } finally {
                    ratchetPersistLock.unlock()
                }
            } catch (e: Exception) {
                println("Could not persist ratchet for ${destHash.toHexString()}: ${e.message}")
            }
        }

        /**
         * Get the most recent ratchet for a destination.
         * Checks in-memory cache first, then tries to load from disk.
         *
         * @param destHash The destination hash
         * @return The most recent ratchet, or null if none stored
         */
        fun getRatchet(destHash: ByteArray): ByteArray? {
            val key = destHash.toKey()
            val now = System.currentTimeMillis()

            // Check in-memory cache first
            synchronized(destinationRatchets) {
                val entries = destinationRatchets[key]
                if (entries != null) {
                    // Clean expired entries
                    entries.removeIf { now - it.timestamp > RATCHET_EXPIRY }
                    val ratchet = entries.firstOrNull()?.ratchet?.copyOf()
                    if (ratchet != null) {
                        return ratchet
                    }
                }
            }

            // Try to load from disk
            return loadRatchetFromDisk(destHash)
        }

        /**
         * Load a ratchet from disk if it exists and is not expired.
         */
        private fun loadRatchetFromDisk(destHash: ByteArray): ByteArray? {
            try {
                val hexHash = destHash.toHexString()
                val ratchetFile = File(ratchetPath, hexHash)

                if (!ratchetFile.exists()) {
                    return null
                }

                val unpacker = MessagePack.newDefaultUnpacker(ratchetFile.readBytes())
                val mapSize = unpacker.unpackMapHeader()

                var ratchet: ByteArray? = null
                var received: Double? = null

                repeat(mapSize) {
                    val keyName = unpacker.unpackString()
                    when (keyName) {
                        "ratchet" -> {
                            val len = unpacker.unpackBinaryHeader()
                            ratchet = ByteArray(len)
                            unpacker.readPayload(ratchet!!)
                        }
                        "received" -> {
                            received = unpacker.unpackDouble()
                        }
                        else -> {
                            // Skip unknown key
                            unpacker.skipValue()
                        }
                    }
                }
                unpacker.close()

                // Validate ratchet
                if (ratchet == null || ratchet!!.size != RnsConstants.KEY_SIZE) {
                    println("Invalid ratchet data for ${destHash.toHexString()}")
                    return null
                }

                // Check if expired
                val now = System.currentTimeMillis()
                val receivedMs = ((received ?: 0.0) * 1000).toLong()
                if (now - receivedMs > RATCHET_EXPIRY) {
                    // Expired, delete file
                    ratchetFile.delete()
                    return null
                }

                // Add to in-memory cache
                val key = destHash.toKey()
                synchronized(destinationRatchets) {
                    val entries = destinationRatchets.getOrPut(key) { mutableListOf() }
                    if (!entries.any { it.ratchet.contentEquals(ratchet!!) }) {
                        entries.add(0, RatchetEntry(ratchet!!.copyOf(), receivedMs))
                    }
                }

                return ratchet!!.copyOf()
            } catch (e: Exception) {
                println("Error loading ratchet for ${destHash.toHexString()}: ${e.message}")
                return null
            }
        }

        /**
         * Get all non-expired ratchets for a destination (newest first).
         * Used for trying multiple ratchets during decryption.
         *
         * @param destHash The destination hash
         * @return List of ratchets, newest first
         */
        fun getRatchets(destHash: ByteArray): List<ByteArray> {
            val key = destHash.toKey()
            val now = System.currentTimeMillis()

            synchronized(destinationRatchets) {
                val entries = destinationRatchets[key] ?: return emptyList()

                // Clean expired entries
                entries.removeIf { now - it.timestamp > RATCHET_EXPIRY }

                // Return all ratchets (already newest first)
                return entries.map { it.ratchet.copyOf() }
            }
        }

        /**
         * Get the ratchet ID for the current ratchet of a destination.
         * The ratchet ID is the first 10 bytes of the SHA-256 hash of the ratchet public key.
         *
         * @param destHash The destination hash
         * @return The ratchet ID (10 bytes), or null if no ratchet stored
         */
        fun currentRatchetId(destHash: ByteArray): ByteArray? {
            val ratchet = getRatchet(destHash) ?: return null
            return Hashes.fullHash(ratchet).copyOfRange(0, RnsConstants.NAME_HASH_BYTES)
        }

        /**
         * Clean all expired ratchets from memory and disk.
         * Should be called periodically to free memory and disk space.
         */
        fun cleanRatchets() {
            println("Cleaning ratchets...")
            val now = System.currentTimeMillis()

            // Clean in-memory cache
            synchronized(destinationRatchets) {
                val iterator = destinationRatchets.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val ratchets = entry.value

                    // Remove expired ratchets
                    ratchets.removeIf { now - it.timestamp > RATCHET_EXPIRY }

                    // Remove destination entry if no ratchets left
                    if (ratchets.isEmpty()) {
                        iterator.remove()
                    }
                }
            }

            // Clean disk
            cleanRatchetsFromDisk()
        }

        /**
         * Clean expired ratchet files from disk.
         */
        private fun cleanRatchetsFromDisk() {
            try {
                val ratchetDir = File(ratchetPath)
                if (!ratchetDir.isDirectory) {
                    return
                }

                val now = System.currentTimeMillis()
                val files = ratchetDir.listFiles() ?: return

                for (file in files) {
                    // Skip temp files (will be cleaned up or completed)
                    if (file.name.endsWith(".out")) {
                        continue
                    }

                    try {
                        val unpacker = MessagePack.newDefaultUnpacker(file.readBytes())
                        val mapSize = unpacker.unpackMapHeader()

                        var received: Double? = null
                        var ratchetSize = 0

                        repeat(mapSize) {
                            val keyName = unpacker.unpackString()
                            when (keyName) {
                                "ratchet" -> {
                                    ratchetSize = unpacker.unpackBinaryHeader()
                                    // Skip the binary content
                                    unpacker.readPayload(ByteArray(ratchetSize))
                                }
                                "received" -> {
                                    received = unpacker.unpackDouble()
                                }
                                else -> {
                                    unpacker.skipValue()
                                }
                            }
                        }
                        unpacker.close()

                        // Check if expired or corrupted
                        val receivedMs = ((received ?: 0.0) * 1000).toLong()
                        val isExpired = now - receivedMs > RATCHET_EXPIRY
                        val isCorrupted = ratchetSize != RnsConstants.KEY_SIZE

                        if (isExpired || isCorrupted) {
                            if (isCorrupted) {
                                println("Removing corrupted ratchet file: ${file.name}")
                            }
                            file.delete()
                        }
                    } catch (e: Exception) {
                        println("Error reading ratchet file ${file.name}, removing: ${e.message}")
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                println("Error cleaning ratchets from disk: ${e.message}")
            }
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
     * Generate and send a proof for a packet.
     *
     * @param packet The packet to prove
     * @param destination Optional destination to send the proof to (if null, uses packet's truncated hash)
     */
    fun prove(packet: network.reticulum.packet.Packet, destination: network.reticulum.destination.Destination? = null) {
        require(hasPrivateKey) { "Cannot prove packet without private key" }

        // Sign the packet hash
        val signature = sign(packet.packetHash)

        // For now, always use explicit proofs (implicit proofs require Reticulum.shouldUseImplicitProof())
        val proofData = packet.packetHash + signature

        // Determine destination hash for proof
        val destinationHash = destination?.hash ?: packet.truncatedHash

        // Create and send proof packet
        val proof = network.reticulum.packet.Packet.createRaw(
            destinationHash = destinationHash,
            data = proofData,
            packetType = network.reticulum.common.PacketType.PROOF
        )

        proof.send()
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

    /**
     * Save the identity's private key to a file.
     *
     * WARNING: This writes the private key to disk. Anyone with access to this file
     * will be able to decrypt all communication for this identity. Use with extreme caution.
     *
     * @param path The path where the identity should be saved
     * @return true if the file was saved successfully, false otherwise
     */
    fun toFile(path: String): Boolean {
        return try {
            check(hasPrivateKey) { "Cannot save identity without private key" }

            val file = java.io.File(path)
            // Ensure parent directories exist
            file.parentFile?.mkdirs()

            // Write the private key bytes
            file.writeBytes(getPrivateKey())
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun toString(): String = hexHash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
