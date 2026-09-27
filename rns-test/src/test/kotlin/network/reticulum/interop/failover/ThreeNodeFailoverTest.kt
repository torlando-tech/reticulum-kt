package network.reticulum.interop.failover

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Three real nodes over loopback: A forwarding for B and C, plus a direct B–C link.
 *
 * ```
 *        B ────────── direct ────────── C
 *         \                            /
 *          \                          /
 *           ── A (transport node) ────
 * ```
 *
 * Each node is its own JVM, because `Transport` is an `object` and a second node cannot be
 * started beside the first in one process. That is the whole reason this file spawns
 * processes instead of constructing three transports: it is not a preference for realism,
 * it is the only way.
 *
 * ## What this can and cannot show today
 *
 * Discovery is real and asserted: with both a direct link and a route through A, does each
 * of B and C hold *both* ways to the other? That exercises transport-node forwarding, which
 * nothing else here covers end to end.
 *
 * Failover is not built. Selection over the reachability set (B.3) and online-aware egress
 * (A.2) do not exist, so nothing can move traffic to the alternate however many are held.
 * Those cases record the outcome rather than asserting a pass, and become the acceptance
 * test the day B.3 lands.
 *
 * ## One thing about the scenario worth stating
 *
 * The obvious reading of "bring up A, then interrupt the direct link" does not test what it
 * sounds like. A path is replaced by the *newest* announce whatever its hop count, so if A
 * comes up last, the two-hop route through A becomes the selected one and the direct link is
 * already the alternate. Interrupting the direct link then interrupts something nothing was
 * using.
 *
 * So the order here is: bring A up first, let both sides learn the route through it, then
 * announce across the direct link so the one-hop route is the newest and therefore selected.
 * Only then is killing the direct link the interesting test.
 */
@DisplayName("Three nodes over loopback")
class ThreeNodeFailoverTest {

    private val nodes = mutableListOf<Node>()

    @AfterEach
    fun tearDown() {
        nodes.forEach { it.close() }
        nodes.clear()
    }

    @Test
    @Timeout(180)
    fun `B and C each learn both ways to the other`() {
        val (a, b, c) = bringUpTriangle()

        // A first: both sides learn the two-hop route through the transport node.
        a.announce()
        b.announce()
        c.announce()
        settle()

        val bSeesC = b.reach(c.destination)
        val cSeesB = c.reach(b.destination)
        println("B sees C: $bSeesC")
        println("C sees B: $cSeesB")

        assertNotNull(bSeesC.selected, "B must have some way to reach C")
        assertNotNull(cSeesB.selected, "C must have some way to reach B")
        assertTrue(
            bSeesC.alternates.isNotEmpty(),
            "B holds only one way to C; the second should have been kept: $bSeesC",
        )
        assertTrue(
            cSeesB.alternates.isNotEmpty(),
            "C holds only one way to B; the second should have been kept: $cSeesB",
        )
    }

    /**
     * The failover case, ordered so the direct link is the one in use before it is cut.
     * Records rather than asserts, because nothing can select an alternate yet.
     */
    @Test
    @Timeout(180)
    fun `cutting the direct link, with the route through A held as the alternate`() {
        val (a, b, c) = bringUpTriangle()

        a.announce()
        settle()
        // Direct last, so the one-hop route is the newest announce and therefore selected.
        b.announce()
        c.announce()
        settle()

        val before = b.reach(c.destination)
        println("B -> C before the cut: $before")

        b.kill("B-out0") // the direct leg
        settle()

        val after = b.reach(c.destination)
        println("B -> C after the cut: $after")
        b.send(c.destination)

        // Not asserted: with no selection over the set, the row B is using does not move.
        // What IS worth asserting is that cutting a link did not lose the other way through.
        assertNotNull(
            after.selected ?: after.alternates.firstOrNull(),
            "cutting one link must not leave B with no route to C at all: $after",
        )
    }

    // --- rig -------------------------------------------------------------------------------

    private fun bringUpTriangle(): Triple<Node, Node, Node> {
        val aPort = freePort()
        val cPort = freePort()

        // A listens for both clients. B connects to A and to C. C listens for B and dials A.
        val a = spawn("A", listen = aPort, transport = true)
        val c = spawn("C", listen = cPort, connect = listOf("127.0.0.1:$aPort"))
        val b = spawn("B", connect = listOf("127.0.0.1:$cPort", "127.0.0.1:$aPort"))
        nodes += listOf(a, b, c)

        // Let the TCP interfaces establish before anyone announces.
        Thread.sleep(3_000)
        return Triple(a, b, c)
    }

    /** Announces need a moment to cross two hops, and a timebase tick to be comparable. */
    private fun settle() = Thread.sleep(2_500)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun spawn(
        name: String,
        listen: Int? = null,
        connect: List<String> = emptyList(),
        transport: Boolean = false,
    ): Node {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val args = mutableListOf(
            javaBin, "-cp", System.getProperty("java.class.path"),
            "network.reticulum.interop.failover.LoopbackNode",
            "--name", name, "--control", "0",
        )
        listen?.let { args += listOf("--listen", it.toString()) }
        connect.forEach { args += listOf("--connect", it) }
        if (transport) args += "--transport"

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val stdout = BufferedReader(InputStreamReader(process.inputStream))

        // The node prints its control port once it is listening; anything before that is
        // startup logging and is echoed so a failure to start is visible rather than a hang.
        var controlPort = -1
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val line = stdout.readLine() ?: break
            if (line.startsWith("CONTROL ")) {
                controlPort = line.removePrefix("CONTROL ").trim().toInt()
                break
            }
        }
        require(controlPort > 0) { "node $name never reported a control port" }

        // Drain the rest so the process never blocks on a full pipe, and keep it: when a
        // three-process topology does not form, the node's own log is the only account of
        // why, and discarding it turns every failure into guesswork.
        val logFile = java.io.File("build/loopback-$name.log")
        logFile.parentFile.mkdirs()
        Thread {
            runCatching {
                logFile.printWriter().use { w ->
                    while (true) { val l = stdout.readLine() ?: break; w.println(l); w.flush() }
                }
            }
        }.apply { isDaemon = true }.start()

        return Node(name, process, Socket("127.0.0.1", controlPort))
    }

    private class Node(val name: String, val process: Process, private val socket: Socket) {
        private val out = PrintWriter(socket.getOutputStream(), true)
        private val input = BufferedReader(InputStreamReader(socket.getInputStream()))

        val destination: String = command("hello").field("destination")

        fun announce() = command("announce")
        fun kill(iface: String) = command("kill $iface")
        fun send(dest: String) = command("send $dest")

        fun reach(dest: String): Reachability {
            val reply = command("reach $dest")
            return Reachability(
                selected = reply.field("selected").takeIf { it != "null" && it.isNotEmpty() },
                alternates = Regex("\"alternates\":\\[(.*?)]").find(reply)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim('"', ' ') }?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
        }

        private fun command(line: String): String {
            out.println(line)
            return input.readLine() ?: error("$name gave no reply to '$line'")
        }

        private fun String.field(key: String): String =
            Regex("\"$key\":\"?([^\",}]*)\"?").find(this)?.groupValues?.get(1).orEmpty()

        fun close() {
            runCatching { command("quit") }
            runCatching { socket.close() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private data class Reachability(val selected: String?, val alternates: List<String>)
}
