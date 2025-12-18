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
            println("[Link] $message")
        }
    }

    // Link identity
    var linkId: ByteArray = ByteArray(0)
        private set
    val hash: ByteArray get() = linkId

    // State
    var status: Int = LinkConstants.PENDING
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

    // Traffic counters
    val tx = AtomicLong(0)
    val rx = AtomicLong(0)
    val txBytes = AtomicLong(0)
    val rxBytes = AtomicLong(0)

    // Physical stats
    var rssi: Int? = null
        private set
    var snr: Float? = null
        private set
    var q: Float? = null
        private set

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
     * Close the link.
     */
    fun teardown(reason: Int = LinkConstants.INITIATOR_CLOSED) {
        if (status == LinkConstants.CLOSED) return

        val previousStatus = status
        status = LinkConstants.CLOSED

        watchdogActive = false

        if (previousStatus == LinkConstants.ACTIVE) {
            // Send close packet
            val closeData = byteArrayOf(reason.toByte())
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

        log("Link ${linkId.toHexString()} closed")
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
     */
    private fun checkTimeout() {
        val now = System.currentTimeMillis()

        when (status) {
            LinkConstants.PENDING, LinkConstants.HANDSHAKE -> {
                if (now - requestTime > establishmentTimeout) {
                    log("Link establishment timeout")
                    teardown(LinkConstants.TIMEOUT)
                }
            }

            LinkConstants.ACTIVE -> {
                val inactivity = noInboundFor()

                if (inactivity > staleTime) {
                    // Send keepalive if we haven't recently
                    if (noOutboundFor() > keepalive / 2) {
                        sendKeepalive()
                    }

                    // Check for stale
                    if (inactivity > staleTime + (rtt ?: 0) * keepaliveTimeoutFactor + LinkConstants.STALE_GRACE) {
                        log("Link timeout (no response)")
                        teardown(LinkConstants.TIMEOUT)
                    } else if (status != LinkConstants.STALE) {
                        status = LinkConstants.STALE
                        log("Link marked stale")
                    }
                }
            }

            LinkConstants.STALE -> {
                val inactivity = noInboundFor()
                if (inactivity > staleTime + (rtt ?: 0) * keepaliveTimeoutFactor + LinkConstants.STALE_GRACE) {
                    log("Stale link timeout")
                    teardown(LinkConstants.TIMEOUT)
                }
            }
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

    override fun toString(): String = "Link[${linkId.toHexString().take(12)}]"
}
