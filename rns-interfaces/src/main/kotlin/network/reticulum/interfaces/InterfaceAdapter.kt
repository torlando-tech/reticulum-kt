package network.reticulum.interfaces

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
