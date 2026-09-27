package network.reticulum.interop.failover

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.Reticulum
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.packet.Packet
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import network.reticulum.interfaces.toRef
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.nio.file.Files

/**
 * One Reticulum node, in its own JVM, driven over a control socket.
 *
 * This exists because `Transport` is an `object` — a JVM-wide singleton — so a second node
 * cannot be started beside the first in the same process. Every multi-node test here so far
 * has worked around that by making the second party python. To watch a Kotlin node forward
 * for two other Kotlin nodes, and to take one of their links away, each has to be its own
 * process. That is what this is: a small main() the test spawns three of, wired together
 * over loopback TCP with real interfaces.
 *
 * Deliberately dumb. It owns no scenario logic and makes no assertions; it stands up
 * interfaces, announces when told, reports what it knows, and breaks what it is told to
 * break. The scenario lives in the test, where it can be read.
 *
 * ## Control protocol
 *
 * One command per line on the control socket, one JSON-ish line back. Text rather than a
 * framework because the whole vocabulary is six verbs and a test has to be able to read the
 * transcript when something goes wrong.
 *
 * ```
 *   hello                      -> {"name":..,"identity":..,"destination":..}
 *   announce                   -> {"announced":"<dest hash>"}
 *   reach <destHex>            -> {"selected":"<iface>","alternates":[..],"hops":n}
 *   paths                      -> {"paths":[{"dest":..,"iface":..,"hops":n}]}
 *   kill <ifaceName>           -> {"killed":".."}      interface reports offline
 *   send <destHex>             -> {"sent":true}
 *   quit                       -> {"bye":true}
 * ```
 *
 * Usage:
 * ```
 *   LoopbackNode --name A --control 0 [--listen 4242] [--connect 127.0.0.1:4242] [--transport]
 * ```
 * `--control 0` takes an ephemeral port and prints `CONTROL <port>` on stdout, which is how
 * the test finds it without guessing.
 */
object LoopbackNode {

    private val interfaces = mutableMapOf<String, InterfaceRef>()
    private val listeners = mutableMapOf<String, TCPServerInterface>()
    private val clients = mutableMapOf<String, TCPClientInterface>()
    private lateinit var nodeName: String
    private lateinit var identity: Identity
    private lateinit var destination: Destination

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parse(args)
        nodeName = opts["name"] ?: "node"

        // Reticulum.start rather than Transport.start: it owns the config directory, and
        // each node needs its own so three of them do not share identity and path state
        // through the filesystem. Transport.setStoragePath is internal to rns-core.
        val storage = Files.createTempDirectory("loopback-$nodeName-").toFile()
        storage.deleteOnExit()
        Reticulum.start(
            configDir = storage.absolutePath,
            enableTransport = opts.containsKey("transport"),
        )
        identity = Transport.identity ?: error("Reticulum started without a transport identity")

        destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "failover",
            aspects = arrayOf("node"),
        )

        opts["listen"]?.let { port ->
            val iface = TCPServerInterface(name = "$nodeName-listen", bindPort = port.toInt())
            iface.start()
            val ref = iface.toRef()
            Transport.registerInterface(ref)
            listeners["$nodeName-listen"] = iface
            interfaces["$nodeName-listen"] = ref
        }

        // --connect may repeat; each becomes its own named interface so the test can kill
        // one by name without touching the other.
        opts.filterKeys { it.startsWith("connect") }.values.forEachIndexed { i, target ->
            val (host, port) = target.split(":")
            val name = "$nodeName-out$i"
            val iface = TCPClientInterface(name = name, targetHost = host, targetPort = port.toInt())
            iface.start()
            val ref = iface.toRef()
            Transport.registerInterface(ref)
            clients[name] = iface
            interfaces[name] = ref
        }

        serveControl((opts["control"] ?: "0").toInt())
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var connects = 0
        var i = 0
        while (i < args.size) {
            val key = args[i].removePrefix("--")
            val takesValue = key != "transport"
            if (takesValue) {
                val name = if (key == "connect") "connect${connects++}" else key
                out[name] = args.getOrElse(i + 1) { "" }
                i += 2
            } else {
                out[key] = "true"
                i += 1
            }
        }
        return out
    }

    private fun serveControl(requestedPort: Int) {
        val server = ServerSocket(requestedPort)
        println("CONTROL ${server.localPort}")
        System.out.flush()

        val socket = server.accept()
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val out = PrintWriter(socket.getOutputStream(), true)

        while (true) {
            val line = input.readLine() ?: break
            val parts = line.trim().split(" ")
            val reply = try {
                dispatch(parts)
            } catch (expectedCommandFailure: Exception) {
                // A command failing is a result the test wants to see, not a reason to die:
                // a node that exits on a bad command leaves the test hanging on a read.
                """{"error":"${expectedCommandFailure.javaClass.simpleName}: """ +
                    """${expectedCommandFailure.message}"}"""
            }
            out.println(reply)
            if (parts[0] == "quit") break
        }

        shutdown()
        socket.close()
        server.close()
    }

    private fun dispatch(parts: List<String>): String = when (parts[0]) {
        "hello" ->
            """{"name":"$nodeName","identity":"${identity.hash.toHexString()}",""" +
                """"destination":"${destination.hash.toHexString()}"}"""

        "announce" -> {
            destination.announce()
            """{"announced":"${destination.hash.toHexString()}"}"""
        }

        "reach" -> {
            val dest = hexToBytes(parts[1])
            val set = Transport.pathReachability(dest)
            if (set == null) {
                """{"selected":null,"alternates":[]}"""
            } else {
                val alts = set.rows.drop(1).joinToString(",") { "\"${it.interfaceName}\"" }
                """{"selected":"${set.selected.interfaceName}",""" +
                    """"hops":${set.selected.hops},"alternates":[$alts]}"""
            }
        }

        "paths" -> {
            val rows = Transport.pathTable.entries.joinToString(",") { (key, entry) ->
                val iface = interfaces.values
                    .firstOrNull { it.hash.contentEquals(entry.receivingInterfaceHash) }?.name
                """{"dest":"${key.bytes.toHexString()}","iface":"$iface","hops":${entry.hops}}"""
            }
            """{"paths":[$rows]}"""
        }

        "kill" -> {
            val name = parts[1]
            // Taken down the way a real link dies rather than by deregistering: the socket
            // goes and the interface reports itself offline, which is what the design's
            // detection hangs on.
            listeners[name]?.detach()
            clients[name]?.detach()
            """{"killed":"$name"}"""
        }

        "send" -> {
            // Recall rather than reconstruct: the peer's identity is known only because we
            // heard its announce, which is the same order a real send happens in. A send
            // before the announce arrives should fail here, and saying so is useful.
            val destHash = hexToBytes(parts[1])
            val peerIdentity = Identity.recall(destHash)
            if (peerIdentity == null) {
                """{"sent":false,"why":"no identity recalled for that destination yet"}"""
            } else {
                val peer = Destination.create(
                    identity = peerIdentity,
                    direction = DestinationDirection.OUT,
                    type = DestinationType.SINGLE,
                    appName = "failover",
                    aspects = arrayOf("node"),
                )
                Packet.create(
                    destination = peer,
                    data = ByteArray(24) { 0x5A },
                    createReceipt = false,
                ).send()
                """{"sent":true}"""
            }
        }

        "quit" -> """{"bye":true}"""

        else -> """{"error":"unknown command ${parts[0]}"}"""
    }

    private fun shutdown() {
        listeners.values.forEach { runCatching { it.detach() } }
        clients.values.forEach { runCatching { it.detach() } }
        runCatching { Reticulum.stop() }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
