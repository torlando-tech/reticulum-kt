package network.reticulum.interfaces

import network.reticulum.common.InterfaceMode
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter to bridge Interface to InterfaceRef for Transport integration.
 * Also sets up the receive callback to deliver packets to Transport.
 */
class InterfaceAdapter private constructor(private val iface: Interface) : InterfaceRef {
    override val name: String = iface.name
    override val hash: ByteArray = iface.getHash()
    override val canSend: Boolean = iface.canSend
    override val canReceive: Boolean = iface.canReceive
    override val online: Boolean get() = iface.online.get()
    override val mode: InterfaceMode get() = iface.mode
    override val announceCap: Double get() = iface.announceCap

    // Tunnel properties - delegate to underlying interface
    override var tunnelId: ByteArray?
        get() = iface.tunnelId
        set(value) { iface.tunnelId = value }

    override var wantsTunnel: Boolean
        get() = iface.wantsTunnel
        set(value) { iface.wantsTunnel = value }

    // Shared instance properties (Python RNS compatibility)
    override val isLocalSharedInstance: Boolean
        get() = iface.isLocalSharedInstance

    override val isConnectedToSharedInstance: Boolean
        get() = (iface as? network.reticulum.interfaces.local.LocalClientInterface)?.isConnectedToSharedInstance() ?: false

    override val parentInterface: InterfaceRef?
        get() = iface.parentInterface?.toRef()

    // IFAC properties - delegate to underlying interface
    override val ifacSize: Int
        get() = iface.ifacSize

    override val ifacKey: ByteArray?
        get() = iface.ifacKey

    override val ifacIdentity: network.reticulum.identity.Identity?
        get() = iface.ifacIdentity

    init {
        // Only set up the callback if one isn't already set
        // This prevents overwriting callbacks set by parent interfaces (e.g., TCPServerInterface)
        if (iface.onPacketReceived == null) {
            iface.onPacketReceived = { data, fromInterface ->
                // Get or create InterfaceRef for the source interface
                val sourceRef = if (fromInterface === iface) {
                    this
                } else {
                    fromInterface.toRef()
                }
                Transport.inbound(data, sourceRef)
            }
        }
    }

    override fun send(data: ByteArray) {
        iface.processOutgoing(data)
    }

    companion object {
        // Cache adapters to avoid creating duplicates
        private val adapterCache = ConcurrentHashMap<Interface, InterfaceAdapter>()

        fun getOrCreate(iface: Interface): InterfaceAdapter {
            return adapterCache.computeIfAbsent(iface) { InterfaceAdapter(iface) }
        }
    }
}

/**
 * Extension function to create an InterfaceRef from an Interface.
 */
fun Interface.toRef(): InterfaceRef = InterfaceAdapter.getOrCreate(this)
