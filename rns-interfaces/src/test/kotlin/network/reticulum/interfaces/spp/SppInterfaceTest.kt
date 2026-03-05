package network.reticulum.interfaces.spp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import network.reticulum.interfaces.framing.HDLC

/**
 * Unit tests for [SppInterface] using piped streams as a mock transport.
 *
 * Tests verify:
 * 1. HDLC framing round-trip (send → frame → deframe → receive)
 * 2. Bidirectional communication between client and server
 * 3. Reconnect behavior when connection drops
 */
@Timeout(15, unit = TimeUnit.SECONDS)
class SppInterfaceTest {

    private val interfaces = mutableListOf<SppInterface>()
    private val drivers = mutableListOf<MockSppDriver>()

    @AfterEach
    fun tearDown() {
        interfaces.forEach { it.detach() }
        interfaces.clear()
        drivers.forEach { it.shutdown() }
        drivers.clear()
        Thread.sleep(100)
    }

    @Test
    fun `test HDLC framing round-trip via processOutgoing`() {
        // Create piped stream pair: interface writes → we read
        val ifaceToUs = PipedInputStream(4096)
        val ifaceWritePipe = PipedOutputStream(ifaceToUs)

        // We write → interface reads
        val usToIface = PipedOutputStream()
        val ifaceReadPipe = PipedInputStream(usToIface, 4096)

        val driver = MockSppDriver(
            connectResult = SppConnection(
                inputStream = ifaceReadPipe,
                outputStream = ifaceWritePipe,
                remoteAddress = "AA:BB:CC:DD:EE:FF",
                remoteName = "TestDevice",
                close = {
                    ifaceWritePipe.close()
                    ifaceReadPipe.close()
                },
            )
        )
        drivers.add(driver)

        val iface = SppInterface(
            name = "TestSPP",
            driver = driver,
            targetAddress = "AA:BB:CC:DD:EE:FF",
        )
        interfaces.add(iface)

        // Collect received packets
        val receivedPackets = CopyOnWriteArrayList<ByteArray>()
        val receiveLatch = CountDownLatch(1)
        iface.onPacketReceived = { data, _ ->
            receivedPackets.add(data)
            receiveLatch.countDown()
        }

        iface.start()

        // Wait for interface to come online
        waitForCondition { iface.online.get() }

        // Test 1: Send data via processOutgoing, verify HDLC framing on wire
        // Payload must be > 19 bytes (HEADER_MIN_SIZE) or deframer will drop it
        val testPayload = ByteArray(32) { (it + 0x41).toByte() } // "ABCDEFGH..."
        iface.processOutgoing(testPayload)

        // Read the framed data from the pipe
        val framedData = readAvailable(ifaceToUs)
        assertTrue(framedData.isNotEmpty(), "Should have received framed data")
        assertEquals(HDLC.FLAG, framedData[0], "Frame should start with FLAG")
        assertEquals(HDLC.FLAG, framedData[framedData.size - 1], "Frame should end with FLAG")

        // Test 2: Write HDLC-framed data into the interface, verify it deframes
        val inboundPayload = ByteArray(32) { (it + 0x61).toByte() } // "abcdefgh..."
        val framedInbound = HDLC.frame(inboundPayload)
        usToIface.write(framedInbound)
        usToIface.flush()

        assertTrue(receiveLatch.await(5, TimeUnit.SECONDS), "Should have received deframed packet")
        assertEquals(1, receivedPackets.size)
        assertArrayEquals(inboundPayload, receivedPackets[0])
    }

    @Test
    fun `test bidirectional communication between client and server`() {
        // Create two pipe pairs connecting client ↔ server
        val clientToServer = PipedOutputStream()
        val serverFromClient = PipedInputStream(clientToServer, 4096)

        val serverToClient = PipedOutputStream()
        val clientFromServer = PipedInputStream(serverToClient, 4096)

        val clientDriver = MockSppDriver(
            connectResult = SppConnection(
                inputStream = clientFromServer,
                outputStream = clientToServer,
                remoteAddress = "11:22:33:44:55:66",
                remoteName = "Server",
                close = {
                    clientToServer.close()
                    clientFromServer.close()
                },
            )
        )

        val serverDriver = MockSppDriver(
            acceptResult = SppConnection(
                inputStream = serverFromClient,
                outputStream = serverToClient,
                remoteAddress = "AA:BB:CC:DD:EE:FF",
                remoteName = "Client",
                close = {
                    serverToClient.close()
                    serverFromClient.close()
                },
            )
        )

        drivers.addAll(listOf(clientDriver, serverDriver))

        val clientIface = SppInterface(
            name = "Client",
            driver = clientDriver,
            targetAddress = "11:22:33:44:55:66",
            serverMode = false,
        )
        val serverIface = SppInterface(
            name = "Server",
            driver = serverDriver,
            targetAddress = "AA:BB:CC:DD:EE:FF",
            serverMode = true,
        )
        interfaces.addAll(listOf(clientIface, serverIface))

        // Collect packets
        val clientReceived = CopyOnWriteArrayList<ByteArray>()
        val serverReceived = CopyOnWriteArrayList<ByteArray>()
        val clientLatch = CountDownLatch(1)
        val serverLatch = CountDownLatch(1)

        clientIface.onPacketReceived = { data, _ ->
            clientReceived.add(data)
            clientLatch.countDown()
        }
        serverIface.onPacketReceived = { data, _ ->
            serverReceived.add(data)
            serverLatch.countDown()
        }

        clientIface.start()
        serverIface.start()

        waitForCondition { clientIface.online.get() && serverIface.online.get() }

        // Client → Server (payload > 19 bytes)
        val clientPayload = ByteArray(40) { (it + 0x30).toByte() }
        clientIface.processOutgoing(clientPayload)

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "Server should receive from client")
        assertArrayEquals(clientPayload, serverReceived[0])

        // Server → Client
        val serverPayload = ByteArray(40) { (it + 0x50).toByte() }
        serverIface.processOutgoing(serverPayload)

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS), "Client should receive from server")
        assertArrayEquals(serverPayload, clientReceived[0])
    }

    @Test
    fun `test reconnect on connection loss`() {
        val connectCount = AtomicInteger(0)
        val firstConnection = createPipedConnection("AA:BB:CC:DD:EE:FF", "Device")
        val secondConnection = createPipedConnection("AA:BB:CC:DD:EE:FF", "Device")

        val driver = object : SppDriver {
            override suspend fun connect(address: String, secure: Boolean): SppConnection {
                val count = connectCount.incrementAndGet()
                return when (count) {
                    1 -> firstConnection
                    2 -> secondConnection
                    else -> throw IOException("No more connections")
                }
            }

            override suspend fun accept(serviceName: String, uuid: UUID, secure: Boolean): SppConnection {
                throw UnsupportedOperationException("Not in server mode")
            }

            override fun cancelAccept() {}
            override fun listPairedDevices(): List<SppDevice> = emptyList()
            override fun shutdown() {}
        }

        val iface = SppInterface(
            name = "ReconnectTest",
            driver = driver,
            targetAddress = "AA:BB:CC:DD:EE:FF",
            maxReconnectAttempts = 5,
        )
        interfaces.add(iface)
        iface.start()

        // Wait for first connection
        waitForCondition { iface.online.get() }
        assertEquals(1, connectCount.get())

        // Kill the first connection by closing its streams
        firstConnection.close()

        // Wait for reconnection to succeed
        waitForCondition(timeoutMs = 10_000) { connectCount.get() >= 2 && iface.online.get() }
        assertEquals(2, connectCount.get())
        assertTrue(iface.online.get(), "Should be online after reconnection")
    }

    @Test
    fun `test processOutgoing throws when offline`() {
        val driver = MockSppDriver(connectException = IOException("Connection refused"))
        drivers.add(driver)

        val iface = SppInterface(
            name = "OfflineTest",
            driver = driver,
            targetAddress = "AA:BB:CC:DD:EE:FF",
            maxReconnectAttempts = 0,
        )
        interfaces.add(iface)

        // Don't start — interface should be offline
        assertFalse(iface.online.get())
        assertThrows(IllegalStateException::class.java) {
            iface.processOutgoing(ByteArray(32))
        }
    }

    @Test
    fun `test SPP_UUID matches standard Bluetooth SIG Serial Port Profile`() {
        assertEquals(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            SppInterface.SPP_UUID,
        )
    }

    @Test
    fun `test HW_MTU matches Python SerialInterface`() {
        assertEquals(564, SppInterface.HW_MTU)
    }

    // --- Helpers ---

    private fun createPipedConnection(address: String, name: String): SppConnection {
        val input = PipedInputStream(4096)
        val output = PipedOutputStream()
        // Connect the pipes so the interface can read what it writes
        // (In a real test we'd connect them to the other side, but for
        // reconnect testing we just need valid streams that can be closed)
        val dummyReader = PipedInputStream(output, 4096)
        val dummyWriter = PipedOutputStream(input)

        return SppConnection(
            inputStream = input,
            outputStream = output,
            remoteAddress = address,
            remoteName = name,
            close = {
                try { input.close() } catch (_: Exception) {}
                try { output.close() } catch (_: Exception) {}
                try { dummyReader.close() } catch (_: Exception) {}
                try { dummyWriter.close() } catch (_: Exception) {}
            },
        )
    }

    private fun readAvailable(stream: InputStream, timeoutMs: Long = 2000): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (stream.available() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val buffer = ByteArray(stream.available())
        if (buffer.isNotEmpty()) {
            stream.read(buffer)
        }
        return buffer
    }

    private fun waitForCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (!condition()) {
            fail<Unit>("Condition not met within ${timeoutMs}ms")
        }
    }

    /**
     * Simple mock SppDriver that returns preconfigured connections.
     */
    private class MockSppDriver(
        private val connectResult: SppConnection? = null,
        private val acceptResult: SppConnection? = null,
        private val connectException: Exception? = null,
    ) : SppDriver {

        override suspend fun connect(address: String, secure: Boolean): SppConnection {
            connectException?.let { throw it }
            return connectResult ?: throw IOException("No mock connection configured")
        }

        override suspend fun accept(serviceName: String, uuid: UUID, secure: Boolean): SppConnection {
            return acceptResult ?: throw IOException("No mock accept configured")
        }

        override fun cancelAccept() {}
        override fun listPairedDevices(): List<SppDevice> = emptyList()
        override fun shutdown() {}
    }
}
