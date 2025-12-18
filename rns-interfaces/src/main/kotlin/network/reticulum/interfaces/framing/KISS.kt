package network.reticulum.interfaces.framing

import java.io.ByteArrayOutputStream

/**
 * KISS (Keep It Simple, Stupid) framing for TNC interfaces.
 *
 * Frame format: [FEND][CMD][escaped data][FEND]
 *
 * Escape sequences:
 * - FEND (0xC0) in data → FESC (0xDB) + TFEND (0xDC)
 * - FESC (0xDB) in data → FESC (0xDB) + TFESC (0xDD)
 */
object KISS {
    /** Frame End delimiter. */
    const val FEND: Byte = 0xC0.toByte()

    /** Frame Escape character. */
    const val FESC: Byte = 0xDB.toByte()

    /** Transposed Frame End (used after FESC). */
    const val TFEND: Byte = 0xDC.toByte()

    /** Transposed Frame Escape (used after FESC). */
    const val TFESC: Byte = 0xDD.toByte()

    /** Data command (port 0). */
    const val CMD_DATA: Byte = 0x00

    /** Unknown/invalid command. */
    const val CMD_UNKNOWN: Byte = 0xFE.toByte()

    /**
     * Escape data for KISS framing.
     *
     * @param data Raw data to escape
     * @return Escaped data (without frame delimiters)
     */
    fun escape(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + data.size / 10)

        for (byte in data) {
            when (byte) {
                FESC -> {
                    output.write(FESC.toInt() and 0xFF)
                    output.write(TFESC.toInt() and 0xFF)
                }
                FEND -> {
                    output.write(FESC.toInt() and 0xFF)
                    output.write(TFEND.toInt() and 0xFF)
                }
                else -> output.write(byte.toInt() and 0xFF)
            }
        }

        return output.toByteArray()
    }

    /**
     * Unescape KISS-escaped data.
     *
     * @param data Escaped data (without frame delimiters)
     * @return Unescaped data
     */
    fun unescape(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size)
        var escape = false

        for (byte in data) {
            if (escape) {
                when (byte) {
                    TFEND -> output.write(FEND.toInt() and 0xFF)
                    TFESC -> output.write(FESC.toInt() and 0xFF)
                    else -> output.write(byte.toInt() and 0xFF)
                }
                escape = false
            } else if (byte == FESC) {
                escape = true
            } else {
                output.write(byte.toInt() and 0xFF)
            }
        }

        return output.toByteArray()
    }

    /**
     * Frame data with KISS framing.
     *
     * @param data Raw data to frame
     * @param command KISS command byte (default: CMD_DATA)
     * @return Framed data: [FEND][CMD][escaped data][FEND]
     */
    fun frame(data: ByteArray, command: Byte = CMD_DATA): ByteArray {
        val escaped = escape(data)
        val output = ByteArray(escaped.size + 3)
        output[0] = FEND
        output[1] = command
        System.arraycopy(escaped, 0, output, 2, escaped.size)
        output[output.size - 1] = FEND
        return output
    }

    /**
     * Create a frame deframer for streaming data.
     *
     * @param onFrame Callback invoked with (command, data) for each complete frame
     * @return Deframer instance
     */
    fun createDeframer(onFrame: (command: Byte, data: ByteArray) -> Unit): Deframer {
        return Deframer(onFrame)
    }

    /**
     * Streaming KISS deframer.
     *
     * Accumulates incoming bytes and emits complete frames via callback.
     */
    class Deframer(private val onFrame: (command: Byte, data: ByteArray) -> Unit) {
        private var buffer = ByteArrayOutputStream()
        private var inFrame = false
        private var command: Byte = CMD_UNKNOWN

        /**
         * Process incoming bytes.
         *
         * @param data Incoming byte data
         */
        fun process(data: ByteArray) {
            for (byte in data) {
                when {
                    byte == FEND -> {
                        if (inFrame && buffer.size() > 0 && command == CMD_DATA) {
                            // End of frame
                            val frameData = buffer.toByteArray()
                            buffer.reset()
                            val unescaped = unescape(frameData)
                            if (unescaped.isNotEmpty()) {
                                onFrame(command, unescaped)
                            }
                        }
                        // Start of new frame
                        inFrame = true
                        command = CMD_UNKNOWN
                        buffer.reset()
                    }
                    inFrame && buffer.size() == 0 && command == CMD_UNKNOWN -> {
                        // First byte after FEND is the command
                        // Strip port nibble (upper 4 bits)
                        command = (byte.toInt() and 0x0F).toByte()
                    }
                    inFrame && command == CMD_DATA -> {
                        buffer.write(byte.toInt() and 0xFF)
                    }
                }
            }
        }

        /**
         * Reset the deframer state.
         */
        fun reset() {
            buffer.reset()
            inFrame = false
            command = CMD_UNKNOWN
        }
    }
}
