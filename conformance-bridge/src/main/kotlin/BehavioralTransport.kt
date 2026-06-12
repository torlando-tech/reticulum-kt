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
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.InterfaceMode
import network.reticulum.destination.Destination
import network.reticulum.destination.RequestPolicy
import network.reticulum.identity.Identity
import network.reticulum.interfaces.IfacUtils
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.packet.Packet
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.LinkEntry
import network.reticulum.transport.RichAnnounceHandler
import network.reticulum.transport.ReverseEntry
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
    override val ifacIdentity: Identity? = null,
    override val ifacKey: ByteArray? = null,
    override val ifacSize: Int = 0,
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

    companion object {
        /** TCP-class default IFAC tag length in bytes (python TCPInterface.py:77). */
        const val DEFAULT_IFAC_SIZE_BYTES = 16
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
    /** iface_id -> the registered InterfaceRef (for table-entry iface mapping). */
    val ifaceRefs: MutableMap<String, InterfaceRef> = mutableMapOf(),
    /** Registered destinations + their recording delivery callbacks. */
    val destinations: MutableMap<String, Destination> = mutableMapOf(),
    val destDeliveries: MutableMap<String, MutableList<ByteArray>> = mutableMapOf(),
    /** Recording announce handlers by id. */
    val announceHandlers: MutableMap<String, RecordingAnnounceHandler> = mutableMapOf(),
)

private val behavioralInstances = mutableMapOf<String, BehavioralInstance>()

/** Map an InterfaceRef object back to the iface_id it was attached with. */
private fun BehavioralInstance.ifaceIdOf(ref: InterfaceRef?): String? {
    if (ref == null) return null
    return ifaceRefs.entries.firstOrNull { it.value.hash.contentEquals(ref.hash) }?.key
}

/**
 * Recording announce handler — mirrors the reference's duck-typed
 * `_RecordingAnnounceHandler`: records every (destination_hash, identity hash,
 * app_data[, announce_packet_hash]) the real Transport dispatch hands it. A
 * 4-param handler records the announce_packet_hash; receivePathResponses and
 * raiseOnCall reproduce the python attributes that gate dispatch.
 */
class RecordingAnnounceHandler(
    private val numParams: Int,
    override val receivePathResponses: Boolean,
    private val raiseOnCall: Boolean,
) : RichAnnounceHandler {
    val calls = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()

    override fun handleAnnounceWithContext(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?,
        hops: Int,
        receivingInterfaceName: String?,
        matchedAspect: String?,
        announcePacketHash: ByteArray?,
    ): Boolean {
        val rec = JsonObject().apply {
            addProperty("destination_hash", destinationHash.toHex())
            addProperty("announced_identity_hash", announcedIdentity.hash.toHex())
            if (appData != null) addProperty("app_data", appData.toHex())
            else add("app_data", JsonNull.INSTANCE)
            // Only a 4-param handler is given announce_packet_hash (python's
            // 3-vs-4 dispatch arm, Transport.py:2055-2069).
            if (numParams == 4 && announcePacketHash != null) {
                addProperty("announce_packet_hash", announcePacketHash.toHex())
            }
        }
        calls.add(rec)
        if (raiseOnCall) throw RuntimeException("recording announce handler deliberately raised")
        return false
    }
}

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

        // The shared-instance-client posture flips Transport's packet-filter /
        // add-packet-hash short-circuits (python Transport.py:1337/:1376).
        if (p.get("connected_to_shared_instance")?.asBoolean == true) {
            Transport.isConnectedToSharedInstance = true
        }

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
            for (handler in inst.announceHandlers.values) {
                try { Transport.deregisterAnnounceHandler(handler) } catch (_: Throwable) {}
            }
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

        // Optional IFAC: derive ifac_identity/ifac_key from netname/netkey
        // exactly as RNS._add_interface does (via the real IfacUtils), so the
        // ifac_mask command can drive genuine masking through this interface.
        var ifacIdentity: Identity? = null
        var ifacKey: ByteArray? = null
        var ifacSize = 0
        val netname = p.strOpt("ifac_netname")
        val netkey = p.strOpt("ifac_netkey")
        if (netname != null || netkey != null) {
            val creds = IfacUtils.deriveIfacCredentials(netname, netkey)
            if (creds != null) {
                ifacIdentity = creds.identity
                ifacKey = creds.key
                ifacSize = p.intOpt("ifac_size") ?: (MockInterface.DEFAULT_IFAC_SIZE_BYTES)
            }
        }

        val iface = MockInterface(name, mode, mtu, ifacIdentity, ifacKey, ifacSize)
        iface.start()
        val ifaceRef = iface.toRef()
        Transport.registerInterface(ifaceRef)

        val ifaceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        inst.interfaces[ifaceId] = iface
        inst.ifaceRefs[ifaceId] = ifaceRef

        result(
            "iface_id" to JsonPrimitive(ifaceId),
            "interface_hash" to hexVal(iface.getHash()),
            "ifac_size" to intVal(ifacSize),
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

    "behavioral_detach_interface" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val ref = inst.ifaceRefs[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        Transport.deregisterInterface(ref)
        inst.interfaces[ifaceId]?.detach()
        result("detached" to boolVal(true))
    }

    // ===== Table reads =====

    "behavioral_read_path_table" -> {
        val inst = inst(p)
        val dest = p.hex("dest")
        val entry = Transport.pathTable[dest.toByteArrayKey()]
            ?: return result("found" to boolVal(false))
        result(
            "found" to boolVal(true),
            "hops" to intVal(entry.hops),
            "next_hop" to hexVal(entry.nextHop),
            // python reports epoch SECONDS (float); kotlin stores millis.
            "timestamp" to doubleVal(entry.timestamp / 1000.0),
            "expires" to doubleVal(entry.expires / 1000.0),
            "random_blobs" to JsonArray().apply { entry.randomBlobs.forEach { add(it.toHex()) } },
            "receiving_interface_hash" to hexVal(entry.receivingInterfaceHash),
            "packet_hash" to hexVal(entry.announcePacketHash),
        )
    }

    "behavioral_read_reverse_table" -> {
        val inst = inst(p)
        fun decompose(key: ByteArray, e: ReverseEntry): JsonObject = JsonObject().apply {
            addProperty("key", key.toHex())
            addProperty("received_if", inst.ifaceIdOf(Transport.findInterfaceByHashForTest(e.receivingInterfaceHash)))
            addProperty("outbound_if", inst.ifaceIdOf(Transport.findInterfaceByHashForTest(e.outboundInterfaceHash)))
            addProperty("received_if_hash", e.receivingInterfaceHash.toHex())
            addProperty("outbound_if_hash", e.outboundInterfaceHash.toHex())
            addProperty("timestamp", e.timestamp / 1000.0)
        }
        val dest = p.hexOpt("dest")
        if (dest != null) {
            val e = Transport.reverseTable[dest.toByteArrayKey()]
                ?: return result("found" to boolVal(false))
            decompose(dest, e).apply { addProperty("found", true) }
        } else {
            result("entries" to JsonArray().apply {
                Transport.reverseTable.forEach { (k, v) -> add(decompose(k.bytes, v)) }
            })
        }
    }

    "behavioral_read_announce_table" -> {
        val inst = inst(p)
        val dest = p.hex("dest")
        val e = Transport.announceTable[dest.toByteArrayKey()]
            ?: return result("found" to boolVal(false))
        result(
            "found" to boolVal(true),
            "retries" to intVal(e.retransmits),
            "hops" to intVal(e.hops),
            "timestamp" to doubleVal(e.timestamp / 1000.0),
            "retransmit_timeout" to doubleVal(e.retransmitTimeout / 1000.0),
            "local_rebroadcasts" to intVal(e.localRebroadcasts),
            // kotlin AnnounceEntry has no block_rebroadcasts flag; report false.
            "block_rebroadcasts" to boolVal(false),
            "received_from" to hexVal(e.destinationHash),
            "attached_interface" to (inst.ifaceIdOf(
                Transport.findInterfaceByHashForTest(e.receivingInterfaceHash))
                ?.let { strVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "behavioral_read_link_table" -> {
        val inst = inst(p)
        fun decompose(key: ByteArray, e: LinkEntry): JsonObject = JsonObject().apply {
            addProperty("link_id", key.toHex())
            addProperty("timestamp", e.timestamp / 1000.0)
            addProperty("next_hop_transport_id", e.nextHop.toHex())
            addProperty("next_hop_if", inst.ifaceIdOf(Transport.findInterfaceByHashForTest(e.nextHopInterfaceHash)))
            addProperty("remaining_hops", e.remainingHops)
            addProperty("received_if", inst.ifaceIdOf(Transport.findInterfaceByHashForTest(e.receivingInterfaceHash)))
            addProperty("hops", e.takenHops)
            addProperty("destination_hash", e.destinationHash.toHex())
            addProperty("validated", e.validated)
            addProperty("proof_timeout", e.proofTimeout / 1000.0)
        }
        val linkId = p.hexOpt("link_id")
        if (linkId != null) {
            val e = Transport.linkTable[linkId.toByteArrayKey()]
                ?: return result("found" to boolVal(false))
            decompose(linkId, e).apply { addProperty("found", true) }
        } else {
            result("entries" to JsonArray().apply {
                Transport.linkTable.forEach { (k, v) -> add(decompose(k.bytes, v)) }
            })
        }
    }

    "behavioral_read_tunnels" -> {
        val inst = inst(p)
        result("tunnels" to JsonArray().apply {
            Transport.tunnelInfosForTest().forEach { t ->
                add(JsonObject().apply {
                    addProperty("tunnel_id", t.tunnelId.toHex())
                    addProperty("interface_hash", t.interface_?.hash?.toHex())
                    addProperty("interface_id", inst.ifaceIdOf(t.interface_))
                    addProperty("expires", t.expires / 1000.0)
                    addProperty("num_paths", t.paths.size)
                })
            }
        })
    }

    "behavioral_read_announce_rate" -> {
        inst(p)
        val dest = p.hex("dest")
        val ts = Transport.announceRateTimestampsForTest(dest)
            ?: return result("found" to boolVal(false))
        // kotlin tracks only the timestamp history; last is its max,
        // rate_violations/blocked_until are 0 (see port-deviations.md).
        result(
            "found" to boolVal(true),
            "last" to doubleVal((ts.maxOrNull() ?: 0L) / 1000.0),
            "rate_violations" to intVal(0),
            "blocked_until" to doubleVal(0.0),
            "timestamps" to JsonArray().apply { ts.forEach { add(it / 1000.0) } },
        )
    }

    "behavioral_read_destination_deliveries" -> {
        val inst = inst(p)
        val dest = p.hex("dest").toHex()
        val deliveries = inst.destDeliveries[dest] ?: mutableListOf()
        result(
            "count" to intVal(deliveries.size),
            "deliveries" to JsonArray().apply { deliveries.forEach { add(it.toHex()) } },
        )
    }

    "behavioral_read_announce_handler_calls" -> {
        val inst = inst(p)
        val handler = inst.announceHandlers[p.str("handler_id")]
            ?: throw IllegalArgumentException("Unknown announce handler_id")
        result(
            "calls" to JsonArray().apply { handler.calls.forEach { add(it) } },
            "registered" to boolVal(true),
        )
    }

    // ===== Seeds + deterministic time =====

    "behavioral_seed_link_table" -> {
        val inst = inst(p)
        val dest = p.hex("dest")
        val nhRef = inst.ifaceRefs[p.str("nh_iface_id")]
            ?: throw IllegalArgumentException("Unknown nh_iface_id")
        val rcvdRef = inst.ifaceRefs[p.str("rcvd_iface_id")]
            ?: throw IllegalArgumentException("Unknown rcvd_iface_id")
        val now = System.currentTimeMillis()
        val ageMs = (p.intOpt("timestamp_age_s") ?: 0) * 1000L
        val proofTimeoutInS = p.intOpt("proof_timeout_in_s") ?: 60
        Transport.linkTable[dest.toByteArrayKey()] = LinkEntry(
            timestamp = now - ageMs,
            nextHop = inst.identityHash,
            nextHopInterfaceHash = nhRef.hash,
            remainingHops = p.intOpt("rem_hops") ?: 99,
            receivingInterfaceHash = rcvdRef.hash,
            takenHops = p.intOpt("hops") ?: 99,
            destinationHash = dest,
            validated = p.boolOpt("validated") ?: true,
            proofTimeout = now + proofTimeoutInS * 1000L,
        )
        result("seeded" to boolVal(true))
    }

    "behavioral_seed_reverse_table" -> {
        val inst = inst(p)
        val key = p.hex("key")
        val rcvdRef = inst.ifaceRefs[p.str("rcvd_iface_id")]
            ?: throw IllegalArgumentException("Unknown rcvd_iface_id")
        val outbRef = inst.ifaceRefs[p.str("outb_iface_id")]
            ?: throw IllegalArgumentException("Unknown outb_iface_id")
        val ageMs = (p.intOpt("timestamp_age_s") ?: 0) * 1000L
        Transport.reverseTable[key.toByteArrayKey()] = ReverseEntry(
            receivingInterfaceHash = rcvdRef.hash,
            outboundInterfaceHash = outbRef.hash,
            timestamp = System.currentTimeMillis() - ageMs,
        )
        result("seeded" to boolVal(true))
    }

    "behavioral_set_path_timestamp" -> {
        inst(p)
        val set = Transport.setPathTimestampForTest(
            p.hex("dest"), (p.get("timestamp").asDouble * 1000).toLong())
        result("set" to boolVal(set))
    }

    "behavioral_set_path_expires" -> {
        inst(p)
        val set = Transport.setPathExpiresForTest(
            p.hex("dest"), (p.get("expires").asDouble * 1000).toLong())
        result("set" to boolVal(set))
    }

    "behavioral_set_announce_timestamp" -> {
        inst(p)
        val e = Transport.announceTable[p.hex("dest").toByteArrayKey()]
            ?: return result("set" to boolVal(false))
        p.get("retransmit_timeout")?.takeIf { !it.isJsonNull }?.let {
            e.retransmitTimeout = (it.asDouble * 1000).toLong()
        }
        result("set" to boolVal(true))
    }

    "behavioral_force_cull" -> {
        inst(p)
        Transport.forceCullForTest()
        result("culled" to boolVal(true))
    }

    "behavioral_mark_path_unresponsive" -> {
        inst(p)
        val dest = p.hex("dest")
        val had = Transport.pathTable.containsKey(dest.toByteArrayKey())
        Transport.markPathUnresponsive(dest)
        result("marked" to boolVal(had))
    }

    // ===== Filter / inbound observation =====

    "behavioral_packet_filter" -> {
        val inst = inst(p)
        val raw = p.hex("raw")
        val remember = p.boolOpt("remember") ?: true
        val packet = Packet.unpack(raw)
            ?: throw IllegalArgumentException("packet failed to unpack")
        // python's Transport.packet_filter(packet) needs no interface; kotlin's
        // gate reads iface.hash only to check local-client membership. A
        // throwaway, unregistered ref is therefore a non-local-client interface
        // — exactly the reference posture — when no mock interface is attached.
        val anyIface = inst.ifaceRefs.values.firstOrNull()
            ?: MockInterface("pf-throwaway", network.reticulum.common.InterfaceMode.FULL, null).toRef()
        val accepted = Transport.packetFilterForTest(packet, anyIface)
        var remembered = false
        if (accepted && remember) {
            Transport.addPacketHashForTest(packet.getHash())
            remembered = true
        }
        result(
            "accepted" to boolVal(accepted),
            "packet_hash" to hexVal(packet.getHash()),
            "remembered" to boolVal(remembered),
        )
    }

    "behavioral_inbound_remembered" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val iface = inst.interfaces[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        val raw = p.hex("raw")
        val probe = Packet.unpack(raw)
        val packetHash = probe?.getHash()
        val before = Transport.packetHashlistSizeForTest()
        iface.inject(raw)
        val after = Transport.packetHashlistSizeForTest()
        result(
            "hashlist_before" to intVal(before),
            "hashlist_after" to intVal(after),
            "hashlist_grew" to boolVal(after > before),
            "unpackable" to boolVal(probe != null),
            "packet_hash" to (packetHash?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "in_hashlist" to boolVal(
                packetHash != null && Transport.packetHashlistContainsForTest(packetHash)),
        )
    }

    "behavioral_ifac_mask" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val iface = inst.interfaces[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        val ref = inst.ifaceRefs[ifaceId]!!
        if (ref.ifacIdentity == null) throw IllegalArgumentException("interface has no IFAC identity configured")
        iface.drainTx()
        Transport.transmitForTest(ref, p.hex("raw"))
        val emitted = iface.drainTx()
        require(emitted.size == 1) { "expected exactly one masked frame, got ${emitted.size}" }
        result("masked" to hexVal(emitted[0]))
    }

    "behavioral_synthesize_tunnel" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val ref = inst.ifaceRefs[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        val tunnelIdData = Transport.identity!!.getPublicKey() + ref.hash
        val tunnelId = network.reticulum.crypto.Hashes.fullHash(tunnelIdData)
        Transport.synthesizeTunnel(ref)
        result("iface_id" to strVal(ifaceId), "tunnel_id" to hexVal(tunnelId))
    }

    "behavioral_request_path" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val ref = inst.ifaceRefs[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        val dest = p.hex("dest")
        // The reference returns the request tag it used; kotlin's requestPath
        // generates its own tag, so report a fresh random one for parity of
        // shape (the emitted packet is what the test drains+inspects).
        Transport.requestPath(dest, ref)
        result("tag" to hexVal(network.reticulum.crypto.Hashes.getRandomHash()))
    }

    "behavioral_hold_and_release_announce" -> {
        val inst = inst(p)
        val ifaceId = p.str("iface_id")
        val iface = inst.interfaces[ifaceId]
            ?: throw IllegalArgumentException("Unknown iface_id: $ifaceId")
        val ref = inst.ifaceRefs[ifaceId]!!
        val hopsMap = JsonObject()
        for (el in p.get("announces").asJsonArray) {
            val raw = el.asString.fromHex()
            val packet = Packet.unpack(raw)
                ?: throw IllegalArgumentException("could not unpack supplied announce packet")
            iface.holdAnnounce(packet.destinationHash, raw, packet.hops, ref)
            hopsMap.addProperty(packet.destinationHash.toHex(), packet.hops)
        }
        val heldBefore = iface.heldAnnounceDestinations().map { it.toHex() }
        iface.openHeldReleaseGateForTest()
        iface.processHeldAnnounces()
        val heldAfter = iface.heldAnnounceDestinations().map { it.toHex() }
        result(
            "held_before" to JsonArray().apply { heldBefore.forEach { add(it) } },
            "held_after" to JsonArray().apply { heldAfter.forEach { add(it) } },
            "released" to JsonArray().apply { heldBefore.filterNot { it in heldAfter }.forEach { add(it) } },
            "hops" to hopsMap,
        )
    }

    // ===== Destinations + announce handlers =====

    "behavioral_register_destination" -> {
        val inst = inst(p)
        val appName = p.str("app_name")
        val aspects = p.stringArray("aspects")
        val typeName = (p.strOpt("type") ?: "single").lowercase()
        val type = when (typeName) {
            "single" -> network.reticulum.common.DestinationType.SINGLE
            "plain" -> network.reticulum.common.DestinationType.PLAIN
            "group" -> network.reticulum.common.DestinationType.GROUP
            else -> throw IllegalArgumentException("unknown destination type: $typeName")
        }
        val identity = if (typeName == "plain") null else {
            val seed = p.hex("identity_seed")
            require(seed.size == 64) { "identity_seed must be 64 bytes" }
            Identity.fromPrivateKey(seed)
        }
        val dest = Destination.create(
            identity, network.reticulum.common.DestinationDirection.IN, type,
            appName, *aspects.toTypedArray())
        val deliveries = mutableListOf<ByteArray>()
        dest.packetCallback = { data, _ -> deliveries.add(data.copyOf()) }
        p.strOpt("proof_strategy")?.let {
            dest.setProofStrategy(when (it.lowercase()) {
                "all" -> Destination.PROVE_ALL
                "none" -> Destination.PROVE_NONE
                "app" -> Destination.PROVE_APP
                else -> throw IllegalArgumentException("unknown proof strategy: $it")
            })
        }
        inst.destinations[dest.hexHash] = dest
        inst.destDeliveries[dest.hexHash] = deliveries
        result("destination_hash" to hexVal(dest.hash))
    }

    "behavioral_register_announce_handler" -> {
        val inst = inst(p)
        val omit = p.boolOpt("omit_aspect_filter") ?: false
        if (omit) {
            // python's register guard rejects a handler lacking an aspect_filter
            // attribute; kotlin handlers are typed, so the unrepresentable case
            // is reported as not-registered and never fires.
            val handlerId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            inst.announceHandlers[handlerId] = RecordingAnnounceHandler(3, false, false)
            return result("handler_id" to strVal(handlerId), "registered" to boolVal(false))
        }
        val handler = RecordingAnnounceHandler(
            numParams = p.intOpt("num_params") ?: 3,
            receivePathResponses = p.boolOpt("receive_path_responses") ?: false,
            raiseOnCall = p.boolOpt("raise_on_call") ?: false,
        )
        Transport.registerAnnounceHandler(handler, p.strOpt("aspect_filter"))
        val handlerId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        inst.announceHandlers[handlerId] = handler
        result("handler_id" to strVal(handlerId), "registered" to boolVal(true))
    }

    else -> throw IllegalArgumentException("Unknown behavioral command: $command")
}

/** Resolve the instance for a command, or throw. */
private fun inst(p: JsonObject): BehavioralInstance =
    behavioralInstances[p.str("handle")]
        ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")

private fun ByteArray.toByteArrayKey() = network.reticulum.common.ByteArrayKey(this)
