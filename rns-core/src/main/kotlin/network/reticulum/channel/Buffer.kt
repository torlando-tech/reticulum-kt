package network.reticulum.channel

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Stream data message for transmitting binary data over a Channel.
 */
class StreamDataMessage : MessageBase() {
    override val msgType = SystemMessageTypes.SMT_STREAM_DATA

    var streamId: Int = 0
    var data: ByteArray = ByteArray(0)
    var eof: Boolean = false
    var compressed: Boolean = false

    override fun pack(): ByteArray {
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
    }
}

/**
 * Input stream that reads data from a Channel.
 *
 * Data arrives asynchronously and is buffered until read.
 */
class ChannelInputStream(
    private val streamId: Int,
    private val channel: Channel
) : InputStream(), Closeable {

    private val buffer = LinkedBlockingQueue<Byte>()
    private val eofReceived = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val readyCallbacks = mutableListOf<(Int) -> Unit>()

    init {
        // Register stream data message type if not already registered
        try {
            channel.registerMessageType(SystemMessageTypes.SMT_STREAM_DATA) { StreamDataMessage() }
        } catch (e: ChannelException) {
            // Already registered - OK
        }

        // Add handler for this stream
        channel.addMessageHandler(this::handleMessage)
    }

    private fun handleMessage(message: MessageBase): Boolean {
        if (message is StreamDataMessage && message.streamId == streamId) {
            // Decompress if needed
            val data = if (message.compressed) {
                decompress(message.data)
            } else {
                message.data
            }

            // Add to buffer
            for (b in data) {
                buffer.offer(b)
            }

            if (message.eof) {
                eofReceived.set(true)
            }

            // Notify ready callbacks
            synchronized(readyCallbacks) {
                readyCallbacks.forEach { it(buffer.size) }
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

    /**
     * Add a callback for when data is ready to read.
     */
    fun addReadyCallback(callback: (Int) -> Unit) {
        synchronized(readyCallbacks) {
            readyCallbacks.add(callback)
        }
    }

    /**
     * Remove a ready callback.
     */
    fun removeReadyCallback(callback: (Int) -> Unit) {
        synchronized(readyCallbacks) {
            readyCallbacks.remove(callback)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            channel.removeMessageHandler(this::handleMessage)
        }
    }

    private fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = mutableListOf<Byte>()
        val buffer = ByteArray(256)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) break
            for (i in 0 until count) {
                output.add(buffer[i])
            }
        }
        inflater.end()
        return output.toByteArray()
    }
}

/**
 * Output stream that writes data to a Channel.
 *
 * Data is chunked and sent as stream messages.
 */
class ChannelOutputStream(
    private val streamId: Int,
    private val channel: Channel,
    private val compress: Boolean = false
) : OutputStream(), Closeable {

    private val closed = AtomicBoolean(false)
    private val chunkSize = channel.mdu - 2  // Subtract stream header (2 bytes)

    init {
        // Register stream data message type if not already registered
        try {
            channel.registerMessageType(SystemMessageTypes.SMT_STREAM_DATA) { StreamDataMessage() }
        } catch (e: ChannelException) {
            // Already registered - OK
        }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed.get()) throw IllegalStateException("Stream is closed")

        var offset = off
        var remaining = len

        while (remaining > 0) {
            // Wait for channel to be ready
            while (!channel.isReadyToSend) {
                Thread.sleep(10)
                if (closed.get()) return
            }

            val toSend = minOf(remaining, chunkSize)
            val chunk = b.copyOfRange(offset, offset + toSend)

            val message = StreamDataMessage().apply {
                this.streamId = this@ChannelOutputStream.streamId
                this.data = if (compress) compress(chunk) else chunk
                this.compressed = compress
                this.eof = false
            }

            channel.send(message)

            offset += toSend
            remaining -= toSend
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Send EOF marker
            while (!channel.isReadyToSend) {
                Thread.sleep(10)
            }

            val eofMessage = StreamDataMessage().apply {
                this.streamId = this@ChannelOutputStream.streamId
                this.data = ByteArray(0)
                this.eof = true
            }
            channel.send(eofMessage)
        }
    }

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val output = mutableListOf<Byte>()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            for (i in 0 until count) {
                output.add(buffer[i])
            }
        }
        deflater.end()
        return output.toByteArray()
    }
}

/**
 * Factory for creating buffered streams over a Channel.
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
 * ```
 */
object Buffer {
    /**
     * Create a buffered reader for receiving stream data.
     *
     * @param streamId Unique identifier for this stream
     * @param channel The channel to receive data from
     * @return An InputStream that reads from the channel
     */
    fun createReader(streamId: Int, channel: Channel): InputStream {
        return ChannelInputStream(streamId, channel)
    }

    /**
     * Create a buffered writer for sending stream data.
     *
     * @param streamId Unique identifier for this stream
     * @param channel The channel to send data to
     * @param compress Whether to compress data before sending
     * @return An OutputStream that writes to the channel
     */
    fun createWriter(streamId: Int, channel: Channel, compress: Boolean = false): OutputStream {
        return ChannelOutputStream(streamId, channel, compress)
    }

    /**
     * Create a bidirectional buffer pair.
     *
     * @param receiveStreamId Stream ID for receiving
     * @param sendStreamId Stream ID for sending
     * @param channel The channel to use
     * @param compress Whether to compress outgoing data
     * @return Pair of (reader, writer)
     */
    fun createBidirectional(
        receiveStreamId: Int,
        sendStreamId: Int,
        channel: Channel,
        compress: Boolean = false
    ): Pair<InputStream, OutputStream> {
        return Pair(
            createReader(receiveStreamId, channel),
            createWriter(sendStreamId, channel, compress)
        )
    }
}
