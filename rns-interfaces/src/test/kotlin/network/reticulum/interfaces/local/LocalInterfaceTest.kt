package network.reticulum.interfaces.local

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tests for LocalInterface IPC communication.
 */
class LocalInterfaceTest {

    private var server: LocalServerInterface? = null
    private val clients = mutableListOf<LocalClientInterface>()

    @AfterEach
    fun tearDown() {
        // Clean up clients
        clients.forEach { it.detach() }
        clients.clear()

        // Clean up server
        server?.detach()
        server = null

        // Give sockets time to close
        Thread.sleep(100)
    }

    @Test
    fun `test server starts and accepts connections via TCP`() {
        // Create server on random port
        server = LocalServerInterface(name = "TestServer", tcpPort = 0)
        server!!.start()

        assertTrue(server!!.online.value)
        assertTrue(server!!.clientCount() == 0)
    }

    @Test
    fun `test client connects to server via TCP`() {
        // Start server
        val tcpPort = 37428
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.start()

        // Start client
        val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        clients.add(client)
        client.start()

        // Give connection time to establish
        Thread.sleep(200)

        assertTrue(server!!.online.value)
        assertTrue(client.online.value)
        assertEquals(1, server!!.clientCount())
    }

    @Test
    fun `test packet transmission from client to server via TCP`() {
        val tcpPort = 37429
        // Data must be > HEADER_MIN_SIZE (19) bytes to pass HDLC deframer validation
        val testData = "Hello from client!!!!".toByteArray()
        val receivedLatch = CountDownLatch(1)
        var receivedData: ByteArray? = null

        // Start server
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.onPacketReceived = { data, _ ->
            receivedData = data
            receivedLatch.countDown()
        }
        server!!.start()

        // Start client
        val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        clients.add(client)
        client.start()

        // Give connection time to establish
        Thread.sleep(200)

        // Send data from client
        client.processOutgoing(testData)

        // Wait for server to receive
        assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(receivedData)
        assertArrayEquals(testData, receivedData)
    }

    @Test
    fun `test packet transmission from server to client via TCP`() {
        val tcpPort = 37430
        // Data must be > HEADER_MIN_SIZE (19) bytes to pass HDLC deframer validation
        val testData = "Hello from server!!!!".toByteArray()
        val receivedLatch = CountDownLatch(1)
        var receivedData: ByteArray? = null

        // Start server
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.start()

        // Start client
        val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        client.onPacketReceived = { data, _ ->
            receivedData = data
            receivedLatch.countDown()
        }
        clients.add(client)
        client.start()

        // Give connection time to establish
        Thread.sleep(200)

        // In production, Transport calls each spawned interface's processOutgoing() directly.
        // server.processOutgoing() is intentionally a no-op to prevent double-send.
        val spawnedClient = server!!.getClients().first()
        spawnedClient.processOutgoing(testData)

        // Wait for client to receive
        assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(receivedData)
        assertArrayEquals(testData, receivedData)
    }

    @Test
    fun `test broadcast to multiple clients via TCP`() {
        val tcpPort = 37431
        // Data must be > HEADER_MIN_SIZE (19) bytes to pass HDLC deframer validation
        val testData = "Broadcast message!!!!!".toByteArray()
        val numClients = 3
        val receivedLatch = CountDownLatch(numClients)
        val receivedDataList = mutableListOf<ByteArray>()

        // Start server
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.start()

        // Start multiple clients
        repeat(numClients) { i ->
            val client = LocalClientInterface(name = "TestClient$i", tcpPort = tcpPort)
            client.onPacketReceived = { data, _ ->
                synchronized(receivedDataList) {
                    receivedDataList.add(data)
                }
                receivedLatch.countDown()
            }
            clients.add(client)
            client.start()
            Thread.sleep(50) // Stagger connections
        }

        // Give connections time to establish
        Thread.sleep(200)

        assertEquals(numClients, server!!.clientCount())

        // In production, Transport calls each spawned interface's processOutgoing() directly.
        // Simulate that broadcast pattern here.
        for (spawnedClient in server!!.getClients()) {
            spawnedClient.processOutgoing(testData)
        }

        // Wait for all clients to receive
        assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(numClients, receivedDataList.size)

        // Verify all received the same data
        receivedDataList.forEach { data ->
            assertArrayEquals(testData, data)
        }
    }

    @Test
    fun `test client disconnect via TCP`() {
        val tcpPort = 37432

        // Start server
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.start()

        // Start client
        val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        clients.add(client)
        client.start()

        // Give connection time to establish
        Thread.sleep(200)

        assertEquals(1, server!!.clientCount())

        // Disconnect client
        client.detach()
        Thread.sleep(200)

        assertEquals(0, server!!.clientCount())
    }

    @Test
    fun `test Unix socket support detection`() {
        // Just verify the method works
        val supportsUnix = LocalServerInterface.supportsUnixSockets()
        // Don't assert a specific value, as it depends on platform
        println("Unix socket support: $supportsUnix")
    }

    @Test
    fun `test server with Unix socket if supported`(@TempDir tempDir: Path) {
        if (!LocalServerInterface.supportsUnixSockets()) {
            println("Skipping Unix socket test - not supported on this platform")
            return
        }

        val socketPath = tempDir.resolve("test.socket").toString()

        try {
            // Start server
            server = LocalServerInterface(name = "TestServer", socketPath = socketPath)
            server!!.start()

            assertTrue(server!!.online.value)

            // Start client
            val client = LocalClientInterface(name = "TestClient", socketPath = socketPath)
            clients.add(client)
            client.start()

            // Give connection time to establish
            Thread.sleep(200)

            assertTrue(client.online.value)
            assertEquals(1, server!!.clientCount())
        } catch (e: UnsupportedOperationException) {
            // Unix sockets detected but not fully supported by JVM implementation
            println("Unix sockets partially supported, skipping: ${e.message}")
        }
    }

    /**
     * Stress regression for the spawned-child read loop. On Android, real-world
     * Carina-as-shared-instance soak (>30 min uptime) has been observed to wedge
     * a long-lived spawned child's read loop — inbound bytes stop draining even
     * though the socket remains ESTABLISHED and outbound bytes still flow. The
     * suspected interaction was a redundant `withContext(Dispatchers.IO)` inside
     * the read loop (already running on `ioScope`'s IO dispatcher), which this
     * commit removes to match python's direct synchronous `socket.recv(4096)`
     * pattern at `RNS/Interfaces/LocalInterface.py:302`.
     *
     * The wedge is not deterministically reproducible on a desktop JVM, so this
     * test does not assert "wedge is fixed" — it asserts "long-lived spawned
     * child keeps draining inbound bytes under aggressive sibling probe churn",
     * which is the regression guard for any future change that disturbs the
     * read loop body.
     */
    @Test
    fun `long-lived spawned child keeps draining inbound bytes under sibling probe churn`() {
        val tcpPort = 37435
        val numRounds = 50
        val probesPerRound = 5
        val packetData = "Long-lived client packet >>>".toByteArray()

        val receivedCount = AtomicInteger(0)

        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.onPacketReceived = { data, _ ->
            if (data.contentEquals(packetData)) {
                receivedCount.incrementAndGet()
            }
        }
        server!!.start()

        val client = LocalClientInterface(name = "LongLivedClient", tcpPort = tcpPort)
        clients.add(client)
        client.start()
        Thread.sleep(200)
        assertEquals(1, server!!.clientCount())

        repeat(numRounds) { round ->
            // Send one packet from the long-lived client.
            client.processOutgoing(packetData)

            // Churn transient sibling probes (open + immediate close), mimicking
            // the watchdog-probe shape that `Reticulum.isSharedInstanceRunning`
            // produces on every shared-instance auto-recovery poll.
            repeat(probesPerRound) {
                Socket().use { probe ->
                    probe.connect(InetSocketAddress("127.0.0.1", tcpPort), 1000)
                }
            }

            // Give the server's spawned children time to settle. The per-round
            // settle is only there so the inline `client.online.value` check
            // below isn't racing the probe-spawn detach machinery on slow
            // runners; the load-bearing assertion is the terminal
            // `receivedCount == numRounds` check which has its own 2 s drain.
            Thread.sleep(50)

            assertTrue(
                client.online.value,
                "Long-lived client went offline at round $round under sibling churn",
            )
        }

        // Wait for last packet to drain.
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && receivedCount.get() < numRounds) {
            Thread.sleep(50)
        }

        assertEquals(
            numRounds,
            receivedCount.get(),
            "Long-lived client sent $numRounds packets, server received ${receivedCount.get()}. " +
                "Read loop may have wedged under sibling churn.",
        )
    }

    @Test
    fun `test bidirectional communication via TCP`() {
        val tcpPort = 37433
        // Data must be > HEADER_MIN_SIZE (19) bytes to pass HDLC deframer validation
        val clientToServerData = "Client to server!!!!!".toByteArray()
        val serverToClientData = "Server to client!!!!!".toByteArray()

        val serverReceivedLatch = CountDownLatch(1)
        val clientReceivedLatch = CountDownLatch(1)

        var serverReceived: ByteArray? = null
        var clientReceived: ByteArray? = null

        // Start server
        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.onPacketReceived = { data, _ ->
            serverReceived = data
            serverReceivedLatch.countDown()
        }
        server!!.start()

        // Start client
        val client = LocalClientInterface(name = "TestClient", tcpPort = tcpPort)
        client.onPacketReceived = { data, _ ->
            clientReceived = data
            clientReceivedLatch.countDown()
        }
        clients.add(client)
        client.start()

        // Give connection time to establish
        Thread.sleep(200)

        // Client sends to server
        client.processOutgoing(clientToServerData)

        // Server sends to client (via spawned interface, as Transport would)
        val spawnedClient = server!!.getClients().first()
        spawnedClient.processOutgoing(serverToClientData)

        // Wait for both to receive
        assertTrue(serverReceivedLatch.await(2, TimeUnit.SECONDS))
        assertTrue(clientReceivedLatch.await(2, TimeUnit.SECONDS))

        assertArrayEquals(clientToServerData, serverReceived)
        assertArrayEquals(serverToClientData, clientReceived)
    }
}
