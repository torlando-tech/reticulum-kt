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
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
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
         * Upper bound (ms) on how long validateProof waits for the next segment
         * of a split transfer to be prepared. The wait runs on the Transport
         * inbound thread under the global jobs lock, so it must not be unbounded
         * (F3.1). Preparation is a background bz2 + encrypt of at most
         * MAX_EFFICIENT_SIZE bytes and is normally finished before the proof
         * arrives; the bound only matters if the segment source cannot be read or
         * the preparation thread dies, in which case the transfer is cancelled
         * rather than the ingest thread stalled forever.
         */
        private const val SEGMENT_WAIT_MS: Long = 15_000

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
         * @param failedCallback Callback when the transfer fails. Installed on
         *   `callbacks.failed` before advertise() spawns the watchdog, so a
         *   failure that fires during that window is propagated (issue #65).
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
            failedCallback: ((Resource) -> Unit)? = null,
            requestId: ByteArray? = null,
            isResponse: Boolean = false,
            timeout: Long? = null
        ): Resource {
            val resource = Resource(link, initiator = true)

            // Install every callback BEFORE advertise() spawns the watchdog.
            // advertise() -> doAdvertise() -> startWatchdog() starts the watchdog
            // thread immediately; if the watchdog fires `failed` before the
            // application installs callbacks.failed (issue #65), the callback is
            // invoked on null (?. no-op) and the failure is never propagated up to
            // LXMessage.state. Installing failedCallback here closes that window.
            callback?.let { resource.callbacks.completed = it }
            progressCallback?.let { resource.callbacks.progress = it }
            failedCallback?.let { resource.callbacks.failed = it }

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
         * @param failedCallback Callback when the transfer fails. Installed on
         *   `callbacks.failed` before startWatchdog(), so a failure that fires
         *   during that window is propagated (issue #65).
         * @return The new Resource instance, or null if invalid
         */
        fun accept(
            advertisement: ResourceAdvertisement,
            link: Link,
            callback: ((Resource) -> Unit)? = null,
            progressCallback: ((Resource) -> Unit)? = null,
            failedCallback: ((Resource) -> Unit)? = null
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

                // Install every callback BEFORE startWatchdog() (below) so a
                // watchdog failure that fires during the accept window is
                // propagated (issue #65).
                callback?.let { resource.callbacks.completed = it }
                progressCallback?.let { resource.callbacks.progress = it }
                failedCallback?.let { resource.callbacks.failed = it }

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
    // Python parity (Resource.py:343): retries_left counts DOWN from max_retries
    // (or max_adv_retries for the ADVERTISED phase, Resource.py:533). The sender
    // watchdog's recovery branches are budgeted by it: ADVERTISED re-sends while
    // retries_left > 0 (Resource.py:576-588) and AWAITING_PROOF cache queries
    // while retries_left > 0 (Resource.py:646-658), cancelling once exhausted.
    // The legacy [retries] counter (counts UP) is kept for the existing
    // watchdog-exhaustion guard and the conformance instrumentation.
    private var retriesLeft: Int = ResourceConstants.MAX_RETRIES

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
    @Volatile private var cancelTransitionHookForTest: (() -> Unit)? = null

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
    /**
     * Size of the wire metadata BLOCK (3-byte big-endian length + msgpack-packed
     * metadata), 0 when there is no metadata. Mirrors python `self.metadata_size`
     * (Resource.py:258-268), which counts the whole prefixed block and is what
     * the first-segment read bound (`MAX_EFFICIENT_SIZE - metadata_size`)
     * subtracts. Distinct from the raw [metadata] payload length.
     */
    private var metadataBlockSize: Int = 0
    /**
     * The auto-compress OPTION as the application passed it (true/false),
     * carried across split segments. Mirrors python `self.auto_compress_option`
     * (Resource.py:369), which each prepared segment re-applies to its own
     * content (Resource.py:773). Not the per-segment [compressed] RESULT:
     * whether a segment actually shrank is decided per segment.
     */
    private var autoCompressOption: Boolean = true
    /**
     * The temporary file backing a split transfer's [inputFile] (python
     * `tempfile.TemporaryFile`, Resource.py:277). Deleted once the transfer
     * reaches a terminal state (final segment concluded or cancel).
     */
    private var tempFile: File? = null
    private var inputFile: java.io.RandomAccessFile? = null
    // @Volatile: written by the background segment-preparation thread and read by
    // validateProof() on the Transport inbound thread (a cross-thread handoff by
    // polling). Volatile gives the publication the happens-before visibility the
    // code relies on, matching the @Volatile idiom used for the same pattern in
    // Link.kt / Transport.kt.
    @Volatile
    private var preparingNextSegment: Boolean = false
    @Volatile
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
    @Volatile private var lastProofPayload: ByteArray? = null
    /** link_id of the link the last proof packet was bound to, or null if the
     *  packet carried no link reference (packet.link == null). Python's
     *  RNS.Packet(link, ...) always sets packet.destination = link; the kotlin
     *  port builds the proof via Packet.createRaw which leaves packet.link null.
     *  Recording the link_id (not a boolean) lets the bridge verify the proof is
     *  bound to the transfer's actual link, not merely to some link.
     *  Observation-only. */
    @Volatile private var lastProofLinkId: ByteArray? = null
    @Volatile private var lastRequestData: ByteArray? = null
    private val requestNextEmitCount = AtomicInteger(0)
    private val hmuRequestsSent = AtomicInteger(0)
    private val hashmapUpdatesReceived = AtomicInteger(0)
    // Counts the sender watchdog's AWAITING_PROOF cache queries (the recovery
    // action at python Resource.py:651-657). Instrumented so a unit test can
    // assert the recovery ran without needing a live network round-trip.
    private val proofCacheQueries = AtomicInteger(0)

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
    private fun initializeForSending(
        data: ByteArray,
        metadata: ByteArray?,
        autoCompress: Boolean,
        segmentContinuation: Boolean = false,
        totalPayloadSize: Int? = null
    ) {
        uncompressedData = data
        // Total transfer size. The ROOT resource is total_size = raw_payload +
        // metadata_block (python Resource.py:283, total_size = data_size +
        // metadata_size). A SPLIT CONTINUATION segment carries the transfer's full
        // RAW payload size (the spilled file length) so the auto-compress decision
        // (python Resource.py:390, data_size = full payload) and the advertisement's
        // data_size (Resource.py:1300, adv.d) match the reference for every segment.
        totalSize = totalPayloadSize ?: data.size
        uncompressedSize = data.size
        autoCompressOption = autoCompress

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
            metadataBlockSize = 3 + metaSize
        }

        // Decide the split before touching the data, from the TOTAL payload size
        // (mirrors python Resource.py:295-301, computed from total_size before any
        // read). A split transfer is driven segment by segment: each segment is an
        // independent, self-contained transfer (own random_hash, compression
        // decision, encryption, hash, expected proof and part map) announced in
        // turn, exactly as python builds one Resource per segment (Resource.py:
        // 296-329 for the file read, __prepare_next_segment at 765-779 for the
        // follow-ups).
        if (totalSize > ResourceConstants.MAX_EFFICIENT_SIZE) {
            totalSegments = ((totalSize - 1) / ResourceConstants.MAX_EFFICIENT_SIZE) + 1
            split = true
        }

        // SPLIT path (F3.1 parity): spill the payload to a temporary file, keep
        // it open as [inputFile] (python `self.input_file`, Resource.py:274-314),
        // and process ONLY the first segment in memory. prepareNextSegment reads
        // the following chunks from the file when the previous segment's proof
        // arrives (validateProof -> prepareNextSegment). Without the spill, an
        // in-memory payload above MAX_EFFICIENT_SIZE marked split had no file to
        // read segment 2 from: the proof validator waited forever for a segment
        // that could never be produced (the remote DoS), and a port that merely
        // bounds that wait (cancel after a deadline) still never CONCLUDES the
        // transfer.
        //
        // First-segment content is [metadata block] + the first
        // (MAX_EFFICIENT_SIZE - metadataBlockSize) RAW payload bytes; segment N>1
        // content is the raw chunk at offset (N-2)*MAX_EFFICIENT_SIZE. Note the
        // segment bounds are expressed in RAW payload bytes, and the metadata
        // block is counted inside the first segment's MAX_EFFICIENT_SIZE budget
        // (python first_read_size = MAX_EFFICIENT_SIZE - self.metadata_size,
        // Resource.py:303-313). Only the ROOT resource (segment 1, non-continuation)
        // spills and truncates; a continuation segment is ALREADY a single chunk
        // read from the parent's file by prepareNextSegment, so it must not
        // re-spill or re-truncate (guard: !segmentContinuation).
        if (!segmentContinuation && split && segmentIndex == 1) {
            // The first segment's RAW read is MAX_EFFICIENT_SIZE - metadataBlockSize
            // (python first_read_size, Resource.py:303). A metadata block that fills
            // or exceeds the whole segment budget makes that read size <= 0: the
            // reference then reads with a negative size and CPython raises
            // ValueError, so the reference does not complete this degenerate
            // transfer either. Fail fast with a clear error before touching the
            // file instead of a mid-init copyOfRange exception.
            if (metadataBlockSize >= ResourceConstants.MAX_EFFICIENT_SIZE) {
                throw IllegalArgumentException(
                    "metadata block (${metadataBlockSize} bytes) leaves no room " +
                        "for the first segment payload (budget ${ResourceConstants.MAX_EFFICIENT_SIZE} bytes)"
                )
            }
            val temp = File.createTempFile("rns-res-${System.nanoTime()}", ".tmp")
            temp.deleteOnExit()
            temp.outputStream().use { it.write(data) }
            tempFile = temp
            inputFile = RandomAccessFile(temp, "rw")

            // First segment raw read: MAX_EFFICIENT_SIZE - metadataBlockSize.
            // (python first_read_size / segment_read_size, Resource.py:303-313)
            val firstChunk = ResourceConstants.MAX_EFFICIENT_SIZE - metadataBlockSize
            val readLen = min(firstChunk, data.size)
            val firstSegmentData = data.copyOfRange(0, readLen)

            // Rebuild the metadata-prefixed content for this segment only: the
            // metadata block (3-byte BE length + msgpack) + this segment's raw
            // chunk. Non-first segments carry no metadata block.
            dataWithMetadata = if (metadataBlockSize > 0 && metadata != null) {
                val packedMetadata = msgpackPackBinary(metadata)
                val metaPrefix = byteArrayOf(
                    ((packedMetadata.size shr 16) and 0xFF).toByte(),
                    ((packedMetadata.size shr 8) and 0xFF).toByte(),
                    (packedMetadata.size and 0xFF).toByte()
                )
                metaPrefix + packedMetadata + firstSegmentData
            } else {
                firstSegmentData
            }
            uncompressedData = dataWithMetadata
            uncompressedSize = dataWithMetadata.size
        }

        // Compress if requested and within limits. Mirrors python
        // Resource.py:389-418: the auto-compress decision is made against the
        // TOTAL payload size (data_size <= auto_compress_limit), applied per
        // segment, and a segment is marked compressed only if compression
        // actually shrank its own content.
        val compressedResult = if (autoCompress && totalSize <= ResourceConstants.AUTO_COMPRESS_MAX_SIZE) {
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
        log("initializeForSending: prefixedData=${prefixedData.size} bytes, encryptedData=${encryptedData.size} bytes (seg $segmentIndex/$totalSegments)")

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

        // Calculate resource hash from the segment's UNCOMPRESSED content (with
        // metadata for segment 1) + randomHash. Mirrors python Resource.py:440-443
        // where hash/expected_proof are computed over THIS segment's `data`
        // (the compressed/uncompressed segment content), and original_hash chains
        // from the previous segment (Resource.py:445-448, 772).
        hash = Hashes.fullHash(dataWithMetadata + randomHash)
        // The ROOT resource's original_hash is its own hash (the first-segment hash
        // the whole transfer is keyed by, which continuations chain from, python
        // Resource.py:445-446). A continuation segment instead inherits the parent's
        // original_hash (python Resource.py:772, set by prepareNextSegment), so it
        // must NOT re-derive it from its own hash here.
        if (!segmentContinuation) {
            originalHash = hash.copyOf()
        }

        // Calculate expected proof: full_hash(segment content + hash)
        expectedProof = Hashes.fullHash(dataWithMetadata + hash)

        status = ResourceConstants.QUEUED
        log("Resource ${hash.toHexString()} created: $size bytes in ${parts.size} parts (compressed=$compressed, encrypted=$encrypted, seg $segmentIndex/$totalSegments)")
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

    /**
     * Build and send a RESOURCE_ADV packet for this resource.
     *
     * Used by doAdvertise() for the initial advertisement AND by the sender
     * watchdog's ADVERTISED-timeout recovery (python Resource.py:584-587: the
     * watchdog rebuilds the advertisement packet and re-sends it). Keeping the
     * build+send in one place means the re-send is byte-identical to the
     * original except for the link's per-send encryption non-determinism.
     *
     * @return true if the packet was sent, false otherwise (the watchdog treats
     *   a send failure as a cancel, mirroring python's except-branch).
     */
    private fun sendAdvertisement(): Boolean {
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
        packet.send()
        // RESOURCE_ADV packets are in the RESOURCE..RESOURCE_RCL context range,
        // which the Transport receipt guard skips, so packet.send() returns a
        // null receipt even on success. Use packet.sent (set by send() on
        // success) as the authoritative success signal.
        val ok = packet.sent
        log("  send result: sent=${packet.sent}")
        if (ok) {
            lastActivity = System.currentTimeMillis()
            advSent = lastActivity
        }
        return ok
    }

    private fun doAdvertise() {
        if (status != ResourceConstants.QUEUED) return

        // Register with link
        link.registerOutgoingResource(this)

        status = ResourceConstants.ADVERTISED
        // Python __advertise_job (Resource.py:533) resets the ADVERTISED-phase
        // retry budget to max_adv_retries once the advertisement is out. The
        // watchdog's ADVERTISED timeout re-sends the advertisement while this
        // budget has room and cancels when it is exhausted (Resource.py:576-591).
        retriesLeft = ResourceConstants.MAX_ADV_RETRIES

        sendAdvertisement()

        // Pre-prepare the next segment of a split transfer in the background
        // (python `advertise()`, Resource.py:528-530: a daemon thread runs
        // __prepare_next_segment whenever segment_index < total_segments), so
        // the segment is built by the time this one's proof arrives instead of
        // the proof validator waiting for it.
        if (split && segmentIndex < totalSegments) {
            prepareNextSegment()
        }

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
            // Python receive_part resets the retry budget (Resource.py:833).
            retriesLeft = ResourceConstants.MAX_RETRIES

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
        // Python request() resets the retry budget (Resource.py:992).
        retriesLeft = ResourceConstants.MAX_RETRIES

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
        // Python hashmap_update_packet resets the retry budget (Resource.py:486).
        retriesLeft = ResourceConstants.MAX_RETRIES

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
     * Re-query the receiver's proof from the local network cache.
     *
     * Sender watchdog AWAITING_PROOF-timeout recovery (python Resource.py:651-657):
     * the sender finished sending all parts but never heard back its resource
     * proof. The recovery rebuilds the expected proof packet (hash + expected
     * proof, PROOF/RESOURCE_PRF) and calls `Transport.cache_request` on its hash
     * so a cached copy is replayed toward us. This is what lets a sender that
     * missed a proof recover instead of just cancelling.
     *
     * The proof packet is packed (not sent) only so its packet_hash is computed
     * for the cache lookup - exactly like python's `expected_proof_packet.pack()`.
     */
    private fun queryProofFromCache() {
        val expected = expectedProof
        if (expected == null) {
            log("AWAITING_PROOF cache query skipped: no expected proof")
            return
        }
        // python Resource.py:653-656: expected_data = hash + expected_proof,
        // build a PROOF/RESOURCE_PRF packet and pack it to derive its hash.
        val proofPacket = Packet.createRaw(
            destinationHash = link.linkId,
            data = hash + expected,
            packetType = PacketType.PROOF,
            destinationType = DestinationType.LINK,
            context = PacketContext.RESOURCE_PRF,
            mtu = link.mtu
        )
        proofPacket.pack()

        // The proof travels over the link, so the cache query replays on the
        // link's attached interface (or its next-hop path interface).
        val iface = resolveProofRequestInterface()
        if (iface != null) {
            Transport.cacheRequest(proofPacket.packetHash, iface)
        }
        proofCacheQueries.incrementAndGet()
        // python Resource.py:657: reset the AWAITING_PROOF timeout anchor.
        lastPartSent = System.currentTimeMillis()
    }

    /**
     * Resolve the interface the proof cache query should replay on.
     *
     * The proof is sent to the receiver over the link, so the natural replay
     * interface is the link's attached interface (python's `cache_request`
     * `destination` arg is `self.link`). Falls back to the next-hop path
     * interface when the link carries no attached interface hash.
     */
    private fun resolveProofRequestInterface(): InterfaceRef? {
        val attached = link.attachedInterfaceHash
        if (attached != null) {
            Transport.findInterfaceByHashForTest(attached)?.let { return it }
        }
        return Transport.nextHopInterface(link.linkId)
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
            // Observation-only: record the unencrypted proof payload (the value
            // the packet carries and, when #97 lands, the value cached). Lets the
            // conformance bridge assert the proof packet landed in the transport
            // cache by its payload without reconstructing it. NOT the fix — the
            // fix is the separate Transport.cache(...) call (python Resource.py:759).
            lastProofPayload = proofPayload.copyOf()

            // Create proof packet - NOT encrypted (matches Python: resource proofs are not encrypted)
            val packet = Packet.createRaw(
                destinationHash = link.linkId,
                data = proofPayload,
                packetType = PacketType.PROOF,
                destinationType = DestinationType.LINK,
                context = PacketContext.RESOURCE_PRF,
                mtu = link.mtu
            )

            // Bind the proof to the transfer's link so the Transport routes it to
            // that link's own interface. Python's RNS.Packet(link, ...) sets
            // packet.destination = link (Packet.py:136), and the Transport's
            // LINK-packet interface filter (Transport.py:1031-1035) plus
            // in-process loopback read that reference to send the proof only on
            // the link's own interface. Packet.createRaw leaves packet.link null,
            // which falls through to broadcast-on-all-interfaces
            // (Transport.kt:3368) in a multi-interface production setup. This
            // matches the Link.kt idiom (packet.link = this) used by the port's
            // other link-bound packet sends.
            packet.link = link
            // Observation-only: which link (by link_id) is the proof bound to at
            // send time, or null if the packet carries no link reference. Recording
            // the link_id (not a boolean) lets the bridge assert the proof is bound
            // to the transfer's actual link, not merely to some link.
            lastProofLinkId = packet.link?.linkId?.copyOf()

            packet.send()
            // python Resource.py:759: cache the proof packet (force_cache=True)
            // so the sender's AWAITING_PROOF recovery (cache_request) can find
            // it. Without this, queryProofFromCache is a no-op - no production
            // path stores proofs in the cache, so the sender cannot recover.
            Transport.cache(packet, forceCache = true)
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

                // Wait for the next segment, but BOUNDED. validateProof runs on the
                // Transport inbound thread under the global jobs lock, so an
                // unbounded `while (nextSegment == null) Thread.sleep(50)` here stalls
                // inbound processing for every interface on the node until a segment
                // appears. An in-memory resource created with create(data) for a
                // payload above MAX_EFFICIENT_SIZE marks itself split (totalSegments >
                // 1) but prepareNextSegment() has no input file to read from, so
                // nextSegment can never be set and the loop spins forever - reachable
                // by a peer simply completing an ordinary, valid transfer. Wait at
                // most SEGMENT_WAIT_MS; if no segment is produced (the source can't be
                // read, or the preparation thread died and cleared its flag), cancel
                // the transfer rather than hang the ingest thread.
                val segmentDeadline = System.currentTimeMillis() + SEGMENT_WAIT_MS
                while (nextSegment == null &&
                    preparingNextSegment &&
                    System.currentTimeMillis() < segmentDeadline
                ) {
                    Thread.sleep(50)
                }

                val next = nextSegment
                if (next == null) {
                    log("Could not prepare segment ${segmentIndex + 1}/$totalSegments; cancelling transfer")
                    cancel()
                    return false
                }

                // Advertise the next segment
                next.advertise()

                // Clean up this segment's data
                uncompressedData = null
                compressedData = null
                assembledData = null
                parts = arrayOf()
            } else {
                // All segments complete, invoke callback
                callbacks.completed?.invoke(this)

                // The transfer's input file (and backing temp file) is no longer
                // needed: the final segment's proof validated, so no further
                // segment will be read from it. Mirrors python closing
                // input_file on the final segment (Resource.py:796-805).
                closeInputFile()
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
                    // Clear the flag so the bounded wait in validateProof() exits
                    // immediately instead of running the full 15 s deadline.
                    preparingNextSegment = false
                    return@thread
                }

                // Segment N's RAW read window (python Resource.py:299-313), where N
                // is the segment being PREPARED = segmentIndex + 1:
                //   first_read_size = MAX_EFFICIENT_SIZE - metadata_size
                //   segment 1:        seek 0,                       read first_read_size
                //   segment N>=2:     seek first_read_size + (N-2)*MAX, read MAX
                // So the segment we are preparing (N = segmentIndex+1, hence N>=2)
                // starts at first_read_size + (segmentIndex-1)*MAX. Note
                // metadata_size is the WIRE block size (3-byte length + packed),
                // i.e. [metadataBlockSize], NOT the raw metadata payload length.
                val firstSegmentSize = ResourceConstants.MAX_EFFICIENT_SIZE - metadataBlockSize
                val dataStart = firstSegmentSize.toLong() +
                    (segmentIndex - 1L) * ResourceConstants.MAX_EFFICIENT_SIZE
                val readSize = min(
                    ResourceConstants.MAX_EFFICIENT_SIZE.toLong(),
                    file.length() - dataStart
                ).toInt()

                // Read next segment data
                file.seek(dataStart)
                val segmentData = ByteArray(readSize)
                file.readFully(segmentData)

                // Create the next segment resource (continuation: no metadata
                // block of its own, carries the transfer's full total size and the
                // root's original_hash). Mirrors python __prepare_next_segment
                // (Resource.py:765-779): a fresh Resource on the same input file at
                // segment_index+1, auto_compress = the transfer's auto_compress_option
                // (so the segment re-runs its own compression decision), original_hash
                // and request/response context carried from the parent.
                nextSegment = Resource(link, initiator = true).apply {
                    this.callbacks.completed = this@Resource.callbacks.completed
                    this.callbacks.progress = this@Resource.callbacks.progress
                    this.callbacks.failed = this@Resource.callbacks.failed
                    this.requestId = this@Resource.requestId
                    this.isResponse = this@Resource.isResponse

                    initializeForSending(
                        data = segmentData,
                        metadata = null,
                        autoCompress = this@Resource.autoCompressOption,
                        segmentContinuation = true,
                        totalPayloadSize = this@Resource.totalSize
                    )

                    // Update segment tracking
                    this.segmentIndex = this@Resource.segmentIndex + 1
                    this.totalSegments = this@Resource.totalSegments
                    this.split = true
                    // python continuation segments keep has_metadata True (via
                    // sent_metadata_size, Resource.py:268-269/773) so the
                    // advertisement's x flag matches the reference for every
                    // segment; their content carries no metadata block, and the
                    // receiver only strips at segment_index == 1 (Resource.py:697).
                    this.hasMetadata = this@Resource.hasMetadata
                    this.originalHash = this@Resource.originalHash
                    this.metadataBlockSize = this@Resource.metadataBlockSize
                    this.inputFile = this@Resource.inputFile
                    this.tempFile = this@Resource.tempFile
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

            // Strip metadata if present, but ONLY for segment 1. Mirrors python
            // `Resource.assemble` (Resource.py:697):
            //   if self.has_metadata and self.segment_index == 1:
            // Continuation segments (segment_index > 1) carry no metadata block in
            // their content (python writes the metadata file once, from segment 1,
            // and each later segment's assembled stream is raw data), so stripping
            // on them would eat real payload bytes. The block is [3-byte BE
            // len(packed)] + umsgpack.packb(metadata) (Resource.py:266/696-704);
            // recover the raw metadata by msgpack-unpacking the packed slice.
            if (hasMetadata && segmentIndex == 1 && assembled.size > 3) {
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
    /**
     * Release the split-transfer input file and delete its backing temp file.
     * Idempotent and safe to call from any terminal path (final-segment proof
     * or cancel). The delete is best-effort: the file was also registered with
     * deleteOnExit as a last resort if the process exits before this runs.
     */
    private fun closeInputFile() {
        try {
            inputFile?.close()
        } catch (e: Exception) {
            log("Error closing resource input file: ${e.message}")
        }
        inputFile = null
        val file = tempFile
        if (file != null) {
            tempFile = null
            if (!file.delete()) {
                log("Could not delete split-transfer temp file ${file.name}")
            }
        }
    }

    fun cancel() {
        // Idempotency guard. Mirrors python `Resource.py:1090`'s
        // `elif self.status < Resource.COMPLETE:` check — once a resource
        // has reached a terminal state (COMPLETE / FAILED / CORRUPT,
        // status >= COMPLETE = 0x06), a second cancel() is a no-op.
        // Necessary now that cancel() fires `callbacks.failed?.invoke`:
        // without this guard, a double-cancel from application code +
        // watchdog timeout would deliver the failed callback twice.
        val transitionedToFailed = synchronized(this) {
            if (status >= ResourceConstants.COMPLETE) {
                false
            } else {
                // Publish the terminal status while holding the same monitor used
                // by startWatchdog(). Python likewise sets FAILED before stopping
                // its watchdog. This closes the stop-then-status gap where another
                // thread could otherwise install a fresh watchdog on a canceled
                // Resource.
                status = ResourceConstants.FAILED
                cancelTransitionHookForTest?.invoke()
                stopWatchdog()
                true
            }
        }
        if (!transitionedToFailed) return
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
        closeInputFile()
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
    @Synchronized
    private fun startWatchdog() {
        // Test-only suppression (see companion watchdogDisabledForTest): the
        // reference harness disables the watchdog around _build_resource_receiver
        // so an inbound Resource can be inspected synchronously without a
        // timeout-retry cancelling it.
        if (watchdogDisabledForTest) return
        if (watchdogActive) return
        if (status >= ResourceConstants.ASSEMBLING) return

        watchdogActive = true
        val newThread = thread(
            start = false,
            isDaemon = true,
            name = "resource-watchdog-${hash.toHexString().take(8)}",
        ) {
            watchdogJob()
        }
        watchdogThread = newThread
        newThread.start()
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
    @Synchronized
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
     *
     * Sleeps WATCHDOG_MAX_SLEEP between iterations and drives [watchdogTick]
     * (the per-iteration timeout check + recovery). Splitting the iteration
     * out of the loop makes the recovery actions deterministically unit-testable
     * (issue #65) without a live link: a test primes the state and drives
     * [watchdogTick] directly instead of waiting on a background thread.
     */
    private fun watchdogJob() {
        try {
            while (watchdogActive && status < ResourceConstants.ASSEMBLING) {
                try {
                    Thread.sleep(ResourceConstants.WATCHDOG_MAX_SLEEP * 1000)

                    if (!watchdogActive || status >= ResourceConstants.ASSEMBLING) break

                    watchdogTick()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("Watchdog error: ${e.message}")
                }
            }
        } finally {
            synchronized(this) {
                if (watchdogThread === Thread.currentThread()) {
                    watchdogActive = false
                    watchdogThread = null
                }
            }
        }
    }

    /**
     * Perform one watchdog timeout check and dispatch the per-state recovery
     * action.
     *
     * Brings the sender watchdog to python parity (Resource.py:560-670). The
     * pre-fix kotlin watchdog only retried on the receiver side
     * (`if (!initiator) requestNext()`); a sender in ADVERTISED or AWAITING_PROOF
     * simply counted up to MAX_RETRIES and cancelled, with no recovery. Under
     * real packet loss (lost initial request, lost proof) python recovers but
     * kotlin gave up. Each recovery branch below mirrors the python watchdog:
     *
     * - ADVERTISED + timeout + retries left: re-send the advertisement
     *   (Resource.py:583-588); cancel when the ADVERTISED budget (max_adv_retries)
     *   is exhausted or the re-send fails (Resource.py:576-591).
     * - AWAITING_PROOF + timeout + retries left: query the proof from the
     *   network cache via Transport.cache_request (Resource.py:651-658); cancel
     *   when the budget is exhausted (Resource.py:646-648).
     * - TRANSFERRING / other + timeout: receiver retries part requests
     *   (Resource.py:612-629); the sender cancels once the wait elapses
     *   (Resource.py:634-637) - this is the pre-existing behaviour, preserved.
     */
    private fun watchdogTick() {
        val now = System.currentTimeMillis()

        // python parity: each watchdog state anchors its timeout on a different
        // timestamp and factor. AWAITING_PROOF anchors on last_part_sent
        // (Resource.py:644, re-anchored on every retry at Resource.py:657) with
        // the proof timeout factor + sender grace, so each attempt gets a full
        // window. Anchoring AWAITING_PROOF on last_activity (the old behaviour)
        // kept the gate tripped after the first timeout - the proof branch then
        // re-fired on every tick and burned all retries in ~16s, so a slow proof
        // arrived only after the transfer had already failed.
        val proofTimeout =
            (link.rtt ?: 5000L) * ResourceConstants.PROOF_TIMEOUT_FACTOR +
                ResourceConstants.SENDER_GRACE_TIME.toLong() * 1000L
        val partTimeout = (link.rtt ?: 5000L) * ResourceConstants.PART_TIMEOUT_FACTOR

        val timedOut = when (status) {
            ResourceConstants.AWAITING_PROOF -> now - lastPartSent > proofTimeout
            else -> now - lastActivity > partTimeout
        }
        if (!timedOut) return

        // Timed out. Dispatch the recovery action by state (python parity).
        when (status) {
            // Sender advertised but the receiver never sent a part request.
            // python Resource.py:574-591: re-send the advertisement while the
            // ADVERTISED retry budget (max_adv_retries) has room, else cancel.
            ResourceConstants.ADVERTISED -> {
                if (retriesLeft <= 0) {
                    log("Resource ${hash.toHexString()} timed out after sending advertisement")
                    cancel()
                } else {
                    log("No part requests received, retrying resource advertisement (retries_left=$retriesLeft)")
                    retriesLeft--
                    if (!sendAdvertisement()) {
                        log("Could not resend advertisement packet, cancelling resource")
                        cancel()
                    }
                }
            }
            // Sender sent all parts but the proof never came back.
            // python Resource.py:639-658: query the proof from the network cache
            // while the budget has room, else cancel.
            ResourceConstants.AWAITING_PROOF -> {
                if (retriesLeft <= 0) {
                    log("Resource ${hash.toHexString()} timed out waiting for proof")
                    cancel()
                } else {
                    log("All parts sent, no resource proof received, querying network cache (retries_left=$retriesLeft)")
                    retriesLeft--
                    queryProofFromCache()
                }
            }
            else -> {
                // TRANSFERRING (and any other non-terminal active state).
                // Pre-existing receiver-side retry + sender-side cancel, preserved
                // exactly (python Resource.py:612-637). The legacy up-counter
                // [retries] bounds both, as before.
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
                } else {
                    log("Resource timeout, retry $retries/${ResourceConstants.MAX_RETRIES}")
                    if (!initiator) {
                        requestNext()
                    }
                }
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
    /** The unencrypted proof payload the last prove() built (hash + proof).
     *  Returns a defensive copy so callers cannot mutate the stored observation. */
    fun proofPayloadForTest(): ByteArray? = lastProofPayload?.copyOf()
    /** The link_id of the link the last prove()'s proof packet was bound to at
     *  send time, or null if the packet carried no link reference (packet.link
     *  == null). Python's RNS.Packet(link, ...) always sets the reference to the
     *  transfer's link; the kotlin port's createRaw leaves it null. The bridge
     *  compares this against the transfer's link_id so a proof bound to the wrong
     *  (or no) link is both detected and named. Returns a defensive copy. */
    fun proofLinkIdForTest(): ByteArray? = lastProofLinkId?.copyOf()
    fun lastRequestDataForTest(): ByteArray? = lastRequestData
    fun requestNextEmitCountForTest(): Int = requestNextEmitCount.get()
    fun hmuRequestsSentForTest(): Int = hmuRequestsSent.get()
    fun hashmapUpdatesReceivedForTest(): Int = hashmapUpdatesReceived.get()
    fun watchdogActiveForTest(): Boolean = watchdogActive
    fun startWatchdogForTest() = startWatchdog()
    // Drive one watchdog timeout-check/recovery iteration synchronously.
    // This is the issue-#65 test seam: a unit test primes the resource state
    // (ADVERTISED / AWAITING_PROOF, lastActivity in the past, retry budget) and
    // invokes this directly instead of waiting on the background watchdog
    // thread, making the sender recovery actions deterministic.
    fun watchdogTickForTest() = watchdogTick()
    // The receiver-side proof sender, exposed so a test can drive prove()
    // directly (with a primed uncompressedData) and assert it stores the proof
    // packet in the transport cache (python Resource.py:759 force_cache=True).
    fun proveForTest() = prove()
    // The AWAITING_PROOF proof-cache query recovery counter (see proofCacheQueries).
    fun proofCacheQueriesForTest(): Int = proofCacheQueries.get()
    fun setCancelTransitionHookForTest(hook: (() -> Unit)?) {
        cancelTransitionHookForTest = hook
    }

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
