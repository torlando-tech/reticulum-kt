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
 *   wire_start_tcp_server
 *   wire_start_tcp_client
 *   wire_announce
 *   wire_poll_path
 *   wire_stop
 *
 * One bridge process hosts at most one wire Reticulum singleton. The
 * pytest `wire_peers` fixture spawns two bridges (one per role) to pair
 * a server with a client.
 */

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import network.reticulum.Reticulum
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.io.File
import java.net.ServerSocket
import java.util.UUID

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
)

private val wireInstances = mutableMapOf<String, WireInstance>()

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
            server.start()
            // Register with Transport so the Transport layer considers this
            // interface a valid outbound path AND so inbound packets land in
            // the announce/path pipeline.
            val serverRef = server.toRef()
            Transport.registerInterface(serverRef)
            server.onPacketReceived = { data, iface ->
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
            client.start()
            val clientRef = client.toRef()
            Transport.registerInterface(clientRef)
            client.onPacketReceived = { data, iface ->
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
