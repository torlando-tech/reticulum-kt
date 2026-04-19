/**
 * Behavioral conformance Transport commands for reticulum-kt.
 *
 * Mirrors the Python reference's `behavioral_*` commands in
 * reference/behavioral_transport.py. Every assertion a test makes is on
 * bytes emitted by a MockInterface — no internal state introspection.
 *
 * Handle-indexed state: each handle corresponds to one running Reticulum
 * instance (reticulum-kt's singleton; we stop-and-restart per test for
 * isolation, which the Kotlin side supports via Reticulum.stop()).
 */

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.InterfaceMode
import network.reticulum.identity.Identity
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Zero-wire Interface subclass.
 *
 * processOutgoing bytes are buffered into [txQueue] (drainable by tests).
 * inject() hands bytes to Transport.inbound via the standard processIncoming
 * → onPacketReceived callback chain that InterfaceAdapter sets up for all
 * registered interfaces.
 */
class MockInterface(
    name: String,
    override val mode: InterfaceMode,
    override val hwMtu: Int?,
) : Interface(name) {
    override val canSend: Boolean = true
    override val canReceive: Boolean = true
    override val bitrate: Int = 10_000_000
    override val supportsDiscovery: Boolean = false
    override val isLocalSharedInstance: Boolean = false

    private val txQueue = ConcurrentLinkedDeque<ByteArray>()

    override fun start() {
        setOnline(true)
    }

    override fun processOutgoing(data: ByteArray) {
        txQueue.addLast(data.copyOf())
        txBytes.addAndGet(data.size.toLong())
    }

    /** Inject raw bytes as if received from the wire.
     *
     * Mirrors the `ExternalTestInterface.injectPacket` pattern used by
     * reticulum-kt's AnnounceForwardingIntegrationTest: invoke the receive
     * callback directly rather than going through processIncoming (which
     * gates on online/detached state that can race with init).
     */
    fun inject(raw: ByteArray) {
        onPacketReceived?.invoke(raw, this)
    }

    fun drainTx(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        while (true) {
            val item = txQueue.pollFirst() ?: break
            out.add(item)
        }
        return out
    }
}

// --- Handle-indexed state ---
//
// Plain class (not data class): `data class` with a ByteArray field uses
// reference equality for the generated equals/hashCode, which is misleading
// and this class isn't used structurally.
private class BehavioralInstance(
    val rns: Reticulum,
    val identityHash: ByteArray,
    val configDir: File,
    val interfaces: MutableMap<String, MockInterface> = mutableMapOf(),
)

private val behavioralInstances = mutableMapOf<String, BehavioralInstance>()

private fun parseMode(name: String?): InterfaceMode = when (name?.uppercase()) {
    null, "FULL" -> InterfaceMode.FULL
    "POINT_TO_POINT" -> InterfaceMode.POINT_TO_POINT
    "ACCESS_POINT" -> InterfaceMode.ACCESS_POINT
    "ROAMING" -> InterfaceMode.ROAMING
    "BOUNDARY" -> InterfaceMode.BOUNDARY
    "GATEWAY" -> InterfaceMode.GATEWAY
    else -> throw IllegalArgumentException("Unknown interface mode: $name")
}

// --- Command handlers ---

fun handleBehavioralCommand(command: String, p: JsonObject): JsonObject = when (command) {
    "behavioral_start" -> {
        val seedHex = p.get("identity_seed")?.asString
        val enableTransport = p.get("enable_transport")?.asBoolean ?: true

        // If a previous behavioral test ran and left Reticulum alive, stop it
        // to ensure each handle starts with a fresh Transport singleton.
        try {
            Reticulum.stop()
        } catch (_: Throwable) {
            // No running instance — fine.
        }

        val transportIdentity: Identity? = seedHex?.let { hex ->
            val seed = hex.fromHex()
            require(seed.size == 64) { "identity_seed must be 64 bytes" }
            Identity.fromPrivateKey(seed)
        }

        val configDir = java.nio.file.Files.createTempDirectory("rns_behav_").toFile()
        val rns = Reticulum.start(
            configDir = configDir.absolutePath,
            enableTransport = enableTransport,
            shareInstance = false,
            connectToSharedInstance = false,
            transportIdentity = transportIdentity,
        )

        val identityHash = Transport.identity?.hash
            ?: throw IllegalStateException("Transport started without an identity")

        val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        behavioralInstances[handle] = BehavioralInstance(rns, identityHash, configDir)

        result(
            "handle" to JsonPrimitive(handle),
            "identity_hash" to hexVal(identityHash),
        )
    }

    "behavioral_stop" -> {
        val handle = p.str("handle")
        val inst = behavioralInstances.remove(handle)
        if (inst != null) {
            for (iface in inst.interfaces.values) {
                iface.detach()
            }
            try {
                Reticulum.stop()
            } catch (_: Throwable) {
                // Best-effort.
            }
            // Clean up the config dir so a full pytest session doesn't
            // accumulate one /tmp/rns_behav_* per test.
            try {
                inst.configDir.deleteRecursively()
            } catch (_: Throwable) {
                // Best-effort.
            }
            result("stopped" to JsonPrimitive(true))
        } else {
            result("stopped" to JsonPrimitive(false))
        }
    }

    "behavioral_attach_mock_interface" -> {
        val handle = p.str("handle")
        val inst = behavioralInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val name = p.str("name")
        val mode = parseMode(p.strOpt("mode"))
        val mtu = p.intOpt("mtu")

        val iface = MockInterface(name, mode, mtu)
        iface.start()
        val ifaceRef = iface.toRef()
        Transport.registerInterface(ifaceRef)

        val ifaceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        inst.interfaces[ifaceId] = iface

        result(
            "iface_id" to JsonPrimitive(ifaceId),
            "interface_hash" to hexVal(iface.getHash()),
        )
    }

    "behavioral_inject" -> {
        val handle = p.str("handle")
        val ifaceId = p.str("iface_id")
        val raw = p.hex("raw")

        val inst = behavioralInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val iface = inst.interfaces[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")

        iface.inject(raw)
        result()
    }

    "behavioral_drain_tx" -> {
        val handle = p.str("handle")
        val ifaceId = p.str("iface_id")

        val inst = behavioralInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val iface = inst.interfaces[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")

        val packets = iface.drainTx()
        val arr = JsonArray()
        for (pkt in packets) {
            arr.add(pkt.toHex())
        }
        result("packets" to arr)
    }

    else -> throw IllegalArgumentException("Unknown behavioral command: $command")
}
