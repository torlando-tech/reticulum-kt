package network.reticulum.interfaces.framing

import java.io.ByteArrayOutputStream

/**
 * HDLC-like framing for Reticulum interfaces.
 *
 * Frame format: [FLAG][escaped data][FLAG]
 *
 * Escape sequences:
 * - FLAG (0x7E) in data → ESC (0x7D) + 0x5E (FLAG XOR ESC_MASK)
 * - ESC (0x7D) in data → ESC (0x7D) + 0x5D (ESC XOR ESC_MASK)
 */
object HDLC {
    /** Frame boundary flag. */
    const val FLAG: Byte = 0x7E

    /** Escape character. */
    const val ESC: Byte = 0x7D

    /** XOR mask for escaped bytes. */
    const val ESC_MASK: Byte = 0x20

    /**
     * Escape data for HDLC framing.
     *
     * @param data Raw data to escape
     * @return Escaped data (without frame flags)
     */
    fun escape(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + data.size / 10)

        for (byte in data) {
            when (byte) {
                FLAG -> {
                    output.write(ESC.toInt())
                    output.write((FLAG.toInt() xor ESC_MASK.toInt()))
                }
                ESC -> {
                    output.write(ESC.toInt())
                    output.write((ESC.toInt() xor ESC_MASK.toInt()))
                }
                else -> output.write(byte.toInt())
            }
        }

        return output.toByteArray()
    }

    /**
     * Unescape HDLC-escaped data.
     *
     * @param data Escaped data (without frame flags)
     * @return Unescaped data
     */
    fun unescape(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size)
        var escape = false

        for (byte in data) {
            if (escape) {
                output.write(byte.toInt() xor ESC_MASK.toInt())
                escape = false
            } else if (byte == ESC) {
                escape = true
            } else {
                output.write(byte.toInt())
            }
        }

        return output.toByteArray()
    }

    /**
     * Frame data with HDLC framing.
     *
     * @param data Raw data to frame
     * @return Framed data: [FLAG][escaped data][FLAG]
     */
    fun frame(data: ByteArray): ByteArray {
        val escaped = escape(data)
        val output = ByteArray(escaped.size + 2)
        output[0] = FLAG
        System.arraycopy(escaped, 0, output, 1, escaped.size)
        output[output.size - 1] = FLAG
        return output
    }

    /**
     * Create a frame deframer for streaming data.
     *
     * @param onFrame Callback invoked with each complete deframed packet
     * @return Deframer instance
     */
    fun createDeframer(onFrame: (ByteArray) -> Unit): Deframer {
        return Deframer(onFrame)
    }

    /**
     * Streaming HDLC deframer.
     *
     * Accumulates incoming bytes and emits complete frames via callback.
     */
    class Deframer(private val onFrame: (ByteArray) -> Unit) {
        private var buffer = ByteArrayOutputStream()
        private var inFrame = false

        /**
         * Process incoming bytes.
         *
         * @param data Incoming byte data
         */
        fun process(data: ByteArray) {
            for (byte in data) {
                if (byte == FLAG) {
                    if (inFrame && buffer.size() > 0) {
                        // End of frame
                        val frameData = buffer.toByteArray()
                        buffer.reset()
                        val unescaped = unescape(frameData)
                        // Python RNS: Only accept frames larger than HEADER_MINSIZE (19 bytes)
                        // Rejects malformed/tiny frames that can't contain a valid packet
                        if (unescaped.size > network.reticulum.common.RnsConstants.HEADER_MIN_SIZE) {
                            onFrame(unescaped)
                        }
                    }
                    // Start of new frame (or just a flag)
                    inFrame = true
                    buffer.reset()
                } else if (inFrame) {
                    buffer.write(byte.toInt())
                }
            }
        }

        /**
         * Reset the deframer state.
         */
        fun reset() {
            buffer.reset()
            inFrame = false
        }
    }
}
