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
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.local.LocalServerInterface
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
        runCatching { inst.sharedClient?.detach() }
        runCatching { inst.sharedServer?.detach() }
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

    else -> throw IllegalArgumentException("Unknown wire command: $command")
}
