package network.reticulum.resource

import network.reticulum.common.PacketContext
import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.link.Link
import network.reticulum.packet.Packet
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.Inflater
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
            println("[Resource] $message")
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
    private var startedTransferring: Long? = null
    private var retries: Int = 0

    // Watchdog
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogActive = false

    // SDU for this resource
    private val sdu: Int = link.mdu

    // Raw data
    private var uncompressedData: ByteArray? = null
    private var compressedData: ByteArray? = null
    private var assembledData: ByteArray? = null
    private var metadata: ByteArray? = null

    /**
     * Initialize resource for sending.
     */
    private fun initializeForSending(data: ByteArray, metadata: ByteArray?, autoCompress: Boolean) {
        uncompressedData = data
        totalSize = data.size
        uncompressedSize = data.size

        // Handle metadata
        if (metadata != null && metadata.size <= ResourceConstants.METADATA_MAX_SIZE) {
            this.metadata = metadata
            this.hasMetadata = true
            totalSize += metadata.size
        }

        // Compress if requested and within limits
        compressedData = if (autoCompress && data.size <= ResourceConstants.AUTO_COMPRESS_MAX_SIZE) {
            compress(data)
        } else {
            data
        }

        compressed = compressedData!!.size < data.size

        // Use compressed data if it's smaller
        val transferData = if (compressed) compressedData!! else data
        size = transferData.size

        // Split into parts
        val totalParts = ceil(size.toDouble() / sdu).toInt()
        parts = arrayOfNulls(totalParts)
        hashmap = arrayOfNulls(totalParts)

        // Generate random hash for uniqueness
        randomHash = ByteArray(ResourceConstants.RANDOM_HASH_SIZE).also { random.nextBytes(it) }

        // Create hashmap and parts
        val hashmapBuilder = ByteArrayOutputStream()
        for (i in 0 until totalParts) {
            val start = i * sdu
            val end = min(start + sdu, size)
            val part = transferData.copyOfRange(start, end)
            parts[i] = part

            // Calculate part hash
            val partHash = getMapHash(part)
            hashmap[i] = partHash
            hashmapBuilder.write(partHash)
        }
        hashmapRaw = hashmapBuilder.toByteArray()

        // Calculate resource hash
        hash = Hashes.truncatedHash(
            randomHash + hashmapRaw
        )
        originalHash = hash.copyOf()

        // Check for segmentation
        if (totalSize > ResourceConstants.MAX_EFFICIENT_SIZE) {
            totalSegments = ((totalSize - 1) / ResourceConstants.MAX_EFFICIENT_SIZE) + 1
            split = true
        }

        status = ResourceConstants.QUEUED
        log("Resource ${hash.toHexString()} created: $size bytes in ${parts.size} parts")
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

        startWatchdog()
        log("Resource ${hash.toHexString()} accepted: $size bytes in ${parts.size} parts")
    }

    /**
     * Advertise this resource to the receiver.
     */
    fun advertise() {
        if (status != ResourceConstants.QUEUED) return

        status = ResourceConstants.ADVERTISED
        val adv = ResourceAdvertisement.fromResource(this)
        val advData = adv.pack()

        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = advData,
            context = PacketContext.RESOURCE_ADV
        )

        link.send(packet.raw ?: ByteArray(0))
        lastActivity = System.currentTimeMillis()

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
     */
    private fun sendPart(index: Int, data: ByteArray) {
        val partData = byteArrayOf((index shr 8).toByte(), (index and 0xFF).toByte()) + data
        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = partData,
            context = PacketContext.RESOURCE
        )

        link.send(packet.raw ?: ByteArray(0))
        lastActivity = System.currentTimeMillis()
    }

    /**
     * Receive a part from the sender.
     */
    fun receivePart(data: ByteArray) {
        if (status != ResourceConstants.TRANSFERRING) return
        if (data.size < 3) return

        val index = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val partData = data.copyOfRange(2, data.size)

        if (index < 0 || index >= parts.size) {
            log("Invalid part index: $index")
            return
        }

        // Verify part hash
        val expectedHash = hashmap[index]
        if (expectedHash != null) {
            val actualHash = getMapHash(partData)
            if (!expectedHash.contentEquals(actualHash)) {
                log("Part $index hash mismatch")
                return
            }
        }

        if (parts[index] == null) {
            parts[index] = partData
            receivedCount++
            lastActivity = System.currentTimeMillis()

            // Update progress
            callbacks.progress?.invoke(this)

            // Check if transfer complete
            if (receivedCount >= parts.size) {
                assemble()
            } else {
                // Request more parts
                requestNext()
            }
        }
    }

    /**
     * Request the next batch of missing parts.
     */
    private fun requestNext() {
        if (status != ResourceConstants.TRANSFERRING) return

        // Build request for missing parts
        val missingParts = mutableListOf<Int>()
        for (i in parts.indices) {
            if (parts[i] == null && missingParts.size < window) {
                missingParts.add(i)
            }
        }

        if (missingParts.isEmpty()) return

        // Send request
        val requestData = ByteArrayOutputStream()
        for (partIndex in missingParts) {
            requestData.write((partIndex shr 8) and 0xFF)
            requestData.write(partIndex and 0xFF)
        }

        val packet = Packet.createRaw(
            destinationHash = link.linkId,
            data = requestData.toByteArray(),
            context = PacketContext.RESOURCE_REQ
        )

        link.send(packet.raw ?: ByteArray(0))
        lastActivity = System.currentTimeMillis()
        outstandingParts = missingParts.size
    }

    /**
     * Handle a request for parts from the receiver.
     */
    fun handleRequest(data: ByteArray) {
        if (status != ResourceConstants.ADVERTISED && status != ResourceConstants.TRANSFERRING) return

        if (status == ResourceConstants.ADVERTISED) {
            status = ResourceConstants.TRANSFERRING
            startedTransferring = System.currentTimeMillis()
        }

        // Parse requested part indices
        var i = 0
        while (i + 1 < data.size) {
            val index = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (index >= 0 && index < parts.size) {
                val part = parts[index]
                if (part != null) {
                    sendPart(index, part)
                }
            }
            i += 2
        }

        lastActivity = System.currentTimeMillis()
    }

    /**
     * Assemble received parts into final data.
     */
    private fun assemble() {
        if (status != ResourceConstants.TRANSFERRING) return

        status = ResourceConstants.ASSEMBLING
        log("Assembling resource ${hash.toHexString()}")

        try {
            // Combine all parts
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

            var assembled = output.toByteArray()

            // Decompress if needed
            if (compressed) {
                assembled = decompress(assembled)
            }

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

            assembledData = assembled
            uncompressedData = assembled

            status = ResourceConstants.COMPLETE
            stopWatchdog()
            log("Resource ${hash.toHexString()} assembled: ${assembled.size} bytes")

            callbacks.completed?.invoke(this)

        } catch (e: Exception) {
            status = ResourceConstants.FAILED
            log("Assembly error: ${e.message}")
            callbacks.failed?.invoke(this)
        }
    }

    /**
     * Cancel this resource transfer.
     */
    fun cancel() {
        stopWatchdog()
        status = ResourceConstants.FAILED
        log("Resource ${hash.toHexString()} cancelled")
    }

    /**
     * Get the received/assembled data.
     */
    val data: ByteArray?
        get() = assembledData ?: uncompressedData

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
     */
    private fun getMapHash(data: ByteArray): ByteArray {
        return Hashes.fullHash(data).copyOf(ResourceConstants.MAPHASH_LEN)
    }

    /**
     * Compress data using DEFLATE.
     */
    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()

        return output.toByteArray()
    }

    /**
     * Decompress DEFLATE data.
     */
    private fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            output.write(buffer, 0, count)
        }
        inflater.end()

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
