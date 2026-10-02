package network.reticulum.interfaces.local

import network.reticulum.transport.Transport
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
     * a long-lived spawned child's read loop - inbound bytes stop draining even
     * though the socket remains ESTABLISHED and outbound bytes still flow. The
     * suspected interaction was a redundant `withContext(Dispatchers.IO)` inside
     * the read loop (already running on `ioScope`'s IO dispatcher), which this
     * commit removes to match python's direct synchronous `socket.recv(4096)`
     * pattern at `RNS/Interfaces/LocalInterface.py:302`.
     *
     * The wedge is not deterministically reproducible on a desktop JVM, so this
     * test does not assert "wedge is fixed" - it asserts "long-lived spawned
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

    /**
     * Regression: transient probe-style connections (open + immediate close,
     * the shape that `Reticulum.isSharedInstanceRunning(port)` produces on
     * every shared-instance auto-recovery poll) should not leave stale entries
     * in `Transport.localClientInterfaces`. Python's `LocalInterface.teardown()`
     * at `RNS/Interfaces/LocalInterface.py:353-354` removes the spawned interface
     * from `Transport.local_client_interfaces` via `in` + `remove` - identity
     * equality on the same `spawned_interface` object that was appended at
     * `LocalInterface.py:462`. The kotlin port relies on the same identity
     * invariant via the `InterfaceAdapter.getOrCreate` cache; this test fails
     * loudly if that invariant ever breaks (e.g. via the read-loop / register
     * ordering race fixed in this commit).
     */
    @Test
    fun `transient probe connections do not leak Transport localClientInterfaces entries`() {
        val tcpPort = 37434
        val numProbes = 10
        val baseline = Transport.localClientCount()

        server = LocalServerInterface(name = "TestServer", tcpPort = tcpPort)
        server!!.start()

        repeat(numProbes) {
            Socket().use { probe ->
                probe.connect(InetSocketAddress("127.0.0.1", tcpPort), 1000)
                // Immediately close - mimics a watchdog probe.
            }
            Thread.sleep(20)
        }

        // Poll until the server has reaped all transient spawned children.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && server!!.clientCount() > 0) {
            Thread.sleep(50)
        }

        assertEquals(
            0,
            server!!.clientCount(),
            "Server still reports live spawned children after probes closed; LocalClientInterface.readLoop / detach path did not run",
        )
        assertEquals(
            baseline,
            Transport.localClientCount(),
            "Transport.localClientInterfaces accumulated stale entries after $numProbes transient probe connects (expected baseline=$baseline)",
        )
    }

    /**
     * Registration failure regression: when Transport.registerInterface throws
     * (the JVM/coroutine pragmatic that motivated the try/catch in
     * handleNewClient), the spawned child must be rolled back cleanly:
     * removed from clients and spawnedInterfaces, the socket closed, and the
     * read loop never started. Exercises the catch block that is otherwise
     * unreachable in normal operation (registerInterface realistically never
     * throws).
     */
    @Test
    fun `registration failure rolls back the spawned child and closes the socket`() {
        val tcpPort = 37436
        val baselineClients = Transport.localClientCount()
        val hookInvocations = AtomicInteger(0)

        val srv = LocalServerInterface(name = "RegFailServer", tcpPort = tcpPort)
        srv.registerInterfaceForTest = { _ ->
            hookInvocations.incrementAndGet()
            throw IllegalStateException("simulated registration failure")
        }
        srv.start()

        // Connect a socket; handleNewClient will add the child to clients,
        // then invoke the hook (which throws), then the catch block rolls back.
        val probe = Socket()
        probe.connect(InetSocketAddress("127.0.0.1", tcpPort), 1000)
        probe.close()

        // Wait for the accept loop to process the connection (the hook must
        // run exactly once - proof the connection reached handleNewClient and
        // the simulated registration-failure path was entered).
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && hookInvocations.get() < 1) {
            Thread.sleep(10)
        }
        assertEquals(
            1,
            hookInvocations.get(),
            "The simulated registration hook was not invoked; the test cannot " +
                "confirm the registration-failure path ran"
        )

        // Wait for the catch block to roll the child back out of clients.
        val deadline2 = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline2 && srv.clientCount() > 0) {
            Thread.sleep(10)
        }

        // The spawned child was rolled back by the catch block.
        assertEquals(
            0,
            srv.clientCount(),
            "Server should have rolled back the spawned child after registration failure"
        )
        // No stale entry in Transport (the hook threw, so registerInterface
        // was never called; the catch must not have added one either).
        assertEquals(
            baselineClients,
            Transport.localClientCount(),
            "Transport.localClientInterfaces should be unchanged after registration failure"
        )

        srv.detach()
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
