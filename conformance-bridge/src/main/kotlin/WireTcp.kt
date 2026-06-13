/**
 * Wire-level TCP conformance commands for reticulum-kt.
 *
 * Mirrors reference/wire_tcp.py. Unlike BehavioralTransport (MockInterface,
 * zero-wire), this spins up a real reticulum-kt Reticulum instance with a
 * TCPServer or TCPClient interface bound to loopback. Paired with a
 * correspondingly-roled bridge subprocess on the other impl, the two
 * exchange real packets over real TCP with IFAC applied end-to-end.
 *
 * This is the only layer that reproduces reticulum-kt#29's symptom: Kotlin
 * sends on-wire IFAC bytes that the Python receiver's IFAC unmasker
 * silently rejects (or vice versa). Byte-level primitive tests don't hit
 * this because they don't exercise the full transmit/receive pipeline.
 *
 * Commands handled:
 *   wire_start_tcp_server          (accepts optional `mode`)
 *   wire_start_tcp_client          (accepts optional `mode`)
 *   wire_set_interface_mode        (runtime mutation of the interface mode)
 *   wire_announce
 *   wire_poll_path
 *   wire_request_path              (synchronous path request packet send)
 *   wire_read_path_entry           (timestamp / expires / hops / next hop / iface)
 *   wire_has_discovery_path_request
 *   wire_has_announce_table_entry
 *   wire_read_path_random_hash     (for cached-announce byte-identity tests)
 *   wire_listen / wire_link_* / wire_resource_*   (link + resource I/O)
 *   wire_stop
 *
 * One bridge process hosts at most one wire Reticulum singleton. The
 * pytest `wire_peers` fixture spawns two bridges (one per role) to pair
 * a server with a client.
 */

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.RnsConstants
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.discovery.DiscoveredInterface
import network.reticulum.discovery.InterfaceDiscovery
import network.reticulum.identity.Identity
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.pipe.PipeInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.link.RequestReceipt
import network.reticulum.packet.Packet
import network.reticulum.packet.PacketReceipt
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
import network.reticulum.resource.ResourceConstants
import network.reticulum.transport.Transport
import network.reticulum.transport.TransportConstants
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handle-indexed state.
 *
 * role: "server" or "client". A single bridge hosts one role per process
 * — Python RNS is a singleton and Reticulum.stop is per-process, so we
 * don't try to juggle both roles in the same JVM.
 */
private class WireInstance(
    val rns: Reticulum,
    val identityHash: ByteArray,
    val configDir: File,
    val role: String,
    val port: Int,
    val serverIface: TCPServerInterface? = null,
    val clientIface: TCPClientInterface? = null,
    // Phase 5h: PipeInterface leaf/relay plumbing for wire_start_pipe_peer and
    // wire_start_pipe_tcp_relay. The pipe is subprocess-backed (a `bash -c
    // 'cat … & cat > …'` child whose stdin/stdout are ordinary pipes) so the
    // command never blocks on a FIFO open — mirroring Python's PipeInterface
    // subprocess model (reference/wire_tcp.py:779-793 _pipe_command). The
    // process is held so teardown can detach the interface then kill the bash
    // child and release the FIFO fds.
    val pipeIface: PipeInterface? = null,
    val pipeProcess: Process? = null,
    // Shared-instance plumbing for wire_start_tcp_server share_instance=true
    // (sharedServer is a LocalServerInterface listening on TCP loopback so
    // wire_start_local_client peers can attach as the master's clients) and
    // wire_start_local_client (sharedClient is a LocalClientInterface
    // connected to a master process's sharedServer port).
    val sharedServer: network.reticulum.interfaces.local.LocalServerInterface? = null,
    val sharedClient: network.reticulum.interfaces.local.LocalClientInterface? = null,
    val sharedInstancePort: Int? = null,
    val destinations: MutableList<Pair<Identity, Destination>> = mutableListOf(),
    // GROUP destinations keyed by hash hex — mirrors the python bridge's
    // inst["group_dests"] (wire_tcp.py cmd_wire_group_create). A GROUP
    // destination has no identity (symmetric key only), so it can't live in
    // `destinations` alongside the SINGLE ones; keep it in its own map.
    val groupDests: ConcurrentHashMap<String, Destination> = ConcurrentHashMap(),
    // Link bookkeeping — used by wire_listen / wire_link_* commands below.
    val listeners: ConcurrentHashMap<String, Listener> = ConcurrentHashMap(),
    val outLinks: ConcurrentHashMap<String, Link> = ConcurrentHashMap(),
    // Per-handle PacketReceipt store, keyed by a fresh receipt_id hex token —
    // mirrors the reference bridge's inst["receipts"][receipt_id] = receipt
    // (wire_tcp.py:3402-3403). wire_send_packet* stash a receipt here;
    // wire_packet_receipt_status / wire_inject_crafted_proof read it back.
    val receipts: ConcurrentHashMap<String, network.reticulum.packet.PacketReceipt> = ConcurrentHashMap(),
    // Outbound Resources started by a non-blocking wire_resource_send(wait=false),
    // keyed by minted resource_id — looked up by wire_resource_cancel. Mirrors the
    // reference bridge's inst["out_resources"] (wire_tcp.py cmd_wire_resource_send /
    // cmd_wire_resource_cancel).
    val outResources: ConcurrentHashMap<String, Resource> = ConcurrentHashMap(),
)

/**
 * Find a registered SINGLE destination on this handle by its hash. Mirrors
 * the python bridge's `_find_destination_by_hash` — searches the strong-ref
 * `destinations` list and the listener-owned destinations. Returns null when
 * no local destination matches.
 */
private fun findDestinationByHash(inst: WireInstance, destHash: ByteArray): Destination? {
    inst.destinations.firstOrNull { it.second.hash.contentEquals(destHash) }?.let { return it.second }
    inst.listeners.values.firstOrNull { it.destination.hash.contentEquals(destHash) }?.let { return it.destination }
    return null
}

/**
 * Locate the live IFAC-configured interface on this handle, mirroring the
 * python bridge's `_interfaces_matching_handle(...)` scan for the first iface
 * with a non-null ifac_identity. A single wire handle hosts exactly one
 * declared interface (server XOR client), so there is no ambiguity.
 */
private fun ifacInterfaceOrThrow(inst: WireInstance): network.reticulum.interfaces.Interface {
    val iface = inst.serverIface ?: inst.clientIface
    if (iface?.ifacIdentity == null) {
        throw IllegalStateException(
            "No IFAC-configured interface on this handle. Start the peer with " +
                "network_name + passphrase so RNS derives an ifac_identity.",
        )
    }
    return iface
}

/** The 10-byte ratchet id of a ratchet PRIVATE key, hex (reference:
 *  _ratchet_id_hex -> _get_ratchet_id(_ratchet_public_bytes(prv))). */
private fun ratchetIdHex(ratchetPrivate: ByteArray): String {
    val pub = network.reticulum.crypto.defaultCryptoProvider().x25519PublicFromPrivate(ratchetPrivate)
    return Identity.ratchetIdFor(pub).toHex()
}

/** Hex id of the newest (index 0) ratchet, or JSON null. */
private fun currentRatchetIdVal(ratchets: List<ByteArray>): com.google.gson.JsonElement =
    if (ratchets.isEmpty()) JsonNull.INSTANCE else JsonPrimitive(ratchetIdHex(ratchets[0]))

/** Hex id of the second-newest (index 1) ratchet, or JSON null. */
private fun previousRatchetIdVal(ratchets: List<ByteArray>): com.google.gson.JsonElement =
    if (ratchets.size < 2) JsonNull.INSTANCE else JsonPrimitive(ratchetIdHex(ratchets[1]))

private val LINK_STATUS_NAMES = mapOf(
    LinkConstants.PENDING to "PENDING",
    LinkConstants.HANDSHAKE to "HANDSHAKE",
    LinkConstants.ACTIVE to "ACTIVE",
    LinkConstants.STALE to "STALE",
    LinkConstants.CLOSED to "CLOSED",
)
private fun receiptStatusName(status: Int): String? = when (status) {
    network.reticulum.packet.PacketReceipt.FAILED -> "FAILED"
    network.reticulum.packet.PacketReceipt.SENT -> "SENT"
    network.reticulum.packet.PacketReceipt.DELIVERED -> "DELIVERED"
    network.reticulum.packet.PacketReceipt.CULLED -> "CULLED"
    else -> null
}

private val LINK_TEARDOWN_NAMES = mapOf(
    LinkConstants.TEARDOWN_REASON_TIMEOUT to "TIMEOUT",
    LinkConstants.INITIATOR_CLOSED to "INITIATOR_CLOSED",
    LinkConstants.DESTINATION_CLOSED to "DESTINATION_CLOSED",
)

/** Find a link by id across both outbound (initiator) links and the inbound
 *  links accepted by any listener on this handle (reference: _find_link_by_id). */
private fun findLinkById(inst: WireInstance, linkIdHex: String): Link? {
    inst.outLinks[linkIdHex]?.let { return it }
    for (listener in inst.listeners.values) {
        listener.inboundLinks.firstOrNull { it.linkId.toHex() == linkIdHex }?.let { return it }
    }
    return null
}

/** The listener whose accepted inbound links include this link id, or null
 *  (the injector must run on the RECEIVER peer that owns the link's handler). */
private fun findListenerForLink(inst: WireInstance, linkIdHex: String): Listener? =
    inst.listeners.values.firstOrNull { l -> l.inboundLinks.any { it.linkId.toHex() == linkIdHex } }

/** Poll for a link to appear by id. The receiver-side inbound link is captured
 *  by an establishment callback on a background thread, so an injector running
 *  immediately after the initiator's link_open returns can race it. */
private fun findLinkByIdWaiting(inst: WireInstance, linkIdHex: String, timeoutMs: Long = 3000): Link? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        findLinkById(inst, linkIdHex)?.let { return it }
        if (System.currentTimeMillis() >= deadline) return null
        Thread.sleep(50)
    }
}

/** A captured outbound packet emission (context value, dest hash hex, data, raw). */
private class CapturedEmission(val context: Int, val destHashHex: String, val data: ByteArray, val raw: ByteArray)

/** Run [block] with the Transport outbound tap installed, returning every packet
 *  emitted during it — the kotlin equivalent of the reference wrapping
 *  Packet.send to capture a link's emissions. Snapshots context/dest/data/raw
 *  inside the tap since processOutbound may mutate the packet afterward. */
private fun captureOutbound(block: () -> Unit): List<CapturedEmission> {
    val captured = java.util.Collections.synchronizedList(mutableListOf<CapturedEmission>())
    Transport.outboundTapForTest = { pkt ->
        runCatching {
            captured.add(
                CapturedEmission(
                    context = pkt.context.value,
                    destHashHex = pkt.destinationHash.toHex(),
                    data = pkt.data.copyOf(),
                    raw = pkt.pack(),
                ),
            )
        }
    }
    try {
        block()
    } finally {
        Transport.outboundTapForTest = null
    }
    return captured.toList()
}

/** Build the raw wire bytes of a genuine DATA/LINKCLOSE packet encrypted to a
 *  live link, mirroring Link.sendWithReceipt (encrypt then createRaw — pack does
 *  NOT re-encrypt for LINK destinations). The caller may corrupt the returned
 *  bytes before feeding them through link.receive. */
private fun buildLinkPacketRaw(link: Link, plaintext: ByteArray, context: network.reticulum.common.PacketContext): ByteArray {
    val encrypted = link.encrypt(plaintext)
    val pkt = Packet.createRaw(
        destinationHash = link.linkId,
        data = encrypted,
        packetType = network.reticulum.common.PacketType.DATA,
        destinationType = DestinationType.LINK,
        context = context,
        mtu = link.mtu,
    )
    return pkt.pack()
}

/** n cryptographically-random bytes (token_bytes analog). */
private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

/** Integer ceiling division ceil(a/b) for positive b — analog of python's -(-a//b). */
private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

/**
 * Build a real sender Resource on [link] plus the receiver Resource the real
 * Resource.accept constructs from the sender's genuine ResourceAdvertisement —
 * the kotlin analog of the reference _build_resource_receiver (wire_tcp.py:6903).
 *
 * [forceSdu] temporarily shrinks link.mtu (restored after) so the payload chunks
 * into many small parts; the shrink MUST stay in effect through accept so the
 * receiver derives the same per-part SDU as the sender. The watchdog is
 * suppressed for the duration so the inbound state stays synchronously
 * inspectable (the caller drives the state machine and cancels the receiver).
 */
private fun buildResourceReceiver(link: Link, payloadLen: Int, forceSdu: Int? = null): Pair<Resource, Resource> {
    val savedMtu = link.mtu
    if (forceSdu != null) {
        link.setMtuForTest(forceSdu + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
    }
    Resource.watchdogDisabledForTest = true
    try {
        val sender = Resource.create(randomBytes(payloadLen), link, advertise = false)
        // Round-trip the advertisement through pack(segment=0)/unpack so it carries
        // ONLY the first hashmap segment (<= HASHMAP_MAX_LEN entries), exactly like
        // the reference _build_resource_receiver (RNS.ResourceAdvertisement(sender)
        // .pack()). Passing the fromResource object directly would hand accept the
        // FULL hashmap, so hashmap_height would start at the whole part count
        // instead of the segment-0 height the HMU handshake depends on.
        val packed = ResourceAdvertisement.fromResource(sender).pack(0)
        val adv = ResourceAdvertisement.unpack(packed)
            ?: throw IllegalStateException("could not unpack sender advertisement")
        val receiver = Resource.accept(adv, link)
            ?: throw IllegalStateException("Resource.accept did not produce a receiver")
        return sender to receiver
    } finally {
        Resource.watchdogDisabledForTest = false
        link.setMtuForTest(savedMtu)
    }
}

/**
 * Feed a genuine encrypted inbound link packet carrying [plaintext] under
 * [context] through the real [link].receive dispatcher. The packet is built with
 * the link's own encryption (buildLinkPacketRaw), re-parsed, tagged with the
 * link's attached interface so it passes the interface-match guard
 * (Link.kt:1773-1781), then dispatched — the kotlin analog of the reference
 * driving RNS.Link.receive on a crafted inbound packet.
 */
private fun feedInboundLinkPacket(link: Link, plaintext: ByteArray, context: network.reticulum.common.PacketContext) {
    val raw = buildLinkPacketRaw(link, plaintext, context)
    val rx = Packet.unpack(raw)
        ?: throw IllegalStateException("could not unpack crafted inbound resource packet")
    rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)
    link.receive(rx)
}

/** Lifecycle snapshot of an RNS.Link — mirrors the reference _link_status_dict.
 *  keepalive_s/stale_time_s/rtt cross the boundary in SECONDS (kotlin millis). */
private fun linkStatusDict(link: Link): JsonObject {
    val now = System.currentTimeMillis()
    val lastInbound = link.lastInbound
    val lastKeepalive = link.lastKeepalive
    val remoteIdentity = link.getRemoteIdentity()
    return result(
        "status" to intVal(link.status),
        "status_name" to (LINK_STATUS_NAMES[link.status]?.let { strVal(it) } ?: JsonNull.INSTANCE),
        "teardown_reason" to intVal(link.teardownReason),
        "teardown_reason_name" to (LINK_TEARDOWN_NAMES[link.teardownReason]?.let { strVal(it) } ?: JsonNull.INSTANCE),
        "no_inbound_for_ms" to (if (lastInbound > 0) intVal(maxOf(0L, now - lastInbound).toInt()) else JsonNull.INSTANCE),
        "last_keepalive_ago_ms" to (if (lastKeepalive > 0) intVal(maxOf(0L, now - lastKeepalive).toInt()) else JsonNull.INSTANCE),
        "keepalive_s" to doubleVal(link.keepalive / 1000.0),
        "stale_time_s" to doubleVal(link.staleTime / 1000.0),
        "rtt" to (link.rtt?.let { doubleVal(it / 1000.0) } ?: JsonNull.INSTANCE),
        "mtu" to (link.getMtu()?.let { intVal(it) } ?: JsonNull.INSTANCE),
        "mdu" to (link.getMdu()?.let { intVal(it) } ?: JsonNull.INSTANCE),
        "mode" to intVal(link.mode),
        "remote_identity_hash" to (remoteIdentity?.hash?.let { hexVal(it) } ?: JsonNull.INSTANCE),
        "remote_identified" to boolVal(remoteIdentity != null),
    )
}

/**
 * Locate a ratchet-ENABLED SINGLE destination by hash, or throw. Mirrors the
 * reference's `_ratchet_dest_or_raise`: the destination must have been created
 * with enable_ratchets=true (kotlin's ratchetsEnabled flag is the analog of
 * python's `destination.ratchets is not None`).
 */
private fun ratchetDestOrThrow(inst: WireInstance, destHash: ByteArray, handle: String): Destination {
    val destination = findDestinationByHash(inst, destHash)
        ?: throw IllegalArgumentException(
            "No registered destination with hash ${destHash.toHex()} on handle $handle; " +
                "call wire_announce(enable_ratchets=true) first.",
        )
    if (!destination.ratchetsEnabledForTest()) {
        throw IllegalArgumentException(
            "Destination ${destHash.toHex()} does not have ratchets enabled; " +
                "call wire_announce(enable_ratchets=true).",
        )
    }
    return destination
}

/** Per-destination receive buffer for incoming link data + resources. */
private class Listener(
    val destination: Destination,
    val identity: Identity,
    val recvBuffer: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque(),
    val resourceBuffer: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque(),
    // Inbound (receiver-side) links this destination has accepted, in arrival
    // order — lets wire_listener_link_status observe teardown_reason on the
    // side that did NOT initiate the close.
    val inboundLinks: ConcurrentLinkedDeque<Link> = ConcurrentLinkedDeque(),
    // Receiver-side proof log: the raw context byte of every inbound packet
    // this listener's links PROVED, in order — mirrors the reference's
    // listener["proof_log"] populated by wrapping link.prove_packet
    // (wire_tcp.py:1299-1317). Filled by the Link.proveTapForTest tap installed
    // in wire_listen; drained by wire_listener_proof_log.
    val proofLog: ConcurrentLinkedDeque<Int> = ConcurrentLinkedDeque(),
    // Receiver-side inbound Resource observations, in arrival order — populated by
    // the link's resource-started callback (see wire_listen). Mirrors the
    // reference listener's incoming_resources list (wire_tcp.py:1205). Read by
    // wire_resource_receiver_status. The newest record is the most recent inbound
    // Resource; per-resource HMU counters live on the Resource itself.
    val incomingResources: ConcurrentLinkedDeque<Resource> = ConcurrentLinkedDeque(),
    // Resource-strategy this listener applies to each accepted link
    // (Link.ACCEPT_ALL / ACCEPT_NONE / ACCEPT_APP). Default ACCEPT_ALL.
    val resourceStrategy: Int = Link.ACCEPT_ALL,
)

/**
 * Receiver-side decompression bound the listener lowers every inbound Resource to,
 * so the bz2 decompression-bomb guard trips cheaply (mirrors the reference
 * _WIRE_RX_MAX_DECOMPRESSED, wire_tcp.py:130). 256 KiB sits below 1 MiB so it is
 * trippable without splitting; all legitimate wire transfers in the suite send
 * incompressible random bytes (decompressor never runs) or sub-KiB compressible
 * payloads, well under this ceiling.
 */
private const val WIRE_RX_MAX_DECOMPRESSED = 256 * 1024

private val wireInstances = mutableMapOf<String, WireInstance>()

/**
 * Request-handler invocation log, keyed "$handle|$destHex|$path" — mirrors the
 * reference bridge's _request_handler_log. The response generator appends one
 * JSON entry per request that reached the handler; wire_get_request_log drains
 * it. Cleared in resetWireState so it can't leak across tests sharing the JVM.
 */
private val wireRequestHandlerLog = ConcurrentHashMap<String, MutableList<JsonObject>>()

/** Last keepalive byte a link emitted/answered, keyed by link id hex — mirrors
 *  the reference's inst["keepalive_payloads"]. Cleared on reset. */
private val wireKeepalivePayloads = ConcurrentHashMap<String, ByteArray>()

/**
 * Inbound tap — every packet that the bridge hands to Transport.inbound is
 * recorded here. Tests query this buffer (via `wire_get_received_packets`)
 * to prove that a hub node does not fan packets out to peers that shouldn't
 * see them.
 */
private const val INBOUND_TAP_CAP = 1024
private val inboundTapBuffer: ArrayDeque<JsonObject> = ArrayDeque()
private var inboundTapSeq: Long = 0L
private val inboundTapLock = Any()

private fun recordInboundPacket(data: ByteArray, ifaceName: String?) {
    try {
        val nowMs = System.currentTimeMillis()
        // Parse light header — matches the Python tap so tests can filter
        // by packet_type / dest_hash without impl-specific parsing. Low
        // 2 bits of byte 0 encode the packet type (DATA=0, ANNOUNCE=1,
        // LINKREQUEST=2, PROOF=3 per RNS Packet.py).
        var packetType: Int? = null
        var destHashHex: String? = null
        var context: Int? = null
        if (data.isNotEmpty()) {
            packetType = data[0].toInt() and 0b00000011
            if (data.size >= 18) {
                destHashHex = data.copyOfRange(2, 18).toHex()
            }
            if (data.size >= 19) {
                context = data[18].toInt() and 0xff
            }
        }
        val entry = JsonObject()
        synchronized(inboundTapLock) {
            inboundTapSeq += 1
            entry.addProperty("seq", inboundTapSeq)
            entry.addProperty("timestamp_ms", nowMs)
            entry.addProperty("raw_hex", data.toHex())
            if (packetType != null) entry.addProperty("packet_type", packetType) else entry.add("packet_type", JsonNull.INSTANCE)
            if (destHashHex != null) entry.addProperty("destination_hash_hex", destHashHex) else entry.add("destination_hash_hex", JsonNull.INSTANCE)
            if (context != null) entry.addProperty("context", context) else entry.add("context", JsonNull.INSTANCE)
            if (ifaceName != null) entry.addProperty("interface_name", ifaceName) else entry.add("interface_name", JsonNull.INSTANCE)
            if (inboundTapBuffer.size >= INBOUND_TAP_CAP) inboundTapBuffer.removeFirst()
            inboundTapBuffer.addLast(entry)
        }
    } catch (_: Throwable) {
        // The tap must never break routing.
    }
}

/**
 * Parse a free-form `mode` string into an [InterfaceMode].
 *
 * Accepts the same synonyms Python RNS's config parser accepts
 * (`Reticulum.py:619-647`), so either side of a cross-impl wire test can
 * pass the same literal and land on the same mode value. Null / empty
 * input returns null (caller applies the default).
 */
private fun parseInterfaceMode(raw: String?): InterfaceMode? {
    if (raw == null) return null
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    return when (s) {
        "full" -> InterfaceMode.FULL
        "access_point", "accesspoint", "ap" -> InterfaceMode.ACCESS_POINT
        "pointtopoint", "point_to_point", "ptp" -> InterfaceMode.POINT_TO_POINT
        "roaming" -> InterfaceMode.ROAMING
        "boundary" -> InterfaceMode.BOUNDARY
        "gateway", "gw" -> InterfaceMode.GATEWAY
        else -> throw IllegalArgumentException("Unknown interface mode: $raw")
    }
}

/**
 * Existence check for the LXMF bridge layer.
 *
 * The LXMF layer borrows the RNS singleton that the wire layer brought
 * up and attaches its own LXMRouter — it never needs to mutate wire
 * state. All we expose is a "did this handle ever exist" check so that
 * Lxmf.kt can throw a coherent `IllegalArgumentException("Unknown
 * wire_handle")` instead of letting an NPE bubble out of the Destination
 * constructor when Transport isn't initialized.
 */
internal fun wireHandleExists(handle: String): Boolean =
    wireInstances.containsKey(handle)

/** Pre-allocate a free loopback port using bind-then-release.
 *  Tiny race window; acceptable for localhost test use. */
private fun allocateFreePort(): Int {
    val s = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    try {
        return s.localPort
    } finally {
        s.close()
    }
}

/** Detach interfaces, clear the map, and stop the RNS singleton.
 *  Clearing the map BEFORE stopping ensures no stale handle can survive
 *  and point at a dead Reticulum. */
private fun resetWireState() {
    val stale = wireInstances.values.toList()
    wireInstances.clear()
    wireRequestHandlerLog.clear()
    wireKeepalivePayloads.clear()
    runCatching { Transport.outboundTapForTest = null }
    // Process-wide implicit-proof policy is a JVM singleton (Reticulum companion);
    // restore the RNS default (true) so a wire_set_proof_implicit toggle in one
    // test cannot leak into the next.
    runCatching { Reticulum.setUseImplicitProofForTest(true) }
    // Reset the JVM-singleton Resource watchdog suppression so a test that left
    // it set (e.g. crashed mid buildResourceReceiver) can't leak into the next.
    runCatching { Resource.watchdogDisabledForTest = false }
    for (inst in stale) {
        runCatching { inst.serverIface?.detach() }
        runCatching { inst.clientIface?.detach() }
        runCatching { inst.sharedClient?.detach() }
        runCatching { inst.sharedServer?.detach() }
        // Phase 5h: detach the PipeInterface (closes its stream ends → the
        // bash subprocess's stdin/stdout close → cat exits) THEN destroy the
        // subprocess so the bash child and FIFO fds don't leak across the
        // JVM's lifetime. Symmetric with serverIface/clientIface above.
        runCatching { inst.pipeIface?.detach() }
        runCatching { inst.pipeProcess?.destroy() }
        runCatching { inst.configDir.deleteRecursively() }
    }
    runCatching { Reticulum.stop() }
    // Drop any pre-start factory / registrar lambdas the previous call may have
    // installed on the Reticulum companion (auto_attach mode does this — see
    // wire_start_local_client). Without this, the captured `pendingClient`
    // array and any other closure state from a prior session would survive
    // across resets and could be applied to the next Reticulum.start() that
    // happened to consult them (e.g. another auto_attach=true call that
    // re-set the lambdas anyway — overwritten, fine — but also any path that
    // unexpectedly hits tryConnectToSharedInstance or startLocalServer with
    // stale state).
    runCatching { Reticulum.clearPendingFactories() }
}

/**
 * Locate the live wire interface on this handle (server XOR client). Mirrors the
 * python bridge's `_primary_wire_interface(inst)`. Returns null when neither is
 * configured (the caller throws a coherent error).
 */
private fun primaryWireInterface(inst: WireInstance): network.reticulum.interfaces.Interface? =
    inst.serverIface ?: inst.clientIface

/**
 * Whether this handle is a connected local client of a shared instance (python
 * rns.is_connected_to_shared_instance). The manual local-client attach path does
 * not flip the rns-core instance flag, but a handle created via
 * wire_start_local_client IS connected to a shared master by construction (its
 * role records that), so report the truthful posture from either signal.
 */
private fun isConnectedToSharedInstance(inst: WireInstance): Boolean =
    inst.role == "local_client" || inst.rns.isConnectedToSharedInstance

/** Phase-5g process-wide posture/config knobs resolved from the start params. */
private class StartConfig(
    val enableTransport: Boolean,
    val respondToProbes: Boolean,
    val useImplicitProof: Boolean,
    val enableRemoteManagement: Boolean,
    val remoteManagementAllowed: List<ByteArray>,
    val panicOnInterfaceError: Boolean,
    val blackholeSources: List<ByteArray>,
    val interfaceDiscoverySources: List<ByteArray>,
    val rpcKey: String?,
)

/** Per-interface tuning knobs (applied in the TCP interface constructors). */
private class IfaceTuning(
    val bitrate: Int?,
    val fixedMtu: Int?,
    val ifacSizeBits: Int?,
)

/**
 * Parse the Phase-5g start config + interface tuning from the wire params.
 *
 * The reference bridge writes these into the INI `[reticulum]` / interface
 * sections (wire_tcp.py:320-405); kotlin has no config layer, so they thread as
 * typed Reticulum.start() / interface-constructor params. Invalid-hex ACL/source
 * entries throw here (during fromHex) → BridgeError, mirroring RNS's
 * bytes.fromhex guard (Reticulum.py:580-581); wrong-LENGTH entries are caught by
 * Reticulum.start()'s 16-byte validator.
 *
 * enable_transport is tri-state (reference wire_tcp.py:484-485): key ABSENT ->
 * the role default; explicit JSON null -> option-absent default-OFF
 * (Reticulum.py:253); explicit bool -> that value.
 */
private fun parseStartConfig(p: JsonObject, defaultEnableTransport: Boolean): Pair<StartConfig, IfaceTuning> {
    val enableTransport =
        if (p.has("enable_transport")) {
            val el = p.get("enable_transport")
            if (el.isJsonNull) false else el.asBoolean
        } else {
            defaultEnableTransport
        }

    fun hashList(key: String): List<ByteArray> = p.stringArray(key).map { it.fromHex() }
    fun boolOr(key: String, default: Boolean): Boolean =
        p.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

    val cfg = StartConfig(
        enableTransport = enableTransport,
        respondToProbes = boolOr("respond_to_probes", false),
        useImplicitProof = boolOr("use_implicit_proof", true),
        enableRemoteManagement = boolOr("enable_remote_management", false),
        remoteManagementAllowed = hashList("remote_management_allowed"),
        panicOnInterfaceError = boolOr("panic_on_interface_error", false),
        blackholeSources = hashList("blackhole_sources"),
        interfaceDiscoverySources = hashList("interface_discovery_sources"),
        rpcKey = p.get("rpc_key")?.takeIf { !it.isJsonNull }?.asString,
    )
    val tuning = IfaceTuning(
        bitrate = p.intOpt("bitrate"),
        fixedMtu = p.intOpt("fixed_mtu"),
        ifacSizeBits = p.intOpt("ifac_size"),
    )
    return cfg to tuning
}

/** Fresh 8-byte hex receipt id token — mirrors the reference's
 *  `secrets.token_hex(8)` keying inst["receipts"] (wire_tcp.py:3402). */
private fun freshReceiptId(): String = defaultCryptoProvider().randomBytes(8).toHex()

/**
 * Build a GENUINE announce frame for a FRESH, FOREIGN destination, mirroring the
 * reference's `_build_foreign_announce` (wire_tcp.py:9798-9824): create a real IN
 * SINGLE destination (auto-registered in Transport), capture its UNMASKED announce
 * via announce(send=false) -> pack(), then deregister it so the local Transport
 * treats the announce as foreign (path-learnable on inbound). A strong ref is kept
 * on the handle so neither identity nor destination is GC'd before inbound runs.
 * No protocol bytes are assembled — announcePacket.raw is whatever RNS packed.
 */
private fun buildForeignAnnounce(
    inst: WireInstance,
    appName: String,
    aspects: Array<String>,
    appData: ByteArray,
): Pair<Destination, ByteArray> {
    val id = Identity.create()
    val dest = Destination.create(id, DestinationDirection.IN, DestinationType.SINGLE, appName, *aspects)
    val ann = dest.announce(appData = appData, send = false)
        ?: throw IllegalStateException("announce(send=false) returned null")
    val plainRaw = ann.pack()
    Transport.deregisterDestination(dest)
    inst.destinations.add(id to dest)
    return dest to plainRaw
}

fun handleWireCommand(command: String, p: JsonObject): JsonObject = when (command) {
    "wire_start_tcp_server" -> {
        val networkName = p.get("network_name")?.asString?.takeIf { it.isNotEmpty() }
        val passphrase = p.get("passphrase")?.asString?.takeIf { it.isNotEmpty() }
        val bindPortReq = p.get("bind_port")?.asInt ?: 0
        val bindPort = if (bindPortReq == 0) allocateFreePort() else bindPortReq
        val desiredMode = parseInterfaceMode(p.get("mode")?.asString)
        val shareInstanceParam = p.get("share_instance")?.asBoolean ?: false
        val shareInstanceTypeParam = p.get("share_instance_type")?.asString?.lowercase()
        if (shareInstanceParam && shareInstanceTypeParam != null && shareInstanceTypeParam != "tcp") {
            throw IllegalArgumentException(
                "Kotlin bridge only supports share_instance_type=tcp (got " +
                    "$shareInstanceTypeParam). Reticulum-kt's LocalServerInterface" +
                    " has TCP and Unix-socket constructors, but the cross-impl" +
                    " interop path the conformance suite relies on is TCP loopback.",
            )
        }
        val sharedInstancePort: Int? =
            if (shareInstanceParam) allocateFreePort() else null

        // Clear any prior wire state (detach interfaces, drop handles,
        // stop the RNS singleton) so this call starts clean and no stale
        // handle can survive pointing at a dead Reticulum.
        resetWireState()

        // Phase 5g posture/config knobs threaded into the typed Reticulum.start()
        // params (kotlin has no INI config layer; mirrors what the reference
        // bridge writes into the [reticulum] section). See wire_instance_posture
        // / wire_rpc_authkey / wire_interface_bitrate / wire_interface_hw_mtu.
        val (cfg, ifaceTuning) = parseStartConfig(p, defaultEnableTransport = true)

        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_server_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = cfg.enableTransport,
                shareInstance = false,
                connectToSharedInstance = false,
                respondToProbes = cfg.respondToProbes,
                useImplicitProof = cfg.useImplicitProof,
                enableRemoteManagement = cfg.enableRemoteManagement,
                remoteManagementAllowed = cfg.remoteManagementAllowed,
                panicOnInterfaceError = cfg.panicOnInterfaceError,
                blackholeSources = cfg.blackholeSources,
                interfaceDiscoverySources = cfg.interfaceDiscoverySources,
                rpcKey = cfg.rpcKey,
            )

            val server = TCPServerInterface(
                name = "Wire TCP Server",
                bindAddress = "127.0.0.1",
                bindPort = bindPort,
                ifacNetname = networkName,
                ifacNetkey = passphrase,
                bitrate = ifaceTuning.bitrate,
                fixedMtuBytes = ifaceTuning.fixedMtu,
                ifacSizeBits = ifaceTuning.ifacSizeBits,
            )
            // Park the interface in the requested mode BEFORE registering
            // with Transport. The mode is read by the InterfaceAdapter's
            // getter, so the Transport-facing view reflects the override
            // as soon as it's observed. Setting it post-register would
            // race with any in-flight inbound packet that reads mode to
            // decide DISCOVER_PATHS_FOR eligibility.
            if (desiredMode != null) server.modeOverride = desiredMode
            // Register spawned client interfaces with Transport so the
            // outbound routing layer can locate them — without this,
            // path responses with `attachedInterface = spawned-child` get
            // silently dropped at Transport.kt:3632-3658 because the
            // spawned child isn't in `interfaces`. Python registers all
            // spawned children at RNS/Interfaces/TCPInterface.py:623
            // (Transport.interfaces.append(spawned_interface)); we mirror
            // that here. See also: deregister on disconnect handled
            // by TCPServerInterface:195.
            server.onClientConnected = { spawnedChild ->
                // Surface registration failures (e.g., Transport not yet
                // initialized) so a silently-unregistered child doesn't
                // re-introduce the exact symptom this callback fixes —
                // path responses dropped at Transport.kt:3632-3658
                // because the spawned interface isn't in `interfaces`.
                runCatching { Transport.registerInterface(spawnedChild.toRef()) }
                    .onFailure { e ->
                        System.err.println(
                            "[WireTcp] Failed to register spawned client ${spawnedChild.name}: $e",
                        )
                    }
            }
            server.start()
            // Register with Transport so the Transport layer considers this
            // interface a valid outbound path AND so inbound packets land in
            // the announce/path pipeline.
            val serverRef = server.toRef()
            Transport.registerInterface(serverRef)
            server.onPacketReceived = { data, iface ->
                recordInboundPacket(data, iface.name)
                Transport.inbound(data, iface.toRef())
            }

            // Optional shared-instance master: stand up a LocalServerInterface
            // on a separate loopback port. wire_start_local_client peers will
            // attach as TCP clients there, and the existing TCPServerInterface
            // forwarding logic still handles remote (TCP) clients on bindPort.
            //
            // Mirrors what app/ReticulumService.kt does manually for production
            // use (line 418+ in eridanus' rns-android service): construct,
            // wire onPacketReceived → Transport.inbound, start, register.
            // Cannot use Reticulum.start(shareInstance=true) directly because
            // the bridge's wire_* commands assume the post-start Transport
            // identity is already final, and the factory-based path in
            // Reticulum.startLocalServer requires a pre-set factory which the
            // bridge doesn't have plumbed in.
            var sharedServer: LocalServerInterface? = null
            if (sharedInstancePort != null) {
                sharedServer = LocalServerInterface(
                    name = "Wire SharedInstance",
                    tcpPort = sharedInstancePort,
                )
                sharedServer.onPacketReceived = { data, fromInterface ->
                    Transport.inbound(data, fromInterface.toRef())
                }
                sharedServer.start()
                Transport.registerInterface(sharedServer.toRef())
            }

            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport started without an identity")

            val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            wireInstances[handle] = WireInstance(
                rns = rns,
                identityHash = identityHash,
                configDir = configDir,
                role = "server",
                port = bindPort,
                serverIface = server,
                sharedServer = sharedServer,
                sharedInstancePort = sharedInstancePort,
            )

            val resultEntries = mutableListOf(
                "handle" to (JsonPrimitive(handle) as com.google.gson.JsonElement),
                "port" to JsonPrimitive(bindPort),
                "identity_hash" to hexVal(identityHash),
            )
            if (sharedInstancePort != null) {
                resultEntries += "shared_instance_port" to JsonPrimitive(sharedInstancePort)
                resultEntries += "share_instance_type" to JsonPrimitive("tcp")
            }
            result(*resultEntries.toTypedArray())
        } catch (t: Throwable) {
            // Partial setup — roll back RNS and the temp dir so we don't
            // leak them for the remainder of the bridge process's lifetime.
            runCatching { Reticulum.stop() }
            runCatching { configDir.deleteRecursively() }
            throw t
        }
    }

    "wire_start_local_client" -> {
        // Spin up an RNS instance that attaches to an already-running
        // shared-instance master (another bridge process running
        // wire_start_tcp_server with share_instance=true). No TCP/UDP
        // interface is added — the only attachment is the LocalClientInterface
        // pointing at the master's `shared_instance_port`. Mirrors what an
        // Eridanus or Sideband client does on a phone hosting Carina/Sideband
        // as the shared instance.
        //
        // The master MUST be started first; otherwise the connect will fail
        // (no listener) and we surface a clear error instead of silently
        // becoming a standalone Reticulum.
        //
        // Two attach codepaths, selected by the `auto_attach` param:
        //
        //   auto_attach=false (default, "manual"):
        //     construct LocalClientInterface, wire onPacketReceived → Transport.inbound,
        //     call sharedClient.start() and Transport.registerInterface(sharedClient.toRef()).
        //     This matches the conformance-bridge's historical behaviour and the bridge in
        //     rns-cli's PipePeer manual-mode.
        //
        //   auto_attach=true:
        //     call Reticulum.setLocalClientFactory + Reticulum.setInterfaceRegistrar, then
        //     Reticulum.start(connectToSharedInstance=true). rns-core itself constructs the
        //     LocalClientInterface (via the factory) and hands it back through the registrar,
        //     which is the only place onPacketReceived gets wired and Transport.registerInterface
        //     gets called. This codepath is what rns-android.ReticulumService uses on real
        //     devices (Eridanus, Caelum) — and is the path that lost packets silently when the
        //     registrar was missing (regression fixed by reticulum-kt#68). Without an
        //     auto_attach-mode test in canonical CI, any future regression here would only
        //     surface on hardware again.
        val sharedInstancePortParam = p.get("shared_instance_port")?.asInt
            ?: throw IllegalArgumentException(
                "wire_start_local_client requires shared_instance_port (the " +
                    "master's TCP port from wire_start_tcp_server's response)",
            )
        val autoAttach = p.get("auto_attach")?.asBoolean ?: false
        // instance_control_port and rpc_key are Python-only (Reticulum.py
        // brings up an RPC control listener for shared instances; reticulum-kt
        // doesn't). Accept them for cross-impl plumbing parity but ignore.
        p.get("instance_control_port")
        p.get("rpc_key")

        resetWireState()

        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_localclient_").toFile()
        try {
            if (!Reticulum.isSharedInstanceRunning(sharedInstancePortParam)) {
                throw IllegalStateException(
                    "wire_start_local_client expected a shared-instance master " +
                        "listening on TCP 127.0.0.1:$sharedInstancePortParam, but " +
                        "nothing is bound there. Start the master via " +
                        "wire_start_tcp_server with share_instance=true first.",
                )
            }

            // enableTransport=false matches Python's behavior at
            // Reticulum.py:418: clients of a shared instance never act as
            // transport routers — the master owns transport.
            val rns: Reticulum
            val sharedClient: LocalClientInterface

            if (autoAttach) {
                // Auto-attach path: wire the factory + registrar before start()
                // so rns-core's tryConnectToSharedInstance can construct and
                // register the LocalClientInterface itself. The registrar is
                // where onPacketReceived gets wired and Transport.registerInterface
                // is called — both load-bearing for packet flow.
                val pendingClient = arrayOfNulls<LocalClientInterface>(1)
                Reticulum.setLocalClientFactory { port, host ->
                    LocalClientInterface(
                        name = "Wire LocalClient (auto-attach)",
                        tcpPort = port,
                        tcpHost = host,
                    ).also { pendingClient[0] = it }
                }
                Reticulum.setInterfaceRegistrar { iface ->
                    if (iface is network.reticulum.interfaces.Interface) {
                        Transport.registerInterface(iface.toRef())
                    }
                }

                rns = Reticulum.start(
                    configDir = configDir.absolutePath,
                    enableTransport = false,
                    shareInstance = false,
                    connectToSharedInstance = true,
                    sharedInstancePort = sharedInstancePortParam,
                )

                sharedClient = pendingClient[0]
                    ?: throw IllegalStateException(
                        "auto-attach path: LocalClientInterface factory was never " +
                            "invoked by Reticulum.start(connectToSharedInstance=true). " +
                            "rns-core's tryConnectToSharedInstance may have short-circuited.",
                    )

                // Defensive cross-check: post-conditions of the auto-attach contract.
                check(rns.isConnectedToSharedInstance) {
                    "auto-attach path: Reticulum.start returned without flipping " +
                        "isConnectedToSharedInstance — tryConnectToSharedInstance probably " +
                        "failed silently. Inspect rns-core logs."
                }
            } else {
                // Manual path: same as before reticulum-kt#69. Kept as the default
                // for backward-compat with existing tests; new shared-instance tests
                // should opt into auto_attach=true to cover the rns-android codepath.
                rns = Reticulum.start(
                    configDir = configDir.absolutePath,
                    enableTransport = false,
                    shareInstance = false,
                    connectToSharedInstance = false,
                )

                sharedClient = LocalClientInterface(
                    name = "Wire LocalClient",
                    tcpPort = sharedInstancePortParam,
                )
                // Wire inbound dispatch BEFORE start() so the read loop can't
                // observe a null callback for a frame arriving in the gap
                // between start() and the .toRef() call below (which would
                // otherwise lazily install a default Transport.inbound dispatch
                // via InterfaceAdapter.init). Matches the explicit pattern used
                // by server/sharedServer/client in this same file, and removes
                // a startup race even if a master sends a frame the moment the
                // socket is accepted.
                sharedClient.onPacketReceived = { data, iface ->
                    Transport.inbound(data, iface.toRef())
                }
                sharedClient.start()
                Transport.registerInterface(sharedClient.toRef())
            }

            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport started without an identity")

            val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            wireInstances[handle] = WireInstance(
                rns = rns,
                identityHash = identityHash,
                configDir = configDir,
                role = "local_client",
                port = sharedInstancePortParam,
                sharedClient = sharedClient,
                sharedInstancePort = sharedInstancePortParam,
            )

            result(
                "handle" to JsonPrimitive(handle),
                "identity_hash" to hexVal(identityHash),
                "auto_attach" to JsonPrimitive(autoAttach),
            )
        } catch (t: Throwable) {
            runCatching { Reticulum.stop() }
            // Symmetric with resetWireState(): if start() succeeded but a
            // post-condition check (e.g. isConnectedToSharedInstance) threw,
            // the factory + registrar are still installed on the Reticulum
            // companion. Drop them so the next Reticulum.start() doesn't
            // inherit closures referencing this failed session's pendingClient.
            runCatching { Reticulum.clearPendingFactories() }
            runCatching { configDir.deleteRecursively() }
            throw t
        }
    }

    "wire_start_tcp_client" -> {
        val networkName = p.get("network_name")?.asString?.takeIf { it.isNotEmpty() }
        val passphrase = p.get("passphrase")?.asString?.takeIf { it.isNotEmpty() }
        val targetHost = p.str("target_host")
        val targetPort = p.int("target_port")
        val desiredMode = parseInterfaceMode(p.get("mode")?.asString)

        val (cfg, ifaceTuning) = parseStartConfig(p, defaultEnableTransport = true)

        resetWireState()

        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_client_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = cfg.enableTransport,
                shareInstance = false,
                connectToSharedInstance = false,
                respondToProbes = cfg.respondToProbes,
                useImplicitProof = cfg.useImplicitProof,
                enableRemoteManagement = cfg.enableRemoteManagement,
                remoteManagementAllowed = cfg.remoteManagementAllowed,
                panicOnInterfaceError = cfg.panicOnInterfaceError,
                blackholeSources = cfg.blackholeSources,
                interfaceDiscoverySources = cfg.interfaceDiscoverySources,
                rpcKey = cfg.rpcKey,
            )

            val client = TCPClientInterface(
                name = "Wire TCP Client",
                targetHost = targetHost,
                targetPort = targetPort,
                ifacNetname = networkName,
                ifacNetkey = passphrase,
                bitrate = ifaceTuning.bitrate,
                fixedMtuBytes = ifaceTuning.fixedMtu,
                ifacSizeBits = ifaceTuning.ifacSizeBits,
            )
            if (desiredMode != null) client.modeOverride = desiredMode
            client.start()
            val clientRef = client.toRef()
            Transport.registerInterface(clientRef)
            client.onPacketReceived = { data, iface ->
                recordInboundPacket(data, iface.name)
                Transport.inbound(data, iface.toRef())
            }

            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport started without an identity")

            val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            wireInstances[handle] = WireInstance(
                rns = rns,
                identityHash = identityHash,
                configDir = configDir,
                role = "client",
                port = targetPort,
                clientIface = client,
            )

            result(
                "handle" to JsonPrimitive(handle),
                "identity_hash" to hexVal(identityHash),
            )
        } catch (t: Throwable) {
            runCatching { Reticulum.stop() }
            runCatching { configDir.deleteRecursively() }
            throw t
        }
    }

    "wire_announce" -> {
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val appDataHex = p.get("app_data")?.asString ?: ""

        val enableRatchets = p.get("enable_ratchets")?.asBoolean ?: false

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = aspects,
        )
        // Enable per-destination ratchets BEFORE announcing so the announce
        // carries the latest ratchet public key (context flag set) and the
        // destination tracks a ratchet store — the "A enables ratchets +
        // announces" precondition for the ratchet observables.
        if (enableRatchets) {
            val ratchetsFile = File(inst.configDir, "ratchets_${destination.hash.toHex()}_${UUID.randomUUID().toString().take(8)}")
            destination.enableRatchets(ratchetsFile.absolutePath)
        }
        val appData: ByteArray? = if (appDataHex.isNotEmpty()) appDataHex.fromHex() else null
        destination.announce(appData = appData)
        inst.destinations.add(identity to destination)

        val entries = mutableListOf<Pair<String, com.google.gson.JsonElement>>(
            "destination_hash" to hexVal(destination.hash),
            "identity_hash" to hexVal(identity.hash),
        )
        if (enableRatchets) {
            val ratchets = destination.ratchetsSnapshotForTest()
            entries += "ratchets_enabled" to boolVal(destination.ratchetsEnabledForTest())
            entries += "current_ratchet_id" to currentRatchetIdVal(ratchets)
            entries += "ratchet_count" to intVal(ratchets.size)
        }
        result(*entries.toTypedArray())
    }

    "wire_poll_path" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 5000

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Busy-loop until path appears or deadline hits.
        val deadline = System.currentTimeMillis() + timeoutMs
        var foundHops: Int? = null
        while (System.currentTimeMillis() < deadline && foundHops == null) {
            if (Transport.hasPath(destHash)) {
                foundHops = Transport.hopsTo(destHash) ?: 0
            } else {
                Thread.sleep(50)
            }
        }
        if (foundHops != null) {
            result("found" to boolVal(true), "hops" to intVal(foundHops))
        } else {
            result("found" to boolVal(false))
        }
    }

    "wire_listen" -> {
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()

        val enableRatchets = p.get("enable_ratchets")?.asBoolean ?: false
        // Resource-acceptance strategy this listener applies to inbound links
        // (Link.set_resource_strategy). Mirrors cmd_wire_listen's resource_strategy
        // ('all'|'none'|'app'); default ACCEPT_ALL.
        val resourceStrategy = when (p.get("resource_strategy")?.asString) {
            "none" -> Link.ACCEPT_NONE
            "app" -> Link.ACCEPT_APP
            "all", null -> Link.ACCEPT_ALL
            else -> throw IllegalArgumentException(
                "Unknown resource_strategy: ${p.get("resource_strategy")?.asString} (use all|none|app)",
            )
        }

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = aspects,
        )
        // Enable per-destination ratchets on the IN destination before its
        // immediate announce, mirroring cmd_wire_listen enable_ratchets, so the
        // destination-level ratchet observables operate on a ratchet-bearing dest.
        if (enableRatchets) {
            val ratchetsFile = File(inst.configDir, "ratchets_${destination.hash.toHex()}_${UUID.randomUUID().toString().take(8)}")
            destination.enableRatchets(ratchetsFile.absolutePath)
        }

        val listener = Listener(destination, identity, resourceStrategy = resourceStrategy)
        // On link established, wire both packet and resource callbacks into
        // the listener's buffers.
        destination.setLinkEstablishedCallback { linkObj ->
            val link = linkObj as? Link ?: return@setLinkEstablishedCallback
            // Track the accepted inbound link so wire_listener_link_status can
            // observe its lifecycle (e.g. teardown_reason after the initiator closes).
            listener.inboundLinks.add(link)
            // Record every packet this link proves into the listener's proof log,
            // the kotlin analog of the reference wrapping link.prove_packet
            // (wire_tcp.py:1299-1317). contextRaw is the raw wire context int —
            // 0x00 for proved link DATA, 0x0E (CHANNEL) for proved channel packets.
            link.proveTapForTest = { provedPkt -> listener.proofLog.add(provedPkt.contextRaw) }
            link.setPacketCallback { data, _packet ->
                listener.recvBuffer.add(data.copyOf())
            }
            // Apply the listener's resource-acceptance strategy. For ACCEPT_APP,
            // install a deterministic predicate accepting a Resource iff its
            // advertised uncompressed data size <= 4096 (mirrors the reference
            // app_accept, wire_tcp.py _RESOURCE_APP_ACCEPT_MAX_SIZE).
            link.setResourceStrategy(listener.resourceStrategy)
            if (listener.resourceStrategy == Link.ACCEPT_APP) {
                link.setResourceCallback { adv -> adv.dataSize <= 4096 }
            }

            // Record each inbound Resource for wire_resource_receiver_status and
            // lower its decompression bound so the bz2-bomb guard trips cheaply
            // (mirrors the reference on_resource_started, wire_tcp.py:1245-1290).
            link.setResourceStartedCallback { resourceObj ->
                val resource = resourceObj as? Resource ?: return@setResourceStartedCallback
                runCatching { resource.setMaxDecompressedSizeForTest(WIRE_RX_MAX_DECOMPRESSED) }
                listener.incomingResources.add(resource)
            }

            // Per-link dedup set: reticulum-kt's Resource.assemble() invokes
            // Link.resourceConcluded twice for the same completed resource
            // (once directly at Resource.kt:1194, again via
            // callbacks.completed at Resource.kt:1200 — which itself was
            // wired to Link.resourceConcluded by Link.ACCEPT_ALL's call
            // to Resource.accept at Link.kt:2036). Both invocations spawn
            // daemon threads that land here, so without dedup a single
            // completed transfer gets enqueued twice and wire_resource_poll
            // returns duplicate entries. Keyed by resource.hash hex so
            // distinct resources on the same link each get their own slot.
            val seenResources: MutableSet<String> =
                java.util.Collections.newSetFromMap(ConcurrentHashMap())

            link.setResourceConcludedCallback { resourceObj ->
                val resource = resourceObj as? Resource ?: return@setResourceConcludedCallback
                if (resource.status != ResourceConstants.COMPLETE) return@setResourceConcludedCallback

                val hashHex = resource.hash.toHex()
                if (!seenResources.add(hashHex)) {
                    // Duplicate invocation from the upstream double-fire —
                    // drop silently.
                    return@setResourceConcludedCallback
                }

                val data = resource.data
                if (data != null) {
                    listener.resourceBuffer.add(data.copyOf())
                } else {
                    // `Resource.data` is nullable even for a COMPLETE
                    // resource. Silently dropping the payload would make
                    // a successful transfer indistinguishable from a
                    // missed one (wire_resource_poll would block until
                    // timeout). Surface it on stderr so a test author
                    // debugging an apparent missed delivery can see it.
                    System.err.println("[WireTcp] wire_listen: COMPLETE resource has null data, dropping")
                }
            }
        }

        // Announce so the sender peer can learn a path via the transport.
        destination.announce()

        inst.listeners[destination.hash.toHex()] = listener
        // Keep strong refs so neither gets GC'd.
        inst.destinations.add(identity to destination)

        result(
            "destination_hash" to hexVal(destination.hash),
            "identity_hash" to hexVal(identity.hash),
            // public_key lets recall tests assert the recalled key is
            // byte-identical to the announced one, not merely the right length.
            "public_key" to hexVal(identity.getPublicKey()),
        )
    }

    "wire_link_open" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 10000

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; " +
                    "ensure an announce was received first.",
            )

        val outDest = Destination.create(
            identity = identity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = aspects,
        )

        val latch = CountDownLatch(1)
        val link = Link.create(
            destination = outDest,
            establishedCallback = { latch.countDown() },
            closedCallback = { latch.countDown() },
        )

        if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            throw IllegalStateException(
                "Link to ${destHash.toHex()} did not become active within ${timeoutMs}ms",
            )
        }
        if (link.status != LinkConstants.ACTIVE) {
            throw IllegalStateException(
                "Link to ${destHash.toHex()} closed before becoming active (status=${link.status})",
            )
        }

        val linkIdHex = link.linkId.toHex()
        inst.outLinks[linkIdHex] = link
        result("link_id" to JsonPrimitive(linkIdHex))
    }

    "wire_link_send" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val payload = p.hex("data")

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val ok = link.send(payload)
        result("sent" to boolVal(ok))
    }

    "wire_resource_send" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val payload = p.hex("data")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 30000
        val metadata = p.hexOpt("metadata")
        val wait = p.get("wait")?.asBoolean ?: true

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        if (!wait) {
            // Non-blocking start: the transfer runs on its own worker (advertise
            // spawns the driving threads); mint a resource_id, stash the Resource
            // so wire_resource_cancel can find it, and return immediately. Mirrors
            // cmd_wire_resource_send wait=False (wire_tcp.py).
            val resource = Resource.create(data = payload, link = link, metadata = metadata)
            val resourceId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }.toHex()
            inst.outResources[resourceId] = resource
            result(
                "started" to boolVal(true),
                "resource_id" to strVal(resourceId),
                "size" to intVal(payload.size),
                "has_metadata" to boolVal(resource.hasMetadata),
                "total_segments" to intVal(resource.totalSegments),
            )
        } else {
            val done = CountDownLatch(1)
            val finalStatus = java.util.concurrent.atomic.AtomicInteger(-1)

            val resource = Resource.create(
                data = payload,
                link = link,
                metadata = metadata,
                callback = { r ->
                    finalStatus.set(r.status)
                    done.countDown()
                },
            )

            val finished = done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                // Cancel the still-running transfer so its background worker
                // can't fire the resource-concluded callback on the receiver
                // later and leave a phantom payload in the listener's buffer
                // for a subsequent wire_resource_poll in the same test to
                // pick up. Symmetric with the Python bridge's on-timeout
                // cancel.
                runCatching { resource.cancel() }
            }
            val status = finalStatus.get().takeIf { it >= 0 } ?: resource.status
            val success = finished && status == ResourceConstants.COMPLETE
            result(
                "success" to boolVal(success),
                "status" to intVal(status),
                "size" to intVal(payload.size),
                "timed_out" to boolVal(!finished),
                "has_metadata" to boolVal(resource.hasMetadata),
                "total_segments" to intVal(resource.totalSegments),
                "original_hash" to hexVal(resource.originalHash),
                "compressed" to boolVal(resource.compressed),
            )
        }
    }

    "wire_resource_poll" -> {
        val handle = p.str("handle")
        val destHashHex = p.str("destination_hash")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 30000

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val listener = inst.listeners[destHashHex]
            ?: throw IllegalArgumentException(
                "No listener registered for destination_hash=$destHashHex",
            )

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && listener.resourceBuffer.isEmpty()) {
            Thread.sleep(100)
        }

        val arr = JsonArray()
        while (true) {
            val item = listener.resourceBuffer.pollFirst() ?: break
            arr.add(item.toHex())
        }
        result("resources" to arr)
    }

    "wire_link_poll" -> {
        val handle = p.str("handle")
        val destHashHex = p.str("destination_hash")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 5000

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val listener = inst.listeners[destHashHex]
            ?: throw IllegalArgumentException(
                "No listener registered for destination_hash=$destHashHex",
            )

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && listener.recvBuffer.isEmpty()) {
            Thread.sleep(50)
        }

        val arr = JsonArray()
        while (true) {
            val item = listener.recvBuffer.pollFirst() ?: break
            arr.add(item.toHex())
        }
        result("packets" to arr)
    }

    "wire_set_interface_mode" -> {
        val handle = p.str("handle")
        val modeStr = p.str("mode")
        val newMode = parseInterfaceMode(modeStr)
            ?: throw IllegalArgumentException("Empty mode string")

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Mutate the bridge's only declared interface. We support exactly
        // one per handle (server OR client), so there's no ambiguity about
        // which interface gets the override.
        val target = inst.serverIface ?: inst.clientIface
            ?: throw IllegalStateException(
                "Handle $handle has neither serverIface nor clientIface",
            )
        target.modeOverride = newMode
        // TCPServerInterface's spawned client interfaces each hold their
        // own `modeOverride` copied at spawn time (TCPServerInterface.kt).
        // Keep them in sync with the parent so a runtime mode change
        // affects packets received via existing connections, not just
        // future ones.
        inst.serverIface?.spawnedInterfaces?.forEach { child ->
            child.modeOverride = newMode
        }
        result("mode" to JsonPrimitive(modeStr))
    }

    "wire_request_path" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // `Transport.requestPath` now sends unconditionally, matching Python's
        // `RNS.Transport.request_path` — it issues a fresh request even for
        // destinations we already know / have recently requested, which is
        // exactly what the path-discovery tests need.
        Transport.requestPath(destHash)
        result("sent" to boolVal(true))
    }

    "wire_read_path_entry" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val entry = Transport.pathTable[network.reticulum.common.ByteArrayKey(destHash)]
        if (entry == null) {
            result("found" to boolVal(false))
        } else {
            val ifaceName = Transport.getInterfaces()
                .find { it.hash.contentEquals(entry.receivingInterfaceHash) }
                ?.name
            result(
                "found" to boolVal(true),
                "timestamp" to JsonPrimitive(entry.timestamp),
                "expires" to JsonPrimitive(entry.expires),
                "hops" to intVal(entry.hops),
                "next_hop" to hexVal(entry.nextHop),
                "receiving_interface_name" to (ifaceName?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
            )
        }
    }

    "wire_has_discovery_path_request" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        result("found" to boolVal(Transport.hasDiscoveryPathRequest(destHash)))
    }

    "wire_has_announce_table_entry" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Membership test on announce_table: a cached-announce re-emission
        // scheduled in response to a path request lives here for the
        // PATH_REQUEST_GRACE window before being retransmitted and cleaned
        // up. Absence immediately after a path request is the observable
        // for "B refused to answer this PR" (e.g., ROAMING loop
        // prevention, Transport.py:2731).
        result(
            "found" to boolVal(
                Transport.announceTable.containsKey(
                    network.reticulum.common.ByteArrayKey(destHash),
                ),
            ),
        )
    }

    "wire_read_announce_table_timestamp" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // Return the `timestamp` field of the announce_table entry, or
        // null if absent. A change in timestamp after a path request
        // indicates the PR caused B to insert/replace the entry (the
        // normal answer path); an unchanged timestamp means the PR's
        // answer path was skipped (e.g. ROAMING loop-prevention).
        val entry = Transport.announceTable[network.reticulum.common.ByteArrayKey(destHash)]
        if (entry == null) {
            result("found" to boolVal(false))
        } else {
            result(
                "found" to boolVal(true),
                "timestamp" to JsonPrimitive(entry.timestamp),
            )
        }
    }

    "wire_tx_bytes" -> {
        val handle = p.str("handle")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        // TX bytes for the bridge's configured interface. For a server,
        // this already aggregates all spawned clients because
        // TCPServerClientInterface.processOutgoing propagates each send
        // up to its parent via `parentInterface?.txBytes?.addAndGet(...)`.
        // Summing spawned children on top would double-count. Used as a
        // model-agnostic "did this peer emit any wire traffic" signal for
        // tests where introspecting internal state (announce_table,
        // discovery_path_requests) is sensitive to impl-specific
        // held/restore timing.
        //
        // For local-client peers (wire_start_local_client), the only
        // interface is the sharedClient — fall back to it so tests that
        // observe "did this peer send anything?" work cross-role.
        val total = (inst.serverIface ?: inst.clientIface ?: inst.sharedClient)
            ?.txBytes?.get() ?: 0L
        result("tx_bytes" to JsonPrimitive(total))
    }

    "wire_read_path_random_hash" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val entry = Transport.pathTable[network.reticulum.common.ByteArrayKey(destHash)]
        if (entry == null) {
            result("found" to boolVal(false))
        } else {
            // announcePacketHash is the KEY into the announce cache.
            // getCachedAnnouncePacket returns the raw on-wire bytes.
            val cached = Transport.getCachedAnnouncePacket(entry.announcePacketHash)
            if (cached == null) {
                result("found" to boolVal(false))
            } else {
                val unpacked = Packet.unpack(cached.first)
                    ?: throw IllegalStateException(
                        "Could not unpack cached announce for ${destHash.toHex()}",
                    )
                // Announce data layout (Python RNS + reticulum-kt):
                //   public_key[0:64] + name_hash[64:74] + random_hash[74:84] + ...
                // KEYSIZE=256*2 bits=64 bytes; NAME_HASH_LENGTH=10 bytes;
                // random_hash=10 bytes. Slice defensively so a malformed
                // payload surfaces a clear error rather than ArrayIndexOOB.
                val data = unpacked.data
                if (data.size < 84) {
                    throw IllegalStateException(
                        "Cached announce data too short (${data.size} < 84) for ${destHash.toHex()}",
                    )
                }
                val randomHash = data.copyOfRange(74, 84)
                result("found" to boolVal(true), "random_hash" to hexVal(randomHash))
            }
        }
    }

    "wire_set_race_inducer" -> {
        // Sets named race-inducer hooks in production code (default 0L =
        // no-op). Tests call this to deterministically widen narrow race
        // windows so a regression at a specific seam fails reliably.
        //
        // Currently-instrumented seams (must match Link.kt companion):
        //   "post-prove" — receiver-side validateRequest, sleeps after
        //                  link.prove() returns.
        val seam = p.str("seam")
        val delayMs = p.get("delay_ms")?.asLong ?: 0L
        when (seam) {
            "post-prove" -> network.reticulum.link.Link.raceInducerPostProveDelayMs = delayMs
            else -> throw IllegalArgumentException("Unknown race-inducer seam: $seam")
        }
        result("seam" to JsonPrimitive(seam), "delay_ms" to JsonPrimitive(delayMs))
    }

    "wire_get_received_packets" -> {
        val sinceSeq = p.get("since_seq")?.asLong ?: 0L
        val packets = JsonArray()
        val highestSeq: Long
        synchronized(inboundTapLock) {
            highestSeq = inboundTapSeq
            for (entry in inboundTapBuffer) {
                if (entry.get("seq").asLong > sinceSeq) packets.add(entry)
            }
        }
        result(
            "packets" to packets,
            "highest_seq" to JsonPrimitive(highestSeq),
        )
    }

    "wire_stop" -> {
        val handle = p.str("handle")
        val inst = wireInstances.remove(handle)
        if (inst != null) {
            try { inst.serverIface?.detach() } catch (_: Throwable) {}
            try { inst.clientIface?.detach() } catch (_: Throwable) {}
            try { inst.sharedClient?.detach() } catch (_: Throwable) {}
            try { inst.sharedServer?.detach() } catch (_: Throwable) {}
            // Phase 5h: tear down the pipe interface + its bash subprocess.
            try { inst.pipeIface?.detach() } catch (_: Throwable) {}
            try { inst.pipeProcess?.destroy() } catch (_: Throwable) {}
            try { Reticulum.stop() } catch (_: Throwable) {}
            try { inst.configDir.deleteRecursively() } catch (_: Throwable) {}
            result("stopped" to boolVal(true))
        } else {
            result("stopped" to boolVal(false))
        }
    }

    // ===== Phase 5a: crypto / identity / group (LIVE, mostly delegating) =====

    "wire_group_create" -> {
        // Create a real GROUP Destination and either generate or load its
        // symmetric key (Destination.createKeys / loadPrivateKey). GROUP
        // crypto is symmetric and identity-independent; the returned key is
        // shared out-of-band. Mirrors cmd_wire_group_create (wire_tcp.py:5034).
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val keyHex = p.get("key")?.asString?.takeIf { it.isNotEmpty() }

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val destination = Destination.create(
            identity = null,
            direction = DestinationDirection.IN,
            type = DestinationType.GROUP,
            appName = appName,
            aspects = aspects,
        )
        if (keyHex != null) {
            destination.loadPrivateKey(keyHex.fromHex())
        } else {
            destination.createKeys()
        }
        inst.groupDests[destination.hash.toHex()] = destination
        // Strong ref so it isn't GC'd (identity slot is null for GROUP).
        inst.destinations.add(Identity.create() to destination)

        result(
            "destination_hash" to hexVal(destination.hash),
            "key" to hexVal(destination.getPrivateKey()),
        )
    }

    "wire_group_encrypt" -> {
        val handle = p.str("handle")
        val destHashHex = p.hex("destination_hash").toHex()
        val plaintext = p.get("plaintext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = inst.groupDests[destHashHex]
            ?: throw IllegalArgumentException("No GROUP destination $destHashHex on handle $handle")
        result("ciphertext" to hexVal(destination.encrypt(plaintext)))
    }

    "wire_group_decrypt" -> {
        val handle = p.str("handle")
        val destHashHex = p.hex("destination_hash").toHex()
        val ciphertext = p.get("ciphertext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = inst.groupDests[destHashHex]
            ?: throw IllegalArgumentException("No GROUP destination $destHashHex on handle $handle")
        val plaintext = destination.decrypt(ciphertext)
        result(
            "plaintext" to (plaintext?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "decrypted" to boolVal(plaintext != null),
        )
    }

    "wire_identity_keypair" -> {
        // Pure RNS crypto — no started wire instance required.
        val identity = Identity.create()
        result(
            "private_key" to hexVal(identity.getPrivateKey()),
            "public_key" to hexVal(identity.getPublicKey()),
            "hash" to hexVal(identity.hash),
        )
    }

    "wire_ratchet_keypair" -> {
        // Fresh X25519 ratchet keypair (Cryptography.X25519). public ->
        // wire_identity_encrypt(ratchet_pub=...); private -> wire_identity_decrypt(ratchets=[...]).
        val crypto = network.reticulum.crypto.defaultCryptoProvider()
        val kp = crypto.generateX25519KeyPair()
        result(
            "private_key" to hexVal(kp.privateKey),
            "public_key" to hexVal(kp.publicKey),
        )
    }

    "wire_identity_encrypt" -> {
        // Encrypt for an identity's public key (real Identity.encrypt). When
        // ratchet_pub is supplied, encrypt to that ratchet public key.
        val publicKey = p.hex("public_key")
        val plaintext = p.get("plaintext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val ratchetPub = p.get("ratchet_pub")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
        val identity = Identity.fromPublicKey(publicKey)
        result("ciphertext" to hexVal(identity.encrypt(plaintext, ratchetPub)))
    }

    "wire_identity_decrypt" -> {
        // Decrypt for an identity's private key with ratchet-enforcement
        // support (real Identity.decrypt). With enforce_ratchets=true, a
        // ciphertext that no supplied ratchet can decrypt is REJECTED even
        // though the base key could — the forward-secrecy guarantee.
        val privateKey = p.hex("private_key")
        val ciphertext = p.hex("ciphertext")
        val ratchetsJson = p.get("ratchets")?.asJsonArray
        val ratchets: List<ByteArray>? = ratchetsJson?.map { it.asString.fromHex() }?.takeIf { it.isNotEmpty() }
        val enforce = p.get("enforce_ratchets")?.asBoolean ?: false
        val identity = Identity.fromBytes(privateKey)
            ?: throw IllegalArgumentException("Identity.fromBytes rejected the private key")
        val plaintext = identity.decrypt(ciphertext, ratchets, enforce)
        result(
            "plaintext" to (plaintext?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "decrypted" to boolVal(plaintext != null),
        )
    }

    "wire_identity_recall" -> {
        // Recall an Identity by destination hash (or identity hash) from this
        // instance's received-announces table (real Identity.recall /
        // recallByIdentityHash). Optionally polls until the announce has been
        // received. Mirrors cmd_wire_identity_recall (wire_tcp.py:1054).
        val handle = p.str("handle")
        val targetHash = p.hex("destination_hash")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 0
        val fromIdentityHash = p.get("from_identity_hash")?.asBoolean ?: false

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val deadline = System.currentTimeMillis() + timeoutMs
        var identity: Identity?
        while (true) {
            identity = if (fromIdentityHash) {
                Identity.recallByIdentityHash(targetHash)
            } else {
                Identity.recall(targetHash)
            }
            if (identity != null || System.currentTimeMillis() >= deadline) break
            Thread.sleep(50)
        }

        if (identity == null) {
            result("found" to boolVal(false), "app_data" to JsonNull.INSTANCE)
        } else {
            // app_data is the last-heard app_data for the destination
            // (recallAppData). For the identity-hash path the destination hash
            // is resolved via the reverse index inside recallByIdentityHash;
            // recallAppData keys on the destination hash, so for that path we
            // surface it only when the queried hash is itself the dest hash.
            val appData = if (fromIdentityHash) null else Identity.recallAppData(targetHash)
            result(
                "found" to boolVal(true),
                "public_key" to hexVal(identity.getPublicKey()),
                "hash" to hexVal(identity.hash),
                "app_data" to (appData?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            )
        }
    }

    "wire_plain_encrypt" -> {
        // PLAIN-destination encrypt: a no-op passthrough (Destination.encrypt
        // returns plaintext unchanged for PLAIN). Returns {ciphertext, passthrough}.
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val plaintext = p.get("plaintext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val dest = Destination.create(null, DestinationDirection.OUT, DestinationType.PLAIN, appName, *aspects)
        inst.destinations.add(Identity.create() to dest)
        val ciphertext = dest.encrypt(plaintext)
        result(
            "ciphertext" to hexVal(ciphertext),
            "passthrough" to boolVal(ciphertext.contentEquals(plaintext)),
        )
    }

    "wire_plain_decrypt" -> {
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val ciphertext = p.get("ciphertext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val dest = Destination.create(null, DestinationDirection.OUT, DestinationType.PLAIN, appName, *aspects)
        inst.destinations.add(Identity.create() to dest)
        val plaintext = dest.decrypt(ciphertext) ?: ciphertext
        result(
            "plaintext" to hexVal(plaintext),
            "passthrough" to boolVal(plaintext.contentEquals(ciphertext)),
        )
    }

    "wire_ifac_compute" -> {
        // Compute the IFAC access code RNS.Transport.transmit would prepend,
        // using the live interface's RNS-derived ifac_identity / ifac_key.
        // Ed25519 sign is deterministic, so this reproduces the on-wire tag
        // RNS itself produces. Mirrors cmd_wire_ifac_compute (wire_tcp.py:5192).
        val handle = p.str("handle")
        val packetData = p.hex("packet_data")
        val ifacSizeOverride = p.get("ifac_size")?.takeIf { !it.isJsonNull }?.asInt

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val iface = ifacInterfaceOrThrow(inst)
        val size = ifacSizeOverride ?: iface.ifacSize
        val signature = iface.ifacIdentity!!.sign(packetData)
        result(
            "ifac_key" to hexVal(iface.ifacKey!!),
            "ifac_size" to intVal(size),
            "signature" to hexVal(signature),
            "ifac" to hexVal(signature.copyOfRange(signature.size - size, signature.size)),
        )
    }

    "wire_ifac_signature" -> {
        // The live interface's IFAC identifier signature: the Ed25519 signature
        // over full_hash(ifac_key) RNS produced at Reticulum.py:916. Ed25519 is
        // deterministic, so signing full_hash(ifac_key) with the live
        // ifac_identity reproduces it byte-for-byte. default_ifac_size is the
        // per-type class default (TCP{Server,Client}Interface.DEFAULT_IFAC_SIZE == 16).
        val handle = p.str("handle")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val iface = ifacInterfaceOrThrow(inst)
        val signature = iface.ifacIdentity!!.sign(network.reticulum.crypto.Hashes.fullHash(iface.ifacKey!!))
        val defaultIfacSize = when (iface) {
            is TCPServerInterface -> TCPServerInterface.DEFAULT_IFAC_SIZE
            is TCPClientInterface -> TCPClientInterface.DEFAULT_IFAC_SIZE
            else -> 16
        }
        result(
            "ifac_signature" to hexVal(signature),
            "ifac_key" to hexVal(iface.ifacKey!!),
            "ifac_size" to intVal(iface.ifacSize),
            "default_ifac_size" to intVal(defaultIfacSize),
        )
    }

    "wire_known_key_validate" -> {
        // Validate a genuinely-signed announce against a planted known public
        // key for the same destination hash (Identity.validateAnnounce
        // known-key guard). The same announce flips accept/reject solely on the
        // stored key. Mirrors cmd_wire_known_key_validate (wire_tcp.py:6068).
        val handle = p.str("handle")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val plant = (p.get("plant")?.asString ?: "mismatch").lowercase()
        if (plant !in setOf("mismatch", "match", "none")) {
            throw IllegalArgumentException("plant must be 'mismatch', 'match' or 'none' (got $plant)")
        }
        val appData = p.get("app_data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.create()
        val destination = Destination.create(
            identity, DestinationDirection.IN, DestinationType.SINGLE, appName, *aspects,
        )
        // Don't let the auto-registered local destination satisfy the recall
        // fallback — validateAnnounce keys on knownDestinations, not Transport,
        // so this is belt-and-suspenders to keep the planted-key check honest.
        runCatching { Transport.deregisterDestination(destination) }
        val realPub = identity.getPublicKey()
        val destHash = destination.hash

        // Build a genuine signed announce and re-parse it as a received packet.
        val announcePacket = destination.announce(appData = appData, send = false)
            ?: throw IllegalStateException("announce(send=false) returned no packet")
        val raw = announcePacket.pack()
        val rx = Packet.unpack(raw)
            ?: throw IllegalStateException("could not unpack crafted announce packet")

        val planted: ByteArray? = when (plant) {
            "match" -> realPub
            "mismatch" -> Identity.create().getPublicKey() // a different, valid key
            else -> null
        }
        if (planted != null) {
            Identity.remember(rx.packetHash, destHash, planted, appData)
        }

        val validated = Identity.validateAnnounce(rx) != null
        // Keep refs alive until validation finished.
        inst.destinations.add(identity to destination)
        result(
            "validated" to boolVal(validated),
            "destination_hash" to hexVal(destHash),
            "public_key" to hexVal(realPub),
            "planted_public_key" to (planted?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "plant" to strVal(plant),
        )
    }

    "wire_encrypt_to_remote" -> {
        // Encrypt to a REMOTE destination, auto-selecting the ratchet this peer
        // ADOPTED from that destination's announce — the same target-key choice
        // Destination.encrypt makes — via Identity.recall + getRatchet +
        // Identity.encrypt(ratchet=...). Mirrors cmd_wire_encrypt_to_remote (5737).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val plaintext = p.get("plaintext")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val useRatchet = p.get("use_ratchet")?.asBoolean ?: true

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; ensure an announce for " +
                    "this destination was received first.",
            )
        val ratchetPublic = if (useRatchet) Identity.getRatchet(destHash) else null
        val ciphertext = identity.encrypt(plaintext, ratchetPublic)
        val ratchetId = ratchetPublic?.let { Identity.ratchetIdFor(it) }
        result(
            "ciphertext" to hexVal(ciphertext),
            "used_ratchet" to boolVal(ratchetPublic != null),
            "ratchet_id" to (ratchetId?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "ratchet_public" to (ratchetPublic?.let { hexVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_destination_decrypt" -> {
        // Decrypt a ciphertext on a local SINGLE destination, exposing WHICH
        // ratchet (if any) decrypted it (real Destination.decrypt sets
        // latestRatchetId via the ratchet_id_receiver contract). Mirrors
        // cmd_wire_destination_decrypt (wire_tcp.py:5777).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val ciphertext = p.hex("ciphertext")

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = findDestinationByHash(inst, destHash)
            ?: throw IllegalArgumentException(
                "No registered destination with hash ${destHash.toHex()} on handle $handle.",
            )
        // Destination.decrypt re-sets latestRatchetId on every successful
        // decrypt (to the winning ratchet's id, or null when the static key
        // was used), so read it only on success to avoid surfacing a stale id
        // from a prior call when decryption fails.
        val plaintext = destination.decrypt(ciphertext)
        val latest = if (plaintext != null) destination.latestRatchetId else null
        result(
            "decrypted" to boolVal(plaintext != null),
            "plaintext" to (plaintext?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "latest_ratchet_id" to (latest?.let { hexVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_known_destinations_roundtrip" -> {
        // Save -> clear -> reload the on-disk known_destinations table and
        // confirm a previously-known destination round-trips (real
        // Identity.saveKnownDestinations / loadKnownDestinations). The bridge
        // does NOT build or parse the on-disk msgpack — the library does.
        // Mirrors cmd_wire_known_destinations_roundtrip (wire_tcp.py:5591).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")

        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val presentBefore = Identity.isKnown(destHash)
        Identity.saveKnownDestinations()
        Identity.clearKnownDestinations()
        val afterClear = Identity.recall(destHash)
        Identity.loadKnownDestinations()
        val reloaded = Identity.recall(destHash)
        val appDataAfter = Identity.recallAppData(destHash)
        result(
            "present_before_save" to boolVal(presentBefore),
            "recall_after_clear_found" to boolVal(afterClear != null),
            "recall_after_load_found" to boolVal(reloaded != null),
            "app_data_after_load" to (appDataAfter?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            // kotlin's known_destinations record is the 4-field IdentityData
            // (timestamp, packet_hash, public_key, app_data). RNS 1.3.1 stores a
            // 5-element entry with a trailing `used` LRU marker
            // (Identity.py:107/:252-254). kotlin does not track that marker —
            // a genuine divergence flagged for the Phase 6 triage; report the
            // honest kotlin field count rather than faking a 5th element.
            "entry_len_after_load" to intVal(if (reloaded != null) 4 else 0),
            "table_size_after_load" to intVal(Identity.knownDestinationCount()),
        )
    }

    // ===== Phase 5b: destination-level ratchets (LIVE) =====

    "wire_read_ratchets" -> {
        // Read the ratchet state of a ratchet-enabled destination. ratchet_interval
        // and latest_ratchet_time are SECONDS on the wire (python attrs); kotlin
        // stores them as MILLIS, converted here. Mirrors cmd_wire_read_ratchets.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)
        val ratchets = destination.ratchetsSnapshotForTest()
        result(
            "ratchet_count" to intVal(ratchets.size),
            "current_ratchet_id" to currentRatchetIdVal(ratchets),
            "previous_ratchet_id" to previousRatchetIdVal(ratchets),
            "ratchet_interval" to intVal((destination.ratchetInterval / 1000L).toInt()),
            "retained_ratchets" to intVal(destination.retainedRatchetsForTest()),
            "latest_ratchet_id" to (destination.latestRatchetId?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "latest_ratchet_time" to doubleVal(destination.lastRatchetRotationForTest / 1000.0),
        )
    }

    "wire_set_ratchet_interval" -> {
        // Set the minimum ratchet-rotation interval (python SECONDS -> kotlin
        // MILLIS). ok is false for a non-positive value (rejected, unchanged).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val seconds = p.get("seconds").asLong
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)
        // A non-positive seconds value stays non-positive in millis, so kotlin's
        // setRatchetInterval rejects it exactly as python does.
        val ok = destination.setRatchetInterval(seconds * 1000L)
        result(
            "ok" to boolVal(ok),
            "ratchet_interval" to intVal((destination.ratchetInterval / 1000L).toInt()),
        )
    }

    "wire_rotate_ratchet" -> {
        // Trigger a rotation and observe the interval gate without a real wait.
        // last_rotation_ago_s backdates latest_ratchet_time (kotlin: lastRatchetRotation,
        // millis) so the gate opens (ago > interval) or stays shut (ago < interval).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val agoS = p.get("last_rotation_ago_s")?.takeIf { !it.isJsonNull }?.asDouble
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)

        if (agoS != null) {
            destination.lastRatchetRotationForTest = System.currentTimeMillis() - (agoS * 1000).toLong()
        }
        val before = destination.ratchetsSnapshotForTest()
        val beforeCurrent = currentRatchetIdVal(before)
        destination.rotateRatchets()
        val after = destination.ratchetsSnapshotForTest()
        result(
            "rotated" to boolVal(after.size > before.size),
            "before_count" to intVal(before.size),
            "after_count" to intVal(after.size),
            "before_current_id" to beforeCurrent,
            "current_ratchet_id" to currentRatchetIdVal(after),
            "previous_ratchet_id" to previousRatchetIdVal(after),
            "ratchet_interval" to intVal((destination.ratchetInterval / 1000L).toInt()),
            "latest_ratchet_time" to doubleVal(destination.lastRatchetRotationForTest / 1000.0),
        )
    }

    "wire_set_retained_ratchets" -> {
        // Set the retained-ratchets cap and observe truncation. pad_to inflates
        // the list with N real fresh ratchets first so the cap is observable.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val n = p.get("n").asInt
        val padTo = p.get("pad_to")?.takeIf { !it.isJsonNull }?.asInt
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)

        if (padTo != null) {
            val crypto = network.reticulum.crypto.defaultCryptoProvider()
            while (destination.ratchetsSnapshotForTest().size < padTo) {
                destination.addRatchetForTest(crypto.generateX25519KeyPair().privateKey)
            }
        }
        val ok = destination.setRetainedRatchets(n)
        result(
            "ok" to boolVal(ok),
            "retained_ratchets" to intVal(destination.retainedRatchetsForTest()),
            "ratchet_count" to intVal(destination.ratchetsSnapshotForTest().size),
            "ratchet_count_cap" to intVal(Destination.RATCHET_COUNT),
        )
    }

    "wire_ratchet_file_roundtrip" -> {
        // Persist + reload a destination's signed ratchet store and confirm it
        // round-trips. The bridge does NOT parse the on-disk format — reloadRatchets
        // validates the embedded signature and only repopulates on success.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)
        if (destination.ratchetsPathForTest() == null) {
            throw IllegalArgumentException(
                "Destination ${destHash.toHex()} has no ratchets_path; enable ratchets " +
                    "with a file path (wire_announce enable_ratchets=true).",
            )
        }
        val before = destination.ratchetsSnapshotForTest()
        val idsBefore = before.map { ratchetIdHex(it) }
        destination.persistRatchetsForTest()
        val reloadOk = try {
            destination.reloadRatchetsFromDiskForTest()
        } catch (e: Exception) {
            false
        }
        val after = destination.ratchetsSnapshotForTest()
        val idsAfter = after.map { ratchetIdHex(it) }
        val idsArr = JsonArray().apply { idsAfter.forEach { add(it) } }
        result(
            "ratchets_path_set" to boolVal(true),
            "reload_ok" to boolVal(reloadOk),
            "ratchet_count_before" to intVal(before.size),
            "ratchet_count_after" to intVal(after.size),
            "roundtrip_match" to boolVal(idsBefore == idsAfter && before.size == after.size),
            "ratchet_ids" to idsArr,
        )
    }

    "wire_identity_ratchet_persist" -> {
        // Persist + reload a RECEIVED ratchet through the Identity-side store
        // (rememberRatchet -> getRatchet) and exercise cleanRatchets housekeeping.
        // The bridge does NOT build/parse the on-disk msgpack — the library does.
        val handle = p.str("handle")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = network.reticulum.crypto.defaultCryptoProvider()
        // A random, never-announced dest hash: absent from known_destinations
        // and from the ratchet cache.
        val destHash = crypto.randomBytes(network.reticulum.common.RnsConstants.TRUNCATED_HASH_BYTES)
        val ratchet = crypto.generateX25519KeyPair().privateKey // 32 genuine ratchet bytes

        val ratchetDir = File(Identity.storagePath, "ratchets")
        val hexHash = destHash.toHex()
        val finalFile = File(ratchetDir, hexHash)
        val outFile = File(ratchetDir, "$hexHash.out")

        Identity.rememberRatchet(destHash, ratchet)
        // rememberRatchet persists synchronously; poll briefly for safety.
        val deadline = System.currentTimeMillis() + 5000
        while (!finalFile.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val fileWritten = finalFile.isFile
        val tmpLeftover = outFile.exists()

        // Force the on-disk LOAD path: drop the in-memory cache entry, read back.
        Identity.dropRatchetCacheForTest(destHash)
        val reloaded = Identity.getRatchet(destHash)
        val reloadMatch = reloaded != null && reloaded.contentEquals(ratchet)
        val acceptedSize = network.reticulum.common.RnsConstants.KEY_SIZE

        // Housekeeping: dest is not in known_destinations -> python _clean_ratchets
        // removes its file (not-in-use branch). kotlin cleanRatchets only removes
        // EXPIRED ratchets (a documented divergence), so cleaned_removed reflects
        // the genuine kotlin behavior.
        Identity.cleanRatchets()
        val cleanedRemoved = !finalFile.isFile
        Identity.dropRatchetCacheForTest(destHash)

        result(
            "dest_hash" to hexVal(destHash),
            "ratchet_len" to intVal(ratchet.size),
            "file_written" to boolVal(fileWritten),
            "tmp_leftover" to boolVal(tmpLeftover),
            "reload_match" to boolVal(reloadMatch),
            "reloaded_len" to (reloaded?.let { intVal(it.size) } ?: JsonNull.INSTANCE),
            "accepted_size" to intVal(acceptedSize),
            "cleaned_removed" to boolVal(cleanedRemoved),
        )
    }

    "wire_destination_latest_ratchet_id" -> {
        // Drive a real Destination.encrypt + decrypt round-trip and expose
        // latest_ratchet_id (encrypt sets it to the current ratchet's id;
        // decrypt re-derives it). Mirrors cmd_wire_destination_latest_ratchet_id.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val probe = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: "ratchet-probe".toByteArray()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = ratchetDestOrThrow(inst, destHash, handle)

        val ciphertext = destination.encrypt(probe)
        val encId = destination.latestRatchetId
        val plaintext = destination.decrypt(ciphertext)
        val decId = destination.latestRatchetId
        result(
            "decrypted" to boolVal(plaintext != null && plaintext.contentEquals(probe)),
            "plaintext" to (plaintext?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "latest_ratchet_id" to (decId?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "encrypt_ratchet_id" to (encId?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "current_ratchet_id" to currentRatchetIdVal(destination.ratchetsSnapshotForTest()),
            "match" to boolVal(encId != null && decId != null && encId.contentEquals(decId)),
            "ratchet_count" to intVal(destination.ratchetsSnapshotForTest().size),
        )
    }

    "wire_get_adopted_ratchet" -> {
        // Report the ratchet this peer ADOPTED for a REMOTE destination after
        // hearing its ratcheted announce (Identity.getRatchet / ratchetIdFor).
        val destHash = p.hex("destination_hash")
        val ratchetPublic = Identity.getRatchet(destHash)
        if (ratchetPublic == null) {
            result(
                "found" to boolVal(false),
                "ratchet_public" to JsonNull.INSTANCE,
                "ratchet_id" to JsonNull.INSTANCE,
            )
        } else {
            result(
                "found" to boolVal(true),
                "ratchet_public" to hexVal(ratchetPublic),
                "ratchet_id" to hexVal(Identity.ratchetIdFor(ratchetPublic)),
            )
        }
    }

    // ===== Phase 5c: link lifecycle — request handlers + link request =====

    "wire_reannounce" -> {
        // Re-announce an already-registered SINGLE IN destination. For a
        // ratchet-enabled dest, announce() rotates (gated); rotate_ago_s
        // backdates latest_ratchet_time so the gate opens for a genuinely NEW
        // ratchet. Mirrors cmd_wire_reannounce.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val appData = p.get("app_data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
        val rotateAgoS = p.get("rotate_ago_s")?.takeIf { !it.isJsonNull }?.asDouble
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = findDestinationByHash(inst, destHash)
            ?: throw IllegalArgumentException(
                "No registered destination with hash ${destHash.toHex()} on handle $handle.",
            )
        if (rotateAgoS != null && destination.ratchetsEnabledForTest()) {
            destination.lastRatchetRotationForTest = System.currentTimeMillis() - (rotateAgoS * 1000).toLong()
        }
        destination.announce(appData = appData)
        result(
            "announced" to boolVal(true),
            "current_ratchet_id" to currentRatchetIdVal(destination.ratchetsSnapshotForTest()),
        )
    }

    "wire_register_request_handler" -> {
        // Register a fixed-response request handler. The generator logs each
        // invocation and returns the configured response (null for response_none).
        // allow: "all"|"list"(+allowed_identity_hashes)|"none". Mirrors
        // cmd_wire_register_request_handler.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val path = p.str("path")
        val responseNone = p.get("response_none")?.asBoolean ?: false
        val response = p.get("response")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        // kotlin's response generator returns ByteArray? only — it cannot return
        // python's (file, metadata) tuple, so the streamed-file response branch
        // is unsupported. Surface that clearly rather than silently mis-answering.
        if (p.get("response_file") != null && !p.get("response_file").isJsonNull) {
            throw IllegalArgumentException(
                "wire_register_request_handler: response_file (streamed file + metadata " +
                    "response) is not supported by the kotlin Destination request API " +
                    "(its generator returns ByteArray only).",
            )
        }
        val allowParam = p.get("allow")?.asString ?: "all"
        val allowedListHex = p.get("allowed_identity_hashes")?.asJsonArray?.map { it.asString } ?: emptyList()

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = findDestinationByHash(inst, destHash)
            ?: throw IllegalArgumentException(
                "No registered destination with hash ${destHash.toHex()} on handle $handle; call wire_listen first.",
            )

        val allow: Int
        val allowedList: List<ByteArray>?
        when (allowParam) {
            "all" -> { allow = network.reticulum.destination.RequestPolicy.ALLOW_ALL; allowedList = null }
            "list" -> { allow = network.reticulum.destination.RequestPolicy.ALLOW_LIST; allowedList = allowedListHex.map { it.fromHex() } }
            "none" -> { allow = network.reticulum.destination.RequestPolicy.ALLOW_NONE; allowedList = null }
            else -> throw IllegalArgumentException("unsupported allow: $allowParam (use 'all', 'list' or 'none')")
        }

        val logKey = "$handle|${destHash.toHex()}|$path"
        wireRequestHandlerLog.getOrPut(logKey) { java.util.Collections.synchronizedList(mutableListOf()) }
        destination.registerRequestHandler(
            path,
            responseGenerator = { _, data, requestId, linkId, remoteIdentity, requestedAt ->
                val entry = JsonObject().apply {
                    addProperty("data", (data ?: ByteArray(0)).toHex())
                    addProperty("request_id", requestId.toHex())
                    addProperty("link_id", linkId.toHex())
                    if (remoteIdentity != null) addProperty("remote_identity_hash", remoteIdentity.hash.toHex())
                    else add("remote_identity_hash", JsonNull.INSTANCE)
                    // python requested_at is epoch seconds; kotlin passes millis.
                    addProperty("requested_at", requestedAt / 1000.0)
                }
                wireRequestHandlerLog[logKey]?.add(entry)
                if (responseNone) null else response
            },
            allow = allow,
            allowedList = allowedList,
        )
        result("registered" to boolVal(true))
    }

    "wire_deregister_request_handler" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val path = p.str("path")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = findDestinationByHash(inst, destHash)
            ?: throw IllegalArgumentException(
                "No registered destination with hash ${destHash.toHex()} on handle $handle.",
            )
        val removed = destination.deregisterRequestHandler(path)
        result("deregistered" to boolVal(removed))
    }

    "wire_get_request_log" -> {
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val path = p.str("path")
        val logKey = "$handle|${destHash.toHex()}|$path"
        val entries = wireRequestHandlerLog[logKey] ?: mutableListOf()
        val arr = JsonArray()
        synchronized(entries) { entries.forEach { arr.add(it) } }
        result("count" to intVal(arr.size()), "entries" to arr)
    }

    "wire_link_identify" -> {
        // Identify the link initiator to the remote peer (Link.identify).
        // Required for ALLOW_LIST handlers — remote_identity is null unless the
        // requester identifies first.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val privateKey = p.hex("private_key")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val identity = Identity.fromBytes(privateKey)
            ?: throw IllegalArgumentException("Identity.fromBytes rejected the private key")
        link.identify(identity)
        result("identified" to boolVal(true), "identity_hash" to hexVal(identity.hash))
    }

    "wire_link_request", "wire_link_request_large" -> {
        // Issue a request over an established outbound Link, wait for the
        // response. Polls RequestReceipt.status until READY/FAILED/timeout.
        // The _large variant just uses a larger default timeout for >MDU
        // resource-backed responses. Mirrors cmd_wire_link_request.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val path = p.str("path")
        val data = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
        val defaultTimeout = if (command == "wire_link_request_large") 30000 else 10000
        val timeoutMs = p.get("timeout_ms")?.asInt ?: defaultTimeout

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val receipt = link.request(path, data = data, timeout = timeoutMs.toLong())
            ?: throw IllegalStateException("Link.request returned null (link not active / REQUEST not sent)")
        // +500ms slack so the receipt's own internal timeout fires first.
        val deadline = System.currentTimeMillis() + timeoutMs + 500
        var out: JsonObject? = null
        while (System.currentTimeMillis() < deadline) {
            when (receipt.status) {
                RequestReceipt.READY -> {
                    val resp = receipt.getResponseCopy()
                    val meta = receipt.metadata
                    out = result(
                        "status" to strVal("ready"),
                        "response" to (resp?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                        "response_metadata" to (meta?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                        "response_time_s" to (receipt.getResponseTime()?.let { doubleVal(it / 1000.0) } ?: JsonNull.INSTANCE),
                    )
                }
                RequestReceipt.FAILED -> {
                    out = result(
                        "status" to strVal("failed"),
                        "response" to JsonNull.INSTANCE,
                        "response_metadata" to JsonNull.INSTANCE,
                    )
                }
            }
            if (out != null) break
            Thread.sleep(50)
        }
        out ?: result(
            "status" to strVal("timeout"),
            "response" to JsonNull.INSTANCE,
            "response_metadata" to JsonNull.INSTANCE,
        )
    }

    "wire_link_request_timeout" -> {
        // Issue Link.request and read back the RequestReceipt's computed timeout
        // WITHOUT waiting. With no explicit timeout, RNS derives rtt*6 + 11.25s.
        // Timestamps cross the boundary in seconds (kotlin stores millis).
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val path = p.str("path")
        val data = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
        val explicitTimeoutMs = p.get("timeout_ms")?.takeIf { !it.isJsonNull }?.asInt

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val receipt = link.request(path, data = data, timeout = explicitTimeoutMs?.toLong())
            ?: throw IllegalStateException("Link.request returned null (REQUEST packet not sent)")
        result(
            "receipt_timeout" to doubleVal(receipt.timeout / 1000.0),
            "rtt" to (link.rtt?.let { doubleVal(it / 1000.0) } ?: JsonNull.INSTANCE),
            "traffic_timeout_factor" to intVal(link.trafficTimeoutFactor),
            "response_max_grace_time" to intVal(ResourceConstants.RESPONSE_MAX_GRACE_TIME),
            "explicit_timeout" to (explicitTimeoutMs?.let { doubleVal(it / 1000.0) } ?: JsonNull.INSTANCE),
        )
    }

    // ===== Phase 5c batch 2: link introspection (LIVE reads) =====

    "wire_link_status" -> {
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        linkStatusDict(link)
    }

    "wire_link_await_status" -> {
        // Block until the link reaches at least target_status (int or name) on
        // the PENDING<HANDSHAKE<ACTIVE<STALE<CLOSED ordering, or timeout.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 15000
        val targetEl = p.get("target_status")
        val targetInt = if (targetEl.asJsonPrimitive.isString) {
            val name = targetEl.asString.uppercase()
            LINK_STATUS_NAMES.entries.firstOrNull { it.value == name }?.key
                ?: throw IllegalArgumentException("Unknown target_status name: $name")
        } else {
            targetEl.asInt
        }
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val deadline = System.currentTimeMillis() + timeoutMs
        var reached = false
        while (System.currentTimeMillis() < deadline) {
            if (link.status >= targetInt) { reached = true; break }
            Thread.sleep(50)
        }
        linkStatusDict(link).apply { addProperty("reached", reached) }
    }

    "wire_link_set_watchdog" -> {
        // Compress keepalive/stale timings so the watchdog path is observable
        // in a test timeout. Seconds on the wire; kotlin stores millis.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val keepaliveS = p.get("keepalive_s")?.takeIf { !it.isJsonNull }?.asDouble
        val staleTimeS = p.get("stale_time_s")?.takeIf { !it.isJsonNull }?.asDouble
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        if (keepaliveS != null) link.keepalive = (keepaliveS * 1000).toLong()
        if (staleTimeS != null) link.staleTime = (staleTimeS * 1000).toLong()
        result(
            "keepalive_s" to doubleVal(link.keepalive / 1000.0),
            "stale_time_s" to doubleVal(link.staleTime / 1000.0),
        )
    }

    "wire_link_teardown" -> {
        // Gracefully tear down an outbound link; the peer observes CLOSED with
        // teardown_reason=INITIATOR_CLOSED (via wire_listener_link_status).
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        link.teardown()
        result("torn_down" to boolVal(true))
    }

    "wire_link_mtu" -> {
        // Read the negotiated MTU/MDU/mode of an established link (initiator OR
        // a listener-accepted inbound link). Reads the raw .mtu/.mdu fields
        // (always populated post-establishment), plus status to gate on ACTIVE.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkById(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        result(
            "mtu" to intVal(link.mtu),
            "mdu" to intVal(link.mdu),
            "mode" to intVal(link.mode),
            "status" to intVal(link.status),
            "status_name" to (LINK_STATUS_NAMES[link.status]?.let { strVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_listener_link_status" -> {
        // Observe the receiver-side (inbound) link a wire_listen destination
        // accepted, by destination_hash. Optionally polls for the inbound link
        // to appear (establishment is async).
        val handle = p.str("handle")
        val destHashHex = p.hex("destination_hash").toHex()
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 0
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val listener = inst.listeners[destHashHex]
            ?: throw IllegalArgumentException("No listener registered for destination_hash=$destHashHex")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (listener.inboundLinks.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        val links = listener.inboundLinks.toList()
        if (links.isEmpty()) {
            result("found" to boolVal(false), "link_count" to intVal(0))
        } else {
            linkStatusDict(links.last()).apply {
                addProperty("found", true)
                addProperty("link_count", links.size)
            }
        }
    }

    // ===== Phase 5c batch 3: link gates / key material / phy stats =====

    "wire_link_type_gate" -> {
        // Pin Link's SINGLE-only construction rule: Link.create raises for
        // PLAIN/GROUP destinations (kotlin IllegalArgumentException; python
        // TypeError). SINGLE is the positive control. Mirrors cmd_wire_link_type_gate.
        val handle = p.str("handle")
        val appName = p.get("app_name")?.asString ?: "conformance"
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: arrayOf("link-type-gate")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        fun attempt(dest: Destination): JsonObject {
            var link: Link? = null
            var raised: String? = null
            try {
                link = Link.create(dest)
            } catch (e: IllegalArgumentException) {
                raised = e.message
            }
            link?.let { runCatching { it.teardown() } }
            return result(
                "raised" to boolVal(raised != null),
                "error" to (raised?.let { strVal(it) } ?: JsonNull.INSTANCE),
                "link_created" to boolVal(link != null),
            )
        }

        val single = Destination.create(Identity.create(), DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val plain = Destination.create(null, DestinationDirection.OUT, DestinationType.PLAIN, appName, *aspects)
        val group = Destination.create(Identity.create(), DestinationDirection.OUT, DestinationType.GROUP, appName, *aspects)
        result(
            "single" to attempt(single),
            "plain" to attempt(plain),
            "group" to attempt(group),
        )
    }

    "wire_link_phy_stats_gate" -> {
        // Pin phy-stats gating: getRssi/getSnr/getQ return stored values only
        // when track_phy_stats is enabled; off -> null regardless. Mirrors
        // cmd_wire_link_phy_stats_gate.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkById(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val rssi = -42; val snr = 7f; val q = 83f
        fun read(): JsonObject = result(
            "rssi" to (link.getRssi()?.let { intVal(it) } ?: JsonNull.INSTANCE),
            "snr" to (link.getSnr()?.let { doubleVal(it.toDouble()) } ?: JsonNull.INSTANCE),
            "q" to (link.getQ()?.let { doubleVal(it.toDouble()) } ?: JsonNull.INSTANCE),
        )
        // Tracking off by default — stored values must gate to null.
        link.setPhyStatsForTest(rssi, snr, q)
        val off = read()
        link.trackPhyStats(true)
        link.setPhyStatsForTest(rssi, snr, q)
        val on = read()
        link.trackPhyStats(false)
        link.setPhyStatsForTest(rssi, snr, q)
        val offAgain = read()
        result(
            "stored" to result("rssi" to intVal(rssi), "snr" to doubleVal(snr.toDouble()), "q" to doubleVal(q.toDouble())),
            "off" to off,
            "on" to on,
            "off_again" to offAgain,
        )
    }

    "wire_link_key_material" -> {
        // Report which ephemeral-key fields the link holds. An ACTIVE link holds
        // prv/pub/shared/derived; after teardown the purge nulls them all.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkById(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val presence = link.keyMaterialPresenceForTest() // [prv, pub, shared, derived]
        result(
            "status" to intVal(link.status),
            "status_name" to (LINK_STATUS_NAMES[link.status]?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "derived_key_present" to boolVal(presence[3]),
            "shared_key_present" to boolVal(presence[2]),
            "prv_present" to boolVal(presence[0]),
            "pub_present" to boolVal(presence[1]),
        )
    }

    "wire_link_identify_pending" -> {
        // Call identify() on a PENDING (pre-ACTIVE) link and assert it is a
        // no-op: identify only acts when initiator && status==ACTIVE, so on
        // PENDING it returns false, emits no LINKIDENTIFY, doesn't crash.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val appName = p.str("app_name")
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val privateKey = p.hex("private_key")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; ensure an announce was received first.",
            )
        val outDest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val ident = Identity.fromBytes(privateKey)
            ?: throw IllegalArgumentException("Identity.fromBytes rejected the private key")

        val link = Link.create(outDest)
        // Force PENDING so identify's ACTIVE-only guard is deterministically hit.
        link.setStatusForTest(LinkConstants.PENDING)
        var crashed = false
        var sent = false
        try {
            // identify() returns true only if it actually emitted a LINKIDENTIFY
            // (Transport.outbound succeeded); on PENDING it returns false early.
            sent = link.identify(ident)
        } catch (e: Exception) {
            crashed = true
        }
        val statusAfter = link.status
        runCatching { link.teardown() }
        result(
            "crashed" to boolVal(crashed),
            "identify_packet_sent" to boolVal(sent),
            "status" to intVal(statusAfter),
            "status_name" to (LINK_STATUS_NAMES[statusAfter]?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "initiator" to boolVal(link.initiator),
        )
    }

    // ===== Phase 5c batch 4: link adversarial / payload validation =====

    "wire_link_signalling_bytes" -> {
        // Delegate to the static Link.signallingBytes(mtu, mode): the 3-byte
        // field for an ENABLED mode, or raised=true for a non-enabled mode.
        val mtu = p.int("mtu")
        val mode = p.int("mode")
        var raised = false
        var signalling: ByteArray? = null
        try {
            signalling = Link.signallingBytes(mtu, mode)
        } catch (e: IllegalArgumentException) {
            raised = true
        }
        val enabledModes = JsonArray().apply { LinkConstants.ENABLED_MODES.forEach { add(it) } }
        result(
            "mtu" to intVal(mtu),
            "mode" to intVal(mode),
            "signalling_bytes" to (signalling?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "raised" to boolVal(raised),
            "mtu_bytemask" to intVal(LinkConstants.MTU_BYTEMASK),
            "mode_bytemask" to intVal(LinkConstants.MODE_BYTEMASK),
            "enabled_modes" to enabledModes,
            "mode_default" to intVal(LinkConstants.MODE_DEFAULT),
            "link_mtu_size" to intVal(LinkConstants.LINK_MTU_SIZE),
        )
    }

    "wire_link_request_payload" -> {
        // Capture a genuine initiator LINKREQUEST payload WITHOUT sending it:
        // pub(32) || sigPub(32) || signalling(3) = ECPUBSIZE(64) + LINK_MTU_SIZE(3).
        val handle = p.str("handle")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val rd = Link.buildInitiatorRequestDataForTest()
        val ecpubsize = LinkConstants.ECPUBSIZE
        result(
            "request_data_hex" to hexVal(rd.requestData),
            "pub_bytes" to hexVal(rd.pubBytes),
            "sig_pub_bytes" to hexVal(rd.sigPubBytes),
            "signalling_bytes" to hexVal(rd.requestData.copyOfRange(ecpubsize, rd.requestData.size)),
            "mtu" to intVal(rd.mtu),
            "mode" to intVal(rd.mode),
            "len" to intVal(rd.requestData.size),
            "ecpubsize" to intVal(ecpubsize),
            "link_mtu_size" to intVal(LinkConstants.LINK_MTU_SIZE),
            "reticulum_mtu" to intVal(network.reticulum.common.RnsConstants.MTU),
        )
    }

    "wire_inject_crafted_link_request" -> {
        // Adversarial LINKREQUEST payload-validation injector: feed a crafted
        // LINKREQUEST of `variant` through the real Link.validateRequest and
        // report whether an inbound link was created. Only 64/67-byte payloads
        // yield a link; bad_mode's reserved signalling mode is rejected.
        val handle = p.str("handle")
        val variant = p.str("variant")
        val hops = p.get("hops")?.asInt ?: 0
        val appName = p.get("app_name")?.asString ?: "conformance"
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: arrayOf("lr-validate")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val ownerIdentity = Identity.create()
        val owner = Destination.create(ownerIdentity, DestinationDirection.IN, DestinationType.SINGLE, appName, *aspects)
        val base = Link.buildInitiatorRequestDataForTest().requestData
        val ecpubsize = LinkConstants.ECPUBSIZE
        val data: ByteArray = when (variant) {
            "valid64" -> base.copyOfRange(0, ecpubsize)
            "valid67" -> base
            "size_63" -> base.copyOfRange(0, 63)
            "size_66" -> base.copyOfRange(0, 66)
            "size_0" -> ByteArray(0)
            "bad_mode" -> base.copyOf().also { it[ecpubsize] = 0x60.toByte() } // reserved mode 3
            else -> throw IllegalArgumentException("unknown link-request variant: $variant")
        }

        // Craft a genuine LINKREQUEST packet carrying `data`, addressed to the
        // owner's hash, then re-parse it as a received packet.
        val outOwner = Destination.create(ownerIdentity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val pkt = Packet.createRaw(
            destinationHash = outOwner.hash,
            data = data,
            packetType = network.reticulum.common.PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE,
        )
        val raw = pkt.pack()
        val rx = Packet.unpack(raw)
            ?: throw IllegalStateException("could not unpack crafted link request packet")
        rx.hops = hops
        val iface = (inst.serverIface ?: inst.clientIface)
        rx.setReceivingInterfaceHashForTest(iface?.getHash())

        val link = runCatching { Link.validateRequest(owner, rx.data, rx) }.getOrNull()
        val accepted = link != null
        var establishmentTimeout: Long? = null
        var mode: Int? = null
        var mtu: Int? = null
        if (link != null) {
            establishmentTimeout = link.establishmentTimeout
            mode = link.mode
            mtu = link.mtu
            runCatching { link.teardown() }
        }
        result(
            "variant" to strVal(variant),
            "data_len" to intVal(data.size),
            "accepted" to boolVal(accepted),
            "inbound_link_created" to boolVal(accepted),
            "establishment_timeout" to (establishmentTimeout?.let { doubleVal(it / 1000.0) } ?: JsonNull.INSTANCE),
            "mode" to (mode?.let { intVal(it) } ?: JsonNull.INSTANCE),
            "mtu" to (mtu?.let { intVal(it) } ?: JsonNull.INSTANCE),
            "establishment_timeout_per_hop" to intVal((LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP / 1000L).toInt()),
            "keepalive" to intVal((LinkConstants.KEEPALIVE / 1000L).toInt()),
        )
    }

    "wire_link_accept_gate" -> {
        // Pin the link-accept gate: an inbound LINKREQUEST yields a link only
        // when the destination accepts links. kotlin enforces this gate in
        // Transport (Transport.kt:4559 `if (!destination.acceptLinkRequests)`
        // skip, then Link.validateRequest), NOT in Destination.receive (whose
        // LINKREQUEST branch is a callback stub). So drive the genuine gate
        // value (acceptsLinks) and the genuine validateRequest exactly as
        // Transport sequences them.
        val handle = p.str("handle")
        val accepts = p.get("accepts").asBoolean
        val appName = p.get("app_name")?.asString ?: "conformance"
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: arrayOf("accept-gate")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val ownerIdentity = Identity.create()
        val owner = Destination.create(ownerIdentity, DestinationDirection.IN, DestinationType.SINGLE, appName, *aspects)
        owner.acceptLinkRequests = accepts

        val data = Link.buildInitiatorRequestDataForTest().requestData
        val outOwner = Destination.create(ownerIdentity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val pkt = Packet.createRaw(
            destinationHash = outOwner.hash,
            data = data,
            packetType = network.reticulum.common.PacketType.LINKREQUEST,
            destinationType = DestinationType.SINGLE,
        )
        val rx = Packet.unpack(pkt.pack())
            ?: throw IllegalStateException("could not unpack crafted link request packet")
        rx.setReceivingInterfaceHashForTest((inst.serverIface ?: inst.clientIface)?.getHash())

        val linksBefore = 0
        // Transport's gate: validate only when the destination accepts links.
        val link = if (owner.acceptsLinks()) runCatching { Link.validateRequest(owner, rx.data, rx) }.getOrNull() else null
        val linksAfter = if (link != null) 1 else 0
        runCatching { link?.teardown() }
        result(
            "accepts" to boolVal(accepts),
            "links_before" to intVal(linksBefore),
            "links_after" to intVal(linksAfter),
            "link_created" to boolVal(linksAfter > linksBefore),
        )
    }

    // ===== Phase 5c batch 4b: link-traffic adversarial injectors =====

    "wire_send_forged_link_close" -> {
        // Inject a LINKCLOSE carrying a forged link_id over an established link;
        // teardown_packet only closes when the decrypted payload == the link's
        // own link_id, so a forged id is ignored (link stays ACTIVE). Passing
        // the real link_id is the positive control. Run on the link's own iface.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val forgedId = p.hex("forged_id")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkByIdWaiting(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val statusBefore = link.status
        val raw = buildLinkPacketRaw(link, forgedId, network.reticulum.common.PacketContext.LINKCLOSE)
        val rx = Packet.unpack(raw)
            ?: throw IllegalStateException("could not unpack crafted LINKCLOSE packet")
        rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)
        link.receive(rx)
        val statusAfter = link.status
        result(
            "torn_down" to boolVal(statusAfter == LinkConstants.CLOSED),
            "status_before" to intVal(statusBefore),
            "status_after" to intVal(statusAfter),
            "status_name_after" to (LINK_STATUS_NAMES[statusAfter]?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "forged_id" to hexVal(forgedId),
            "real_link_id" to hexVal(link.linkId),
        )
    }

    "wire_inject_tampered_link_data" -> {
        // Build a real DATA packet encrypted to an ACTIVE link, optionally
        // corrupt it, feed through link.receive, and report whether the
        // decrypted message reached the link's packet handler. The link Token
        // verifies its HMAC before decrypting, so any tamper -> dropped.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val corruption = p.get("corruption")?.asString ?: "none"
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkByIdWaiting(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val listener = findListenerForLink(inst, linkIdHex)
            ?: throw IllegalArgumentException(
                "link_id $linkIdHex is not an inbound link of any listener on this peer (run on the RECEIVER peer)",
            )
        val before = listener.recvBuffer.size

        var raw = buildLinkPacketRaw(link, payload, network.reticulum.common.PacketContext.NONE)
        // HEADER_1 link packet: flags(1)+hops(1)+link_id(16)+context(1)=19, then
        // the encrypted token (IV(16)||ciphertext||HMAC(32)). Damage one byte.
        val payloadOff = 19
        raw = when (corruption) {
            "ciphertext" -> raw.copyOf().also { it[payloadOff + 4] = ((it[payloadOff + 4].toInt() + 1) and 0xFF).toByte() }
            "hmac" -> raw.copyOf().also { it[it.size - 1] = ((it[it.size - 1].toInt() + 1) and 0xFF).toByte() }
            "truncate" -> raw.copyOf(raw.size - 1)
            "none", "foreign_interface" -> raw
            else -> throw IllegalArgumentException("unknown corruption: $corruption")
        }
        val rx = Packet.unpack(raw)
        val unpacked = rx != null
        if (rx != null) {
            if (corruption == "foreign_interface") {
                // Present on an interface that is NOT the link's attached one,
                // so the interface-bind check rejects an otherwise-valid packet.
                rx.setReceivingInterfaceHashForTest(network.reticulum.crypto.Hashes.fullHash("foreign-iface".toByteArray()))
            } else {
                rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)
            }
            link.receive(rx)
        }
        Thread.sleep(50)
        val after = listener.recvBuffer.size
        result(
            "corruption" to strVal(corruption),
            "unpacked" to boolVal(unpacked),
            "delivered" to boolVal(after > before),
            "link_active" to boolVal(link.status == LinkConstants.ACTIVE),
            "status_name" to (LINK_STATUS_NAMES[link.status]?.let { strVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_inject_closed_link_data" -> {
        // Pin that a CLOSED link silently drops all link traffic. Build a
        // PRISTINE DATA packet while the link is ACTIVE (key still present),
        // cache it, tear down, then replay — receive() returns immediately once
        // status==CLOSED, so the packet is never delivered.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
            ?: network.reticulum.crypto.Hashes.fullHash("closed-link-probe".toByteArray()).copyOf(16)
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkByIdWaiting(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val listener = findListenerForLink(inst, linkIdHex)
            ?: throw IllegalArgumentException(
                "link_id $linkIdHex is not an inbound link of any listener on this peer (run on the RECEIVER peer)",
            )
        // Build + pack while ACTIVE — the key is purged on close.
        val cachedRaw = buildLinkPacketRaw(link, payload, network.reticulum.common.PacketContext.NONE)
        val before = listener.recvBuffer.size
        runCatching { link.teardown() }
        Thread.sleep(50)
        val rx = Packet.unpack(cachedRaw)
        if (rx != null) {
            rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)
            link.receive(rx)
        }
        Thread.sleep(50)
        val after = listener.recvBuffer.size
        result(
            "delivered_before" to boolVal(false),
            "delivered" to boolVal(after > before),
            "status_name" to (LINK_STATUS_NAMES[link.status]?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "link_closed" to boolVal(link.status == LinkConstants.CLOSED),
        )
    }

    // ===== Phase 5c batch 4c: emission-capture (keepalive / teardown) =====

    "wire_send_keepalive_probe" -> {
        // Inject a decrypted 0xFF keepalive into a link's receive path and report
        // its response. A non-initiator answers 0xFF with 0xFE; an initiator
        // drops its own 0xFF echo (no answer). KEEPALIVE data is unencrypted.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val value = p.get("value")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: byteArrayOf(0xFF.toByte())
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkByIdWaiting(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        // KEEPALIVE packets carry their value unencrypted (Packet ciphertext==data).
        val pkt = Packet.createRaw(
            destinationHash = link.linkId,
            data = value,
            packetType = network.reticulum.common.PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = network.reticulum.common.PacketContext.KEEPALIVE,
            mtu = link.mtu,
        )
        val rx = Packet.unpack(pkt.pack())
            ?: throw IllegalStateException("could not unpack crafted keepalive packet")
        rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)

        val lastInboundBefore = link.lastInbound
        val lastDataBefore = link.lastData
        val statusBefore = link.status
        val keepaliveCtx = network.reticulum.common.PacketContext.KEEPALIVE.value
        val emissions = captureOutbound { link.receive(rx) }
        val answer = emissions.lastOrNull { it.context == keepaliveCtx && it.destHashHex == linkIdHex }?.data
        if (answer != null) wireKeepalivePayloads[linkIdHex] = answer
        result(
            "response" to (answer?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "answered" to boolVal(answer != null),
            "initiator" to boolVal(link.initiator),
            "last_inbound_advanced" to boolVal(link.lastInbound > lastInboundBefore),
            "last_data_advanced" to boolVal(link.lastData > lastDataBefore),
            "status_before" to intVal(statusBefore),
            "status_after" to intVal(link.status),
        )
    }

    "wire_last_keepalive" -> {
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val payload = wireKeepalivePayloads[linkIdHex]
        result("payload" to (payload?.let { hexVal(it) } ?: JsonNull.INSTANCE))
    }

    "wire_link_teardown_emission" -> {
        // Pin Link.teardown's LINKCLOSE-emission gate: a PENDING link tears down
        // silently; an ACTIVE link emits exactly one LINKCLOSE. Count LINKCLOSE
        // emissions across a fresh forced-PENDING link and the established link.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val appName = p.get("app_name")?.asString ?: "conformance"
        val aspectsJson = p.get("aspects")?.asJsonArray
        val aspects: Array<String> = aspectsJson?.map { it.asString }?.toTypedArray() ?: arrayOf("teardown-emit")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val activeLink = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown outbound link_id: $linkIdHex")

        val linkcloseCtx = network.reticulum.common.PacketContext.LINKCLOSE.value
        val activeStatusBefore = LINK_STATUS_NAMES[activeLink.status]
        // PENDING: a fresh initiator link forced PENDING (its handshake sends a
        // LINKREQUEST, never a LINKCLOSE, so it doesn't perturb the count). A
        // PENDING teardown must emit no LINKCLOSE.
        val pendingDest = Destination.create(Identity.create(), DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val pending = Link.create(pendingDest)
        pending.setStatusForTest(LinkConstants.PENDING)
        val pendingEmitted = captureOutbound { pending.teardown() }.count { it.context == linkcloseCtx }
        // ACTIVE: the established link emits exactly one LINKCLOSE.
        val activeEmitted = captureOutbound { activeLink.teardown() }
            .count { it.context == linkcloseCtx && it.destHashHex == linkIdHex }

        result(
            "pending_linkclose_emitted" to intVal(pendingEmitted),
            "active_linkclose_emitted" to intVal(activeEmitted),
            "active_status_before" to (activeStatusBefore?.let { strVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    // ===== Phase 5c batch 4d: crafted proof / identify injectors =====

    "wire_inject_crafted_lrproof" -> {
        // Adversarial LRPROOF injector: a PENDING initiator validates the
        // destination's LRPROOF (signature over link_id||eph_pub||dest_sig_pub).
        // Only a signature verifying against the destination identity activates
        // the link. Mirrors cmd_wire_inject_crafted_lrproof.
        val handle = p.str("handle")
        val variant = p.str("variant")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = network.reticulum.crypto.defaultCryptoProvider()

        val destIdentity = Identity.create()
        val outDest = Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "lrproof")
        val link = Link.create(outDest)
        link.setStatusForTest(LinkConstants.PENDING)

        val ecHalf = LinkConstants.ECPUBSIZE / 2 // 32
        val sigLen = network.reticulum.common.RnsConstants.SIGNATURE_SIZE // 64
        val ephemeralPub = crypto.generateX25519KeyPair().publicKey
        val destSigPub = destIdentity.getPublicKey().copyOfRange(ecHalf, LinkConstants.ECPUBSIZE)
        val signedData = link.linkId + ephemeralPub + destSigPub

        val proofData: ByteArray = when (variant) {
            "valid", "non_pending" -> {
                if (variant == "non_pending") link.setStatusForTest(LinkConstants.CLOSED)
                destIdentity.sign(signedData) + ephemeralPub
            }
            "forged_signature" -> Identity.create().sign(signedData) + ephemeralPub
            "wrong_signed_data" -> destIdentity.sign(network.reticulum.crypto.Hashes.fullHash("unrelated".toByteArray()).copyOf(96.coerceAtMost(32)) + ByteArray(64)) + ephemeralPub
            "wrong_size" -> (destIdentity.sign(signedData) + ephemeralPub).let { it.copyOf(it.size - 1) }
            "mode_mismatch" -> {
                val signalling = Link.signallingBytes(network.reticulum.common.RnsConstants.MTU, link.mode)
                val sig = destIdentity.sign(link.linkId + ephemeralPub + destSigPub + signalling)
                val raw = (sig + ephemeralPub + signalling).copyOf()
                val modeIdx = sigLen + ecHalf // 96
                val wrongMode = LinkConstants.MODE_AES256_GCM
                raw[modeIdx] = (((raw[modeIdx].toInt() and LinkConstants.MODE_BYTEMASK.inv()) or ((wrongMode shl 5) and LinkConstants.MODE_BYTEMASK)) and 0xFF).toByte()
                raw
            }
            else -> throw IllegalArgumentException("unknown lrproof variant: $variant")
        }

        val pkt = Packet.createRaw(
            destinationHash = link.linkId, data = proofData,
            packetType = network.reticulum.common.PacketType.PROOF,
            destinationType = DestinationType.LINK,
            context = network.reticulum.common.PacketContext.LRPROOF,
        )
        val rx = Packet.unpack(pkt.pack())
        if (rx != null) {
            runCatching { link.validateProof(rx) }
        }
        val statusAfter = link.status
        val activated = statusAfter == LinkConstants.ACTIVE
        runCatching { link.teardown() }
        result(
            "variant" to strVal(variant),
            "activated" to boolVal(activated),
            "status" to intVal(statusAfter),
            "status_name" to (LINK_STATUS_NAMES[statusAfter]?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "mtu" to (if (activated) intVal(link.mtu) else JsonNull.INSTANCE),
            "mode" to intVal(link.mode),
        )
    }

    "wire_inject_crafted_link_identify" -> {
        // Adversarial LINKIDENTIFY injector: the non-initiator adopts the
        // claimed identity only when the encrypted payload is public_key(64)||
        // signature(64) (128B) and the signature verifies over link_id||pubkey.
        // Run on the NON-INITIATOR (the listener holding the inbound link).
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val variant = p.str("variant")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = findLinkByIdWaiting(inst, linkIdHex)
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val claimed = Identity.create()
        val publicKey = claimed.getPublicKey()
        val signedData = link.linkId + publicKey
        val payload: ByteArray = when (variant) {
            "valid" -> publicKey + claimed.sign(signedData)
            "forged_signature" -> publicKey + Identity.create().sign(signedData)
            "wrong_signed_data" -> publicKey + claimed.sign(network.reticulum.crypto.Hashes.fullHash("unrelated".toByteArray()) + ByteArray(64))
            "wrong_length" -> publicKey + claimed.sign(signedData).copyOf(network.reticulum.common.RnsConstants.SIGNATURE_SIZE / 2) // 96B total
            else -> throw IllegalArgumentException("unknown link-identify variant: $variant")
        }
        val raw = buildLinkPacketRaw(link, payload, network.reticulum.common.PacketContext.LINKIDENTIFY)
        val rx = Packet.unpack(raw)
        if (rx != null) {
            rx.setReceivingInterfaceHashForTest(link.attachedInterfaceHash)
            link.receive(rx)
        }
        Thread.sleep(50)
        val remoteAfter = link.getRemoteIdentity()
        result(
            "variant" to strVal(variant),
            "claimed_identity_hash" to hexVal(claimed.hash),
            "remote_identity_after" to (remoteAfter?.hash?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "adopted" to boolVal(remoteAfter != null && remoteAfter.hash.contentEquals(claimed.hash)),
            "initiator" to boolVal(link.initiator),
        )
    }

    // ===== Phase 5c batch 4e: LRPROOF frame capture + link-proof injection =====

    "wire_capture_lrproof_frame" -> {
        // Capture the raw outbound LRPROOF frame + its flag-byte shape. An
        // LRPROOF is built as a PROOF packet to a LINK destination, so its flags
        // encode dest-type LINK and pack() writes the link_id in the dest-address
        // position. Mirrors cmd_wire_capture_lrproof_frame.
        val handle = p.str("handle")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destIdentity = Identity.create()
        val outDest = Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "lrproof-shape")
        val link = Link.create(outDest)
        val signalling = Link.signallingBytes(link.mtu, link.mode)
        val pubBytes = link.pubBytesForTest()!!
        val sigPubBytes = link.sigPubBytesForTest()!!
        val signedData = link.linkId + pubBytes + sigPubBytes + signalling
        val signature = destIdentity.sign(signedData)
        val proofData = signature + pubBytes + signalling
        val proof = Packet.createRaw(
            destinationHash = link.linkId, data = proofData,
            packetType = network.reticulum.common.PacketType.PROOF,
            destinationType = DestinationType.LINK,
            context = network.reticulum.common.PacketContext.LRPROOF,
        )
        val raw = proof.pack()
        val flags = proof.getPackedFlags()
        runCatching { link.teardown() }
        result(
            "raw" to hexVal(raw),
            "flags" to intVal(flags),
            "link_id" to hexVal(link.linkId),
            "packet_type" to intVal(network.reticulum.common.PacketType.PROOF.value),
            "context" to intVal(network.reticulum.common.PacketContext.LRPROOF.value),
            "expected_link_dest_type" to intVal(DestinationType.LINK.value),
            "truncated_hashlength" to intVal(network.reticulum.common.RnsConstants.TRUNCATED_HASH_BYTES),
        )
    }

    "wire_inject_crafted_link_proof" -> {
        // Link DATA-packet proofs are EXPLICIT-only (validateLinkProof): only the
        // 96-byte packet_hash||signature form is accepted; a valid-signature
        // 64-byte implicit proof is STILL rejected. Self-consistent signing lets
        // a real link.sign produce a signature the real validate accepts.
        val handle = p.str("handle")
        val variant = p.str("variant")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = network.reticulum.crypto.defaultCryptoProvider()

        val destIdentity = Identity.create()
        val outDest = Destination.create(destIdentity, DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "link-proof")
        val link = Link.create(outDest)
        link.makeSelfConsistentSigningForTest()

        val basePacket = Packet.createRaw(
            destinationHash = outDest.hash, data = crypto.randomBytes(20),
            packetType = network.reticulum.common.PacketType.DATA, destinationType = DestinationType.SINGLE,
        )
        basePacket.pack()
        val receipt = network.reticulum.packet.PacketReceipt.forPacketForTest(basePacket)
        val sigLen = network.reticulum.common.RnsConstants.SIGNATURE_SIZE
        val proof: ByteArray = when (variant) {
            "valid_explicit" -> receipt.hash + link.sign(receipt.hash)
            "implicit_valid_sig" -> link.sign(receipt.hash)
            "implicit_random" -> crypto.randomBytes(sigLen)
            "wrong_length_short" -> crypto.randomBytes(sigLen / 2)
            else -> throw IllegalArgumentException("unknown link-proof variant: $variant")
        }
        val validated = runCatching { receipt.validateLinkProof(proof, link, null) }.getOrDefault(false)
        val status = receipt.status
        runCatching { link.teardown() }
        result(
            "variant" to strVal(variant),
            "validated" to boolVal(validated),
            "status" to intVal(status),
            "status_name" to (receiptStatusName(status)?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "proof_len" to intVal(proof.size),
            "expl_length" to intVal(network.reticulum.packet.PacketReceipt.EXPL_LENGTH),
            "impl_length" to intVal(network.reticulum.packet.PacketReceipt.IMPL_LENGTH),
        )
    }

    "wire_capture_response_packet" -> {
        // Capture the raw RESPONSE packet RNS delivers for a Link.request. Arms
        // a per-link inbound tap that records RESPONSE (decrypted plaintext) and
        // RESOURCE_ADV packets, then issues link.request and waits for it to
        // conclude. Mirrors cmd_wire_capture_response_packet.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val path = p.str("path")
        val data = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex()
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 15000
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val captured = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val responseCtx = network.reticulum.common.PacketContext.RESPONSE.value
        val resourceAdvCtx = network.reticulum.common.PacketContext.RESOURCE_ADV.value
        link.inboundTapForTest = { pkt ->
            runCatching {
                val ctx = pkt.context.value
                if (ctx == responseCtx || ctx == resourceAdvCtx) {
                    val entry = JsonObject()
                    entry.addProperty("context", ctx)
                    if (ctx == responseCtx) {
                        val plaintext = link.decrypt(pkt.data)
                        if (plaintext != null) entry.addProperty("plaintext", plaintext.toHex())
                        else entry.add("plaintext", JsonNull.INSTANCE)
                    } else {
                        entry.add("plaintext", JsonNull.INSTANCE)
                    }
                    captured.add(entry)
                }
            }
        }
        var status = "timeout"
        var responseHex: String? = null
        try {
            val receipt = link.request(path, data = data, timeout = timeoutMs.toLong())
                ?: throw IllegalStateException("Link.request returned null (REQUEST not sent)")
            val deadline = System.currentTimeMillis() + timeoutMs + 500
            while (System.currentTimeMillis() < deadline) {
                when (receipt.status) {
                    RequestReceipt.READY -> {
                        responseHex = receipt.getResponseCopy()?.toHex()
                        status = "ready"
                    }
                    RequestReceipt.FAILED -> status = "failed"
                }
                if (status != "timeout") break
                Thread.sleep(50)
            }
        } finally {
            link.inboundTapForTest = null
        }
        val arr = JsonArray()
        synchronized(captured) { captured.forEach { arr.add(it) } }
        result(
            "status" to strVal(status),
            "response" to (responseHex?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "captured" to arr,
        )
    }

    "wire_start_pipe_peer" -> {
        // Bring up RNS with a SINGLE PipeInterface — the leaf "A" end of the
        // mixed pipe<->TCP relay topology. enable_transport defaults False (a
        // leaf host, not a transport node); no TCP block. Returns
        // {handle, identity_hash}. Mirrors cmd_wire_start_pipe_peer
        // (reference/wire_tcp.py:854-894).
        val readFifo = p.str("read_fifo")
        val writeFifo = p.str("write_fifo")
        // network_name/passphrase are accepted for parity with the reference
        // and the conftest helper, but reticulum-kt's PipeInterface has no
        // IFAC constructor params (PipeInterface.kt:30-36 → ifacSize 0,
        // Interface.kt:42-43) so IFAC cannot be applied over the pipe — see
        // RISK 2. The only consuming test passes both empty, so this is inert
        // there; a non-empty value would silently NOT be IFAC-masked.
        val networkName = p.get("network_name")?.asString ?: ""
        val passphrase = p.get("passphrase")?.asString ?: ""
        // Reference default: enable_transport=False (wire_tcp.py:867).
        val enableTransport = p.get("enable_transport")?.asBoolean ?: false

        resetWireState()
        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_pipe_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = enableTransport,
                shareInstance = false,
                connectToSharedInstance = false,
            )

            // FIFO wiring (RISK 3): do NOT open the FIFOs directly — a named
            // FIFO open blocks until the other end opens, which would hang the
            // synchronous command loop (and deadlock the symmetric peer).
            // Instead spawn the exact same `bash -c 'cat read & cat > write'`
            // subprocess Python's PipeInterface uses (reference _pipe_command,
            // wire_tcp.py:779-793): its stdin/stdout are ordinary pipes (open
            // instantly), and the blocking FIFO opens happen inside the bash
            // child off the bridge's thread. PipeInterface then reads the
            // subprocess STDOUT (→ RNS incoming) and writes RNS outgoing to the
            // subprocess STDIN — byte-identical to the reference.
            val proc = ProcessBuilder("bash", "-c", "cat $readFifo & cat > $writeFifo")
                .redirectErrorStream(false)
                .start()
            val pipe = PipeInterface(
                name = "Wire Pipe Peer",
                inputStream = proc.inputStream,
                outputStream = proc.outputStream,
            )
            // Mirror the TCP-server inbound wiring (WireTcp.kt:508-511): record
            // into the wire_get_received_packets tap and route to Transport.
            pipe.onPacketReceived = { data, iface ->
                recordInboundPacket(data, iface.name)
                Transport.inbound(data, iface.toRef())
            }
            pipe.start()
            Transport.registerInterface(pipe.toRef())

            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport started without an identity")

            val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            wireInstances[handle] = WireInstance(
                rns = rns,
                identityHash = identityHash,
                configDir = configDir,
                role = "pipe_peer",
                port = 0,
                pipeIface = pipe,
                pipeProcess = proc,
            )
            result(
                "handle" to strVal(handle),
                "identity_hash" to hexVal(identityHash),
            )
        } catch (t: Throwable) {
            runCatching { Reticulum.stop() }
            runCatching { configDir.deleteRecursively() }
            throw t
        }
    }

    "wire_start_pipe_tcp_relay" -> {
        // Bring up RNS as the transport relay "B": a PipeInterface (to A) PLUS
        // a TCPServerInterface (to C) in one instance. enable_transport
        // defaults True (it MUST forward); bind_port 0 → allocate a free port.
        // Returns {handle, port, identity_hash}. Mirrors
        // cmd_wire_start_pipe_tcp_relay (reference/wire_tcp.py:897-948).
        val readFifo = p.str("read_fifo")
        val writeFifo = p.str("write_fifo")
        // See RISK 2 note in wire_start_pipe_peer: applied to the TCP block via
        // the TCPServerInterface IFAC params, but NOT to the pipe block (kotlin
        // PipeInterface has no IFAC support). Reference applies them to both.
        val networkName = p.get("network_name")?.asString?.takeIf { it.isNotEmpty() }
        val passphrase = p.get("passphrase")?.asString?.takeIf { it.isNotEmpty() }
        // Reference default: enable_transport=True (wire_tcp.py:914).
        val enableTransport = p.get("enable_transport")?.asBoolean ?: true
        val bindPortReq = p.get("bind_port")?.asInt ?: 0
        val bindPort = if (bindPortReq == 0) allocateFreePort() else bindPortReq

        resetWireState()
        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_piperelay_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = enableTransport,
                shareInstance = false,
                connectToSharedInstance = false,
            )

            // TCP server (to C). Register spawned client children with Transport
            // (RISK 4) so path/link responses addressed to a spawned child are
            // not dropped — same guard as wire_start_tcp_server
            // (WireTcp.kt:489-501).
            val server = TCPServerInterface(
                name = "Wire Pipe Relay TCP",
                bindAddress = "127.0.0.1",
                bindPort = bindPort,
                ifacNetname = networkName,
                ifacNetkey = passphrase,
            )
            server.onClientConnected = { spawnedChild ->
                runCatching { Transport.registerInterface(spawnedChild.toRef()) }
                    .onFailure { e ->
                        System.err.println(
                            "[WireTcp] Failed to register spawned relay client ${spawnedChild.name}: $e",
                        )
                    }
            }
            server.start()
            Transport.registerInterface(server.toRef())
            server.onPacketReceived = { data, iface ->
                recordInboundPacket(data, iface.name)
                Transport.inbound(data, iface.toRef())
            }

            // Pipe (to A) — subprocess-backed, exactly as wire_start_pipe_peer
            // (RISK 3): never blocks the command loop.
            val proc = ProcessBuilder("bash", "-c", "cat $readFifo & cat > $writeFifo")
                .redirectErrorStream(false)
                .start()
            val pipe = PipeInterface(
                name = "Wire Pipe Relay",
                inputStream = proc.inputStream,
                outputStream = proc.outputStream,
            )
            pipe.onPacketReceived = { data, iface ->
                recordInboundPacket(data, iface.name)
                Transport.inbound(data, iface.toRef())
            }
            pipe.start()
            Transport.registerInterface(pipe.toRef())

            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport started without an identity")

            val handle = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            // Carry the TCP server on serverIface so the existing wire_listen /
            // IFAC / mode / detach logic (which resolves inst.serverIface ?:
            // inst.clientIface) keeps working for the relay's TCP side; the pipe
            // rides on the new pipeIface/pipeProcess fields.
            wireInstances[handle] = WireInstance(
                rns = rns,
                identityHash = identityHash,
                configDir = configDir,
                role = "pipe_tcp_relay",
                port = bindPort,
                serverIface = server,
                pipeIface = pipe,
                pipeProcess = proc,
            )
            result(
                "handle" to strVal(handle),
                "port" to intVal(bindPort),
                "identity_hash" to hexVal(identityHash),
            )
        } catch (t: Throwable) {
            runCatching { Reticulum.stop() }
            runCatching { configDir.deleteRecursively() }
            throw t
        }
    }

    // ===== Phase 5g: instance / config posture =====

    "wire_transport_enabled" -> {
        // Ground-truth Reticulum.transport_enabled() + the two shared-instance
        // role flags (reference wire_tcp.py:3242-3267).
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        result(
            "transport_enabled" to boolVal(Reticulum.transportEnabled()),
            "is_shared_instance" to boolVal(inst.rns.isSharedInstance),
            "is_connected_to_shared_instance" to boolVal(isConnectedToSharedInstance(inst)),
        )
    }

    "wire_instance_posture" -> {
        // Union of the process-wide posture flags RNS resolves at start
        // (reference wire_tcp.py:8497-8542). Each reads the companion static /
        // Transport list the start command populated from the config knobs.
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        result(
            "transport_enabled" to boolVal(Reticulum.transportEnabled()),
            "remote_management_enabled" to boolVal(Reticulum.remoteManagementEnabled()),
            "respond_to_probes" to boolVal(Reticulum.probeDestinationEnabled()),
            "should_use_implicit_proof" to boolVal(Reticulum.shouldUseImplicitProof()),
            "link_mtu_discovery" to boolVal(Reticulum.linkMtuDiscovery()),
            "remote_management_allowed" to JsonArray().apply {
                Transport.remoteManagementAllowed.forEach { add(it.toHex()) }
            },
            "panic_on_interface_error" to boolVal(Reticulum.panicOnInterfaceError()),
            "blackhole_sources" to JsonArray().apply {
                Transport.blackholeSources.forEach { add(it.toHex()) }
            },
            "interface_discovery_sources" to JsonArray().apply {
                Reticulum.interfaceDiscoverySources().forEach { add(it.toHex()) }
            },
            "is_shared_instance" to boolVal(inst.rns.isSharedInstance),
            "is_connected_to_shared_instance" to boolVal(isConnectedToSharedInstance(inst)),
        )
    }

    "wire_rpc_authkey" -> {
        // Derived RPC authkey + the transport identity private key (reference
        // wire_tcp.py:8655-8676). Default = SHA-256(privkey) (Reticulum.py:347-348).
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        val priv = Transport.identity?.getPrivateKey()
            ?: throw IllegalStateException("Transport has no identity")
        result(
            "rpc_key" to hexVal(inst.rns.rpcKey),
            "transport_private_key" to hexVal(priv),
        )
    }

    "wire_interface_bitrate" -> {
        // Live interface bitrate after the MINIMUM_BITRATE floor (reference
        // wire_tcp.py:8629-8652; Reticulum.py:765-768). bitrate_guess is the
        // class constant regardless of the floored value.
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        val iface = primaryWireInterface(inst)
            ?: throw RuntimeException("No configured wire interface on this handle.")
        val guess = when (iface) {
            is TCPServerInterface -> TCPServerInterface.BITRATE_GUESS
            is TCPClientInterface -> TCPClientInterface.BITRATE_GUESS
            else -> iface.bitrate
        }
        result(
            "bitrate" to intVal(iface.bitrate),
            "bitrate_guess" to intVal(guess),
            "minimum_bitrate" to intVal(Reticulum.MINIMUM_BITRATE),
        )
    }

    "wire_interface_hw_mtu" -> {
        // Link-MTU-discovery flag + interface HW_MTU posture (reference
        // wire_tcp.py:2493-2524). fixed_mtu pins HW_MTU and disables AUTOCONFIGURE.
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        val iface = primaryWireInterface(inst)
            ?: throw RuntimeException("No configured wire interface on this handle.")
        val classHwMtu = when (iface) {
            is TCPServerInterface -> TCPServerInterface.HW_MTU
            is TCPClientInterface -> TCPClientInterface.HW_MTU
            else -> iface.hwMtu
        }
        result(
            "hw_mtu" to (iface.hwMtu?.let { intVal(it) } ?: JsonNull.INSTANCE),
            "link_mtu_discovery" to boolVal(Reticulum.linkMtuDiscovery()),
            "reticulum_mtu" to intVal(Reticulum.MTU),
            "autoconfigure_mtu" to boolVal(iface.autoconfigureMtu),
            "fixed_mtu" to boolVal(iface.fixedMtu),
            "class_hw_mtu" to (classHwMtu?.let { intVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_interface_transport_defaults" -> {
        // Transport-node interop default constants (reference wire_tcp.py:9143-9167).
        wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        result(
            "local_interface_port" to intVal(Reticulum.DEFAULT_SHARED_INSTANCE_PORT),
            "ar_target" to intVal(network.reticulum.interfaces.Interface.DEFAULT_AR_TARGET),
            "ar_penalty" to intVal(network.reticulum.interfaces.Interface.DEFAULT_AR_PENALTY),
            "ar_grace" to intVal(network.reticulum.interfaces.Interface.DEFAULT_AR_GRACE),
        )
    }

    "wire_first_hop_timeout" -> {
        // Transport.first_hop_timeout(dh): unknown path -> DEFAULT_PER_HOP_TIMEOUT
        // (reference wire_tcp.py:8679-8698). kotlin returns ms; convert to seconds.
        wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")
        val dh = p.hex("destination_hash")
        val timeoutMs = Transport.firstHopTimeout(dh)
        result(
            "timeout" to doubleVal(timeoutMs / 1000.0),
            "default_per_hop_timeout" to intVal(Reticulum.DEFAULT_PER_HOP_TIMEOUT),
        )
    }

    "wire_discovery_autoconnect_gate" -> {
        // Drive InterfaceDiscovery.autoconnect for four discovered-interface
        // records; every one must add zero interfaces (reference wire_tcp.py:
        // 9170-9246). Also pins the endpoint dedup hash == SHA-256("host:port").
        val inst = wireInstances[p.str("handle")]
            ?: throw IllegalArgumentException("Unknown handle: ${p.str("handle")}")

        val savedCap = Reticulum.autoconnectDiscoveredInterfaces
        Reticulum.autoconnectDiscoveredInterfaces = 4
        val createdRefs = mutableListOf<network.reticulum.transport.InterfaceRef>()
        try {
            val disc = InterfaceDiscovery(
                storagePath = inst.configDir.absolutePath + "/discovery_gate",
                requiredValue = 14,
                autoConnectFactory = { _ ->
                    // Only reached if autoconnect's type / ygg guards wrongly
                    // pass — registers a (non-started) dummy so the Transport
                    // interface count rises, making the gate test discriminating.
                    val dummy = TCPServerInterface(
                        name = "discovery-gate-dummy-${UUID.randomUUID()}",
                        bindPort = allocateFreePort(),
                    )
                    val ref = dummy.toRef()
                    Transport.registerInterface(ref)
                    createdRefs.add(ref)
                    ref
                },
                maxAutoConnected = 4,
            )

            fun info(type: String, reachableOn: String, port: Int): DiscoveredInterface =
                DiscoveredInterface(
                    type = type, transport = false, name = "discovered-gate",
                    received = 0, stampValue = 14,
                    transportId = "00".repeat(16), networkId = "00".repeat(16),
                    hops = 0, latitude = null, longitude = null, height = null,
                    reachableOn = reachableOn, port = port, frequency = null,
                    bandwidth = null, spreadingFactor = null, codingRate = null,
                    modulation = null, channel = null, ifacNetname = null,
                    ifacNetkey = null,
                    discoveryHash = Hashes.fullHash((type + "discovered-gate").toByteArray()),
                )

            val cases = linkedMapOf(
                "wrong_type" to info("UDPInterface", "10.0.0.5", 4242),
                "tcp_client" to info("TCPClientInterface", "10.0.0.6", 4242),
                "i2p" to info("I2PInterface", "abcdefgh.b32.i2p", 0),
                "yggdrasil" to info("BackboneInterface", "200::1", 4242),
            )
            val out = mutableListOf<Pair<String, com.google.gson.JsonElement>>()
            for ((name, rec) in cases) {
                val before = Transport.getInterfaces().size
                runCatching { disc.autoconnectForTest(rec) }
                val after = Transport.getInterfaces().size
                out += name to result("interfaces_added" to intVal(after - before))
            }
            val probe = info("BackboneInterface", "10.9.9.9", 4242)
            out += "endpoint_hash" to hexVal(disc.endpointHashForTest(probe))
            out += "endpoint_spec" to strVal("10.9.9.9:4242")
            result(*out.toTypedArray())
        } finally {
            // Deregister any dummy a (buggy) autoconnect added, so it can't leak
            // into the next test sharing this JVM singleton.
            for (ref in createdRefs) runCatching { Transport.deregisterInterface(ref) }
            Reticulum.autoconnectDiscoveredInterfaces = savedCap
        }
    }

    // wire_mgmt_destinations is intentionally NOT implemented: reticulum-kt has
    // no probe-responder / remote-management Destination feature (no
    // rnstransport.probe or rnstransport.remote.management registration in
    // Transport.start). Shipping only the negative "{present:false}" case would
    // be a half-implementation that tests nothing, so it surfaces as a SUT
    // capability gap (reticulum-kt#mgmt-destinations). config_parse_interface is
    // likewise unimplemented: kotlin has no INI/ConfigObj parser nor
    // _synthesize_interface (python-reference-only). Both fall through to else.

    // ===== Phase 5f: packet / receipt commands =====

    "wire_send_packet" -> {
        // Send a single SINGLE-destination DATA packet with a tracked
        // PacketReceipt. Mirrors cmd_wire_send_packet (wire_tcp.py:3343-3407):
        // recall identity -> OUT SINGLE Destination -> Packet.send(). `sent` is
        // read off packet.sent (kotlin send() returns null for BOTH
        // create_receipt=false AND failure, so it can't distinguish them);
        // hops_to falls back to PATHFINDER_M for an unknown path (python int()).
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val appName = p.str("app_name")
        val aspects = p.get("aspects")?.asJsonArray?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val createReceipt = p.get("create_receipt")?.asBoolean ?: true
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; ensure an announce for this destination was received first.",
            )
        val outDest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val packet = Packet.create(outDest, payload, createReceipt = createReceipt)
        packet.send()
        if (!packet.sent) {
            result(
                "sent" to boolVal(false),
                "receipt_id" to JsonNull.INSTANCE,
                "hops" to JsonNull.INSTANCE,
            )
        } else {
            val receiptId = packet.receipt?.let { r ->
                val id = freshReceiptId()
                inst.receipts[id] = r
                id
            }
            inst.destinations.add(identity to outDest)
            result(
                "sent" to boolVal(true),
                "receipt_id" to (receiptId?.let { strVal(it) } ?: JsonNull.INSTANCE),
                "hops" to intVal(Transport.hopsTo(destHash) ?: TransportConstants.PATHFINDER_M),
            )
        }
    }

    "wire_send_packet_with_proof_request" -> {
        // Like wire_send_packet but BLOCKS for the receipt to conclude, then
        // surfaces the captured PROOF. Mirrors cmd_wire_send_packet_with_proof_request
        // (wire_tcp.py:5881-5974). proof_is_implicit/explicit are decided by the
        // captured proof's LENGTH, not by config; implicit_proof_config is the
        // local should_use_implicit_proof().
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val appName = p.get("app_name")?.asString ?: "conformance"
        val aspects = p.get("aspects")?.asJsonArray?.map { it.asString }?.toTypedArray() ?: arrayOf("wire")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 10000
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; ensure an announce for this destination was received first.",
            )
        val outDest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val packet = Packet.create(outDest, payload, createReceipt = true)
        packet.send()
        val implLen = PacketReceipt.IMPL_LENGTH
        val explLen = PacketReceipt.EXPL_LENGTH
        if (!packet.sent) {
            result(
                "sent" to boolVal(false),
                "receipt_id" to JsonNull.INSTANCE,
                "hops" to JsonNull.INSTANCE,
                "delivered" to boolVal(false),
                "proved" to boolVal(false),
                "implicit_proof_config" to boolVal(Reticulum.shouldUseImplicitProof()),
                "proof_data" to JsonNull.INSTANCE,
                "proof_len" to JsonNull.INSTANCE,
                "proof_is_implicit" to JsonNull.INSTANCE,
                "proof_is_explicit" to JsonNull.INSTANCE,
                "impl_length" to intVal(implLen),
                "expl_length" to intVal(explLen),
                "proof_raw" to JsonNull.INSTANCE,
                "proved_packet_hash" to JsonNull.INSTANCE,
            )
        } else {
            val receiptId = freshReceiptId()
            val receipt = packet.receipt
            if (receipt != null) inst.receipts[receiptId] = receipt
            inst.destinations.add(identity to outDest)

            val terminal = setOf(PacketReceipt.DELIVERED, PacketReceipt.FAILED, PacketReceipt.CULLED)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (receipt != null && receipt.status !in terminal && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val proofPacket = receipt?.proofPacket
            val proofData = proofPacket?.data
            val proofLen = proofData?.size
            result(
                "sent" to boolVal(true),
                "receipt_id" to strVal(receiptId),
                "hops" to intVal(Transport.hopsTo(destHash) ?: TransportConstants.PATHFINDER_M),
                "delivered" to boolVal(receipt?.status == PacketReceipt.DELIVERED),
                "proved" to boolVal(receipt?.proved ?: false),
                "implicit_proof_config" to boolVal(Reticulum.shouldUseImplicitProof()),
                "proof_data" to (proofData?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                "proof_len" to (proofLen?.let { intVal(it) } ?: JsonNull.INSTANCE),
                "proof_is_implicit" to (proofLen?.let { boolVal(it == implLen) } ?: JsonNull.INSTANCE),
                "proof_is_explicit" to (proofLen?.let { boolVal(it == explLen) } ?: JsonNull.INSTANCE),
                "impl_length" to intVal(implLen),
                "expl_length" to intVal(explLen),
                "proof_raw" to (proofPacket?.raw?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                "proved_packet_hash" to (receipt?.hash?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            )
        }
    }

    "wire_send_link_data" -> {
        // Send a DATA packet OVER an established link with a tracked receipt.
        // Mirrors cmd_wire_send_link_data (wire_tcp.py:3518-3555). create_receipt
        // true -> link.sendWithReceipt; false -> build (seam) + send (sendWithReceipt
        // can't express create_receipt=false).
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val createReceipt = p.get("create_receipt")?.asBoolean ?: true
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        if (createReceipt) {
            val receipt = link.sendWithReceipt(payload)
            if (receipt == null) {
                result("sent" to boolVal(false), "receipt_id" to JsonNull.INSTANCE)
            } else {
                val id = freshReceiptId()
                inst.receipts[id] = receipt
                result("sent" to boolVal(true), "receipt_id" to strVal(id))
            }
        } else {
            val packet = link.buildDataPacketForTest(payload, createReceipt = false)
            packet.send()
            result("sent" to boolVal(packet.sent), "receipt_id" to JsonNull.INSTANCE)
        }
    }

    "wire_send_over_closed_link" -> {
        // Drive send() over a CLOSED link and report that nothing was transmitted.
        // Mirrors cmd_wire_send_over_closed_link (wire_tcp.py:3558-3601):
        // sendWithReceipt returns null for a non-ACTIVE link WITHOUT transmitting,
        // so sent=false and txBytes is unchanged.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: "after-close".toByteArray()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val txBefore = link.txBytes.get()
        val receipt = link.sendWithReceipt(payload)
        val txAfter = link.txBytes.get()
        result(
            "link_status" to intVal(link.status),
            "link_status_name" to strVal(LINK_STATUS_NAMES[link.status] ?: link.status.toString()),
            "sent" to boolVal(receipt != null),
            "bytes_transmitted" to intVal((txAfter - txBefore).toInt()),
        )
    }

    "wire_send_oversize_link_packet" -> {
        // Build a link DATA packet of `size` random bytes and report whether the
        // genuine link-MTU bound rejects it. Mirrors cmd_wire_send_oversize_link_packet
        // (wire_tcp.py:2527-2578): the per-packet mtu is link.mtu (seam), and
        // pack() (inside send()) throws when the packed size exceeds it.
        val handle = p.str("handle")
        val linkIdHex = p.hex("link_id").toHex()
        val size = p.int("size")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val payload = defaultCryptoProvider().randomBytes(size)
        val packet = link.buildDataPacketForTest(payload)
        val packetMtu = packet.mtu
        var sent = false
        var rejected = false
        var error: String? = null
        var rawLen: Int? = null
        try {
            packet.send()
            sent = packet.sent
            rawLen = packet.raw?.size
        } catch (e: Exception) {
            sent = false
            rejected = true
            error = e.message
        }
        result(
            "size" to intVal(size),
            "mtu" to intVal(link.mtu),
            "mdu" to intVal(link.mdu),
            "packet_mtu" to intVal(packetMtu),
            "sent" to boolVal(sent),
            "rejected" to boolVal(rejected),
            "error" to (error?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "raw_len" to (rawLen?.let { intVal(it) } ?: JsonNull.INSTANCE),
        )
    }

    "wire_send_undecryptable" -> {
        // Adversarial: damage a genuine packed SINGLE DATA packet's final
        // ciphertext byte and transmit the corrupted bytes raw, so the receiver
        // can't decrypt (Token HMAC fails) and emits no proof. Mirrors
        // cmd_wire_send_undecryptable (wire_tcp.py:3410-3466). MUST use
        // transmitForTest, NOT send(): kotlin processOutbound always re-packs
        // (Transport.kt) and would overwrite the damage with fresh ciphertext.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val appName = p.str("app_name")
        val aspects = p.get("aspects")?.asJsonArray?.map { it.asString }?.toTypedArray() ?: emptyArray()
        val payload = p.get("data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: ByteArray(0)
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val identity = Identity.recall(destHash)
            ?: throw IllegalStateException(
                "No identity known for ${destHash.toHex()}; ensure an announce for this destination was received first.",
            )
        val outDest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, appName, *aspects)
        val packet = Packet.create(outDest, payload, createReceipt = true)
        packet.pack()
        val raw = packet.raw!!.copyOf()
        raw[raw.size - 1] = ((raw[raw.size - 1].toInt() + 1) and 0xFF).toByte()
        val ifaceRef = (
            inst.serverIface ?: inst.clientIface
                ?: throw IllegalStateException("No live interface for handle $handle")
        ).toRef()
        Transport.transmitForTest(ifaceRef, raw)
        val receiptId = freshReceiptId()
        inst.receipts[receiptId] = PacketReceipt.forPacketForTest(packet)
        packet.sent = true
        inst.destinations.add(identity to outDest)
        result("sent" to boolVal(true), "receipt_id" to strVal(receiptId))
    }

    "wire_inject_raw_frame" -> {
        // Push a genuine (optionally IFAC-masked / trimmed) announce through a live
        // interface's Transport.inbound to drive the four pre-unpack drop guards,
        // reporting whether a path was learned. Mirrors cmd_wire_inject_raw_frame
        // (wire_tcp.py:9871-9982). A wire handle hosts exactly one interface.
        val handle = p.str("handle")
        val variant = p.str("variant")
        val appName = p.get("app_name")?.asString ?: "rawframe"
        val aspects = p.get("aspects")?.asJsonArray?.map { it.asString }?.toTypedArray() ?: arrayOf("inject")
        val appData = p.get("app_data")?.asString?.takeIf { it.isNotEmpty() }?.fromHex() ?: "probe".toByteArray()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val iface = inst.serverIface ?: inst.clientIface
            ?: throw IllegalStateException("No live interface for handle $handle")
        val ref = iface.toRef()
        val ifacRef = if (ref.ifacIdentity != null) ref else null
        val openRef = if (ref.ifacIdentity == null) ref else null

        when (variant) {
            "inject_external" -> {
                val target = openRef
                    ?: throw IllegalStateException("inject_external requires a non-IFAC (open) interface")
                val frame = p.hex("raw")
                val dh = p.hex("dest_hash")
                Transport.inbound(frame, target)
                result(
                    "dest_hash" to hexVal(dh),
                    "frame_len" to intVal(frame.size),
                    "learned" to boolVal(Transport.hasPath(dh)),
                )
            }
            "build_masked" -> {
                val target = ifacRef
                    ?: throw IllegalStateException("build_masked requires an IFAC-configured interface")
                val (dest, plainRaw) = buildForeignAnnounce(inst, appName, aspects, appData)
                val maskedRaw = Transport.applyIfacMaskingForTest(plainRaw, target)
                result(
                    "dest_hash" to hexVal(dest.hash),
                    "raw" to hexVal(maskedRaw),
                    "frame_len" to intVal(maskedRaw.size),
                    "ifac_size" to intVal(target.ifacSize),
                )
            }
            else -> {
                val target: network.reticulum.transport.InterfaceRef
                val frame: ByteArray
                val dest: Destination
                when (variant) {
                    "masked_full", "masked_short", "plain_on_ifac" -> {
                        target = ifacRef
                            ?: throw IllegalStateException("variant $variant requires an IFAC interface")
                        val (d, plainRaw) = buildForeignAnnounce(inst, appName, aspects, appData)
                        dest = d
                        frame = when (variant) {
                            "plain_on_ifac" -> plainRaw
                            "masked_full" -> Transport.applyIfacMaskingForTest(plainRaw, target)
                            else -> Transport.applyIfacMaskingForTest(plainRaw, target).copyOf(2 + target.ifacSize)
                        }
                    }
                    "plain_on_open" -> {
                        target = openRef
                            ?: throw IllegalStateException("plain_on_open requires an open interface")
                        val (d, plainRaw) = buildForeignAnnounce(inst, appName, aspects, appData)
                        dest = d
                        frame = plainRaw
                    }
                    "min_short" -> {
                        target = openRef ?: ifacRef
                            ?: throw IllegalStateException("min_short requires any live interface")
                        val trimTo = p.get("trim_to")?.asInt ?: 2
                        val (d, plainRaw) = buildForeignAnnounce(inst, appName, aspects, appData)
                        dest = d
                        frame = plainRaw.copyOf(trimTo)
                    }
                    else -> throw IllegalArgumentException("unknown variant: $variant")
                }
                Transport.inbound(frame, target)
                val out = mutableListOf<Pair<String, JsonElement>>(
                    "dest_hash" to hexVal(dest.hash),
                    "frame_len" to intVal(frame.size),
                    "learned" to boolVal(Transport.hasPath(dest.hash)),
                )
                if (ifacRef != null && target === ifacRef) {
                    out.add("ifac_size" to intVal(ifacRef.ifacSize))
                }
                result(*out.toTypedArray())
            }
        }
    }

    "wire_inject_single_proof_format" -> {
        // Self-contained single-packet (non-link) PROOF FORMAT injector against a
        // receipt this command CONTROLS, so it can sign a genuinely-valid proof.
        // Mirrors cmd_wire_inject_single_proof_format (wire_tcp.py:9571-9644).
        // Routes through validateProof (NOT validateLinkProof) so the implicit
        // form is honored for non-link receipts.
        val handle = p.str("handle")
        val variant = p.str("variant")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = defaultCryptoProvider()
        val identity = Identity.create()
        val outDest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "proof-format")
        val base = Packet.create(outDest, crypto.randomBytes(20), createReceipt = true)
        base.pack()
        val receipt = PacketReceipt.forPacketForTest(base)
        val hashLen = RnsConstants.FULL_HASH_BYTES
        val proof: ByteArray = when (variant) {
            "valid_explicit" -> receipt.hash + identity.sign(receipt.hash)
            "valid_implicit" -> identity.sign(receipt.hash)
            "forged_explicit" -> receipt.hash + Identity.create().sign(receipt.hash)
            "wrong_hash_explicit" -> crypto.randomBytes(hashLen) + identity.sign(receipt.hash)
            else -> throw IllegalArgumentException("unknown single-proof-format variant: $variant")
        }
        val validated = runCatching { receipt.validateProof(proof) }.getOrDefault(false)
        val status = receipt.status
        result(
            "variant" to strVal(variant),
            "validated" to boolVal(validated),
            "status" to intVal(status),
            "status_name" to (receiptStatusName(status)?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "proof_len" to intVal(proof.size),
            "expl_length" to intVal(PacketReceipt.EXPL_LENGTH),
            "impl_length" to intVal(PacketReceipt.IMPL_LENGTH),
        )
    }

    "wire_inject_crafted_proof" -> {
        // Adversarial single-packet PROOF injector against a PENDING receipt from a
        // prior wire_send_packet*. Mirrors cmd_wire_inject_crafted_proof
        // (wire_tcp.py:6188-6260). Every variant is a REJECTION case run through
        // the real validateProof gate; none needs the receiver key.
        val handle = p.str("handle")
        val receiptId = p.str("receipt_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val receipt = inst.receipts[receiptId]
            ?: throw IllegalArgumentException("Unknown receipt_id: $receiptId")
        val crypto = defaultCryptoProvider()
        val provenHash = receipt.hash
        val hashLen = RnsConstants.FULL_HASH_BYTES
        val sigLen = RnsConstants.SIGNATURE_SIZE
        val proof: ByteArray = when (variant) {
            "forged_implicit" -> Identity.create().sign(provenHash)
            "forged_explicit" -> provenHash + Identity.create().sign(provenHash)
            "wrong_hash_explicit" -> crypto.randomBytes(hashLen) + Identity.create().sign(provenHash)
            "wrong_length_short" -> crypto.randomBytes(hashLen)
            "wrong_length_mid" -> crypto.randomBytes(sigLen + 1)
            "wrong_length_long" -> crypto.randomBytes(hashLen + sigLen + 1)
            else -> throw IllegalArgumentException("unknown proof variant: $variant")
        }
        val validated = runCatching { receipt.validateProof(proof) }.getOrDefault(false)
        val status = receipt.status
        result(
            "variant" to strVal(variant),
            "validated" to boolVal(validated),
            "status" to intVal(status),
            "status_name" to (receiptStatusName(status)?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "proved" to boolVal(receipt.proved),
            "proof_len" to intVal(proof.size),
        )
    }

    "wire_packet_receipt_generation" -> {
        // Report whether RNS creates a PacketReceipt for a packet of a given
        // dest-type/context/packet-type, even with create_receipt=True. Mirrors
        // cmd_wire_packet_receipt_generation (wire_tcp.py:9647-9713). MUST use
        // Packet.create(destination, ...) so processOutbound's PLAIN gate keys on
        // the real destination type, not null.
        val handle = p.str("handle")
        val destType = (p.get("dest_type")?.asString ?: "single").lowercase()
        val context = p.get("context")?.asInt ?: PacketContext.NONE.value
        val packetTypeInt = p.get("packet_type")?.asInt ?: PacketType.DATA.value
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = defaultCryptoProvider()
        val destination = when (destType) {
            "single" -> Destination.create(Identity.create(), DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "receipt-gen")
            "plain" -> Destination.create(null, DestinationDirection.OUT, DestinationType.PLAIN, "conformance", "receipt-gen")
            else -> throw IllegalArgumentException("dest_type must be 'single' or 'plain' (got $destType)")
        }
        val packet = Packet.create(
            destination,
            crypto.randomBytes(12),
            packetType = PacketType.fromValue(packetTypeInt),
            context = PacketContext.fromValue(context),
            createReceipt = true,
        )
        runCatching { packet.send() }
        val deadline = System.currentTimeMillis() + 1000
        while (!packet.sent && System.currentTimeMillis() < deadline) Thread.sleep(20)
        result(
            "dest_type" to strVal(destType),
            "context" to intVal(context),
            "packet_type" to intVal(packetTypeInt),
            "sent" to boolVal(packet.sent),
            "has_receipt" to boolVal(packet.receipt != null),
            "create_receipt_flag" to boolVal(packet.createReceipt),
        )
    }

    "wire_packet_receipt_status" -> {
        // Poll a tracked receipt until it concludes or timeout. Mirrors
        // cmd_wire_packet_receipt_status (wire_tcp.py:3472-3515).
        val handle = p.str("handle")
        val receiptId = p.str("receipt_id")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 0
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val receipt = inst.receipts[receiptId]
            ?: throw IllegalArgumentException("Unknown receipt_id: $receiptId")
        val terminal = setOf(PacketReceipt.DELIVERED, PacketReceipt.FAILED, PacketReceipt.CULLED)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (receipt.status !in terminal && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        val status = receipt.status
        result(
            "status" to intVal(status),
            "status_name" to (receiptStatusName(status)?.let { strVal(it) } ?: JsonNull.INSTANCE),
            "delivered" to boolVal(status == PacketReceipt.DELIVERED),
            "proved" to boolVal(receipt.proved),
        )
    }

    "wire_packet_receipt_timeout" -> {
        // Build a real PacketReceipt for a fresh path-less SINGLE destination and
        // report its computed .timeout plus the constituents, then optionally force
        // a timeout and run checkTimeout. Mirrors cmd_wire_packet_receipt_timeout
        // (wire_tcp.py:9716-9783). first_hop_timeout is ms -> seconds at the boundary.
        val handle = p.str("handle")
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val crypto = defaultCryptoProvider()
        val identity = Identity.create()
        val dest = Destination.create(identity, DestinationDirection.OUT, DestinationType.SINGLE, "conformance", "receipt-timeout")
        val packet = Packet.create(dest, crypto.randomBytes(12), createReceipt = true)
        packet.pack()
        val receipt = PacketReceipt.forPacketForTest(packet)
        val instance = Reticulum.getInstance()
        val firstHopMs = instance.getFirstHopTimeout(dest.hash)
        val out = mutableListOf<Pair<String, JsonElement>>(
            "timeout" to doubleVal(receipt.timeout),
            "status" to intVal(receipt.status),
            "is_link" to boolVal(dest.type == DestinationType.LINK),
            "default_per_hop_timeout" to intVal(Reticulum.DEFAULT_PER_HOP_TIMEOUT),
            // kotlin has no Packet.TIMEOUT_PER_HOP; RNS defines it == DEFAULT_PER_HOP_TIMEOUT.
            "timeout_per_hop" to intVal(Reticulum.DEFAULT_PER_HOP_TIMEOUT),
            "pathfinder_m" to intVal(TransportConstants.PATHFINDER_M),
            "hops_to" to intVal(Transport.hopsTo(dest.hash) ?: TransportConstants.PATHFINDER_M),
            "first_hop_timeout" to doubleVal(firstHopMs / 1000.0),
            "is_connected_to_shared" to boolVal(instance.isConnectedToSharedInstance),
            "status_sent" to intVal(PacketReceipt.SENT),
        )
        val forceEl = p.get("force_timeout")
        if (forceEl != null && !forceEl.isJsonNull) {
            receipt.setTimeout(forceEl.asDouble)
            receipt.setSentAtForTest(receipt.sentAt - 100_000_000L)
            receipt.checkTimeout()
            out.add("forced_timeout" to doubleVal(receipt.timeout))
            out.add("forced_status" to intVal(receipt.status))
            out.add("status_culled" to intVal(PacketReceipt.CULLED))
            out.add("status_failed" to intVal(PacketReceipt.FAILED))
        }
        result(*out.toTypedArray())
    }

    "wire_set_proof_strategy" -> {
        // Set a listening destination's proof strategy. Mirrors
        // cmd_wire_set_proof_strategy (wire_tcp.py:3178-3231). For "app", install a
        // proof_requested callback that proves iff the decrypted payload[0]==0x01.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val strategy = p.str("strategy").lowercase()
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val destination = findDestinationByHash(inst, destHash)
            ?: throw IllegalArgumentException(
                "No registered destination with hash ${destHash.toHex()} on handle $handle; call wire_listen first.",
            )
        val const = when (strategy) {
            "all" -> Destination.PROVE_ALL
            "app" -> Destination.PROVE_APP
            "none" -> Destination.PROVE_NONE
            else -> throw IllegalArgumentException("strategy must be one of [all, app, none] (got $strategy)")
        }
        destination.setProofStrategy(const)
        if (strategy == "app") {
            val listener = inst.listeners[destHash.toHex()]
            destination.setProofRequestedCallback { pktAny ->
                val pkt = pktAny as? Packet ?: return@setProofRequestedCallback false
                listener?.inboundLinks?.any { link ->
                    val pt = runCatching { link.decrypt(pkt.data) }.getOrNull()
                    pt != null && pt.isNotEmpty() && pt[0] == 0x01.toByte()
                } ?: false
            }
        }
        result(
            "strategy" to strVal(strategy),
            "proof_strategy" to intVal(destination.getProofStrategy()),
        )
    }

    "wire_set_proof_implicit" -> {
        // Toggle the process-wide implicit-vs-explicit single-packet PROOF policy.
        // Mirrors cmd_wire_set_proof_implicit (wire_tcp.py:5855-5872). Set on the
        // PROVER before the proof is requested; Identity.prove branches on it.
        val handle = p.str("handle")
        val enabled = p.get("enabled")?.asBoolean ?: true
        wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        Reticulum.setUseImplicitProofForTest(enabled)
        result("implicit_proof" to boolVal(Reticulum.shouldUseImplicitProof()))
    }

    "wire_listener_proof_log" -> {
        // Return the receiver-side proof log for a listening destination. Mirrors
        // cmd_wire_listener_proof_log (wire_tcp.py:4951-4979). The log is filled by
        // the Link.proveTapForTest tap installed in wire_listen.
        val handle = p.str("handle")
        val destHash = p.hex("destination_hash")
        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")
        val listener = inst.listeners[destHash.toHex()]
            ?: throw IllegalArgumentException("No listener registered for destination_hash=${destHash.toHex()}")
        val channelCtx = PacketContext.CHANNEL.value
        val contexts = listener.proofLog.toList()
        val arr = JsonArray()
        contexts.forEach { arr.add(JsonPrimitive(it)) }
        result(
            "contexts" to arr,
            "channel_proofs" to intVal(contexts.count { it == channelCtx }),
            "channel_context" to intVal(channelCtx),
        )
    }

    // ===== Phase 5d: Resource commands =====

    "wire_resource_create" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val payload = p.hexOpt("data") ?: ByteArray(0)
        val forceSdu = p.get("force_sdu")?.asInt
        val includeParts = p.get("include_parts")?.asBoolean ?: true
        val autoCompress = p.get("auto_compress")?.asBoolean ?: true
        val metadata = p.hexOpt("metadata")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val savedMtu = link.mtu
        if (forceSdu != null) {
            link.setMtuForTest(forceSdu + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
        }
        val resource = try {
            Resource.create(payload, link, metadata = metadata, advertise = false, autoCompress = autoCompress)
        } finally {
            if (forceSdu != null) link.setMtuForTest(savedMtu)
        }
        val adv = ResourceAdvertisement.fromResource(resource)
        val pairs = mutableListOf<Pair<String, com.google.gson.JsonElement>>(
            "hash" to hexVal(resource.hash),
            // kotlin has no truncatedHash field; python truncated = full_hash[:16]
            // (TRUNCATED_HASHLENGTH//8 == 16).
            "truncated_hash" to hexVal(resource.hash.copyOf(16)),
            "random_hash" to hexVal(resource.randomHash),
            "expected_proof" to (resource.expectedProofForTest()?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            "hashmap" to hexVal(resource.hashmapRaw),
            "num_parts" to intVal(resource.parts.size),
            "encrypted" to boolVal(resource.encrypted),
            "compressed" to boolVal(resource.compressed),
            "split" to boolVal(resource.split),
            "total_segments" to intVal(resource.totalSegments),
            "segment_index" to intVal(resource.segmentIndex),
            "original_hash" to hexVal(resource.originalHash),
            "has_metadata" to boolVal(resource.hasMetadata),
            "flags" to intVal(adv.flags),
            "size" to intVal(resource.size),
            "total_size" to intVal(resource.totalSize),
            "sdu" to intVal(resource.sduForTest()),
        )
        if (includeParts) {
            val arr = JsonArray()
            resource.parts.forEach { arr.add((it ?: ByteArray(0)).toHex()) }
            pairs.add("parts" to arr)
        }
        result(*pairs.toTypedArray())
    }

    "wire_resource_constants" -> {
        result(
            "WINDOW" to intVal(ResourceConstants.WINDOW),
            "WINDOW_MIN" to intVal(ResourceConstants.WINDOW_MIN),
            "WINDOW_MAX" to intVal(ResourceConstants.WINDOW_MAX),
            "MAPHASH_LEN" to intVal(ResourceConstants.MAPHASH_LEN),
            "RANDOM_HASH_SIZE" to intVal(ResourceConstants.RANDOM_HASH_SIZE),
            "HASHMAP_MAX_LEN" to intVal(ResourceAdvertisement.HASHMAP_MAX_LEN),
            "COLLISION_GUARD_SIZE" to intVal(ResourceAdvertisement.COLLISION_GUARD_SIZE),
            "MAX_EFFICIENT_SIZE" to intVal(ResourceConstants.MAX_EFFICIENT_SIZE),
            "METADATA_MAX_SIZE" to intVal(ResourceConstants.METADATA_MAX_SIZE),
            "MAX_RETRIES" to intVal(ResourceConstants.MAX_RETRIES),
            "MAX_ADV_RETRIES" to intVal(ResourceConstants.MAX_ADV_RETRIES),
            "HASHMAP_IS_EXHAUSTED" to intVal(ResourceConstants.HASHMAP_IS_EXHAUSTED),
            "HASHMAP_IS_NOT_EXHAUSTED" to intVal(ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED),
            "WINDOW_MAX_SLOW" to intVal(ResourceConstants.WINDOW_MAX_SLOW),
            "WINDOW_MAX_VERY_SLOW" to intVal(ResourceConstants.WINDOW_MAX_VERY_SLOW),
            "WINDOW_MAX_FAST" to intVal(ResourceConstants.WINDOW_MAX_FAST),
            "WINDOW_FLEXIBILITY" to intVal(ResourceConstants.WINDOW_FLEXIBILITY),
            "FAST_RATE_THRESHOLD" to intVal(ResourceConstants.FAST_RATE_THRESHOLD),
            "VERY_SLOW_RATE_THRESHOLD" to intVal(ResourceConstants.VERY_SLOW_RATE_THRESHOLD),
            "RATE_FAST" to doubleVal(ResourceConstants.RATE_FAST.toDouble()),
            "RATE_VERY_SLOW" to doubleVal(ResourceConstants.RATE_VERY_SLOW.toDouble()),
            "PART_TIMEOUT_FACTOR" to intVal(ResourceConstants.PART_TIMEOUT_FACTOR),
            "PART_TIMEOUT_FACTOR_AFTER_RTT" to intVal(ResourceConstants.PART_TIMEOUT_FACTOR_AFTER_RTT),
            "PROOF_TIMEOUT_FACTOR" to intVal(ResourceConstants.PROOF_TIMEOUT_FACTOR),
            "AUTO_COMPRESS_MAX_SIZE" to intVal(ResourceConstants.AUTO_COMPRESS_MAX_SIZE),
        )
    }

    "wire_resource_decompress_limit" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val sender = Resource.create(randomBytes(500), link, advertise = false)
        result(
            "max_decompressed_size" to intVal(sender.maxDecompressedSizeForTest()),
            "auto_compress_limit" to intVal(sender.autoCompressLimitForTest()),
            "constant" to intVal(ResourceConstants.AUTO_COMPRESS_MAX_SIZE),
        )
    }

    "wire_resource_progress" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = buildResourceReceiver(link, 1500, forceSdu = 200)
        val total = receiver.parts.size
        if (total < 4) throw IllegalStateException("need a multi-part transfer, got $total")
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        receiver.callbacks.progress = { counter.incrementAndGet() }
        val progressInitial = receiver.progress.toDouble()
        val fed = total / 2
        receiver.setAutoRequestNextForTest(false)
        for (i in 0 until fed) receiver.receivePart(sender.parts[i]!!)
        receiver.setAutoRequestNextForTest(true)
        val out = result(
            "total_parts" to intVal(total),
            "fed" to intVal(fed),
            "received_count" to intVal(receiver.receivedCountForTest()),
            "progress_initial" to doubleVal(progressInitial),
            "progress_mid" to doubleVal(receiver.progress.toDouble()),
            "progress_callback_calls" to intVal(counter.get()),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_resource_receiver_proof_count" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = buildResourceReceiver(link, 1200, forceSdu = 200)
        val total = receiver.parts.size
        if (total < 2) throw IllegalStateException("need a multi-part transfer, got $total")
        for (i in 0 until total - 1) receiver.receivePart(sender.parts[i]!!)
        val proofsBefore = receiver.proveCallCountForTest()
        // Last part triggers assemble() synchronously inside receivePart.
        receiver.receivePart(sender.parts[total - 1]!!)
        val status = receiver.status
        val out = result(
            "total_parts" to intVal(total),
            "proofs_before_final" to intVal(proofsBefore),
            "proofs_after_assembly" to intVal(receiver.proveCallCountForTest()),
            "status_name" to strVal(ResourceConstants.statusDescription(status)),
            "complete" to boolVal(status == ResourceConstants.COMPLETE),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_resource_receiver_request_state" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val n = p.get("n")?.asInt ?: 2
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = buildResourceReceiver(link, 1500, forceSdu = 200)
        val total = receiver.parts.size
        val windowInit = receiver.windowForTest()
        val windowMin = receiver.windowMinForTest()
        val windowMax = receiver.windowMaxForTest()
        val cchInit = receiver.consecutiveCompletedHeightForTest()
        val hmHeightInit = receiver.hashmapHeightForTest()
        val waitingInit = receiver.waitingForHmuForTest()
        val fed = minOf(n, total)
        for (i in 0 until fed) receiver.receivePart(sender.parts[i]!!)
        val out = result(
            "window" to intVal(windowInit),
            "window_min" to intVal(windowMin),
            "window_max" to intVal(windowMax),
            "total_parts" to intVal(total),
            "consecutive_height_initial" to intVal(cchInit),
            "hashmap_height_initial" to intVal(hmHeightInit),
            "waiting_for_hmu_initial" to boolVal(waitingInit),
            "fed" to intVal(fed),
            "received_count" to intVal(receiver.receivedCountForTest()),
            "consecutive_height_after" to intVal(receiver.consecutiveCompletedHeightForTest()),
            "hashmap_height_after" to intVal(receiver.hashmapHeightForTest()),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_resource_request_next_content" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = if (variant == "exhausted") {
            buildResourceReceiver(link, 14000, forceSdu = 150)
        } else {
            buildResourceReceiver(link, 1500, forceSdu = 200)
        }
        val segLen = ResourceAdvertisement.HASHMAP_MAX_LEN
        val feed = when (variant) {
            "initial" -> 0
            "after_parts" -> 2
            "exhausted" -> segLen - 1
            else -> throw IllegalArgumentException("unknown request_next variant: $variant")
        }
        if (variant == "exhausted" && receiver.parts.size <= segLen) {
            throw IllegalStateException("need a multi-segment transfer (>$segLen parts), got ${receiver.parts.size}")
        }
        // Suppress the internal follow-up request during the feed so only the
        // explicit requestNext below is observed (mirrors the reference shadowing
        // receiver.request_next during the feed).
        receiver.setAutoRequestNextForTest(false)
        for (i in 0 until feed) receiver.receivePart(sender.parts[i]!!)
        receiver.setAutoRequestNextForTest(true)

        val window = receiver.windowForTest()
        val cch = receiver.consecutiveCompletedHeightForTest()
        // expected hashes: hashmap[cch+1 ..] up to window, stopping at first null.
        val entries = receiver.hashmapEntriesForTest()
        val expected = JsonArray()
        run {
            var pn = cch + 1
            while (pn < entries.size && expected.size() < window) {
                val h = entries[pn] ?: break
                expected.add(h.toHex())
                pn++
            }
        }

        val emitBefore = receiver.requestNextEmitCountForTest()
        receiver.requestNextForTest()
        val emitAfterFirst = receiver.requestNextEmitCountForTest()
        val waiting = receiver.waitingForHmuForTest()
        val req = receiver.lastRequestDataForTest()
            ?: throw IllegalStateException("requestNext emitted no request data")
        val flag = req[0].toInt() and 0xFF
        val isExhausted = flag == ResourceConstants.HASHMAP_IS_EXHAUSTED
        val pad = 1 + (if (isExhausted) ResourceConstants.MAPHASH_LEN else 0) + ResourceConstants.RESOURCE_HASH_LEN
        val requested = JsonArray()
        run {
            var i = pad
            while (i + ResourceConstants.MAPHASH_LEN <= req.size) {
                requested.add(req.copyOfRange(i, i + ResourceConstants.MAPHASH_LEN).toHex())
                i += ResourceConstants.MAPHASH_LEN
            }
        }
        receiver.requestNextForTest()
        val secondEmitted = receiver.requestNextEmitCountForTest() > emitAfterFirst
        val out = result(
            "variant" to strVal(variant),
            "window" to intVal(window),
            "total_parts" to intVal(receiver.parts.size),
            "hashmap_height" to intVal(receiver.hashmapHeightForTest()),
            "consecutive_height" to intVal(cch),
            "requested" to requested,
            "expected" to expected,
            "exhausted" to boolVal(isExhausted),
            "waiting_for_hmu" to boolVal(waiting),
            "second_request_emitted" to boolVal(secondEmitted),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_resource_part_count_derivation" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val savedMtu = link.mtu
        link.setMtuForTest(200 + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
        Resource.watchdogDisabledForTest = true
        val out: JsonObject
        try {
            val sender = Resource.create(randomBytes(1500), link, advertise = false)
            val adv = ResourceAdvertisement.fromResource(sender)
            val advNGenuine = adv.numParts
            val transferSize = adv.transferSize
            adv.numParts = advNGenuine + 5  // tamper ONLY the part-count field
            val receiver = Resource.accept(adv, link)
                ?: throw IllegalStateException("Resource.accept did not produce a receiver")
            val receiverSdu = receiver.sduForTest()
            val derivedExpected = ceilDiv(transferSize, receiverSdu)
            out = result(
                "sender_parts" to intVal(sender.parts.size),
                "adv_n_genuine" to intVal(advNGenuine),
                "adv_n_tampered" to intVal(advNGenuine + 5),
                "transfer_size" to intVal(transferSize),
                "receiver_sdu" to intVal(receiverSdu),
                "receiver_total_parts" to intVal(receiver.parts.size),
                "derived_expected" to intVal(derivedExpected),
            )
            runCatching { receiver.cancel() }
        } finally {
            Resource.watchdogDisabledForTest = false
            link.setMtuForTest(savedMtu)
        }
        out
    }

    "wire_resource_window_inheritance" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender1, receiver1) = buildResourceReceiver(link, 1500, forceSdu = 150)
        val total1 = receiver1.parts.size
        if (total1 <= ResourceConstants.WINDOW + 1) {
            throw IllegalStateException("need >${ResourceConstants.WINDOW + 1} parts, got $total1")
        }
        for (i in 0 until total1) receiver1.receivePart(sender1.parts[i]!!)
        val windowAfterComplete = receiver1.windowForTest()
        val completed1 = receiver1.status == ResourceConstants.COMPLETE
        val linkLastWindow = link.getLastResourceWindow()
        val (_, receiver2) = buildResourceReceiver(link, 1500, forceSdu = 150)
        val window2Initial = receiver2.windowForTest()
        val out = result(
            "default_window" to intVal(ResourceConstants.WINDOW),
            "total_parts_1" to intVal(total1),
            "completed_1" to boolVal(completed1),
            "window_after_complete" to intVal(windowAfterComplete),
            "link_last_window" to (linkLastWindow?.let { intVal(it) } ?: JsonNull.INSTANCE),
            "window2_initial" to intVal(window2Initial),
        )
        runCatching { receiver2.cancel() }
        out
    }

    "wire_resource_late_after_cancel" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val segLen = ResourceAdvertisement.HASHMAP_MAX_LEN
        Resource.watchdogDisabledForTest = true
        val out: JsonObject
        try {
            // RECEIVER path
            val (rsender, receiver) = buildResourceReceiver(link, 12000, forceSdu = 150)
            if (receiver.parts.size <= segLen) throw IllegalStateException("need >$segLen parts")
            receiver.cancel()
            val recvStatus = ResourceConstants.statusDescription(receiver.status)
            val recvReceivedBefore = receiver.receivedCountForTest()
            val recvHeightBefore = receiver.hashmapHeightForTest()
            receiver.receivePart(rsender.parts[0]!!)
            val recvReceivedAfter = receiver.receivedCountForTest()
            val start = segLen * ResourceConstants.MAPHASH_LEN
            val end = minOf(2 * segLen, rsender.parts.size) * ResourceConstants.MAPHASH_LEN
            receiver.hashmapUpdateForTest(1, rsender.hashmapRaw.copyOfRange(start, end))
            val recvHeightAfter = receiver.hashmapHeightForTest()

            // SENDER path
            val savedMtu = link.mtu
            link.setMtuForTest(200 + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
            val sender = try { Resource.create(randomBytes(1500), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
            sender.primeTransferringForTest()
            sender.cancel()
            val sendStatus = ResourceConstants.statusDescription(sender.status)
            val sendSentBefore = sender.sentPartsForTest()
            val reqData = byteArrayOf(ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED.toByte()) + sender.hash + sender.hashmapRaw
            sender.handleRequest(reqData)
            val sendSentAfter = sender.sentPartsForTest()
            val proof = randomBytes(32) + (sender.expectedProofForTest() ?: ByteArray(32))
            sender.validateProof(proof)
            val sendStatusAfterProof = ResourceConstants.statusDescription(sender.status)

            // CONTROL: a fresh sender accepts the same proof shape pre-cancel.
            link.setMtuForTest(200 + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
            val ctl = try { Resource.create(randomBytes(1500), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
            ctl.setStatusForTest(ResourceConstants.AWAITING_PROOF)
            val ctlProof = randomBytes(32) + (ctl.expectedProofForTest() ?: ByteArray(32))
            ctl.validateProof(ctlProof)
            val ctlStatus = ResourceConstants.statusDescription(ctl.status)

            out = result(
                "receiver_status" to strVal(recvStatus),
                "receiver_received_before" to intVal(recvReceivedBefore),
                "receiver_received_after_late_part" to intVal(recvReceivedAfter),
                "receiver_height_before" to intVal(recvHeightBefore),
                "receiver_height_after_late_hmu" to intVal(recvHeightAfter),
                "sender_status" to strVal(sendStatus),
                "sender_sent_before" to intVal(sendSentBefore),
                "sender_sent_after_late_request" to intVal(sendSentAfter),
                "sender_status_after_late_proof" to strVal(sendStatusAfterProof),
                "control_status_after_proof" to strVal(ctlStatus),
            )
            runCatching { receiver.cancel() }
            runCatching { sender.cancel() }
            runCatching { ctl.cancel() }
        } finally {
            Resource.watchdogDisabledForTest = false
        }
        out
    }

    "wire_resource_outgoing_queue_state" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 5000
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val readyEmpty = link.readyForNewResource()
        val first = Resource.create(randomBytes(800), link, advertise = false)
        link.registerOutgoingResource(first)
        val readyWithOne = link.readyForNewResource()
        val second = Resource.create(randomBytes(800), link, advertise = true)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (second.status == ResourceConstants.QUEUED) break
            Thread.sleep(20)
        }
        val out = result(
            "ready_empty" to boolVal(readyEmpty),
            "ready_with_one" to boolVal(readyWithOne),
            "first_status" to intVal(first.status),
            "first_status_name" to strVal(ResourceConstants.statusDescription(first.status)),
            "second_status" to intVal(second.status),
            "second_status_name" to strVal(ResourceConstants.statusDescription(second.status)),
            "queued" to boolVal(second.status == ResourceConstants.QUEUED),
        )
        runCatching { second.cancel() }
        runCatching { link.cancelOutgoingResource(first) }
        out
    }

    "wire_resource_force_collision" -> {
        // BLOCKED (reticulum-kt#resource-collision-guard): kotlin
        // initializeForSending builds the hashmap in a SINGLE pass with no
        // collision_guard_list / random_hash regeneration / rebuild loop (cf.
        // python Resource.py:436-472). There is no remap path to drive, so this
        // reports the genuine (no-remap) observation; the test's remapped==True
        // assertion fails by design until the collision-guard loop is implemented.
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val savedMtu = link.mtu
        link.setMtuForTest(200 + network.reticulum.common.RnsConstants.HEADER_MAX_SIZE + network.reticulum.common.RnsConstants.IFAC_MIN_SIZE)
        val resource = try { Resource.create(randomBytes(4000), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
        result(
            "remapped" to boolVal(false),
            "random_hash_before" to hexVal(resource.randomHash),
            "random_hash_after" to hexVal(resource.randomHash),
            "hashmap_changed" to boolVal(false),
            "num_parts" to intVal(resource.parts.size),
        )
    }

    "wire_resource_cancel" -> {
        val handle = p.str("handle")
        val resourceId = p.str("resource_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val resource = inst.outResources[resourceId]
            ?: throw IllegalArgumentException("Unknown resource_id: $resourceId")
        val cancelled = runCatching { resource.cancel(); true }.getOrDefault(false)
        result(
            "cancelled" to boolVal(cancelled),
            "resource_id" to strVal(resourceId),
            "status" to intVal(resource.status),
        )
    }

    "wire_resource_send_bomb" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val requested = p.get("decompressed_size")?.asInt ?: (WIRE_RX_MAX_DECOMPRESSED + 1024 * 1024)
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 30000
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val floor = WIRE_RX_MAX_DECOMPRESSED + 1
        val ceil = minOf(WIRE_RX_MAX_DECOMPRESSED * 2, ResourceConstants.MAX_EFFICIENT_SIZE - 1)
        val crafted = maxOf(floor, minOf(requested, ceil))
        val payload = ByteArray(crafted)  // zeros: bz2-compresses tiny, inflates past the bound
        val done = CountDownLatch(1)
        val finalStatus = java.util.concurrent.atomic.AtomicInteger(-1)
        val resource = Resource.create(payload, link, callback = { r -> finalStatus.set(r.status); done.countDown() })
        val resourceId = randomBytes(8).toHex()
        inst.outResources[resourceId] = resource
        val finished = done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) runCatching { resource.cancel() }
        val status = finalStatus.get().takeIf { it >= 0 } ?: resource.status
        result(
            "success" to boolVal(status == ResourceConstants.COMPLETE),
            "status" to intVal(status),
            "size" to intVal(crafted),
            "resource_id" to strVal(resourceId),
        )
    }

    "wire_resource_receiver_status" -> {
        val handle = p.str("handle")
        val destHashHex = p.str("destination_hash")
        val timeoutMs = p.get("timeout_ms")?.asInt ?: 0
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val listener = inst.listeners[destHashHex]
            ?: throw IllegalArgumentException("No listener registered for destination_hash=$destHashHex")
        val terminal = setOf(ResourceConstants.COMPLETE, ResourceConstants.FAILED, ResourceConstants.CORRUPT)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val last = listener.incomingResources.peekLast()
            if (last != null) {
                if (last.status in terminal || System.currentTimeMillis() >= deadline) break
            } else if (System.currentTimeMillis() >= deadline) {
                break
            }
            Thread.sleep(50)
        }
        val recs = listener.incomingResources.toList()
        if (recs.isEmpty()) {
            result("found" to boolVal(false), "resource_count" to intVal(0))
        } else {
            val resource = recs.last()
            val status = resource.status
            val metadataBytes = resource.metadataBytes
            val dataBytes = resource.data
            result(
                "found" to boolVal(true),
                "resource_count" to intVal(recs.size),
                "status" to intVal(status),
                "status_name" to strVal(ResourceConstants.statusDescription(status)),
                "corrupt" to boolVal(status == ResourceConstants.CORRUPT),
                "hmu_requests_sent" to intVal(resource.hmuRequestsSentForTest()),
                "hashmap_updates_received" to intVal(resource.hashmapUpdatesReceivedForTest()),
                "hashmap_height" to intVal(resource.hashmapHeightForTest()),
                "max_decompressed_size" to intVal(resource.maxDecompressedSizeForTest()),
                "compressed" to boolVal(resource.compressed),
                "has_metadata" to boolVal(resource.hasMetadata),
                "metadata" to (metadataBytes?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                "data" to (dataBytes?.let { hexVal(it) } ?: JsonNull.INSTANCE),
            )
        }
    }

    "wire_inject_corrupt_assembled_resource" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = buildResourceReceiver(link, 900, forceSdu = 200)
        for (i in receiver.parts.indices) receiver.setPartForTest(i, sender.parts[i]!!)
        when (variant) {
            "valid" -> {}
            "corrupt" -> {
                val j = receiver.parts.size / 2
                receiver.setPartForTest(j, randomBytes(sender.parts[j]!!.size))
            }
            else -> throw IllegalArgumentException("unknown corrupt-assemble variant: $variant")
        }
        receiver.assembleForTest()
        val status = receiver.status
        val out = result(
            "variant" to strVal(variant),
            "status" to intVal(status),
            "status_name" to strVal(ResourceConstants.statusDescription(status)),
            "complete" to boolVal(status == ResourceConstants.COMPLETE),
            "corrupt" to boolVal(status == ResourceConstants.CORRUPT),
            "proof_calls" to intVal(receiver.proveCallCountForTest()),
            "proof_sent" to boolVal(receiver.proveCallCountForTest() > 0),
            "total_parts" to intVal(receiver.parts.size),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_inject_crafted_resource_part" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        fun received(r: Resource) = r.parts.count { it != null }

        val out: JsonObject
        if (variant == "beyond_window" || variant == "duplicate_filled") {
            val (sender, receiver) = buildResourceReceiver(link, 1500, forceSdu = 200)
            val window = receiver.windowForTest()
            if (receiver.parts.size <= window + 1) {
                throw IllegalStateException("need >${window + 1} parts, got ${receiver.parts.size}")
            }
            var partsBefore = received(receiver)
            if (variant == "beyond_window") {
                receiver.receivePart(sender.parts[window]!!)
            } else {
                receiver.receivePart(sender.parts[0]!!)
                partsBefore = received(receiver)
                receiver.receivePart(sender.parts[0]!!)  // duplicate into filled slot
            }
            val partsAfter = received(receiver)
            out = result(
                "variant" to strVal(variant),
                "accepted" to boolVal(partsAfter > partsBefore),
                "parts_before" to intVal(partsBefore),
                "parts_after" to intVal(partsAfter),
                "total_parts" to intVal(receiver.parts.size),
                "window" to intVal(window),
                "received_count_after" to intVal(receiver.receivedCountForTest()),
            )
            runCatching { receiver.cancel() }
        } else {
            Resource.watchdogDisabledForTest = true
            try {
                val sender = Resource.create(randomBytes(2000), link, advertise = false)
                val receiver = Resource.accept(ResourceAdvertisement.fromResource(sender), link)
                    ?: throw IllegalStateException("Resource.accept did not produce a receiver")
                val partsBefore = received(receiver)
                val partData = when (variant) {
                    "valid" -> sender.parts[0]!!
                    "forged_map_hash" -> randomBytes(sender.parts[0]!!.size)
                    else -> throw IllegalArgumentException("unknown resource-part variant: $variant")
                }
                receiver.receivePart(partData)
                val partsAfter = received(receiver)
                out = result(
                    "variant" to strVal(variant),
                    "accepted" to boolVal(partsAfter > partsBefore),
                    "parts_before" to intVal(partsBefore),
                    "parts_after" to intVal(partsAfter),
                    "total_parts" to intVal(receiver.parts.size),
                )
                runCatching { receiver.cancel() }
            } finally {
                Resource.watchdogDisabledForTest = false
            }
        }
        out
    }

    "wire_inject_crafted_resource_proof" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val resource = Resource.create(randomBytes(200), link, advertise = false)
        val expected = resource.expectedProofForTest() ?: ByteArray(32)
        val proof = when (variant) {
            "valid" -> randomBytes(32) + expected
            "wrong_proof" -> randomBytes(32) + randomBytes(32)
            "wrong_length_short" -> randomBytes(32)
            "wrong_length_long" -> randomBytes(96)
            else -> throw IllegalArgumentException("unknown resource-proof variant: $variant")
        }
        runCatching { resource.validateProof(proof) }
        val status = resource.status
        val out = result(
            "variant" to strVal(variant),
            "concluded" to boolVal(status == ResourceConstants.COMPLETE),
            "status" to intVal(status),
            "status_name" to strVal(ResourceConstants.statusDescription(status)),
            "proof_len" to intVal(proof.size),
        )
        runCatching { resource.cancel() }
        out
    }

    "wire_inject_crafted_resource_request" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val overhead = network.reticulum.common.RnsConstants.HEADER_MAX_SIZE +
            network.reticulum.common.RnsConstants.IFAC_MIN_SIZE
        fun mapHashOf(s: Resource, i: Int): ByteArray =
            s.hashmapRaw.copyOfRange(i * ResourceConstants.MAPHASH_LEN, (i + 1) * ResourceConstants.MAPHASH_LEN)
        val exhausted = ResourceConstants.HASHMAP_IS_EXHAUSTED.toByte()
        val notExhausted = ResourceConstants.HASHMAP_IS_NOT_EXHAUSTED.toByte()

        val out: JsonObject = when (variant) {
            "misaligned_hmu", "aligned" -> {
                val savedMtu = link.mtu; link.setMtuForTest(50 + overhead)
                val sender = try { Resource.create(randomBytes(6000), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
                if (sender.parts.size < 74) throw IllegalStateException("need >=74 parts, got ${sender.parts.size}")
                sender.primeTransferringForTest()
                val partIndex = if (variant == "misaligned_hmu") 0 else 73
                val req = byteArrayOf(exhausted) + mapHashOf(sender, partIndex) + sender.hash
                sender.handleRequest(req)
                val r = result(
                    "variant" to strVal(variant),
                    "cancelled" to boolVal(sender.status == ResourceConstants.FAILED),
                    "status" to intVal(sender.status),
                    "status_name" to strVal(ResourceConstants.statusDescription(sender.status)),
                )
                runCatching { sender.cancel() }
                r
            }
            "aligned_scope" -> {
                val savedMtu = link.mtu; link.setMtuForTest(50 + overhead)
                val sender = try { Resource.create(randomBytes(9000), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
                if (sender.parts.size < 148) throw IllegalStateException("need >=148 parts, got ${sender.parts.size}")
                sender.primeTransferringForTest()
                val scopeBefore = sender.receiverMinConsecutiveHeightForTest()
                val req = byteArrayOf(exhausted) + mapHashOf(sender, 147) + sender.hash
                sender.handleRequest(req)
                val r = result(
                    "variant" to strVal(variant),
                    "part_index" to intVal(148),
                    "window_max" to intVal(ResourceConstants.WINDOW_MAX),
                    "scope_before" to intVal(scopeBefore),
                    "scope_after" to intVal(sender.receiverMinConsecutiveHeightForTest()),
                    "cancelled" to boolVal(sender.status == ResourceConstants.FAILED),
                    "status_name" to strVal(ResourceConstants.statusDescription(sender.status)),
                )
                runCatching { sender.cancel() }
                r
            }
            "serve_all" -> {
                val savedMtu = link.mtu; link.setMtuForTest(200 + overhead)
                val sender = try { Resource.create(randomBytes(1500), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
                sender.primeTransferringForTest()
                val req = byteArrayOf(notExhausted) + sender.hash + sender.hashmapRaw
                sender.handleRequest(req)
                val servedIndices = JsonArray()
                sender.sentPartIndicesForTest().forEach { servedIndices.add(it) }
                val sentParts = sender.sentPartsForTest()
                val statusName = ResourceConstants.statusDescription(sender.status)
                val before = sender.parts.map { it?.toHex() }
                sender.setStatusForTest(ResourceConstants.TRANSFERRING)
                sender.handleRequest(req)
                val after = sender.parts.map { it?.toHex() }
                val r = result(
                    "variant" to strVal(variant),
                    "served_indices" to servedIndices,
                    "sent_parts" to intVal(sentParts),
                    "total_parts" to intVal(sender.parts.size),
                    "identical_on_resend" to boolVal(before == after),
                    "status_name" to strVal(statusName),
                )
                runCatching { sender.cancel() }
                r
            }
            "duplicate" -> {
                // BLOCKED (reticulum-kt#resource-req-hashlist): kotlin
                // Link.processResourceReq has no req_hashlist packet-hash de-dup
                // (cf. Link.py:1109-1115). Drive the genuine behaviour and report
                // it honestly; first_in_hashlist / req_hashlist_len have no source.
                val savedMtu = link.mtu; link.setMtuForTest(200 + overhead)
                val sender = try { Resource.create(randomBytes(1500), link, advertise = false) } finally { link.setMtuForTest(savedMtu) }
                sender.primeTransferringForTest()
                link.registerOutgoingResource(sender)
                val reqData = byteArrayOf(notExhausted) + sender.hash + sender.hashmapRaw
                feedInboundLinkPacket(link, reqData, network.reticulum.common.PacketContext.RESOURCE_REQ)
                val firstServed = sender.sentPartsForTest()
                feedInboundLinkPacket(link, reqData, network.reticulum.common.PacketContext.RESOURCE_REQ)
                val secondServed = sender.sentPartsForTest()
                val r = result(
                    "variant" to strVal(variant),
                    "total_parts" to intVal(sender.parts.size),
                    "first_served" to intVal(firstServed),
                    "second_served" to intVal(secondServed),
                    "first_in_hashlist" to boolVal(false),
                    "req_hashlist_len" to intVal(0),
                    "deduped" to boolVal(false),
                )
                runCatching { link.cancelOutgoingResource(sender) }
                runCatching { sender.cancel() }
                r
            }
            else -> throw IllegalArgumentException("unknown resource-request variant: $variant")
        }
        out
    }

    "wire_inject_duplicate_resource_adv" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        Resource.watchdogDisabledForTest = true
        val out: JsonObject
        try {
            val sender = Resource.create(randomBytes(800), link, advertise = false)
            val adv = ResourceAdvertisement.fromResource(sender)
            fun countForHash() = link.incomingResourceHashesForTest().count { it.contentEquals(sender.hash) }
            val receiver1 = Resource.accept(adv, link)
            val firstAccepted = receiver1 != null
            val countAfterFirst = countForHash()
            val receiver2 = Resource.accept(adv, link)
            val secondCreated = receiver2 != null
            val incomingCount = countForHash()
            out = result(
                "first_accepted" to boolVal(firstAccepted),
                "second_created" to boolVal(secondCreated),
                "incoming_count" to intVal(incomingCount),
                "incoming_count_after_first" to intVal(countAfterFirst),
            )
            runCatching { receiver1?.cancel() }
            runCatching { receiver2?.cancel() }
        } finally {
            Resource.watchdogDisabledForTest = false
        }
        out
    }

    "wire_inject_hashmap_update" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        val (sender, receiver) = buildResourceReceiver(link, 12000, forceSdu = 150)
        val segLen = ResourceAdvertisement.HASHMAP_MAX_LEN
        if (sender.parts.size <= segLen) throw IllegalStateException("need >$segLen parts, got ${sender.parts.size}")
        val heightAfterAdvert = receiver.hashmapHeightForTest()
        val start = segLen * ResourceConstants.MAPHASH_LEN
        val end = minOf(2 * segLen, sender.parts.size) * ResourceConstants.MAPHASH_LEN
        val seg1 = sender.hashmapRaw.copyOfRange(start, end)
        receiver.hashmapUpdateForTest(1, seg1)
        val heightAfterFirst = receiver.hashmapHeightForTest()
        receiver.hashmapUpdateForTest(1, seg1)
        val heightAfterDuplicate = receiver.hashmapHeightForTest()
        val out = result(
            "total_parts" to intVal(sender.parts.size),
            "height_after_advert" to intVal(heightAfterAdvert),
            "height_after_first" to intVal(heightAfterFirst),
            "height_after_duplicate" to intVal(heightAfterDuplicate),
            "grew_on_first" to boolVal(heightAfterFirst > heightAfterAdvert),
            "grew_on_duplicate" to boolVal(heightAfterDuplicate > heightAfterFirst),
        )
        runCatching { receiver.cancel() }
        out
    }

    "wire_inject_malformed_resource_adv" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        Resource.watchdogDisabledForTest = true
        val out: JsonObject
        try {
            val plaintext: ByteArray = when (variant) {
                "garbage" -> byteArrayOf(0xC1.toByte()) + randomBytes(40)
                "missing_key" -> {
                    val sender = Resource.create(randomBytes(400), link, advertise = false)
                    val genuine = ResourceAdvertisement.fromResource(sender).pack()
                    // Re-pack the genuine msgpack map without the required "h" key.
                    val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(genuine)
                    val n = unpacker.unpackMapHeader()
                    val kept = ArrayList<Pair<org.msgpack.value.Value, org.msgpack.value.Value>>()
                    repeat(n) {
                        val k = unpacker.unpackValue()
                        val v = unpacker.unpackValue()
                        if (!(k.isStringValue && k.asStringValue().asString() == "h")) kept.add(k to v)
                    }
                    unpacker.close()
                    val bos = java.io.ByteArrayOutputStream()
                    val packer = org.msgpack.core.MessagePack.newDefaultPacker(bos)
                    packer.packMapHeader(kept.size)
                    for ((k, v) in kept) { packer.packValue(k); packer.packValue(v) }
                    packer.close()
                    runCatching { sender.cancel() }
                    bos.toByteArray()
                }
                else -> throw IllegalArgumentException("unknown malformed-adv variant: $variant")
            }
            val before = link.incomingResourceHashesForTest().size
            var crashed = false
            var receiver: Resource? = null
            try {
                val adv = ResourceAdvertisement.unpack(plaintext)
                receiver = if (adv != null) Resource.accept(adv, link) else null
            } catch (e: Exception) {
                crashed = true
            }
            val after = link.incomingResourceHashesForTest().size
            out = result(
                "variant" to strVal(variant),
                "inbound_started" to boolVal(receiver != null || after > before),
                "crashed" to boolVal(crashed),
            )
            runCatching { receiver?.cancel() }
        } finally {
            Resource.watchdogDisabledForTest = false
        }
        out
    }

    "wire_inject_resource_adv_flags" -> {
        val handle = p.str("handle")
        val linkIdHex = p.str("link_id")
        val variant = p.str("variant")
        val inst = wireInstances[handle] ?: throw IllegalArgumentException("Unknown handle: $handle")
        val link = inst.outLinks[linkIdHex] ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")
        data class FlagsCase(val requestId: ByteArray?, val isResponse: Boolean, val strategy: Int)
        val case = when (variant) {
            "request_autoaccept" -> FlagsCase(randomBytes(16), false, Link.ACCEPT_NONE)
            "response_no_pending_request" -> FlagsCase(randomBytes(16), true, Link.ACCEPT_NONE)
            "plain_accept_none" -> FlagsCase(null, false, Link.ACCEPT_NONE)
            "plain_accept_all" -> FlagsCase(null, false, Link.ACCEPT_ALL)
            else -> throw IllegalArgumentException("unknown adv-flags variant: $variant")
        }
        Resource.watchdogDisabledForTest = true
        val out: JsonObject
        try {
            val sender = Resource.create(
                randomBytes(600), link, advertise = false,
                requestId = case.requestId, isResponse = case.isResponse,
            )
            val advPlain = ResourceAdvertisement.fromResource(sender).pack()
            fun countForHash() = link.incomingResourceHashesForTest().count { it.contentEquals(sender.hash) }
            val savedStrategy = link.getResourceStrategy()
            val before = countForHash()
            link.setResourceStrategy(case.strategy)
            try {
                feedInboundLinkPacket(link, advPlain, network.reticulum.common.PacketContext.RESOURCE_ADV)
            } finally {
                link.setResourceStrategy(savedStrategy)
            }
            val after = countForHash()
            out = result(
                "variant" to strVal(variant),
                "accepted" to boolVal(after > before),
                "strategy" to intVal(case.strategy),
            )
        } finally {
            Resource.watchdogDisabledForTest = false
        }
        out
    }

    else -> throw IllegalArgumentException("Unknown wire command: $command")
}
