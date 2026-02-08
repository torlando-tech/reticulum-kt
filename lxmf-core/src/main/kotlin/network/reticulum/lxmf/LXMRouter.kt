package network.reticulum.lxmf

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * LXMF Message Router.
 *
 * Handles sending and receiving LXMF messages with support for multiple
 * delivery methods: OPPORTUNISTIC, DIRECT, and PROPAGATED.
 *
 * This is a Kotlin port of Python LXMF's LXMRouter class.
 */
class LXMRouter(
    /** Identity for this router (optional, for creating destinations) */
    private val identity: Identity? = null,

    /** Storage path for router data */
    private val storagePath: String? = null,

    /** Whether to automatically peer with propagation nodes */
    private val autopeer: Boolean = true
) {
    // ===== Configuration Constants =====

    companion object {
        /** Maximum delivery attempts before failing */
        const val MAX_DELIVERY_ATTEMPTS = 5

        /** Processing loop interval in milliseconds */
        const val PROCESSING_INTERVAL = 4000L

        /** Wait time between delivery retries in milliseconds */
        const val DELIVERY_RETRY_WAIT = 10000L

        /** Wait time for path discovery in milliseconds */
        const val PATH_REQUEST_WAIT = 7000L

        /** Maximum pathless delivery attempts before requesting path */
        const val MAX_PATHLESS_TRIES = 1

        /** Message expiry time in milliseconds (30 days) */
        const val MESSAGE_EXPIRY = 30L * 24 * 60 * 60 * 1000

        /** Maximum messages in propagation transfer */
        const val PROPAGATION_LIMIT = 256

        /** Maximum messages in delivery transfer */
        const val DELIVERY_LIMIT = 1000

        /** Maximum peering connections */
        const val MAX_PEERS = 20

        /** Default propagation cost */
        const val PROPAGATION_COST = 16

        /** Strict message validation mode */
        const val STRICT = false

        /** LXMF app name */
        const val APP_NAME = "lxmf"

        /** LXMF delivery aspect */
        const val DELIVERY_ASPECT = "delivery"

        /** LXMF propagation aspect */
        const val PROPAGATION_ASPECT = "propagation"
    }

    // ===== Message Queues =====

    /** Pending inbound messages awaiting processing */
    private val pendingInbound = mutableListOf<LXMessage>()
    private val pendingInboundMutex = Mutex()

    /** Pending outbound messages awaiting delivery */
    private val pendingOutbound = mutableListOf<LXMessage>()
    private val pendingOutboundMutex = Mutex()

    /** Failed outbound messages */
    private val failedOutbound = mutableListOf<LXMessage>()
    private val failedOutboundMutex = Mutex()

    // ===== Destinations and Links =====

    /** Registered delivery destinations: hash -> Destination */
    private val deliveryDestinations = ConcurrentHashMap<String, DeliveryDestination>()

    /** Active direct links: destination_hash -> Link */
    private val directLinks = ConcurrentHashMap<String, Link>()

    /** Backchannel links for replies: destination_hash -> Link */
    private val backchannelLinks = ConcurrentHashMap<String, Link>()

    /** Destinations with pending link establishments */
    private val pendingLinkEstablishments = ConcurrentHashMap.newKeySet<String>()

    /** Pending resource transfers: message_hash -> (message, resource) */
    private val pendingResources = ConcurrentHashMap<String, Pair<LXMessage, Resource>>()

    // ===== Propagation Node Tracking =====

    /** Known propagation nodes: destination_hash -> PropagationNode */
    private val propagationNodes = ConcurrentHashMap<String, PropagationNode>()

    /** Active propagation node for outbound messages */
    @Volatile
    private var activePropagationNodeHash: String? = null

    /** Link to active propagation node */
    @Volatile
    private var outboundPropagationLink: Link? = null

    /** Propagation transfer state */
    @Volatile
    var propagationTransferState: PropagationTransferState = PropagationTransferState.IDLE
        private set

    /** Progress of current propagation transfer (0.0 to 1.0) */
    @Volatile
    var propagationTransferProgress: Double = 0.0
        private set

    /** Number of messages retrieved in last transfer */
    @Volatile
    var propagationTransferLastResult: Int = 0
        private set

    // ===== Callbacks =====

    /** Callback for delivered messages */
    private var deliveryCallback: ((LXMessage) -> Unit)? = null

    /** Callback for message delivery failures */
    private var failedDeliveryCallback: ((LXMessage) -> Unit)? = null

    // ===== Processing State =====

    /** Whether the router is running */
    @Volatile
    private var running = false

    /** Coroutine scope for background processing */
    private var processingScope: CoroutineScope? = null

    /** Processing job */
    private var processingJob: Job? = null

    /** Outbound processing mutex */
    private val outboundProcessingMutex = Mutex()

    // ===== Delivery Tracking =====

    /** Locally delivered message transient IDs: hexHash -> timestamp (seconds) */
    private val locallyDeliveredTransientIds = ConcurrentHashMap<String, Long>()

    /** Outbound stamp costs: destination_hash_hex -> [timestamp, cost] */
    private val outboundStampCosts = ConcurrentHashMap<String, Pair<Long, Int>>()

    // ===== Ticket System =====

    /**
     * Available tickets for stamp bypass.
     * Structure mirrors Python's available_tickets dict:
     * - outbound: destHashHex -> Pair(expiresTimestamp, ticketBytes)
     * - inbound: destHashHex -> Map(ticketHex -> expiresTimestamp)
     * - lastDeliveries: destHashHex -> timestamp
     */
    private val outboundTickets = ConcurrentHashMap<String, Pair<Long, ByteArray>>()
    private val inboundTickets = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val lastTicketDeliveries = ConcurrentHashMap<String, Long>()

    // ===== Deferred Stamp Processing =====

    /** Messages awaiting deferred stamp generation: messageIdHex -> LXMessage */
    private val pendingDeferredStamps = ConcurrentHashMap<String, LXMessage>()

    /** Mutex for stamp generation to prevent concurrent CPU-heavy work */
    private val stampGenMutex = Mutex()

    // ===== Access Control =====

    /** Allowed identity hashes (for authentication) */
    private val allowedList = mutableListOf<ByteArray>()

    /** Ignored source destination hashes */
    private val ignoredList = mutableListOf<ByteArray>()

    /** Prioritised destination hashes */
    private val prioritisedList = mutableListOf<ByteArray>()

    /** Whether authentication is required for message delivery */
    private var authRequired: Boolean = false

    // ===== Cleanup Tracking =====

    /** Processing loop counter for scheduling periodic cleanup */
    private var processingCount: Long = 0

    /** File locks for persistence */
    private val costFileMutex = Mutex()
    private val ticketFileMutex = Mutex()
    private val transientIdFileMutex = Mutex()

    // ===== Initialization =====

    init {
        // Create storage directories if needed
        storagePath?.let { path ->
            val dir = File(path, "lxmf")
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        // Load persisted state
        loadOutboundStampCosts()
        loadAvailableTickets()
        loadTransientIds()

        // Register announce handler for propagation nodes
        // Note: Kotlin's AnnounceHandler doesn't have aspect filtering like Python,
        // so we handle all announces and let handlePropagationAnnounce filter by appData format.
        Transport.registerAnnounceHandler { destHash, identity, appData ->
            // Only call handler if this looks like a propagation announce (has appData)
            if (appData != null && appData.isNotEmpty()) {
                handlePropagationAnnounce(destHash, identity, appData)
            }
            false // Don't consume - let other handlers see it too
        }
    }

    /**
     * Data class holding a delivery destination and its configuration.
     */
    data class DeliveryDestination(
        val destination: Destination,
        val identity: Identity,
        val displayName: String? = null,
        var stampCost: Int? = null
    )

    /**
     * Data class representing a discovered propagation node.
     */
    data class PropagationNode(
        /** The destination hash of the propagation node */
        val destHash: ByteArray,
        /** The identity of the propagation node */
        val identity: Identity,
        /** Display name of the node (from metadata) */
        val displayName: String? = null,
        /** Timebase of the node when announced */
        val timebase: Long = 0,
        /** Whether the node is an active propagation node */
        val isActive: Boolean = true,
        /** Per-transfer limit in KB */
        val perTransferLimit: Int = LXMFConstants.PROPAGATION_LIMIT_KB,
        /** Per-sync limit in KB */
        val perSyncLimit: Int = LXMFConstants.SYNC_LIMIT_KB,
        /** Required stamp cost for message delivery */
        val stampCost: Int = LXMFConstants.PROPAGATION_COST,
        /** Stamp cost flexibility */
        val stampCostFlexibility: Int = LXMFConstants.PROPAGATION_COST_FLEX,
        /** Peering cost */
        val peeringCost: Int = LXMFConstants.PEERING_COST,
        /** When this node was last seen (announcement timestamp) */
        val lastSeen: Long = System.currentTimeMillis()
    ) {
        val hexHash: String get() = destHash.toHexString()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PropagationNode) return false
            return destHash.contentEquals(other.destHash)
        }

        override fun hashCode(): Int = destHash.contentHashCode()
    }

    /**
     * Propagation transfer state for tracking sync progress.
     */
    enum class PropagationTransferState {
        IDLE,
        PATH_REQUESTED,
        LINK_ESTABLISHING,
        LINK_ESTABLISHED,
        LISTING_MESSAGES,
        REQUESTING_MESSAGES,
        RECEIVING_MESSAGES,
        COMPLETE,
        FAILED,
        NO_PATH,
        NO_LINK
    }

    // ===== Registration Methods =====

    /**
     * Register a delivery identity for receiving LXMF messages.
     *
     * Creates an RNS Destination with the LXMF delivery aspect and sets up
     * the necessary callbacks for message reception.
     *
     * @param identity The identity to register for delivery
     * @param displayName Optional display name for announcements
     * @param stampCost Optional stamp cost requirement
     * @return The created destination
     */
    fun registerDeliveryIdentity(
        identity: Identity,
        displayName: String? = null,
        stampCost: Int? = null
    ): Destination {
        // Create destination with LXMF delivery aspect
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = APP_NAME,
            DELIVERY_ASPECT
        )

        // Enable ratchets for forward secrecy
        storagePath?.let { path ->
            val ratchetPath = "$path/lxmf/ratchets/${destination.hexHash}"
            destination.enableRatchets(ratchetPath)
        }

        // Set up packet callback for incoming messages
        destination.packetCallback = { data, packet ->
            handleDeliveryPacket(data, destination, packet as? Packet)
        }

        // Set up link established callback
        destination.setLinkEstablishedCallback { link ->
            handleDeliveryLinkEstablished(link as Link, destination)
        }

        // Set default app data for announcements
        if (displayName != null || (stampCost != null && stampCost > 0)) {
            val appData = packAnnounceAppData(displayName, stampCost ?: 0)
            destination.setDefaultAppData(appData)
        }

        // Register with Transport so it can receive packets
        Transport.registerDestination(destination)

        // Store the delivery destination
        val deliveryDest = DeliveryDestination(
            destination = destination,
            identity = identity,
            displayName = displayName,
            stampCost = stampCost
        )
        deliveryDestinations[destination.hexHash] = deliveryDest

        return destination
    }

    /**
     * Register a callback for delivered messages.
     *
     * The callback will be invoked when a message is successfully received
     * and validated.
     *
     * @param callback Function to call with delivered messages
     */
    fun registerDeliveryCallback(callback: (LXMessage) -> Unit) {
        deliveryCallback = callback
    }

    /**
     * Register a callback for failed message deliveries.
     *
     * @param callback Function to call when message delivery fails
     */
    fun registerFailedDeliveryCallback(callback: (LXMessage) -> Unit) {
        failedDeliveryCallback = callback
    }

    // ===== Outbound Message Handling =====

    /**
     * Handle an outbound message by queuing it for delivery.
     *
     * This method prepares the message for sending and adds it to the
     * outbound queue for processing.
     *
     * @param message The message to send
     */
    suspend fun handleOutbound(message: LXMessage) {
        val destHashHex = message.destinationHash.toHexString()

        // Auto-configure stamp cost from outbound_stamp_costs if not set
        if (message.stampCost == null) {
            val costEntry = outboundStampCosts[destHashHex]
            if (costEntry != null) {
                message.stampCost = costEntry.second
                println("[LXMRouter] Auto-configured stamp cost to ${costEntry.second} for $destHashHex")
            }
        }

        // Set message state to outbound
        message.state = MessageState.OUTBOUND

        // Attach outbound ticket if available
        message.outboundTicket = getOutboundTicket(destHashHex)
        if (message.outboundTicket != null && message.deferStamp) {
            // Ticket bypass means no PoW needed — don't defer
            message.deferStamp = false
        }

        // Include ticket for reply if requested
        if (message.includeTicket) {
            val ticket = generateTicket(message.destinationHash)
            if (ticket != null) {
                message.fields[LXMFConstants.FIELD_TICKET] = ticket
            }
        }

        // Pack the message if not already packed
        if (message.packed == null) {
            message.pack()
        }

        // If stamp is deferred and no cost, don't defer
        if (message.deferStamp && message.stampCost == null) {
            message.deferStamp = false
        }

        if (!message.deferStamp) {
            // Generate stamp now if needed (before queueing)
            if (message.stampCost != null && message.stamp == null && message.outboundTicket == null) {
                message.getStamp()
                // Re-pack with stamp
                message.repackWithStamp()
            }

            // Add to outbound queue
            pendingOutboundMutex.withLock {
                pendingOutbound.add(message)
            }

            // Trigger processing
            triggerProcessing()
        } else {
            // Deferred: stamp will be generated in background
            val messageIdHex = message.hash?.toHexString() ?: return
            pendingDeferredStamps[messageIdHex] = message
        }
    }

    /**
     * Trigger outbound processing.
     */
    private fun triggerProcessing() {
        processingScope?.launch {
            processOutbound()
        }
    }

    /**
     * Process pending outbound messages.
     *
     * This is the main delivery loop that handles different delivery methods.
     */
    suspend fun processOutbound() {
        if (!outboundProcessingMutex.tryLock()) {
            return // Already processing
        }

        try {
            val toRemove = mutableListOf<LXMessage>()
            val currentTime = System.currentTimeMillis()

            pendingOutboundMutex.withLock {
                for (message in pendingOutbound) {
                    when (message.state) {
                        MessageState.DELIVERED -> {
                            toRemove.add(message)
                        }

                        MessageState.SENT -> {
                            // For propagated messages, SENT is final
                            if (message.method == DeliveryMethod.PROPAGATED) {
                                toRemove.add(message)
                            }
                        }

                        MessageState.CANCELLED, MessageState.REJECTED -> {
                            toRemove.add(message)
                            failedDeliveryCallback?.invoke(message)
                        }

                        MessageState.FAILED -> {
                            toRemove.add(message)
                            failedOutboundMutex.withLock {
                                failedOutbound.add(message)
                            }
                            failedDeliveryCallback?.invoke(message)
                        }

                        MessageState.OUTBOUND -> {
                            // Check if ready to attempt delivery
                            val nextAttempt = message.nextDeliveryAttempt ?: 0L
                            if (currentTime >= nextAttempt) {
                                processOutboundMessage(message)
                            }
                        }

                        else -> {
                            // Other states (GENERATING, SENDING) - wait
                        }
                    }
                }

                // Remove processed messages
                pendingOutbound.removeAll(toRemove)
            }
        } finally {
            outboundProcessingMutex.unlock()
        }
    }

    /**
     * Process a single outbound message based on its delivery method.
     */
    private suspend fun processOutboundMessage(message: LXMessage) {
        val method = message.desiredMethod ?: DeliveryMethod.DIRECT

        when (method) {
            DeliveryMethod.OPPORTUNISTIC -> {
                processOpportunisticDelivery(message)
            }

            DeliveryMethod.DIRECT -> {
                processDirectDelivery(message)
            }

            DeliveryMethod.PROPAGATED -> {
                processPropagatedDelivery(message)
            }

            DeliveryMethod.PAPER -> {
                // Paper delivery is handled separately (QR codes, etc.)
                message.state = MessageState.FAILED
            }
        }
    }

    /**
     * Process opportunistic message delivery.
     *
     * Matches Python LXMF LXMRouter opportunistic outbound handling (lines 2554-2581):
     * - After MAX_PATHLESS_TRIES attempts without path, request path
     * - At MAX_PATHLESS_TRIES+1 with path but still failing, rediscover path
     * - Normal delivery attempt otherwise
     */
    private suspend fun processOpportunisticDelivery(message: LXMessage) {
        // Check max delivery attempts FIRST (matching Python's <= check)
        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            // Max attempts reached - fail the message
            message.state = MessageState.FAILED
            message.failedCallback?.invoke(message)
            return
        }

        val dest = message.destination
        if (dest == null) {
            // No destination, can't check path - schedule retry
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            return
        }

        val hasPath = Transport.hasPath(dest.hash)

        when {
            // After MAX_PATHLESS_TRIES attempts without path, request path
            // Python: if delivery_attempts >= MAX_PATHLESS_TRIES and not has_path()
            message.deliveryAttempts >= MAX_PATHLESS_TRIES && !hasPath -> {
                println("[LXMRouter] Requesting path after ${message.deliveryAttempts} pathless tries for ${message.destinationHash.toHexString()}")
                message.deliveryAttempts++
                Transport.requestPath(dest.hash)
                message.nextDeliveryAttempt = System.currentTimeMillis() + PATH_REQUEST_WAIT
                message.progress = 0.01
            }

            // At MAX_PATHLESS_TRIES+1 with path but still failing, rediscover path
            // Python: elif delivery_attempts == MAX_PATHLESS_TRIES+1 and has_path()
            message.deliveryAttempts == MAX_PATHLESS_TRIES + 1 && hasPath -> {
                println("[LXMRouter] Opportunistic delivery still unsuccessful after ${message.deliveryAttempts} attempts, trying to rediscover path")
                message.deliveryAttempts++
                // Drop existing path and re-request (Python does this via Reticulum.drop_path + request_path)
                Transport.expirePath(dest.hash)
                // Small delay then request new path (matching Python's 0.5s sleep in thread)
                processingScope?.launch {
                    delay(500)
                    Transport.requestPath(dest.hash)
                }
                message.nextDeliveryAttempt = System.currentTimeMillis() + PATH_REQUEST_WAIT
                message.progress = 0.01
            }

            // Normal delivery attempt
            // Python: else: if time.time() > next_delivery_attempt
            else -> {
                val now = System.currentTimeMillis()
                val nextAttempt = message.nextDeliveryAttempt ?: 0L
                if (nextAttempt == 0L || now > nextAttempt) {
                    message.deliveryAttempts++
                    message.nextDeliveryAttempt = now + DELIVERY_RETRY_WAIT
                    println("[LXMRouter] Opportunistic delivery attempt ${message.deliveryAttempts} for ${message.destinationHash.toHexString()}")

                    val sent = sendOpportunisticMessage(message)
                    if (sent) {
                        message.state = MessageState.SENT
                        message.method = DeliveryMethod.OPPORTUNISTIC
                    }
                }
            }
        }
    }

    /**
     * Send a message opportunistically (single encrypted packet).
     *
     * This uses Packet.create() with the destination object, which matches
     * how Python RNS creates packets. The Packet class handles encryption
     * automatically based on destination type.
     */
    private fun sendOpportunisticMessage(message: LXMessage): Boolean {
        // Get the packed message data
        val packed = message.packed ?: return false

        // Get the destination - required for encryption
        val dest = message.destination
        if (dest == null) {
            println("[LXMRouter] Cannot send opportunistic: no destination")
            return false
        }

        // For opportunistic delivery, we send:
        // - Destination hash is in the packet header
        // - Data is: source_hash + signature + payload (everything after dest hash)
        // - This data is encrypted by Packet.create() for the destination
        val plainData = packed.copyOfRange(LXMFConstants.DESTINATION_LENGTH, packed.size)

        // Debug logging
        val destPubKey = dest.identity?.getPublicKey()
        println("[LXMRouter] sendOpportunisticMessage:")
        println("[LXMRouter]   Destination hash: ${message.destinationHash.toHexString()}")
        println("[LXMRouter]   Dest identity hash: ${dest.identity?.hash?.toHexString() ?: "null"}")
        println("[LXMRouter]   Dest public key (first 8 bytes): ${destPubKey?.take(8)?.toByteArray()?.toHexString() ?: "null"}")
        println("[LXMRouter]   Plain data size: ${plainData.size} bytes")
        println("[LXMRouter]   Plain data (first 32 bytes): ${plainData.take(32).toByteArray().toHexString()}")

        // Create the packet - Packet.create() will encrypt the data for us
        val packet = Packet.create(
            destination = dest,
            data = plainData,
            packetType = PacketType.DATA,
            context = PacketContext.NONE,
            transportType = TransportType.BROADCAST
        )

        println("[LXMRouter]   Packed packet size: ${packet.raw?.size ?: packet.pack().size} bytes")

        // Send via packet.send() to get receipt for delivery confirmation
        val receipt = packet.send()
        if (receipt != null) {
            println("[LXMRouter] Sent opportunistic message to ${message.destinationHash.toHexString()}")

            // Set up delivery confirmation callback
            receipt.setDeliveryCallback { _ ->
                message.state = MessageState.DELIVERED
                message.deliveryCallback?.invoke(message)
            }

            // Set up timeout callback
            receipt.setTimeoutCallback { _ ->
                message.state = MessageState.FAILED
                message.failedCallback?.invoke(message)
            }

            return true
        } else {
            println("[LXMRouter] Failed to send opportunistic message")
            return false
        }
    }

    /**
     * Process direct link-based message delivery.
     */
    private suspend fun processDirectDelivery(message: LXMessage) {
        message.deliveryAttempts++

        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            message.state = MessageState.FAILED
            return
        }

        val destHashHex = message.destinationHash.toHexString()

        // Debug logging
        println("[LXMRouter] processDirectDelivery: destHashHex=$destHashHex")
        println("[LXMRouter] processDirectDelivery: directLinks keys=${directLinks.keys}")
        println("[LXMRouter] processDirectDelivery: backchannelLinks keys=${backchannelLinks.keys}")

        // Check for existing active link that WE initiated (directLinks only)
        // Note: We don't use backchannelLinks because the remote peer may not have set up
        // resource receive callbacks on links THEY initiated. We need to establish our own link.
        var link = directLinks[destHashHex]
        println("[LXMRouter] processDirectDelivery: found link=${link != null}, status=${link?.status}")

        when {
            link != null && link.status == LinkConstants.ACTIVE -> {
                // Use existing active link
                sendViaLink(message, link)
            }

            link != null && link.status == LinkConstants.PENDING -> {
                // Link is being established, wait
                message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            }

            link != null && link.status == LinkConstants.CLOSED -> {
                // Link is closed, remove and try to establish new one
                directLinks.remove(destHashHex)
                establishLinkForMessage(message)
            }

            else -> {
                // No link exists, establish one
                establishLinkForMessage(message)
            }
        }
    }

    /**
     * Establish a link for sending a message.
     */
    private fun establishLinkForMessage(message: LXMessage) {
        val destination = message.destination
        if (destination == null) {
            // Can't establish link without destination object
            // For now, schedule retry and hope destination becomes available
            message.nextDeliveryAttempt = System.currentTimeMillis() + PATH_REQUEST_WAIT
            return
        }

        val destHashHex = message.destinationHash.toHexString()

        // Check if we're already establishing a link
        if (pendingLinkEstablishments.contains(destHashHex)) {
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            return
        }

        pendingLinkEstablishments.add(destHashHex)

        try {
            // Create the link
            val link = Link.create(
                destination = destination,
                establishedCallback = { establishedLink ->
                    // Link established successfully
                    directLinks[destHashHex] = establishedLink
                    pendingLinkEstablishments.remove(destHashHex)

                    // Set up link callbacks for receiving
                    setupLinkCallbacks(establishedLink, destHashHex)

                    // Identify ourselves on the link
                    identifyOnLink(establishedLink)

                    // Trigger processing to send pending messages
                    triggerProcessing()
                },
                closedCallback = { closedLink ->
                    // Link closed
                    directLinks.remove(destHashHex)
                    pendingLinkEstablishments.remove(destHashHex)

                    // Notify messages that need this link
                    handleLinkClosed(destHashHex)
                }
            )

            // Store pending link
            directLinks[destHashHex] = link

        } catch (e: Exception) {
            pendingLinkEstablishments.remove(destHashHex)
            println("Failed to establish link to $destHashHex: ${e.message}")
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
        }
    }

    /**
     * Set up callbacks on an established link.
     */
    private fun setupLinkCallbacks(link: Link, destHashHex: String) {
        // Packet callback for receiving messages
        link.setPacketCallback { data, packet ->
            // Send proof back to sender for delivery confirmation
            packet?.prove()
            processInboundDelivery(data, DeliveryMethod.DIRECT, null, link)
        }

        // Enable app-controlled resource acceptance for LXMF messages
        link.setResourceStrategy(Link.ACCEPT_APP)

        // Resource advertisement callback - accept all LXMF resources
        link.setResourceCallback { _: ResourceAdvertisement ->
            // Accept LXMF resources
            true
        }

        link.setResourceStartedCallback { _: Any ->
            println("Resource transfer started on link to $destHashHex")
        }

        link.setResourceConcludedCallback { resource: Any ->
            handleResourceConcluded(resource, link)
        }
    }

    /**
     * Identify ourselves on a link (for backchannel replies).
     */
    private fun identifyOnLink(link: Link) {
        // Get our first delivery destination's identity
        val deliveryDest = deliveryDestinations.values.firstOrNull() ?: return

        try {
            link.identify(deliveryDest.identity)
        } catch (e: Exception) {
            println("Failed to identify on link: ${e.message}")
        }
    }

    /**
     * Handle a link being closed.
     */
    private fun handleLinkClosed(destHashHex: String) {
        processingScope?.launch {
            pendingOutboundMutex.withLock {
                for (message in pendingOutbound) {
                    if (message.destinationHash.toHexString() == destHashHex &&
                        message.state == MessageState.SENDING) {
                        // Reset to outbound for retry
                        message.state = MessageState.OUTBOUND
                        message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
                    }
                }
            }
        }
    }

    /**
     * Handle a resource transfer being concluded.
     */
    private fun handleResourceConcluded(resource: Any, link: Link) {
        // Cast to Resource and extract data
        val res = resource as? Resource
        if (res == null) {
            println("Resource concluded but could not cast resource")
            return
        }

        val data = res.data
        if (data == null || data.isEmpty()) {
            println("Resource concluded but no data received")
            return
        }

        // Process as inbound LXMF delivery
        processInboundDelivery(data, DeliveryMethod.DIRECT, null, link)
    }

    /**
     * Process propagated message delivery.
     *
     * Matches Python LXMF LXMRouter.py lines 2669-2720:
     * - If link exists and ACTIVE -> send message
     * - If link exists and CLOSED -> clear link, set retry wait
     * - If link exists but PENDING -> just wait (no retry delay)
     * - If link is null -> establish new link (with retry wait check)
     */
    private suspend fun processPropagatedDelivery(message: LXMessage) {
        // Check for active propagation node
        val node = getActivePropagationNode()
        if (node == null) {
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            return
        }

        // Check max delivery attempts (Python line 2673)
        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            message.state = MessageState.FAILED
            return
        }

        val link = outboundPropagationLink

        when {
            // Link exists and is ACTIVE -> send message (Python line 2678-2682)
            link != null && link.status == LinkConstants.ACTIVE -> {
                if (message.state != MessageState.SENDING) {
                    sendViaPropagation(message, link)
                }
                // If already SENDING, just wait for transfer to complete
            }

            // Link exists and is CLOSED -> clear and retry later (Python line 2688-2691)
            link != null && link.status == LinkConstants.CLOSED -> {
                outboundPropagationLink = null
                message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            }

            // Link exists but is PENDING or other state -> just wait (Python line 2692-2695)
            link != null -> {
                // Don't set nextDeliveryAttempt - the established callback will trigger processOutbound
            }

            // No link exists -> establish one (Python line 2696-2715)
            else -> {
                // Only establish if we haven't tried recently (Python line 2700)
                val nextAttempt = message.nextDeliveryAttempt ?: 0L
                val now = System.currentTimeMillis()

                if (nextAttempt == 0L || now >= nextAttempt) {
                    message.deliveryAttempts++
                    message.nextDeliveryAttempt = now + DELIVERY_RETRY_WAIT

                    if (message.deliveryAttempts <= MAX_DELIVERY_ATTEMPTS) {
                        establishPropagationLink(node, forRetrieval = false)
                    } else {
                        message.state = MessageState.FAILED
                    }
                }
            }
        }
    }

    /**
     * Send a message via propagation node.
     */
    private fun sendViaPropagation(message: LXMessage, link: Link) {
        val packed = message.packed ?: return

        message.state = MessageState.SENDING
        message.method = DeliveryMethod.PROPAGATED

        try {
            // Pack message data with timebase for propagation transfer
            val buffer = java.io.ByteArrayOutputStream()
            val packer = org.msgpack.core.MessagePack.newDefaultPacker(buffer)

            // Propagation transfer format: [timebase, [message_list]]
            packer.packArrayHeader(2)
            packer.packDouble(System.currentTimeMillis() / 1000.0)

            // Message list with just our message
            packer.packArrayHeader(1)
            packer.packBinaryHeader(packed.size)
            packer.writePayload(packed)

            packer.close()

            // Send as Resource (propagation transfers are always Resource-based)
            val resource = Resource.create(
                data = buffer.toByteArray(),
                link = link,
                callback = { _ ->
                    // Transfer complete - for propagated messages, SENT is final
                    message.state = MessageState.SENT
                    message.deliveryCallback?.invoke(message)
                },
                progressCallback = { progressResource ->
                    message.progress = progressResource.progress.toDouble()
                }
            )

            // Track the resource
            val messageHashHex = message.hash?.toHexString() ?: ""
            pendingResources[messageHashHex] = Pair(message, resource)

        } catch (e: Exception) {
            println("Failed to send via propagation: ${e.message}")
            message.state = MessageState.OUTBOUND
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
        }
    }

    /**
     * Send a message via an established link.
     */
    private fun sendViaLink(message: LXMessage, link: Link) {
        val packed = message.packed ?: return

        message.state = MessageState.SENDING
        message.method = DeliveryMethod.DIRECT

        if (message.representation == MessageRepresentation.PACKET) {
            // For DIRECT delivery over link, Python's __as_packet sends full self.packed (WITH dest hash):
            //   elif self.method == LXMessage.DIRECT:
            //       return RNS.Packet(self.__delivery_destination, self.packed)
            // And unpack_from_bytes expects the destination hash at the start.
            val lxmfData = packed  // Full packed message including destination hash
            // Send as packet over link with receipt tracking
            try {
                val receipt = link.sendWithReceipt(lxmfData)
                if (receipt != null) {
                    message.state = MessageState.SENT

                    // Set up delivery confirmation callback
                    receipt.setDeliveryCallback { _ ->
                        message.state = MessageState.DELIVERED
                        message.deliveryCallback?.invoke(message)
                    }

                    // Set up timeout callback
                    receipt.setTimeoutCallback { _ ->
                        message.state = MessageState.FAILED
                        message.failedCallback?.invoke(message)
                    }
                } else {
                    // Send failed
                    message.state = MessageState.OUTBOUND
                    message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
                }
            } catch (e: Exception) {
                println("Failed to send packet via link: ${e.message}")
                message.state = MessageState.OUTBOUND
                message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            }
        } else {
            // Send as resource for large messages
            // Python's __as_resource() sends self.packed (full message including dest hash)
            try {
                val messageHashHex = message.hash?.toHexString() ?: ""
                val resource = Resource.create(
                    data = packed,  // Full packed message, matches Python's __as_resource()
                    link = link,
                    callback = { completedResource ->
                        // Resource transfer complete
                        pendingResources.remove(messageHashHex)
                        message.state = MessageState.DELIVERED
                        message.progress = 1.0
                        message.deliveryCallback?.invoke(message)
                    },
                    progressCallback = { progressResource ->
                        // Update progress (Resource provides progress as 0.0-1.0 Float)
                        message.progress = progressResource.progress.toDouble()
                    }
                )

                // Track resource for completion
                pendingResources[messageHashHex] = Pair(message, resource)
                message.state = MessageState.SENDING
            } catch (e: Exception) {
                println("Failed to send resource via link: ${e.message}")
                message.state = MessageState.OUTBOUND
                message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            }
        }
    }

    // ===== Inbound Message Handling =====

    /**
     * Handle an incoming delivery packet.
     */
    private fun handleDeliveryPacket(data: ByteArray, destination: Destination, packet: Packet?) {
        // CRITICAL: Send proof back to sender FIRST, before processing
        // This is what triggers delivery confirmation on the sender side
        packet?.prove()

        println("[LXMRouter] handleDeliveryPacket called with ${data.size} bytes for ${destination.hexHash}")
        // For OPPORTUNISTIC delivery (single packet), the data doesn't include the
        // destination hash - we need to prepend it to match the LXMF message format.
        // Format: [destination_hash (16)] + [source_hash (16)] + [signature (64)] + [payload]
        val method = DeliveryMethod.OPPORTUNISTIC
        val lxmfData = destination.hash + data
        println("[LXMRouter] Prepended dest hash, lxmfData size: ${lxmfData.size} bytes (was ${data.size})")

        // Process the delivery
        processInboundDelivery(lxmfData, method, destination)
    }

    /**
     * Handle a new delivery link being established.
     */
    private fun handleDeliveryLinkEstablished(link: Link, destination: Destination) {
        // Set up link callbacks for packets
        link.setPacketCallback { data, packet ->
            // Send proof back to sender for delivery confirmation
            packet?.prove()
            processInboundDelivery(data, DeliveryMethod.DIRECT, destination, link)
        }

        // Enable app-controlled resource acceptance for LXMF messages
        link.setResourceStrategy(Link.ACCEPT_APP)

        // Resource advertisement callback - accept all LXMF resources
        link.setResourceCallback { _: ResourceAdvertisement ->
            true
        }

        link.setResourceConcludedCallback { resource: Any ->
            handleResourceConcluded(resource, link)
        }

        // Set up callback for when the remote peer identifies themselves
        // This is critical for backchannel replies - the sender calls link.identify()
        // to reveal their identity so we can reply to them
        link.setRemoteIdentifiedCallback { _, remoteIdentity ->
            val remoteHashHex = remoteIdentity.hexHash
            println("[LXMRouter] Remote peer identified on link: $remoteHashHex")

            // Calculate the LXMF delivery destination hash from the identity
            // This is the hash that will appear as sourceHash in LXMF messages
            val lxmfDestHash = Destination.hashFromNameAndIdentity("lxmf.delivery", remoteIdentity)
            val lxmfDestHashHex = lxmfDestHash.toHexString()

            // Store the backchannel link for replies (use LXMF dest hash, not identity hash)
            backchannelLinks[lxmfDestHashHex] = link

            // Store the identity so Identity.recall(sourceHash) works
            // This is needed because receivers use Identity.recall(message.sourceHash)
            // to get the sender's identity for replies
            Identity.remember(
                packetHash = link.hash,  // Use link hash as packet hash
                destHash = lxmfDestHash,  // Use LXMF destination hash as lookup key
                publicKey = remoteIdentity.getPublicKey(),
                appData = null
            )
            println("[LXMRouter] Stored identity for LXMF dest: $lxmfDestHashHex")
        }

        // Also check if identity is already known (for immediate identification)
        link.getRemoteIdentity()?.let { remoteIdentity ->
            val remoteHashHex = remoteIdentity.hexHash
            backchannelLinks[remoteHashHex] = link
        }
    }

    /**
     * Process an inbound LXMF delivery.
     *
     * @param data The raw message data
     * @param method The delivery method used
     * @param destination The destination that received the message
     * @param link Optional link if this came via direct delivery
     */
    private fun processInboundDelivery(
        data: ByteArray,
        method: DeliveryMethod,
        destination: Destination? = null,
        link: Link? = null
    ) {
        println("[LXMRouter] processInboundDelivery called with ${data.size} bytes, method=$method")
        try {
            // Unpack the message
            val message = LXMessage.unpackFromBytes(data, method)
            if (message == null) {
                println("[LXMRouter] Failed to unpack LXMF message")
                return
            }
            println("[LXMRouter] Unpacked message from ${message.sourceHash.toHexString()}")

            message.incoming = true
            message.method = method

            // Check for duplicates
            val transientId = message.transientId?.toHexString()
            if (transientId != null && locallyDeliveredTransientIds.containsKey(transientId)) {
                println("Duplicate message detected, ignoring")
                return
            }

            // Validate signature if possible
            if (!message.signatureValidated) {
                when (message.unverifiedReason) {
                    UnverifiedReason.SOURCE_UNKNOWN -> {
                        // Source not known - could still accept depending on policy
                        println("Message from unknown source: ${message.sourceHash.toHexString()}")
                    }
                    UnverifiedReason.SIGNATURE_INVALID -> {
                        println("Message signature invalid, rejecting")
                        return
                    }
                    null -> {
                        // No error, signature validated
                    }
                }
            }

            // Check ignored list (access control)
            val sourceHashHexForCheck = message.sourceHash.toHexString()
            if (ignoredList.any { it.toHexString() == sourceHashHexForCheck }) {
                println("[LXMRouter] Ignored message from $sourceHashHexForCheck")
                return
            }

            // Validate stamp if required (PAPER messages bypass stamp enforcement)
            val destHashHex = message.destinationHash.toHexString()
            val deliveryDest = deliveryDestinations[destHashHex]
            val requiredCost = deliveryDest?.stampCost
            val noStampEnforcement = method == DeliveryMethod.PAPER
            if (requiredCost != null && requiredCost > 0) {
                // Extract ticket from incoming message first
                extractAndRememberTicket(message)

                val tickets = getInboundTickets(sourceHashHexForCheck)
                if (!message.validateStamp(requiredCost, tickets)) {
                    if (noStampEnforcement) {
                        println("[LXMRouter] Message from $sourceHashHexForCheck has invalid stamp, but allowing (PAPER delivery)")
                    } else {
                        println("[LXMRouter] Message from $sourceHashHexForCheck failed stamp validation (required cost: $requiredCost)")
                        return
                    }
                }
            }

            // Store backchannel link and identity for replies
            if (link != null) {
                val sourceHashHex = message.sourceHash.toHexString()
                backchannelLinks[sourceHashHex] = link

                // If the link has a remote identity, store it so Identity.recall() works
                // This is needed for echo/reply functionality
                link.getRemoteIdentity()?.let { remoteIdentity ->
                    // Calculate the LXMF delivery destination hash from the identity
                    val lxmfDestHash = Destination.hashFromNameAndIdentity("lxmf.delivery", remoteIdentity)

                    // Only store if the identity's LXMF dest hash matches the source hash
                    if (lxmfDestHash.contentEquals(message.sourceHash)) {
                        Identity.remember(
                            packetHash = link.hash,
                            destHash = lxmfDestHash,
                            publicKey = remoteIdentity.getPublicKey(),
                            appData = null
                        )
                        println("[LXMRouter] Stored identity from link for LXMF dest: $sourceHashHex")
                    }
                }
            }

            // Mark as delivered
            val nowSeconds = System.currentTimeMillis() / 1000
            transientId?.let {
                locallyDeliveredTransientIds[it] = nowSeconds
                saveTransientIdsAsync()
            }

            // Invoke delivery callback
            deliveryCallback?.invoke(message)

        } catch (e: Exception) {
            println("Error processing inbound delivery: ${e.message}")
        }
    }

    // ===== Announce Handling =====

    /**
     * Pack app data for announcements.
     */
    private fun packAnnounceAppData(displayName: String?, stampCost: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val packer = org.msgpack.core.MessagePack.newDefaultPacker(buffer)

        packer.packArrayHeader(2)

        // Display name
        if (displayName != null) {
            val nameBytes = displayName.toByteArray(Charsets.UTF_8)
            packer.packBinaryHeader(nameBytes.size)
            packer.writePayload(nameBytes)
        } else {
            packer.packNil()
        }

        // Stamp cost
        if (stampCost > 0) {
            packer.packInt(stampCost)
        } else {
            packer.packNil()
        }

        packer.close()
        return buffer.toByteArray()
    }

    /**
     * Handle a delivery announcement from a remote destination.
     */
    fun handleDeliveryAnnounce(destHash: ByteArray, appData: ByteArray?) {
        if (appData == null || appData.isEmpty()) {
            return
        }

        try {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(appData)
            val arraySize = unpacker.unpackArrayHeader()

            if (arraySize >= 2) {
                // Skip display name
                unpacker.skipValue()

                // Extract stamp cost
                if (!unpacker.tryUnpackNil()) {
                    val stampCost = unpacker.unpackInt()
                    updateStampCost(destHash.toHexString(), stampCost)
                }
            }
        } catch (e: Exception) {
            println("Error parsing delivery announce: ${e.message}")
        }

        // Trigger retry for pending messages to this destination
        val destHashHex = destHash.toHexString()
        processingScope?.launch {
            pendingOutboundMutex.withLock {
                for (message in pendingOutbound) {
                    if (message.destinationHash.toHexString() == destHashHex) {
                        message.nextDeliveryAttempt = System.currentTimeMillis()
                    }
                }
            }
            processOutbound()
        }
    }

    // ===== Router Lifecycle =====

    /**
     * Start the router processing loop.
     */
    fun start() {
        if (running) return

        running = true
        processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        processingJob = processingScope?.launch {
            while (running) {
                try {
                    processOutbound()
                    processDeferredStamps()

                    // Periodic cleanup every 60 iterations (~240 seconds at 4s interval)
                    processingCount++
                    if (processingCount % 60 == 0L) {
                        cleanTransientIdCaches()
                        cleanOutboundStampCosts()
                        cleanAvailableTickets()
                    }
                } catch (e: Exception) {
                    println("Error in processing loop: ${e.message}")
                }
                delay(PROCESSING_INTERVAL)
            }
        }
    }

    /**
     * Stop the router processing loop.
     */
    fun stop() {
        running = false
        processingJob?.cancel()
        processingScope?.cancel()
        processingScope = null
    }

    // ===== Utility Methods =====

    /**
     * Get the number of pending outbound messages.
     */
    suspend fun pendingOutboundCount(): Int {
        return pendingOutboundMutex.withLock {
            pendingOutbound.size
        }
    }

    /**
     * Get the number of failed outbound messages.
     */
    suspend fun failedOutboundCount(): Int {
        return failedOutboundMutex.withLock {
            failedOutbound.size
        }
    }

    /**
     * Get a registered delivery destination by hash.
     */
    fun getDeliveryDestination(hexHash: String): DeliveryDestination? {
        return deliveryDestinations[hexHash]
    }

    /**
     * Get all registered delivery destinations.
     */
    fun getDeliveryDestinations(): List<DeliveryDestination> {
        return deliveryDestinations.values.toList()
    }

    /**
     * Set the inbound stamp cost for a registered delivery destination.
     *
     * Matches Python LXMRouter.set_inbound_stamp_cost() (lines 993-1001):
     * Validates cost range (null or 1-254) and updates the delivery destination.
     *
     * @param destinationHexHash The hex hash of the delivery destination
     * @param cost The stamp cost (null to disable, 1-254 for PoW requirement)
     * @throws IllegalArgumentException if cost is out of range or destination not found
     */
    fun setInboundStampCost(destinationHexHash: String, cost: Int?) {
        if (cost != null && (cost < 1 || cost > 254)) {
            throw IllegalArgumentException("Stamp cost must be null or between 1 and 254, got $cost")
        }

        val deliveryDest = deliveryDestinations[destinationHexHash]
            ?: throw IllegalArgumentException("No delivery destination registered with hash $destinationHexHash")

        deliveryDest.stampCost = cost

        // Update announce app data with new stamp cost
        val appData = packAnnounceAppData(deliveryDest.displayName, cost ?: 0)
        deliveryDest.destination.setDefaultAppData(appData)
    }

    /**
     * Announce a delivery destination.
     */
    fun announce(destination: Destination, appData: ByteArray? = null) {
        destination.announce(appData)
    }

    // ===== Propagation Node Methods =====

    /**
     * Handle a propagation node announcement.
     *
     * Parses the announcement app data and stores the node information
     * for future use when sending PROPAGATED messages.
     *
     * @param destHash The destination hash of the propagation node
     * @param identity The identity of the propagation node
     * @param appData The announcement app data (msgpack-encoded)
     */
    fun handlePropagationAnnounce(destHash: ByteArray, identity: Identity, appData: ByteArray?) {
        if (appData == null || appData.isEmpty()) {
            return
        }

        try {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(appData)
            val arraySize = unpacker.unpackArrayHeader()

            if (arraySize < 7) {
                println("Invalid propagation announce: expected 7 fields, got $arraySize")
                return
            }

            // [0] Legacy support flag (ignored)
            unpacker.skipValue()

            // [1] Timebase (int - unix timestamp)
            val timebase = if (!unpacker.tryUnpackNil()) unpacker.unpackLong() else 0L

            // [2] Node state (bool - active propagation node)
            val isActive = if (!unpacker.tryUnpackNil()) unpacker.unpackBoolean() else true

            // [3] Per-transfer limit (int KB)
            val perTransferLimit = if (!unpacker.tryUnpackNil()) {
                unpacker.unpackInt()
            } else {
                LXMFConstants.PROPAGATION_LIMIT_KB
            }

            // [4] Per-sync limit (int KB)
            val perSyncLimit = if (!unpacker.tryUnpackNil()) {
                unpacker.unpackInt()
            } else {
                LXMFConstants.SYNC_LIMIT_KB
            }

            // [5] Stamp cost list [cost, flexibility, peering_cost]
            var stampCost = LXMFConstants.PROPAGATION_COST
            var stampCostFlex = LXMFConstants.PROPAGATION_COST_FLEX
            var peeringCost = LXMFConstants.PEERING_COST

            if (!unpacker.tryUnpackNil()) {
                val costArraySize = unpacker.unpackArrayHeader()
                if (costArraySize >= 1) stampCost = unpacker.unpackInt()
                if (costArraySize >= 2) stampCostFlex = unpacker.unpackInt()
                if (costArraySize >= 3) peeringCost = unpacker.unpackInt()
            }

            // [6] Metadata (map with node name, etc.)
            var displayName: String? = null
            if (!unpacker.tryUnpackNil()) {
                val metaSize = unpacker.unpackMapHeader()
                for (i in 0 until metaSize) {
                    val key = unpacker.unpackInt()
                    when (key) {
                        LXMFConstants.PN_META_NAME -> {
                            if (!unpacker.tryUnpackNil()) {
                                val nameLen = unpacker.unpackBinaryHeader()
                                val nameBytes = ByteArray(nameLen)
                                unpacker.readPayload(nameBytes)
                                displayName = String(nameBytes, Charsets.UTF_8)
                            }
                        }
                        else -> unpacker.skipValue()
                    }
                }
            }

            unpacker.close()

            // Create and store the propagation node
            val node = PropagationNode(
                destHash = destHash,
                identity = identity,
                displayName = displayName,
                timebase = timebase,
                isActive = isActive,
                perTransferLimit = perTransferLimit,
                perSyncLimit = perSyncLimit,
                stampCost = stampCost,
                stampCostFlexibility = stampCostFlex,
                peeringCost = peeringCost
            )

            propagationNodes[node.hexHash] = node
            println("Discovered propagation node: ${node.hexHash} (${displayName ?: "unnamed"})")

        } catch (e: Exception) {
            println("Error parsing propagation announce: ${e.message}")
        }
    }

    /**
     * Set the active propagation node for outbound PROPAGATED messages.
     *
     * @param destHashHex The hex-encoded destination hash of the propagation node
     * @return True if the node was set successfully
     */
    fun setActivePropagationNode(destHashHex: String): Boolean {
        val node = propagationNodes[destHashHex]
        if (node == null || !node.isActive) {
            println("Cannot set inactive or unknown propagation node: $destHashHex")
            return false
        }

        activePropagationNodeHash = destHashHex
        // Close existing link if any
        outboundPropagationLink?.teardown()
        outboundPropagationLink = null

        println("Active propagation node set to: $destHashHex")
        return true
    }

    /**
     * Clear the active propagation node.
     */
    fun clearActivePropagationNode() {
        activePropagationNodeHash = null
        outboundPropagationLink?.teardown()
        outboundPropagationLink = null
    }

    /**
     * Get the active propagation node.
     */
    fun getActivePropagationNode(): PropagationNode? {
        val hash = activePropagationNodeHash ?: return null
        return propagationNodes[hash]
    }

    /**
     * Get all known propagation nodes.
     */
    fun getPropagationNodes(): List<PropagationNode> {
        return propagationNodes.values.filter { it.isActive }.toList()
    }

    /**
     * Add a propagation node directly to the known nodes map.
     *
     * This method bypasses the announce flow and is useful for testing
     * or when the propagation node details are known through other means.
     *
     * @param node The propagation node to add
     */
    fun addPropagationNode(node: PropagationNode) {
        propagationNodes[node.hexHash] = node
        println("Added propagation node: ${node.hexHash} (${node.displayName ?: "unnamed"})")
    }

    /**
     * Get the stamp cost required for the active propagation node.
     *
     * @return The minimum stamp cost, or null if no active node
     */
    fun getOutboundPropagationCost(): Int? {
        val node = getActivePropagationNode() ?: return null
        // Minimum accepted cost is (cost - flexibility), but at least PROPAGATION_COST_MIN
        return maxOf(
            LXMFConstants.PROPAGATION_COST_MIN,
            node.stampCost - node.stampCostFlexibility
        )
    }

    /**
     * Request messages from the active propagation node.
     *
     * This initiates the two-stage retrieval process:
     * 1. List available messages
     * 2. Download selected messages
     *
     * Progress can be tracked via propagationTransferProgress and
     * results via propagationTransferLastResult.
     */
    fun requestMessagesFromPropagationNode() {
        val node = getActivePropagationNode()
        if (node == null) {
            propagationTransferState = PropagationTransferState.FAILED
            println("No active propagation node to request from")
            return
        }

        propagationTransferState = PropagationTransferState.LINK_ESTABLISHING
        propagationTransferProgress = 0.0
        propagationTransferLastResult = 0

        // Check if we have an active link
        val link = outboundPropagationLink
        if (link != null && link.status == LinkConstants.ACTIVE) {
            propagationTransferState = PropagationTransferState.LINK_ESTABLISHED
            requestMessageList(link)
        } else {
            // Need to establish link first
            establishPropagationLink(node)
        }
    }

    /**
     * Establish a link to a propagation node.
     *
     * @param node The propagation node to connect to
     * @param forRetrieval True for message retrieval (calls requestMessageList),
     *                     False for message delivery (calls processOutbound).
     *                     Matches Python's architecture where delivery path (line 2709) uses
     *                     established_callback=self.process_outbound, while retrieval path
     *                     (line 512) uses msg_request_established_callback that calls
     *                     request_messages_from_propagation_node.
     */
    private fun establishPropagationLink(node: PropagationNode, forRetrieval: Boolean = true) {
        try {
            // Create destination for the propagation node
            val destination = Destination.create(
                identity = node.identity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = APP_NAME,
                PROPAGATION_ASPECT
            )

            val link = Link.create(
                destination = destination,
                establishedCallback = { establishedLink ->
                    outboundPropagationLink = establishedLink
                    propagationTransferState = PropagationTransferState.LINK_ESTABLISHED

                    // Identify ourselves on the link
                    identifyOnLink(establishedLink)

                    if (forRetrieval) {
                        // Retrieval path: request message list (Python line 510)
                        requestMessageList(establishedLink)
                    } else {
                        // Delivery path: re-trigger outbound processing for pending messages (Python line 2709)
                        processingScope?.launch {
                            // Reset nextDeliveryAttempt for pending PROPAGATED messages so they get processed immediately.
                            // This matches Python's behavior where process_outbound sends immediately when link is active,
                            // without checking next_delivery_attempt (which was set when we initiated link establishment).
                            pendingOutboundMutex.withLock {
                                val now = System.currentTimeMillis()
                                for (message in pendingOutbound) {
                                    if (message.desiredMethod == DeliveryMethod.PROPAGATED && message.state == MessageState.OUTBOUND) {
                                        message.nextDeliveryAttempt = now - 1  // Make eligible for immediate processing
                                    }
                                }
                            }
                            processOutbound()
                        }
                    }
                },
                closedCallback = { _ ->
                    if (propagationTransferState != PropagationTransferState.COMPLETE) {
                        propagationTransferState = PropagationTransferState.NO_LINK
                    }
                    outboundPropagationLink = null
                }
            )

            outboundPropagationLink = link

        } catch (e: Exception) {
            println("Failed to establish propagation link: ${e.message}")
            propagationTransferState = PropagationTransferState.FAILED
        }
    }

    /**
     * Request the list of available messages from a propagation node.
     */
    private fun requestMessageList(link: Link) {
        propagationTransferState = PropagationTransferState.LISTING_MESSAGES

        try {
            // Send LIST request: [None, None]
            // Python sends [None, None] as data, which gets packed by Link.request
            val requestData = listOf(null, null)

            link.request(
                path = LXMFConstants.MESSAGE_GET_PATH,
                data = requestData,
                responseCallback = { receipt ->
                    val responseData = receipt.response
                    if (responseData != null) {
                        handleMessageListResponse(link, responseData)
                    } else {
                        propagationTransferState = PropagationTransferState.FAILED
                        println("Message list request returned null response")
                    }
                },
                failedCallback = { _ ->
                    propagationTransferState = PropagationTransferState.FAILED
                    println("Message list request failed")
                }
            )

        } catch (e: Exception) {
            println("Failed to request message list: ${e.message}")
            propagationTransferState = PropagationTransferState.FAILED
        }
    }

    /**
     * Handle the message list response from a propagation node.
     */
    private fun handleMessageListResponse(link: Link, response: ByteArray) {
        try {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(response)

            // Check for error response
            if (unpacker.nextFormat.valueType == org.msgpack.value.ValueType.INTEGER) {
                val errorCode = unpacker.unpackInt()
                println("Propagation node returned error: $errorCode")
                propagationTransferState = PropagationTransferState.FAILED
                return
            }

            // Response is list of transient_ids (just the IDs, no sizes)
            // Python's message_get_request returns [transient_id, transient_id, ...]
            val messageCount = unpacker.unpackArrayHeader()
            val wantedIds = mutableListOf<ByteArray>()

            for (i in 0 until messageCount) {
                val idLen = unpacker.unpackBinaryHeader()
                val transientId = ByteArray(idLen)
                unpacker.readPayload(transientId)

                // Check if we already have this message
                val idHex = transientId.toHexString()
                if (!locallyDeliveredTransientIds.containsKey(idHex)) {
                    wantedIds.add(transientId)
                }
            }

            unpacker.close()

            if (wantedIds.isEmpty()) {
                propagationTransferState = PropagationTransferState.COMPLETE
                propagationTransferProgress = 1.0
                propagationTransferLastResult = 0
                println("No new messages from propagation node")
                return
            }

            // Request the messages we want
            requestMessages(link, wantedIds)

        } catch (e: Exception) {
            println("Error parsing message list: ${e.message}")
            propagationTransferState = PropagationTransferState.FAILED
        }
    }

    /**
     * Request specific messages from a propagation node.
     */
    private fun requestMessages(link: Link, wantedIds: List<ByteArray>) {
        propagationTransferState = PropagationTransferState.REQUESTING_MESSAGES

        try {
            // Send GET request: [wants, haves, limit]
            // Python expects: data[0]=wants (list of transient IDs), data[1]=haves (list), data[2]=limit (int KB)
            val requestData = listOf(
                wantedIds,                       // wants: list of transient IDs (ByteArray)
                emptyList<ByteArray>(),          // haves: empty list
                LXMFConstants.DELIVERY_LIMIT_KB  // limit: KB limit
            )

            link.request(
                path = LXMFConstants.MESSAGE_GET_PATH,
                data = requestData,
                responseCallback = { receipt ->
                    val responseData = receipt.response
                    if (responseData != null) {
                        handleMessageGetResponse(responseData, wantedIds.size)
                    } else {
                        propagationTransferState = PropagationTransferState.FAILED
                        println("Message get request returned null response")
                    }
                },
                failedCallback = { _ ->
                    propagationTransferState = PropagationTransferState.FAILED
                    println("Message get request failed")
                }
            )

        } catch (e: Exception) {
            println("Failed to request messages: ${e.message}")
            propagationTransferState = PropagationTransferState.FAILED
        }
    }

    /**
     * Handle the message get response from a propagation node.
     */
    private fun handleMessageGetResponse(response: ByteArray, expectedCount: Int) {
        propagationTransferState = PropagationTransferState.RECEIVING_MESSAGES

        try {
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(response)

            // Check for error response
            if (unpacker.nextFormat.valueType == org.msgpack.value.ValueType.INTEGER) {
                val errorCode = unpacker.unpackInt()
                println("Propagation node returned error: $errorCode")
                propagationTransferState = PropagationTransferState.FAILED
                return
            }

            // Response is list of message data (raw bytes, no wrapper arrays)
            // Python's message_get_request returns [lxmf_data, lxmf_data, ...]
            val messageCount = unpacker.unpackArrayHeader()
            var receivedCount = 0

            for (i in 0 until messageCount) {
                val dataLen = unpacker.unpackBinaryHeader()
                val messageData = ByteArray(dataLen)
                unpacker.readPayload(messageData)

                // Process the message
                processInboundDelivery(messageData, DeliveryMethod.PROPAGATED)

                receivedCount++
                propagationTransferProgress = receivedCount.toDouble() / expectedCount
            }

            unpacker.close()

            propagationTransferState = PropagationTransferState.COMPLETE
            propagationTransferProgress = 1.0
            propagationTransferLastResult = receivedCount

            println("Received $receivedCount messages from propagation node")

        } catch (e: Exception) {
            println("Error processing received messages: ${e.message}")
            propagationTransferState = PropagationTransferState.FAILED
        }
    }

    // ===== Stamp Cost Management =====

    /**
     * Update outbound stamp cost for a destination.
     * Matches Python LXMRouter.update_stamp_cost() (lines 977-982).
     */
    private fun updateStampCost(destHashHex: String, stampCost: Int) {
        val nowSeconds = System.currentTimeMillis() / 1000
        outboundStampCosts[destHashHex] = Pair(nowSeconds, stampCost)
        saveOutboundStampCostsAsync()
    }

    // ===== Deferred Stamp Processing (Phase 2) =====

    /**
     * Process deferred stamp generation.
     *
     * Picks one pending deferred message, generates its stamp in a coroutine,
     * re-packs, and moves to pendingOutbound.
     */
    private suspend fun processDeferredStamps() {
        if (pendingDeferredStamps.isEmpty()) return

        stampGenMutex.withLock {
            // Pick first entry
            val entry = pendingDeferredStamps.entries.firstOrNull() ?: return
            val messageIdHex = entry.key
            val message = entry.value

            try {
                // Generate stamp
                message.getStamp()
                message.repackWithStamp()

                // Move to outbound queue
                pendingDeferredStamps.remove(messageIdHex)
                pendingOutboundMutex.withLock {
                    pendingOutbound.add(message)
                }
                triggerProcessing()
            } catch (e: Exception) {
                println("[LXMRouter] Error generating deferred stamp for $messageIdHex: ${e.message}")
                // Leave in deferred queue for retry
            }
        }
    }

    // ===== Ticket System (Phase 3) =====

    /**
     * Generate a ticket for a destination.
     *
     * Matches Python LXMRouter.generate_ticket() (lines 1022-1049):
     * - Check TICKET_INTERVAL between deliveries
     * - Reuse existing ticket if > TICKET_RENEW validity left
     * - Otherwise generate 32 random bytes
     *
     * @param destinationHash Raw destination hash bytes
     * @return [expires, ticketBytes] list for FIELD_TICKET, or null
     */
    private fun generateTicket(destinationHash: ByteArray): List<Any>? {
        val destHashHex = destinationHash.toHexString()
        val nowSeconds = System.currentTimeMillis() / 1000

        // Check if we delivered a ticket recently
        val lastDelivery = lastTicketDeliveries[destHashHex]
        if (lastDelivery != null) {
            val elapsed = nowSeconds - lastDelivery
            if (elapsed < LXMFConstants.TICKET_INTERVAL) {
                return null
            }
        }

        // Check for existing reusable ticket
        val existingTickets = inboundTickets[destHashHex]
        if (existingTickets != null) {
            for ((ticketHex, expires) in existingTickets) {
                val validityLeft = expires - nowSeconds
                if (validityLeft > LXMFConstants.TICKET_RENEW) {
                    // Reuse existing ticket
                    return listOf(expires, hexToBytes(ticketHex))
                }
            }
        }

        // Generate new ticket
        val expires = nowSeconds + LXMFConstants.TICKET_EXPIRY
        val ticket = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES)
        SecureRandom().nextBytes(ticket)

        // Store in inbound tickets
        val tickets = inboundTickets.getOrPut(destHashHex) { ConcurrentHashMap() }
        tickets[ticket.toHexString()] = expires
        saveAvailableTicketsAsync()

        return listOf(expires, ticket)
    }

    /**
     * Remember a ticket received from a peer.
     *
     * Matches Python LXMRouter.remember_ticket() (lines 1051-1054).
     */
    private fun rememberTicket(sourceHashHex: String, ticketEntry: List<Any?>) {
        if (ticketEntry.size < 2) return
        val expires = (ticketEntry[0] as? Number)?.toLong() ?: return
        val ticket = ticketEntry[1] as? ByteArray ?: return

        outboundTickets[sourceHashHex] = Pair(expires, ticket)
        saveAvailableTicketsAsync()
    }

    /**
     * Get an outbound ticket for stamp bypass.
     *
     * Matches Python LXMRouter.get_outbound_ticket() (lines 1056-1062).
     */
    private fun getOutboundTicket(destHashHex: String): ByteArray? {
        val entry = outboundTickets[destHashHex] ?: return null
        val nowSeconds = System.currentTimeMillis() / 1000
        return if (entry.first > nowSeconds) entry.second else null
    }

    /**
     * Get valid inbound tickets for a source.
     *
     * Matches Python LXMRouter.get_inbound_tickets() (lines 1072-1083).
     */
    private fun getInboundTickets(sourceHashHex: String): List<ByteArray>? {
        val tickets = inboundTickets[sourceHashHex] ?: return null
        val nowSeconds = System.currentTimeMillis() / 1000
        val valid = tickets.entries
            .filter { nowSeconds < it.value }
            .map { hexToBytes(it.key) }

        return if (valid.isEmpty()) null else valid
    }

    /**
     * Extract ticket from an incoming message and remember it.
     *
     * Matches Python LXMRouter.py lines 1730-1741.
     */
    private fun extractAndRememberTicket(message: LXMessage) {
        if (!message.signatureValidated) return

        val ticketField = message.fields[LXMFConstants.FIELD_TICKET] ?: return
        val ticketEntry = ticketField as? List<*> ?: return
        if (ticketEntry.size < 2) return

        val expires = (ticketEntry[0] as? Number)?.toLong() ?: return
        val ticket = ticketEntry[1] as? ByteArray ?: return
        val nowSeconds = System.currentTimeMillis() / 1000

        if (nowSeconds < expires && ticket.size == RnsConstants.TRUNCATED_HASH_BYTES) {
            val sourceHashHex = message.sourceHash.toHexString()
            @Suppress("UNCHECKED_CAST")
            rememberTicket(sourceHashHex, ticketEntry as List<Any?>)
        }
    }

    // ===== Persistence (Phase 4) =====

    /**
     * Save outbound stamp costs to disk.
     * File: {storagePath}/lxmf/outbound_stamp_costs (msgpack)
     */
    private fun saveOutboundStampCosts() {
        val path = storagePath ?: return
        try {
            val dir = File(path, "lxmf")
            if (!dir.exists()) dir.mkdirs()

            val buffer = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(buffer)

            packer.packMapHeader(outboundStampCosts.size)
            for ((hexHash, pair) in outboundStampCosts) {
                val hashBytes = hexToBytes(hexHash)
                packer.packBinaryHeader(hashBytes.size)
                packer.writePayload(hashBytes)
                packer.packArrayHeader(2)
                packer.packLong(pair.first)
                packer.packInt(pair.second)
            }
            packer.close()

            File(dir, "outbound_stamp_costs").writeBytes(buffer.toByteArray())
        } catch (e: Exception) {
            println("[LXMRouter] Could not save outbound stamp costs: ${e.message}")
        }
    }

    private fun saveOutboundStampCostsAsync() {
        processingScope?.launch {
            costFileMutex.withLock { saveOutboundStampCosts() }
        }
    }

    /**
     * Load outbound stamp costs from disk.
     */
    private fun loadOutboundStampCosts() {
        val path = storagePath ?: return
        val file = File(path, "lxmf/outbound_stamp_costs")
        if (!file.exists()) return

        try {
            val data = file.readBytes()
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val mapSize = unpacker.unpackMapHeader()

            for (i in 0 until mapSize) {
                val keyLen = unpacker.unpackBinaryHeader()
                val keyBytes = ByteArray(keyLen)
                unpacker.readPayload(keyBytes)
                val hexHash = keyBytes.toHexString()

                val arrSize = unpacker.unpackArrayHeader()
                if (arrSize >= 2) {
                    val timestamp = unpacker.unpackLong()
                    val cost = unpacker.unpackInt()
                    outboundStampCosts[hexHash] = Pair(timestamp, cost)
                }
            }
            unpacker.close()

            // Clean expired entries on load
            cleanOutboundStampCosts()
        } catch (e: Exception) {
            println("[LXMRouter] Could not load outbound stamp costs: ${e.message}")
            outboundStampCosts.clear()
        }
    }

    /**
     * Save available tickets to disk.
     * File: {storagePath}/lxmf/available_tickets (msgpack)
     */
    private fun saveAvailableTickets() {
        val path = storagePath ?: return
        try {
            val dir = File(path, "lxmf")
            if (!dir.exists()) dir.mkdirs()

            val buffer = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(buffer)

            // Pack as map with 3 keys: "outbound", "inbound", "last_deliveries"
            packer.packMapHeader(3)

            // Outbound tickets
            packer.packString("outbound")
            packer.packMapHeader(outboundTickets.size)
            for ((hexHash, pair) in outboundTickets) {
                val hashBytes = hexToBytes(hexHash)
                packer.packBinaryHeader(hashBytes.size)
                packer.writePayload(hashBytes)
                packer.packArrayHeader(2)
                packer.packLong(pair.first)
                packer.packBinaryHeader(pair.second.size)
                packer.writePayload(pair.second)
            }

            // Inbound tickets
            packer.packString("inbound")
            packer.packMapHeader(inboundTickets.size)
            for ((hexHash, ticketMap) in inboundTickets) {
                val hashBytes = hexToBytes(hexHash)
                packer.packBinaryHeader(hashBytes.size)
                packer.writePayload(hashBytes)
                packer.packMapHeader(ticketMap.size)
                for ((ticketHex, expires) in ticketMap) {
                    val ticketBytes = hexToBytes(ticketHex)
                    packer.packBinaryHeader(ticketBytes.size)
                    packer.writePayload(ticketBytes)
                    packer.packArrayHeader(1)
                    packer.packLong(expires)
                }
            }

            // Last deliveries
            packer.packString("last_deliveries")
            packer.packMapHeader(lastTicketDeliveries.size)
            for ((hexHash, timestamp) in lastTicketDeliveries) {
                val hashBytes = hexToBytes(hexHash)
                packer.packBinaryHeader(hashBytes.size)
                packer.writePayload(hashBytes)
                packer.packLong(timestamp)
            }

            packer.close()
            File(dir, "available_tickets").writeBytes(buffer.toByteArray())
        } catch (e: Exception) {
            println("[LXMRouter] Could not save available tickets: ${e.message}")
        }
    }

    private fun saveAvailableTicketsAsync() {
        processingScope?.launch {
            ticketFileMutex.withLock { saveAvailableTickets() }
        }
    }

    /**
     * Load available tickets from disk.
     */
    private fun loadAvailableTickets() {
        val path = storagePath ?: return
        val file = File(path, "lxmf/available_tickets")
        if (!file.exists()) return

        try {
            val data = file.readBytes()
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val mapSize = unpacker.unpackMapHeader()

            for (i in 0 until mapSize) {
                val key = unpacker.unpackString()
                when (key) {
                    "outbound" -> {
                        val outSize = unpacker.unpackMapHeader()
                        for (j in 0 until outSize) {
                            val keyLen = unpacker.unpackBinaryHeader()
                            val keyBytes = ByteArray(keyLen)
                            unpacker.readPayload(keyBytes)
                            val hexHash = keyBytes.toHexString()

                            val arrSize = unpacker.unpackArrayHeader()
                            if (arrSize >= 2) {
                                val expires = unpacker.unpackLong()
                                val ticketLen = unpacker.unpackBinaryHeader()
                                val ticket = ByteArray(ticketLen)
                                unpacker.readPayload(ticket)
                                outboundTickets[hexHash] = Pair(expires, ticket)
                            }
                        }
                    }
                    "inbound" -> {
                        val inSize = unpacker.unpackMapHeader()
                        for (j in 0 until inSize) {
                            val keyLen = unpacker.unpackBinaryHeader()
                            val keyBytes = ByteArray(keyLen)
                            unpacker.readPayload(keyBytes)
                            val hexHash = keyBytes.toHexString()

                            val ticketMapSize = unpacker.unpackMapHeader()
                            val ticketMap = ConcurrentHashMap<String, Long>()
                            for (k in 0 until ticketMapSize) {
                                val tLen = unpacker.unpackBinaryHeader()
                                val tBytes = ByteArray(tLen)
                                unpacker.readPayload(tBytes)
                                val arrSize = unpacker.unpackArrayHeader()
                                if (arrSize >= 1) {
                                    val expires = unpacker.unpackLong()
                                    ticketMap[tBytes.toHexString()] = expires
                                }
                            }
                            if (ticketMap.isNotEmpty()) {
                                inboundTickets[hexHash] = ticketMap
                            }
                        }
                    }
                    "last_deliveries" -> {
                        val ldSize = unpacker.unpackMapHeader()
                        for (j in 0 until ldSize) {
                            val keyLen = unpacker.unpackBinaryHeader()
                            val keyBytes = ByteArray(keyLen)
                            unpacker.readPayload(keyBytes)
                            val hexHash = keyBytes.toHexString()
                            val timestamp = unpacker.unpackLong()
                            lastTicketDeliveries[hexHash] = timestamp
                        }
                    }
                    else -> unpacker.skipValue()
                }
            }
            unpacker.close()
        } catch (e: Exception) {
            println("[LXMRouter] Could not load available tickets: ${e.message}")
            outboundTickets.clear()
            inboundTickets.clear()
            lastTicketDeliveries.clear()
        }
    }

    /**
     * Save locally delivered transient IDs to disk.
     * File: {storagePath}/lxmf/local_deliveries (msgpack)
     */
    private fun saveTransientIds() {
        val path = storagePath ?: return
        if (locallyDeliveredTransientIds.isEmpty()) return

        try {
            val dir = File(path, "lxmf")
            if (!dir.exists()) dir.mkdirs()

            val buffer = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(buffer)

            packer.packMapHeader(locallyDeliveredTransientIds.size)
            for ((hexHash, timestamp) in locallyDeliveredTransientIds) {
                val hashBytes = hexToBytes(hexHash)
                packer.packBinaryHeader(hashBytes.size)
                packer.writePayload(hashBytes)
                packer.packLong(timestamp)
            }
            packer.close()

            File(dir, "local_deliveries").writeBytes(buffer.toByteArray())
        } catch (e: Exception) {
            println("[LXMRouter] Could not save transient IDs: ${e.message}")
        }
    }

    private fun saveTransientIdsAsync() {
        processingScope?.launch {
            transientIdFileMutex.withLock { saveTransientIds() }
        }
    }

    /**
     * Load locally delivered transient IDs from disk.
     */
    private fun loadTransientIds() {
        val path = storagePath ?: return
        val file = File(path, "lxmf/local_deliveries")
        if (!file.exists()) return

        try {
            val data = file.readBytes()
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val mapSize = unpacker.unpackMapHeader()

            for (i in 0 until mapSize) {
                val keyLen = unpacker.unpackBinaryHeader()
                val keyBytes = ByteArray(keyLen)
                unpacker.readPayload(keyBytes)
                val hexHash = keyBytes.toHexString()
                val timestamp = unpacker.unpackLong()
                locallyDeliveredTransientIds[hexHash] = timestamp
            }
            unpacker.close()
        } catch (e: Exception) {
            println("[LXMRouter] Could not load transient IDs: ${e.message}")
            locallyDeliveredTransientIds.clear()
        }
    }

    // ===== Access Control (Phase 5) =====

    /**
     * Set whether authentication is required for message delivery.
     */
    fun setAuthentication(required: Boolean) {
        authRequired = required
    }

    /**
     * Check if authentication is required.
     */
    fun requiresAuthentication(): Boolean = authRequired

    /**
     * Add an identity hash to the allowed list.
     *
     * @param identityHash Truncated identity hash (16 bytes)
     * @throws IllegalArgumentException if hash length is wrong
     */
    fun allow(identityHash: ByteArray) {
        require(identityHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
            "Allowed identity hash must be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
        }
        if (allowedList.none { it.contentEquals(identityHash) }) {
            allowedList.add(identityHash)
        }
    }

    /**
     * Remove an identity hash from the allowed list.
     */
    fun disallow(identityHash: ByteArray) {
        require(identityHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
            "Disallowed identity hash must be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
        }
        allowedList.removeAll { it.contentEquals(identityHash) }
    }

    /**
     * Add a destination hash to the ignored list.
     * Messages from ignored destinations are silently dropped.
     */
    fun ignoreDestination(destinationHash: ByteArray) {
        if (ignoredList.none { it.contentEquals(destinationHash) }) {
            ignoredList.add(destinationHash)
        }
    }

    /**
     * Remove a destination hash from the ignored list.
     */
    fun unignoreDestination(destinationHash: ByteArray) {
        ignoredList.removeAll { it.contentEquals(destinationHash) }
    }

    /**
     * Add a destination hash to the prioritised list.
     *
     * @param destinationHash Truncated destination hash (16 bytes)
     * @throws IllegalArgumentException if hash length is wrong
     */
    fun prioritise(destinationHash: ByteArray) {
        require(destinationHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
            "Prioritised destination hash must be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
        }
        if (prioritisedList.none { it.contentEquals(destinationHash) }) {
            prioritisedList.add(destinationHash)
        }
    }

    /**
     * Remove a destination hash from the prioritised list.
     */
    fun unprioritise(destinationHash: ByteArray) {
        require(destinationHash.size == RnsConstants.TRUNCATED_HASH_BYTES) {
            "Prioritised destination hash must be ${RnsConstants.TRUNCATED_HASH_BYTES} bytes"
        }
        prioritisedList.removeAll { it.contentEquals(destinationHash) }
    }

    /**
     * Check if an identity is allowed to deliver messages.
     */
    fun identityAllowed(identity: Identity): Boolean {
        return if (authRequired) {
            allowedList.any { it.contentEquals(identity.hash) }
        } else {
            true
        }
    }

    // ===== Cleanup Jobs (Phase 6) =====

    /**
     * Clean expired entries from transient ID caches.
     *
     * Matches Python LXMRouter.clean_transient_id_caches() (lines 955-975):
     * Removes entries older than MESSAGE_EXPIRY * 6 (180 days).
     */
    private fun cleanTransientIdCaches() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val expiryThreshold = LXMFConstants.MESSAGE_EXPIRY * 6

        val removed = locallyDeliveredTransientIds.entries.removeIf { (_, timestamp) ->
            nowSeconds > timestamp + expiryThreshold
        }
        if (removed) {
            saveTransientIdsAsync()
        }
    }

    /**
     * Clean expired outbound stamp costs.
     *
     * Matches Python LXMRouter.clean_outbound_stamp_costs() (lines 1217-1230):
     * Removes entries older than STAMP_COST_EXPIRY (45 days).
     */
    private fun cleanOutboundStampCosts() {
        try {
            val nowSeconds = System.currentTimeMillis() / 1000
            val removed = outboundStampCosts.entries.removeIf { (_, pair) ->
                nowSeconds > pair.first + LXMFConstants.STAMP_COST_EXPIRY
            }
            if (removed) {
                saveOutboundStampCostsAsync()
            }
        } catch (e: Exception) {
            println("[LXMRouter] Error cleaning outbound stamp costs: ${e.message}")
        }
    }

    /**
     * Clean expired available tickets.
     *
     * Matches Python LXMRouter.clean_available_tickets() (lines 1245-1271):
     * - Outbound: remove expired tickets
     * - Inbound: remove expired tickets + TICKET_GRACE
     */
    private fun cleanAvailableTickets() {
        try {
            val nowSeconds = System.currentTimeMillis() / 1000

            // Clean outbound tickets
            outboundTickets.entries.removeIf { (_, pair) ->
                nowSeconds > pair.first
            }

            // Clean inbound tickets
            for ((_, ticketMap) in inboundTickets) {
                ticketMap.entries.removeIf { (_, expires) ->
                    nowSeconds > expires + LXMFConstants.TICKET_GRACE
                }
            }

            // Remove empty inbound ticket maps
            inboundTickets.entries.removeIf { (_, ticketMap) ->
                ticketMap.isEmpty()
            }
        } catch (e: Exception) {
            println("[LXMRouter] Error cleaning available tickets: ${e.message}")
        }
    }

    // ===== PAPER Delivery (Phase 6) =====

    /**
     * Ingest a paper message from an lxm:// URI.
     *
     * Matches Python LXMRouter.ingest_lxm_uri() (lines 2358-2378):
     * 1. Validate URI prefix
     * 2. Strip prefix and decode base64url
     * 3. Process as paper message (no stamp enforcement)
     *
     * @param uri The lxm:// URI string
     * @return True if the message was successfully ingested
     */
    fun ingestLxmUri(uri: String): Boolean {
        try {
            val schema = "${LXMFConstants.URI_SCHEMA}://"
            if (!uri.lowercase().startsWith(schema)) {
                println("[LXMRouter] Cannot ingest LXM, invalid URI provided")
                return false
            }

            // Decode: remove protocol, remove slashes, add padding, base64url-decode
            val encoded = uri.removePrefix(schema)
                .replace(LXMFConstants.URI_SCHEMA + "://", "")
                .replace("/", "") + "=="

            val lxmfData = Base64.getUrlDecoder().decode(encoded)
            val transientId = Hashes.fullHash(lxmfData)
            val transientIdHex = transientId.toHexString()

            // Check for duplicates
            if (locallyDeliveredTransientIds.containsKey(transientIdHex)) {
                println("[LXMRouter] Paper message already delivered, ignoring duplicate")
                return false
            }

            // Process as inbound delivery (no stamp enforcement for paper messages)
            processInboundDelivery(lxmfData, DeliveryMethod.PAPER)

            println("[LXMRouter] Ingested paper message with transient ID ${transientIdHex.take(12)}")
            return true

        } catch (e: Exception) {
            println("[LXMRouter] Error decoding URI-encoded LXMF message: ${e.message}")
            return false
        }
    }

    // ===== Utility =====

    /**
     * Convert hex string to byte array.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
