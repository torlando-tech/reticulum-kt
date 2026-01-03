package network.reticulum.interfaces

import network.reticulum.common.InterfaceMode
import network.reticulum.common.RnsConstants
import network.reticulum.crypto.Hashes
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

    /** Estimated bitrate in bits per second. */
    open val bitrate: Int = 62500

    /** Hardware MTU (if different from standard MTU). */
    open val hwMtu: Int? = null

    /** Creation timestamp in milliseconds. */
    val createdAt: Long = System.currentTimeMillis()

    /** Total bytes received. */
    val rxBytes = AtomicLong(0)

    /** Total bytes transmitted. */
    val txBytes = AtomicLong(0)

    /** Whether this interface is currently online. */
    val online = AtomicBoolean(false)

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

    /** Callback for received packets. */
    var onPacketReceived: ((data: ByteArray, fromInterface: Interface) -> Unit)? = null

    // Ingress control for announce rate limiting
    private val ingressControl = AtomicBoolean(true)
    private val incomingAnnounceTimestamps = ConcurrentLinkedDeque<Long>()
    private val outgoingAnnounceTimestamps = ConcurrentLinkedDeque<Long>()
    private val burstActive = AtomicBoolean(false)
    private var burstActivatedAt: Long = 0
    private var heldReleaseAt: Long = 0

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
        if (!online.get() || detached.get()) return

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
        online.set(false)
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
     * Get the effective MTU for this interface.
     */
    fun getEffectiveMtu(): Int = hwMtu ?: RnsConstants.MTU

    override fun toString(): String = "Interface[$name]"
}
