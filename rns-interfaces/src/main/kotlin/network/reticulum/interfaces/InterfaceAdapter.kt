package network.reticulum.interfaces

import network.reticulum.transport.InterfaceRef

/**
 * Adapter to bridge Interface to InterfaceRef for Transport integration.
 */
class InterfaceAdapter(private val iface: Interface) : InterfaceRef {
    override val name: String = iface.name
    override val hash: ByteArray = iface.getHash()
    override val canSend: Boolean = iface.canSend
    override val canReceive: Boolean = iface.canReceive
    override val online: Boolean get() = iface.online.get()

    override fun send(data: ByteArray) {
        iface.processOutgoing(data)
    }
}

/**
 * Extension function to create an InterfaceRef from an Interface.
 */
fun Interface.toRef(): InterfaceRef = InterfaceAdapter(this)
