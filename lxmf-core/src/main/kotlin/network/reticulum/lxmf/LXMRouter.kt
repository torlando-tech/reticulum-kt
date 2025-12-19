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

        // Check for existing link
        var link = directLinks[destHashHex] ?: backchannelLinks[destHashHex]

        if (link != null && link.status == LinkConstants.ACTIVE) {
            // Use existing link
            sendViaLink(message, link)
        } else {
            // Need to establish link
            // TODO: Implement link establishment
            message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
        }
    }

    /**
     * Process propagated message delivery.
     */
    private suspend fun processPropagatedDelivery(message: LXMessage) {
        // TODO: Implement propagation node delivery
        message.deliveryAttempts++

        if (message.deliveryAttempts > MAX_DELIVERY_ATTEMPTS) {
            message.state = MessageState.FAILED
            return
        }

        message.nextDeliveryAttempt = System.currentTimeMillis() + DELIVERY_RETRY_WAIT
    }

    /**
     * Send a message via an established link.
     */
    private fun sendViaLink(message: LXMessage, link: Link) {
        val packed = message.packed ?: return

        message.state = MessageState.SENDING

        if (message.representation == MessageRepresentation.PACKET) {
            // Send as packet over link
            // TODO: Implement link packet sending
        } else {
            // Send as resource for large messages
            // TODO: Implement resource transfer
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
        // Set up link callbacks
        link.setPacketCallback { data, packet ->
            processInboundDelivery(data, DeliveryMethod.DIRECT, destination, link)
        }

        // TODO: Set up resource callbacks for large messages
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
        destination: Destination,
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
}
