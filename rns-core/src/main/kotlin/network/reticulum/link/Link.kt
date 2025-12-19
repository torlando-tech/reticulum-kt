package network.reticulum.link

import network.reticulum.common.DestinationType
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.common.toHexString
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.Token
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import network.reticulum.channel.Channel
import network.reticulum.channel.ChannelOutlet
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Callbacks for link events.
 */
class LinkCallbacks {
    var linkEstablished: ((Link) -> Unit)? = null
    var linkClosed: ((Link) -> Unit)? = null
    var packet: ((ByteArray, Packet) -> Unit)? = null
    var resourceStarted: ((Any) -> Unit)? = null
    var resourceConcluded: ((Any) -> Unit)? = null
    var remoteIdentified: ((Link, Identity) -> Unit)? = null
}

/**
 * Represents an encrypted link to a remote peer.
 *
 * Links provide encrypted, authenticated communication channels between
 * two Reticulum peers. A link is established through a handshake protocol
 * that uses ECDH key exchange and Ed25519 signatures.
 *
 * Usage for initiator:
 * ```kotlin
 * val link = Link.create(destination) { link ->
 *     println("Link established!")
 * }
 * ```
 *
 * Usage for receiver (via Destination):
 * ```kotlin
 * destination.setLinkEstablishedCallback { link ->
 *     link.setPacketCallback { data, packet ->
 *         // Handle received data
 *     }
 * }
 * ```
 */
class Link private constructor(
    private val crypto: CryptoProvider,
    /** The destination this link connects to (null for incoming links). */
    val destination: Destination?,
    /** Whether this side initiated the link. */
    val initiator: Boolean,
    /** The owner destination for incoming links. */
    private val owner: Destination?,
    /** Encryption mode for this link. */
    val mode: Int = LinkConstants.MODE_DEFAULT
) {
    companion object {
        private val linkCounter = AtomicInteger(0)

        // Resource strategy constants
        const val ACCEPT_NONE = 0x00
        const val ACCEPT_APP = 0x01
        const val ACCEPT_ALL = 0x02

        /**
         * Create an outgoing link to a destination.
         *
         * @param destination The destination to connect to (must be SINGLE type)
         * @param establishedCallback Optional callback when link is established
         * @param closedCallback Optional callback when link is closed
         * @param mode Encryption mode (default: AES-256-CBC)
         * @return The new Link instance
         */
        fun create(
            destination: Destination,
            establishedCallback: ((Link) -> Unit)? = null,
            closedCallback: ((Link) -> Unit)? = null,
            mode: Int = LinkConstants.MODE_DEFAULT
        ): Link {
            require(destination.type == DestinationType.SINGLE) {
                "Links can only be established to SINGLE destination type"
            }

            val link = Link(
                crypto = defaultCryptoProvider(),
                destination = destination,
                initiator = true,
                owner = null,
                mode = mode
            )

            establishedCallback?.let { link.callbacks.linkEstablished = it }
            closedCallback?.let { link.callbacks.linkClosed = it }

            link.initializeAsInitiator()
            return link
        }

        /**
         * Validate an incoming link request and create a link if valid.
         *
         * @param owner The destination that received the link request
         * @param data The link request data
         * @param packet The link request packet
         * @return The new Link if valid, null otherwise
         */
        fun validateRequest(owner: Destination, data: ByteArray, packet: Packet): Link? {
            if (data.size != LinkConstants.ECPUBSIZE &&
                data.size != LinkConstants.ECPUBSIZE + LinkConstants.LINK_MTU_SIZE) {
                log("Invalid link request payload size: ${data.size}")
                return null
            }

            return try {
                val peerPubBytes = data.copyOfRange(0, LinkConstants.KEYSIZE)
                val peerSigPubBytes = data.copyOfRange(LinkConstants.KEYSIZE, LinkConstants.ECPUBSIZE)

                val link = Link(
                    crypto = defaultCryptoProvider(),
                    destination = null,
                    initiator = false,
                    owner = owner,
                    mode = modeFromLrPacket(packet)
                )

                link.loadPeer(peerPubBytes, peerSigPubBytes)
                link.initializeAsReceiver()
                link.setLinkId(packet)

                // Handle MTU signalling if present
                if (data.size == LinkConstants.ECPUBSIZE + LinkConstants.LINK_MTU_SIZE) {
                    link.mtu = mtuFromLrPacket(packet) ?: RnsConstants.MTU
                }

                link.mdu = LinkConstants.calculateMdu(link.mtu)
                link.attachedDestination = owner

                // Calculate establishment timeout
                link.establishmentTimeout = LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP *
                    maxOf(1, packet.hops) + LinkConstants.KEEPALIVE
                link.establishmentCost += packet.raw?.size ?: 0

                log("Validating link request ${link.linkId.toHexString()}")

                // Perform handshake and send proof
                link.handshake()
                link.prove()

                link.requestTime = System.currentTimeMillis()
                Transport.registerLink(link)

                link.lastInbound = System.currentTimeMillis()
                link.startWatchdog()

                log("Link request ${link.linkId.toHexString()} accepted")
                link

            } catch (e: Exception) {
                log("Validating link request failed: ${e.message}")
                null
            }
        }

        /**
         * Compute link ID from link request packet.
         */
        fun linkIdFromLrPacket(packet: Packet): ByteArray {
            var hashable = packet.getHashablePart()
            if (packet.data.size > LinkConstants.ECPUBSIZE) {
                val diff = packet.data.size - LinkConstants.ECPUBSIZE
                hashable = hashable.copyOf(hashable.size - diff)
            }
            return Hashes.truncatedHash(hashable)
        }

        /**
         * Extract MTU from link request packet.
         */
        private fun mtuFromLrPacket(packet: Packet): Int? {
            if (packet.data.size != LinkConstants.ECPUBSIZE + LinkConstants.LINK_MTU_SIZE) {
                return null
            }
            val offset = LinkConstants.ECPUBSIZE
            return ((packet.data[offset].toInt() and 0xFF) shl 16) or
                   ((packet.data[offset + 1].toInt() and 0xFF) shl 8) or
                   (packet.data[offset + 2].toInt() and 0xFF) and LinkConstants.MTU_BYTEMASK
        }

        /**
         * Extract mode from link request packet.
         */
        private fun modeFromLrPacket(packet: Packet): Int {
            if (packet.data.size > LinkConstants.ECPUBSIZE) {
                return (packet.data[LinkConstants.ECPUBSIZE].toInt() and LinkConstants.MODE_BYTEMASK) shr 5
            }
            return LinkConstants.MODE_DEFAULT
        }

        /**
         * Create signalling bytes for MTU and mode.
         */
        fun signallingBytes(mtu: Int, mode: Int): ByteArray {
            require(mode in LinkConstants.ENABLED_MODES) {
                "Requested link mode ${LinkConstants.modeDescription(mode)} not enabled"
            }
            val value = (mtu and LinkConstants.MTU_BYTEMASK) +
                        (((mode shl 5) and LinkConstants.MODE_BYTEMASK) shl 16)
            return byteArrayOf(
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        }

        private fun log(message: String) {
            val timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            )
            println("[$timestamp] [Link] $message")
        }
    }

    // Link identity
    var linkId: ByteArray = ByteArray(0)
        private set
    val hash: ByteArray get() = linkId

    // State
    var status: Int = LinkConstants.PENDING
        private set

    /** Reason for link teardown. */
    var teardownReason: Int = LinkConstants.TEARDOWN_REASON_UNKNOWN
        private set

    // Timing
    var rtt: Long? = null
        private set
    var mtu: Int = RnsConstants.MTU
        private set
    var mdu: Int = LinkConstants.calculateMdu()
        private set
    var establishmentCost: Int = 0
        private set
    var establishmentTimeout: Long = 0
        private set

    // Callbacks
    val callbacks = LinkCallbacks()

    // Timestamps
    private var requestTime: Long = 0
    private var activatedAt: Long = 0
    var lastInbound: Long = 0
        private set
    var lastOutbound: Long = 0
        private set
    var lastKeepalive: Long = 0
        private set
    var lastData: Long = 0
        private set
    var lastProof: Long = 0
        private set

    // Traffic counters
    val tx = AtomicLong(0)
    val rx = AtomicLong(0)
    val txBytes = AtomicLong(0)
    val rxBytes = AtomicLong(0)

    // Physical stats
    private var trackPhyStats: Boolean = false
    private var phyRssi: Int? = null
    private var phySnr: Float? = null
    private var phyQ: Float? = null

    // Timeout factors
    var trafficTimeoutFactor: Int = LinkConstants.TRAFFIC_TIMEOUT_FACTOR
    var keepaliveTimeoutFactor: Int = LinkConstants.KEEPALIVE_TIMEOUT_FACTOR
    var keepalive: Long = LinkConstants.KEEPALIVE
    var staleTime: Long = LinkConstants.STALE_TIME

    // Attached interface/destination
    var attachedDestination: Destination? = null
        private set
    private var remoteIdentity: Identity? = null

    // Cryptographic state
    private var prv: ByteArray? = null  // X25519 private key
    private var pub: ByteArray? = null  // X25519 public key
    private var sigPrv: ByteArray? = null  // Ed25519 private key
    private var sigPub: ByteArray? = null  // Ed25519 public key
    private var peerPub: ByteArray? = null  // Peer's X25519 public key
    private var peerSigPub: ByteArray? = null  // Peer's Ed25519 public key
    private var sharedKey: ByteArray? = null
    private var derivedKey: ByteArray? = null
    private var token: Token? = null

    // Watchdog
    private var watchdogThread: Thread? = null
    @Volatile private var watchdogActive = false

    // Resource tracking
    private val outgoingResources = mutableListOf<network.reticulum.resource.Resource>()
    private val incomingResources = mutableListOf<network.reticulum.resource.Resource>()
    private var resourceStrategy: Int = ACCEPT_NONE
    private var resourceCallback: ((network.reticulum.resource.ResourceAdvertisement) -> Boolean)? = null

    // Resource performance tracking
    private var lastResourceWindow: Int? = null
    private var lastResourceEifr: Float? = null
    private var establishmentRate: Float? = null
    private var expectedRate: Float? = null

    // Request/response tracking
    internal val pendingRequests = mutableListOf<RequestReceipt>()

    // Channel support
    private var _channel: Channel? = null

    /**
     * Initialize as link initiator (outgoing link).
     */
    private fun initializeAsInitiator() {
        // Generate X25519 keypair
        val x25519KeyPair = crypto.generateX25519KeyPair()
        prv = x25519KeyPair.privateKey
        pub = x25519KeyPair.publicKey

        // Generate Ed25519 keypair for this link
        val ed25519KeyPair = crypto.generateEd25519KeyPair()
        sigPrv = ed25519KeyPair.privateKey
        sigPub = ed25519KeyPair.publicKey

        // Calculate establishment timeout
        val hops = Transport.hopsTo(destination!!.hash) ?: 1
        establishmentTimeout = LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP * maxOf(1, hops) +
            LinkConstants.KEEPALIVE

        // Build request data
        val signallingBytes = signallingBytes(mtu, mode)
        val requestData = pub!! + sigPub!! + signallingBytes

        // Create and send link request
        val packet = Packet.createRaw(
            destinationHash = destination.hash,
            data = requestData,
            packetType = PacketType.LINKREQUEST,
            destinationType = destination.type,
            transportType = TransportType.BROADCAST
        )
        packet.pack()

        establishmentCost += packet.raw?.size ?: 0
        setLinkId(packet)

        Transport.registerLink(this)
        requestTime = System.currentTimeMillis()

        startWatchdog()
        Transport.outbound(packet)
        hadOutbound()

        log("Link request ${linkId.toHexString()} sent to ${destination.hexHash}")
    }

    /**
     * Load peer public keys.
     */
    private fun loadPeer(peerPubBytes: ByteArray, peerSigPubBytes: ByteArray) {
        this.peerPub = peerPubBytes.copyOf()
        this.peerSigPub = peerSigPubBytes.copyOf()
    }

    /**
     * Initialize as link receiver (incoming link).
     * Generates fresh X25519 keypair and uses owner's Ed25519 signing key.
     */
    private fun initializeAsReceiver() {
        // Generate fresh X25519 keypair for ECDH
        val x25519KeyPair = crypto.generateX25519KeyPair()
        prv = x25519KeyPair.privateKey
        pub = x25519KeyPair.publicKey

        // Use owner's Ed25519 signing key
        val ownerIdentity = owner?.identity
            ?: throw IllegalStateException("Cannot initialize receiver link: owner has no identity")
        sigPrv = ownerIdentity.sigPrv
        sigPub = ownerIdentity.sigPub
    }

    /**
     * Set link ID from packet.
     */
    private fun setLinkId(packet: Packet) {
        linkId = linkIdFromLrPacket(packet)
    }

    /**
     * Perform ECDH handshake and derive keys.
     */
    private fun handshake() {
        if (status != LinkConstants.PENDING || prv == null) {
            log("Handshake attempt with invalid state: $status")
            return
        }

        status = LinkConstants.HANDSHAKE

        // Perform ECDH
        sharedKey = crypto.x25519Exchange(prv!!, peerPub!!)

        // Derive encryption keys using HKDF
        val derivedKeyLength = LinkConstants.derivedKeyLength(mode)
        derivedKey = crypto.hkdf(
            length = derivedKeyLength,
            ikm = sharedKey!!,
            salt = getSalt(),
            info = getContext()
        )
    }

    /**
     * Send link proof to complete handshake (receiver side).
     */
    private fun prove() {
        val signallingBytes = signallingBytes(mtu, mode)
        val signedData = linkId + pub!! + sigPub!! + signallingBytes

        // Sign with owner's identity
        val signature = owner!!.identity!!.sign(signedData)
            ?: throw IllegalStateException("Cannot sign link proof")

        val proofData = signature + pub!! + signallingBytes

        val proof = Packet.createRaw(
            destinationHash = linkId,
            data = proofData,
            packetType = PacketType.PROOF,
            context = PacketContext.LRPROOF,
            destinationType = DestinationType.LINK
        )

        Transport.outbound(proof)
        establishmentCost += proof.raw?.size ?: 0
        hadOutbound()
    }

    /**
     * Validate link proof and complete handshake (initiator side).
     *
     * @param packet The proof packet
     * @return true if proof is valid
     */
    fun validateProof(packet: Packet): Boolean {
        try {
            if (status != LinkConstants.PENDING) return false

            val sigLength = RnsConstants.SIGNATURE_SIZE
            val pubSize = LinkConstants.KEYSIZE

            // Check mode matches
            val receivedMode = modeFromLpPacket(packet)
            if (receivedMode != mode) {
                log("Invalid link mode in proof: $receivedMode vs $mode")
                return false
            }

            // Extract peer public key and signature
            if (packet.data.size < sigLength + pubSize) {
                log("Proof packet too small: ${packet.data.size}")
                return false
            }

            val signature = packet.data.copyOfRange(0, sigLength)
            val peerPubBytes = packet.data.copyOfRange(sigLength, sigLength + pubSize)

            // Get peer's Ed25519 public key from destination's identity
            val peerSigPubBytes = destination!!.identity!!.getPublicKey()
                .copyOfRange(LinkConstants.KEYSIZE, LinkConstants.ECPUBSIZE)

            loadPeer(peerPubBytes, peerSigPubBytes)
            handshake()

            establishmentCost += packet.raw?.size ?: 0

            // Build signed data for verification
            var signallingBytes = ByteArray(0)
            if (packet.data.size > sigLength + pubSize) {
                val confirmedMtu = mtuFromLpPacket(packet)
                if (confirmedMtu != null) {
                    mtu = confirmedMtu
                    signallingBytes = signallingBytes(confirmedMtu, mode)
                }
            }

            val signedData = linkId + peerPub!! + peerSigPub!! + signallingBytes

            // Verify signature
            if (!destination.identity!!.validate(signature, signedData)) {
                log("Invalid link proof signature")
                return false
            }

            if (status != LinkConstants.HANDSHAKE) {
                log("Invalid state for proof validation: $status")
                return false
            }

            // Link is now active
            rtt = System.currentTimeMillis() - requestTime
            remoteIdentity = destination.identity
            mdu = LinkConstants.calculateMdu(mtu)
            status = LinkConstants.ACTIVE
            activatedAt = System.currentTimeMillis()

            // Calculate establishment rate (bytes per ms)
            val linkRtt = rtt
            if (linkRtt != null && linkRtt > 0 && establishmentCost > 0) {
                establishmentRate = establishmentCost.toFloat() / linkRtt.toFloat()
            }

            Transport.activateLink(this)

            log("Link ${linkId.toHexString()} established, RTT: ${rtt}ms")

            updateKeepalive()

            // Notify callback
            callbacks.linkEstablished?.let { callback ->
                thread(isDaemon = true) {
                    callback(this)
                }
            }

            return true

        } catch (e: Exception) {
            log("Error validating proof: ${e.message}")
            status = LinkConstants.CLOSED
            return false
        }
    }

    private fun modeFromLpPacket(packet: Packet): Int {
        val sigLength = RnsConstants.SIGNATURE_SIZE
        val pubSize = LinkConstants.KEYSIZE
        if (packet.data.size > sigLength + pubSize) {
            return packet.data[sigLength + pubSize].toInt() shr 5
        }
        return LinkConstants.MODE_DEFAULT
    }

    private fun mtuFromLpPacket(packet: Packet): Int? {
        val sigLength = RnsConstants.SIGNATURE_SIZE
        val pubSize = LinkConstants.KEYSIZE
        val mtuOffset = sigLength + pubSize
        if (packet.data.size >= mtuOffset + LinkConstants.LINK_MTU_SIZE) {
            return ((packet.data[mtuOffset].toInt() and 0xFF) shl 16) or
                   ((packet.data[mtuOffset + 1].toInt() and 0xFF) shl 8) or
                   (packet.data[mtuOffset + 2].toInt() and 0xFF) and LinkConstants.MTU_BYTEMASK
        }
        return null
    }

    /**
     * Get the salt for key derivation.
     */
    fun getSalt(): ByteArray = linkId

    /**
     * Get the context for key derivation.
     */
    fun getContext(): ByteArray? = null

    /**
     * Encrypt data for transmission over the link.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (token == null) {
            token = Token(derivedKey!!)
        }
        return token!!.encrypt(plaintext)
    }

    /**
     * Decrypt data received over the link.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (token == null) {
            token = Token(derivedKey!!)
        }
        return token!!.decrypt(ciphertext)
    }

    /**
     * Sign a message using this link's signing key.
     */
    fun sign(message: ByteArray): ByteArray {
        return crypto.ed25519Sign(sigPrv!!, message)
    }

    /**
     * Validate a signature from the peer.
     */
    fun validate(signature: ByteArray, message: ByteArray): Boolean {
        return try {
            crypto.ed25519Verify(peerSigPub!!, message, signature)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate and send a proof for a packet over this link.
     *
     * @param packet The packet to prove
     */
    fun provePacket(packet: Packet) {
        // Sign the packet hash
        val signature = sign(packet.packetHash)

        // Always use explicit proofs for links (for now)
        val proofData = packet.packetHash + signature

        // Create proof packet addressed to this link
        val proof = Packet.createRaw(
            destinationHash = linkId,
            data = proofData,
            packetType = PacketType.PROOF,
            destinationType = DestinationType.LINK
        )

        proof.send()
        hadOutbound(isData = false)
    }

    /**
     * Send data over the link.
     */
    fun send(data: ByteArray): Boolean {
        if (status != LinkConstants.ACTIVE) {
            return false
        }

        val encrypted = encrypt(data)
        val packet = Packet.createRaw(
            destinationHash = linkId,
            data = encrypted,
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK
        )

        return Transport.outbound(packet).also { sent ->
            if (sent) hadOutbound(isData = true)
        }
    }

    /**
     * Get the Channel for this link.
     * Creates the channel lazily on first access.
     *
     * @return The Channel for this link
     */
    fun getChannel(): Channel {
        if (_channel == null) {
            _channel = Channel(LinkChannelOutlet(this))
        }
        return _channel!!
    }

    /**
     * ChannelOutlet implementation that wraps a Link.
     * Provides the transport layer for Channel message delivery.
     */
    private class LinkChannelOutlet(private val link: Link) : ChannelOutlet {
        override val mdu: Int
            get() = link.mdu

        override val rtt: Long?
            get() = link.rtt

        override val isUsable: Boolean
            get() = link.status == LinkConstants.ACTIVE

        override val timedOut: Boolean
            get() = link.status == LinkConstants.CLOSED &&
                    link.teardownReason == LinkConstants.TEARDOWN_REASON_TIMEOUT

        override fun send(raw: ByteArray): Any? {
            if (!isUsable) return null

            val encrypted = link.encrypt(raw)
            val packet = Packet.createRaw(
                destinationHash = link.linkId,
                data = encrypted,
                packetType = PacketType.DATA,
                context = PacketContext.CHANNEL
            )

            return if (Transport.outbound(packet)) {
                link.hadOutbound(isData = true)
                packet
            } else {
                null
            }
        }

        override fun resend(packet: Any): Any? {
            if (packet !is Packet) return null
            val receipt = packet.resend()
            return if (receipt != null) packet else null
        }

        override fun getPacketState(packet: Any): Int {
            // TODO: Implement proper packet state tracking
            return 0
        }

        override fun setPacketTimeoutCallback(packet: Any, callback: ((Any) -> Unit)?, timeout: Long?) {
            // TODO: Implement packet timeout callback
        }

        override fun setPacketDeliveredCallback(packet: Any, callback: ((Any) -> Unit)?) {
            // TODO: Implement packet delivered callback
        }

        override fun getPacketId(packet: Any): Any {
            return if (packet is Packet) packet.packetHash else packet
        }
    }

    /**
     * Send a request to the remote peer.
     *
     * Requests allow querying the remote peer and receiving a response. The request
     * is sent over the encrypted link, and the response is delivered via callbacks.
     *
     * For small requests (<=MDU), the request is sent as a packet. For larger requests,
     * it's automatically sent as a resource.
     *
     * @param path The request path (identifies the handler on the remote side)
     * @param data The request data (will be serialized with msgpack)
     * @param responseCallback Optional callback when response is received
     * @param failedCallback Optional callback when request fails
     * @param progressCallback Optional callback for response progress
     * @param timeout Optional timeout in milliseconds (if null, calculated from RTT)
     * @return RequestReceipt to track the request, or null if link is not active
     */
    fun request(
        path: String,
        data: Any? = null,
        responseCallback: ((RequestReceipt) -> Unit)? = null,
        failedCallback: ((RequestReceipt) -> Unit)? = null,
        progressCallback: ((RequestReceipt) -> Unit)? = null,
        timeout: Long? = null
    ): RequestReceipt? {
        if (status != LinkConstants.ACTIVE) {
            log("Cannot send request: link not active")
            return null
        }

        // Hash the path
        val pathHash = Hashes.truncatedHash(path.toByteArray(Charsets.UTF_8))

        // Pack the request: [timestamp, path_hash, data]
        val timestamp = System.currentTimeMillis()
        val packedRequest = try {
            packRequest(timestamp, pathHash, data)
        } catch (e: Exception) {
            log("Error packing request: ${e.message}")
            return null
        }

        // Calculate timeout if not provided
        val actualTimeout = timeout ?: calculateRequestTimeout()

        // Generate request ID
        val requestId = Hashes.truncatedHash(packedRequest)

        // Create the RequestReceipt
        val receipt = RequestReceipt(
            link = this,
            requestId = requestId,
            requestSize = packedRequest.size,
            responseCallback = responseCallback,
            failedCallback = failedCallback,
            progressCallback = progressCallback,
            timeout = actualTimeout
        )

        // Add to pending requests
        synchronized(pendingRequests) {
            pendingRequests.add(receipt)
        }

        // Decide whether to send as packet or resource
        if (packedRequest.size <= mdu) {
            // Send as packet
            val encrypted = encrypt(packedRequest)
            val packet = Packet.createRaw(
                destinationHash = linkId,
                data = encrypted,
                packetType = PacketType.DATA,
                context = PacketContext.REQUEST,
                destinationType = DestinationType.LINK
            )

            if (!Transport.outbound(packet)) {
                log("Failed to send request packet")
                synchronized(pendingRequests) {
                    pendingRequests.remove(receipt)
                }
                return null
            }

            receipt.startedAt = System.currentTimeMillis()
            hadOutbound(isData = true)

            // TODO: Set up timeout monitoring thread
            // For now, the timeout will be handled by the watchdog or caller

        } else {
            // Send as resource
            log("Sending request ${requestId.toHexString()} as resource")
            // TODO: Implement large request sending via Resource
            // This requires Resource class to support request_id and is_response flags
            log("Large requests not yet fully implemented")
            synchronized(pendingRequests) {
                pendingRequests.remove(receipt)
            }
            return null
        }

        return receipt
    }

    /**
     * Identify to the remote peer.
     *
     * This method allows the link initiator to reveal their identity to the remote peer
     * over the encrypted link. This preserves initiator anonymity while allowing authentication.
     *
     * Only works if:
     * - This is the initiator side
     * - Link is active
     *
     * @param identity The identity to identify as
     * @return true if identification was sent, false otherwise
     */
    fun identify(identity: Identity): Boolean {
        if (!initiator) {
            log("Cannot identify: only initiator can identify")
            return false
        }

        if (status != LinkConstants.ACTIVE) {
            log("Cannot identify: link not active")
            return false
        }

        // Build signed data: link_id + public_key
        val publicKey = identity.getPublicKey()
        val signedData = linkId + publicKey

        // Sign the data
        val signature = identity.sign(signedData)
            ?: return false.also { log("Failed to sign identity data") }

        // Build proof data: public_key + signature
        val proofData = publicKey + signature

        // Encrypt and send
        val encrypted = encrypt(proofData)
        val packet = Packet.createRaw(
            destinationHash = linkId,
            data = encrypted,
            packetType = PacketType.DATA,
            context = PacketContext.LINKIDENTIFY,
            destinationType = DestinationType.LINK
        )

        return Transport.outbound(packet).also { sent ->
            if (sent) {
                hadOutbound(isData = true)
                log("Sent identity to remote peer")
            }
        }
    }

    /**
     * Set the resource strategy for this link.
     *
     * @param strategy One of ACCEPT_NONE, ACCEPT_ALL, or ACCEPT_APP
     * @return true if strategy was set successfully, false otherwise
     */
    fun setResourceStrategy(strategy: Int): Boolean {
        return when (strategy) {
            ACCEPT_NONE, ACCEPT_ALL, ACCEPT_APP -> {
                resourceStrategy = strategy
                true
            }
            else -> {
                log("Invalid resource strategy: $strategy")
                false
            }
        }
    }

    /**
     * Get the current resource strategy for this link.
     *
     * @return The current resource strategy
     */
    fun getResourceStrategy(): Int = resourceStrategy

    /**
     * Set the resource callback for ACCEPT_APP strategy.
     *
     * @param callback Function that takes a ResourceAdvertisement and returns true to accept
     */
    fun setResourceCallback(callback: ((network.reticulum.resource.ResourceAdvertisement) -> Boolean)?) {
        resourceCallback = callback
    }

    /**
     * Pack a request using msgpack.
     *
     * Format: [timestamp, path_hash, data]
     */
    private fun packRequest(timestamp: Long, pathHash: ByteArray, data: Any?): ByteArray {
        val output = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(output)

        packer.packArrayHeader(3)
        packer.packLong(timestamp)
        packer.packBinaryHeader(pathHash.size)
        packer.writePayload(pathHash)

        // Pack data (can be null)
        packValue(packer, data)

        packer.close()
        return output.toByteArray()
    }

    /**
     * Pack a generic value using msgpack.
     */
    private fun packValue(packer: org.msgpack.core.MessagePacker, value: Any?) {
        when (value) {
            null -> packer.packNil()
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is String -> packer.packString(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Boolean -> packer.packBoolean(value)
            is Float -> packer.packFloat(value)
            is Double -> packer.packDouble(value)
            is Map<*, *> -> packMap(packer, value)
            is List<*> -> packList(packer, value)
            else -> {
                // For other types, convert to string
                packer.packString(value.toString())
            }
        }
    }

    /**
     * Pack a map using msgpack.
     */
    private fun packMap(packer: org.msgpack.core.MessagePacker, map: Map<*, *>) {
        packer.packMapHeader(map.size)
        for ((key, value) in map) {
            // Pack key
            packValue(packer, key)
            // Pack value recursively
            packValue(packer, value)
        }
    }

    /**
     * Pack a list using msgpack.
     */
    private fun packList(packer: org.msgpack.core.MessagePacker, list: List<*>) {
        packer.packArrayHeader(list.size)
        for (item in list) {
            packValue(packer, item)
        }
    }

    /**
     * Calculate request timeout based on RTT.
     */
    private fun calculateRequestTimeout(): Long {
        val linkRtt = rtt ?: LinkConstants.KEEPALIVE_MAX
        // Python: timeout = self.rtt * self.traffic_timeout_factor + RNS.Resource.RESPONSE_MAX_GRACE_TIME*1.125
        // For simplicity, use RTT * 6 + 5 seconds
        return linkRtt * trafficTimeoutFactor + 5000L
    }

    /**
     * Close the link.
     *
     * @param reason The teardown reason (defaults based on initiator status)
     */
    fun teardown(reason: Int = LinkConstants.TEARDOWN_REASON_UNKNOWN) {
        if (status == LinkConstants.CLOSED) return

        val previousStatus = status
        status = LinkConstants.CLOSED

        // Set teardown reason
        teardownReason = if (reason == LinkConstants.TEARDOWN_REASON_UNKNOWN) {
            if (initiator) LinkConstants.TEARDOWN_REASON_INITIATOR_CLOSED
            else LinkConstants.TEARDOWN_REASON_DESTINATION_CLOSED
        } else {
            reason
        }

        watchdogActive = false

        if (previousStatus == LinkConstants.ACTIVE) {
            // Send close packet
            val closeData = linkId
            val packet = Packet.createRaw(
                destinationHash = linkId,
                data = closeData,
                packetType = PacketType.DATA,
                context = PacketContext.LINKCLOSE,
                destinationType = DestinationType.LINK
            )
            Transport.outbound(packet)
        }

        Transport.deregisterLink(this)

        callbacks.linkClosed?.let { callback ->
            thread(isDaemon = true) {
                callback(this)
            }
        }

        log("Link ${linkId.toHexString()} closed (reason: $teardownReason)")
    }

    /**
     * Record outbound activity.
     */
    fun hadOutbound(isKeepalive: Boolean = false, isData: Boolean = false) {
        lastOutbound = System.currentTimeMillis()
        if (isKeepalive) {
            lastKeepalive = lastOutbound
        }
        if (isData) {
            lastData = lastOutbound
        }
    }

    /**
     * Record inbound activity.
     */
    fun hadInbound(isData: Boolean = false) {
        lastInbound = System.currentTimeMillis()
        if (isData) {
            lastData = lastInbound
        }
    }

    /**
     * Get link age in milliseconds.
     */
    fun getAge(): Long? {
        return if (activatedAt > 0) {
            System.currentTimeMillis() - activatedAt
        } else null
    }

    /**
     * Time since last inbound packet.
     */
    fun noInboundFor(): Long {
        val activeAt = if (activatedAt > 0) activatedAt else 0
        val lastIn = maxOf(lastInbound, activeAt)
        return System.currentTimeMillis() - lastIn
    }

    /**
     * Time since last outbound packet.
     */
    fun noOutboundFor(): Long {
        return System.currentTimeMillis() - lastOutbound
    }

    /**
     * Time since last data packet (excludes keepalives).
     */
    fun noDataFor(): Long {
        return System.currentTimeMillis() - lastData
    }

    /**
     * Time since any activity.
     */
    fun inactiveFor(): Long {
        return minOf(noInboundFor(), noOutboundFor())
    }

    /**
     * Get the remote peer's identity if known.
     */
    fun getRemoteIdentity(): Identity? = remoteIdentity

    /**
     * Update keepalive interval based on RTT.
     */
    private fun updateKeepalive() {
        val linkRtt = rtt ?: return
        val rttKeepalive = (linkRtt * LinkConstants.KEEPALIVE_MAX_RTT).toLong()
        keepalive = maxOf(LinkConstants.KEEPALIVE_MIN, minOf(rttKeepalive, LinkConstants.KEEPALIVE_MAX))
        staleTime = LinkConstants.STALE_FACTOR * keepalive
    }

    /**
     * Start the watchdog thread.
     */
    private fun startWatchdog() {
        watchdogActive = true
        watchdogThread = thread(name = "Link-watchdog-${linkCounter.incrementAndGet()}", isDaemon = true) {
            watchdogLoop()
        }
    }

    /**
     * Watchdog loop for timeout detection.
     */
    private fun watchdogLoop() {
        while (watchdogActive && status != LinkConstants.CLOSED) {
            try {
                Thread.sleep(minOf(keepalive / 4, LinkConstants.WATCHDOG_MAX_SLEEP))
                checkTimeout()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log("Watchdog error: ${e.message}")
            }
        }
    }

    /**
     * Check for link timeout.
     * Matches Python RNS watchdog state machine.
     */
    private fun checkTimeout() {
        val now = System.currentTimeMillis()

        when (status) {
            LinkConstants.PENDING -> {
                // Link was initiated, but no response from destination yet
                if (now >= requestTime + establishmentTimeout) {
                    log("Link establishment timed out")
                    teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT)
                }
            }

            LinkConstants.HANDSHAKE -> {
                // Waiting for link proof or RTT packet
                if (now >= requestTime + establishmentTimeout) {
                    if (initiator) {
                        log("Timeout waiting for link request proof")
                    } else {
                        log("Timeout waiting for RTT packet from link initiator")
                    }
                    teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT)
                }
            }

            LinkConstants.ACTIVE -> {
                val activatedTime = activatedAt ?: 0L
                val lastInbound = maxOf(maxOf(this.lastInbound, lastProof), activatedTime)
                val inactivity = now - lastInbound

                if (inactivity >= keepalive) {
                    // Send keepalive if we're the initiator and haven't sent one recently
                    if (initiator && (now - lastKeepalive) >= keepalive) {
                        sendKeepalive()
                    }

                    // Transition to stale if no inbound for stale_time
                    if (inactivity >= staleTime) {
                        status = LinkConstants.STALE
                        log("Link marked stale")
                    }
                }
            }

            LinkConstants.STALE -> {
                // In Python, STALE immediately sends teardown and closes
                sendTeardownPacket()
                teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT)
            }
        }
    }

    /**
     * Send a teardown packet to the remote end.
     */
    private fun sendTeardownPacket() {
        try {
            val teardownData = linkId  // Send link ID as teardown data
            val packet = Packet.createRaw(
                destinationHash = linkId,
                data = encrypt(teardownData),
                packetType = PacketType.DATA,
                context = PacketContext.LINKCLOSE,
                destinationType = DestinationType.LINK
            )
            Transport.outbound(packet)
        } catch (e: Exception) {
            log("Error sending teardown packet: ${e.message}")
        }
    }

    /**
     * Send a keepalive packet.
     */
    private fun sendKeepalive() {
        if (status != LinkConstants.ACTIVE && status != LinkConstants.STALE) return

        val packet = Packet.createRaw(
            destinationHash = linkId,
            data = ByteArray(0),
            packetType = PacketType.DATA,
            context = PacketContext.KEEPALIVE,
            destinationType = DestinationType.LINK
        )

        Transport.outbound(packet)
        hadOutbound(isKeepalive = true)
    }

    /**
     * Receive and process an incoming packet on this link.
     *
     * This is the main packet processing method that handles all link traffic including:
     * - Regular data packets
     * - Keepalives
     * - Link identification
     * - RTT measurements
     * - Resource advertisements and transfers
     * - Requests and responses
     * - Channel data
     * - Link close packets
     *
     * @param packet The incoming packet to process
     */
    fun receive(packet: Packet) {
        // Skip closed links, and skip initiator keepalive responses
        if (status == LinkConstants.CLOSED) return
        if (initiator && packet.context == PacketContext.KEEPALIVE &&
            packet.data.contentEquals(byteArrayOf(0xFF.toByte()))) {
            return
        }

        // Record inbound activity
        lastInbound = System.currentTimeMillis()
        if (packet.context != PacketContext.KEEPALIVE) {
            lastData = lastInbound
        }

        // Update counters
        rx.incrementAndGet()
        rxBytes.addAndGet(packet.data.size.toLong())

        // Revive stale link
        if (status == LinkConstants.STALE) {
            status = LinkConstants.ACTIVE
            log("Link ${linkId.toHexString()} revived from stale state")
        }

        // Process based on packet type
        when (packet.packetType) {
            PacketType.DATA -> processDataPacket(packet)
            PacketType.PROOF -> processProofPacket(packet)
            else -> {
                log("Ignoring packet with unexpected type: ${packet.packetType}")
            }
        }
    }

    /**
     * Process DATA type packets with various contexts.
     */
    private fun processDataPacket(packet: Packet) {
        when (packet.context) {
            PacketContext.NONE -> processRegularData(packet)
            PacketContext.LINKIDENTIFY -> processLinkIdentify(packet)
            PacketContext.REQUEST -> processRequest(packet)
            PacketContext.RESPONSE -> processResponse(packet)
            PacketContext.LRRTT -> processLrrtt(packet)
            PacketContext.LINKCLOSE -> processLinkClose(packet)
            PacketContext.RESOURCE_ADV -> processResourceAdv(packet)
            PacketContext.RESOURCE_REQ -> processResourceReq(packet)
            PacketContext.RESOURCE_HMU -> processResourceHmu(packet)
            PacketContext.RESOURCE_ICL -> processResourceIcl(packet)
            PacketContext.RESOURCE_RCL -> processResourceRcl(packet)
            PacketContext.RESOURCE -> processResource(packet)
            PacketContext.KEEPALIVE -> processKeepalive(packet)
            PacketContext.CHANNEL -> processChannel(packet)
            PacketContext.COMMAND -> processCommand(packet)
            PacketContext.COMMAND_STATUS -> processCommandStatus(packet)
            else -> {
                log("Unhandled packet context: ${packet.context}")
            }
        }
    }

    /**
     * Process regular data packets (context = NONE).
     */
    private fun processRegularData(packet: Packet) {
        val plaintext = decrypt(packet.data) ?: return

        // Invoke packet callback
        callbacks.packet?.let { callback ->
            thread(isDaemon = true) {
                try {
                    callback(plaintext, packet)
                } catch (e: Exception) {
                    log("Error in packet callback: ${e.message}")
                }
            }
        }

        // TODO: Handle proof strategies (PROVE_ALL, PROVE_APP)
        // This requires Destination proof strategy implementation
    }

    /**
     * Process link identification packets.
     */
    private fun processLinkIdentify(packet: Packet) {
        val plaintext = decrypt(packet.data) ?: return

        // Only receivers process identity packets, and format is:
        // public_key (32 bytes) + signature (64 bytes)
        if (!initiator && plaintext.size == RnsConstants.KEY_SIZE + RnsConstants.SIGNATURE_SIZE) {
            try {
                val publicKey = plaintext.copyOfRange(0, RnsConstants.KEY_SIZE)
                val signedData = linkId + publicKey
                val signature = plaintext.copyOfRange(
                    RnsConstants.KEY_SIZE,
                    RnsConstants.KEY_SIZE + RnsConstants.SIGNATURE_SIZE
                )

                // Load and validate the identity
                val identity = Identity.fromPublicKey(publicKey)
                if (identity.validate(signature, signedData)) {
                    remoteIdentity = identity
                    log("Remote peer identified: ${identity.hexHash}")

                    // Invoke callback
                    callbacks.remoteIdentified?.let { callback ->
                        thread(isDaemon = true) {
                            try {
                                callback(this, identity)
                            } catch (e: Exception) {
                                log("Error in remote identified callback: ${e.message}")
                            }
                        }
                    }
                } else {
                    log("Invalid signature in link identify packet")
                }
            } catch (e: Exception) {
                log("Error processing link identify: ${e.message}")
            }
        }
    }

    /**
     * Process request packets.
     */
    private fun processRequest(packet: Packet) {
        try {
            val requestId = packet.truncatedHash
            val packedRequest = decrypt(packet.data) ?: return

            // Unpack msgpack request: [timestamp, pathHash, data]
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(packedRequest)
            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize != 3) {
                log("Invalid request format: expected 3 elements, got $arraySize")
                return
            }

            val timestamp = unpacker.unpackLong()
            val pathHashSize = unpacker.unpackBinaryHeader()
            val pathHash = ByteArray(pathHashSize)
            unpacker.readPayload(pathHash)

            val requestData = if (unpacker.tryUnpackNil()) {
                null
            } else {
                val dataSize = unpacker.unpackBinaryHeader()
                val data = ByteArray(dataSize)
                unpacker.readPayload(data)
                data
            }
            unpacker.close()

            // Pass to handleRequest
            val unpackedRequest = listOf(timestamp, pathHash, requestData)
            handleRequest(requestId, unpackedRequest)

        } catch (e: Exception) {
            log("Error processing request: ${e.message}")
        }
    }

    /**
     * Process response packets.
     */
    private fun processResponse(packet: Packet) {
        try {
            val packedResponse = decrypt(packet.data) ?: return

            // Unpack msgpack response: [requestId, responseData]
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(packedResponse)
            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize != 2) {
                log("Invalid response format: expected 2 elements, got $arraySize")
                return
            }

            val requestIdSize = unpacker.unpackBinaryHeader()
            val requestId = ByteArray(requestIdSize)
            unpacker.readPayload(requestId)

            val responseDataSize = unpacker.unpackBinaryHeader()
            val responseData = ByteArray(responseDataSize)
            unpacker.readPayload(responseData)
            unpacker.close()

            // Calculate transfer size (size of msgpack-encoded response data)
            val transferSize = responseDataSize + 2 // +2 for msgpack overhead

            // Pass to handleResponse
            handleResponse(requestId, responseData, responseDataSize, transferSize)

        } catch (e: Exception) {
            log("Error processing response: ${e.message}")
        }
    }

    /**
     * Process LRRTT (Link RTT) packets.
     */
    private fun processLrrtt(packet: Packet) {
        // Only receivers process RTT packets
        if (!initiator) {
            rttPacket(packet)
        }
    }

    /**
     * Process RTT measurement packet.
     */
    private fun rttPacket(packet: Packet) {
        try {
            val measuredRtt = System.currentTimeMillis() - requestTime
            val plaintext = decrypt(packet.data) ?: return

            // Unpack msgpack RTT value from plaintext
            // Python sends RTT as a float in seconds
            val remoteRtt = try {
                val unpacker = MessagePack.newDefaultUnpacker(plaintext)
                val rttSeconds = unpacker.unpackDouble()
                unpacker.close()
                (rttSeconds * 1000).toLong()  // Convert seconds to ms
            } catch (e: Exception) {
                // Fallback: try as float
                try {
                    val unpacker = MessagePack.newDefaultUnpacker(plaintext)
                    val rttSeconds = unpacker.unpackFloat()
                    unpacker.close()
                    (rttSeconds * 1000).toLong()
                } catch (e2: Exception) {
                    measuredRtt  // Use measured if unpacking fails
                }
            }

            // Use max of measured and remote RTT
            rtt = maxOf(measuredRtt, remoteRtt)
            status = LinkConstants.ACTIVE
            activatedAt = System.currentTimeMillis()

            // Calculate establishment rate (bytes per ms)
            val linkRtt = rtt
            if (linkRtt != null && linkRtt > 0 && establishmentCost > 0) {
                establishmentRate = establishmentCost.toFloat() / linkRtt.toFloat()
            }

            log("Link RTT measured: ${rtt}ms")
            updateKeepalive()

            // Notify callback
            callbacks.linkEstablished?.let { callback ->
                thread(isDaemon = true) {
                    try {
                        callback(this)
                    } catch (e: Exception) {
                        log("Error in link established callback: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            log("Error processing RTT packet: ${e.message}")
            teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT)
        }
    }

    /**
     * Process link close packets.
     */
    private fun processLinkClose(packet: Packet) {
        try {
            val plaintext = decrypt(packet.data) ?: return

            // Verify the close is for this link
            if (plaintext.contentEquals(linkId)) {
                log("Received link close packet")
                val closeReason = if (initiator) {
                    LinkConstants.TEARDOWN_REASON_DESTINATION_CLOSED
                } else {
                    LinkConstants.TEARDOWN_REASON_INITIATOR_CLOSED
                }
                teardown(closeReason)
            }
        } catch (e: Exception) {
            log("Error processing link close: ${e.message}")
        }
    }

    /**
     * Process resource advertisement packets.
     */
    private fun processResourceAdv(packet: Packet) {
        try {
            // Decrypt the advertisement data
            val plaintext = decrypt(packet.data) ?: return

            // Parse the advertisement
            val advertisement = network.reticulum.resource.ResourceAdvertisement.unpack(plaintext)
            if (advertisement == null) {
                log("Failed to unpack resource advertisement")
                return
            }

            // Check if this is a request response
            if (network.reticulum.resource.ResourceAdvertisement.isRequest(plaintext)) {
                // This is a request being sent as a resource
                val resource = network.reticulum.resource.Resource.accept(
                    advertisement = advertisement,
                    link = this,
                    callback = { res -> requestResourceConcluded(res) }
                )
                if (resource != null) {
                    registerIncomingResource(resource)
                }
                return
            }

            // Check if this is a response to a pending request
            if (network.reticulum.resource.ResourceAdvertisement.isResponse(plaintext)) {
                val requestId = network.reticulum.resource.ResourceAdvertisement.readRequestId(plaintext)
                if (requestId != null) {
                    // Find matching pending request
                    val pendingRequest = synchronized(pendingRequests) {
                        pendingRequests.find { it.requestId.contentEquals(requestId) }
                    }

                    if (pendingRequest != null) {
                        val resource = network.reticulum.resource.Resource.accept(
                            advertisement = advertisement,
                            link = this,
                            callback = { res -> responseResourceConcluded(res) },
                            progressCallback = { res -> pendingRequest.updateProgress(res.progress) }
                        )
                        if (resource != null) {
                            val responseSize = network.reticulum.resource.ResourceAdvertisement.readSize(plaintext)
                            val transferSize = network.reticulum.resource.ResourceAdvertisement.readTransferSize(plaintext)
                            if (responseSize != null) {
                                pendingRequest.responseSize = responseSize
                            }
                            if (transferSize != null) {
                                if (pendingRequest.responseTransferSize == null) {
                                    pendingRequest.responseTransferSize = 0
                                }
                                pendingRequest.responseTransferSize = (pendingRequest.responseTransferSize ?: 0) + transferSize
                            }
                            if (pendingRequest.startedAt == null) {
                                pendingRequest.startedAt = System.currentTimeMillis()
                            }
                            registerIncomingResource(resource)
                        }
                    }
                }
                return
            }

            // General resource advertisement - check strategy
            when (resourceStrategy) {
                ACCEPT_NONE -> {
                    log("Rejecting resource ${advertisement.hash.toHexString()} (strategy: ACCEPT_NONE)")
                    network.reticulum.resource.Resource.reject(advertisement, this)
                }
                ACCEPT_ALL -> {
                    log("Accepting resource ${advertisement.hash.toHexString()} (strategy: ACCEPT_ALL)")
                    val resource = network.reticulum.resource.Resource.accept(
                        advertisement = advertisement,
                        link = this,
                        callback = { res -> resourceConcluded(res) }
                    )
                    if (resource != null) {
                        registerIncomingResource(resource)
                        callbacks.resourceStarted?.let { callback ->
                            thread(isDaemon = true) {
                                try {
                                    callback(resource)
                                } catch (e: Exception) {
                                    log("Error in resource started callback: ${e.message}")
                                }
                            }
                        }
                    }
                }
                ACCEPT_APP -> {
                    val callback = resourceCallback
                    if (callback != null) {
                        try {
                            if (callback(advertisement)) {
                                log("Accepting resource ${advertisement.hash.toHexString()} (strategy: ACCEPT_APP, callback returned true)")
                                val resource = network.reticulum.resource.Resource.accept(
                                    advertisement = advertisement,
                                    link = this,
                                    callback = { res -> resourceConcluded(res) }
                                )
                                if (resource != null) {
                                    registerIncomingResource(resource)
                                    callbacks.resourceStarted?.let { startCallback ->
                                        thread(isDaemon = true) {
                                            try {
                                                startCallback(resource)
                                            } catch (e: Exception) {
                                                log("Error in resource started callback: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            } else {
                                log("Rejecting resource ${advertisement.hash.toHexString()} (strategy: ACCEPT_APP, callback returned false)")
                                network.reticulum.resource.Resource.reject(advertisement, this)
                            }
                        } catch (e: Exception) {
                            log("Error in resource callback: ${e.message}")
                            network.reticulum.resource.Resource.reject(advertisement, this)
                        }
                    } else {
                        log("Rejecting resource ${advertisement.hash.toHexString()} (strategy: ACCEPT_APP, no callback set)")
                        network.reticulum.resource.Resource.reject(advertisement, this)
                    }
                }
            }

        } catch (e: Exception) {
            log("Error processing resource advertisement: ${e.message}")
        }
    }

    /**
     * Process resource request packets.
     */
    private fun processResourceReq(packet: Packet) {
        try {
            val plaintext = decrypt(packet.data) ?: return

            // Extract resource hash from request
            // Format is either:
            // - [flags][hash] for normal requests
            // - [HASHMAP_IS_EXHAUSTED][maphash][hash] for exhausted hashmap requests
            val resourceHash = if (plaintext.isNotEmpty() &&
                plaintext[0].toInt() and 0xFF == network.reticulum.resource.ResourceConstants.HASHMAP_IS_EXHAUSTED) {
                // Exhausted hashmap format: skip first byte + MAPHASH_LEN
                val offset = 1 + network.reticulum.resource.ResourceConstants.MAPHASH_LEN
                if (plaintext.size >= offset + RnsConstants.TRUNCATED_HASH_BYTES) {
                    plaintext.copyOfRange(offset, offset + RnsConstants.TRUNCATED_HASH_BYTES)
                } else {
                    log("Invalid exhausted hashmap request size: ${plaintext.size}")
                    return
                }
            } else {
                // Normal format: skip first byte (flags)
                if (plaintext.size >= 1 + RnsConstants.TRUNCATED_HASH_BYTES) {
                    plaintext.copyOfRange(1, 1 + RnsConstants.TRUNCATED_HASH_BYTES)
                } else {
                    log("Invalid resource request size: ${plaintext.size}")
                    return
                }
            }

            // Find matching outgoing resource
            val resource = synchronized(outgoingResources) {
                outgoingResources.find { it.hash.contentEquals(resourceHash) }
            }

            if (resource == null) {
                log("Received request for unknown resource: ${resourceHash.toHexString()}")
                return
            }

            // TODO: Process the request when Resource.request() method is available
            // This would typically:
            // 1. Check if packet.packetHash is not in resource.reqHashlist (avoid duplicates)
            // 2. Add packet.packetHash to resource.reqHashlist
            // 3. Call resource.request(plaintext) to send requested parts
            log("Processing request for resource ${resourceHash.toHexString()}")

        } catch (e: Exception) {
            log("Error processing resource request: ${e.message}")
        }
    }

    /**
     * Process resource hashmap update packets.
     */
    private fun processResourceHmu(packet: Packet) {
        try {
            val plaintext = decrypt(packet.data) ?: return

            // Extract resource hash (first 16 bytes)
            if (plaintext.size < RnsConstants.TRUNCATED_HASH_BYTES) {
                log("Invalid HMU packet size: ${plaintext.size}")
                return
            }

            val resourceHash = plaintext.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

            // Find matching incoming resource
            val resource = synchronized(incomingResources) {
                incomingResources.find { it.hash.contentEquals(resourceHash) }
            }

            if (resource == null) {
                log("Received HMU for unknown resource: ${resourceHash.toHexString()}")
                return
            }

            // TODO: Process hashmap update when Resource.hashmapUpdatePacket() method is available
            // This would update the resource's received parts bitmap and trigger next part requests
            log("Processing HMU for resource ${resourceHash.toHexString()}")

        } catch (e: Exception) {
            log("Error processing resource HMU: ${e.message}")
        }
    }

    /**
     * Process resource initiator cancel packets.
     */
    private fun processResourceIcl(packet: Packet) {
        try {
            val plaintext = decrypt(packet.data) ?: return

            // Extract resource hash (first 16 bytes)
            if (plaintext.size < RnsConstants.TRUNCATED_HASH_BYTES) {
                log("Invalid ICL packet size: ${plaintext.size}")
                return
            }

            val resourceHash = plaintext.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

            // Find matching incoming resource (we're receiving, initiator is cancelling)
            val resource = synchronized(incomingResources) {
                incomingResources.find { it.hash.contentEquals(resourceHash) }
            }

            if (resource == null) {
                log("Received ICL for unknown resource: ${resourceHash.toHexString()}")
                return
            }

            log("Initiator cancelled resource ${resourceHash.toHexString()}")
            resource.cancel()

        } catch (e: Exception) {
            log("Error processing resource ICL: ${e.message}")
        }
    }

    /**
     * Process resource receiver cancel packets.
     */
    private fun processResourceRcl(packet: Packet) {
        try {
            val plaintext = decrypt(packet.data) ?: return

            // Extract resource hash (first 16 bytes)
            if (plaintext.size < RnsConstants.TRUNCATED_HASH_BYTES) {
                log("Invalid RCL packet size: ${plaintext.size}")
                return
            }

            val resourceHash = plaintext.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

            // Find matching outgoing resource (we're sending, receiver is rejecting)
            val resource = synchronized(outgoingResources) {
                outgoingResources.find { it.hash.contentEquals(resourceHash) }
            }

            if (resource == null) {
                log("Received RCL for unknown resource: ${resourceHash.toHexString()}")
                return
            }

            log("Receiver rejected resource ${resourceHash.toHexString()}")
            // TODO: Call resource.rejected() when method is available
            resource.cancel()

        } catch (e: Exception) {
            log("Error processing resource RCL: ${e.message}")
        }
    }

    /**
     * Process resource data packets.
     */
    private fun processResource(packet: Packet) {
        // TODO: Implement resource data handling
        // This requires:
        // - Finding matching incoming resource
        // - Passing packet to resource for processing
        log("Resource data handling not yet implemented")
    }

    /**
     * Process keepalive packets.
     */
    private fun processKeepalive(packet: Packet) {
        // Receivers respond to keepalive requests
        if (!initiator && packet.data.contentEquals(byteArrayOf(0xFF.toByte()))) {
            val keepaliveResponse = Packet.createRaw(
                destinationHash = linkId,
                data = byteArrayOf(0xFE.toByte()),
                packetType = PacketType.DATA,
                context = PacketContext.KEEPALIVE,
                destinationType = DestinationType.LINK
            )
            Transport.outbound(keepaliveResponse)
            hadOutbound(isKeepalive = true)
        }
        // Keepalives update last_inbound which is already handled in receive()
    }

    /**
     * Process channel packets.
     */
    private fun processChannel(packet: Packet) {
        // TODO: Implement channel handling
        // This requires:
        // - Checking if channel is open
        // - Proving packet receipt
        // - Decrypting and passing to channel
        log("Channel handling not yet implemented")
    }

    /**
     * Process command packets.
     */
    private fun processCommand(packet: Packet) {
        // TODO: Implement command handling
        log("Command handling not yet implemented")
    }

    /**
     * Process command status packets.
     */
    private fun processCommandStatus(packet: Packet) {
        // TODO: Implement command status handling
        log("Command status handling not yet implemented")
    }

    /**
     * Process PROOF type packets.
     */
    private fun processProofPacket(packet: Packet) {
        when (packet.context) {
            PacketContext.RESOURCE_PRF -> processResourceProof(packet)
            else -> {
                log("Unhandled proof context: ${packet.context}")
            }
        }
    }

    /**
     * Process resource proof packets.
     */
    private fun processResourceProof(packet: Packet) {
        try {
            val data = packet.data
            if (data.isEmpty()) {
                log("Invalid proof packet: no data")
                return
            }

            // Proof format: [resource_hash (16 bytes)][proof (32 bytes)]
            if (data.size != RnsConstants.TRUNCATED_HASH_BYTES + RnsConstants.FULL_HASH_BYTES) {
                log("Invalid proof packet size: ${data.size}")
                return
            }

            // Extract resource hash
            val resourceHash = data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

            // Find matching outgoing resource
            val resource = synchronized(outgoingResources) {
                outgoingResources.find { it.hash.contentEquals(resourceHash) }
            }

            if (resource == null) {
                log("Received proof for unknown resource: ${resourceHash.toHexString()}")
                return
            }

            // Validate the proof
            if (resource.validateProof(data)) {
                log("Proof validated for resource ${resourceHash.toHexString()}")
                lastProof = System.currentTimeMillis()
                // Resource validation handles completion logic
            } else {
                log("Proof validation failed for resource ${resourceHash.toHexString()}")
            }

        } catch (e: Exception) {
            log("Error processing resource proof: ${e.message}")
        }
    }

    /**
     * Register an outgoing resource with this link.
     */
    fun registerOutgoingResource(resource: network.reticulum.resource.Resource) {
        synchronized(outgoingResources) {
            if (!outgoingResources.contains(resource)) {
                outgoingResources.add(resource)
                log("Registered outgoing resource ${resource.hash.toHexString()}")
            }
        }
    }

    /**
     * Register an incoming resource with this link.
     */
    fun registerIncomingResource(resource: network.reticulum.resource.Resource) {
        synchronized(incomingResources) {
            if (!incomingResources.contains(resource)) {
                incomingResources.add(resource)
                log("Registered incoming resource ${resource.hash.toHexString()}")
            }
        }
    }

    /**
     * Called when a resource transfer concludes (successfully or with failure).
     * Updates link statistics and removes resource from tracking.
     *
     * @param resource The resource that concluded
     */
    fun resourceConcluded(resource: network.reticulum.resource.Resource) {
        val concludedAt = System.currentTimeMillis()
        val wasIncoming = synchronized(incomingResources) {
            incomingResources.contains(resource)
        }
        val wasOutgoing = synchronized(outgoingResources) {
            outgoingResources.contains(resource)
        }

        // Update statistics based on resource performance
        if (wasIncoming) {
            // For incoming resources, track window and EIFR for bandwidth estimation
            // TODO: Add window and eifr properties to Resource class when implemented
            // lastResourceWindow = resource.window
            // lastResourceEifr = resource.eifr

            // Calculate expected rate from resource transfer
            // TODO: Get resource.startedTransferring property when Resource is fully implemented
            // For now, we skip this calculation
            // val transferTime = (concludedAt - resource.startedTransferring) / 1000.0f
            // if (transferTime > 0.0001f) {
            //     expectedRate = (resource.size * 8) / transferTime
            // }

            synchronized(incomingResources) {
                incomingResources.remove(resource)
            }
        }

        if (wasOutgoing) {
            // Calculate expected rate from resource transfer
            // TODO: Get resource.startedTransferring property when Resource is fully implemented
            // For now, we skip this calculation
            // val transferTime = (concludedAt - resource.startedTransferring) / 1000.0f
            // if (transferTime > 0.0001f) {
            //     expectedRate = (resource.size * 8) / transferTime
            // }

            synchronized(outgoingResources) {
                outgoingResources.remove(resource)
            }
        }

        // Invoke the resource concluded callback if set
        callbacks.resourceConcluded?.let { callback ->
            thread(isDaemon = true) {
                try {
                    callback(resource)
                } catch (e: Exception) {
                    log("Error in resource concluded callback: ${e.message}")
                }
            }
        }

        log("Resource ${resource.hash.toHexString()} concluded")
    }

    /**
     * Handle an incoming request on this link.
     *
     * @param requestId The unique request identifier
     * @param unpackedRequest Array of [timestamp, pathHash, data]
     */
    fun handleRequest(requestId: ByteArray, unpackedRequest: List<Any?>) {
        if (status != LinkConstants.ACTIVE) return

        try {
            // Extract request components
            val requestedAt = (unpackedRequest[0] as? Number)?.toLong() ?: System.currentTimeMillis()
            val pathHash = unpackedRequest[1] as? ByteArray ?: return
            val requestData = unpackedRequest[2] as? ByteArray

            // Get the destination (owner for incoming links, destination for outgoing)
            val targetDestination = owner ?: attachedDestination ?: return

            // Look up the request handler
            val handler = targetDestination.getRequestHandler(pathHash) ?: run {
                log("No handler found for path hash ${pathHash.toHexString()}")
                return
            }

            // Check access control
            val allowed = when (handler.allow) {
                network.reticulum.destination.RequestPolicy.ALLOW_NONE -> false
                network.reticulum.destination.RequestPolicy.ALLOW_ALL -> true
                network.reticulum.destination.RequestPolicy.ALLOW_LIST -> {
                    val remoteId = remoteIdentity
                    remoteId != null && handler.allowedList?.any { it.contentEquals(remoteId.hash) } == true
                }
                else -> false
            }

            if (!allowed) {
                val identityStr = remoteIdentity?.hexHash ?: "<Unknown>"
                log("Request ${requestId.toHexString()} from $identityStr not allowed for path: ${handler.path}")
                return
            }

            log("Handling request ${requestId.toHexString()} for: ${handler.path}")

            // Generate response
            val response = try {
                handler.responseGenerator(
                    handler.path,
                    requestData,
                    requestId,
                    linkId,
                    remoteIdentity,
                    requestedAt
                )
            } catch (e: Exception) {
                log("Error in response generator: ${e.message}")
                null
            }

            // Send response if not null
            if (response != null) {
                sendResponse(requestId, response, handler.autoCompress)
            }

        } catch (e: Exception) {
            log("Error handling request: ${e.message}")
        }
    }

    /**
     * Send a response to a request.
     *
     * @param requestId The request ID this is responding to
     * @param response The response data
     * @param autoCompress Whether to auto-compress (if large enough)
     */
    private fun sendResponse(requestId: ByteArray, response: ByteArray, autoCompress: Boolean) {
        try {
            // Pack response as msgpack: [requestId, response]
            val output = java.io.ByteArrayOutputStream()
            val packer = org.msgpack.core.MessagePack.newDefaultPacker(output)
            packer.packArrayHeader(2)
            packer.packBinaryHeader(requestId.size)
            packer.writePayload(requestId)
            packer.packBinaryHeader(response.size)
            packer.writePayload(response)
            packer.close()

            val packedResponse = output.toByteArray()

            // Send as packet if small enough, otherwise as resource
            if (packedResponse.size <= mdu) {
                val packet = Packet.createRaw(
                    destinationHash = linkId,
                    data = encrypt(packedResponse),
                    packetType = PacketType.DATA,
                    context = PacketContext.RESPONSE,
                    destinationType = DestinationType.LINK
                )
                Transport.outbound(packet)
                hadOutbound(isData = true)
            } else {
                // TODO: Send as resource
                // This requires implementing Resource class with is_response flag
                log("Large response not yet implemented (requires Resource)")
            }
        } catch (e: Exception) {
            log("Error sending response: ${e.message}")
        }
    }

    /**
     * Handle an incoming response to a previous request.
     *
     * @param requestId The request ID this is responding to
     * @param responseData The response data
     * @param responseSize The total size of the response
     * @param transferSize The transfer size (may differ due to compression)
     * @param metadata Optional metadata for file responses
     */
    fun handleResponse(
        requestId: ByteArray,
        responseData: ByteArray?,
        responseSize: Int,
        transferSize: Int,
        metadata: ByteArray? = null
    ) {
        if (status != LinkConstants.ACTIVE) return

        try {
            // Find matching pending request
            val receipt = synchronized(pendingRequests) {
                pendingRequests.find { it.requestId.contentEquals(requestId) }
            }

            if (receipt == null) {
                log("Received response for unknown request: ${requestId.toHexString()}")
                return
            }

            // Update receipt
            receipt.responseSize = responseSize
            if (receipt.responseTransferSize == null) {
                receipt.responseTransferSize = 0
            }
            receipt.responseTransferSize = (receipt.responseTransferSize ?: 0) + transferSize

            // Mark as ready and invoke callback
            receipt.responseReceived(responseData, metadata)

            // Remove from pending list
            synchronized(pendingRequests) {
                pendingRequests.remove(receipt)
            }

        } catch (e: Exception) {
            log("Error handling response: ${e.message}")
        }
    }

    /**
     * Called when a request resource transfer completes.
     *
     * @param resource The completed resource
     */
    fun requestResourceConcluded(resource: network.reticulum.resource.Resource) {
        // TODO: Implement when Resource class has status property
        // This should unpack the request data and call handleRequest
        log("Request resource concluded (not yet fully implemented)")
    }

    /**
     * Called when a response resource transfer completes.
     *
     * @param resource The completed resource
     */
    fun responseResourceConcluded(resource: network.reticulum.resource.Resource) {
        // TODO: Implement when Resource class is complete
        // This should:
        // 1. Check if resource.status == COMPLETE
        // 2. Unpack the response data
        // 3. Call handleResponse with the unpacked data
        log("Response resource concluded (not yet fully implemented)")
    }

    /**
     * Enable or disable physical layer statistics tracking.
     *
     * @param track Whether to track physical layer statistics
     */
    fun trackPhyStats(track: Boolean) {
        this.trackPhyStats = track
    }

    /**
     * Get the RSSI (Received Signal Strength Indication) value.
     *
     * @return RSSI value if tracking is enabled and available, null otherwise
     */
    fun getRssi(): Int? {
        return if (trackPhyStats) phyRssi else null
    }

    /**
     * Get the SNR (Signal-to-Noise Ratio) value.
     *
     * @return SNR value if tracking is enabled and available, null otherwise
     */
    fun getSnr(): Float? {
        return if (trackPhyStats) phySnr else null
    }

    /**
     * Get the Q (link quality) value.
     *
     * @return Q value if tracking is enabled and available, null otherwise
     */
    fun getQ(): Float? {
        return if (trackPhyStats) phyQ else null
    }

    /**
     * Update physical layer statistics from interface.
     * This is typically called by the interface when a packet is received.
     *
     * @param rssi RSSI value
     * @param snr SNR value
     * @param q Link quality value
     */
    fun updatePhyStats(rssi: Int? = null, snr: Float? = null, q: Float? = null) {
        if (trackPhyStats) {
            rssi?.let { phyRssi = it }
            snr?.let { phySnr = it }
            q?.let { phyQ = it }
        }
    }

    /**
     * Get the MTU for this link.
     *
     * @return MTU if link is active, null otherwise
     */
    fun getMtu(): Int? {
        return if (status == LinkConstants.ACTIVE) mtu else null
    }

    /**
     * Get the MDU (Maximum Data Unit) for this link.
     *
     * @return MDU if link is active, null otherwise
     */
    fun getMdu(): Int? {
        return if (status == LinkConstants.ACTIVE) mdu else null
    }

    /**
     * Check if a resource is in the incoming resources list.
     *
     * @param resource The resource to check
     * @return true if the resource is in the list, false otherwise
     */
    fun hasIncomingResource(resource: network.reticulum.resource.Resource): Boolean {
        synchronized(incomingResources) {
            return incomingResources.any { it.hash.contentEquals(resource.hash) }
        }
    }

    /**
     * Check if a resource is in the outgoing resources list.
     *
     * @param resource The resource to check
     * @return true if the resource is in the list, false otherwise
     */
    fun hasOutgoingResource(resource: network.reticulum.resource.Resource): Boolean {
        synchronized(outgoingResources) {
            return outgoingResources.any { it.hash.contentEquals(resource.hash) }
        }
    }

    /**
     * Check if the link is ready for a new outgoing resource.
     *
     * @return true if no outgoing resources are active, false otherwise
     */
    fun readyForNewResource(): Boolean {
        synchronized(outgoingResources) {
            return outgoingResources.isEmpty()
        }
    }

    /**
     * Cancel an outgoing resource transfer.
     *
     * @param resource The resource to cancel
     * @return true if removed, false if not found
     */
    fun cancelOutgoingResource(resource: network.reticulum.resource.Resource): Boolean {
        synchronized(outgoingResources) {
            val removed = outgoingResources.remove(resource)
            if (!removed) {
                log("Attempt to cancel a non-existing outgoing resource")
            }
            return removed
        }
    }

    /**
     * Cancel an incoming resource transfer.
     *
     * @param resource The resource to cancel
     * @return true if removed, false if not found
     */
    fun cancelIncomingResource(resource: network.reticulum.resource.Resource): Boolean {
        synchronized(incomingResources) {
            val removed = incomingResources.remove(resource)
            if (!removed) {
                log("Attempt to cancel a non-existing incoming resource")
            }
            return removed
        }
    }

    /**
     * Get the window size from the last concluded resource.
     *
     * @return Window size if available, null otherwise
     */
    fun getLastResourceWindow(): Int? = lastResourceWindow

    /**
     * Get the EIFR (Effective Information Flow Rate) from the last concluded resource.
     *
     * @return EIFR if available, null otherwise
     */
    fun getLastResourceEifr(): Float? = lastResourceEifr

    /**
     * Set the link established callback.
     *
     * @param callback Function to call when link is established
     */
    fun setLinkEstablishedCallback(callback: ((Link) -> Unit)?) {
        callbacks.linkEstablished = callback
    }

    /**
     * Set the link closed callback.
     *
     * @param callback Function to call when link is closed
     */
    fun setLinkClosedCallback(callback: ((Link) -> Unit)?) {
        callbacks.linkClosed = callback
    }

    /**
     * Set the packet callback.
     *
     * @param callback Function to call when a packet is received
     */
    fun setPacketCallback(callback: ((ByteArray, Packet) -> Unit)?) {
        callbacks.packet = callback
    }

    /**
     * Set the remote identified callback.
     *
     * @param callback Function to call when remote peer identifies
     */
    fun setRemoteIdentifiedCallback(callback: ((Link, Identity) -> Unit)?) {
        callbacks.remoteIdentified = callback
    }

    /**
     * Set the resource started callback.
     *
     * @param callback Function to call when a resource transfer starts
     */
    fun setResourceStartedCallback(callback: ((Any) -> Unit)?) {
        callbacks.resourceStarted = callback
    }

    /**
     * Set the resource concluded callback.
     *
     * @param callback Function to call when a resource transfer concludes
     */
    fun setResourceConcludedCallback(callback: ((Any) -> Unit)?) {
        callbacks.resourceConcluded = callback
    }

    /**
     * Get the establishment rate (data rate during link establishment).
     *
     * @return Establishment rate in bits per second if available, null otherwise
     */
    fun getEstablishmentRate(): Float? {
        return establishmentRate?.let { it * 8 }  // Convert bytes/ms to bits/second
    }

    /**
     * Get the expected data rate for this link.
     *
     * @return Expected rate in bits per second if available, null otherwise
     */
    fun getExpectedRate(): Float? {
        return if (status == LinkConstants.ACTIVE) expectedRate else null
    }

    override fun toString(): String = "Link[${linkId.toHexString().take(12)}]"
}

/**
 * Receipt for a request sent over a link.
 * Tracks the status and response of the request.
 */
class RequestReceipt(
    internal val link: Link,
    internal val requestId: ByteArray,
    val requestSize: Int,
    private val responseCallback: ((RequestReceipt) -> Unit)? = null,
    private val failedCallback: ((RequestReceipt) -> Unit)? = null,
    private val progressCallback: ((RequestReceipt) -> Unit)? = null,
    val timeout: Long
) {
    companion object {
        const val FAILED = 0x00
        const val SENT = 0x01
        const val DELIVERED = 0x02
        const val RECEIVING = 0x03
        const val READY = 0x04
    }

    var status: Int = SENT
        private set

    var progress: Float = 0.0f
        private set

    var response: ByteArray? = null
        private set

    var metadata: ByteArray? = null
        private set

    var responseSize: Int? = null
        internal set

    var responseTransferSize: Int? = null
        internal set

    private val sentAt = System.currentTimeMillis()
    private var concludedAt: Long? = null
    private var responseConcludedAt: Long? = null

    internal var startedAt: Long? = null

    /**
     * Called when the response is received.
     */
    internal fun responseReceived(responseData: ByteArray?, metadata: ByteArray? = null) {
        if (status == FAILED) return

        this.progress = 1.0f
        this.response = responseData
        this.metadata = metadata
        this.status = READY
        this.responseConcludedAt = System.currentTimeMillis()

        // Invoke callbacks
        progressCallback?.let { callback ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    callback(this)
                } catch (e: Exception) {
                    println("[RequestReceipt] Error in progress callback: ${e.message}")
                }
            }
        }

        responseCallback?.let { callback ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    callback(this)
                } catch (e: Exception) {
                    println("[RequestReceipt] Error in response callback: ${e.message}")
                }
            }
        }
    }

    /**
     * Called when the request times out or fails.
     */
    internal fun requestFailed() {
        if (status == READY) return

        this.status = FAILED
        this.concludedAt = System.currentTimeMillis()

        failedCallback?.let { callback ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    callback(this)
                } catch (e: Exception) {
                    println("[RequestReceipt] Error in failed callback: ${e.message}")
                }
            }
        }
    }

    /**
     * Update progress for resource transfers.
     */
    internal fun updateProgress(newProgress: Float) {
        if (status == FAILED) return

        this.progress = newProgress
        if (status != RECEIVING) {
            status = RECEIVING
        }

        progressCallback?.let { callback ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    callback(this)
                } catch (e: Exception) {
                    println("[RequestReceipt] Error in progress callback: ${e.message}")
                }
            }
        }
    }

    /**
     * Get the request ID (copy).
     */
    fun getRequestIdCopy(): ByteArray = requestId.copyOf()

    /**
     * Get the response data if ready (copy).
     */
    fun getResponseCopy(): ByteArray? = response?.copyOf()

    /**
     * Get the response time in milliseconds.
     */
    fun getResponseTime(): Long? {
        val concluded = responseConcludedAt ?: return null
        val started = startedAt ?: sentAt
        return concluded - started
    }

    /**
     * Check if the request has concluded (success or failure).
     */
    fun concluded(): Boolean = status == READY || status == FAILED
}
