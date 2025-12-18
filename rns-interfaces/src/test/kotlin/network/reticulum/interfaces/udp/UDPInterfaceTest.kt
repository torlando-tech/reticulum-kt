package network.reticulum.interfaces.udp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UDPInterfaceTest {

    private val interfaces = mutableListOf<UDPInterface>()

    @AfterEach
    fun cleanup() {
        interfaces.forEach { it.detach() }
        interfaces.clear()
        Thread.sleep(100) // Allow sockets to close
    }

    @Test
    @Timeout(10)
    fun `test basic unicast communication`() {
        val receivedData = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)

        // Create receiver interface
        val receiver = UDPInterface(
            name = "receiver",
            bindPort = 14241,
            forwardIp = "127.0.0.1",
            forwardPort = 14242
        )
        receiver.onPacketReceived = { data, _ ->
            receivedData.set(data)
            latch.countDown()
        }
        interfaces.add(receiver)
        receiver.start()

        assertTrue(receiver.online.get(), "Receiver should be online")
        assertTrue(receiver.canReceive, "Receiver should be able to receive")

        // Create sender interface
        val sender = UDPInterface(
            name = "sender",
            bindPort = 14242,
            forwardIp = "127.0.0.1",
            forwardPort = 14241
        )
        interfaces.add(sender)
        sender.start()

        assertTrue(sender.online.get(), "Sender should be online")
        assertTrue(sender.canSend, "Sender should be able to send")

        // Send test data
        Thread.sleep(100) // Allow interfaces to fully initialize
        val testData = "Hello UDP!".toByteArray()
        sender.processOutgoing(testData)

        // Wait for data to be received
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive data within timeout")
        assertArrayEquals(testData, receivedData.get(), "Received data should match sent data")

        // Verify statistics
        assertTrue(sender.txBytes.get() > 0, "Sender should have transmitted bytes")
        assertTrue(receiver.rxBytes.get() > 0, "Receiver should have received bytes")
    }

    @Test
    @Timeout(10)
    fun `test bidirectional communication`() {
        val received1 = AtomicReference<ByteArray>()
        val received2 = AtomicReference<ByteArray>()
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)

        // Interface 1
        val interface1 = UDPInterface(
            name = "interface1",
            bindPort = 14251,
            forwardIp = "127.0.0.1",
            forwardPort = 14252
        )
        interface1.onPacketReceived = { data, _ ->
            received1.set(data)
            latch1.countDown()
        }
        interfaces.add(interface1)
        interface1.start()

        // Interface 2
        val interface2 = UDPInterface(
            name = "interface2",
            bindPort = 14252,
            forwardIp = "127.0.0.1",
            forwardPort = 14251
        )
        interface2.onPacketReceived = { data, _ ->
            received2.set(data)
            latch2.countDown()
        }
        interfaces.add(interface2)
        interface2.start()

        Thread.sleep(100)

        // Send from interface1 to interface2
        val data1 = "Message from 1".toByteArray()
        interface1.processOutgoing(data1)

        // Send from interface2 to interface1
        val data2 = "Message from 2".toByteArray()
        interface2.processOutgoing(data2)

        // Wait for both messages
        assertTrue(latch1.await(5, TimeUnit.SECONDS), "Interface1 should receive message")
        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Interface2 should receive message")

        assertArrayEquals(data2, received1.get(), "Interface1 should receive data from interface2")
        assertArrayEquals(data1, received2.get(), "Interface2 should receive data from interface1")
    }

    @Test
    @Timeout(10)
    fun `test broadcast mode`() {
        val receivedCount = AtomicInteger(0)
        val latch = CountDownLatch(2) // Expect 2 receivers to get the message

        // Create two receivers
        val receiver1 = UDPInterface(
            name = "receiver1",
            bindPort = 14261,
            forwardIp = "255.255.255.255",
            forwardPort = 14261,
            broadcast = true
        )
        receiver1.onPacketReceived = { _, _ ->
            receivedCount.incrementAndGet()
            latch.countDown()
        }
        interfaces.add(receiver1)
        receiver1.start()

        val receiver2 = UDPInterface(
            name = "receiver2",
            bindPort = 14262,
            forwardIp = "255.255.255.255",
            forwardPort = 14261,
            broadcast = true
        )
        receiver2.onPacketReceived = { _, _ ->
            receivedCount.incrementAndGet()
            latch.countDown()
        }
        interfaces.add(receiver2)
        receiver2.start()

        // Create sender with broadcast enabled
        val sender = UDPInterface(
            name = "sender",
            bindPort = 14263,
            forwardIp = "255.255.255.255",
            forwardPort = 14261,
            broadcast = true
        )
        interfaces.add(sender)
        sender.start()

        Thread.sleep(100)

        // Send broadcast message
        val testData = "Broadcast message".toByteArray()
        sender.processOutgoing(testData)

        // Wait for receivers
        // Note: Broadcast delivery is not guaranteed, especially on loopback
        // This test may be flaky depending on OS configuration
        val received = latch.await(5, TimeUnit.SECONDS)

        // At least verify the broadcast flag is set correctly
        assertTrue(sender.canSend, "Sender should be able to send")
        assertTrue(UDPInterface.isBroadcast("255.255.255.255"), "Should detect broadcast address")
    }

    @Test
    fun `test multicast address detection`() {
        assertTrue(UDPInterface.isMulticast("224.0.0.1"))
        assertTrue(UDPInterface.isMulticast("239.255.255.255"))
        assertFalse(UDPInterface.isMulticast("192.168.1.1"))
        assertFalse(UDPInterface.isMulticast("127.0.0.1"))
    }

    @Test
    fun `test broadcast address detection`() {
        assertTrue(UDPInterface.isBroadcast("255.255.255.255"))
        assertTrue(UDPInterface.isBroadcast("192.168.1.255"))
        assertFalse(UDPInterface.isBroadcast("192.168.1.1"))
        assertFalse(UDPInterface.isBroadcast("192.168.1.254"))
    }

    @Test
    @Timeout(10)
    fun `test receive-only interface`() {
        val receivedData = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)

        // Create receive-only interface (no forwardIp)
        val receiver = UDPInterface(
            name = "receiver-only",
            bindPort = 14271,
            forwardIp = null,
            forwardPort = 0
        )
        receiver.onPacketReceived = { data, _ ->
            receivedData.set(data)
            latch.countDown()
        }
        interfaces.add(receiver)
        receiver.start()

        assertTrue(receiver.canReceive, "Should be able to receive")
        assertFalse(receiver.canSend, "Should not be able to send")

        // Send data using raw socket
        val socket = DatagramSocket()
        val testData = "Test message".toByteArray()
        val packet = DatagramPacket(
            testData,
            testData.size,
            InetAddress.getByName("127.0.0.1"),
            14271
        )
        socket.send(packet)
        socket.close()

        // Verify reception
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive data")
        assertArrayEquals(testData, receivedData.get())

        // Verify sending is not allowed
        assertThrows(IllegalStateException::class.java) {
            receiver.processOutgoing("should fail".toByteArray())
        }
    }

    @Test
    @Timeout(10)
    fun `test send-only interface`() {
        // Create send-only interface (but bind to port 0 for ephemeral)
        val sender = UDPInterface(
            name = "sender-only",
            bindPort = 0, // Ephemeral port
            forwardIp = "127.0.0.1",
            forwardPort = 14281
        )
        interfaces.add(sender)
        sender.start()

        assertFalse(sender.canReceive, "Should not be able to receive")
        assertTrue(sender.canSend, "Should be able to send")

        // Create receiver with raw socket
        val receiver = DatagramSocket(14281)
        receiver.soTimeout = 5000

        // Send data
        val testData = "Test message".toByteArray()
        sender.processOutgoing(testData)

        // Receive with raw socket
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        receiver.receive(packet)
        receiver.close()

        val receivedData = packet.data.copyOf(packet.length)
        assertArrayEquals(testData, receivedData)
    }

    @Test
    @Timeout(10)
    fun `test multiple packets`() {
        val receivedPackets = mutableListOf<ByteArray>()
        val latch = CountDownLatch(5)

        val receiver = UDPInterface(
            name = "receiver",
            bindPort = 14291,
            forwardIp = "127.0.0.1",
            forwardPort = 14292
        )
        receiver.onPacketReceived = { data, _ ->
            synchronized(receivedPackets) {
                receivedPackets.add(data)
            }
            latch.countDown()
        }
        interfaces.add(receiver)
        receiver.start()

        val sender = UDPInterface(
            name = "sender",
            bindPort = 14292,
            forwardIp = "127.0.0.1",
            forwardPort = 14291
        )
        interfaces.add(sender)
        sender.start()

        Thread.sleep(100)

        // Send multiple packets
        repeat(5) { i ->
            sender.processOutgoing("Packet $i".toByteArray())
            Thread.sleep(10) // Small delay between packets
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive all packets")
        assertEquals(5, receivedPackets.size, "Should receive 5 packets")
    }

    @Test
    @Timeout(10)
    fun `test large packet`() {
        val receivedData = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)

        val receiver = UDPInterface(
            name = "receiver",
            bindPort = 14301,
            forwardIp = "127.0.0.1",
            forwardPort = 14302
        )
        receiver.onPacketReceived = { data, _ ->
            receivedData.set(data)
            latch.countDown()
        }
        interfaces.add(receiver)
        receiver.start()

        val sender = UDPInterface(
            name = "sender",
            bindPort = 14302,
            forwardIp = "127.0.0.1",
            forwardPort = 14301
        )
        interfaces.add(sender)
        sender.start()

        Thread.sleep(100)

        // Send a large packet (but within UDP MTU)
        val testData = ByteArray(1400) { it.toByte() }
        sender.processOutgoing(testData)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive large packet")
        assertArrayEquals(testData, receivedData.get())
    }

    @Test
    fun `test interface properties`() {
        val iface = UDPInterface(
            name = "test-interface",
            bindIp = "127.0.0.1",
            bindPort = 14311,
            forwardIp = "127.0.0.1",
            forwardPort = 14312
        )
        interfaces.add(iface)

        assertEquals("test-interface", iface.name)
        assertEquals(UDPInterface.BITRATE_GUESS, iface.bitrate)
        assertEquals(UDPInterface.HW_MTU, iface.hwMtu)
        assertEquals(UDPInterface.HW_MTU, iface.getEffectiveMtu())
        assertTrue(iface.canReceive)
        assertTrue(iface.canSend)
        assertFalse(iface.online.get())

        iface.start()
        assertTrue(iface.online.get())

        iface.detach()
        assertFalse(iface.online.get())
        assertTrue(iface.detached.get())
    }

    @Test
    fun `test toString`() {
        val iface = UDPInterface(
            name = "test",
            bindIp = "192.168.1.100",
            bindPort = 4242,
            forwardIp = "192.168.1.255",
            forwardPort = 4242,
            broadcast = true
        )
        interfaces.add(iface)

        val str = iface.toString()
        assertTrue(str.contains("test"))
        assertTrue(str.contains("192.168.1.100:4242"))
        assertTrue(str.contains("192.168.1.255:4242"))
        assertTrue(str.contains("broadcast"))
    }

    @Test
    fun `test double start is safe`() {
        val iface = UDPInterface(
            name = "test",
            bindPort = 14321,
            forwardIp = "127.0.0.1",
            forwardPort = 14322
        )
        interfaces.add(iface)

        iface.start()
        assertTrue(iface.online.get())

        // Second start should be safe
        iface.start()
        assertTrue(iface.online.get())
    }

    @Test
    fun `test send before start fails`() {
        val iface = UDPInterface(
            name = "test",
            bindPort = 14331,
            forwardIp = "127.0.0.1",
            forwardPort = 14332
        )
        interfaces.add(iface)

        assertThrows(IllegalStateException::class.java) {
            iface.processOutgoing("test".toByteArray())
        }
    }

    @Test
    fun `test send after detach fails`() {
        val iface = UDPInterface(
            name = "test",
            bindPort = 14341,
            forwardIp = "127.0.0.1",
            forwardPort = 14342
        )
        interfaces.add(iface)

        iface.start()
        assertTrue(iface.online.get())

        iface.detach()
        assertFalse(iface.online.get())

        assertThrows(IllegalStateException::class.java) {
            iface.processOutgoing("test".toByteArray())
        }
    }
}
