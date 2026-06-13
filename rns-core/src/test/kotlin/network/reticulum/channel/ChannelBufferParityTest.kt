package network.reticulum.channel

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mirroring unit tests for the Channel / Buffer parity fixes made for the
 * conformance bridge phase 5e. Each pins a behavior change against the python
 * RNS contract it converges to, driving the real production code through a
 * lightweight in-memory [FakeOutlet] (no Link / wire required).
 */
class ChannelBufferParityTest {

    /** Minimal in-memory ChannelOutlet: every send produces an opaque packet
     *  reported as SENT, so Channel.send treats it as transmitted-with-receipt. */
    private class FakeOutlet(
        override val mdu: Int = 500,
        override val rtt: Long? = 0L,
    ) : ChannelOutlet {
        var failSend = false
        var notifyTimedOutCalls = 0
        override fun send(raw: ByteArray): Any? = if (failSend) null else Any()
        override fun resend(packet: Any): Any? = packet
        override val isUsable: Boolean get() = true
        override val timedOut: Boolean get() = false
        override fun getPacketState(packet: Any): Int = MessageState.SENT
        override fun setPacketTimeoutCallback(packet: Any, callback: ((Any) -> Unit)?, timeout: Long?) {}
        override fun setPacketDeliveredCallback(packet: Any, callback: ((Any) -> Unit)?) {}
        override fun getPacketId(packet: Any): Any = packet
        override fun notifyTimedOut() { notifyTimedOutCalls++ }
    }

    private class Probe(override val msgType: Int, var payload: ByteArray = ByteArray(0)) : MessageBase() {
        override fun pack(): ByteArray = payload
        override fun unpack(raw: ByteArray) { payload = raw }
    }

    private fun channel(mdu: Int = 500): Channel = Channel(FakeOutlet(mdu = mdu))

    // #D1: ME_TOO_BIG fires BEFORE the sequence advances / envelope is emplaced.
    @Test
    fun `send rejects oversize with ME_TOO_BIG without advancing sequence`() {
        val ch = channel(mdu = 20)
        ch.registerMessageType(0x0101, MessageFactory { Probe(0x0101) })
        // packed = 6-byte header + 20-byte payload = 26 > outlet.mdu(20).
        val ex = assertFailsWith<ChannelException> { ch.send(Probe(0x0101, ByteArray(20))) }
        assertEquals(ChannelExceptionType.ME_TOO_BIG, ex.type)
        val s = ch.stateForTest()
        assertEquals(0, s.nextSequence)
        assertEquals(0, s.txRing)
    }

    // #D2: a non-transmitting outlet restores the reserved sequence + raises
    // ME_LINK_NOT_READY; the next send reuses the freed sequence (no gap).
    @Test
    fun `send restores sequence and raises ME_LINK_NOT_READY when outlet does not transmit`() {
        val ch = channel()
        ch.registerMessageType(0x0101, MessageFactory { Probe(0x0101) })
        ch.failNextSendForTest = true
        val ex = assertFailsWith<ChannelException> { ch.send(Probe(0x0101, "hi".toByteArray())) }
        assertEquals(ChannelExceptionType.ME_LINK_NOT_READY, ex.type)
        assertEquals(0, ch.stateForTest().nextSequence)
        assertEquals(0, ch.stateForTest().txRing)

        val env = ch.send(Probe(0x0101, "ok".toByteArray()))
        assertEquals(0, env.sequence)
        assertEquals(1, ch.stateForTest().nextSequence)
    }

    // #D7: Envelope.unpack ignores the on-wire length field and delivers raw[6:].
    @Test
    fun `receive ignores envelope length field and delivers full payload`() {
        val ch = channel()
        val delivered = mutableListOf<ByteArray>()
        ch.registerMessageType(0x0101, MessageFactory { Probe(0x0101) })
        ch.addMessageHandler { m -> if (m is Probe) { delivered.add(m.payload); true } else false }
        val payload = "length-mismatch!".toByteArray() // 16 bytes
        // header: msgType=0x0101, sequence=0, length=1 (deliberately wrong).
        val raw = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x01) + payload
        ch.receive(raw)
        assertEquals(1, delivered.size)
        assertEquals(payload.toList(), delivered[0].toList())
    }

    // Contiguous-delivery loop must NOT break on a non-matching ring head, so it
    // crosses the 0xFFFF->0 modulus boundary (the [0,0xFFFF] ring ordering).
    @Test
    fun `contiguous delivery crosses the sequence wrap boundary`() {
        val ch = channel()
        val delivered = mutableListOf<ByteArray>()
        ch.registerMessageType(0x0101, MessageFactory { Probe(0x0101) })
        ch.addMessageHandler { m -> if (m is Probe) { delivered.add(m.payload); true } else false }
        // Drive the receive counter up to 0xFFFF by delivering 0..0xFFFE in order.
        for (seq in 0 until 0xFFFF) {
            ch.receive(ch.packEnvelopeForTest(Probe(0x0101, ByteArray(0)), seq))
        }
        assertEquals(0xFFFF, ch.stateForTest().nextRxSequence)

        delivered.clear()
        // seq=0 arrives early -> in-window, buffered, not delivered.
        ch.receive(ch.packEnvelopeForTest(Probe(0x0101, "zero".toByteArray()), 0))
        assertTrue(delivered.isEmpty())
        assertEquals(1, ch.stateForTest().rxRing)
        // seq=0xFFFF completes the run -> delivers [0xFFFF, 0] across the wrap.
        ch.receive(ch.packEnvelopeForTest(Probe(0x0101, "last".toByteArray()), 0xFFFF))
        assertEquals(listOf("last", "zero"), delivered.map { String(it) })
        assertEquals(1, ch.stateForTest().nextRxSequence)
        assertEquals(0, ch.stateForTest().rxRing)
    }

    // Channel.mdu = min(outlet.mdu - 6, 0xFFFF).
    @Test
    fun `channel mdu is capped at 0xFFFF`() {
        assertEquals(494, channel(mdu = 500).mdu)
        assertEquals(0xFFFF, channel(mdu = 70000).mdu)
    }

    // #D3: retransmission exhaustion shuts the channel down AND notifies the
    // outlet (which, for a LinkChannelOutlet, tears the Link down).
    @Test
    fun `retransmission exhaustion shuts down and notifies the outlet`() {
        val outlet = FakeOutlet()
        val ch = Channel(outlet)
        ch.registerMessageType(0x0101, MessageFactory { Probe(0x0101) })
        ch.addMessageHandler { _ -> true }
        val env = ch.send(Probe(0x0101, "x".toByteArray()))
        val pkt = env.packet!!
        // The packet never delivers (FakeOutlet keeps it SENT) -> fire timeouts.
        repeat(6) { ch.firePacketTimeoutForTest(pkt) }
        assertEquals(5, env.tries)
        assertEquals(1, outlet.notifyTimedOutCalls)
        assertEquals(0, ch.stateForTest().txRing)
        assertEquals(0, ch.stateForTest().messageHandlers) // _shutdown cleared handlers
    }

    // #D5: StreamDataMessage.unpack accepts a chunk inflating to exactly
    // MAX_CHUNK_LEN but aborts (IOException) one byte over.
    @Test
    fun `StreamDataMessage unpack enforces the 16384 decompression bound`() {
        val okRaw = packStream(StreamDataMessage.compressForTest(ByteArray(16384)))
        val ok = StreamDataMessage()
        ok.unpack(okRaw)
        assertEquals(16384, ok.data.size)

        val bombRaw = packStream(StreamDataMessage.compressForTest(ByteArray(16385)))
        assertFailsWith<IOException> { StreamDataMessage().unpack(bombRaw) }
    }

    // #D9: RawChannelReader registers SMT_STREAM_DATA as a SYSTEM type, so an
    // inbound StreamDataMessage unpacks and is reassembled (no ME_NOT_REGISTERED).
    @Test
    fun `RawChannelReader registers the system stream type and reassembles`() {
        val ch = channel()
        val reader = RawChannelReader(0, ch)
        val sdm = StreamDataMessage().apply { streamId = 0; data = "hello".toByteArray(); eof = true }
        ch.receive(ch.packEnvelopeForTest(sdm, 0))
        val buf = ByteArray(16)
        val n = reader.read(buf, 0, buf.size)
        assertEquals("hello", String(buf, 0, n))
    }

    private fun packStream(compressedBody: ByteArray): ByteArray =
        StreamDataMessage().apply {
            streamId = 0
            data = compressedBody
            compressed = true
        }.pack()
}
