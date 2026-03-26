package network.reticulum.interfaces.nearby

import network.reticulum.interfaces.Interface

/**
 * Per-endpoint child interface for Nearby Connections.
 *
 * Spawned by [NearbyInterface] for each connected endpoint. Much simpler than
 * [network.reticulum.interfaces.ble.BLEPeerInterface] because Nearby Connections
 * handles transport internally — no fragmentation, reassembly, or keepalive needed.
 *
 * Responsibilities:
 * - Send outgoing packets via [NearbyDriver.send] (called by Transport)
 * - Receive incoming packets via [processIncoming] (called by parent's data collection)
 *
 * @param name Human-readable name (format: "Nearby|{endpointId.take(8)}")
 * @param endpointId Nearby Connections endpoint identifier
 * @param parentNearbyInterface Parent [NearbyInterface] that spawned this
 * @param driver Driver for sending data
 */
class NearbyPeerInterface(
    name: String,
    val endpointId: String,
    private val parentNearbyInterface: NearbyInterface,
    private val driver: NearbyDriver,
) : Interface(name) {
    override val bitrate: Int = 2_000_000 // WiFi Direct: ~2 Mbps effective
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    init {
        this.parentInterface = parentNearbyInterface
    }

    /**
     * Send data to this specific endpoint.
     */
    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) return

        try {
            driver.send(endpointId, data)
            txBytes.addAndGet(data.size.toLong())
            parentNearbyInterface.txBytes.addAndGet(data.size.toLong())
        } catch (e: Exception) {
            log("Send failed to $endpointId: ${e.message}")
        }
    }

    /**
     * Deliver incoming data to Transport.
     * Called by [NearbyInterface] when data arrives from this endpoint.
     */
    fun deliverIncoming(data: ByteArray) {
        if (!online.get() || detached.get()) return
        processIncoming(data)
    }

    override fun start() {
        online.set(true)
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)

        try {
            driver.disconnect(endpointId)
        } catch (_: Exception) {
        }

        parentNearbyInterface.peerDisconnected(this)
    }

    private fun log(message: String) {
        println("[NearbyPeerInterface][$name] $message")
    }

    override fun toString(): String = "NearbyPeerInterface[$name]"
}
