package network.reticulum.resource

import network.reticulum.common.DestinationType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
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
         * Test-only watchdog suppression. Mirrors the reference conformance
         * harness monkeypatching `RNS.Resource.watchdog_job = lambda self: None`
         * around `_build_resource_receiver` (wire_tcp.py:6903) so an inbound
         * Resource can be built and driven synchronously without its watchdog
         * thread firing a part-request / timeout retry that would cancel the
         * transfer out from under inspection. Production code never sets this;
         * the conformance-bridge sets it true for the duration of a receiver
         * build and resets it in resetWireState().
         */
        @Volatile
        var watchdogDisabledForTest: Boolean = false

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
            progressCallback: ((Resource) -> Unit)? = null,
            requestId: ByteArray? = null,
            isResponse: Boolean = false,
            timeout: Long? = null
        ): Resource {
            val resource = Resource(link, initiator = true)

            callback?.let { resource.callbacks.completed = it }
            progressCallback?.let { resource.callbacks.progress = it }

            resource.requestId = requestId
            resource.isResponse = isResponse

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
            // Dedupe duplicate advertisements before doing any setup work.
            // Mirrors python `RNS.Resource.accept`'s
            // `if not resource.link.has_incoming_resource(resource)` guard
            // at Resource.py:223 — the check sits inside accept() so all
            // four `Link.processResourceAdv` call sites (isRequest,
            // isResponse, ACCEPT_APP, ACCEPT_ALL) automatically benefit.
            // Transport's packet hashlist intentionally skips LINK-destined
            // packets, so a sender retransmit of `RESOURCE_ADV` reaches the
            // link layer in raw form; without this check a fresh Resource
            // instance gets built per retransmit and assemble fires twice
            // (observed as `Inbox sizes [N, N]` in the cross-impl
            // conformance suite).
            if (link.hasIncomingResource(advertisement.hash)) {
                log(
                    "Ignoring RESOURCE_ADV ${advertisement.hash.toHexString()} — " +
                        "resource already transferring",
                )
                return null
            }
            // Track whether initialization registered the resource so that
            // a thrown `requestNext()` doesn't leave a zombie entry in
            // `link.incomingResources`. `initializeFromAdvertisement` calls
            // `link.registerIncomingResource(this)` and `startWatchdog()`
            // before we get a chance to call `requestNext()`; a throw from
            // there with the registration leaked would mean the dedup guard
            // above rejects every subsequent retransmit of the same
            // advertisement.hash for the lifetime of the link, removing the
            // recovery path entirely. Python's accept (Resource.py:223-244)
            // has the same shape but the failure modes there are caught by
            // its own watchdog cancellation; we mirror that recovery
            // explicitly via `resource.cancel()`.
            var resource: Resource? = null
            return try {
                resource = Resource(link, initiator = false)

                callback?.let { resource.callbacks.completed = it }
                progressCallback?.let { resource.callbacks.progress = it }

                resource.initializeFromAdvertisement(advertisement)
                // Python invokes resource_started synchronously after the
                // inbound Resource is registered and before hashmap_update()
                // requests the first parts (Resource.py:223-234). This ordering
                // is load-bearing: applications use the callback to configure
                // per-transfer limits such as max_decompressed_size. Deferring
                // it lets a small compressed Resource arrive and assemble with
                // the default limit before the callback applies its bound.
                link.resourceStarted(resource)
                resource.requestNext()
                // Python starts the watchdog only after resource_started and
                // the initial hashmap request (Resource.py:223-234). Starting
                // it during initialization allows a watchdog retry to bypass a
                // slow callback and request parts before configuration finishes.
                resource.startWatchdog()

                resource
            } catch (e: Exception) {
                log("Failed to accept resource: ${e.message}")
                resource?.cancel()
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

    // Status. @Volatile because it is read across threads: the daemon
    // advertise spin-wait (below) and the watchdog read it while cancel() /
    // conclude write it from another thread. Without it the JVM may cache a
    // stale value, so the daemon's `while (status == QUEUED ...)` loop could
    // spin until shutdown and its post-loop QUEUED guards (and doAdvertise's)
    // could see a stale QUEUED after a cancel. Python's GIL gives this
    // cross-thread visibility for free; @Volatile is the JVM equivalent, not a
    // behavior change. status is only ever ASSIGNED (never read-modify-written),
    // so @Volatile suffices — no atomic needed.
    @Volatile
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

    /**
     * Current flow-control window, exposed module-internally so
     * `Link.resourceConcluded` can record it as the link's last-resource-window
     * (Link.py:1284). Not a test seam — this is production state used by the
     * window-inheritance path.
     */
    internal val currentWindow: Int get() = window
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

    // Decompression-bomb ceiling. Mirrors python Resource.__init__
    // (Resource.py:364-365): max_decompressed_size == auto_compress_limit ==
    // Resource.AUTO_COMPRESS_MAX_SIZE (64 MiB). This is the bound the receiver's
    // bounded bz2 decompression stops at before declaring a CORRUPT bomb
    // (Resource.py:686-689). A listener may lower it per-inbound-resource.
    @Volatile
    private var maxDecompressedSize: Int = ResourceConstants.AUTO_COMPRESS_MAX_SIZE
    private var autoCompressLimit: Int = ResourceConstants.AUTO_COMPRESS_MAX_SIZE

    // Conformance instrumentation counters (see *ForTest accessors). These count
    // genuine state-machine events the reference harness observes by wrapping the
    // python instance methods (which kotlin cannot monkeypatch per-instance).
    // AtomicInteger, not @Volatile Int: these are bumped with ++ from the
    // receiver's part-delivery path and the background watchdog. @Volatile only
    // guarantees visibility; ++ is a non-atomic read-modify-write that can lose
    // increments under concurrency. incrementAndGet() is atomic. (Kotlin-only
    // conformance instrumentation — no python equivalent.)
    private val proveCalls = AtomicInteger(0)
    @Volatile private var lastRequestData: ByteArray? = null
    private val requestNextEmitCount = AtomicInteger(0)
    private val hmuRequestsSent = AtomicInteger(0)
    private val hashmapUpdatesReceived = AtomicInteger(0)

    // Test-only: when false, receivePart() does NOT auto-issue its follow-up
    // requestNext() on a window drain. Mirrors the reference harness shadowing
    // `receiver.request_next = lambda: None` during a part-feed so the feed only
    // POSITIONS the consecutive pointer and the explicitly-driven requestNext()
    // afterwards is the one observed. Default true = production behaviour.
    @Volatile private var autoRequestNext: Boolean = true

    /**
     * Initialize resource for sending.
     * Matches Python RNS Resource.__init__() protocol.
     */
    private fun initializeForSending(data: ByteArray, metadata: ByteArray?, autoCompress: Boolean) {
        uncompressedData = data
        totalSize = data.size
        uncompressedSize = data.size

        // Handle metadata. Mirrors python Resource.__init__ (Resource.py:260-268):
        //   packed_metadata = umsgpack.packb(metadata)
        //   self.metadata   = struct.pack(">I", len(packed_metadata))[1:] + packed_metadata
        //   data            = self.metadata + resource_data
        // i.e. the metadata is first msgpack-packed (a `bytes` value packs to bin
        // format: 0xC4 + len + body for <=255 bytes), THEN prefixed with a 3-byte
        // big-endian length of the PACKED block. total_size counts the whole
        // 3 + len(packed) metadata block. A previous build prepended the raw
        // metadata without the msgpack wrapper, growing total_size by only
        // 3 + len(metadata) instead of 3 + len(umsgpack.packb(metadata)).
        var dataWithMetadata = data
        if (metadata != null && metadata.size <= ResourceConstants.METADATA_MAX_SIZE) {
            this.metadata = metadata
            this.hasMetadata = true
            val packedMetadata = msgpackPackBinary(metadata)
            val metaSize = packedMetadata.size
            val metaPrefix = byteArrayOf(
                ((metaSize shr 16) and 0xFF).toByte(),
                ((metaSize shr 8) and 0xFF).toByte(),
                (metaSize and 0xFF).toByte()
            )
            dataWithMetadata = metaPrefix + packedMetadata + data
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

        // Derive the part count from the advertised TRANSFER SIZE and this
        // receiver's OWN per-part SDU — NOT the advertised n field. Mirrors
        // python `Resource.accept` (Resource.py:187):
        //   resource.total_parts = int(math.ceil(resource.size/float(resource.sdu)))
        // The advertisement also carries n = len(parts) (Resource.py:301) but
        // accept never reads it; trusting a tampered n would build a mis-sized
        // parts list and desynchronise indexing.
        val totalParts = ceil(size.toDouble() / sdu).toInt()
        parts = arrayOfNulls(totalParts)
        hashmap = arrayOfNulls(totalParts)

        // Parse hashmap from advertisement
        hashmapRaw = adv.hashmap
        updateHashmap(0, hashmapRaw)

        // Inherit the previous transfer's final window on this link, mirroring
        // python `Resource.accept` (Resource.py:216-218):
        //   previous_window = resource.link.get_last_resource_window()
        //   if previous_window: resource.window = previous_window
        // Link.resourceConcluded records the window of each completed inbound
        // transfer; a second transfer starts at that grown window rather than
        // the WINDOW=4 default, preserving multi-resource throughput.
        link.getLastResourceWindow()?.let { window = it }

        lastActivity = System.currentTimeMillis()
        startedTransferring = lastActivity

        // Register with link
        link.registerIncomingResource(this)

        log("Resource ${hash.toHexString()} accepted: $size bytes in ${parts.size} parts")
    }

    /**
     * Advertise this resource to the receiver.
     */
    fun advertise() {
        if (status != ResourceConstants.QUEUED) return

        // One-outgoing-resource-at-a-time gate. Mirrors python
        // `Resource.__advertise_job` (Resource.py:520-524):
        //   while not self.link.ready_for_new_resource():
        //       self.status = Resource.QUEUED
        //       sleep(0.25)
        // If the link already has an outgoing resource in flight, spin in QUEUED
        // (on a daemon thread, like python's __advertise_job) until it is ready,
        // then advertise. The common idle case (ready immediately) advertises
        // synchronously, so this adds no thread/latency for the normal path.
        if (!link.readyForNewResource()) {
            thread(isDaemon = true, name = "resource-advertise-${hash.toHexString().take(8)}") {
                while (status == ResourceConstants.QUEUED && !link.readyForNewResource()) {
                    try {
                        Thread.sleep(250)
                    } catch (e: InterruptedException) {
                        return@thread
                    }
                }
                if (status == ResourceConstants.QUEUED) {
                    doAdvertise()
                }
            }
            return
        }

        doAdvertise()
    }

    private fun doAdvertise() {
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

                // Auto-follow-up request, suppressible under test (see autoRequestNext)
                // to mirror the reference shadowing request_next during a part-feed.
                if (autoRequestNext) requestNext()
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
            // Count the false->true transition of waiting_for_hmu — this is the
            // hashmap-update request the receiver issues over a >74-part transfer.
            // The reference harness counts the same event by wrapping the
            // instance request_next (wire_tcp.py on_resource_started). requestNext
            // early-returns while waitingForHmu, so reaching here always means a
            // false->true transition.
            hmuRequestsSent.incrementAndGet()
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
            // Record the genuine request plaintext + count this emit. Mirrors the
            // reference harness capturing each outbound RESOURCE_REQ packet's
            // .data (wire_tcp.py cmd_wire_resource_request_next_content). Captured
            // only at the actual send block, so the waitingForHmu early-return
            // above does not bump the count.
            lastRequestData = reqDataBytes
            requestNextEmitCount.incrementAndGet()
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

        // Extract requested hashes (after pad + resource hash). Mirrors python
        // `Resource.request` (Resource.py:998): requested_hashes =
        // request_data[pad+HASHLENGTH//8:]. An exhausted HMU-only request carries
        // NO requested hashes (data.size == hashStart) but MUST still reach the
        // hashmap-update / sequencing-gate branch below — so only a strictly
        // SHORTER (malformed) request is dropped here, not the empty-hashes case.
        // (A previous `<=` guard dropped every HMU-only request, skipping the
        // 74-alignment sequencing gate entirely.)
        val hashStart = pad + ResourceConstants.RESOURCE_HASH_LEN
        if (data.size < hashStart) return

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

        // Find and send requested parts. Mirrors python `Resource.request`
        // (Resource.py:1009-1014):
        //   if not part.sent: part.send(); self.sent_parts += 1
        //   else: part.resend()
        // sendPart() already tracks first-send vs resend via sentPartsSet and
        // increments sentParts exactly once per unique part — so this loop must
        // NOT separately bump sentParts / sentPartsSet. A previous build did both
        // (sendPart AND an inline increment), double-counting sent_parts: an
        // 8-part serve reported sent_parts=16 and reached AWAITING_PROOF after
        // only half the parts were actually sent.
        val searchScope = parts.slice(searchStart until minOf(searchEnd, parts.size))
        for ((index, part) in searchScope.withIndex()) {
            if (part != null) {
                val partMapHash = getMapHash(part)
                if (mapHashes.any { it.contentEquals(partMapHash) }) {
                    val actualIndex = searchStart + index
                    sendPart(actualIndex, part)
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

            // Count each accepted hashmap-update segment. Mirrors the reference
            // harness wrapping the instance hashmap_update_packet
            // (wire_tcp.py on_resource_started). Counted in the packet handler,
            // NOT in the private hashmapUpdate(), so the inject_hashmap_update
            // injector (which drives hashmapUpdate directly) is unaffected.
            hashmapUpdatesReceived.incrementAndGet()
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
        // Count every prove() entry. The reference harness wraps the instance
        // prove (wire_tcp.py cmd_wire_resource_receiver_proof_count /
        // cmd_wire_inject_corrupt_assembled_resource) to assert exactly one
        // proof per completed transfer and zero on a CORRUPT one.
        proveCalls.incrementAndGet()
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
                    // Mirrors python `Resource.assemble` (Resource.py:676): a None
                    // part makes `b"".join(self.parts)` raise, landing in the
                    // except branch that sets CORRUPT (Resource.py:721).
                    markCorrupt("Assembly failed: missing parts")
                    return
                }
                output.write(part)
            }

            val encryptedStream = output.toByteArray()

            // Decrypt the stream if encrypted. A failed Token authentication is an
            // integrity failure: mirrors python where link.decrypt raising lands
            // in assemble's except->CORRUPT branch (Resource.py:715/721), NOT a
            // clean FAILED. A corrupted-in-flight part typically breaks the
            // Token HMAC, so this is the path the corrupt-assembled injector hits.
            var decryptedData = if (encrypted) {
                link.decrypt(encryptedStream) ?: run {
                    markCorrupt("Assembly failed: decryption/authentication error")
                    return
                }
            } else {
                encryptedStream
            }

            // Strip off the random prefix (first RANDOM_HASH_SIZE bytes)
            if (decryptedData.size < ResourceConstants.RANDOM_HASH_SIZE) {
                markCorrupt("Assembly failed: data too short after decryption")
                return
            }
            decryptedData = decryptedData.copyOfRange(ResourceConstants.RANDOM_HASH_SIZE, decryptedData.size)

            // Decompress if needed, bounded by maxDecompressedSize. Mirrors python
            // `Resource.assemble` (Resource.py:685-690):
            //   self.data = decompressor.decompress(data, max_length=self.max_decompressed_size)
            //   if not decompressor.eof: self.status = CORRUPT; self.cancel(); return
            // A bz2 stream that inflates past the bound is a decompression bomb;
            // the receiver marks the transfer CORRUPT rather than exhausting memory.
            var assembled = if (compressed) {
                decompressBounded(decryptedData, maxDecompressedSize) ?: run {
                    markCorrupt("Decompressed resource exceeded maximum decompressed size")
                    cancel()
                    return
                }
            } else {
                decryptedData
            }

            // Verify hash matches
            val calculatedHash = Hashes.fullHash(assembled + randomHash)
            if (!calculatedHash.contentEquals(hash)) {
                markCorrupt("Assembly failed: hash mismatch")
                return
            }

            // Store the full assembled data (with metadata) for proof calculation
            // This matches Python where self.data in prove() includes metadata
            val dataForProof = assembled

            // Strip metadata if present. The block is [3-byte BE len(packed)] +
            // umsgpack.packb(metadata) (Resource.py:266/696-704); recover the raw
            // metadata by msgpack-unpacking the packed slice.
            if (hasMetadata && assembled.size > 3) {
                val metaSize = ((assembled[0].toInt() and 0xFF) shl 16) or
                              ((assembled[1].toInt() and 0xFF) shl 8) or
                              (assembled[2].toInt() and 0xFF)
                if (metaSize > 0 && metaSize + 3 <= assembled.size) {
                    val packedMetadata = assembled.copyOfRange(3, 3 + metaSize)
                    metadata = runCatching { msgpackUnpackBinary(packedMetadata) }.getOrNull() ?: packedMetadata
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
            // Mirrors python `Resource.assemble`'s except branch (Resource.py:
            // 719-721): any error during reassembly marks the transfer CORRUPT,
            // not FAILED.
            markCorrupt("Assembly error: ${e.message}")
        }
    }

    /**
     * Mark this inbound transfer CORRUPT and conclude it on the link, mirroring
     * python `Resource.assemble`'s CORRUPT paths (Resource.py:689/715/721) which
     * set `status = CORRUPT` and fall through to `link.resource_concluded(self)`.
     * No proof is sent on a CORRUPT verdict.
     */
    private fun markCorrupt(reason: String) {
        status = ResourceConstants.CORRUPT
        log(reason)
        stopWatchdog()
        link.resourceConcluded(this)
        callbacks.failed?.invoke(this)
    }

    /**
     * Cancel this resource transfer.
     *
     * Mirrors python `RNS.Resource.cancel` (Resource.py:1079-1108): set
     * `status = FAILED`, remove from the link's incoming/outgoing list
     * via `link.resourceConcluded`, and notify any registered failure
     * callback. Without the `callbacks.failed?.invoke` fire here, the
     * watchdog timeout path would silently drop the registration but
     * leave the message-level state stuck at SENDING/TRANSFERRING.
     * Without `link.resourceConcluded`, the hash would remain in
     * `incomingResources` for the lifetime of the link — every
     * subsequent retransmit of the same `RESOURCE_ADV` would be dropped
     * by the dedup guard inside `Resource.accept`, removing the
     * recovery path that existed pre-dedup.
     */
    fun cancel() {
        // Idempotency guard. Mirrors python `Resource.py:1090`'s
        // `elif self.status < Resource.COMPLETE:` check — once a resource
        // has reached a terminal state (COMPLETE / FAILED / CORRUPT,
        // status >= COMPLETE = 0x06), a second cancel() is a no-op.
        // Necessary now that cancel() fires `callbacks.failed?.invoke`:
        // without this guard, a double-cancel from application code +
        // watchdog timeout would deliver the failed callback twice.
        if (status >= ResourceConstants.COMPLETE) {
            return
        }
        stopWatchdog()
        status = ResourceConstants.FAILED
        // python Resource.py:1087-1094 — when the INITIATOR cancels a still-ACTIVE
        // transfer it sends a RESOURCE_ICL packet carrying the resource hash so the
        // receiver tears its inbound resource down too. Without this the receiver's
        // inbound Resource is never told and lingers in TRANSFERRING. The receiver
        // (processResourceIcl) decrypts the data and reads the first 16 bytes as the
        // resource hash, so encrypt the hash to the link exactly like advertise().
        if (initiator && link.status == LinkConstants.ACTIVE) {
            try {
                val cancelPacket =
                    Packet.createRaw(
                        destinationHash = link.linkId,
                        data = link.encrypt(hash),
                        packetType = PacketType.DATA,
                        destinationType = DestinationType.LINK,
                        context = PacketContext.RESOURCE_ICL,
                        mtu = link.mtu,
                    )
                cancelPacket.send()
            } catch (e: Exception) {
                log("Could not send resource cancel packet: ${e.message}")
            }
        }
        link.resourceConcluded(this)
        callbacks.failed?.invoke(this)
        log("Resource ${hash.toHexString()} cancelled")
    }

    /**
     * Get the received/assembled data (without metadata).
     */
    val data: ByteArray?
        get() = assembledData

    /**
     * Get extracted metadata bytes, if present.
     */
    val metadataBytes: ByteArray?
        get() = metadata

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
     * Bounded BZ2 decompression. Returns null if the decompressed output would
     * exceed [maxLen] bytes — the decompression-bomb guard. Mirrors python
     * `BZ2Decompressor.decompress(data, max_length=self.max_decompressed_size)`
     * + the `if not decompressor.eof` over-bound check (Resource.py:687-690):
     * a stream that has not reached EOF by the bound is a bomb and is rejected.
     */
    private fun decompressBounded(data: ByteArray, maxLen: Int): ByteArray? {
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        BZip2CompressorInputStream(input).use { bz2 ->
            val buffer = ByteArray(8192)
            var total = 0L
            var len: Int
            while (bz2.read(buffer).also { len = it } != -1) {
                total += len
                if (total > maxLen) {
                    return null
                }
                output.write(buffer, 0, len)
            }
        }
        return output.toByteArray()
    }

    /**
     * MessagePack-pack a binary value (msgpack `bin` format), mirroring python
     * `umsgpack.packb(metadata)` for a `bytes` payload (Resource.py:261).
     */
    private fun msgpackPackBinary(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val packer = org.msgpack.core.MessagePack.newDefaultPacker(out)
        packer.packBinaryHeader(data.size)
        packer.writePayload(data)
        packer.close()
        return out.toByteArray()
    }

    /**
     * MessagePack-unpack a single binary value, the inverse of
     * [msgpackPackBinary] — mirrors python `umsgpack.unpackb(packed_metadata)`.
     */
    private fun msgpackUnpackBinary(data: ByteArray): ByteArray {
        val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)
        val len = unpacker.unpackBinaryHeader()
        val payload = unpacker.readPayload(len)
        unpacker.close()
        return payload
    }

    /**
     * Start watchdog thread for timeout detection.
     */
    private fun startWatchdog() {
        // Test-only suppression (see companion watchdogDisabledForTest): the
        // reference harness disables the watchdog around _build_resource_receiver
        // so an inbound Resource can be inspected synchronously without a
        // timeout-retry cancelling it.
        if (watchdogDisabledForTest) return
        if (watchdogActive) return

        watchdogActive = true
        watchdogThread = thread(name = "resource-watchdog-${hash.toHexString().take(8)}") {
            watchdogJob()
        }
    }

    /**
     * Stop the watchdog thread.
     *
     * Skips the `interrupt()` call when invoked from the watchdog thread
     * itself (via `cancel()`'s call from `watchdogJob`'s retry-exhausted
     * branch). Setting the interrupt flag on the current thread would
     * propagate into any subsequent callback I/O — `callbacks.failed`
     * runs on this same thread, and a TCP send from inside the failed
     * callback uses `ReentrantLock.lockInterruptibly()` which checks
     * the flag on entry and immediately throws, silently aborting the
     * send. Python's watchdog uses a `__watchdog_job_id` flag check
     * rather than thread interruption (Resource.py:560-670), so the
     * equivalent self-targeting issue doesn't exist there.
     */
    private fun stopWatchdog() {
        watchdogActive = false
        val thread = watchdogThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
        }
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
                        // Mirrors python `Resource.py:578, 591, 628, 636, 648,
                        // 667, 690` etc. — every retries-exhausted branch in
                        // python's watchdog calls `self.cancel()`. Calling
                        // cancel() (rather than the previous inline
                        // `status = FAILED; callbacks.failed?.invoke`) ensures
                        // `link.resourceConcluded(this)` runs, which removes
                        // the resource from `incomingResources` so a future
                        // RESOURCE_ADV with the same hash is no longer
                        // dropped by the dedup guard inside
                        // `Resource.accept`. Without this, a single
                        // watchdog-fail leaves the hash registered for the
                        // lifetime of the link, killing the recovery path.
                        log("Resource ${hash.toHexString()} timed out after $retries retries")
                        cancel()
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

    // ===== Conformance test seams =====
    // Each is a thin public wrapper over a private member or method. The
    // conformance-bridge is a separate gradle module and cannot see private/
    // internal state; these exist solely so the bridge can read back or drive
    // the Resource state machine the way the reference harness reads/drives the
    // python instance attributes directly. None changes protocol behaviour.

    /** private expectedProof — full_hash(data+hash) (Resource.kt:expectedProof). */
    fun expectedProofForTest(): ByteArray? = expectedProof

    /** private per-part SDU captured at construction (Resource.py:338). */
    fun sduForTest(): Int = sdu

    /** private window / windowMin / windowMax (flow-control state). */
    fun windowForTest(): Int = window
    fun windowMinForTest(): Int = windowMin
    fun windowMaxForTest(): Int = windowMax

    /** private hashmapHeight — number of loaded hashmap slots. */
    fun hashmapHeightForTest(): Int = hashmapHeight

    /** private receivedCount — number of stored parts. */
    fun receivedCountForTest(): Int = receivedCount

    /** private consecutiveCompletedHeight — the in-order pointer. */
    fun consecutiveCompletedHeightForTest(): Int = consecutiveCompletedHeight

    /** private waitingForHmu flag. */
    fun waitingForHmuForTest(): Boolean = waitingForHmu

    /** private sentParts (sender). */
    fun sentPartsForTest(): Int = sentParts

    /** sorted copy of the private sentPartsSet (sender). */
    fun sentPartIndicesForTest(): List<Int> = sentPartsSet.toList().sorted()

    /** private receiverMinConsecutiveHeight (sender search-scope anchor). */
    fun receiverMinConsecutiveHeightForTest(): Int = receiverMinConsecutiveHeight

    /** copy of the parsed private hashmap[] array (preserves nulls). */
    fun hashmapEntriesForTest(): List<ByteArray?> = hashmap.toList()

    /** private maxDecompressedSize / autoCompressLimit (bomb-guard ceiling). */
    fun maxDecompressedSizeForTest(): Int = maxDecompressedSize
    fun autoCompressLimitForTest(): Int = autoCompressLimit
    /** Lower the per-resource decompression bound (listener bomb-guard hook). */
    fun setMaxDecompressedSizeForTest(value: Int) { maxDecompressedSize = value }

    /** Instrumentation counters (see the fields for what each event is). */
    fun proveCallCountForTest(): Int = proveCalls.get()
    fun lastRequestDataForTest(): ByteArray? = lastRequestData
    fun requestNextEmitCountForTest(): Int = requestNextEmitCount.get()
    fun hmuRequestsSentForTest(): Int = hmuRequestsSent.get()
    fun hashmapUpdatesReceivedForTest(): Int = hashmapUpdatesReceived.get()

    /** Drive the private assemble(). */
    fun assembleForTest() = assemble()

    /** Set parts[index] directly (parts has a private setter). */
    fun setPartForTest(index: Int, data: ByteArray) {
        parts[index] = data
    }

    /** Drive the private hashmapUpdate(segment, bytes). */
    fun hashmapUpdateForTest(segment: Int, bytes: ByteArray) = hashmapUpdate(segment, bytes)

    /** Drive the private requestNext(). */
    fun requestNextForTest() = requestNext()

    /** Suppress/restore receivePart's auto-follow-up requestNext during a feed. */
    fun setAutoRequestNextForTest(enabled: Boolean) { autoRequestNext = enabled }

    /** Prime the sender as if it had just advertised (status TRANSFERRING,
     *  adv_sent set) — mirrors the reference priming a sender before request().*/
    fun primeTransferringForTest() {
        status = ResourceConstants.TRANSFERRING
        advSent = System.currentTimeMillis()
    }

    /** Force the status field (e.g. AWAITING_PROOF for the control proof case). */
    fun setStatusForTest(newStatus: Int) {
        status = newStatus
    }

    override fun toString(): String {
        return "<Resource ${hash.toHexString().take(16)}/${link.linkId.toHexString().take(16)}>"
    }
}
