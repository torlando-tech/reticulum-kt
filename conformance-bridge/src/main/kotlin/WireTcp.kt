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
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceConstants
import network.reticulum.transport.Transport
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
    val destinations: MutableList<Pair<Identity, Destination>> = mutableListOf(),
    // Link bookkeeping — used by wire_listen / wire_link_* commands below.
    val listeners: ConcurrentHashMap<String, Listener> = ConcurrentHashMap(),
    val outLinks: ConcurrentHashMap<String, Link> = ConcurrentHashMap(),
)

/** Per-destination receive buffer for incoming link data + resources. */
private class Listener(
    val destination: Destination,
    val identity: Identity,
    val recvBuffer: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque(),
    val resourceBuffer: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque(),
)

private val wireInstances = mutableMapOf<String, WireInstance>()

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
    for (inst in stale) {
        runCatching { inst.serverIface?.detach() }
        runCatching { inst.clientIface?.detach() }
        runCatching { inst.configDir.deleteRecursively() }
    }
    runCatching { Reticulum.stop() }
}

fun handleWireCommand(command: String, p: JsonObject): JsonObject = when (command) {
    "wire_start_tcp_server" -> {
        val networkName = p.get("network_name")?.asString?.takeIf { it.isNotEmpty() }
        val passphrase = p.get("passphrase")?.asString?.takeIf { it.isNotEmpty() }
        val bindPortReq = p.get("bind_port")?.asInt ?: 0
        val bindPort = if (bindPortReq == 0) allocateFreePort() else bindPortReq
        val desiredMode = parseInterfaceMode(p.get("mode")?.asString)

        // Clear any prior wire state (detach interfaces, drop handles,
        // stop the RNS singleton) so this call starts clean and no stale
        // handle can survive pointing at a dead Reticulum.
        resetWireState()

        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_server_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = true,
                shareInstance = false,
                connectToSharedInstance = false,
            )

            val server = TCPServerInterface(
                name = "Wire TCP Server",
                bindAddress = "127.0.0.1",
                bindPort = bindPort,
                ifacNetname = networkName,
                ifacNetkey = passphrase,
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
            )

            result(
                "handle" to JsonPrimitive(handle),
                "port" to JsonPrimitive(bindPort),
                "identity_hash" to hexVal(identityHash),
            )
        } catch (t: Throwable) {
            // Partial setup — roll back RNS and the temp dir so we don't
            // leak them for the remainder of the bridge process's lifetime.
            runCatching { Reticulum.stop() }
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

        resetWireState()

        val configDir = java.nio.file.Files.createTempDirectory("rns_wire_client_").toFile()
        try {
            val rns = Reticulum.start(
                configDir = configDir.absolutePath,
                enableTransport = true,
                shareInstance = false,
                connectToSharedInstance = false,
            )

            val client = TCPClientInterface(
                name = "Wire TCP Client",
                targetHost = targetHost,
                targetPort = targetPort,
                ifacNetname = networkName,
                ifacNetkey = passphrase,
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
        val appData: ByteArray? = if (appDataHex.isNotEmpty()) appDataHex.fromHex() else null
        destination.announce(appData = appData)
        inst.destinations.add(identity to destination)

        result(
            "destination_hash" to hexVal(destination.hash),
            "identity_hash" to hexVal(identity.hash),
        )
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

        val listener = Listener(destination, identity)
        // On link established, wire both packet and resource callbacks into
        // the listener's buffers.
        destination.setLinkEstablishedCallback { linkObj ->
            val link = linkObj as? Link ?: return@setLinkEstablishedCallback
            link.setPacketCallback { data, _packet ->
                listener.recvBuffer.add(data.copyOf())
            }
            // Accept any incoming Resource transfer and buffer its
            // reassembled data on completion.
            link.setResourceStrategy(Link.ACCEPT_ALL)

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

        val inst = wireInstances[handle]
            ?: throw IllegalArgumentException("Unknown handle: $handle")

        val link = inst.outLinks[linkIdHex]
            ?: throw IllegalArgumentException("Unknown link_id: $linkIdHex")

        val done = CountDownLatch(1)
        val finalStatus = java.util.concurrent.atomic.AtomicInteger(-1)

        val resource = Resource.create(
            data = payload,
            link = link,
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
        )
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

        // `Transport.requestPath` adds Kotlin-only early-skip guards
        // (already has path / too recent) that would make this a no-op in
        // the path-discovery tests — the whole point is to issue a fresh
        // request even for destinations we already know / have recently
        // requested. `sendPathRequestUnconditional` bypasses those guards,
        // matching Python's `RNS.Transport.request_path` behaviour.
        Transport.sendPathRequestUnconditional(destHash)
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
        val primary = inst.serverIface ?: inst.clientIface
        val total = primary?.txBytes?.get() ?: 0L
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
            try { Reticulum.stop() } catch (_: Throwable) {}
            try { inst.configDir.deleteRecursively() } catch (_: Throwable) {}
            result("stopped" to boolVal(true))
        } else {
            result("stopped" to boolVal(false))
        }
    }

    else -> throw IllegalArgumentException("Unknown wire command: $command")
}
