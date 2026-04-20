package network.reticulum.interfaces

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.InterfaceMode
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.crypto.Hashes
import network.reticulum.transport.HeldAnnounce
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import network.reticulum.transport.TransportConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Base interface contract for Reticulum network interfaces.
 *
 * Interfaces are responsible for sending and receiving raw packet data.
 * They handle framing, but not packet parsing or routing.
 */
abstract class Interface(
    /** Human-readable name for this interface. */
    val name: String
) {
    // IFAC (Interface Access Code) properties for network isolation

    /** IFAC network name for access control. */
    open val ifacNetname: String? = null

    /** IFAC network passphrase for access control. */
    open val ifacNetkey: String? = null

    /**
     * IFAC size in bytes. 0 = disabled.
     * When ifacNetname or ifacNetkey is set, defaults to 16 bytes.
     */
    open val ifacSize: Int
        get() = if (ifacNetname != null || ifacNetkey != null) 16 else 0

    /** IFAC key derived from network name/key. Null if IFAC disabled. */
    open val ifacKey: ByteArray? = null

    /** IFAC identity for signing packets. Null if IFAC disabled. */
    open val ifacIdentity: network.reticulum.identity.Identity? = null

    /** Whether this interface can receive packets. */
    open val canReceive: Boolean = true

    /** Whether this interface can send packets. */
    open val canSend: Boolean = true

    /** Whether this interface forwards packets (for transport nodes). */
    open val forward: Boolean = false

    /** Whether this interface repeats packets (for radio repeaters). */
    open val repeat: Boolean = false

    /** Interface operational mode. */
    open val mode: InterfaceMode = InterfaceMode.FULL

    /**
     * Runtime override for [mode], settable after interface construction.
     *
     * When non-null, [InterfaceAdapter.mode] surfaces this value to Transport
     * instead of the declared [mode]. Introduced so the conformance bridge
     * can park a peer in any of the six modes without requiring a
     * per-subclass constructor parameter, mirroring Python RNS's post-init
     * `interface.mode = MODE_X` assignment used by `Reticulum._synthesize_interface`
     * (Reticulum.py:773) and by runtime tests.
     *
     * Production code paths should never set this — prefer declaring the
     * intended mode via the subclass's natural configuration surface.
     */
    @Volatile
    var modeOverride: InterfaceMode? = null

    /** Estimated bitrate in bits per second. */
    open val bitrate: Int = 62500

    /** Announce bandwidth cap as fraction of interface bitrate (default 2%). */
    open val announceCap: Double = 0.02

    /** Hardware MTU (if different from standard MTU). */
    open val hwMtu: Int? = null

    /** Whether this interface supports link MTU discovery (Python: AUTOCONFIGURE_MTU or FIXED_MTU). */
    open val supportsLinkMtuDiscovery: Boolean = false

    /** Whether this is a local shared instance server (Python RNS compatibility). */
    open val isLocalSharedInstance: Boolean = false

    // Discovery properties
    /** Whether this interface type supports discovery. */
    open val supportsDiscovery: Boolean = false

    /** Whether discovery is enabled for this instance. */
    open val discoverable: Boolean = false

    /** Last time a discovery announce was sent (epoch seconds). */
    @Volatile var lastDiscoveryAnnounce: Long = 0L

    /** Interval between discovery announces (seconds). */
    open val discoveryAnnounceInterval: Long
        get() = network.reticulum.discovery.DiscoveryConstants.DEFAULT_ANNOUNCE_INTERVAL

    /** Human-readable name for discovery announces. */
    open val discoveryName: String? = null

    /** Whether to encrypt discovery announce payloads. */
    open val discoveryEncrypt: Boolean = false

    /** Required stamp value (null = use default). */
    open val discoveryStampValue: Int? = null

    /** Whether to include IFAC credentials in discovery announces. */
    open val discoveryPublishIfac: Boolean = false

    /** Geographic coordinates for discovery. */
    open val discoveryLatitude: Double? = null
    open val discoveryLongitude: Double? = null
    open val discoveryHeight: Double? = null

    /** Interface type name for discovery announces. */
    open val discoveryInterfaceType: String = "Interface"

    /** Type-specific discovery data. */
    open fun getDiscoveryData(): Map<Int, Any>? = null

    /** Creation timestamp in milliseconds. */
    val createdAt: Long = System.currentTimeMillis()

    /** Total bytes received. */
    val rxBytes = AtomicLong(0)

    /** Total bytes transmitted. */
    val txBytes = AtomicLong(0)

    private val _online = MutableStateFlow(false)

    /**
     * Whether this interface is currently online, exposed as a [StateFlow]
     * so observers can react to transitions (e.g., UI state, handshake
     * completion in [network.reticulum.interfaces.rnode.RNodeInterface]
     * which flips to online several seconds after registration).
     *
     * Scalar reads use `online.value`; to observe, collect the flow.
     *
     * The exposed type is the read-only [StateFlow]; mutation happens
     * through [setOnline] (the backing [MutableStateFlow] stays private).
     */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * Update the online state. Public so subclasses (and parents managing
     * spawned peers) can flip the flag during their lifecycle.
     *
     * Public rather than protected because some subclass hierarchies —
     * [network.reticulum.interfaces.nearby.NearbyInterface] tearing down
     * its spawned [network.reticulum.interfaces.nearby.NearbyPeerInterface]
     * peers, and the conformance bridge's `MockInterface` in a separate
     * module — need cross-instance or cross-module write access that JVM
     * protected semantics won't allow. External Columba-side consumers
     * read via the [online] StateFlow; they have no incentive to call
     * this, and doing so would fight with the owning subclass.
     */
    fun setOnline(value: Boolean) {
        _online.value = value
    }

    /** Whether this interface has been detached (shutdown). */
    val detached = AtomicBoolean(false)

    /** Parent interface (for spawned interfaces). */
    var parentInterface: Interface? = null

    /** Spawned child interfaces. */
    var spawnedInterfaces: MutableList<Interface>? = null

    /** Tunnel ID if this is a tunneled interface. */
    var tunnelId: ByteArray? = null

    /** Whether this interface wants a tunnel synthesized. */
    @Volatile
    var wantsTunnel: Boolean = false

    /** Physical layer signal stats from the most recently received packet. */
    @Volatile var rStatRssi: Int? = null
    @Volatile var rStatSnr: Float? = null
    @Volatile var rStatQ: Float? = null

    /** Callback for received packets. */
    var onPacketReceived: ((data: ByteArray, fromInterface: Interface) -> Unit)? = null

    // Ingress control for announce rate limiting
    protected val ingressControl = AtomicBoolean(true)
    private val incomingAnnounceTimestamps = ConcurrentLinkedDeque<Long>()
    private val outgoingAnnounceTimestamps = ConcurrentLinkedDeque<Long>()
    private val burstActive = AtomicBoolean(false)
    @Volatile private var burstActivatedAt: Long = 0
    @Volatile private var heldReleaseAt: Long = 0

    /** Per-interface held announces for ingress control: dest_hash -> HeldAnnounce. */
    private val heldAnnounces = ConcurrentHashMap<ByteArrayKey, HeldAnnounce>()

    companion object {
        /** How many samples for announce frequency calculation. */
        const val IA_FREQ_SAMPLES = 6
        const val OA_FREQ_SAMPLES = 6

        /** Maximum held announces. */
        const val MAX_HELD_ANNOUNCES = 256

        /** Burst detection thresholds. */
        const val IC_NEW_TIME = 2 * 60 * 60 * 1000L // 2 hours
        const val IC_BURST_FREQ_NEW = 3.5
        const val IC_BURST_FREQ = 12.0
        const val IC_BURST_HOLD = 60 * 1000L // 1 minute
        const val IC_BURST_PENALTY = 5 * 60 * 1000L // 5 minutes
        const val IC_HELD_RELEASE_INTERVAL = 30 * 1000L // 30 seconds

        /** Interface modes that should actively discover paths. */
        val DISCOVER_PATHS_FOR = setOf(
            InterfaceMode.ACCESS_POINT,
            InterfaceMode.GATEWAY,
            InterfaceMode.ROAMING
        )
    }

    /**
     * Get interface age in milliseconds.
     */
    fun age(): Long = System.currentTimeMillis() - createdAt

    /**
     * Get a hash identifier for this interface.
     */
    fun getHash(): ByteArray = Hashes.fullHash(toString().toByteArray())

    /**
     * Send raw packet data through this interface.
     *
     * Implementations should frame the data appropriately.
     *
     * @param data Raw packet bytes to send
     * @throws IllegalStateException if interface is not online or is detached
     */
    abstract fun processOutgoing(data: ByteArray)

    /**
     * Handle incoming data from the physical layer.
     *
     * Implementations should deframe the data and call [processIncoming].
     */
    protected fun processIncoming(data: ByteArray) {
        if (!online.value || detached.get()) return

        rxBytes.addAndGet(data.size.toLong())
        parentInterface?.rxBytes?.addAndGet(data.size.toLong())

        onPacketReceived?.invoke(data, this)
    }

    /**
     * Start the interface.
     */
    abstract fun start()

    /**
     * Stop and detach the interface.
     */
    open fun detach() {
        setOnline(false)
        detached.set(true)
    }

    /**
     * Record that an announce was received.
     */
    fun recordIncomingAnnounce() {
        val now = System.currentTimeMillis()
        incomingAnnounceTimestamps.addLast(now)
        while (incomingAnnounceTimestamps.size > IA_FREQ_SAMPLES) {
            incomingAnnounceTimestamps.pollFirst()
        }
        parentInterface?.recordIncomingAnnounce()
    }

    /**
     * Record that an announce was sent.
     */
    fun recordOutgoingAnnounce() {
        val now = System.currentTimeMillis()
        outgoingAnnounceTimestamps.addLast(now)
        while (outgoingAnnounceTimestamps.size > OA_FREQ_SAMPLES) {
            outgoingAnnounceTimestamps.pollFirst()
        }
        parentInterface?.recordOutgoingAnnounce()
    }

    /**
     * Calculate incoming announce frequency (announces per second).
     */
    fun incomingAnnounceFrequency(): Double {
        val timestamps = incomingAnnounceTimestamps.toList()
        if (timestamps.size <= 1) return 0.0

        var deltaSum = 0L
        for (i in 1 until timestamps.size) {
            deltaSum += timestamps[i] - timestamps[i - 1]
        }
        deltaSum += System.currentTimeMillis() - timestamps.last()

        return if (deltaSum == 0L) 0.0 else 1000.0 / (deltaSum.toDouble() / timestamps.size)
    }

    /**
     * Calculate outgoing announce frequency (announces per second).
     */
    fun outgoingAnnounceFrequency(): Double {
        val timestamps = outgoingAnnounceTimestamps.toList()
        if (timestamps.size <= 1) return 0.0

        var deltaSum = 0L
        for (i in 1 until timestamps.size) {
            deltaSum += timestamps[i] - timestamps[i - 1]
        }
        deltaSum += System.currentTimeMillis() - timestamps.last()

        return if (deltaSum == 0L) 0.0 else 1000.0 / (deltaSum.toDouble() / timestamps.size)
    }

    /**
     * Check if ingress should be limited due to announce burst.
     */
    fun shouldIngressLimit(): Boolean {
        if (!ingressControl.get()) return false

        val freqThreshold = if (age() < IC_NEW_TIME) IC_BURST_FREQ_NEW else IC_BURST_FREQ
        val iaFreq = incomingAnnounceFrequency()

        if (burstActive.get()) {
            if (iaFreq < freqThreshold && System.currentTimeMillis() > burstActivatedAt + IC_BURST_HOLD) {
                burstActive.set(false)
                heldReleaseAt = System.currentTimeMillis() + IC_BURST_PENALTY
            }
            return true
        } else {
            if (iaFreq > freqThreshold) {
                burstActive.set(true)
                burstActivatedAt = System.currentTimeMillis()
                return true
            }
            return false
        }
    }

    /**
     * Hold an announce for later release when ingress burst subsides.
     * Matches Python Interface.hold_announce() (Interface.py:170-174).
     */
    fun holdAnnounce(destinationHash: ByteArray, raw: ByteArray, hops: Int, receivingInterface: InterfaceRef) {
        val key = destinationHash.toKey()
        val held = HeldAnnounce(destinationHash.copyOf(), raw.copyOf(), hops, receivingInterface)
        heldAnnounces.compute(key) { _, existing ->
            if (existing != null) held                              // update existing
            else if (heldAnnounces.size < MAX_HELD_ANNOUNCES) held  // insert new
            else existing                                           // full — drop new (Python behavior)
        }
    }

    /**
     * Process held announces: release one (min-hops first) when burst has subsided.
     * Matches Python Interface.process_held_announces() (Interface.py:176-200).
     */
    fun processHeldAnnounces() {
        try {
            if (shouldIngressLimit() || heldAnnounces.isEmpty()) return

            val now = System.currentTimeMillis()
            if (now <= heldReleaseAt) return

            val freqThreshold = if (age() < IC_NEW_TIME) IC_BURST_FREQ_NEW else IC_BURST_FREQ
            val iaFreq = incomingAnnounceFrequency()
            if (iaFreq >= freqThreshold) return

            // Select announce with minimum hops (Python: PATHFINDER_M as initial max)
            var minHops = TransportConstants.PATHFINDER_M
            var selectedKey: ByteArrayKey? = null
            var selectedAnnounce: HeldAnnounce? = null
            for ((key, announce) in heldAnnounces) {
                if (announce.hops < minHops) {
                    minHops = announce.hops
                    selectedKey = key
                    selectedAnnounce = announce
                }
            }

            if (selectedAnnounce != null && selectedKey != null) {
                heldReleaseAt = now + IC_HELD_RELEASE_INTERVAL
                heldAnnounces.remove(selectedKey)
                // Re-inject via daemon thread to avoid re-entrancy (matches Python)
                val raw = selectedAnnounce.raw
                val iface = selectedAnnounce.receivingInterface
                Thread {
                    Transport.inbound(raw, iface)
                }.apply {
                    isDaemon = true
                    name = "HeldRelease-${this@Interface.name}"
                }.start()
            }
        } catch (e: Exception) {
            System.err.println("An error occurred while processing held announces for $this: ${e.message}")
        }
    }

    /**
     * Number of announces currently held on this interface.
     */
    fun heldAnnounceCount(): Int = heldAnnounces.size

    /**
     * Get the effective MTU for this interface.
     */
    fun getEffectiveMtu(): Int = hwMtu ?: RnsConstants.MTU

    override fun toString(): String = "Interface[$name]"
}
