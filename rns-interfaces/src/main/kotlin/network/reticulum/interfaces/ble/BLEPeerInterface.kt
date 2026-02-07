package network.reticulum.interfaces.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.reticulum.interfaces.Interface

/**
 * Per-peer BLE child interface with fragmentation, reassembly, and keepalive.
 *
 * Spawned by [BLEInterface] for each connected peer after the identity handshake
 * completes. Follows the LocalClientInterface pattern: registered with Transport
 * via toRef(), handles data transfer for a single peer.
 *
 * Responsibilities:
 * - Fragment outgoing packets via [BLEFragmenter] for BLE MTU limits
 * - Reassemble incoming fragments via [BLEReassembler] into complete packets
 * - Send keepalive bytes (0x00) every 15 seconds to prevent BLE supervision timeout
 * - Deliver reassembled packets to Transport via [processIncoming]
 * - Handle MAC rotation by swapping the underlying [BLEPeerConnection]
 *
 * @param name Human-readable name (format: "BLE|{identityHex8}")
 * @param connection Active BLE connection to the peer
 * @param parentBleInterface Parent [BLEInterface] that spawned this interface
 * @param peerIdentity 16-byte Reticulum Transport identity hash of the remote peer
 */
class BLEPeerInterface(
    name: String,
    private var connection: BLEPeerConnection,
    private val parentBleInterface: BLEInterface,
    val peerIdentity: ByteArray,
) : Interface(name) {

    override val bitrate: Int = 40_000  // ~40 kbps BLE practical throughput
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    /** BLE MAC address of the connected peer (for UI display). */
    val peerAddress: String get() = connection.address

    /** Negotiated BLE MTU for this connection (for UI display). */
    val peerMtu: Int get() = connection.mtu

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null

    private var fragmenter = BLEFragmenter(connection.mtu)
    private var reassembler = BLEReassembler()

    // Keepalive tracking
    @Volatile
    private var lastKeepaliveReceived = System.currentTimeMillis()

    // Traffic tracking for zombie detection (any traffic, including keepalives)
    @Volatile
    var lastTrafficReceived: Long = System.currentTimeMillis()

    // RSSI at discovery time, used for scoring/eviction decisions
    @Volatile
    var discoveryRssi: Int = -100

    /** true = we are central (outgoing), false = we are peripheral (incoming) */
    @Volatile
    var isOutgoing: Boolean = true

    /** Live RSSI from GATT readRemoteRssi, updated periodically (central only). */
    @Volatile
    var currentRssi: Int = -100

    private var rssiJob: Job? = null

    init {
        this.parentInterface = parentBleInterface
    }

    /**
     * Start the receive and keepalive loops.
     * Called by [BLEInterface] after spawning and registering with Transport.
     */
    fun startReceiving() {
        online.set(true)

        receiveJob = scope.launch { receiveLoop() }
        keepaliveJob = scope.launch { keepaliveLoop() }
        startRssiPolling()
    }

    /**
     * Poll RSSI every 10 seconds on central (outgoing) connections.
     * Peripheral connections don't have a GATT client handle, so RSSI reads are unsupported.
     */
    private fun startRssiPolling() {
        if (!isOutgoing) return
        rssiJob = scope.launch {
            while (online.get() && !detached.get()) {
                delay(10_000)
                if (!online.get() || detached.get()) break
                try {
                    currentRssi = connection.readRemoteRssi()
                } catch (_: Exception) {
                    // Not all connections support RSSI reading — silently ignore
                }
            }
        }
    }

    /**
     * Collect fragments from the peer connection, filter keepalives,
     * reassemble packets, and deliver to Transport via [processIncoming].
     */
    private suspend fun receiveLoop() {
        try {
            connection.receivedFragments.collect { fragment ->
                if (!online.get() || detached.get()) return@collect

                // Any traffic resets the zombie detection timer
                lastTrafficReceived = System.currentTimeMillis()

                // Filter keepalive bytes (single 0x00)
                if (fragment.size == 1 && fragment[0] == BLEConstants.KEEPALIVE_BYTE) {
                    lastKeepaliveReceived = System.currentTimeMillis()
                    return@collect
                }

                // Skip identity handshake data (16 bytes exactly, already consumed by BLEInterface)
                if (fragment.size == BLEConstants.IDENTITY_SIZE) {
                    return@collect
                }

                try {
                    val packet = reassembler.receiveFragment(fragment, connection.address)
                    if (packet != null) {
                        processIncoming(packet) // Delivers to Transport via onPacketReceived
                    }
                } catch (e: Exception) {
                    log("Reassembly error: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation
        } catch (e: Exception) {
            if (!detached.get()) {
                log("Receive loop error: ${e.message}")
                detach()
            }
        }
    }

    /**
     * Fragment outgoing data and send via BLE connection.
     *
     * Transport calls this synchronously. BLE send is async (suspend function),
     * so we bridge with runBlocking(Dispatchers.IO).
     */
    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) return

        try {
            val fragments = fragmenter.fragment(data)

            runBlocking(Dispatchers.IO) {
                for (frag in fragments) {
                    connection.sendFragment(frag)
                }
            }

            txBytes.addAndGet(data.size.toLong())
            parentInterface?.txBytes?.addAndGet(data.size.toLong())

        } catch (e: Exception) {
            log("Send failed: ${e.message}")
            // Don't detach on single send failure -- BLE is lossy
            // Reticulum handles retransmission at a higher level
        }
    }

    /**
     * Send keepalive byte (0x00) every 15 seconds to prevent BLE supervision timeout.
     * On failure, waits one more interval (grace period) then tries again.
     * If the second attempt also fails, tears down the connection.
     */
    private suspend fun keepaliveLoop() {
        try {
            while (online.get() && !detached.get()) {
                delay(BLEConstants.KEEPALIVE_INTERVAL_MS)

                if (!online.get() || detached.get()) break

                try {
                    connection.sendFragment(byteArrayOf(BLEConstants.KEEPALIVE_BYTE))
                } catch (e: Exception) {
                    // Keepalive failure -- grace period
                    log("Keepalive failed, grace period...")
                    delay(BLEConstants.KEEPALIVE_INTERVAL_MS)

                    if (!online.get() || detached.get()) break

                    try {
                        connection.sendFragment(byteArrayOf(BLEConstants.KEEPALIVE_BYTE))
                    } catch (e2: Exception) {
                        log("Keepalive failed after grace period, disconnecting")
                        detach()
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation
        }
    }

    /**
     * Update the underlying BLE connection (for MAC rotation).
     * Called by [BLEInterface] when the same identity connects from a new address.
     * Cancels old receive/keepalive, swaps connection, restarts loops.
     */
    fun updateConnection(newConnection: BLEPeerConnection, newAddress: String) {
        // Cancel old receive/keepalive/rssi jobs
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        rssiJob?.cancel()

        // Close old connection
        try { connection.close() } catch (_: Exception) {}

        // Set new connection and reset fragmentation state
        connection = newConnection
        fragmenter = BLEFragmenter(newConnection.mtu)
        reassembler = BLEReassembler()

        // MAC rotation proves liveness -- reset zombie detection timer
        lastTrafficReceived = System.currentTimeMillis()

        // Restart receive, keepalive, and RSSI polling
        receiveJob = scope.launch { receiveLoop() }
        keepaliveJob = scope.launch { keepaliveLoop() }
        startRssiPolling()

        log("Connection updated to ${newAddress.takeLast(8)}")
    }

    override fun start() {
        // No-op: startReceiving() is called explicitly by BLEInterface after setup
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)

        // Cancel coroutines
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        rssiJob?.cancel()
        scope.cancel()

        // Close BLE connection
        try { connection.close() } catch (_: Exception) {}

        // Notify parent
        parentBleInterface.peerDisconnected(this)
    }

    private fun log(message: String) {
        println("[BLEPeerInterface][$name] $message")
    }

    override fun toString(): String = "BLEPeerInterface[$name]"
}
