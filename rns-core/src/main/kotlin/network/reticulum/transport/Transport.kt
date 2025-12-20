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
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.packet.Packet
import network.reticulum.packet.PacketReceipt
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

    /** Crypto provider for IFAC operations. */
    private val crypto: CryptoProvider by lazy { defaultCryptoProvider() }

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

    // ===== Packet Cache =====

    /** Cached packet data class. */
    data class CachedPacket(
        val raw: ByteArray,
        val timestamp: Long,
        val receivingInterfaceHash: ByteArray?
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > TransportConstants.PACKET_CACHE_TIMEOUT
    }

    /** Interface traffic statistics. */
    data class InterfaceTrafficStats(
        var txBytes: Long = 0,
        var rxBytes: Long = 0,
        var txPackets: Long = 0,
        var rxPackets: Long = 0,
        var announcesSent: Long = 0,
        var lastActivity: Long = System.currentTimeMillis()
    )

    /** Packet cache: packet_hash -> CachedPacket. */
    private val packetCache = ConcurrentHashMap<ByteArrayKey, CachedPacket>()

    /** Last time cache was cleaned. */
    private var cacheLastCleaned: Long = 0

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

    /** All outgoing packet receipts for timeout tracking. */
    private val receipts = CopyOnWriteArrayList<PacketReceipt>()
    private var receiptsLastChecked: Long = 0

    /** Announce timing per destination: dest_hash -> allowed_at timestamp. */
    private val announceAllowedAt = ConcurrentHashMap<ByteArrayKey, Long>()

    /** Held announces awaiting retransmission: dest_hash -> (timestamp, raw_data). */
    private val heldAnnounces = ConcurrentHashMap<ByteArrayKey, Pair<Long, ByteArray>>()

    /** Local client interfaces (shared instance clients). */
    private val localClientInterfaces = CopyOnWriteArrayList<InterfaceRef>()

    /** Per-interface traffic statistics. */
    private val interfaceStats = ConcurrentHashMap<ByteArrayKey, InterfaceTrafficStats>()

    /** Per-interface announce queues. */
    private val interfaceAnnounceQueues = ConcurrentHashMap<ByteArrayKey, MutableList<QueuedAnnounce>>()

    /** Per-interface announce allowed timestamps. */
    private val interfaceAnnounceAllowedAt = ConcurrentHashMap<ByteArrayKey, Long>()

    // ===== Tunnels =====

    /** Active tunnels: tunnel_id -> TunnelInfo. */
    private val tunnels = ConcurrentHashMap<ByteArrayKey, TunnelInfo>()

    /** Tunnel interfaces: tunnel_id -> InterfaceRef. */
    private val tunnelInterfaces = ConcurrentHashMap<ByteArrayKey, InterfaceRef>()

    // ===== Traffic Stats =====

    var trafficRxBytes: Long = 0
        private set
    var trafficTxBytes: Long = 0
        private set

    /** Current RX speed in bytes/sec. */
    var speedRx: Long = 0
        private set

    /** Current TX speed in bytes/sec. */
    var speedTx: Long = 0
        private set

    /** Last traffic snapshot for speed calculation. */
    private var lastTrafficSnapshot = Pair(0L, 0L)
    private var lastTrafficTime = 0L

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
        announceAllowedAt.clear()
        heldAnnounces.clear()
        localClientInterfaces.clear()
        queuedAnnounces.clear()
        announceRateTable.clear()
        pathRequests.clear()
        discoveryPrTags.clear()
        pendingReceipts.clear()
        tunnels.clear()
        tunnelInterfaces.clear()

        // Reset speed tracking
        speedRx = 0
        speedTx = 0
        lastTrafficSnapshot = Pair(0L, 0L)
        lastTrafficTime = 0L

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
     * Get all registered destinations.
     * Used by Identity.recall() for fallback lookups.
     */
    internal fun getDestinations(): List<Destination> {
        return destinations.toList()
    }

    /**
     * Find a registered destination by hash.
     */
    fun findDestination(hash: ByteArray): Destination? {
        val key = hash.toKey()
        return destinations.find { it.hash.toKey() == key }
    }

    // ===== Receipt Tracking =====

    /**
     * Register a packet receipt for timeout tracking.
     * Called automatically when packets are sent with createReceipt=true.
     */
    fun registerReceipt(receipt: PacketReceipt) {
        receipts.add(receipt)
    }

    /**
     * Find a receipt by packet hash.
     */
    fun findReceipt(packetHash: ByteArray): PacketReceipt? {
        val key = packetHash.toKey()
        return receipts.find { it.hash.toKey() == key }
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

    /**
     * Check if a path is currently unresponsive.
     */
    fun pathIsUnresponsive(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false
        return entry.state == PathState.UNRESPONSIVE || entry.state == PathState.STALE
    }

    /**
     * Mark a path as unknown state (reset for re-discovery).
     */
    fun markPathUnknownState(destinationHash: ByteArray) {
        val entry = pathTable[destinationHash.toKey()] ?: return
        entry.state = PathState.ACTIVE
        entry.failureCount = 0
    }

    /**
     * Get path state for a destination.
     *
     * @param destinationHash The destination to check
     * @return Path state constant (PATH_STATE_*), or PATH_STATE_UNKNOWN if no path exists
     */
    fun getPathState(destinationHash: ByteArray): Int {
        val entry = pathTable[destinationHash.toKey()] ?: return TransportConstants.PATH_STATE_UNKNOWN

        return when (entry.state) {
            PathState.ACTIVE -> TransportConstants.PATH_STATE_RESPONSIVE
            PathState.UNRESPONSIVE, PathState.STALE -> TransportConstants.PATH_STATE_UNRESPONSIVE
        }
    }

    /**
     * Check if a path is expired or has been unresponsive for too long.
     *
     * @param destinationHash The destination to check
     * @return true if path is unresponsive beyond timeout threshold
     */
    fun isPathUnresponsive(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false

        // Check if path is marked unresponsive
        if (entry.state == PathState.UNRESPONSIVE || entry.state == PathState.STALE) {
            return true
        }

        // Check if path has been inactive too long
        val timeSinceActivity = System.currentTimeMillis() - entry.timestamp
        return timeSinceActivity > TransportConstants.PATH_UNRESPONSIVE_TIMEOUT
    }

    /**
     * Get the interface for the next hop to a destination.
     */
    fun nextHopInterface(destinationHash: ByteArray): InterfaceRef? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        return findInterfaceByHash(entry.receivingInterfaceHash)
    }

    // ===== Interface Latency Calculations =====

    /**
     * Get the bitrate of the next-hop interface.
     *
     * @param destinationHash The destination to check
     * @return Bitrate in bits per second, or null if unknown
     */
    fun nextHopInterfaceBitrate(destinationHash: ByteArray): Int? {
        val iface = nextHopInterface(destinationHash) ?: return null
        return iface.bitrate
    }

    /**
     * Get the hardware MTU of the next-hop interface.
     *
     * @param destinationHash The destination to check
     * @return Hardware MTU in bytes, or null if unknown
     */
    fun nextHopInterfaceHwMtu(destinationHash: ByteArray): Int? {
        val iface = nextHopInterface(destinationHash) ?: return null
        return iface.hwMtu
    }

    /**
     * Calculate per-bit latency for next hop (seconds).
     *
     * @param destinationHash The destination to check
     * @return Latency per bit in seconds, or null if bitrate unknown
     */
    fun nextHopPerBitLatency(destinationHash: ByteArray): Double? {
        val bitrate = nextHopInterfaceBitrate(destinationHash) ?: return null
        if (bitrate == 0) return null
        return 1.0 / bitrate
    }

    /**
     * Calculate per-byte latency for next hop (seconds).
     *
     * @param destinationHash The destination to check
     * @return Latency per byte in seconds, or null if bitrate unknown
     */
    fun nextHopPerByteLatency(destinationHash: ByteArray): Double? {
        val perBit = nextHopPerBitLatency(destinationHash) ?: return null
        return perBit * 8
    }

    /**
     * Calculate first hop timeout based on MTU and bitrate.
     * Matches Python RNS implementation: MTU * per_byte_latency + DEFAULT_PER_HOP_TIMEOUT.
     *
     * @param destinationHash The destination to check
     * @return Timeout in milliseconds
     */
    fun firstHopTimeout(destinationHash: ByteArray): Long {
        val perByteLatency = nextHopPerByteLatency(destinationHash)
        return if (perByteLatency != null) {
            val transmitTime = (RnsConstants.MTU * perByteLatency * 1000).toLong()
            transmitTime + TransportConstants.DEFAULT_PER_HOP_TIMEOUT
        } else {
            TransportConstants.DEFAULT_PER_HOP_TIMEOUT
        }
    }

    /**
     * Calculate extra timeout for link proofs based on interface characteristics.
     *
     * @param interfaceRef The interface to calculate timeout for
     * @return Extra timeout in milliseconds
     */
    fun extraLinkProofTimeout(interfaceRef: InterfaceRef?): Long {
        if (interfaceRef == null) return 0L
        val bitrate = interfaceRef.bitrate
        if (bitrate <= 0) return 0L

        // Calculate time to transmit MTU bytes: (1/bitrate) * 8 * MTU
        val perBitLatency = 1.0 / bitrate
        val timeSeconds = perBitLatency * 8 * RnsConstants.MTU
        return (timeSeconds * 1000).toLong()
    }

    // ===== Announce Queue Management =====

    /**
     * Drop all pending announces (for cleanup).
     */
    fun dropAnnounceQueues() {
        queuedAnnounces.clear()
        announceRateTable.clear()
        announceAllowedAt.clear()
        interfaceAnnounceQueues.clear()
        interfaceAnnounceAllowedAt.clear()
        log("Dropped all announce queues")
    }

    /**
     * Queue an announce for transmission on a specific interface.
     *
     * @param destinationHash The destination being announced
     * @param raw The raw announce packet
     * @param interfaceRef The interface to queue the announce on
     * @param hops Current hop count
     * @param emitted When the announce was originally emitted
     * @return true if queued successfully, false if queue is full
     */
    fun queueAnnounce(
        destinationHash: ByteArray,
        raw: ByteArray,
        interfaceRef: InterfaceRef,
        hops: Int,
        emitted: Long
    ): Boolean {
        val ifaceKey = interfaceRef.hash.toKey()
        val queue = interfaceAnnounceQueues.getOrPut(ifaceKey) { mutableListOf() }

        // Check queue size limit
        if (queue.size >= TransportConstants.MAX_QUEUED_ANNOUNCES) {
            log("Announce queue full on ${interfaceRef.name}, dropping announce")
            return false
        }

        // Check if already queued
        val existing = queue.find { it.destinationHash.contentEquals(destinationHash) }

        if (existing != null) {
            // Update if this is newer
            if (emitted > existing.emitted) {
                queue.remove(existing)
            } else {
                // Already queued with same or newer announce
                return false
            }
        }

        // Calculate queue time
        val now = System.currentTimeMillis()
        val graceMs = TransportConstants.PATH_REQUEST_GRACE
        val randomMs = (Math.random() * TransportConstants.PATHFINDER_RW * 1000).toLong()
        val queueTime = now + graceMs + randomMs

        // Create queued announce
        val queued = QueuedAnnounce(
            destinationHash = destinationHash.copyOf(),
            time = queueTime,
            hops = hops,
            emitted = emitted,
            raw = raw.copyOf()
        )

        queue.add(queued)

        // Schedule processing if this is the first item
        if (queue.size == 1) {
            scheduleAnnounceQueueProcessing(interfaceRef)
        }

        log("Queued announce for ${destinationHash.toHexString()} on ${interfaceRef.name} (queue size: ${queue.size})")
        return true
    }

    /**
     * Schedule processing of the announce queue for an interface.
     */
    private fun scheduleAnnounceQueueProcessing(interfaceRef: InterfaceRef) {
        val ifaceKey = interfaceRef.hash.toKey()
        val allowedAt = interfaceAnnounceAllowedAt[ifaceKey] ?: 0L
        val now = System.currentTimeMillis()
        val waitTime = maxOf(allowedAt - now, 0L)

        thread(name = "AnnounceQueue-${interfaceRef.name}", isDaemon = true) {
            Thread.sleep(waitTime)
            processAnnounceQueue(interfaceRef)
        }
    }

    /**
     * Process queued announces for a specific interface.
     *
     * Removes stale announces, selects the best announce to send based on hop count,
     * and respects bandwidth limits.
     */
    fun processAnnounceQueue(interfaceRef: InterfaceRef) {
        val ifaceKey = interfaceRef.hash.toKey()
        val queue = interfaceAnnounceQueues[ifaceKey] ?: return

        synchronized(queue) {
            try {
                val now = System.currentTimeMillis()

                // Remove stale announces
                queue.removeAll { now > it.time + TransportConstants.QUEUED_ANNOUNCE_LIFE }

                if (queue.isEmpty()) {
                    return
                }

                // Select announce with minimum hops
                val minHops = queue.minOf { it.hops }
                val candidates = queue.filter { it.hops == minHops }

                // Sort by time and select earliest
                val selected = candidates.minByOrNull { it.time } ?: return

                // Calculate transmission time and wait time based on bitrate
                val bitrate = interfaceRef.bitrate
                val announceCap = TransportConstants.ANNOUNCE_CAP

                val txTime = if (bitrate > 0) {
                    (selected.raw.size * 8.0) / bitrate
                } else {
                    0.0
                }

                val waitTime = if (announceCap > 0) {
                    (txTime / announceCap * 1000).toLong()
                } else {
                    0L
                }

                // Update allowed timestamp
                interfaceAnnounceAllowedAt[ifaceKey] = now + waitTime

                // Transmit the announce
                try {
                    interfaceRef.send(selected.raw)
                    recordTxBytes(interfaceRef, selected.raw.size)
                    recordAnnounceSent(interfaceRef)
                    log("Sent queued announce for ${selected.destinationHash.toHexString()} on ${interfaceRef.name}")
                } catch (e: Exception) {
                    log("Failed to send queued announce on ${interfaceRef.name}: ${e.message}")
                }

                // Remove from queue
                queue.remove(selected)

                // Schedule next processing if queue not empty
                if (queue.isNotEmpty()) {
                    scheduleAnnounceQueueProcessing(interfaceRef)
                }
            } catch (e: Exception) {
                log("Error processing announce queue on ${interfaceRef.name}: ${e.message}")
                queue.clear()
            }
        }
    }

    /**
     * Check if announce is allowed (rate limiting).
     */
    private fun announceAllowed(destinationHash: ByteArray): Boolean {
        val allowedAt = announceAllowedAt[destinationHash.toKey()] ?: return true
        return System.currentTimeMillis() >= allowedAt
    }

    /**
     * Schedule next announce time for a destination.
     */
    private fun scheduleNextAnnounce(destinationHash: ByteArray, delayMs: Long) {
        announceAllowedAt[destinationHash.toKey()] = System.currentTimeMillis() + delayMs
    }

    // ===== Held Announces =====

    /**
     * Hold an announce for later retransmission.
     */
    fun holdAnnounce(destinationHash: ByteArray, rawData: ByteArray) {
        heldAnnounces[destinationHash.toKey()] = Pair(System.currentTimeMillis(), rawData.copyOf())
        log("Held announce for ${destinationHash.toHexString()}")
    }

    /**
     * Release a held announce for transmission.
     */
    fun releaseHeldAnnounce(destinationHash: ByteArray): ByteArray? {
        val held = heldAnnounces.remove(destinationHash.toKey())
        if (held != null) {
            log("Released held announce for ${destinationHash.toHexString()}")
        }
        return held?.second
    }

    /**
     * Get count of held announces.
     */
    fun heldAnnounceCount(): Int = heldAnnounces.size

    // ===== Traffic Statistics =====

    /**
     * Record transmitted bytes for an interface.
     *
     * @param interfaceRef The interface that transmitted data
     * @param bytes Number of bytes transmitted
     */
    fun recordTxBytes(interfaceRef: InterfaceRef, bytes: Int) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.txBytes += bytes
        stats.txPackets++
        stats.lastActivity = System.currentTimeMillis()
    }

    /**
     * Record received bytes for an interface.
     *
     * @param interfaceRef The interface that received data
     * @param bytes Number of bytes received
     */
    fun recordRxBytes(interfaceRef: InterfaceRef, bytes: Int) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.rxBytes += bytes
        stats.rxPackets++
        stats.lastActivity = System.currentTimeMillis()
    }

    /**
     * Record that an announce was sent on an interface.
     *
     * @param interfaceRef The interface that sent the announce
     */
    fun recordAnnounceSent(interfaceRef: InterfaceRef) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.announcesSent++
    }

    /**
     * Get traffic statistics for an interface.
     *
     * @param interfaceRef The interface to get stats for
     * @return Traffic stats or null if no data recorded
     */
    fun getInterfaceStats(interfaceRef: InterfaceRef): InterfaceTrafficStats? {
        return interfaceStats[interfaceRef.hash.toKey()]
    }

    /**
     * Get all interfaces prioritized by capacity and current load.
     * Higher capacity and lower recent traffic = higher priority.
     *
     * @return List of interfaces sorted by priority (highest first)
     */
    fun prioritizeInterfaces(): List<InterfaceRef> {
        return interfaces.sortedByDescending { interfaceRef ->
            val stats = interfaceStats[interfaceRef.hash.toKey()]
            val bitrate = interfaceRef.bitrate.toLong()
            val recentTx = stats?.txBytes ?: 0L

            // Higher bitrate and lower recent tx = higher priority
            if (bitrate > 0) {
                bitrate.toDouble() / (recentTx + 1)
            } else {
                // Interfaces without bitrate info get lower priority
                1.0 / (recentTx + 1)
            }
        }
    }

    /**
     * Get active interfaces (online and can send).
     *
     * @return List of active interfaces
     */
    fun getActiveInterfaces(): List<InterfaceRef> {
        return interfaces.filter { it.online && it.canSend }
    }

    // ===== Local Client Support =====

    /**
     * Register a local client interface.
     */
    fun registerLocalClientInterface(interfaceRef: InterfaceRef) {
        localClientInterfaces.add(interfaceRef)
        interfaces.add(interfaceRef)
        log("Registered local client interface: ${interfaceRef.name}")
    }

    /**
     * Deregister a local client interface.
     */
    fun deregisterLocalClientInterface(interfaceRef: InterfaceRef) {
        localClientInterfaces.remove(interfaceRef)
        interfaces.remove(interfaceRef)
        log("Deregistered local client interface: ${interfaceRef.name}")
    }

    /**
     * Check if interface is a local client.
     */
    fun isLocalClientInterface(interfaceRef: InterfaceRef): Boolean {
        return localClientInterfaces.any { it.hash.contentEquals(interfaceRef.hash) }
    }

    /**
     * Check if packet came from a local client.
     */
    fun fromLocalClient(interfaceRef: InterfaceRef): Boolean {
        return isLocalClientInterface(interfaceRef)
    }

    /**
     * Handle shared connection disappearing.
     */
    fun sharedConnectionDisappeared() {
        localClientInterfaces.clear()
        log("Shared connection disappeared, cleared local clients")
    }

    /**
     * Handle shared connection reappearing.
     */
    fun sharedConnectionReappeared() {
        // Re-announce local destinations
        for (dest in destinations) {
            if (dest.direction == DestinationDirection.IN) {
                val announcePacket = Packet.createAnnounce(dest)
                if (announcePacket != null) {
                    outbound(announcePacket)
                }
            }
        }
        log("Shared connection reappeared, re-announced destinations")
    }

    /**
     * Get count of local client interfaces.
     */
    fun localClientCount(): Int = localClientInterfaces.size

    // ===== Persistence =====

    /**
     * Save path table to a file.
     *
     * Format: one line per entry, fields separated by |
     * destHash|nextHop|hops|expires|interfaceHash|announceHash|state|failureCount
     */
    fun savePathTable(file: java.io.File) {
        try {
            val lines = pathTable.entries.mapNotNull { (key, entry) ->
                if (entry.isExpired()) return@mapNotNull null
                listOf(
                    key.toString(),
                    entry.nextHop.toHexString(),
                    entry.hops.toString(),
                    entry.expires.toString(),
                    entry.receivingInterfaceHash.toHexString(),
                    entry.announcePacketHash.toHexString(),
                    entry.state.ordinal.toString(),
                    entry.failureCount.toString()
                ).joinToString("|")
            }
            file.writeText(lines.joinToString("\n"))
            log("Saved ${lines.size} path entries to ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to save path table: ${e.message}")
        }
    }

    /**
     * Load path table from a file.
     */
    fun loadPathTable(file: java.io.File) {
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            var loaded = 0
            for (line in lines) {
                val parts = line.split("|")
                if (parts.size < 8) continue

                try {
                    val destHash = hexToBytes(parts[0])
                    val nextHop = hexToBytes(parts[1])
                    val hops = parts[2].toInt()
                    val expires = parts[3].toLong()
                    val interfaceHash = hexToBytes(parts[4])
                    val announceHash = hexToBytes(parts[5])
                    val stateOrdinal = parts[6].toInt()
                    val failureCount = parts[7].toInt()

                    // Skip expired entries
                    if (System.currentTimeMillis() > expires) continue

                    val entry = PathEntry(
                        timestamp = System.currentTimeMillis(),
                        nextHop = nextHop,
                        hops = hops,
                        expires = expires,
                        randomBlobs = mutableListOf(),
                        receivingInterfaceHash = interfaceHash,
                        announcePacketHash = announceHash,
                        state = PathState.entries.getOrElse(stateOrdinal) { PathState.ACTIVE },
                        failureCount = failureCount
                    )
                    pathTable[destHash.toKey()] = entry
                    loaded++
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            log("Loaded $loaded path entries from ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to load path table: ${e.message}")
        }
    }

    /**
     * Save packet hashlist to a file.
     *
     * Format: one hash per line, hex-encoded
     */
    fun savePacketHashlist(file: java.io.File) {
        try {
            val hashes = (packetHashlist + packetHashlistPrev).map { it.toString() }
            file.writeText(hashes.joinToString("\n"))
            log("Saved ${hashes.size} packet hashes to ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to save packet hashlist: ${e.message}")
        }
    }

    /**
     * Load packet hashlist from a file.
     */
    fun loadPacketHashlist(file: java.io.File) {
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            var loaded = 0
            for (line in lines) {
                try {
                    val hash = hexToBytes(line.trim())
                    packetHashlist.add(hash.toKey())
                    loaded++
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            log("Loaded $loaded packet hashes from ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to load packet hashlist: ${e.message}")
        }
    }

    /**
     * Persist all transport data to a directory.
     *
     * @param directory Directory to save data to (created if needed)
     */
    fun persistData(directory: java.io.File) {
        directory.mkdirs()
        savePathTable(java.io.File(directory, "path_table.txt"))
        savePacketHashlist(java.io.File(directory, "packet_hashlist.txt"))
    }

    /**
     * Load all transport data from a directory.
     *
     * @param directory Directory to load data from
     */
    fun loadPersistedData(directory: java.io.File) {
        if (!directory.exists()) return
        loadPathTable(java.io.File(directory, "path_table.txt"))
        loadPacketHashlist(java.io.File(directory, "packet_hashlist.txt"))
    }

    // ===== Packet Caching =====

    /**
     * Determine if a packet should be cached.
     * Currently returns false (caching disabled by default, matching Python RNS).
     */
    fun shouldCache(@Suppress("UNUSED_PARAMETER") packet: Packet): Boolean {
        // TODO: Implement caching policy (e.g., for Resource proofs)
        // Currently disabled to match Python RNS behavior
        return false
    }

    /**
     * Cache a packet for later retrieval.
     *
     * @param packet The packet to cache
     * @param forceCache Force caching even if shouldCache returns false
     * @param receivingInterface The interface that received the packet
     */
    fun cache(packet: Packet, forceCache: Boolean = false, receivingInterface: InterfaceRef? = null) {
        if (!forceCache && !shouldCache(packet)) return

        val raw = packet.raw ?: return
        val hash = packet.packetHash

        packetCache[hash.toKey()] = CachedPacket(
            raw = raw.copyOf(),
            timestamp = System.currentTimeMillis(),
            receivingInterfaceHash = receivingInterface?.hash
        )
    }

    /**
     * Retrieve a cached packet by hash.
     *
     * @param packetHash The packet hash to look up
     * @return The cached packet or null if not found/expired
     */
    fun getCachedPacket(packetHash: ByteArray): CachedPacket? {
        val cached = packetCache[packetHash.toKey()] ?: return null

        if (cached.isExpired()) {
            packetCache.remove(packetHash.toKey())
            return null
        }

        return cached
    }

    /**
     * Handle a cache request by re-injecting a cached packet.
     *
     * @param packetHash The requested packet hash
     * @param requestingInterface The interface requesting the packet
     * @return True if packet was found and re-injected
     */
    fun cacheRequest(packetHash: ByteArray, requestingInterface: InterfaceRef): Boolean {
        val cached = getCachedPacket(packetHash) ?: return false

        // Re-inject the cached packet
        inbound(cached.raw, requestingInterface)
        return true
    }

    /**
     * Clean expired packets from the cache.
     */
    fun cleanCache() {
        val now = System.currentTimeMillis()
        if (now - cacheLastCleaned < TransportConstants.CACHE_CLEAN_INTERVAL) return

        var removed = 0
        val iterator = packetCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
                removed++
            }
        }

        if (removed > 0) {
            log("Cleaned $removed expired packets from cache")
        }

        cacheLastCleaned = now
    }

    /**
     * Graceful shutdown - persist data before stopping.
     *
     * @param dataDirectory Directory to save data to
     */
    fun shutdown(dataDirectory: java.io.File? = null) {
        if (!started.get()) return

        if (dataDirectory != null) {
            persistData(dataDirectory)
        }

        stop()
    }

    /**
     * Convert hex string to bytes.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                          Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
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
        if (!started.get()) {
            log("Transport not started, dropping packet")
            return
        }
        if (raw.size < RnsConstants.HEADER_MIN_SIZE) {
            log("Packet too small (${raw.size} < ${RnsConstants.HEADER_MIN_SIZE}), dropping")
            return
        }

        // Handle IFAC unmasking if interface has IFAC enabled
        val processedRaw = if (interfaceRef.ifacIdentity != null && interfaceRef.ifacSize > 0) {
            val unmasked = removeIfacMasking(raw, interfaceRef)
            if (unmasked == null) {
                // IFAC authentication failed, drop packet silently
                return
            }
            unmasked
        } else {
            // No IFAC on interface - check if packet has IFAC flag set (shouldn't)
            if (raw[0].toInt() and 0x80 == 0x80) {
                // Drop packets with IFAC flag on non-IFAC interface
                return
            }
            raw
        }

        jobsLock.withLock {
            try {
                processInbound(processedRaw, interfaceRef)
            } catch (e: Exception) {
                log("Error processing inbound packet: ${e.message}")
            }
        }
    }

    /**
     * Remove IFAC (Interface Access Code) masking from a packet.
     * Returns null if authentication fails.
     */
    private fun removeIfacMasking(raw: ByteArray, interfaceRef: InterfaceRef): ByteArray? {
        val ifacIdentity = interfaceRef.ifacIdentity ?: return raw
        val ifacKey = interfaceRef.ifacKey ?: return raw
        val ifacSize = interfaceRef.ifacSize
        if (ifacSize <= 0) return raw

        // Check if IFAC flag is set
        if (raw[0].toInt() and 0x80 != 0x80) {
            // IFAC flag not set but should be - drop packet
            return null
        }

        // Check packet length
        if (raw.size <= 2 + ifacSize) {
            return null
        }

        // Extract IFAC from bytes 2 to 2+ifacSize
        val ifac = raw.copyOfRange(2, 2 + ifacSize)

        // Generate mask using HKDF
        val mask = crypto.hkdf(
            length = raw.size,
            ikm = ifac,
            salt = ifacKey,
            info = null
        )

        // Unmask the payload
        val unmaskedRaw = ByteArray(raw.size)
        for (i in raw.indices) {
            unmaskedRaw[i] = if (i <= 1 || i > ifacSize + 1) {
                // Unmask header bytes and payload (after IFAC)
                (raw[i].toInt() xor mask[i].toInt()).toByte()
            } else {
                // Don't unmask IFAC itself
                raw[i]
            }
        }

        // Clear IFAC flag
        val newHeader = byteArrayOf(
            (unmaskedRaw[0].toInt() and 0x7F).toByte(),
            unmaskedRaw[1]
        )

        // Re-assemble packet without IFAC
        val newRaw = newHeader + unmaskedRaw.copyOfRange(2 + ifacSize, unmaskedRaw.size)

        // Calculate expected IFAC
        val signature = ifacIdentity.sign(newRaw)
        val expectedIfac = signature.copyOfRange(signature.size - ifacSize, signature.size)

        // Verify authentication
        if (!ifac.contentEquals(expectedIfac)) {
            return null
        }

        return newRaw
    }

    private fun processInbound(raw: ByteArray, interfaceRef: InterfaceRef) {
        // Parse the packet
        val packet = Packet.unpack(raw)
        if (packet == null) {
            log("Failed to parse packet (${raw.size} bytes)")
            return
        }

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
        recordRxBytes(interfaceRef, raw.size)

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
        val destHex = packet.destinationHash.toHexString()

        // Check if we have a known path
        val pathEntry = pathTable[packet.destinationHash.toKey()]

        // Use path routing when we have a valid, unexpired path.
        // For DATA packets with multi-hop paths, we still broadcast since HEADER_2 transport
        // routing has issues with nextHop. But for 1-hop (direct) paths, we use path routing
        // to avoid duplicate sends across multiple interfaces.
        val usePathRouting = pathEntry != null && !pathEntry.isExpired() &&
            packet.packetType != PacketType.ANNOUNCE &&
            packet.destinationType != DestinationType.PLAIN &&
            packet.destinationType != DestinationType.GROUP

        // For DATA packets, only use path routing for direct (1-hop) connections
        // Multi-hop DATA packets still need broadcasting until transport routing is fixed
        val effectiveUsePathRouting = if (packet.packetType == PacketType.DATA && pathEntry != null) {
            usePathRouting && pathEntry.hops == 1
        } else {
            usePathRouting
        }

        if (effectiveUsePathRouting) {
            // We have a path - use it
            val outboundInterface = findInterfaceByHash(pathEntry!!.receivingInterfaceHash)
            if (outboundInterface != null) {
                log("Sending to $destHex via path (${pathEntry.hops} hops) on ${outboundInterface.name}")
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
            } else {
                log("Path exists for $destHex but interface not found")
            }
        }

        if (!sent) {
            // Broadcast on all interfaces
            addPacketHash(packet.packetHash)
            log("Broadcasting to $destHex on all interfaces (${packedData.size} bytes)")

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
     * Applies IFAC masking if the interface has IFAC enabled.
     */
    private fun transmit(interfaceRef: InterfaceRef, data: ByteArray) {
        try {
            val transmitData = if (interfaceRef.ifacIdentity != null && interfaceRef.ifacSize > 0) {
                applyIfacMasking(data, interfaceRef)
            } else {
                data
            }
            interfaceRef.send(transmitData)
            trafficTxBytes += transmitData.size
            recordTxBytes(interfaceRef, transmitData.size)
        } catch (e: Exception) {
            log("Transmit error on ${interfaceRef.name}: ${e.message}")
        }
    }

    /**
     * Apply IFAC (Interface Access Code) masking to a packet.
     * This authenticates the packet for the specific interface.
     */
    private fun applyIfacMasking(raw: ByteArray, interfaceRef: InterfaceRef): ByteArray {
        val ifacIdentity = interfaceRef.ifacIdentity ?: return raw
        val ifacKey = interfaceRef.ifacKey ?: return raw
        val ifacSize = interfaceRef.ifacSize
        if (ifacSize <= 0) return raw

        // Calculate packet access code by signing and taking last ifacSize bytes
        val signature = ifacIdentity.sign(raw)
        val ifac = signature.copyOfRange(signature.size - ifacSize, signature.size)

        // Generate mask using HKDF
        val mask = crypto.hkdf(
            length = raw.size + ifacSize,
            ikm = ifac,
            salt = ifacKey,
            info = null
        )

        // Set IFAC flag in header (bit 7)
        val newHeader = byteArrayOf(
            (raw[0].toInt() or 0x80).toByte(),
            raw[1]
        )

        // Assemble new payload: header + ifac + rest of packet
        val newRaw = newHeader + ifac + raw.copyOfRange(2, raw.size)

        // Mask the payload
        val maskedRaw = ByteArray(newRaw.size)
        for (i in newRaw.indices) {
            maskedRaw[i] = when {
                i == 0 -> {
                    // Mask first header byte but keep IFAC flag set
                    ((newRaw[i].toInt() xor mask[i].toInt()) or 0x80).toByte()
                }
                i == 1 || i > ifacSize + 1 -> {
                    // Mask second header byte and payload (after IFAC)
                    (newRaw[i].toInt() xor mask[i].toInt()).toByte()
                }
                else -> {
                    // Don't mask the IFAC itself
                    newRaw[i]
                }
            }
        }

        return maskedRaw
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
        val announceData = validateAnnounce(packet)
        if (announceData == null) {
            log("Announce validation failed for ${packet.destinationHash.toHexString()}")
            return
        }

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

        // Store the identity for later recall
        Identity.remember(
            packetHash = packet.packetHash,
            destHash = destHash,
            publicKey = identity.getPublicKey(),
            appData = appData
        )

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

        // Store in announce table for potential path request responses
        val announceEntry = AnnounceEntry(
            destinationHash = destinationHash.copyOf(),
            timestamp = now,
            retransmits = 0,
            retransmitTimeout = now,
            raw = packet.raw ?: packet.pack(),
            hops = packet.hops,
            receivingInterfaceHash = receivingInterface.hash,
            localRebroadcasts = 0
        )
        announceTable[destKey] = announceEntry

        // Queue on all interfaces except the receiving one
        for (iface in interfaces) {
            if (iface.canSend && iface.online &&
                !iface.hash.contentEquals(receivingInterface.hash)) {
                queueAnnounce(
                    destinationHash = destinationHash,
                    raw = announceEntry.raw,
                    interfaceRef = iface,
                    hops = packet.hops,
                    emitted = now
                )
            }
        }
    }

    private fun processData(packet: Packet, interfaceRef: InterfaceRef) {
        log("processData: dest=${packet.destinationHash.toHexString()}, ${packet.data.size} bytes from ${interfaceRef.name}")

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
        log("processData: findDestination result = ${destination?.hexHash ?: "null"}")

        if (destination != null) {
            // Deliver locally
            deliverPacket(destination, packet)
            return
        }

        // Check if this is data for a local link (destination hash is a link_id)
        val localLink = findLink(packet.destinationHash)
        if (localLink != null) {
            log("Delivering link data to local link ${packet.destinationHash.toHexString()}")
            try {
                val receiveMethod = localLink::class.java.getMethod("receive", Packet::class.java)
                receiveMethod.invoke(localLink, packet)
            } catch (e: Exception) {
                log("Failed to deliver to local link: ${e.message}")
            }
            return
        }

        // Check if we have a path to forward this packet
        val pathEntry = pathTable[packet.destinationHash.toKey()]
        if (pathEntry != null) {
            val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
            if (outboundInterface != null && !outboundInterface.hash.contentEquals(interfaceRef.hash)) {
                log("Forwarding data packet for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}")
                transmit(outboundInterface, packet.raw ?: packet.pack())
                return
            }
        }

        // Check if this is link data for forwarding (destination hash is a link_id)
        val linkEntry = linkTable[packet.destinationHash.toKey()]
        if (linkEntry != null) {
            // This is link data - forward to the appropriate interface based on which
            // interface the packet came from (bidirectional routing)
            val outboundInterfaceHash = if (interfaceRef.hash.contentEquals(linkEntry.receivingInterfaceHash)) {
                // Packet came from the link initiator side, forward to the destination side
                linkEntry.nextHopInterfaceHash
            } else {
                // Packet came from the destination side, forward to the initiator side
                linkEntry.receivingInterfaceHash
            }

            val outboundInterface = findInterfaceByHash(outboundInterfaceHash)
            if (outboundInterface != null) {
                log("Forwarding link data for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}")
                transmit(outboundInterface, packet.raw ?: packet.pack())
                return
            } else {
                log("Link data forward failed: interface ${outboundInterfaceHash.toHexString()} not found")
            }
        }

        // Transport-mode forwarding (when we're an intermediate hop)
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

        // Check if we have a path to forward this Link request
        val pathEntry = pathTable[packet.destinationHash.toKey()]
        if (pathEntry != null) {
            val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
            if (outboundInterface != null && !outboundInterface.hash.contentEquals(interfaceRef.hash)) {
                // Calculate link_id properly using Link.linkIdFromLrPacket
                // This is the truncated hash of the hashable part, potentially
                // excluding MTU signalling bytes from the data
                val linkId = Link.linkIdFromLrPacket(packet)

                // Create link table entry for return routing of proofs (for local client traffic)
                // The LRPROOF will have this link_id as its destination_hash
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
                linkTable[linkId.toKey()] = linkEntry
                log("Forwarding Link request for ${packet.destinationHash.toHexString()} via ${outboundInterface.name} (link_id=${linkId.toHexString()})")

                // Forward the packet
                transmit(outboundInterface, packet.raw ?: packet.pack())
                return
            }
        }

        // Transport-mode forwarding (when we're an intermediate hop)
        if (transportEnabled && packet.transportId != null) {
            val myHash = identity?.hash
            if (myHash != null && packet.transportId.contentEquals(myHash)) {
                // Record in link table for return path
                if (pathEntry != null) {
                    // Use proper link_id calculation
                    val linkId = Link.linkIdFromLrPacket(packet)
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
                    linkTable[linkId.toKey()] = linkEntry
                    log("Created link table entry for ${linkId.toHexString()}")

                    forwardPacket(packet, interfaceRef)
                }
            }
        }
    }

    private fun processProof(packet: Packet, interfaceRef: InterfaceRef) {
        log("Processing proof: dest=${packet.destinationHash.toHexString()}, context=${packet.context}, from ${interfaceRef.name}")

        // Handle LRPROOF (Link Request Proof) - look up in link table
        if (packet.context == PacketContext.LRPROOF) {
            val linkEntry = linkTable[packet.destinationHash.toKey()]
            if (linkEntry != null) {
                // Forward proof back to the originating interface
                val outboundInterface = findInterfaceByHash(linkEntry.receivingInterfaceHash)
                if (outboundInterface != null) {
                    log("Forwarding LRPROOF for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}")
                    transmit(outboundInterface, packet.raw ?: packet.pack())
                }
                // Mark link as validated
                linkEntry.validated = true
                return
            } else {
                // Debug: show what's in the link table
                if (linkTable.isNotEmpty()) {
                    val keys = linkTable.keys.take(3).map { it.toString().take(16) + "..." }
                    log("LRPROOF dest=${packet.destinationHash.toHexString()} not found in link_table. Keys: $keys")
                } else {
                    log("LRPROOF dest=${packet.destinationHash.toHexString()} not found. Link table is empty")
                }
            }
        }

        // For other proof types, try reverse table
        var reverseEntry = reverseTable[packet.destinationHash.toKey()]
        if (reverseEntry == null) {
            reverseEntry = reverseTable[packet.truncatedHash.toKey()]
        }

        if (reverseEntry != null) {
            // Forward proof back to the originating interface
            val outboundInterface = findInterfaceByHash(reverseEntry.receivingInterfaceHash)
            if (outboundInterface != null) {
                log("Forwarding proof for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}")
                transmit(outboundInterface, packet.raw ?: packet.pack())
            }
            // Remove the reverse entry after use
            reverseTable.remove(packet.destinationHash.toKey())
            reverseTable.remove(packet.truncatedHash.toKey())
            return
        }

        // Check if there's a pending receipt for this proof (local destination)
        val receiptKey = packet.destinationHash.toKey()
        val callback = pendingReceipts.remove(receiptKey)

        if (callback != null) {
            try {
                val validated = callback.onProofReceived(packet)
                if (validated) {
                    log("Proof validated for ${packet.destinationHash.toHexString()}")
                    markPathResponsive(packet.destinationHash)
                } else {
                    log("Proof validation failed for ${packet.destinationHash.toHexString()}")
                }
            } catch (e: Exception) {
                log("Proof callback error: ${e.message}")
            }
        } else {
            log("Proof dest=${packet.destinationHash.toHexString()} not found in link_table (${linkTable.size} entries) or reverse_table (${reverseTable.size} entries)")
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

                    // Validate and create the incoming link
                    val link = Link.validateRequest(destination, packet.data, packet)
                    if (link != null) {
                        log("Link request for ${destination.hexHash} accepted: ${link.linkId.toHexString()}")
                        // Invoke the destination's link established callback
                        destination.invokeLinkEstablished(link)
                    } else {
                        log("Link request for ${destination.hexHash} rejected (validation failed)")
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
        if (data.size < RnsConstants.ANNOUNCE_MIN_SIZE) {
            log("Announce too small: ${data.size} < ${RnsConstants.ANNOUNCE_MIN_SIZE}")
            return null
        }

        // Announce format:
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [signature: 64] [app_data: variable]
        // Or with ratchet (when context_flag is SET):
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [ratchet: 32] [signature: 64] [app_data: variable]

        val keySize = RnsConstants.IDENTITY_PUBLIC_KEY_SIZE // 64
        val nameHashLen = 10
        val randomHashLen = 10
        val ratchetSize = 32
        val sigLen = 64

        val publicKey = data.copyOfRange(0, keySize)
        val nameHash = data.copyOfRange(keySize, keySize + nameHashLen)
        val randomHash = data.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + randomHashLen)

        // Determine if ratchet is present based on context flag
        val hasRatchet = packet.contextFlag == ContextFlag.SET

        val ratchet: ByteArray
        val signature: ByteArray
        val appData: ByteArray?

        if (hasRatchet) {
            val ratchetStart = keySize + nameHashLen + randomHashLen
            val ratchetEnd = ratchetStart + ratchetSize
            val sigEnd = ratchetEnd + sigLen

            if (data.size < sigEnd) {
                log("Announce data too short for ratchet+signature: ${data.size} < $sigEnd")
                return null
            }

            ratchet = data.copyOfRange(ratchetStart, ratchetEnd)
            signature = data.copyOfRange(ratchetEnd, sigEnd)
            appData = if (data.size > sigEnd) data.copyOfRange(sigEnd, data.size) else null
        } else {
            ratchet = ByteArray(0)
            val sigStart = keySize + nameHashLen + randomHashLen
            val sigEnd = sigStart + sigLen

            if (data.size < sigEnd) {
                log("Announce data too short for signature: ${data.size} < $sigEnd")
                return null
            }

            signature = data.copyOfRange(sigStart, sigEnd)
            appData = if (data.size > sigEnd) data.copyOfRange(sigEnd, data.size) else null
        }

        // Create identity from public key
        val identity = try {
            Identity.fromPublicKey(publicKey)
        } catch (e: Exception) {
            log("Failed to create identity from public key: ${e.message}")
            return null
        }

        // Verify destination hash matches
        val computedDestHash = Destination.computeHash(nameHash, identity.hash)
        if (!computedDestHash.contentEquals(packet.destinationHash)) {
            log("Destination hash mismatch: computed=${computedDestHash.toHexString()}, packet=${packet.destinationHash.toHexString()}")
            return null
        }

        // Build signed data: destination_hash + public_key + name_hash + random_hash + ratchet + app_data
        // IMPORTANT: The destination_hash from packet header is included in signed data!
        val signedData = packet.destinationHash + publicKey + nameHash + randomHash + ratchet + (appData ?: ByteArray(0))

        if (!identity.validate(signature, signedData)) {
            log("Signature validation failed")
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

        // Process held announces (expire old ones)
        processHeldAnnounces(now)

        // Update traffic speed
        updateTrafficSpeed(now)

        // Check receipt timeouts
        if (now - receiptsLastChecked > TransportConstants.RECEIPTS_CHECK_INTERVAL) {
            checkReceiptTimeouts()
            receiptsLastChecked = now
        }

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
     * Check receipt timeouts and cull old receipts.
     * Matches Python RNS behavior.
     */
    private fun checkReceiptTimeouts() {
        // Cull excess receipts (oldest first)
        while (receipts.size > TransportConstants.MAX_RECEIPTS) {
            val culled = receipts.removeAt(0)
            // Force timeout with CULLED status
            culled.checkTimeout()
        }

        // Check all receipts for timeout
        val toRemove = mutableListOf<PacketReceipt>()
        for (receipt in receipts) {
            receipt.checkTimeout()
            if (receipt.status != PacketReceipt.SENT) {
                toRemove.add(receipt)
            }
        }
        receipts.removeAll(toRemove)
    }

    /**
     * Process held announces - expire old ones.
     */
    private fun processHeldAnnounces(now: Long) {
        val expireCutoff = now - TransportConstants.HELD_ANNOUNCE_TIMEOUT
        val expired = heldAnnounces.entries.filter { it.value.first < expireCutoff }
        expired.forEach { heldAnnounces.remove(it.key) }
    }

    /**
     * Update traffic speed calculations.
     */
    private fun updateTrafficSpeed(now: Long) {
        val elapsed = now - lastTrafficTime

        if (elapsed >= TransportConstants.SPEED_UPDATE_INTERVAL) {
            val rxDelta = trafficRxBytes - lastTrafficSnapshot.first
            val txDelta = trafficTxBytes - lastTrafficSnapshot.second

            speedRx = if (elapsed > 0) (rxDelta * 1000) / elapsed else 0
            speedTx = if (elapsed > 0) (txDelta * 1000) / elapsed else 0

            lastTrafficSnapshot = Pair(trafficRxBytes, trafficTxBytes)
            lastTrafficTime = now
        }
    }

    /**
     * Process queued announces that are ready for retransmission.
     * This is now handled per-interface, but we keep this for backwards compatibility
     * with the old global queue (queuedAnnounces).
     */
    private fun processQueuedAnnounces(@Suppress("UNUSED_PARAMETER") now: Long) {
        // The old global queue is no longer used - announces are now queued per-interface
        // and processed asynchronously via scheduleAnnounceQueueProcessing()
        // This method is kept empty for compatibility with the job loop
    }

    private fun cullTables() {
        val now = System.currentTimeMillis()

        // Remove expired path entries
        pathTable.entries.removeIf { it.value.isExpired() }

        // Remove expired reverse entries
        reverseTable.entries.removeIf { it.value.isExpired() }

        // Remove unvalidated link entries that have timed out
        // Validated entries are kept longer (until general expiry)
        linkTable.entries.removeIf { entry ->
            val linkEntry = entry.value
            if (!linkEntry.validated && now > linkEntry.proofTimeout) {
                log("Removing unvalidated link entry: ${entry.key}")
                true
            } else if (linkEntry.validated &&
                       now - linkEntry.timestamp > TransportConstants.LINK_TIMEOUT) {
                // Also clean up old validated entries
                true
            } else {
                false
            }
        }

        // Remove expired tunnels
        cleanExpiredTunnels()
    }

    // ===== Tunnel Management =====

    /**
     * Synthesize a virtual tunnel through an interface.
     * Used for creating persistent paths through the network.
     *
     * @param interface_ The interface to create the tunnel on
     * @return Tunnel ID, or null if maximum tunnels reached
     */
    fun synthesizeTunnel(interface_: InterfaceRef): ByteArray? {
        if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
            cleanExpiredTunnels()
            if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
                log("Maximum tunnels reached, cannot create new tunnel")
                return null
            }
        }

        // Generate unique tunnel ID
        val tunnelId = Hashes.getRandomHash()
        val key = ByteArrayKey(tunnelId)

        // Create tunnel info
        val tunnel = TunnelInfo(
            tunnelId = tunnelId,
            interface_ = interface_
        )

        tunnels[key] = tunnel
        tunnelInterfaces[key] = interface_

        log("Synthesized tunnel ${tunnelId.toHexString()} on ${interface_.name}")

        return tunnelId
    }

    /**
     * Handle incoming tunnel synthesis requests.
     *
     * @param data Packet data containing tunnel synthesis information
     * @param packet The packet containing the request
     * @param receivingInterface The interface that received the packet
     * @return true if tunnel was created successfully
     */
    fun tunnelSynthesizeHandler(data: ByteArray, @Suppress("UNUSED_PARAMETER") packet: Packet, receivingInterface: InterfaceRef): Boolean {
        if (data.size < RnsConstants.TRUNCATED_HASH_BYTES) {
            log("Invalid tunnel synthesis request: data too short")
            return false
        }

        val tunnelId = data.copyOf(RnsConstants.TRUNCATED_HASH_BYTES)
        val interface_ = receivingInterface

        // Create tunnel for this request
        val key = ByteArrayKey(tunnelId)

        if (tunnels.containsKey(key)) {
            // Tunnel already exists, update activity
            tunnels[key]?.lastActivity = System.currentTimeMillis()
            return true
        }

        if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
            cleanExpiredTunnels()
            if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
                log("Cannot create tunnel, max tunnels reached")
                return false
            }
        }

        val tunnel = TunnelInfo(
            tunnelId = tunnelId,
            interface_ = interface_
        )

        tunnels[key] = tunnel
        tunnelInterfaces[key] = interface_

        log("Created tunnel ${tunnelId.toHexString()} from synthesis request")

        return true
    }

    /**
     * Remove a tunnel and its associated interface.
     *
     * @param tunnelId The tunnel ID to void
     * @return true if tunnel was removed
     */
    fun voidTunnelInterface(tunnelId: ByteArray): Boolean {
        val key = ByteArrayKey(tunnelId)

        val removed = tunnels.remove(key)
        tunnelInterfaces.remove(key)

        if (removed != null) {
            log("Voided tunnel ${tunnelId.toHexString()}")
            return true
        }

        return false
    }

    /**
     * Handle packet routing through a tunnel.
     *
     * @param tunnelId The tunnel to route through
     * @param packet The packet data to transmit
     * @return true if transmitted successfully
     */
    fun handleTunnel(tunnelId: ByteArray, packet: ByteArray): Boolean {
        val key = ByteArrayKey(tunnelId)
        val tunnel = tunnels[key] ?: return false

        // Update tunnel activity
        tunnel.lastActivity = System.currentTimeMillis()
        tunnel.rxBytes += packet.size

        // Get the interface for this tunnel
        val interface_ = tunnelInterfaces[key] ?: return false

        // Transmit through tunnel interface
        return try {
            transmit(interface_, packet)
            tunnel.txBytes += packet.size
            true
        } catch (e: Exception) {
            log("Failed to transmit through tunnel: ${e.message}")
            false
        }
    }

    /**
     * Get tunnel info by ID.
     *
     * @param tunnelId The tunnel ID
     * @return TunnelInfo or null if not found
     */
    fun getTunnel(tunnelId: ByteArray): TunnelInfo? {
        return tunnels[ByteArrayKey(tunnelId)]
    }

    /**
     * Check if a tunnel exists.
     *
     * @param tunnelId The tunnel ID to check
     * @return true if tunnel exists
     */
    fun hasTunnel(tunnelId: ByteArray): Boolean {
        return tunnels.containsKey(ByteArrayKey(tunnelId))
    }

    /**
     * Get interface for a tunnel.
     *
     * @param tunnelId The tunnel ID
     * @return InterfaceRef or null if not found
     */
    fun getTunnelInterface(tunnelId: ByteArray): InterfaceRef? {
        return tunnelInterfaces[ByteArrayKey(tunnelId)]
    }

    /**
     * Remove expired tunnels.
     */
    fun cleanExpiredTunnels() {
        val expired = tunnels.filter { it.value.isExpired() }
        for ((key, tunnel) in expired) {
            tunnels.remove(key)
            tunnelInterfaces.remove(key)
            log("Cleaned expired tunnel ${tunnel.tunnelId.toHexString()}")
        }
    }

    // ===== Helpers =====

    private fun findInterfaceByHash(hash: ByteArray): InterfaceRef? {
        val key = hash.toKey()
        return interfaces.find { it.hash.toKey() == key }
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [Transport] $message")
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

    /** Bitrate in bits per second (0 if unknown). */
    val bitrate: Int
        get() = 0

    /** Hardware MTU in bytes. */
    val hwMtu: Int
        get() = RnsConstants.MTU

    // IFAC (Interface Access Code) properties
    /** IFAC size in bytes. 0 means IFAC is disabled. */
    val ifacSize: Int
        get() = 0

    /** IFAC key derived from network name/key. */
    val ifacKey: ByteArray?
        get() = null

    /** IFAC identity for signing packets. */
    val ifacIdentity: network.reticulum.identity.Identity?
        get() = null

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
