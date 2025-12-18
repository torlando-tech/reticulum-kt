package network.reticulum.transport

import network.reticulum.common.ContextFlag
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.common.concatBytes
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Handler for incoming announces.
 */
fun interface AnnounceHandler {
    /**
     * Called when an announce is received.
     *
     * @param destinationHash The destination hash being announced
     * @param announcedIdentity The identity from the announce (public keys only)
     * @param appData Application data included in the announce
     * @return true if the announce was handled, false to pass to other handlers
     */
    fun handleAnnounce(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?
    ): Boolean
}

/**
 * Callback for packet delivery.
 */
fun interface PacketCallback {
    fun onPacket(data: ByteArray, packet: Packet)
}

/**
 * Callback for proof reception.
 */
fun interface ProofCallback {
    /**
     * Called when a proof is received for a packet.
     *
     * @param packet The proof packet
     * @return true if the proof was validated successfully
     */
    fun onProofReceived(packet: Packet): Boolean
}

/**
 * The Transport singleton manages routing and packet delivery.
 *
 * This is the core routing engine that:
 * - Maintains path tables for known destinations
 * - Processes incoming packets from interfaces
 * - Routes outgoing packets to appropriate interfaces
 * - Handles announce propagation and path discovery
 */
object Transport {
    // ===== State =====

    /** Transport identity for this node. */
    var identity: Identity? = null
        private set

    /** Whether transport is enabled (routing for other nodes). */
    var transportEnabled: Boolean = false
        private set

    /** Whether transport has been started. */
    private val started = AtomicBoolean(false)

    /** Lock for job execution. */
    private val jobsLock = ReentrantLock()

    /** Whether jobs are currently running. */
    private val jobsRunning = AtomicBoolean(false)

    // ===== Tables =====

    /** Path table: destination_hash -> PathEntry. */
    val pathTable = ConcurrentHashMap<ByteArrayKey, PathEntry>()

    /** Link table: link_id -> LinkEntry. */
    val linkTable = ConcurrentHashMap<ByteArrayKey, LinkEntry>()

    /** Reverse table: packet_truncated_hash -> ReverseEntry. */
    val reverseTable = ConcurrentHashMap<ByteArrayKey, ReverseEntry>()

    /** Announce table: destination_hash -> AnnounceEntry. */
    val announceTable = ConcurrentHashMap<ByteArrayKey, AnnounceEntry>()

    /** Packet hash list for duplicate detection. */
    private val packetHashlist = ConcurrentHashMap.newKeySet<ByteArrayKey>()
    private val packetHashlistPrev = ConcurrentHashMap.newKeySet<ByteArrayKey>()

    // ===== Registered Objects =====

    /** Registered interfaces. */
    private val interfaces = CopyOnWriteArrayList<InterfaceRef>()

    /** Registered destinations. */
    private val destinations = CopyOnWriteArrayList<Destination>()

    /** Registered announce handlers. */
    private val announceHandlers = CopyOnWriteArrayList<AnnounceHandler>()

    /** Control destination hashes (path requests, etc.). */
    private val controlHashes = ConcurrentHashMap.newKeySet<ByteArrayKey>()

    /** Rate limiting: destination_hash -> list of announce timestamps. */
    private val announceRateTable = ConcurrentHashMap<ByteArrayKey, MutableList<Long>>()

    /** Queued announces waiting for retransmission. */
    private val queuedAnnounces = CopyOnWriteArrayList<QueuedAnnounce>()

    /** Registered links: link_id -> Link. */
    private val pendingLinks = ConcurrentHashMap<ByteArrayKey, Any>()
    private val activeLinks = ConcurrentHashMap<ByteArrayKey, Any>()

    /** Pending receipts: packet_hash -> ProofCallback. */
    private val pendingReceipts = ConcurrentHashMap<ByteArrayKey, ProofCallback>()

    // ===== Traffic Stats =====

    var trafficRxBytes: Long = 0
        private set
    var trafficTxBytes: Long = 0
        private set

    // ===== Timestamps =====

    private var startTime: Long = 0
    private var tablesLastCulled: Long = 0
    private var hashlistLastCleaned: Long = 0

    /**
     * Initialize and start the transport system.
     *
     * @param transportIdentity Identity for this transport node (or null to generate)
     * @param enableTransport Whether to enable transport (routing for others)
     */
    fun start(transportIdentity: Identity? = null, enableTransport: Boolean = false) {
        if (started.getAndSet(true)) {
            return // Already started
        }

        identity = transportIdentity ?: Identity.create()
        transportEnabled = enableTransport
        startTime = System.currentTimeMillis()
        tablesLastCulled = startTime
        hashlistLastCleaned = startTime

        // Initialize path request destination
        initializeControlDestinations()

        // Start background job thread
        thread(name = "Transport-jobs", isDaemon = true) {
            jobLoop()
        }

        log("Transport started (transport=${if (enableTransport) "enabled" else "disabled"})")
    }

    /**
     * Initialize control destinations (path request, etc.).
     */
    private fun initializeControlDestinations() {
        // Create path request destination
        pathRequestDestination = Destination.create(
            identity = null,
            direction = DestinationDirection.IN,
            type = DestinationType.PLAIN,
            appName = TransportConstants.APP_NAME,
            aspects = arrayOf("path", "request")
        )
        controlHashes.add(pathRequestDestination!!.hash.toKey())
        log("Path request destination: ${pathRequestDestination!!.hexHash}")
    }

    /**
     * Stop the transport system.
     */
    fun stop() {
        if (!started.getAndSet(false)) {
            return
        }

        // Clear all tables
        pathTable.clear()
        linkTable.clear()
        reverseTable.clear()
        announceTable.clear()
        packetHashlist.clear()
        packetHashlistPrev.clear()

        log("Transport stopped")
    }

    // ===== Interface Management =====

    /**
     * Register an interface with transport.
     */
    fun registerInterface(interfaceRef: InterfaceRef) {
        interfaces.add(interfaceRef)
        log("Registered interface: ${interfaceRef.name}")
    }

    /**
     * Deregister an interface.
     */
    fun deregisterInterface(interfaceRef: InterfaceRef) {
        interfaces.remove(interfaceRef)
        log("Deregistered interface: ${interfaceRef.name}")
    }

    /**
     * Get all registered interfaces.
     */
    fun getInterfaces(): List<InterfaceRef> = interfaces.toList()

    // ===== Destination Management =====

    /**
     * Register a destination with transport.
     */
    fun registerDestination(destination: Destination) {
        destinations.add(destination)
        log("Registered destination: ${destination.hexHash}")
    }

    /**
     * Deregister a destination.
     */
    fun deregisterDestination(destination: Destination) {
        destinations.remove(destination)
        log("Deregistered destination: ${destination.hexHash}")
    }

    /**
     * Find a registered destination by hash.
     */
    fun findDestination(hash: ByteArray): Destination? {
        val key = hash.toKey()
        return destinations.find { it.hash.toKey() == key }
    }

    // ===== Announce Handlers =====

    /**
     * Register an announce handler.
     */
    fun registerAnnounceHandler(handler: AnnounceHandler) {
        announceHandlers.add(handler)
    }

    /**
     * Deregister an announce handler.
     */
    fun deregisterAnnounceHandler(handler: AnnounceHandler) {
        announceHandlers.remove(handler)
    }

    // ===== Link Management =====

    /**
     * Register a link (pending or active).
     */
    fun registerLink(link: Any) {
        // Use Any type to avoid circular dependency with Link class
        // The link provides its hash via reflection or interface
        val linkId = getLinkId(link) ?: return
        pendingLinks[linkId.toKey()] = link
        log("Registered pending link: ${linkId.toHexString()}")
    }

    /**
     * Activate a link (move from pending to active).
     */
    fun activateLink(link: Any) {
        val linkId = getLinkId(link) ?: return
        val key = linkId.toKey()
        pendingLinks.remove(key)
        activeLinks[key] = link
        log("Activated link: ${linkId.toHexString()}")
    }

    /**
     * Deregister a link.
     */
    fun deregisterLink(link: Any) {
        val linkId = getLinkId(link) ?: return
        val key = linkId.toKey()
        pendingLinks.remove(key)
        activeLinks.remove(key)
        log("Deregistered link: ${linkId.toHexString()}")
    }

    /**
     * Find a link by ID.
     */
    fun findLink(linkId: ByteArray): Any? {
        val key = linkId.toKey()
        return activeLinks[key] ?: pendingLinks[key]
    }

    /**
     * Get link ID from a link object using reflection.
     */
    private fun getLinkId(link: Any): ByteArray? {
        return try {
            val prop = link::class.java.getDeclaredMethod("getLinkId")
            prop.invoke(link) as? ByteArray
        } catch (e: Exception) {
            try {
                val field = link::class.java.getDeclaredField("linkId")
                field.isAccessible = true
                field.get(link) as? ByteArray
            } catch (e: Exception) {
                null
            }
        }
    }

    // ===== Receipt Management =====

    /**
     * Register a callback for when a proof is received for a packet.
     *
     * @param packetHash The hash of the packet to wait for proof
     * @param callback The callback to invoke when proof is received
     */
    fun registerReceipt(packetHash: ByteArray, callback: ProofCallback) {
        pendingReceipts[packetHash.toKey()] = callback
        log("Registered receipt for ${packetHash.toHexString()}")
    }

    /**
     * Deregister a pending receipt.
     *
     * @param packetHash The packet hash to stop waiting for
     */
    fun deregisterReceipt(packetHash: ByteArray) {
        pendingReceipts.remove(packetHash.toKey())
    }

    // ===== Path Table Operations =====

    /**
     * Check if a path exists to a destination.
     */
    fun hasPath(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false
        return !entry.isExpired()
    }

    /**
     * Get hop count to a destination.
     *
     * @return Hop count, or null if no path
     */
    fun hopsTo(destinationHash: ByteArray): Int? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        return entry.hops
    }

    /**
     * Get next hop for a destination.
     *
     * @return Next hop transport ID (16 bytes), or null if no path
     */
    fun nextHop(destinationHash: ByteArray): ByteArray? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        return entry.nextHop.copyOf()
    }

    /**
     * Expire (remove) a path.
     */
    fun expirePath(destinationHash: ByteArray) {
        pathTable.remove(destinationHash.toKey())
    }

    /**
     * Mark a path as unresponsive.
     *
     * Tracks failure count and transitions path through states:
     * ACTIVE -> UNRESPONSIVE (after first failure)
     * UNRESPONSIVE -> STALE (after 3 failures)
     * STALE paths are automatically expired.
     */
    fun markPathUnresponsive(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        val entry = pathTable[key] ?: return

        entry.failureCount++

        when {
            entry.failureCount >= 3 -> {
                entry.state = PathState.STALE
                log("Path to ${destinationHash.toHexString()} marked STALE, expiring")
                expirePath(destinationHash)
            }
            entry.failureCount >= 1 -> {
                entry.state = PathState.UNRESPONSIVE
                log("Path to ${destinationHash.toHexString()} marked UNRESPONSIVE (${entry.failureCount} failures)")
            }
        }
    }

    /**
     * Mark a path as responsive (reset failure state).
     */
    fun markPathResponsive(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        val entry = pathTable[key] ?: return

        if (entry.state != PathState.ACTIVE || entry.failureCount > 0) {
            entry.state = PathState.ACTIVE
            entry.failureCount = 0
            log("Path to ${destinationHash.toHexString()} marked ACTIVE")
        }
    }

    // ===== Path Requests =====

    /** Pending path requests: destination_hash -> request timestamp. */
    private val pathRequests = ConcurrentHashMap<ByteArrayKey, Long>()

    /** Discovery path request tags to avoid duplicates. */
    private val discoveryPrTags = CopyOnWriteArrayList<ByteArrayKey>()

    /** Path request destination for sending requests. */
    private var pathRequestDestination: Destination? = null

    /**
     * Request a path to a destination from the network.
     *
     * This broadcasts a path request packet. If another node on the network
     * knows a path, it will respond with an announce.
     *
     * @param destinationHash The destination to find a path to
     * @param onInterface Optional specific interface to send request on
     * @param callback Optional callback when path is found
     */
    fun requestPath(
        destinationHash: ByteArray,
        onInterface: InterfaceRef? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (!started.get()) {
            callback?.invoke(false)
            return
        }

        // Check if we already have a path
        if (hasPath(destinationHash)) {
            callback?.invoke(true)
            return
        }

        // Check if request was made too recently
        val lastRequest = pathRequests[destinationHash.toKey()]
        val now = System.currentTimeMillis()
        if (lastRequest != null && now - lastRequest < TransportConstants.PATH_REQUEST_MI) {
            log("Skipping path request for ${destinationHash.toHexString()} (too recent)")
            callback?.invoke(false)
            return
        }

        // Generate request tag
        val requestTag = Hashes.getRandomHash()

        // Build path request data
        val pathRequestData = if (transportEnabled && identity != null) {
            // Transport mode: include our transport ID
            concatBytes(destinationHash, identity!!.hash, requestTag)
        } else {
            // Client mode: just destination and tag
            concatBytes(destinationHash, requestTag)
        }

        // Ensure path request destination exists (created during start)
        val prDest = pathRequestDestination
        if (prDest == null) {
            log("Path request destination not initialized")
            callback?.invoke(false)
            return
        }

        // Create and send the packet
        val packet = Packet.createRaw(
            destinationHash = prDest.hash,
            data = pathRequestData,
            packetType = PacketType.DATA,
            destinationType = DestinationType.PLAIN,
            transportType = TransportType.BROADCAST
        )

        // Send on specific interface or all
        val sent = if (onInterface != null) {
            try {
                onInterface.send(packet.pack())
                true
            } catch (e: Exception) {
                log("Failed to send path request: ${e.message}")
                false
            }
        } else {
            outbound(packet)
        }

        if (sent) {
            pathRequests[destinationHash.toKey()] = now
            log("Sent path request for ${destinationHash.toHexString()}")

            // Set up timeout callback if provided
            if (callback != null) {
                thread(name = "PathRequest-timeout", isDaemon = true) {
                    Thread.sleep(TransportConstants.PATH_REQUEST_TIMEOUT)
                    val found = hasPath(destinationHash)
                    callback(found)
                }
            }
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * Handle an incoming path request packet.
     */
    private fun handlePathRequest(
        data: ByteArray,
        @Suppress("UNUSED_PARAMETER") packet: Packet,
        receivingInterface: InterfaceRef
    ) {
        if (data.size < RnsConstants.TRUNCATED_HASH_BYTES) return

        val destinationHash = data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

        // Extract requesting transport ID if present
        val requestingTransportId = if (data.size > RnsConstants.TRUNCATED_HASH_BYTES * 2) {
            data.copyOfRange(RnsConstants.TRUNCATED_HASH_BYTES, RnsConstants.TRUNCATED_HASH_BYTES * 2)
        } else null

        // Extract tag
        val tagOffset = if (requestingTransportId != null) {
            RnsConstants.TRUNCATED_HASH_BYTES * 2
        } else {
            RnsConstants.TRUNCATED_HASH_BYTES
        }

        if (data.size <= tagOffset) {
            log("Ignoring tagless path request")
            return
        }

        val tagBytes = data.copyOfRange(tagOffset, minOf(tagOffset + RnsConstants.TRUNCATED_HASH_BYTES, data.size))
        val uniqueTag = concatBytes(destinationHash, tagBytes).toKey()

        // Check for duplicate
        if (discoveryPrTags.contains(uniqueTag)) {
            log("Ignoring duplicate path request for ${destinationHash.toHexString()}")
            return
        }

        discoveryPrTags.add(uniqueTag)

        // Trim tags list if too large
        while (discoveryPrTags.size > 32000) {
            discoveryPrTags.removeAt(0)
        }

        // Check if we know the path
        processPathRequest(destinationHash, receivingInterface, requestingTransportId, tagBytes)
    }

    /**
     * Process a path request - check if we know the destination and can announce it.
     */
    private fun processPathRequest(
        destinationHash: ByteArray,
        @Suppress("UNUSED_PARAMETER") receivingInterface: InterfaceRef,
        @Suppress("UNUSED_PARAMETER") requestingTransportId: ByteArray?,
        @Suppress("UNUSED_PARAMETER") tag: ByteArray
    ) {
        // Check if this is a local destination
        val localDest = findDestination(destinationHash)
        if (localDest != null) {
            log("Responding to path request with local destination ${destinationHash.toHexString()}")

            // Create and send an announce
            val announcePacket = Packet.createAnnounce(localDest)
            if (announcePacket != null) {
                outbound(announcePacket)
                log("Sent announce in response to path request")
            } else {
                log("Cannot create announce for ${destinationHash.toHexString()} (no private key)")
            }
            return
        }

        // Check if we know a path (transport nodes can forward cached announces)
        val pathEntry = pathTable[destinationHash.toKey()]
        if (pathEntry != null && !pathEntry.isExpired()) {
            // Note: To forward cached announces, we would need to store the raw announce data
            // in the path table or a separate announce cache. For now, just log that we know the path.
            log("We know path to ${destinationHash.toHexString()} (${pathEntry.hops} hops), announce forwarding requires caching")
        }
    }

    // ===== Packet Processing =====

    /**
     * Process an incoming packet from an interface.
     *
     * @param raw Raw packet bytes
     * @param interfaceRef Interface that received the packet
     */
    fun inbound(raw: ByteArray, interfaceRef: InterfaceRef) {
        if (!started.get()) return
        if (raw.size < RnsConstants.HEADER_MIN_SIZE) return

        jobsLock.withLock {
            try {
                processInbound(raw, interfaceRef)
            } catch (e: Exception) {
                log("Error processing inbound packet: ${e.message}")
            }
        }
    }

    private fun processInbound(raw: ByteArray, interfaceRef: InterfaceRef) {
        // Parse the packet
        val packet = Packet.unpack(raw) ?: return

        // Increment hop count
        packet.hops++

        // Check for duplicates
        if (!packetFilter(packet)) {
            return
        }

        // Add to hashlist (with some exceptions)
        val rememberHash = when {
            linkTable.containsKey(packet.destinationHash.toKey()) -> false
            packet.packetType == PacketType.PROOF &&
                packet.context == PacketContext.LRPROOF -> false
            else -> true
        }

        if (rememberHash) {
            addPacketHash(packet.packetHash)
        }

        trafficRxBytes += raw.size

        // Route based on packet type
        when (packet.packetType) {
            PacketType.ANNOUNCE -> processAnnounce(packet, interfaceRef)
            PacketType.LINKREQUEST -> processLinkRequest(packet, interfaceRef)
            PacketType.PROOF -> processProof(packet, interfaceRef)
            PacketType.DATA -> processData(packet, interfaceRef)
        }
    }

    /**
     * Send a packet.
     *
     * @param packet Packet to send
     * @return true if sent successfully
     */
    fun outbound(packet: Packet): Boolean {
        if (!started.get()) return false

        return jobsLock.withLock {
            try {
                processOutbound(packet)
            } catch (e: Exception) {
                log("Error processing outbound packet: ${e.message}")
                false
            }
        }
    }

    private fun processOutbound(packet: Packet): Boolean {
        val packedData = packet.pack()
        var sent = false

        // Check if we have a known path
        val pathEntry = pathTable[packet.destinationHash.toKey()]

        if (pathEntry != null && !pathEntry.isExpired() &&
            packet.packetType != PacketType.ANNOUNCE &&
            packet.destinationType != DestinationType.PLAIN &&
            packet.destinationType != DestinationType.GROUP) {

            // We have a path - use it
            val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
            if (outboundInterface != null) {
                if (pathEntry.hops > 1) {
                    // Insert into transport (HEADER_2)
                    val transportRaw = insertIntoTransport(packet, pathEntry.nextHop)
                    transmit(outboundInterface, transportRaw)
                    sent = true
                } else {
                    // Direct transmission
                    transmit(outboundInterface, packedData)
                    sent = true
                }

                // Update path timestamp
                pathTable[packet.destinationHash.toKey()] = pathEntry.touch()
            }
        }

        if (!sent) {
            // No path - broadcast on all interfaces
            addPacketHash(packet.packetHash)

            for (iface in interfaces) {
                if (iface.canSend && iface.online) {
                    transmit(iface, packedData)
                    sent = true
                }
            }
        }

        return sent
    }

    /**
     * Transmit raw data on an interface.
     */
    private fun transmit(interfaceRef: InterfaceRef, data: ByteArray) {
        try {
            interfaceRef.send(data)
            trafficTxBytes += data.size
        } catch (e: Exception) {
            log("Transmit error on ${interfaceRef.name}: ${e.message}")
        }
    }

    /**
     * Insert a packet into transport by adding HEADER_2.
     */
    private fun insertIntoTransport(packet: Packet, nextHop: ByteArray): ByteArray {
        val raw = packet.raw ?: packet.pack()

        // Build new flags with HEADER_2 and TRANSPORT type
        val newFlags = (HeaderType.HEADER_2.value shl 6) or
                       (TransportType.TRANSPORT.value shl 4) or
                       (raw[0].toInt() and 0x0F)

        // Build new packet: flags + hops + transport_id + rest of original
        val result = ByteArray(raw.size + RnsConstants.TRUNCATED_HASH_BYTES)
        result[0] = newFlags.toByte()
        result[1] = raw[1] // hops
        System.arraycopy(nextHop, 0, result, 2, RnsConstants.TRUNCATED_HASH_BYTES)
        System.arraycopy(raw, 2, result, 2 + RnsConstants.TRUNCATED_HASH_BYTES, raw.size - 2)

        return result
    }

    // ===== Packet Type Handlers =====

    private fun processAnnounce(packet: Packet, interfaceRef: InterfaceRef) {
        // Validate and extract announce data
        val announceData = validateAnnounce(packet) ?: return

        val destHash = packet.destinationHash
        val identity = announceData.identity
        val appData = announceData.appData

        // Update path table
        val pathEntry = PathEntry(
            timestamp = System.currentTimeMillis(),
            nextHop = destHash.copyOf(), // For direct announces, next hop is the destination
            hops = packet.hops,
            expires = System.currentTimeMillis() + TransportConstants.PATHFINDER_E,
            randomBlobs = mutableListOf(announceData.randomHash),
            receivingInterfaceHash = interfaceRef.hash,
            announcePacketHash = packet.packetHash
        )

        pathTable[destHash.toKey()] = pathEntry

        log("Learned path to ${destHash.toHexString()} via ${interfaceRef.name} (${packet.hops} hops)")

        // Notify announce handlers
        for (handler in announceHandlers) {
            try {
                if (handler.handleAnnounce(destHash, identity, appData)) {
                    break
                }
            } catch (e: Exception) {
                log("Announce handler error: ${e.message}")
            }
        }

        // Retransmit if transport is enabled and under hop limit
        if (transportEnabled && packet.hops < TransportConstants.PATHFINDER_M) {
            queueAnnounceRetransmit(destHash, packet, interfaceRef)
        }
    }

    /**
     * Queue an announce for retransmission after rate limiting checks.
     */
    private fun queueAnnounceRetransmit(
        destinationHash: ByteArray,
        packet: Packet,
        receivingInterface: InterfaceRef
    ) {
        val destKey = destinationHash.toKey()
        val now = System.currentTimeMillis()

        // Check rate limiting
        val rateTimestamps = announceRateTable.getOrPut(destKey) { mutableListOf() }

        // Clean old timestamps (older than 30 seconds)
        val cutoff = now - 30_000
        rateTimestamps.removeAll { it < cutoff }

        if (rateTimestamps.size >= TransportConstants.MAX_RATE_TIMESTAMPS) {
            log("Rate limiting announce for ${destinationHash.toHexString()}")
            return
        }

        rateTimestamps.add(now)

        // Calculate random delay for retransmission
        val graceMs = TransportConstants.PATH_REQUEST_GRACE
        val randomMs = (Math.random() * TransportConstants.PATHFINDER_RW * 1000).toLong()
        val retransmitTime = now + graceMs + randomMs

        // Create queued announce
        val queuedAnnounce = QueuedAnnounce(
            destinationHash = destinationHash.copyOf(),
            time = retransmitTime,
            hops = packet.hops,
            emitted = now,
            raw = packet.raw ?: packet.pack()
        )

        // Store in announce table for potential path request responses
        val announceEntry = AnnounceEntry(
            destinationHash = destinationHash.copyOf(),
            timestamp = now,
            retransmits = 0,
            retransmitTimeout = retransmitTime,
            raw = queuedAnnounce.raw,
            hops = packet.hops,
            receivingInterfaceHash = receivingInterface.hash,
            localRebroadcasts = 0
        )
        announceTable[destKey] = announceEntry

        // Add to retransmit queue
        queuedAnnounces.add(queuedAnnounce)

        log("Queued announce for ${destinationHash.toHexString()} (retransmit in ${retransmitTime - now}ms)")
    }

    private fun processData(packet: Packet, interfaceRef: InterfaceRef) {
        // Check if this is a control packet (path request, etc.)
        if (controlHashes.contains(packet.destinationHash.toKey())) {
            // Check if this is a path request
            if (pathRequestDestination != null &&
                packet.destinationHash.contentEquals(pathRequestDestination!!.hash)) {
                handlePathRequest(packet.data, packet, interfaceRef)
                return
            }
        }

        // Check if this is for a local destination
        val destination = findDestination(packet.destinationHash)

        if (destination != null) {
            // Deliver locally
            deliverPacket(destination, packet)
            return
        }

        // Check if we should forward (transport mode)
        if (transportEnabled && packet.transportId != null) {
            val myHash = identity?.hash
            if (myHash != null && packet.transportId.contentEquals(myHash)) {
                forwardPacket(packet, interfaceRef)
            }
        }
    }

    private fun processLinkRequest(packet: Packet, interfaceRef: InterfaceRef) {
        // Check if this is for a local destination
        val destination = findDestination(packet.destinationHash)

        if (destination != null) {
            // Deliver locally
            deliverPacket(destination, packet)
            return
        }

        // Forward if transport enabled
        if (transportEnabled && packet.transportId != null) {
            val myHash = identity?.hash
            if (myHash != null && packet.transportId.contentEquals(myHash)) {
                // Record in link table for return path
                val pathEntry = pathTable[packet.destinationHash.toKey()]
                if (pathEntry != null) {
                    // Create link table entry for return routing of proofs
                    val linkEntry = LinkEntry(
                        timestamp = System.currentTimeMillis(),
                        nextHop = pathEntry.nextHop,
                        nextHopInterfaceHash = pathEntry.receivingInterfaceHash,
                        remainingHops = pathEntry.hops,
                        receivingInterfaceHash = interfaceRef.hash,
                        takenHops = packet.hops,
                        destinationHash = packet.destinationHash,
                        validated = false,
                        proofTimeout = System.currentTimeMillis() + TransportConstants.LINK_PROOF_TIMEOUT
                    )
                    linkTable[packet.truncatedHash.toKey()] = linkEntry
                    log("Created link table entry for ${packet.truncatedHash.toHexString()}")

                    forwardPacket(packet, interfaceRef)
                }
            }
        }
    }

    private fun processProof(packet: Packet, @Suppress("UNUSED_PARAMETER") interfaceRef: InterfaceRef) {
        // Check reverse table for return path
        val reverseEntry = reverseTable[packet.truncatedHash.toKey()]

        if (reverseEntry != null) {
            // Forward proof back
            val outboundInterface = findInterfaceByHash(reverseEntry.receivingInterfaceHash)
            if (outboundInterface != null) {
                transmit(outboundInterface, packet.raw ?: packet.pack())
            }
            return
        }

        // Check if there's a pending receipt for this proof
        // The proof's destination hash should match the original packet hash
        val receiptKey = packet.destinationHash.toKey()
        val callback = pendingReceipts.remove(receiptKey)

        if (callback != null) {
            try {
                val validated = callback.onProofReceived(packet)
                if (validated) {
                    log("Proof validated for ${packet.destinationHash.toHexString()}")
                    // Mark path as responsive if we have one
                    markPathResponsive(packet.destinationHash)
                } else {
                    log("Proof validation failed for ${packet.destinationHash.toHexString()}")
                }
            } catch (e: Exception) {
                log("Proof callback error: ${e.message}")
            }
        } else {
            log("Received proof with no pending receipt: ${packet.destinationHash.toHexString()}")
        }
    }

    private fun forwardPacket(packet: Packet, receivingInterface: InterfaceRef) {
        val pathEntry = pathTable[packet.destinationHash.toKey()] ?: return
        val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash) ?: return

        val raw = packet.raw ?: return

        if (pathEntry.hops > 1) {
            // Update transport header with next hop
            val newRaw = raw.copyOf()
            newRaw[1] = packet.hops.toByte()
            System.arraycopy(pathEntry.nextHop, 0, newRaw, 2, RnsConstants.TRUNCATED_HASH_BYTES)
            transmit(outboundInterface, newRaw)
        } else {
            // Strip transport header (convert to HEADER_1)
            val newFlags = (HeaderType.HEADER_1.value shl 6) or
                           (TransportType.BROADCAST.value shl 4) or
                           (raw[0].toInt() and 0x0F)
            val newRaw = ByteArray(raw.size - RnsConstants.TRUNCATED_HASH_BYTES)
            newRaw[0] = newFlags.toByte()
            newRaw[1] = packet.hops.toByte()
            System.arraycopy(
                raw, 2 + RnsConstants.TRUNCATED_HASH_BYTES,
                newRaw, 2,
                raw.size - 2 - RnsConstants.TRUNCATED_HASH_BYTES
            )
            transmit(outboundInterface, newRaw)
        }

        // Record reverse entry for proofs
        val reverseEntry = ReverseEntry(
            receivingInterfaceHash = receivingInterface.hash,
            outboundInterfaceHash = outboundInterface.hash,
            timestamp = System.currentTimeMillis()
        )
        reverseTable[packet.truncatedHash.toKey()] = reverseEntry

        // Update path timestamp
        pathTable[packet.destinationHash.toKey()] = pathEntry.touch()
    }

    private fun deliverPacket(destination: Destination, packet: Packet) {
        val data = packet.data
        if (data.isEmpty()) {
            log("Ignoring empty packet for ${destination.hexHash}")
            return
        }

        try {
            // Handle based on packet type
            when (packet.packetType) {
                PacketType.DATA -> {
                    // Decrypt the data if needed
                    val plaintext = when (destination.type) {
                        DestinationType.PLAIN -> data
                        else -> destination.decrypt(data) ?: run {
                            log("Failed to decrypt packet for ${destination.hexHash}")
                            return
                        }
                    }

                    // Deliver via callback
                    val callback = destination.packetCallback
                    if (callback != null) {
                        callback(plaintext, packet)
                        log("Delivered packet to ${destination.hexHash} (${plaintext.size} bytes)")
                    } else {
                        log("No callback registered for ${destination.hexHash}")
                    }
                }

                PacketType.LINKREQUEST -> {
                    // Check if destination accepts link requests
                    if (!destination.acceptLinkRequests) {
                        log("Destination ${destination.hexHash} not accepting link requests")
                        return
                    }

                    // Deliver to link callback if set
                    val linkCallback = destination.linkRequestCallback
                    if (linkCallback != null) {
                        val linkId = packet.truncatedHash
                        val accepted = linkCallback(linkId)
                        log("Link request for ${destination.hexHash}: ${if (accepted) "accepted" else "rejected"}")
                    } else {
                        log("No link callback registered for ${destination.hexHash}")
                    }
                }

                else -> {
                    log("Unexpected packet type ${packet.packetType} for delivery")
                }
            }
        } catch (e: Exception) {
            log("Error delivering packet to ${destination.hexHash}: ${e.message}")
        }
    }

    // ===== Announce Validation =====

    private data class AnnounceData(
        val identity: Identity,
        val nameHash: ByteArray,
        val randomHash: ByteArray,
        val appData: ByteArray?
    )

    private fun validateAnnounce(packet: Packet): AnnounceData? {
        val data = packet.data
        if (data.size < RnsConstants.ANNOUNCE_MIN_SIZE) return null

        // Announce format:
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [signature: 64] [app_data: variable]
        // Or with ratchet:
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [ratchet: 32] [signature: 64] [app_data: variable]

        val publicKey = data.copyOfRange(0, RnsConstants.IDENTITY_PUBLIC_KEY_SIZE)
        val nameHash = data.copyOfRange(64, 74)
        val randomHash = data.copyOfRange(74, 84)

        // Determine if ratchet is present
        val hasRatchet = data.size >= 84 + 32 + 64
        val signatureOffset = if (hasRatchet) 84 + 32 else 84
        val signatureEnd = signatureOffset + 64

        if (data.size < signatureEnd) return null

        val signature = data.copyOfRange(signatureOffset, signatureEnd)
        val appData = if (data.size > signatureEnd) {
            data.copyOfRange(signatureEnd, data.size)
        } else null

        // Create identity from public key
        val identity = try {
            Identity.fromPublicKey(publicKey)
        } catch (e: Exception) {
            return null
        }

        // Verify destination hash matches
        val computedDestHash = Destination.computeHash(nameHash, identity.hash)
        if (!computedDestHash.contentEquals(packet.destinationHash)) {
            return null
        }

        // Verify signature
        val signedData = data.copyOfRange(0, signatureOffset)
        if (!identity.validate(signature, signedData)) {
            return null
        }

        return AnnounceData(identity, nameHash, randomHash, appData)
    }

    // ===== Packet Filter (Duplicate Detection) =====

    private fun packetFilter(packet: Packet): Boolean {
        val key = packet.packetHash.toKey()
        return !packetHashlist.contains(key) && !packetHashlistPrev.contains(key)
    }

    private fun addPacketHash(hash: ByteArray) {
        val key = hash.toKey()
        packetHashlist.add(key)

        // Rotate hashlist if too large
        if (packetHashlist.size > TransportConstants.HASHLIST_MAXSIZE) {
            packetHashlistPrev.clear()
            packetHashlistPrev.addAll(packetHashlist)
            packetHashlist.clear()
        }
    }

    // ===== Background Jobs =====

    private fun jobLoop() {
        while (started.get()) {
            try {
                Thread.sleep(TransportConstants.JOB_INTERVAL)
                runJobs()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log("Job error: ${e.message}")
            }
        }
    }

    private fun runJobs() {
        val now = System.currentTimeMillis()

        // Process queued announces
        processQueuedAnnounces(now)

        // Cull stale table entries
        if (now - tablesLastCulled > TransportConstants.TABLES_CULL_INTERVAL) {
            cullTables()
            tablesLastCulled = now
        }

        // Clean hashlist
        if (now - hashlistLastCleaned > TransportConstants.CACHE_CLEAN_INTERVAL) {
            packetHashlistPrev.clear()
            hashlistLastCleaned = now
        }
    }

    /**
     * Process queued announces that are ready for retransmission.
     */
    private fun processQueuedAnnounces(now: Long) {
        val toRemove = mutableListOf<QueuedAnnounce>()

        for (queued in queuedAnnounces) {
            if (now >= queued.time) {
                retransmitAnnounce(queued)
                toRemove.add(queued)
            }
        }

        queuedAnnounces.removeAll(toRemove)
    }

    /**
     * Retransmit a queued announce on all interfaces.
     */
    private fun retransmitAnnounce(queued: QueuedAnnounce) {
        val destKey = queued.destinationHash.toKey()
        val announceEntry = announceTable[destKey] ?: return

        // Check if we've exceeded max local rebroadcasts
        if (announceEntry.localRebroadcasts >= TransportConstants.LOCAL_REBROADCASTS_MAX) {
            log("Max rebroadcasts reached for ${queued.destinationHash.toHexString()}")
            return
        }

        // Increment hop count in the raw packet
        val rawCopy = queued.raw.copyOf()
        rawCopy[1] = ((rawCopy[1].toInt() and 0xFF) + 1).toByte()

        // Transmit on all interfaces except the receiving one
        var transmitted = false
        for (iface in interfaces) {
            if (iface.canSend && iface.online &&
                !iface.hash.contentEquals(announceEntry.receivingInterfaceHash)) {
                try {
                    iface.send(rawCopy)
                    trafficTxBytes += rawCopy.size
                    transmitted = true
                } catch (e: Exception) {
                    log("Announce retransmit error on ${iface.name}: ${e.message}")
                }
            }
        }

        if (transmitted) {
            announceEntry.retransmits++
            announceEntry.localRebroadcasts++
            log("Retransmitted announce for ${queued.destinationHash.toHexString()} (rebroadcast ${announceEntry.localRebroadcasts})")
        }
    }

    private fun cullTables() {
        // Remove expired path entries
        pathTable.entries.removeIf { it.value.isExpired() }

        // Remove expired reverse entries
        reverseTable.entries.removeIf { it.value.isExpired() }
    }

    // ===== Helpers =====

    private fun findInterfaceByHash(hash: ByteArray): InterfaceRef? {
        val key = hash.toKey()
        return interfaces.find { it.hash.toKey() == key }
    }

    private fun log(message: String) {
        println("[Transport] $message")
    }
}

/**
 * Reference to an interface for transport routing.
 *
 * This abstraction allows Transport to work with interfaces without
 * depending on the full Interface class from rns-interfaces.
 */
interface InterfaceRef {
    val name: String
    val hash: ByteArray
    val canSend: Boolean
    val canReceive: Boolean
    val online: Boolean

    fun send(data: ByteArray)
}

/**
 * Wrapper for ByteArray to use as map key.
 */
class ByteArrayKey(private val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArrayKey) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.toHexString()
}

/**
 * Convert ByteArray to ByteArrayKey for use in maps.
 */
fun ByteArray.toKey(): ByteArrayKey = ByteArrayKey(this)
