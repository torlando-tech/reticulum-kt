package network.reticulum.link

import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests verifying that reticulum-kt links can handle LXST voice call patterns.
 *
 * NativeNetworkTransport (columba) uses these link features:
 * - link.send(data) for fire-and-forget audio packets
 * - Single-byte packets for signalling (0x00-0x06, 0xFF)
 * - Multi-byte packets for encoded audio frames (~20-80 bytes per frame)
 * - Bidirectional data flow on the same link
 * - High packet rate (~50 packets/sec for real-time audio)
 *
 * These tests use Python interop (real TCP) to verify the link layer
 * supports these patterns end-to-end.
 */
@DisplayName("Voice Data Flow Tests")
class VoiceDataFlowTest : RnsLiveTestBase() {

    override val appName: String = "lxst"
    override val aspects: Array<String> = arrayOf("telephony")

    @Test
    @DisplayName("Single-byte signalling packets delivered Kotlin → Python")
    @Timeout(30)
    fun `single-byte signalling packets delivered kotlin to python`() {
        val destination = createPythonOutDestination()
        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(destination, establishedCallback = { l ->
            link = l
            establishedLatch.countDown()
        })

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // LXST signalling constants
        val signals = listOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0xFF)

        for (signal in signals) {
            link!!.send(byteArrayOf(signal.toByte()))
        }

        // Wait a bit for delivery
        Thread.sleep(1000)

        val pythonPackets = getPythonPackets()
        println("  [Test] Python received ${pythonPackets.size} packets")

        assertTrue(pythonPackets.size >= signals.size,
            "Python should receive all ${signals.size} signal packets, got ${pythonPackets.size}")

        // Verify signal values
        for (i in signals.indices) {
            assertEquals(1, pythonPackets[i].size, "Signal packet $i should be 1 byte")
            assertEquals(signals[i].toByte(), pythonPackets[i][0], "Signal $i value mismatch")
        }

        println("  [Test] All ${signals.size} signalling packets verified!")
        link!!.teardown()
    }

    @Test
    @DisplayName("Multi-byte audio frames delivered intact Kotlin → Python")
    @Timeout(30)
    fun `multi-byte audio frames delivered intact kotlin to python`() {
        val destination = createPythonOutDestination()
        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(destination, establishedCallback = { l ->
            link = l
            establishedLatch.countDown()
        })

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Simulate Opus audio frames (typical sizes: 20-80 bytes)
        val frames = listOf(20, 40, 60, 80, 40).map { size ->
            byteArrayOf(0x01) + Random.nextBytes(size - 1) // 0x01 = Opus codec header
        }

        for (frame in frames) {
            link!!.send(frame)
            Thread.sleep(20) // ~50fps pacing
        }

        Thread.sleep(1000)

        val pythonPackets = getPythonPackets()
        println("  [Test] Python received ${pythonPackets.size} audio frames")

        assertTrue(pythonPackets.size >= frames.size,
            "Python should receive all ${frames.size} frames, got ${pythonPackets.size}")

        for (i in frames.indices) {
            assertEquals(frames[i].size, pythonPackets[i].size, "Frame $i size mismatch")
            assertTrue(frames[i].contentEquals(pythonPackets[i]), "Frame $i content mismatch")
        }

        println("  [Test] All ${frames.size} audio frames verified!")
        link!!.teardown()
    }

    @Test
    @DisplayName("Bidirectional data flow works (full-duplex voice)")
    @Timeout(30)
    fun `bidirectional data flow works`() {
        val destination = createPythonOutDestination()
        val establishedLatch = CountDownLatch(1)
        var link: Link? = null
        val kotlinReceived = CopyOnWriteArrayList<ByteArray>()

        Link.create(destination, establishedCallback = { l ->
            link = l
            l.setPacketCallback { data, _ ->
                kotlinReceived.add(data)
            }
            establishedLatch.countDown()
        })

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Kotlin → Python: send 5 audio frames
        val k2pFrames = (0 until 5).map { byteArrayOf(0x01) + Random.nextBytes(39) }
        for (frame in k2pFrames) {
            link!!.send(frame)
            Thread.sleep(20)
        }

        // Python → Kotlin: send 5 audio frames
        val p2kData = Random.nextBytes(40)
        for (i in 0 until 5) {
            sendFromPython(byteArrayOf(0x02) + p2kData.copyOfRange(i * 8, i * 8 + 8))
            Thread.sleep(20)
        }

        // Wait for all deliveries
        Thread.sleep(2000)

        // Verify K→P
        val pythonPackets = getPythonPackets()
        println("  [Test] Python received ${pythonPackets.size} packets from Kotlin")
        assertTrue(pythonPackets.size >= k2pFrames.size,
            "Python should receive all ${k2pFrames.size} frames")

        // Verify P→K
        println("  [Test] Kotlin received ${kotlinReceived.size} packets from Python")
        assertTrue(kotlinReceived.size >= 5,
            "Kotlin should receive all 5 frames from Python, got ${kotlinReceived.size}")

        println("  [Test] Bidirectional voice data flow verified!")
        link!!.teardown()
    }

    @Test
    @DisplayName("Mixed signalling and audio interleaving works")
    @Timeout(30)
    fun `mixed signalling and audio interleaving works`() {
        val destination = createPythonOutDestination()
        val establishedLatch = CountDownLatch(1)
        var link: Link? = null

        Link.create(destination, establishedCallback = { l ->
            link = l
            establishedLatch.countDown()
        })

        assertTrue(establishedLatch.await(15, TimeUnit.SECONDS), "Link should establish")
        waitForPythonLink(5000)

        // Simulate LXST call flow: signal → signal → audio frames → signal
        val sentPackets = mutableListOf<ByteArray>()
        sentPackets.add(byteArrayOf(0x02)) // STATUS_CALLING
        sentPackets.add(byteArrayOf(0x05)) // STATUS_CONNECTING
        sentPackets.add(byteArrayOf(0x06)) // STATUS_ESTABLISHED
        // 10 audio frames
        for (i in 0 until 10) {
            sentPackets.add(byteArrayOf(0x01) + Random.nextBytes(39))
        }
        sentPackets.add(byteArrayOf(0x03)) // STATUS_AVAILABLE (hangup)

        for (packet in sentPackets) {
            link!!.send(packet)
            if (packet.size > 1) Thread.sleep(20) // Audio pacing
        }

        Thread.sleep(1500)

        val pythonPackets = getPythonPackets()
        println("  [Test] Python received ${pythonPackets.size}/${sentPackets.size} packets")

        assertTrue(pythonPackets.size >= sentPackets.size,
            "Python should receive all ${sentPackets.size} packets, got ${pythonPackets.size}")

        // Verify ordering and content
        for (i in sentPackets.indices) {
            assertTrue(sentPackets[i].contentEquals(pythonPackets[i]),
                "Packet $i mismatch: sent ${sentPackets[i].size}B, received ${pythonPackets[i].size}B")
        }

        // Verify signal/audio distinction
        val signals = pythonPackets.take(sentPackets.size).filter { it.size == 1 }
        val audio = pythonPackets.take(sentPackets.size).filter { it.size > 1 }
        assertEquals(4, signals.size, "Should have 4 signalling packets")
        assertEquals(10, audio.size, "Should have 10 audio frames")

        println("  [Test] Mixed signalling + audio interleaving verified!")
        link!!.teardown()
    }
}
