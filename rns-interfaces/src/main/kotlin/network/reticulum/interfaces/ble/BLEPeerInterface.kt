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

    /**
     * Last time *real data* crossed the link — a data fragment or a data-path probe
     * frame, but NOT a keepalive or handshake. Drives the data-path liveness probe.
     * ([lastTrafficReceived] counts keepalives and so cannot distinguish an
     * idle-but-healthy link from a keepalive-alive-but-data-dead one; this clock can.)
     * Mirrors python ble-reticulum `_last_real_data` (BLEInterface.py).
     */
    @Volatile
    private var lastRealData: Long = System.currentTimeMillis()

    /**
     * Set once a PING/PONG has been seen from this peer (the peer speaks the data-path
     * probe). Only probe-capable peers are reconnected on a dead path, so peers that
     * predate the probe are never falsely reaped. Mirrors python `_probe_capable`.
     */
    @Volatile
    private var probeCapable: Boolean = false

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
    private var probeJob: Job? = null

    init {
        this.parentInterface = parentBleInterface
    }

    /**
     * Start the receive and keepalive loops.
     * Called by [BLEInterface] after spawning and registering with Transport.
     */
    fun startReceiving() {
        setOnline(true)

        receiveJob = scope.launch { receiveLoop() }
        keepaliveJob = scope.launch { keepaliveLoop() }
        probeJob = scope.launch { dataPathProbeLoop() }
        startRssiPolling()
    }

    /**
     * Poll RSSI every 10 seconds on central (outgoing) connections and mirror
     * the value into the base-class [rStatRssi] so Transport.inbound annotates
     * every received packet with it. This is a poll-interval-stale proxy for
     * per-packet RSSI — Android's BluetoothGatt does not expose RSSI per GATT
     * notification. Peripheral connections have no GATT client handle so
     * [android.bluetooth.BluetoothGatt.readRemoteRssi] is unavailable there;
     * we leave [rStatRssi] null on that side (packets stay un-annotated).
     *
     * Visible as `internal` (rather than private) so unit tests can exercise
     * the seed-from-[discoveryRssi] path without waiting on a real 10-second
     * GATT polling loop. The per-tick poll logic lives in [pollAndApplyRssi]
     * and is tested separately.
     */
    internal fun startRssiPolling() {
        if (!isOutgoing) return
        // Seed with the scan-time RSSI so packets received during the first
        // 10-second polling window are still annotated with a meaningful value.
        rStatRssi = discoveryRssi
        rssiJob = scope.launch {
            while (online.value && !detached.get()) {
                delay(10_000)
                if (!online.value || detached.get()) break
                pollAndApplyRssi()
            }
        }
    }

    /**
     * One RSSI poll tick: read from the GATT connection and mirror the result
     * into both [currentRssi] (for peer scoring) and the base-class
     * [rStatRssi] (for per-packet annotation via Transport.inbound). Any
     * exception from [BLEPeerConnection.readRemoteRssi] is silently swallowed
     * so a flaky read doesn't clobber a previously-valid reading.
     *
     * Visible as `internal` so unit tests can call it directly with a fake
     * connection, avoiding the 10-second `delay` inside [startRssiPolling]'s
     * launch block.
     */
    internal suspend fun pollAndApplyRssi() {
        try {
            val rssi = connection.readRemoteRssi()
            currentRssi = rssi
            rStatRssi = rssi
        } catch (_: Exception) {
            // Not all connections support RSSI reading — silently ignore
        }
    }

    /**
     * Collect fragments from the peer connection, filter keepalives,
     * reassemble packets, and deliver to Transport via [processIncoming].
     */
    private suspend fun receiveLoop() {
        try {
            connection.receivedFragments.collect { fragment ->
                if (!online.value || detached.get()) return@collect

                // Any traffic resets the zombie detection timer
                lastTrafficReceived = System.currentTimeMillis()

                // Filter keepalive bytes (single 0x00)
                if (fragment.size == 1 && fragment[0] == BLEConstants.KEEPALIVE_BYTE) {
                    lastKeepaliveReceived = System.currentTimeMillis()
                    return@collect
                }

                // Data-path liveness probe frames (2-byte PING/PONG)
                if (handleProbeFrame(fragment)) return@collect

                // Skip identity handshake data (16 bytes exactly, already consumed by BLEInterface)
                if (fragment.size == BLEConstants.IDENTITY_SIZE) {
                    return@collect
                }

                // Real data crossed the link -- refresh the data-path liveness clock.
                lastRealData = System.currentTimeMillis()

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
        if (!online.value || detached.get()) return

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
            while (online.value && !detached.get()) {
                delay(BLEConstants.KEEPALIVE_INTERVAL_MS)

                if (!online.value || detached.get()) break

                try {
                    connection.sendFragment(byteArrayOf(BLEConstants.KEEPALIVE_BYTE))
                } catch (e: Exception) {
                    // Keepalive failure -- grace period
                    log("Keepalive failed, grace period...")
                    delay(BLEConstants.KEEPALIVE_INTERVAL_MS)

                    if (!online.value || detached.get()) break

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

    // ---- Data-Path Liveness Probe ----

    /**
     * Handle an inbound data-path liveness frame (2-byte PING/PONG). Returns true if
     * [fragment] was a probe frame (and is now consumed). Receiving any probe frame
     * proves the inbound data path is alive and that the peer speaks the probe, so the
     * peer is marked probe-capable; a PING is echoed as a PONG.
     *
     * Mirrors python ble-reticulum `_handle_probe_frame` (BLEInterface.py). The python
     * version also normalizes a "dev:"-prefixed peripheral address to resolve the peer's
     * identity; here the frame already arrives on this peer's own connection, so no
     * address lookup is needed.
     *
     * Visible as `internal` (rather than private) so unit tests can exercise the
     * PING->PONG echo directly without driving the full receive coroutine loop (same
     * pattern as [pollAndApplyRssi]).
     */
    internal suspend fun handleProbeFrame(fragment: ByteArray): Boolean {
        if (fragment.size != 2) return false
        val type = fragment[0]
        if (type != BLEConstants.PROBE_PING_BYTE && type != BLEConstants.PROBE_PONG_BYTE) return false

        // Any probe frame proves the data path is alive and the peer speaks the probe.
        lastRealData = System.currentTimeMillis()
        probeCapable = true

        if (type == BLEConstants.PROBE_PING_BYTE) {
            sendProbe(BLEConstants.PROBE_PONG_BYTE, fragment[1])
        }
        return true
    }

    /** Send a 2-byte data-path probe frame (PING/PONG) over the real data path. */
    private suspend fun sendProbe(type: Byte, nonce: Byte) {
        try {
            connection.sendFragment(byteArrayOf(type, nonce))
        } catch (e: Exception) {
            log("Probe send failed: ${e.message}")
        }
    }

    /**
     * Periodic data-path liveness sweep for this peer.
     *
     * - If the link has had no real data for [BLEConstants.DATA_PATH_PROBE_INTERVAL_MS],
     *   send a PING; a healthy peer echoes a PONG, which refreshes [lastRealData] -- so
     *   the probe is itself the traffic that keeps a genuinely idle-but-healthy link
     *   from ever looking dead, and idle links are never reaped.
     * - If a probe-capable peer's data path has been silent past
     *   [BLEConstants.DATA_PATH_TIMEOUT_MS], the link is "connected but data-dead":
     *   force a real reconnect via the driver.
     *
     * Mirrors python ble-reticulum `_run_data_path_probes` (BLEInterface.py). Python runs
     * one timer in the parent interface iterating all peers; this kotlin port runs the
     * loop per-peer (alongside the existing keepalive/RSSI jobs) to fit the per-peer
     * structure. Reconnect goes through the parent's `driver.disconnect` (python parity),
     * not the per-peer `detach()`/`close()` -- the parent owns the driver, and on Android
     * `disconnect` cancels both a central GATT connection and a peripheral-side central.
     */
    private suspend fun dataPathProbeLoop() {
        try {
            while (online.value && !detached.get()) {
                delay(BLEConstants.DATA_PATH_PROBE_POLL_INTERVAL_MS)
                if (!online.value || detached.get()) break

                val idle = System.currentTimeMillis() - lastRealData
                if (idle > BLEConstants.DATA_PATH_PROBE_INTERVAL_MS) {
                    // Low byte of the clock is a fine nonce; Long.toByte() truncates to it.
                    sendProbe(BLEConstants.PROBE_PING_BYTE, System.currentTimeMillis().toByte())
                }
                if (probeCapable && idle > BLEConstants.DATA_PATH_TIMEOUT_MS) {
                    log("data-path dead (no real data ${idle}ms) -- reconnecting")
                    probeCapable = false
                    parentBleInterface.onDataPathDead(connection.address)
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
        // Cancel old receive/keepalive/rssi/probe jobs
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        rssiJob?.cancel()
        probeJob?.cancel()

        // Close old connection
        try { connection.close() } catch (_: Exception) {}

        // Set new connection and reset fragmentation state
        connection = newConnection
        fragmenter = BLEFragmenter(newConnection.mtu)
        reassembler = BLEReassembler()

        // MAC rotation proves liveness -- reset zombie + data-path timers, re-negotiate probe
        lastTrafficReceived = System.currentTimeMillis()
        lastRealData = System.currentTimeMillis()
        probeCapable = false

        // Restart receive, keepalive, probe, and RSSI polling
        receiveJob = scope.launch { receiveLoop() }
        keepaliveJob = scope.launch { keepaliveLoop() }
        probeJob = scope.launch { dataPathProbeLoop() }
        startRssiPolling()

        log("Connection updated to ${newAddress.takeLast(8)}")
    }

    override fun start() {
        // No-op: startReceiving() is called explicitly by BLEInterface after setup
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        setOnline(false)

        // Cancel coroutines
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        rssiJob?.cancel()
        probeJob?.cancel()
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
