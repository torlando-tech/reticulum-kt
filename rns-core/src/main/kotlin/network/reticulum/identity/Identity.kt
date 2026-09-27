package network.reticulum.identity

import java.util.Locale
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.RnsConstants
import network.reticulum.common.hexToByteArray
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
     * The hex-encoded hash. Lazy: most identities (every recall(), the
     * throw-away one in validateAnnounce) never have it read.
     */
    val hexHash: String by lazy { hash.toHexString() }

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
        val appData: ByteArray?,
        /**
         * When this destination's identity was last USED, which is what decides how long
         * it is worth keeping (python `known_destinations[...][4]`, `Identity.py:107`).
         *
         * Three meanings in one field, matching the reference:
         *   0   never used — forgotten once its announce ages past UNUSED_DESTINATION_LINGER
         *  >0   last-use time — kept until DESTINATION_TIMEOUT * 1.25 past that
         *  -1   retained — never evicted, whatever its age
         *
         * Without it every entry looks never-used, so an identity a running application is
         * actively talking to is evicted on the same schedule as one that merely announced
         * once and was never touched.
         */
        val lastUsed: Long = 0L,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityData) return false
            return timestamp == other.timestamp &&
                packetHash.contentEquals(other.packetHash) &&
                publicKey.contentEquals(other.publicKey) &&
                appData.contentEquals(other.appData) &&
                lastUsed == other.lastUsed
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + packetHash.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            result = 31 * result + appData.contentHashCode()
            result = 31 * result + lastUsed.hashCode()
            return result
        }

        /** Marked never to be evicted (python `last_use == -1`). */
        val retained: Boolean get() = lastUsed < 0
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
         * Storage for ratchets: destination_hash -> the current (ratchet, timestamp).
         *
         * Invariant: each list holds at most ONE entry. Python keeps exactly one
         * ratchet per destination (`known_ratchets[destination_hash] = ratchet`,
         * Identity.py:419) and a new ratchet replaces the old one; accumulating
         * them here let any announcer grow this map by one entry per announce for
         * RATCHET_EXPIRY. The list is kept only as the per-destination lock
         * object (see [withRatchetEntries]).
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

        /** Pluggable persistent storage for known destinations and ratchets.
         *  When null, falls back to file-based persistence. */
        var identityStore: network.reticulum.storage.IdentityStore? = null

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
        @JvmOverloads
        fun recall(hash: ByteArray, noUse: Boolean = false): Identity? {
            // Check known destinations cache first
            val data = knownDestinations[hash.toKey()]
            if (data != null) {
                // A successful recall IS the use (python Identity.py:135). Callers that
                // are only inspecting the table — the announce-retransmit job checking
                // whether a path still resolves, say — pass noUse so a housekeeping read
                // does not look like an application reaching for the destination.
                if (!noUse) markDestinationUsed(hash)
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
            // Python RNS rejects malformed keys at remember() time
            // (Identity.py:101-102, TypeError on len != KEYSIZE//8); without
            // this gate a corrupt announce could plant an unusable key.
            if (publicKey.size != RnsConstants.FULL_KEY_SIZE) {
                throw IllegalArgumentException(
                    "Can't remember destination, public key size of ${publicKey.size} is not valid"
                )
            }
            remember(packetHash, destHash, publicKey, appData, destHash.toKey(), Hashes.truncatedHash(publicKey))
        }

        /**
         * [remember] for a caller that already holds the destination key and the
         * identity hash of [publicKey] (validateAnnounce has both), so neither is
         * recomputed. [publicKey] must be [RnsConstants.FULL_KEY_SIZE] bytes and
         * [identityHash] must equal `Hashes.truncatedHash(publicKey)`.
         */
        internal fun remember(
            packetHash: ByteArray,
            destHash: ByteArray,
            publicKey: ByteArray,
            appData: ByteArray?,
            destKey: ByteArrayKey,
            identityHash: ByteArray
        ) {
            val data = IdentityData(
                timestamp = System.currentTimeMillis(),
                packetHash = packetHash.copyOf(),
                publicKey = publicKey.copyOf(),
                appData = appData?.copyOf()
            )
            knownDestinations[destKey] = data
            identityStore?.upsertKnownDestination(destHash, data)

            // Index by identity hash for reverse lookups
            identityHashIndex[identityHash.toKey()] = destHash.copyOf()
        }

        /**
         * Get a random truncated hash, exactly as Python RNS's
         * Identity.get_random_hash(): the truncated SHA-256 of
         * TRUNCATED_HASH_BYTES of random data (Identity.py:386-393).
         */
        fun getRandomHash(crypto: CryptoProvider = defaultCryptoProvider()): ByteArray {
            return Hashes.truncatedHash(crypto.randomBytes(RnsConstants.TRUNCATED_HASH_BYTES))
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
         * Evict stale known destinations to bound the in-memory identity caches
         * (parity with python Identity.clean_known_destinations, Identity.py:284-349).
         *
         * A remote can mint fresh identities cheaply and their announces propagate
         * multi-hop, so without eviction [knownDestinations], [identityHashIndex]
         * and [destinationRatchets] grow monotonically per validated announce —
         * unbounded memory growth on a long-running node on a busy network.
         *
         * All three of python's arms are ported (Identity.py:322-339): an entry with an
         * active path is kept; a retained entry (`lastUsed == -1`, set by
         * [retainDestinationData]) is kept whatever its age; an entry that was ever
         * used (`lastUsed > 0`) is evicted `DESTINATION_TIMEOUT * 1.25` after its LAST
         * USE; and an entry that only ever announced is evicted
         * [network.reticulum.transport.TransportConstants.UNUSED_DESTINATION_LINGER]
         * after its announce. An application that must keep quiet correspondents
         * (calling [remember] + [retainDestinationData] for each saved contact at
         * startup) relies on the retained sentinel and on this method honouring it.
         *
         * @param hasPath predicate: does Transport currently have a path to this
         *   destination hash? Entries with a path are always retained.
         * @param now current time in millis (injectable for tests).
         * @return the number of destinations evicted.
         */
        fun cleanKnownDestinations(
            hasPath: (ByteArray) -> Boolean,
            now: Long = System.currentTimeMillis(),
        ): Int {
            val linger = network.reticulum.transport.TransportConstants.UNUSED_DESTINATION_LINGER
            val usedTimeout =
                (network.reticulum.transport.TransportConstants.DESTINATION_TIMEOUT * 1.25).toLong()
            var removed = 0
            for ((key, data) in knownDestinations) {
                val destHash = key.bytes
                if (data.retained) continue
                if (hasPath(destHash)) continue
                // Two different clocks, and which one applies depends on whether anything
                // ever used this identity (python Identity.py:329-331). An identity that
                // only ever announced is forgotten once the announce goes stale; one that
                // was used is kept from its LAST USE, so an idle but live correspondent is
                // not dropped simply because it has not re-announced recently.
                val stale = if (data.lastUsed > 0) {
                    now - data.lastUsed > usedTimeout
                } else {
                    now - data.timestamp > linger
                }
                if (!stale) continue
                knownDestinations.remove(key)
                identityHashIndex.remove(Hashes.truncatedHash(data.publicKey).toKey())
                destinationRatchets.remove(key)
                identityStore?.removeKnownDestination(destHash)
                removed++
            }
            return removed
        }

        /**
         * Mark a destination's identity as used now (python
         * `Reticulum._used_destination_data`, `Identity.py:241-248`).
         *
         * A retained entry is left alone — retention is a standing instruction to keep the
         * entry, and overwriting its marker with a timestamp would put it back on the
         * eviction clock.
         *
         * @return true if the entry existed and was marked.
         */
        fun markDestinationUsed(destHash: ByteArray): Boolean {
            val key = destHash.toKey()
            val data = knownDestinations[key] ?: return false
            if (data.retained) return false
            val updated = data.copy(lastUsed = System.currentTimeMillis())
            knownDestinations[key] = updated
            // python's marker is memory-only until the next save (Identity.py:242-247); a
            // write-through here cost one database write per transported LRPROOF on a
            // transport node. The marker is flushed by the known-destinations job.
            dirtyUsedMarkers.add(key)
            return true
        }

        private val dirtyUsedMarkers: MutableSet<ByteArrayKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Write the use markers accumulated since the last flush to the identity store, if there is one. */
        fun flushUsedMarkers() {
            val store = identityStore ?: run { dirtyUsedMarkers.clear(); return }
            val keys = dirtyUsedMarkers.toList()
            dirtyUsedMarkers.removeAll(keys.toSet())
            for (key in keys) {
                val data = knownDestinations[key] ?: continue
                try {
                    store.upsertKnownDestination(key.bytes, data)
                } catch (e: Exception) {
                    dirtyUsedMarkers.add(key)
                }
            }
        }
        /**
         * Pin a destination's identity so it is never evicted (python
         * `_retain_destination_data`, `Identity.py:250-256`).
         */
        fun retainDestinationData(destHash: ByteArray): Boolean {
            val key = destHash.toKey()
            val data = knownDestinations[key] ?: return false
            val updated = data.copy(lastUsed = -1L)
            knownDestinations[key] = updated
            identityStore?.upsertKnownDestination(destHash, updated)
            return true
        }

        /**
         * Release a retained destination back onto the normal eviction clock, as though it
         * had just been used (python `_unretain_destination_data`, `Identity.py:258-264`).
         */
        fun unretainDestinationData(destHash: ByteArray): Boolean {
            val key = destHash.toKey()
            val data = knownDestinations[key] ?: return false
            val updated = data.copy(lastUsed = System.currentTimeMillis())
            knownDestinations[key] = updated
            identityStore?.upsertKnownDestination(destHash, updated)
            return true
        }

        /**
         * Mark a known destination as used now, unless it is retained (python
         * `Identity._used_destination_data`, Identity.py:242-249). False when unknown or retained.
         */
        fun usedDestinationData(destHash: ByteArray): Boolean {
            val key = destHash.toKey()
            val data = knownDestinations[key] ?: return false
            if (data.lastUsed < 0) return false
            val updated = data.copy(lastUsed = System.currentTimeMillis())
            knownDestinations[key] = updated
            // Memory-only until the next flush, as python (Identity.py:242-247).
            dirtyUsedMarkers.add(key)
            return true
        }

        /**
         * Retain every known destination belonging to an identity (python
         * `Identity._retain_identity`, Identity.py:270-283). True when at least one was retained.
         */
        fun retainIdentity(identityHash: ByteArray): Boolean {
            var retained = false
            for ((key, data) in knownDestinations.entries.toList()) {
                if (Hashes.truncatedHash(data.publicKey).contentEquals(identityHash)) {
                    if (retainDestinationData(key.bytes)) retained = true
                }
            }
            return retained
        }
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
                var appData: ByteArray?

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

                    // python Identity.py:514-516 — app_data defaults to b"" (empty),
                    // not None, when the ratcheted announce carries no trailing bytes.
                    appData = if (data.size > keySize + nameHashLen + 10 + ratchetSize + sigLen) {
                        data.copyOfRange(keySize + nameHashLen + 10 + ratchetSize + sigLen, data.size)
                    } else {
                        byteArrayOf()
                    }
                } else {
                    // Without ratchet: public_key (64) + name_hash (10) + random_hash (10) + signature (64) + app_data
                    nameHash = data.copyOfRange(keySize, keySize + nameHashLen)
                    randomHash = data.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + 10)
                    ratchet = byteArrayOf()
                    signature = data.copyOfRange(keySize + nameHashLen + 10, keySize + nameHashLen + 10 + sigLen)

                    // python Identity.py:525-527 — app_data defaults to b"" (empty),
                    // not None, when the ratchetless announce carries no trailing bytes.
                    appData = if (data.size > keySize + nameHashLen + 10 + sigLen) {
                        data.copyOfRange(keySize + nameHashLen + 10 + sigLen, data.size)
                    } else {
                        byteArrayOf()
                    }
                }

                // Build signed data: destination_hash + public_key + name_hash + random_hash + ratchet + app_data
                // (app_data is b"" here in every no-trailing-bytes case, matching python's
                // pre-override value at Identity.py:529.)
                val signedData = destinationHash + publicKey + nameHash + randomHash + ratchet + appData

                // python Identity.py:531-532 — ONLY the ratchetless no-app_data layout
                // (data length == keysize+name_hash+10+sig_len, the threshold WITHOUT the
                // ratchet term) nulls app_data after signing. A ratcheted no-app_data
                // announce exceeds this threshold by the 32-byte ratchet, so it keeps the
                // b"" sentinel; this is what `recall_app_data` must return as empty, not None.
                if (!(data.size > keySize + nameHashLen + 10 + sigLen)) {
                    appData = null
                }

                // Create identity from public key
                val announcedIdentity = fromPublicKey(publicKey)

                // Blackhole gate (python Identity.py:566-569): an announce from a
                // blackholed identity is invalidated and dropped BEFORE signature
                // validation, so it learns no path.
                if (network.reticulum.transport.Transport.blackholedIdentities.isNotEmpty() &&
                    network.reticulum.transport.Transport.isBlackholed(announcedIdentity.hash)
                ) {
                    return null
                }

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
                val destinationKey = destinationHash.toKey()
                val existingData = knownDestinations[destinationKey]
                if (existingData != null) {
                    if (!publicKey.contentEquals(existingData.publicKey)) {
                        // Hash collision or attack - reject
                        return null
                    }
                }

                // Store the announced identity. announcedIdentity.hash IS
                // truncatedHash(publicKey); the key was built for the lookup above.
                remember(
                    packetHash = packet.packetHash,
                    destHash = destinationHash,
                    publicKey = publicKey,
                    appData = appData,
                    destKey = destinationKey,
                    identityHash = announcedIdentity.hash
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
         * Unpack one `known_destinations` map entry positioned at its key:
         * `bin(dest_hash) -> [int timestamp, bin packet_hash, bin public_key, bin|nil app_data]`
         * (python Identity.save_known_destinations, Identity.py:446-508). Consumes
         * exactly the entry's bytes; any msgpack error propagates to the caller.
         */
        private fun unpackKnownDestinationEntry(unpacker: MessageUnpacker): Pair<ByteArrayKey, IdentityData> {
            val destHash = ByteArray(unpacker.unpackBinaryHeader())
            unpacker.readPayload(destHash)

            val fieldCount = unpacker.unpackArrayHeader()
            val timestamp = unpacker.unpackLong()

            val packetHash = ByteArray(unpacker.unpackBinaryHeader())
            unpacker.readPayload(packetHash)

            val publicKey = ByteArray(unpacker.unpackBinaryHeader())
            unpacker.readPayload(publicKey)

            val appData = if (unpacker.tryUnpackNil()) {
                null
            } else {
                val data = ByteArray(unpacker.unpackBinaryHeader())
                unpacker.readPayload(data)
                data
            }

            // A file written before the last-used marker existed has four fields; read
            // it as never-used rather than rejecting it, exactly as the reference upgrades
            // short entries in place (Identity.py:226-228).
            val lastUsed = if (fieldCount >= 5) unpacker.unpackLong() else 0L

            return destHash.toKey() to IdentityData(timestamp, packetHash, publicKey, appData, lastUsed)
        }

        /**
         * Save known destinations to disk.
         * Serializes the known destinations map to msgpack format.
         * Thread-safe with a lock to prevent concurrent saves.
         */
        fun saveKnownDestinations() {
            // When store is active, write-through handles persistence
            if (identityStore != null) return

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
                val storageKnownDestinations = mutableMapOf<ByteArrayKey, IdentityData>()
                if (destFile.exists()) {
                    try {
                        val unpacker = MessagePack.newDefaultUnpacker(destFile.readBytes())
                        val mapSize = unpacker.unpackMapHeader()
                        repeat(mapSize) {
                            val (key, data) = unpackKnownDestinationEntry(unpacker)
                            storageKnownDestinations[key] = data
                        }
                        unpacker.close()
                    } catch (e: Exception) {
                        // Ignore errors loading existing data
                    }
                }

                // Merge storage data with in-memory data (prefer in-memory)
                for ((key, data) in storageKnownDestinations) {
                    if (!knownDestinations.containsKey(key)) {
                        knownDestinations[key] = data
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

                    // [timestamp, packet_hash, public_key, app_data, last_used]
                    // (python Identity.py:107). The last-used marker is part of the
                    // on-disk format, so a peer or a later version reading this file
                    // must find all five fields.
                    packer.packArrayHeader(5)
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
                    packer.packLong(data.lastUsed)
                }
                packer.close()

                // Write to file atomically (python os.replace, Identity.py:199)
                val tempFile = java.io.File(storagePath, "known_destinations.tmp")
                tempFile.writeBytes(buffer.toByteArray())
                replaceFile(tempFile, destFile)

                val saveTime = System.currentTimeMillis() - saveStart
                val timeStr = if (saveTime < 1000) {
                    String.format(Locale.ROOT, "%.2fms", saveTime.toDouble())
                } else {
                    String.format(Locale.ROOT, "%.2fs", saveTime / 1000.0)
                }
                println("Saved known destinations to storage in $timeStr")

            } catch (e: Exception) {
                println("Error while saving known destinations to disk: ${e.javaClass.simpleName}: ${e.message}")
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
            // When store is active, load from database
            identityStore?.let { store ->
                val loaded = store.loadAllKnownDestinations()
                knownDestinations.putAll(loaded)
                // Rebuild identity hash index
                for ((destKey, data) in loaded) {
                    val identityHash = Hashes.truncatedHash(data.publicKey)
                    identityHashIndex[identityHash.toKey()] = destKey.bytes.copyOf()
                }
                println("Loaded ${loaded.size} known destinations from store")
                return
            }

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
                        // The entry is always consumed in full so the stream stays in
                        // sync; it is only STORED if the key is a 16-byte hash.
                        val (key, data) = unpackKnownDestinationEntry(packer)
                        if (key.bytes.size != RnsConstants.TRUNCATED_HASH_BYTES) {
                            return@repeat
                        }

                        knownDestinations[key] = data

                        // Index by identity hash
                        val identityHash = Hashes.truncatedHash(data.publicKey)
                        identityHashIndex[identityHash.toKey()] = key.bytes

                        loadedCount++
                    } catch (e: Exception) {
                        // Skip corrupted entry
                        println("Warning: Skipped corrupted entry in known_destinations: ${e.message}")
                    }
                }
                packer.close()

                println("Loaded $loadedCount known destinations from storage")

            } catch (e: Exception) {
                println("Error loading known destinations from disk, file will be recreated on exit: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /**
         * Run [block] on the ratchet list for [key], holding that list's monitor.
         *
         * Ratchet ops used to take one global `synchronized(destinationRatchets)`
         * over the ConcurrentHashMap; contention is per destination, so lock the
         * per-destination list instead. The map itself is thread-safe. The only
         * hazard is [cleanRatchets] detaching an emptied list between our
         * getOrPut and our lock, which would strand a subsequent add on an
         * orphaned list — so after locking, confirm the list is still the one
         * in the map and retry otherwise (cleanRatchets detaches only while
         * holding the list's monitor, so the check is race-free).
         */
        private inline fun <T> withRatchetEntries(key: ByteArrayKey, block: (MutableList<RatchetEntry>) -> T): T {
            while (true) {
                val entries = destinationRatchets.getOrPut(key) { mutableListOf() }
                synchronized(entries) {
                    if (destinationRatchets[key] === entries) {
                        return block(entries)
                    }
                }
            }
        }

        /**
         * Remember a ratchet for a destination.
         * Stores the ratchet with the current timestamp, replacing any previous
         * ratchet for that destination — exactly one ratchet is kept per
         * destination, as python `Identity._remember_ratchet` does
         * (`known_ratchets[destination_hash] = ratchet`, Identity.py:419).
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

            withRatchetEntries(key) { entries ->
                // Already the current ratchet: nothing to do (python compares only
                // against the one stored ratchet, Identity.py:412).
                if (entries.any { it.ratchet.contentEquals(ratchet) }) {
                    return
                }

                // Replace rather than accumulate (Identity.py:419).
                entries.clear()
                entries.add(entry)
                shouldPersist = true
            }

            // Persist to storage
            if (shouldPersist) {
                identityStore?.upsertRatchet(destHash, ratchet, now) ?: persistRatchet(destHash, ratchet, now)
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

                    // Write atomically (python os.replace, Identity.py:435)
                    val hexHash = destHash.toHexString()
                    val outFile = File(ratchetDir, "$hexHash.out")
                    val finalFile = File(ratchetDir, hexHash)
                    outFile.writeBytes(buffer.toByteArray())
                    replaceFile(outFile, finalFile)
                } finally {
                    ratchetPersistLock.unlock()
                }
            } catch (e: Exception) {
                println("Could not persist ratchet for ${destHash.toHexString()}: ${e.message}")
            }
        }

        /**
         * Atomically replace [dst] with [src] — the java.nio equivalent of python's
         * `os.replace` (Identity.py:199, 435). `File.renameTo` was used before; it
         * returns false (silently, the result was discarded) when the target
         * already exists on Windows, so the known-destinations file and the
         * per-peer ratchet files were never updated after their first write.
         * Falls back to a non-atomic replace where the filesystem cannot do an
         * atomic one. Failures propagate to the caller, which logs them.
         */
        private fun replaceFile(src: File, dst: File) {
            try {
                Files.move(
                    src.toPath(),
                    dst.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                println("Atomic replace of ${dst.name} unsupported here (${e.message}); replacing non-atomically")
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
            val entries = destinationRatchets[key]
            if (entries != null) {
                synchronized(entries) {
                    // Clean expired entries
                    entries.removeIf { now - it.timestamp > RATCHET_EXPIRY }
                    val ratchet = entries.firstOrNull()?.ratchet?.copyOf()
                    if (ratchet != null) {
                        return ratchet
                    }
                }
            }

            // Try to load from store or disk
            identityStore?.let { store ->
                val result = store.getRatchet(destHash) ?: return null
                val (ratchet, timestamp) = result
                if (System.currentTimeMillis() - timestamp > RATCHET_EXPIRY) return null
                // Cache in memory. Only fill an empty slot: an entry that
                // appeared meanwhile came from rememberRatchet and is at least
                // as fresh as the store (write-through follows the in-memory
                // update), and the slot holds at most one entry.
                withRatchetEntries(key) { cached ->
                    if (cached.isEmpty()) {
                        cached.add(RatchetEntry(ratchet.copyOf(), timestamp))
                    }
                }
                return ratchet
            }
            return loadRatchetFromDisk(destHash)
        }

        /**
         * Parsed on-disk ratchet file: `{"ratchet": bin, "received": float seconds}`
         * (python Identity._remember_ratchet persist_job, Identity.py:414-420).
         * Either field is null when absent.
         */
        private data class RatchetFile(val ratchet: ByteArray?, val received: Double?)

        /**
         * Parse a ratchet file's bytes. Unknown keys are skipped; msgpack errors
         * propagate to the caller, which owns the per-file error policy.
         */
        private fun readRatchetFile(bytes: ByteArray): RatchetFile {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val mapSize = unpacker.unpackMapHeader()

            var ratchet: ByteArray? = null
            var received: Double? = null

            repeat(mapSize) {
                when (unpacker.unpackString()) {
                    "ratchet" -> {
                        val data = ByteArray(unpacker.unpackBinaryHeader())
                        unpacker.readPayload(data)
                        ratchet = data
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

            return RatchetFile(ratchet, received)
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

                val (ratchet, received) = readRatchetFile(ratchetFile.readBytes())

                // Validate ratchet
                if (ratchet == null || ratchet.size != RnsConstants.KEY_SIZE) {
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

                // Add to in-memory cache (single slot; see getRatchet)
                withRatchetEntries(destHash.toKey()) { entries ->
                    if (entries.isEmpty()) {
                        entries.add(RatchetEntry(ratchet.copyOf(), receivedMs))
                    }
                }

                return ratchet.copyOf()
            } catch (e: Exception) {
                println("Error loading ratchet for ${destHash.toHexString()}: ${e.message}")
                return null
            }
        }

        /**
         * Get the non-expired ratchet for a destination as a list of at most one
         * element. Only one ratchet is retained per destination (python has no
         * multi-ratchet fallback; Identity.py:419), so this never returns more
         * than [getRatchet] does. Kept for API compatibility.
         *
         * @param destHash The destination hash
         * @return The current in-memory ratchet, or an empty list
         */
        fun getRatchets(destHash: ByteArray): List<ByteArray> {
            val key = destHash.toKey()
            val now = System.currentTimeMillis()

            val entries = destinationRatchets[key] ?: return emptyList()
            synchronized(entries) {
                // Clean expired entries
                entries.removeIf { now - it.timestamp > RATCHET_EXPIRY }

                // Return all ratchets (already newest first)
                return entries.map { it.ratchet.copyOf() }
            }
        }

        /**
         * Conformance test seam: drop the in-memory ratchet cache entry for a
         * destination so the next getRatchet() must load from the on-disk store
         * (the conformance bridge is a separate module and can't touch the
         * private destinationRatchets map). Mirrors the reference bridge's
         * `Identity.known_ratchets.pop(dest_hash, None)` (reticulum-conformance
         * reference/wire_tcp.py cmd_wire_identity_ratchet_persist), which forces
         * the disk-load path. Read-only on disk; no port logic.
         */
        @network.reticulum.RnsTestSeam
        fun dropRatchetCacheForTest(destHash: ByteArray) {
            destinationRatchets.remove(destHash.toKey())
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
            return ratchetIdFor(ratchet)
        }

        /**
         * The id of a ratchet from its public bytes: full_hash[:10], exactly
         * python Identity._get_ratchet_id (Identity.py:410-411).
         */
        fun ratchetIdFor(ratchetPubBytes: ByteArray): ByteArray =
            Hashes.fullHash(ratchetPubBytes).copyOfRange(0, RnsConstants.NAME_HASH_BYTES)

        /**
         * Clean all expired ratchets from memory and disk.
         * Should be called periodically to free memory and disk space.
         */
        fun cleanRatchets() {
            println("Cleaning ratchets...")
            val now = System.currentTimeMillis()

            // Clean in-memory cache. Each list is trimmed and, if emptied, detached
            // from the map while its monitor is held — see withRatchetEntries.
            val iterator = destinationRatchets.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val ratchets = entry.value

                synchronized(ratchets) {
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
                        val (ratchet, received) = readRatchetFile(file.readBytes())

                        // Check if expired or corrupted
                        val receivedMs = ((received ?: 0.0) * 1000).toLong()
                        val isExpired = now - receivedMs > RATCHET_EXPIRY
                        val isCorrupted = (ratchet?.size ?: 0) != RnsConstants.KEY_SIZE

                        // RNS 1.3.1 _clean_ratchets also removes "not in use" ratchets:
                        // a file whose dest-hash (its hex filename) is absent from
                        // known_destinations is unknown and unlinked
                        // (installed RNS 1.3.1 Identity.py _clean_ratchets:
                        // `destination_hash = bytes.fromhex(filename); if not ... in
                        // known_destinations: unknown = True`; the `expired or corrupted
                        // or unknown` unlink). The pinned ../Reticulum checkout is 1.1.9
                        // and lacks this branch; the conformance target is 1.3.1. The hex
                        // decode is guarded so a non-hex filename is skipped, not deleted,
                        // matching python's per-file try/except.
                        val unknown = runCatching {
                            !knownDestinations.containsKey(file.name.hexToByteArray().toKey())
                        }.getOrDefault(false)

                        if (isExpired || isCorrupted || unknown) {
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
     * Receives the id of the ratchet that successfully decrypted a token,
     * mirroring python's `ratchet_id_receiver` duck-typed argument to
     * Identity.decrypt (Identity.py:865,884-908): set to the winning
     * ratchet's id on a ratchet decrypt, and to null when the static key
     * was used, enforcement failed, or decryption failed entirely.
     */
    class RatchetIdReceiver {
        var latestRatchetId: ByteArray? = null
    }

    /**
     * Decrypt ciphertext that was encrypted for this identity.
     *
     * Mirrors python Identity.decrypt (Identity.py:865-921), including the
     * ratchet trial order (supplied list IN ORDER, first success wins,
     * per-ratchet failures swallowed) and the [ratchetIdReceiver] contract.
     *
     * @param ciphertext The ciphertext token (ephemeral_pub || token)
     * @param ratchets Optional list of ratchet private keys to try
     * @param enforceRatchets If true, only decrypt if a ratchet succeeds
     * @param ratchetIdReceiver Receives the winning ratchet id (see [RatchetIdReceiver])
     * @return Decrypted plaintext, or null if decryption fails
     * @throws IllegalStateException if this identity doesn't have a private key
     */
    fun decrypt(
        ciphertext: ByteArray,
        ratchets: List<ByteArray>? = null,
        enforceRatchets: Boolean = false,
        ratchetIdReceiver: RatchetIdReceiver? = null
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
                    if (plaintext != null) {
                        // python computes the candidate's id before the exchange
                        // (Identity.py:872-873), but the id is a pure function of
                        // the ratchet and only observable on success, so defer the
                        // scalar mult + SHA-256 until a candidate actually wins.
                        ratchetIdReceiver?.latestRatchetId =
                            ratchetIdFor(crypto.x25519PublicFromPrivate(ratchet))
                        break
                    }
                } catch (e: Exception) {
                    // Try next ratchet
                }
            }
        }

        // If ratchet enforcement is on and we didn't decrypt, fail
        if (enforceRatchets && plaintext == null) {
            ratchetIdReceiver?.latestRatchetId = null
            return null
        }

        // Try regular decryption if ratchets didn't work; a static-key
        // success (or failure) reports no ratchet id, as python does.
        if (plaintext == null) {
            try {
                val sharedKey = crypto.x25519Exchange(x25519Private!!, peerPublicBytes)
                plaintext = decryptWithSharedKey(sharedKey, tokenData)
                ratchetIdReceiver?.latestRatchetId = null
            } catch (e: Exception) {
                ratchetIdReceiver?.latestRatchetId = null
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

        // python Identity.prove (Identity.py:961-963): an IMPLICIT proof carries
        // only the signature; an EXPLICIT proof prepends the packet hash. The
        // form is selected by RNS.Reticulum.should_use_implicit_proof().
        val proofData = if (network.reticulum.Reticulum.shouldUseImplicitProof()) {
            signature
        } else {
            packet.packetHash + signature
        }

        // Determine destination hash for proof
        val destinationHash = destination?.hash ?: packet.truncatedHash

        // Create and send proof packet
        val proof = network.reticulum.packet.Packet.createRaw(
            destinationHash = destinationHash,
            data = proofData,
            packetType = network.reticulum.common.PacketType.PROOF
        )

        // Send the proof back out the interface the proved packet ARRIVED on (python
        // Identity.py:956, `attached_interface = packet.receiving_interface`). Without
        // it the proof falls into Transport.outbound's broadcast, which fans it across
        // every interface — leaking a proof onto networks that never saw the packet,
        // and on a multi-client server sending N copies to reach one sender.
        packet.receivingInterfaceHash?.let { hash ->
            proof.attachedInterface = network.reticulum.transport.Transport.interfaceForHash(hash)
        }

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
