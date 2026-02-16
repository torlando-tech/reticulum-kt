package network.reticulum.resource

import network.reticulum.common.DestinationType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.link.Link
import network.reticulum.packet.Packet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Callbacks for resource transfer events.
 */
class ResourceCallbacks {
    var completed: ((Resource) -> Unit)? = null
    var progress: ((Resource) -> Unit)? = null
    var failed: ((Resource) -> Unit)? = null
}

/**
 * Represents a large data transfer over a Link.
 *
 * Resources handle automatic chunking, sequencing, compression,
 * and retransmission for reliable transfer of arbitrary-sized data.
 *
 * Usage for sending:
 * ```kotlin
 * val resource = Resource.create(data, link) { resource ->
 *     println("Transfer complete!")
 * }
 * ```
 *
 * Usage for receiving (via Link callback):
 * ```kotlin
 * link.callbacks.resourceStarted = { resource ->
 *     resource.callbacks.completed = { r ->
 *         val data = r.data
 *         // Process received data
 *     }
 * }
 * ```
 */
class Resource private constructor(
    /** The link this resource is being transferred over. */
    val link: Link,
    /** Whether this side initiated the transfer. */
    val initiator: Boolean
) {
    companion object {
        private val resourceCounter = AtomicInteger(0)
        private val random = SecureRandom()

        /**
         * Create a new resource for outgoing transfer.
         *
         * @param data The data to transfer
         * @param link The link to transfer over
         * @param metadata Optional metadata to include with the resource
         * @param advertise Whether to automatically advertise (default: true)
         * @param autoCompress Whether to compress the data (default: true)
         * @param callback Callback when transfer completes
         * @param progressCallback Callback for progress updates
         * @return The new Resource instance
         */
        fun create(
            data: ByteArray,
            link: Link,
            metadata: ByteArray? = null,
            advertise: Boolean = true,
            autoCompress: Boolean = true,
            callback: ((Resource) -> Unit)? = null,
            progressCallback: ((Resource) -> Unit)? = null
        ): Resource {
            val resource = Resource(link, initiator = true)

            callback?.let { resource.callbacks.completed = it }
            progressCallback?.let { resource.callbacks.progress = it }

            resource.initializeForSending(data, metadata, autoCompress)

            if (advertise) {
                resource.advertise()
            }

            return resource
        }

        /**
         * Accept an incoming resource advertisement.
         *
         * @param advertisement The received advertisement
         * @param link The link the advertisement came from
         * @param callback Callback when transfer completes
         * @param progressCallback Callback for progress updates
         * @return The new Resource instance, or null if invalid
         */
        fun accept(
            advertisement: ResourceAdvertisement,
            link: Link,
            callback: ((Resource) -> Unit)? = null,
            progressCallback: ((Resource) -> Unit)? = null
        ): Resource? {
            return try {
                val resource = Resource(link, initiator = false)

                callback?.let { resource.callbacks.completed = it }
                progressCallback?.let { resource.callbacks.progress = it }

                resource.initializeFromAdvertisement(advertisement)
                resource.requestNext()

                resource
            } catch (e: Exception) {
                log("Failed to accept resource: ${e.message}")
                null
            }
        }

        /**
         * Reject an incoming resource advertisement.
         */
        fun reject(advertisement: ResourceAdvertisement, link: Link) {
            try {
                val rejectPacket = Packet.createRaw(
                    destinationHash = advertisement.hash,
                    data = advertisement.hash,
                    context = PacketContext.RESOURCE_RCL
                )
                link.send(rejectPacket.raw ?: ByteArray(0))
            } catch (e: Exception) {
                log("Error rejecting resource: ${e.message}")
            }
        }

        private fun log(message: String) {
            val timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            )
            println("[$timestamp] [Resource] $message")
        }
    }

    // Resource identification
    var hash: ByteArray = ByteArray(0)
        private set
    var originalHash: ByteArray = ByteArray(0)
        private set
    var randomHash: ByteArray = ByteArray(0)
        private set

    // Size tracking
    var size: Int = 0           // Transfer size (possibly compressed)
        private set
    var totalSize: Int = 0      // Total uncompressed size
        private set
    var uncompressedSize: Int = 0
        private set

    // Status
    var status: Int = ResourceConstants.NONE
        private set

    // Parts management
    var parts: Array<ByteArray?> = arrayOf()
        private set
    var hashmapRaw: ByteArray = ByteArray(0)
        private set
    private var hashmap: Array<ByteArray?> = arrayOf()
    private var hashmapHeight: Int = 0
    private var receivedCount: Int = 0
    private var outstandingParts: Int = 0
    private var consecutiveCompletedHeight: Int = -1
    private var sentParts: Int = 0
    private val sentPartsSet = mutableSetOf<Int>()

    // Segmenting
    var segmentIndex: Int = 1
        private set
    var totalSegments: Int = 1
        private set
    var split: Boolean = false
        private set

    // Flags
    var compressed: Boolean = false
        private set
    var encrypted: Boolean = true  // Resources over links are always encrypted
        private set
    var hasMetadata: Boolean = false
        private set
    var isResponse: Boolean = false
        private set

    // Request tracking
    var requestId: ByteArray? = null
        private set

    // Callbacks
    val callbacks = ResourceCallbacks()

    // Window management
    private var window: Int = ResourceConstants.WINDOW
    private var windowMax: Int = ResourceConstants.WINDOW_MAX_SLOW
    private var windowMin: Int = ResourceConstants.WINDOW_MIN

    // Timing
    private var rtt: Long? = null
    private var lastActivity: Long = System.currentTimeMillis()
    private var lastPartSent: Long = 0
    private var startedTransferring: Long? = null
    private var retries: Int = 0

    // Request/response timing for RTT calculation
    private var reqSent: Long = 0
    private var reqResp: Long? = null
    private var reqSentBytes: Int = 0
    private var rttRxdBytes: Long = 0
    private var rttRxdBytesAtPartReq: Long = 0
    private var reqRespRttRate: Double = 0.0
    private var reqDataRttRate: Double = 0.0

    // Rate tracking
    private var fastRateRounds: Int = 0
    private var verySlowRateRounds: Int = 0
    private var windowFlexibility: Int = ResourceConstants.WINDOW_FLEXIBILITY
    private var eifr: Double = 0.0
    private var previousEifr: Double? = null

    // Hashmap update tracking
    private var waitingForHmu: Boolean = false
    private var receivingPart: Boolean = false
    private val receiveLock = java.util.concurrent.locks.ReentrantLock()
    private var assemblyLock: Boolean = false

    // Sender-side tracking
    private var receiverMinConsecutiveHeight: Int = 0
    private var advSent: Long = 0

    // Watchdog
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogActive = false

    // SDU for this resource — uses plain packet MDU (not link MDU) because
    // resource parts are already bulk-encrypted before splitting, and are sent
    // as raw packets that only add header + IFAC overhead (no Token encryption).
    // Python: self.sdu = self.link.mtu - RNS.Reticulum.HEADER_MAXSIZE - RNS.Reticulum.IFAC_MIN_SIZE
    private val sdu: Int = link.mtu - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE

    // Raw data
    private var uncompressedData: ByteArray? = null
    private var compressedData: ByteArray? = null
    private var assembledData: ByteArray? = null
    private var metadata: ByteArray? = null

    // Multi-segment support
    private var inputFile: java.io.RandomAccessFile? = null
    private var preparingNextSegment: Boolean = false
    private var nextSegment: Resource? = null

    // Proof tracking
    private var expectedProof: ByteArray? = null

    /**
     * Initialize resource for sending.
     * Matches Python RNS Resource.__init__() protocol.
     */
    private fun initializeForSending(data: ByteArray, metadata: ByteArray?, autoCompress: Boolean) {
        uncompressedData = data
        totalSize = data.size
        uncompressedSize = data.size

        // Handle metadata - prepend with 3-byte size prefix like Python
        var dataWithMetadata = data
        if (metadata != null && metadata.size <= ResourceConstants.METADATA_MAX_SIZE) {
            this.metadata = metadata
            this.hasMetadata = true
            // Pack metadata with 3-byte size prefix (big-endian)
            val metaSize = metadata.size
            val metaPrefix = byteArrayOf(
                ((metaSize shr 16) and 0xFF).toByte(),
                ((metaSize shr 8) and 0xFF).toByte(),
                (metaSize and 0xFF).toByte()
            )
            dataWithMetadata = metaPrefix + metadata + data
            totalSize = dataWithMetadata.size
        }

        // Compress if requested and within limits
        val compressedResult = if (autoCompress && dataWithMetadata.size <= ResourceConstants.AUTO_COMPRESS_MAX_SIZE) {
            compress(dataWithMetadata)
        } else {
            dataWithMetadata
        }

        compressed = compressedResult.size < dataWithMetadata.size
        compressedData = if (compressed) compressedResult else null

        // Use compressed data if it's smaller, otherwise uncompressed
        val contentData = if (compressed) compressedResult else dataWithMetadata

        // Generate random hash for hash calculations (this is sent in advertisement)
        randomHash = ByteArray(ResourceConstants.RANDOM_HASH_SIZE).also { random.nextBytes(it) }

        // Generate random prefix for the data stream (different from randomHash!)
        // This provides uniqueness for the encrypted stream
        val dataRandomPrefix = ByteArray(ResourceConstants.RANDOM_HASH_SIZE).also { random.nextBytes(it) }

        // Build the transfer data: random_prefix + content
        val prefixedData = dataRandomPrefix + contentData

        // Encrypt the entire data stream using the link's encryption
        val encryptedData = link.encrypt(prefixedData)
        encrypted = true

        size = encryptedData.size
        log("initializeForSending: prefixedData=${prefixedData.size} bytes, encryptedData=${encryptedData.size} bytes")

        // Split encrypted data into parts
        val totalParts = ceil(size.toDouble() / sdu).toInt()
        parts = arrayOfNulls(totalParts)
        hashmap = arrayOfNulls(totalParts)

        // Create hashmap and parts from encrypted data
        val hashmapBuilder = ByteArrayOutputStream()
        for (i in 0 until totalParts) {
            val start = i * sdu
            val end = min(start + sdu, size)
            val part = encryptedData.copyOfRange(start, end)
            parts[i] = part

            // Calculate part hash: full_hash(part + randomHash)[:MAPHASH_LEN]
            val partHash = getMapHash(part)
            hashmap[i] = partHash
            hashmapBuilder.write(partHash)
        }
        hashmapRaw = hashmapBuilder.toByteArray()

        // Calculate resource hash from UNCOMPRESSED data (with metadata) + randomHash
        // This matches Python: self.hash = RNS.Identity.full_hash(data+self.random_hash)
        hash = Hashes.fullHash(dataWithMetadata + randomHash)
        originalHash = hash.copyOf()

        // Calculate expected proof: full_hash(uncompressed_data + hash)
        expectedProof = Hashes.fullHash(dataWithMetadata + hash)

        // Check for segmentation
        if (totalSize > ResourceConstants.MAX_EFFICIENT_SIZE) {
            totalSegments = ((totalSize - 1) / ResourceConstants.MAX_EFFICIENT_SIZE) + 1
            split = true
        }

        status = ResourceConstants.QUEUED
        log("Resource ${hash.toHexString()} created: $size bytes in ${parts.size} parts (compressed=$compressed, encrypted=$encrypted)")
    }

    /**
     * Initialize resource from received advertisement.
     */
    private fun initializeFromAdvertisement(adv: ResourceAdvertisement) {
        status = ResourceConstants.TRANSFERRING
        hash = adv.hash
        originalHash = adv.originalHash
        randomHash = adv.randomHash
        size = adv.transferSize
        totalSize = adv.dataSize
        uncompressedSize = adv.dataSize
        compressed = adv.compressed
        encrypted = adv.encrypted
        hasMetadata = adv.hasMetadata
        split = adv.split
        segmentIndex = adv.segmentIndex
        totalSegments = adv.totalSegments
        requestId = adv.requestId

        // Initialize parts array
        val totalParts = adv.numParts
        parts = arrayOfNulls(totalParts)
        hashmap = arrayOfNulls(totalParts)

        // Parse hashmap from advertisement
        hashmapRaw = adv.hashmap
        updateHashmap(0, hashmapRaw)

        lastActivity = System.currentTimeMillis()
        startedTransferring = lastActivity

        // Register with link
        link.registerIncomingResource(this)

        startWatchdog()
        log("Resource ${hash.toHexString()} accepted: $size bytes in ${parts.size} parts")
    }

    /**
     * Advertise this resource to the receiver.
     */
    fun advertise() {
        if (status != ResourceConstants.QUEUED) return

        // Register with link
        link.registerOutgoingResource(this)

        status = ResourceConstants.ADVERTISED
        val adv = ResourceAdvertisement.fromResource(this)
        val advData = adv.pack()

        // Debug: log the advertisement content
        log("Advertisement content:")
        log("  transferSize=${adv.transferSize}, dataSize=${adv.dataSize}, numParts=${adv.numParts}")
        log("  hash=${adv.hash.toHexString()} (${adv.hash.size} bytes)")
        log("  randomHash=${adv.randomHash.toHexString()} (${adv.randomHash.size} bytes)")
        log("  flags=${adv.flags}, segmentIndex=${adv.segmentIndex}, totalSegments=${adv.totalSegments}")
        log("  advData size=${advData.size} bytes")

        // Send encrypted via link
        val encrypted = link.encrypt(advData)
        log("  encrypted size=${encrypted.size} bytes")

        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = encrypted,
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = PacketContext.RESOURCE_ADV,
            mtu = link.mtu
        )

        log("  packet linkId=${link.linkId.toHexString()}, raw size=${packet.raw?.size ?: "null"}")
        log("  link status=${link.status}")
        val receipt = packet.send()
        log("  send result: receipt=${receipt != null}, packet.sent=${packet.sent}")
        lastActivity = System.currentTimeMillis()
        advSent = lastActivity

        startWatchdog()
        log("Advertised resource ${hash.toHexString()}")
    }

    /**
     * Send the next batch of parts.
     */
    private fun sendParts() {
        if (status != ResourceConstants.TRANSFERRING) return

        var sent = 0
        for (i in parts.indices) {
            if (sent >= window) break

            val part = parts[i]
            if (part != null) {
                sendPart(i, part)
                sent++
            }
        }
    }

    /**
     * Send a single part.
     * Matches Python: part is just the encrypted data chunk, no index prefix.
     * The receiver identifies parts by their map hash, not by index.
     */
    private fun sendPart(index: Int, data: ByteArray) {
        // Send just the data - no index prefix!
        // Python identifies parts by computing the map hash of the received data
        link.sendResourceData(data)
        lastActivity = System.currentTimeMillis()
        lastPartSent = lastActivity

        // Track sent parts
        if (sentPartsSet.add(index)) {
            sentParts++
        }
    }

    /**
     * Receive a part from the sender.
     * Parts are identified by their map hash, not by index.
     * Matches Python RNS receive_part() protocol.
     */
    fun receivePart(data: ByteArray) {
        receiveLock.lock()
        try {
            receivingPart = true
            lastActivity = System.currentTimeMillis()
            retries = 0

            // RTT calculation on first response
            if (reqResp == null) {
                reqResp = lastActivity
                val rttMs = reqResp!! - reqSent

                if (rtt == null) {
                    rtt = link.rtt ?: rttMs
                } else if (rttMs < rtt!!) {
                    rtt = maxOf(rtt!! - (rtt!! * 0.05).toLong(), rttMs)
                } else if (rttMs > rtt!!) {
                    rtt = minOf(rtt!! + (rtt!! * 0.05).toLong(), rttMs)
                }

                // Calculate request-response RTT rate
                if (rttMs > 0) {
                    val reqRespCost = data.size + reqSentBytes
                    reqRespRttRate = reqRespCost.toDouble() / (rttMs.toDouble() / 1000.0)

                    if (reqRespRttRate > ResourceConstants.RATE_FAST && fastRateRounds < ResourceConstants.FAST_RATE_THRESHOLD) {
                        fastRateRounds++
                        if (fastRateRounds == ResourceConstants.FAST_RATE_THRESHOLD) {
                            windowMax = ResourceConstants.WINDOW_MAX_FAST
                        }
                    }
                }
            }

            if (status == ResourceConstants.FAILED) {
                receivingPart = false
                return
            }

            status = ResourceConstants.TRANSFERRING
            val partData = data
            val partHash = getMapHash(partData)

            log("receivePart: received ${partData.size} bytes, partHash=${partHash.toHexString()}")
            log("receivePart: randomHash=${randomHash.toHexString()}, hashmap size=${hashmap.size}")
            if (hashmap.isNotEmpty() && hashmap[0] != null) {
                log("receivePart: expected hashmap[0]=${hashmap[0]!!.toHexString()}")
            }

            // Search for matching hash in current window
            val searchStart = if (consecutiveCompletedHeight >= 0) consecutiveCompletedHeight else 0
            log("receivePart: searchStart=$searchStart, window=$window, parts.size=${parts.size}")
            for (i in searchStart until minOf(searchStart + window, parts.size)) {
                val mapHash = hashmap[i]
                if (mapHash != null && mapHash.contentEquals(partHash)) {
                    if (parts[i] == null) {
                        // Insert data into parts list
                        parts[i] = partData
                        rttRxdBytes += partData.size
                        receivedCount++
                        outstandingParts--

                        // Update consecutive completed pointer
                        if (i == consecutiveCompletedHeight + 1) {
                            consecutiveCompletedHeight = i
                        }

                        // Extend consecutive pointer if possible
                        var cp = consecutiveCompletedHeight + 1
                        while (cp < parts.size && parts[cp] != null) {
                            consecutiveCompletedHeight = cp
                            cp++
                        }

                        // Progress callback
                        try {
                            callbacks.progress?.invoke(this)
                        } catch (e: Exception) {
                            log("Error in progress callback: ${e.message}")
                        }
                    }
                    break
                }
            }

            receivingPart = false

            // Check if transfer complete
            if (receivedCount == parts.size && !assemblyLock) {
                assemblyLock = true
                assemble()
            } else if (outstandingParts == 0) {
                // All outstanding parts received, adjust window and request more
                if (window < windowMax) {
                    window++
                    if ((window - windowMin) > (windowFlexibility - 1)) {
                        windowMin++
                    }
                }

                // Calculate data rate
                if (reqSent != 0L) {
                    val rttMs = System.currentTimeMillis() - reqSent
                    val reqTransferred = rttRxdBytes - rttRxdBytesAtPartReq

                    if (rttMs != 0L) {
                        reqDataRttRate = reqTransferred.toDouble() / (rttMs.toDouble() / 1000.0)
                        updateEifr()
                        rttRxdBytesAtPartReq = rttRxdBytes

                        if (reqDataRttRate > ResourceConstants.RATE_FAST && fastRateRounds < ResourceConstants.FAST_RATE_THRESHOLD) {
                            fastRateRounds++
                            if (fastRateRounds == ResourceConstants.FAST_RATE_THRESHOLD) {
                                windowMax = ResourceConstants.WINDOW_MAX_FAST
                            }
                        }

                        if (fastRateRounds == 0 && reqDataRttRate < ResourceConstants.RATE_VERY_SLOW &&
                            verySlowRateRounds < ResourceConstants.VERY_SLOW_RATE_THRESHOLD) {
                            verySlowRateRounds++
                            if (verySlowRateRounds == ResourceConstants.VERY_SLOW_RATE_THRESHOLD) {
                                windowMax = ResourceConstants.WINDOW_MAX_VERY_SLOW
                            }
                        }
                    }
                }

                requestNext()
            }
        } finally {
            receivingPart = false
            receiveLock.unlock()
        }
    }

    /**
     * Request the next batch of missing parts.
     * Matches Python RNS request_next() protocol.
     */
    private fun requestNext() {
        // Wait for any receiving operation to complete
        while (receivingPart) {
            Thread.sleep(1)
        }

        if (status == ResourceConstants.FAILED) return
        if (waitingForHmu) return

        outstandingParts = 0
        var hashmapExhausted = ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED
        val requestedHashes = ByteArrayOutputStream()

        var i = 0
        var pn = consecutiveCompletedHeight + 1
        val searchStart = pn

        for (partIdx in searchStart until minOf(searchStart + window, parts.size)) {
            if (parts[partIdx] == null) {
                val partHash = hashmap[partIdx]
                if (partHash != null) {
                    requestedHashes.write(partHash)
                    outstandingParts++
                    i++
                } else {
                    hashmapExhausted = ResourceConstants.HASHMAP_IS_EXHAUSTED
                }
            }
            pn++
            if (i >= window || hashmapExhausted == ResourceConstants.HASHMAP_IS_EXHAUSTED) {
                break
            }
        }

        // Build HMU part
        val hmuPart = ByteArrayOutputStream()
        hmuPart.write(hashmapExhausted)
        if (hashmapExhausted == ResourceConstants.HASHMAP_IS_EXHAUSTED) {
            val lastMapHash = hashmap[hashmapHeight - 1]
            if (lastMapHash != null) {
                hmuPart.write(lastMapHash)
            }
            waitingForHmu = true
        }

        // Build full request: hmu_part + resource_hash + requested_hashes
        val requestData = ByteArrayOutputStream()
        requestData.write(hmuPart.toByteArray())
        requestData.write(hash)
        requestData.write(requestedHashes.toByteArray())

        try {
            // Send encrypted via link
            val reqDataBytes = requestData.toByteArray()
            val encrypted = link.encrypt(reqDataBytes)
            val packet = Packet.createRaw(
                destinationHash = link.linkId,
                data = encrypted,
                packetType = PacketType.DATA,
                destinationType = DestinationType.LINK,
                context = PacketContext.RESOURCE_REQ,
                mtu = link.mtu
            )

            packet.send()
            lastActivity = System.currentTimeMillis()
            reqSent = lastActivity
            reqSentBytes = encrypted.size
            reqResp = null
        } catch (e: Exception) {
            log("Failed to send resource request: ${e.message}")
        }
    }

    /**
     * Handle a request for parts from the receiver.
     * Matches Python RNS request() protocol.
     */
    fun handleRequest(data: ByteArray) {
        if (status == ResourceConstants.FAILED) return

        // Calculate RTT
        val rttMs = System.currentTimeMillis() - advSent
        if (rtt == null) {
            rtt = rttMs
        }

        if (status != ResourceConstants.TRANSFERRING) {
            status = ResourceConstants.TRANSFERRING
            startedTransferring = System.currentTimeMillis()
        }

        retries = 0

        // Parse request format: [hmu_flag] [last_map_hash?] [resource_hash] [requested_hashes...]
        val wantsMoreHashmap = data[0].toInt() and 0xFF == ResourceConstants.HASHMAP_IS_EXHAUSTED
        val pad = if (wantsMoreHashmap) 1 + ResourceConstants.MAPHASH_LEN else 1

        // Extract requested hashes (after pad + resource hash)
        val hashStart = pad + ResourceConstants.RESOURCE_HASH_LEN
        if (data.size <= hashStart) return

        val requestedHashesData = data.copyOfRange(hashStart, data.size)

        // Define search scope
        val searchStart = receiverMinConsecutiveHeight
        val searchEnd = receiverMinConsecutiveHeight + ResourceAdvertisement.COLLISION_GUARD_SIZE

        // Parse requested map hashes
        val mapHashes = mutableListOf<ByteArray>()
        for (i in 0 until requestedHashesData.size / ResourceConstants.MAPHASH_LEN) {
            val start = i * ResourceConstants.MAPHASH_LEN
            val end = start + ResourceConstants.MAPHASH_LEN
            mapHashes.add(requestedHashesData.copyOfRange(start, end))
        }

        // Find and send requested parts
        val searchScope = parts.slice(searchStart until minOf(searchEnd, parts.size))
        for ((index, part) in searchScope.withIndex()) {
            if (part != null) {
                val partMapHash = getMapHash(part)
                if (mapHashes.any { it.contentEquals(partMapHash) }) {
                    val actualIndex = searchStart + index
                    if (!sentPartsSet.contains(actualIndex)) {
                        sendPart(actualIndex, part)
                        sentParts++
                        sentPartsSet.add(actualIndex)
                    } else {
                        // Resend
                        sendPart(actualIndex, part)
                    }
                    lastActivity = System.currentTimeMillis()
                }
            }
        }

        // Handle hashmap update request
        if (wantsMoreHashmap) {
            val lastMapHash = data.copyOfRange(1, 1 + ResourceConstants.MAPHASH_LEN)

            // Find the part that matches last_map_hash
            var partIndex = receiverMinConsecutiveHeight
            for (i in searchStart until minOf(searchEnd, parts.size)) {
                val part = parts[i]
                if (part != null) {
                    val partMapHash = getMapHash(part)
                    partIndex++
                    if (partMapHash.contentEquals(lastMapHash)) {
                        break
                    }
                } else {
                    partIndex++
                }
            }

            receiverMinConsecutiveHeight = maxOf(partIndex - 1 - ResourceConstants.WINDOW_MAX, 0)

            if (partIndex % ResourceAdvertisement.HASHMAP_MAX_LEN != 0) {
                log("Resource sequencing error, cancelling transfer!")
                cancel()
                return
            }

            val segment = partIndex / ResourceAdvertisement.HASHMAP_MAX_LEN

            // Build hashmap update
            val hashmapStart = segment * ResourceAdvertisement.HASHMAP_MAX_LEN
            val hashmapEnd = minOf((segment + 1) * ResourceAdvertisement.HASHMAP_MAX_LEN, parts.size)

            val hashmapData = ByteArrayOutputStream()
            for (i in hashmapStart until hashmapEnd) {
                val start = i * ResourceConstants.MAPHASH_LEN
                val end = start + ResourceConstants.MAPHASH_LEN
                if (end <= hashmapRaw.size) {
                    hashmapData.write(hashmapRaw.copyOfRange(start, end))
                }
            }

            // Send hashmap update: resource_hash + msgpack([segment, hashmap])
            val hmuData = ByteArrayOutputStream()
            hmuData.write(hash)
            // Pack [segment, hashmap] using msgpack
            val packer = org.msgpack.core.MessagePack.newDefaultPacker(hmuData)
            packer.packArrayHeader(2)
            packer.packInt(segment)
            packer.packBinaryHeader(hashmapData.size())
            packer.writePayload(hashmapData.toByteArray())
            packer.close()

            try {
                // Send encrypted via link
                val hmuBytes = hmuData.toByteArray()
                val encrypted = link.encrypt(hmuBytes)
                val hmuPacket = Packet.createRaw(
                    destinationHash = link.linkId,
                    data = encrypted,
                    packetType = PacketType.DATA,
                    destinationType = DestinationType.LINK,
                    context = PacketContext.RESOURCE_HMU,
                    mtu = link.mtu
                )
                hmuPacket.send()
                lastActivity = System.currentTimeMillis()
            } catch (e: Exception) {
                log("Failed to send hashmap update: ${e.message}")
            }
        }

        // Check if all parts have been sent
        if (sentParts >= parts.size) {
            status = ResourceConstants.AWAITING_PROOF
            log("All parts sent, awaiting proof for ${hash.toHexString()}")
        }
    }

    /**
     * Handle a hashmap update packet from the sender.
     * Matches Python RNS hashmap_update_packet().
     */
    fun handleHashmapUpdate(plaintext: ByteArray) {
        if (status == ResourceConstants.FAILED) return

        lastActivity = System.currentTimeMillis()
        retries = 0

        // Parse: resource_hash (32 bytes) + msgpack([segment, hashmap])
        if (plaintext.size <= ResourceConstants.RESOURCE_HASH_LEN) return

        val msgpackData = plaintext.copyOfRange(ResourceConstants.RESOURCE_HASH_LEN, plaintext.size)

        try {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(msgpackData)
            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize != 2) return

            val segment = unpacker.unpackInt()
            val hashmapLen = unpacker.unpackBinaryHeader()
            val hashmapBytes = unpacker.readPayload(hashmapLen)
            unpacker.close()

            hashmapUpdate(segment, hashmapBytes)
        } catch (e: Exception) {
            log("Failed to parse hashmap update: ${e.message}")
        }
    }

    /**
     * Apply a hashmap update.
     * Matches Python RNS hashmap_update().
     */
    private fun hashmapUpdate(segment: Int, hashmapBytes: ByteArray) {
        if (status == ResourceConstants.FAILED) return

        status = ResourceConstants.TRANSFERRING
        val segLen = ResourceAdvertisement.HASHMAP_MAX_LEN
        val hashes = hashmapBytes.size / ResourceConstants.MAPHASH_LEN

        for (i in 0 until hashes) {
            val idx = i + segment * segLen
            if (idx < hashmap.size && hashmap[idx] == null) {
                hashmapHeight++
            }
            if (idx < hashmap.size) {
                val start = i * ResourceConstants.MAPHASH_LEN
                val end = start + ResourceConstants.MAPHASH_LEN
                hashmap[idx] = hashmapBytes.copyOfRange(start, end)
            }
        }

        waitingForHmu = false
        requestNext()
    }

    /**
     * Update expected in-flight rate.
     * Matches Python RNS update_eifr().
     */
    private fun updateEifr() {
        val currentRtt = rtt ?: link.rtt ?: return

        val expectedInflightRate = if (reqDataRttRate != 0.0) {
            reqDataRttRate * 8
        } else if (previousEifr != null) {
            previousEifr!!
        } else {
            // Estimate from link establishment cost
            (link.mdu * 8).toDouble() / (currentRtt.toDouble() / 1000.0)
        }

        eifr = expectedInflightRate
        previousEifr = eifr
    }

    /**
     * Send proof of complete receipt to sender.
     * Called by receiver after successfully assembling all parts.
     * Matches Python: proof = full_hash(self.data + self.hash)
     * where self.data includes metadata (before stripping).
     */
    private fun prove() {
        if (status == ResourceConstants.FAILED) return

        try {
            // Use uncompressedData which contains data WITH metadata
            // This matches Python's prove() which uses self.data before metadata is stripped
            val proofData = uncompressedData
            if (proofData == null) {
                log("Cannot prove resource: no assembled data")
                return
            }

            val proof = Hashes.fullHash(proofData + hash)
            val proofPayload = hash + proof

            // Create proof packet - NOT encrypted (matches Python: resource proofs are not encrypted)
            val packet = Packet.createRaw(
                destinationHash = link.linkId,
                data = proofPayload,
                packetType = PacketType.PROOF,
                destinationType = DestinationType.LINK,
                context = PacketContext.RESOURCE_PRF,
                mtu = link.mtu
            )

            packet.send()
            log("Sent proof for resource ${hash.toHexString()}")

        } catch (e: Exception) {
            log("Could not send proof packet: ${e.message}")
            cancel()
        }
    }

    /**
     * Validate proof received from receiver.
     * Called by sender when proof packet arrives.
     */
    fun validateProof(proofData: ByteArray): Boolean {
        if (status == ResourceConstants.FAILED) return false

        try {
            // Proof format: [resource_hash (32 bytes)][proof (32 bytes)]
            // Python sends full hash (32 bytes), not truncated (16 bytes)
            if (proofData.size != RnsConstants.FULL_HASH_BYTES * 2) {
                log("Invalid proof length: ${proofData.size}")
                return false
            }

            val receivedHash = proofData.copyOfRange(0, RnsConstants.FULL_HASH_BYTES)
            val receivedProof = proofData.copyOfRange(RnsConstants.FULL_HASH_BYTES, proofData.size)

            // Verify the proof matches expected
            val expected = expectedProof
            if (expected == null) {
                log("No expected proof available")
                return false
            }

            if (!receivedProof.contentEquals(expected)) {
                log("Proof validation failed: mismatch")
                return false
            }

            // Mark resource as complete
            status = ResourceConstants.COMPLETE
            stopWatchdog()
            link.resourceConcluded(this)
            log("Resource ${hash.toHexString()} proof validated successfully")

            // Handle multi-segment resources
            if (segmentIndex < totalSegments) {
                // Prepare and advertise next segment
                if (!preparingNextSegment) {
                    log("Preparing next segment ${segmentIndex + 1}/$totalSegments")
                    prepareNextSegment()
                }

                // Wait for next segment to be ready
                while (nextSegment == null) {
                    Thread.sleep(50)
                }

                // Advertise the next segment
                nextSegment?.advertise()

                // Clean up this segment's data
                uncompressedData = null
                compressedData = null
                assembledData = null
                parts = arrayOf()
            } else {
                // All segments complete, invoke callback
                callbacks.completed?.invoke(this)

                // Close input file if present
                inputFile?.close()
                inputFile = null
            }

            return true

        } catch (e: Exception) {
            log("Error validating proof: ${e.message}")
            return false
        }
    }

    /**
     * Prepare the next segment for a multi-segment transfer.
     * This creates a new Resource for the next segment of data.
     */
    private fun prepareNextSegment() {
        if (preparingNextSegment) return
        if (segmentIndex >= totalSegments) return

        preparingNextSegment = true
        log("Preparing segment ${segmentIndex + 1} of $totalSegments")

        thread(name = "segment-prep-${hash.toHexString().take(8)}") {
            try {
                val file = inputFile
                if (file == null) {
                    log("Cannot prepare next segment: no input file")
                    return@thread
                }

                // Calculate segment data range
                val firstSegmentSize = ResourceConstants.MAX_EFFICIENT_SIZE -
                    (if (hasMetadata) metadata?.size ?: 0 else 0)
                val segmentSize = ResourceConstants.MAX_EFFICIENT_SIZE

                val dataStart = if (segmentIndex == 1) {
                    0L
                } else {
                    firstSegmentSize + ((segmentIndex - 1L) * segmentSize)
                }

                // Read next segment data
                file.seek(dataStart)
                val readSize = min(segmentSize.toLong(), file.length() - dataStart).toInt()
                val segmentData = ByteArray(readSize)
                file.readFully(segmentData)

                // Create next segment resource (without metadata)
                nextSegment = Resource(link, initiator = true).apply {
                    this.callbacks.completed = this@Resource.callbacks.completed
                    this.callbacks.progress = this@Resource.callbacks.progress
                    this.callbacks.failed = this@Resource.callbacks.failed

                    initializeForSending(
                        data = segmentData,
                        metadata = null,
                        autoCompress = compressed
                    )

                    // Update segment tracking
                    this.segmentIndex = this@Resource.segmentIndex + 1
                    this.totalSegments = this@Resource.totalSegments
                    this.split = true
                    this.originalHash = this@Resource.originalHash
                    this.inputFile = this@Resource.inputFile
                }

                log("Next segment prepared: ${nextSegment?.hash?.toHexString()}")

            } catch (e: Exception) {
                log("Error preparing next segment: ${e.message}")
                preparingNextSegment = false
            }
        }
    }

    /**
     * Assemble received parts into final data.
     * Matches Python RNS Resource.assemble() protocol.
     */
    private fun assemble() {
        if (status != ResourceConstants.TRANSFERRING) return

        status = ResourceConstants.ASSEMBLING
        log("Assembling resource ${hash.toHexString()}")

        try {
            // Combine all parts (encrypted stream)
            val output = ByteArrayOutputStream()
            for (part in parts) {
                if (part == null) {
                    status = ResourceConstants.FAILED
                    log("Assembly failed: missing parts")
                    callbacks.failed?.invoke(this)
                    return
                }
                output.write(part)
            }

            val encryptedStream = output.toByteArray()

            // Decrypt the stream if encrypted
            var decryptedData = if (encrypted) {
                link.decrypt(encryptedStream) ?: run {
                    status = ResourceConstants.FAILED
                    log("Assembly failed: decryption error")
                    callbacks.failed?.invoke(this)
                    return
                }
            } else {
                encryptedStream
            }

            // Strip off the random prefix (first RANDOM_HASH_SIZE bytes)
            if (decryptedData.size < ResourceConstants.RANDOM_HASH_SIZE) {
                status = ResourceConstants.FAILED
                log("Assembly failed: data too short after decryption")
                callbacks.failed?.invoke(this)
                return
            }
            decryptedData = decryptedData.copyOfRange(ResourceConstants.RANDOM_HASH_SIZE, decryptedData.size)

            // Decompress if needed
            var assembled = if (compressed) {
                decompress(decryptedData)
            } else {
                decryptedData
            }

            // Verify hash matches
            val calculatedHash = Hashes.fullHash(assembled + randomHash)
            if (!calculatedHash.contentEquals(hash)) {
                status = ResourceConstants.CORRUPT
                log("Assembly failed: hash mismatch")
                callbacks.failed?.invoke(this)
                return
            }

            // Store the full assembled data (with metadata) for proof calculation
            // This matches Python where self.data in prove() includes metadata
            val dataForProof = assembled

            // Strip metadata if present
            if (hasMetadata && assembled.size > 3) {
                val metaSize = ((assembled[0].toInt() and 0xFF) shl 16) or
                              ((assembled[1].toInt() and 0xFF) shl 8) or
                              (assembled[2].toInt() and 0xFF)
                if (metaSize > 0 && metaSize + 3 <= assembled.size) {
                    metadata = assembled.copyOfRange(3, 3 + metaSize)
                    assembled = assembled.copyOfRange(3 + metaSize, assembled.size)
                }
            }

            // assembledData = data after metadata stripped (what caller receives)
            // uncompressedData = data with metadata (for proof calculation)
            assembledData = assembled
            uncompressedData = dataForProof

            status = ResourceConstants.COMPLETE
            stopWatchdog()
            link.resourceConcluded(this)
            log("Resource ${hash.toHexString()} assembled: ${assembled.size} bytes")

            // Send proof to sender
            prove()

            callbacks.completed?.invoke(this)

        } catch (e: Exception) {
            status = ResourceConstants.FAILED
            log("Assembly error: ${e.message}")
            e.printStackTrace()
            callbacks.failed?.invoke(this)
        }
    }

    /**
     * Cancel this resource transfer.
     */
    fun cancel() {
        stopWatchdog()
        status = ResourceConstants.FAILED
        link.resourceConcluded(this)
        log("Resource ${hash.toHexString()} cancelled")
    }

    /**
     * Get the received/assembled data (without metadata).
     */
    val data: ByteArray?
        get() = assembledData

    /**
     * Get transfer progress (0.0 to 1.0).
     */
    val progress: Float
        get() = if (parts.isEmpty()) 0f else receivedCount.toFloat() / parts.size

    /**
     * Update hashmap from received data.
     */
    private fun updateHashmap(startIndex: Int, hashmapData: ByteArray) {
        val hashLen = ResourceConstants.MAPHASH_LEN
        var mapIndex = startIndex
        var offset = 0

        while (offset + hashLen <= hashmapData.size && mapIndex < hashmap.size) {
            hashmap[mapIndex] = hashmapData.copyOfRange(offset, offset + hashLen)
            mapIndex++
            offset += hashLen
        }

        hashmapHeight = mapIndex
    }

    /**
     * Calculate a short hash for a part.
     * Matches Python: RNS.Identity.full_hash(data+self.random_hash)[:MAPHASH_LEN]
     */
    private fun getMapHash(data: ByteArray): ByteArray {
        return Hashes.fullHash(data + randomHash).copyOf(ResourceConstants.MAPHASH_LEN)
    }

    /**
     * Compress data using BZ2 (matches Python RNS).
     */
    private fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bz2 ->
            bz2.write(data)
        }
        return output.toByteArray()
    }

    /**
     * Decompress BZ2 data (matches Python RNS).
     */
    private fun decompress(data: ByteArray): ByteArray {
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        BZip2CompressorInputStream(input).use { bz2 ->
            val buffer = ByteArray(1024)
            var len: Int
            while (bz2.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
            }
        }
        return output.toByteArray()
    }

    /**
     * Start watchdog thread for timeout detection.
     */
    private fun startWatchdog() {
        if (watchdogActive) return

        watchdogActive = true
        watchdogThread = thread(name = "resource-watchdog-${hash.toHexString().take(8)}") {
            watchdogJob()
        }
    }

    /**
     * Stop the watchdog thread.
     */
    private fun stopWatchdog() {
        watchdogActive = false
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /**
     * Watchdog job for timeout handling.
     */
    private fun watchdogJob() {
        while (watchdogActive) {
            try {
                Thread.sleep(ResourceConstants.WATCHDOG_MAX_SLEEP * 1000)

                if (!watchdogActive) break

                val now = System.currentTimeMillis()
                val idleTime = now - lastActivity

                // Check for timeout
                val timeout = (link.rtt ?: 5000L) * ResourceConstants.PART_TIMEOUT_FACTOR
                if (idleTime > timeout) {
                    retries++
                    if (retries > ResourceConstants.MAX_RETRIES) {
                        status = ResourceConstants.FAILED
                        log("Resource ${hash.toHexString()} timed out after $retries retries")
                        callbacks.failed?.invoke(this)
                        watchdogActive = false
                        break
                    } else {
                        log("Resource timeout, retry $retries/${ResourceConstants.MAX_RETRIES}")
                        if (!initiator) {
                            requestNext()
                        }
                    }
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log("Watchdog error: ${e.message}")
            }
        }
    }

    override fun toString(): String {
        return "<Resource ${hash.toHexString().take(16)}/${link.linkId.toHexString().take(16)}>"
    }
}
