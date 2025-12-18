package network.reticulum.transport

import network.reticulum.common.ContextFlag
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
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

        // Start background job thread
        thread(name = "Transport-jobs", isDaemon = true) {
            jobLoop()
        }

        log("Transport started (transport=${if (enableTransport) "enabled" else "disabled"})")
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
     */
    fun markPathUnresponsive(destinationHash: ByteArray) {
        // For now, just expire it
        // TODO: Track path state separately
        expirePath(destinationHash)
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
            // TODO: Queue for retransmission with rate limiting
        }
    }

    private fun processData(packet: Packet, interfaceRef: InterfaceRef) {
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
                    // TODO: Create link table entry
                    forwardPacket(packet, interfaceRef)
                }
            }
        }
    }

    private fun processProof(packet: Packet, interfaceRef: InterfaceRef) {
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

        // Check if it's for a local destination
        // TODO: Receipt handling
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
        // TODO: Decrypt if needed and deliver via callback
        log("Delivering packet to ${destination.hexHash}")
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

    private fun cullTables() {
        val now = System.currentTimeMillis()

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
