package network.reticulum.interfaces.auto

import network.reticulum.interfaces.Interface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents a connection to a single discovered peer via UDP unicast.
 *
 * Each AutoInterfacePeer is spawned by AutoInterface when a new peer is
 * discovered via multicast. Data is transmitted directly to the peer's
 * IPv6 address.
 */
class AutoInterfacePeer(
    name: String,
    /** The parent AutoInterface that spawned this peer. */
    val parent: AutoInterface,
    /** The peer's IPv6 address and data port. */
    private val targetAddress: InetSocketAddress,
    /** Network interface name this peer was discovered on. */
    val interfaceName: String
) : Interface(name) {

    // UDP socket for sending data to this peer
    private var outboundSocket: DatagramSocket? = null

    // Receiving is handled by parent's data socket
    private val running = AtomicBoolean(false)

    override val bitrate: Int
        get() = parent.configuredBitrate ?: AutoInterfaceConstants.BITRATE_GUESS

    override val hwMtu: Int = AutoInterfaceConstants.HW_MTU
    override val supportsLinkMtuDiscovery: Boolean = true

    init {
        parentInterface = parent
    }

    override fun start() {
        if (running.getAndSet(true)) return

        try {
            // Create outbound socket bound to the same interface
            outboundSocket = DatagramSocket().apply {
                reuseAddress = true
            }
            online.set(true)
            log("Started peer connection to $targetAddress")
        } catch (e: Exception) {
            log("Failed to start peer connection: ${e.message}")
            running.set(false)
        }
    }

    override fun detach() {
        if (!running.getAndSet(false)) return

        log("Detaching peer connection to $targetAddress")
        online.set(false)
        detached.set(true)

        try {
            outboundSocket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        outboundSocket = null
    }

    override fun processOutgoing(data: ByteArray) {
        val socket = outboundSocket
        if (socket == null || !online.get() || detached.get()) {
            log("Cannot send: socket=${socket != null}, online=${online.get()}, detached=${detached.get()}")
            return
        }

        try {
            log("Sending ${data.size} bytes to $targetAddress")
            val packet = DatagramPacket(data, data.size, targetAddress)
            socket.send(packet)
            txBytes.addAndGet(data.size.toLong())
            parent.txBytes.addAndGet(data.size.toLong())
            log("Sent successfully")
        } catch (e: Exception) {
            log("Failed to send to $targetAddress: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Called by parent when data is received from this peer's address.
     * This handles the inbound data flow.
     */
    fun handleIncomingData(data: ByteArray) {
        if (!online.get() || detached.get()) return
        processIncoming(data)
    }

    /**
     * Refresh this peer's last-heard timestamp in the parent.
     */
    fun refresh() {
        parent.refreshPeer(targetAddress.address.hostAddress ?: return)
    }

    private fun log(message: String) {
        println("[${System.currentTimeMillis()}] [$name] $message")
    }

    override fun toString(): String = "AutoInterfacePeer[$name -> $targetAddress]"
}
