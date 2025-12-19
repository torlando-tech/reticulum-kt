package network.reticulum.lxmf

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
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

        /** Pathless delivery attempts before requesting path */
        const val PATHLESS_DELIVERY_ATTEMPTS = 3

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
    private var propagationTransferState: PropagationTransferState = PropagationTransferState.IDLE

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

    /** Locally delivered message transient IDs for duplicate detection */
    private val locallyDeliveredTransientIds = ConcurrentHashMap.newKeySet<String>()

    /** Outbound stamp costs: destination_hash -> cost */
    private val outboundStampCosts = ConcurrentHashMap<String, Int>()

    // ===== Initialization =====

    init {
        // Create storage directories if needed
        storagePath?.let { path ->
            val dir = java.io.File(path, "lxmf")
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Data class holding a delivery destination and its configuration.
     */
    data class DeliveryDestination(
        val destination: Destination,
        val identity: Identity,
        val displayName: String? = null,
        val stampCost: Int = 0
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
        stampCost: Int = 0
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
            handleDeliveryPacket(data, destination)
        }

        // Set up link established callback
        destination.setLinkEstablishedCallback { link ->
            handleDeliveryLinkEstablished(link as Link, destination)
        }

        // Set default app data for announcements
        if (displayName != null || stampCost > 0) {
            val appData = packAnnounceAppData(displayName, stampCost)
            destination.setDefaultAppData(appData)
        }

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
        // Set message state to outbound
        message.state = MessageState.OUTBOUND

        // Pack the message if not already packed
        if (message.packed == null) {
            message.pack()
        }

        // Add to outbound queue
        pendingOutboundMutex.withLock {
            pendingOutbound.add(message)
        }

        // Trigger processing
        triggerProcessing()
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
     */
    private suspend fun processOpportunisticDelivery(message: LXMessage) {
        message.deliveryAttempts++

        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            message.state = MessageState.FAILED
            return
        }

        // Try to send the message
        val sent = sendOpportunisticMessage(message)

        if (sent) {
            message.state = MessageState.SENT
            message.method = DeliveryMethod.OPPORTUNISTIC
        } else {
            // Schedule retry
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
        }
    }

    /**
     * Send a message opportunistically (single encrypted packet).
     */
    private fun sendOpportunisticMessage(message: LXMessage): Boolean {
        // Get the packed message data
        val packed = message.packed ?: return false

        // For opportunistic delivery, we prepend the source hash
        val data = message.sourceHash + packed.copyOfRange(
            LXMFConstants.DESTINATION_LENGTH,
            packed.size
        )

        // TODO: Create and send packet via Transport
        // For now, return false as Transport integration is not complete
        return false
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

        // Check for existing active link
        var link = directLinks[destHashHex] ?: backchannelLinks[destHashHex]

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
        link.setPacketCallback { data, _ ->
            processInboundDelivery(data, DeliveryMethod.DIRECT, null, link)
        }

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
     */
    private suspend fun processPropagatedDelivery(message: LXMessage) {
        message.deliveryAttempts++

        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            message.state = MessageState.FAILED
            return
        }

        // Check for active propagation node
        val node = getActivePropagationNode()
        if (node == null) {
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
            println("No active propagation node for message delivery")
            return
        }

        // Check if we have an active link
        val link = outboundPropagationLink
        if (link != null && link.status == LinkConstants.ACTIVE) {
            sendViaPropagation(message, link)
        } else {
            // Need to establish link
            message.state = MessageState.OUTBOUND
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT

            // Try to establish link
            if (outboundPropagationLink == null) {
                establishPropagationLink(node)
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

        // The LXMF payload for link delivery excludes the destination hash
        // (since it's implicit from the link), but includes source hash
        val lxmfData = packed.copyOfRange(
            LXMFConstants.DESTINATION_LENGTH,  // Skip destination hash
            packed.size
        )

        if (message.representation == MessageRepresentation.PACKET) {
            // Send as packet over link
            try {
                val sent = link.send(lxmfData)
                if (sent) {
                    // For now, mark as SENT - actual delivery confirmation
                    // would require link-level receipt tracking
                    message.state = MessageState.SENT
                    // Schedule check for delivery - without receipts, we assume
                    // success after send. In a full implementation, we'd track
                    // link-level receipts for true delivery confirmation.
                    processingScope?.launch {
                        delay(500) // Brief delay before marking delivered
                        if (message.state == MessageState.SENT) {
                            message.state = MessageState.DELIVERED
                            message.deliveryCallback?.invoke(message)
                        }
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
            try {
                val messageHashHex = message.hash?.toHexString() ?: ""
                val resource = Resource.create(
                    data = lxmfData,
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
    private fun handleDeliveryPacket(data: ByteArray, destination: Destination) {
        // Determine delivery method based on context
        val method = DeliveryMethod.OPPORTUNISTIC

        // Process the delivery
        processInboundDelivery(data, method, destination)
    }

    /**
     * Handle a new delivery link being established.
     */
    private fun handleDeliveryLinkEstablished(link: Link, destination: Destination) {
        // Set up link callbacks for packets
        link.setPacketCallback { data, _ ->
            processInboundDelivery(data, DeliveryMethod.DIRECT, destination, link)
        }

        // Resource advertisement callback - accept all LXMF resources
        link.setResourceCallback { _: ResourceAdvertisement ->
            true
        }

        link.setResourceConcludedCallback { resource: Any ->
            handleResourceConcluded(resource, link)
        }

        // Store as backchannel link for replies if we know the remote identity
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
        try {
            // Unpack the message
            val message = LXMessage.unpackFromBytes(data, method)
            if (message == null) {
                println("Failed to unpack LXMF message")
                return
            }

            message.incoming = true
            message.method = method

            // Check for duplicates
            val transientId = message.transientId?.toHexString()
            if (transientId != null && locallyDeliveredTransientIds.contains(transientId)) {
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

            // TODO: Validate stamp if required

            // Store backchannel link for replies
            if (link != null) {
                val sourceHashHex = message.sourceHash.toHexString()
                backchannelLinks[sourceHashHex] = link
            }

            // Mark as delivered
            transientId?.let { locallyDeliveredTransientIds.add(it) }

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
                    outboundStampCosts[destHash.toHexString()] = stampCost
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
     */
    private fun establishPropagationLink(node: PropagationNode) {
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

                    // Request message list
                    requestMessageList(establishedLink)
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
            val buffer = java.io.ByteArrayOutputStream()
            val packer = org.msgpack.core.MessagePack.newDefaultPacker(buffer)
            packer.packArrayHeader(2)
            packer.packNil()  // wants = null (list mode)
            packer.packNil()  // haves = null (list mode)
            packer.close()

            link.request(
                path = LXMFConstants.MESSAGE_GET_PATH,
                data = buffer.toByteArray(),
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

            // Response is list of [transient_id, size] pairs
            val messageCount = unpacker.unpackArrayHeader()
            val wantedIds = mutableListOf<ByteArray>()

            for (i in 0 until messageCount) {
                unpacker.unpackArrayHeader() // Each item is [id, size]
                val idLen = unpacker.unpackBinaryHeader()
                val transientId = ByteArray(idLen)
                unpacker.readPayload(transientId)
                unpacker.skipValue() // Skip size

                // Check if we already have this message
                val idHex = transientId.toHexString()
                if (!locallyDeliveredTransientIds.contains(idHex)) {
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
            val buffer = java.io.ByteArrayOutputStream()
            val packer = org.msgpack.core.MessagePack.newDefaultPacker(buffer)

            // Send GET request: [wants, haves, limit]
            packer.packArrayHeader(3)

            // wants: list of transient IDs
            packer.packArrayHeader(wantedIds.size)
            for (id in wantedIds) {
                packer.packBinaryHeader(id.size)
                packer.writePayload(id)
            }

            // haves: empty list (we'll handle deletion later)
            packer.packArrayHeader(0)

            // limit: delivery limit
            packer.packInt(LXMFConstants.DELIVERY_LIMIT_KB)

            packer.close()

            link.request(
                path = LXMFConstants.MESSAGE_GET_PATH,
                data = buffer.toByteArray(),
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

            // Response is list of message data
            val messageCount = unpacker.unpackArrayHeader()
            var receivedCount = 0

            for (i in 0 until messageCount) {
                // Each message is wrapped in an array
                unpacker.unpackArrayHeader()
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
}
