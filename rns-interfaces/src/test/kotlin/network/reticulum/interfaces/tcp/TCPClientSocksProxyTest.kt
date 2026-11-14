package network.reticulum.interfaces.tcp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `client connects to an onion target through the socks5 proxy`() {
        val proxy = FakeSocks5Proxy()
        try {
            proxy.start()
            val iface =
                TCPClientInterface(
                    name = "tor",
                    targetHost = ONION,
                    targetPort = PORT,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = proxy.port,
                )
            interfaces += iface
            iface.start()

            val target = proxy.awaitConnect(10_000)
            assertNotNull(target, "the proxy must receive a CONNECT")
            assertEquals(ONION, target!!.first)
            assertEquals(PORT, target.second)
        } finally {
            proxy.stop()
        }
    }

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `a refused socks5 connect fails the interface instead of reporting online`() {
        val proxy = FakeSocks5Proxy(refuse = true)
        try {
            proxy.start()
            val iface =
                TCPClientInterface(
                    name = "tor-refused",
                    targetHost = ONION,
                    targetPort = PORT,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = proxy.port,
                )
            interfaces += iface
            iface.start()

            proxy.awaitConnect(10_000)
            val deadline = System.currentTimeMillis() + 3_000
            var everOnline = false
            while (System.currentTimeMillis() < deadline) {
                if (iface.online.value) {
                    everOnline = true
                    break
                }
                Thread.sleep(50)
            }
            assertFalse(everOnline, "a refused CONNECT must not report the interface online")
        } finally {
            proxy.stop()
        }
    }

    private class FakeSocks5Proxy(private val refuse: Boolean = false) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val connections = LinkedBlockingQueue<Pair<String, Int>>()

        init {
            thread(start = true, isDaemon = true, name = "fake-socks5") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                    runCatching { serveOne(socket) }
                }
            }
        }

        val port: Int get() = server.localPort

        fun start() = Unit

        fun stop() {
            runCatching { server.close() }
        }

        fun awaitConnect(timeoutMs: Long): Pair<String, Int>? =
            connections.poll(timeoutMs, TimeUnit.MILLISECONDS)

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

                if (refuse) {
                    // SOCKS5 "connection refused" status.
                    out.write(byteArrayOf(5, 0x05, 0, 1, 0, 0, 0, 0, 0, 0))
                } else {
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    runCatching { input.read() }
                }
                out.flush()
            }
        }
    }

    private companion object {
        const val ONION = "ympr5d6kpga2xhfpm5h4uxltvddckarpjae7cq6rnkqp6vy4rj6gziqd.onion"
        const val PORT = 4245
    }
}
