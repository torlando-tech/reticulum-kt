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
     * @param hwMtu Upper bound on the unescaped frame size in bytes. A frame
     *   longer than this is dropped, mirroring RNS 1.5.5's
     *   `Interface.check_frame_len` (TCPInterface.py) upper cap
     *   (`frame_len > HW_MTU + ifac_size -> drop`). Defaults to Int.MAX_VALUE
     *   (no cap) so callers that don't have an HW MTU are unaffected.
     * @param onFrame Callback invoked with each complete deframed packet
     * @return Deframer instance
     */
    fun createDeframer(
        hwMtu: Int = Int.MAX_VALUE,
        onFrame: (ByteArray) -> Unit,
    ): Deframer {
        return Deframer(hwMtu, onFrame)
    }

    /**
     * Streaming HDLC deframer.
     *
     * Accumulates incoming bytes and emits complete frames via callback.
     * Frames whose unescaped length exceeds [hwMtu] are dropped (RNS 1.5.5
     * check_frame_len upper cap); frames of HEADER_MIN_SIZE bytes or less are
     * dropped (the runt guard below).
     */
    class Deframer(
        private val hwMtu: Int = Int.MAX_VALUE,
        private val onFrame: (ByteArray) -> Unit,
    ) {
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
                        // RNS 1.5.5 check_frame_len: a frame of
                        // HEADER_MIN_SIZE (19) bytes or less is malformed
                        // (cannot hold a valid packet), and a frame exceeding
                        // the interface HW MTU is dropped, not delivered.
                        if (unescaped.size > network.reticulum.common.RnsConstants.HEADER_MIN_SIZE &&
                            unescaped.size <= hwMtu
                        ) {
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
