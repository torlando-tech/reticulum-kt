package network.reticulum.channel

import network.reticulum.link.LinkConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

/**
 * Stream data message for transmitting binary data over a Channel.
 *
 * Message type for Channel. StreamDataMessage uses a system-reserved message type.
 * The stream id is limited to 14 bits (0-16383).
 */
class StreamDataMessage : MessageBase() {
    companion object {
        const val STREAM_ID_MAX = 0x3FFF  // 16383

        // Overhead: 2 for stream data message header, 6 for channel envelope
        const val OVERHEAD = 2 + 6

        // MAX_DATA_LEN is calculated based on Link MDU
        // In Python: RNS.Link.MDU - OVERHEAD
        // Link.MDU is typically 431 bytes for default case
        val MAX_DATA_LEN = LinkConstants.MDU - OVERHEAD

        /**
         * Conformance test seam: bz2-compress [data] with the same codec
         * StreamDataMessage/RawChannelWriter use, so the wire bridge can craft a
         * genuine compressed bomb chunk (wire_buffer_stream bomb=true) without
         * needing commons-compress on its own classpath. Mirrors python
         * `bz2.compress(bytes(N))`. No port logic.
         */
        fun compressForTest(data: ByteArray): ByteArray {
            val outputStream = ByteArrayOutputStream()
            val bz2Stream = BZip2CompressorOutputStream(outputStream)
            bz2Stream.write(data)
            bz2Stream.close()
            return outputStream.toByteArray()
        }
    }

    override val msgType = SystemMessageTypes.SMT_STREAM_DATA

    var streamId: Int = 0
        set(value) {
            require(value in 0..STREAM_ID_MAX) { "stream_id must be 0-16383" }
            field = value
        }
    var data: ByteArray = ByteArray(0)
    var eof: Boolean = false
    var compressed: Boolean = false

    override fun pack(): ByteArray {
        require(streamId in 0..STREAM_ID_MAX) { "stream_id must be 0-16383" }

        // Python format: [header:2][data:N]
        // Header bits:
        //   Bit 15: EOF flag (0x8000)
        //   Bit 14: compressed flag (0x4000)
        //   Bits 13-0: stream_id (0-16383)
        var headerVal = streamId and 0x3FFF
        if (eof) headerVal = headerVal or 0x8000
        if (compressed) headerVal = headerVal or 0x4000

        val header = byteArrayOf(
            ((headerVal shr 8) and 0xFF).toByte(),
            (headerVal and 0xFF).toByte()
        )
        return header + data
    }

    override fun unpack(raw: ByteArray) {
        if (raw.size < 2) return

        // Parse header
        val headerVal = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)

        streamId = headerVal and 0x3FFF
        eof = (headerVal and 0x8000) != 0
        compressed = (headerVal and 0x4000) != 0
        data = if (raw.size > 2) raw.copyOfRange(2, raw.size) else ByteArray(0)

        // Decompress if needed
        if (compressed && data.isNotEmpty()) {
            data = decompressBZ2(data)
        }
    }

    private fun decompressBZ2(data: ByteArray): ByteArray {
        // Mirror python RNS StreamDataMessage.unpack (Buffer.py:94-97): bound the
        // decompression to MAX_CHUNK_LEN and raise IOError if the bz2 stream would
        // inflate past it (the bz2-bomb guard). Previously kotlin decompressed any
        // size unbounded, silently accepting a bomb and never aborting the reader.
        val maxLen = RawChannelWriter.MAX_CHUNK_LEN
        val inputStream = BZip2CompressorInputStream(ByteArrayInputStream(data))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (total < maxLen) {
            val r = inputStream.read(buf, 0, minOf(buf.size, maxLen - total))
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        // If any decompressed byte remains beyond MAX_CHUNK_LEN, the chunk is
        // over-bound (python: `if not decompressor.eof: raise IOError(...)`).
        if (inputStream.read() >= 0) {
            throw IOException("Decompressed buffer chunk exceeds maximum legitimate size")
        }
        return out.toByteArray()
    }
}

/**
 * An implementation of InputStream that receives binary stream data sent over a Channel.
 *
 * This class generally need not be instantiated directly.
 * Use [Buffer.createReader], [Buffer.createWriter], and
 * [Buffer.createBidirectional] functions to create buffered streams with optional callbacks.
 */
class RawChannelReader(
    private val streamId: Int,
    private val channel: Channel
) : InputStream(), Closeable {

    private val lock = ReentrantLock()
    private val buffer = LinkedBlockingQueue<Byte>()
    private val eofReceived = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val listeners = mutableListOf<(Int) -> Unit>()

    init {
        // Register stream data message type if not already registered.
        // Mirror python RNS RawChannelReader.__init__ (Buffer.py:127): the
        // SMT_STREAM_DATA type is in the system-reserved band (0xFF00 >= 0xF000),
        // so it MUST be registered with isSystemType=true — otherwise
        // registerMessageType raises "system-reserved" and the factory is never
        // installed, so inbound StreamDataMessages fail to unpack (ME_NOT_REGISTERED).
        try {
            channel.registerMessageType(SystemMessageTypes.SMT_STREAM_DATA, MessageFactory { StreamDataMessage() }, isSystemType = true)
        } catch (e: ChannelException) {
            // Already registered - OK
        }

        // Add handler for this stream
        channel.addMessageHandler(this::handleMessage)
    }

    /**
     * Add a function to be called when new data is available.
     * The function should have the signature `(readyBytes: Int) -> Unit`
     */
    fun addReadyCallback(callback: (Int) -> Unit) {
        lock.withLock {
            listeners.add(callback)
        }
    }

    /**
     * Remove a function added with [addReadyCallback]
     */
    fun removeReadyCallback(callback: (Int) -> Unit) {
        lock.withLock {
            listeners.remove(callback)
        }
    }

    private fun handleMessage(message: MessageBase): Boolean {
        if (message is StreamDataMessage && message.streamId == streamId) {
            lock.withLock {
                if (message.data.isNotEmpty()) {
                    for (b in message.data) {
                        buffer.offer(b)
                    }
                }
                if (message.eof) {
                    eofReceived.set(true)
                }

                // Notify callbacks in separate threads
                val bufferSize = buffer.size
                listeners.forEach { listener ->
                    try {
                        thread(name = "Message Callback", isDaemon = true) {
                            try {
                                listener(bufferSize)
                            } catch (ex: Exception) {
                                println("Error calling RawChannelReader($streamId) callback: ${ex.message}")
                            }
                        }
                    } catch (ex: Exception) {
                        println("Error starting RawChannelReader($streamId) callback thread: ${ex.message}")
                    }
                }
            }
            return true
        }
        return false
    }

    override fun read(): Int {
        if (closed.get()) return -1
        if (eofReceived.get() && buffer.isEmpty()) return -1

        return try {
            val byte = buffer.poll(100, TimeUnit.MILLISECONDS)
            byte?.toInt()?.and(0xFF) ?: if (eofReceived.get()) -1 else read()
        } catch (e: InterruptedException) {
            -1
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) return -1
        if (eofReceived.get() && buffer.isEmpty()) return -1

        var count = 0
        while (count < len) {
            val byte = buffer.poll(if (count == 0) 100 else 0, TimeUnit.MILLISECONDS)
            if (byte != null) {
                b[off + count] = byte
                count++
            } else {
                break
            }
        }

        return if (count > 0) count else if (eofReceived.get()) -1 else 0
    }

    override fun available(): Int = buffer.size

    /** Conformance test seam: whether the EOF marker was received (private
     *  `eofReceived`). Drives wire_buffer_received's eof field. */
    fun isEofForTest(): Boolean = eofReceived.get()

    /** Conformance test seam: the Channel this reader is bound to (private ctor
     *  val), mirroring the reference's `reader._channel`. */
    fun channelForTest(): Channel = channel

    fun readable(): Boolean = true
    fun writable(): Boolean = false
    fun seekable(): Boolean = false

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lock.withLock {
                channel.removeMessageHandler(this::handleMessage)
                listeners.clear()
            }
        }
    }
}

/**
 * An implementation of OutputStream that sends binary stream data over a Channel.
 *
 * This class generally need not be instantiated directly.
 * Use [Buffer.createReader], [Buffer.createWriter], and
 * [Buffer.createBidirectional] functions to create buffered streams with optional callbacks.
 */
class RawChannelWriter(
    private val streamId: Int,
    private val channel: Channel
) : OutputStream(), Closeable {

    companion object {
        const val MAX_CHUNK_LEN = 1024 * 16
        const val COMPRESSION_TRIES = 4
    }

    private val eofSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mdu = channel.mdu - StreamDataMessage.OVERHEAD

    init {
        // Register stream data message type if not already registered.
        // SMT_STREAM_DATA is system-reserved (0xFF00) and must register with
        // isSystemType=true (see RawChannelReader.init / python Buffer.py:127).
        try {
            channel.registerMessageType(SystemMessageTypes.SMT_STREAM_DATA, MessageFactory { StreamDataMessage() }, isSystemType = true)
        } catch (e: ChannelException) {
            // Already registered - OK
        }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed.get()) throw IllegalStateException("Stream is closed")

        val data = b.copyOfRange(off, off + len)
        writeInternal(data)
    }

    private fun writeInternal(bytes: ByteArray): Int {
        try {
            var compTries = COMPRESSION_TRIES
            var compTry = 1
            var compSuccess = false
            var chunkLen = bytes.size

            // Limit chunk size
            if (chunkLen > MAX_CHUNK_LEN) {
                chunkLen = MAX_CHUNK_LEN
            }
            val limitedBytes = bytes.copyOf(chunkLen)

            var chunk = limitedBytes
            var processedLength = chunkLen

            // Try compression if chunk is large enough
            while (chunkLen > 32 && compTry < compTries) {
                val chunkSegmentLength = chunkLen / compTry
                val compressedChunk = compressBZ2(limitedBytes.copyOf(chunkSegmentLength))
                val compressedLength = compressedChunk.size

                if (compressedLength < StreamDataMessage.MAX_DATA_LEN &&
                    compressedLength < chunkSegmentLength) {
                    compSuccess = true
                    chunk = compressedChunk
                    processedLength = chunkSegmentLength
                    break
                } else {
                    compTry++
                }
            }

            // If compression didn't help, send uncompressed
            if (!compSuccess) {
                chunk = limitedBytes.copyOf(minOf(StreamDataMessage.MAX_DATA_LEN, limitedBytes.size))
                processedLength = chunk.size
            }

            val message = StreamDataMessage().apply {
                this.streamId = this@RawChannelWriter.streamId
                this.data = chunk
                this.eof = eofSent.get()
                this.compressed = compSuccess
            }

            channel.send(message)
            return processedLength

        } catch (cex: ChannelException) {
            if (cex.type != ChannelExceptionType.ME_LINK_NOT_READY) {
                throw cex
            }
        }
        return 0
    }

    /** Conformance test seam: run one chunked write and return the processed
     *  length the public OutputStream.write swallows (it returns Unit). Mirrors
     *  the per-write() return RawChannelWriter.write exposes in python. Used by
     *  wire_buffer_stream's write loop. */
    fun writeChunkForTest(bytes: ByteArray): Int = writeInternal(bytes)

    /** Conformance test seam: set the private EOF flag so the NEXT
     *  writeChunkForTest emits an EOF-flagged StreamDataMessage (eof_with_data /
     *  default empty-EOF paths). */
    fun flagEofForTest() {
        eofSent.set(true)
    }

    fun writable(): Boolean = true
    fun readable(): Boolean = false
    fun seekable(): Boolean = false

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Calculate timeout based on RTT and pending messages
            try {
                val linkRtt = channel.outlet.rtt ?: 5000L
                val timeout = System.currentTimeMillis() + (linkRtt * 10)

                // Wait for channel to be ready
                while (System.currentTimeMillis() < timeout && !channel.isReadyToSend()) {
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Send EOF marker
            eofSent.set(true)
            writeInternal(ByteArray(0))
        }
    }

    private fun compressBZ2(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val bz2Stream = BZip2CompressorOutputStream(outputStream)
        bz2Stream.write(data)
        bz2Stream.close()
        return outputStream.toByteArray()
    }
}

/**
 * Static functions for creating buffered streams that send and receive over a Channel.
 *
 * These functions use standard Java InputStream and OutputStream to add buffering to
 * RawChannelReader and RawChannelWriter.
 *
 * Usage:
 * ```kotlin
 * // Create a reader
 * val reader = Buffer.createReader(streamId = 1, channel = channel)
 *
 * // Create a writer
 * val writer = Buffer.createWriter(streamId = 2, channel = channel)
 *
 * // Use like standard Java streams
 * writer.write("Hello, world!".toByteArray())
 * writer.close()
 *
 * val data = reader.readBytes()
 * reader.close()
 * ```
 */
object Buffer {
    /**
     * Create a buffered reader that reads binary data sent over a Channel,
     * with an optional callback when new data is available.
     *
     * Callback signature: `(readyBytes: Int) -> Unit`
     *
     * @param streamId the local stream id to receive from
     * @param channel the channel to receive on
     * @param readyCallback function to call when new data is available
     * @return a buffered InputStream
     */
    fun createReader(
        streamId: Int,
        channel: Channel,
        readyCallback: ((Int) -> Unit)? = null
    ): InputStream {
        val reader = RawChannelReader(streamId, channel)
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback)
        }
        return reader.buffered()
    }

    /**
     * Create a buffered writer that writes binary data over a Channel.
     *
     * @param streamId the remote stream id to send to
     * @param channel the channel to send on
     * @return a buffered OutputStream
     */
    fun createWriter(streamId: Int, channel: Channel): OutputStream {
        val writer = RawChannelWriter(streamId, channel)
        return writer.buffered()
    }

    /**
     * Create a buffered reader/writer pair that reads and writes binary data
     * over a Channel, with an optional callback when new data is available.
     *
     * Callback signature: `(readyBytes: Int) -> Unit`
     *
     * @param receiveStreamId the local stream id to receive at
     * @param sendStreamId the remote stream id to send to
     * @param channel the channel to send and receive on
     * @param readyCallback function to call when new data is available
     * @return a Pair of (reader, writer)
     */
    fun createBidirectional(
        receiveStreamId: Int,
        sendStreamId: Int,
        channel: Channel,
        readyCallback: ((Int) -> Unit)? = null
    ): Pair<InputStream, OutputStream> {
        val reader = RawChannelReader(receiveStreamId, channel)
        if (readyCallback != null) {
            reader.addReadyCallback(readyCallback)
        }
        val writer = RawChannelWriter(sendStreamId, channel)
        return Pair(reader.buffered(), writer.buffered())
    }
}
