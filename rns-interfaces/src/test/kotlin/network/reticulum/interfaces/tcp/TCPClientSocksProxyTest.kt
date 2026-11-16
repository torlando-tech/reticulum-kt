package network.reticulum.interfaces.tcp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * columba#965: a TCP client must be able to reach a `.onion` target through a
 * SOCKS5 proxy, because onion names never resolve locally.
 */
class TCPClientSocksProxyTest {
    private val interfaces = mutableListOf<TCPClientInterface>()

    @AfterEach
    fun cleanup() {
        interfaces.forEach { runCatching { it.stop() } }
        interfaces.clear()
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    fun `client tunnels packets to an onion target through the socks5 proxy`() {
        val proxy = FakeSocks5Proxy()
        try {
            proxy.start()
            val received = LinkedBlockingQueue<ByteArray>()
            val iface = newInterface("tor", proxy)
            iface.onPacketReceived = { data, _ -> received.put(data.copyOf()) }
            iface.start()

            val target = proxy.awaitConnect(10_000)
            assertNotNull(target, "the proxy must receive a CONNECT")
            assertEquals(ONION, target!!.first)
            assertEquals(PORT, target.second)

            // A successful reply (sent in domain form) must leave the client
            // online. This fails if the client rejects or mis-parses the reply.
            assertTrue(
                awaitOnline(iface, 5_000),
                "a successful SOCKS5 reply must leave the interface online",
            )

            // HDLC deframer drops frames of HEADER_MIN_SIZE (19) bytes or less,
            // so the payload must be large enough to survive the runt guard.
            val payload = "reticulum-socks5-round-trip-payload".toByteArray()
            iface.processOutgoing(payload)
            val echoed = awaitPayload(received, payload, 5_000)
            assertArrayEquals(payload, echoed, "the payload must round-trip through the tunnel")
        } finally {
            proxy.stop()
        }
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    fun `a refused socks5 connect closes the socket and never reports online`() {
        val proxy = FakeSocks5Proxy(refuse = true)
        try {
            proxy.start()
            val iface = newInterface("tor-refused", proxy)
            iface.start()

            val target = proxy.awaitConnect(10_000)
            assertNotNull(target, "the proxy must receive a CONNECT even when refusing")
            assertEquals(ONION, target!!.first)
            assertEquals(PORT, target.second)

            // The proxy observes EOF only after the client closes the socket it
            // opened for the failed handshake. A leaked socket shows up as a
            // missing EOF (timeout) here.
            assertTrue(
                proxy.awaitClientClosed(10_000),
                "the client must close the socket after a refused SOCKS5 handshake",
            )
            assertFalse(iface.online.value, "a refused CONNECT must not report the interface online")
        } finally {
            proxy.stop()
        }
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    fun `detach during a blocked socks5 handshake closes the socket and never reports online`() {
        val proxy = FakeSocks5Proxy(silent = true)
        try {
            proxy.start()
            val iface = newInterface("tor-detach", proxy)
            iface.start()

            val target = proxy.awaitConnect(10_000)
            assertNotNull(target, "the proxy must receive a CONNECT before detach")
            assertEquals(ONION, target!!.first)

            // The proxy accepted the TCP connection and the CONNECT request but
            // never replies, so the client is blocked in the SOCKS5 handshake.
            iface.detach()

            // detach() must close the in-flight handshake socket instead of
            // waiting for the connect timeout: the proxy observes EOF promptly.
            assertTrue(
                proxy.awaitClientClosed(1_000),
                "detach() must close the socket blocked in the SOCKS5 handshake",
            )
            assertFalse(
                awaitOnline(iface, 500),
                "a detached interface must never report the handshake online",
            )
        } finally {
            proxy.stop()
        }
    }

    private fun newInterface(name: String, proxy: FakeSocks5Proxy): TCPClientInterface =
        TCPClientInterface(
            name = name,
            targetHost = ONION,
            targetPort = PORT,
            connectTimeoutMs = 2_000,
            socksProxyHost = "127.0.0.1",
            socksProxyPort = proxy.port,
        ).also { interfaces += it }

    private fun awaitOnline(iface: TCPClientInterface, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (iface.online.value) return true
            Thread.sleep(25)
        }
        return iface.online.value
    }

    private fun awaitPayload(
        received: LinkedBlockingQueue<ByteArray>,
        expected: ByteArray,
        timeoutMs: Long,
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val next = received.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (next.contentEquals(expected)) return next
        }
        return null
    }

    /**
     * Minimal SOCKS5 server: accepts one client per connection and records the
     * requested target. Successful replies use the domain-name address form and
     * the connection stays open, echoing tunneled bytes back to the client.
     */
    private class FakeSocks5Proxy(
        private val refuse: Boolean = false,
        private val silent: Boolean = false,
    ) {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val connections = LinkedBlockingQueue<Pair<String, Int>>()
        private val clientClosed = LinkedBlockingQueue<Boolean>()
        private val workers = CopyOnWriteArrayList<Thread>()

        init {
            thread(start = true, isDaemon = true, name = "fake-socks5-accept") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                    workers +=
                        thread(start = true, isDaemon = true, name = "fake-socks5-conn") {
                            runCatching { serveOne(socket) }
                        }
                }
            }
        }

        val port: Int get() = server.localPort

        fun start() = Unit

        fun stop() {
            runCatching { server.close() }
            workers.forEach { runCatching { it.interrupt() } }
        }

        fun awaitConnect(timeoutMs: Long): Pair<String, Int>? =
            connections.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitClientClosed(timeoutMs: Long): Boolean =
            clientClosed.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: false

        private fun serveOne(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 10_000
                val input = DataInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                val ver = input.readUnsignedByte()
                val methods = input.readUnsignedByte()
                repeat(methods) { input.readUnsignedByte() }
                if (ver != 5) throw IOException("not SOCKS5: ver=$ver")
                out.write(byteArrayOf(5, 0))
                out.flush()

                input.readUnsignedByte()
                val cmd = input.readUnsignedByte()
                input.readUnsignedByte()
                val atyp = input.readUnsignedByte()
                if (cmd != 1) throw IOException("not CONNECT: cmd=$cmd")
                val host =
                    when (atyp) {
                        1 -> ByteArray(4).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
                        4 -> ByteArray(16).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                        else -> throw IOException("bad atyp=$atyp")
                    }
                val port = input.readUnsignedShort()
                connections.put(host to port)

                when {
                    // Deliberately never answer the CONNECT: the client stays
                    // blocked in the handshake until its socket is closed or
                    // its read times out.
                    silent -> Unit

                    refuse -> {
                        // SOCKS5 "connection refused" status.
                        out.write(byteArrayOf(5, 0x05, 0, 1, 0, 0, 0, 0, 0, 0))
                        out.flush()
                    }

                    else -> {
                        // Domain-form bound address (ATYP 0x03): the reply shape
                        // a proxy returns for a name it resolved itself.
                        val bound = "127.0.0.1".toByteArray()
                        out.write(byteArrayOf(5, 0, 0, 3, bound.size.toByte()) + bound + byteArrayOf(0, 0))
                        out.flush()
                    }
                }

                val buffer = ByteArray(4096)
                while (true) {
                    val read =
                        try {
                            input.read(buffer)
                        } catch (e: SocketTimeoutException) {
                            // The proxy's own idle timeout says nothing about
                            // the client: it is not evidence of a closure.
                            throw e
                        } catch (_: IOException) {
                            // A reset/abort also means the client closed its end.
                            clientClosed.put(true)
                            break
                        }
                    if (read < 0) {
                        // EOF: the client closed its end of the connection.
                        clientClosed.put(true)
                        break
                    }
                    if (read > 0 && !refuse && !silent) {
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
            }
        }
    }

    private companion object {
        const val ONION = "ympr5d6kpga2xhfpm5h4uxltvddckarpjae7cq6rnkqp6vy4rj6gziqd.onion"
        const val PORT = 4245
    }
}
