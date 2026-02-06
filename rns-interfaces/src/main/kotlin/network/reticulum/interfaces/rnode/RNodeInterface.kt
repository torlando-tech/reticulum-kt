package network.reticulum.interfaces.rnode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.framing.KISS
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.pow

/**
 * RNode interface for Reticulum — connects to RNode LoRa hardware via
 * an arbitrary InputStream/OutputStream (BLE, serial, TCP, etc.).
 *
 * Ports the Python RNodeInterface protocol:
 * - KISS-framed command exchange for radio configuration
 * - Byte-level state machine for ~30 command types (readLoop)
 * - Flow control: device sends CMD_READY when ready for next packet
 * - Detect → initRadio → validateRadioState → online
 *
 * The existing [KISS.Deframer] is NOT used here — it only handles CMD_DATA
 * with port-nibble stripping for TCP KISS. RNode needs per-command dispatch
 * with varying payload sizes (1-byte TXPOWER, 4-byte FREQUENCY, 11-byte CHTM, etc.).
 */
class RNodeInterface(
    name: String,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val frequency: Long,
    private val bandwidth: Long,
    private val txPower: Int,
    private val spreadingFactor: Int,
    private val codingRate: Int,
    private val flowControl: Boolean = true,
    private val parentScope: CoroutineScope? = null,
) : Interface(name) {

    companion object {
        private const val RSSI_OFFSET = 157
        private const val REQUIRED_FW_VER_MAJ = 1
        private const val REQUIRED_FW_VER_MIN = 52
        private const val FREQ_MIN = 137_000_000L
        private const val FREQ_MAX = 3_000_000_000L
        private const val DETECT_TIMEOUT_MS = 5_000L
        private const val VALIDATE_TIMEOUT_MS = 2_000L
        private const val READ_TIMEOUT_MS = 1_250L
    }

    override val hwMtu: Int = 508

    override val bitrate: Int
        get() = computedBitrate

    private var computedBitrate: Int = 0

    // Radio state echoed back from device
    private var rFrequency: Long? = null
    private var rBandwidth: Long? = null
    private var rTxpower: Int? = null
    private var rSf: Int? = null
    private var rCr: Int? = null
    private var rState: Int? = null
    private var rLock: Int? = null

    // Firmware
    private var majVersion: Int = 0
    private var minVersion: Int = 0
    private var firmwareOk: Boolean = false
    private var platform: Int? = null
    private var mcu: Int? = null
    private var detected: Boolean = false

    // Statistics
    @Volatile var rStatRssi: Int? = null; private set
    @Volatile var rStatSnr: Double? = null; private set
    @Volatile var rBatteryState: Int = 0; private set
    @Volatile var rBatteryPercent: Int = 0; private set

    // Flow control
    @Volatile private var interfaceReady: Boolean = false
    private val packetQueue = CopyOnWriteArrayList<ByteArray>()
    private val txLock = Any()

    // Coroutine management
    private val ioScope: CoroutineScope = if (parentScope != null) {
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    } else {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private var readJob: Job? = null

    override fun start() {
        ioScope.launch {
            try {
                configure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Failed to configure RNode: ${e.message}")
                online.set(false)
            }
        }
    }

    /**
     * Full configuration sequence matching Python's configure_device():
     * reset state → start readLoop → detect → initRadio → validateRadioState → online
     */
    private suspend fun configure() {
        rFrequency = null; rBandwidth = null; rTxpower = null; rSf = null; rCr = null; rState = null
        detected = false

        // Start read loop BEFORE sending detect (Python does this too)
        readJob = ioScope.launch { readLoop() }

        // Small delay for BLE to settle (matches Python's sleep(2.0) for serial)
        delay(500)

        detect()

        // Wait for detect response
        val detectStart = System.currentTimeMillis()
        while (!detected && (System.currentTimeMillis() - detectStart) < DETECT_TIMEOUT_MS) {
            delay(100)
        }

        if (!detected) {
            throw IOException("Could not detect RNode device")
        }

        log("RNode detected (firmware $majVersion.$minVersion, platform=${platformName()})")

        initRadio()

        // Wait for radio state to be echoed back (BLE is slower)
        delay(VALIDATE_TIMEOUT_MS)

        if (validateRadioState()) {
            interfaceReady = true
            delay(300)
            online.set(true)
            log("RNode is configured and online")
        } else {
            throw IOException("Radio parameter validation failed — device reported different values than configured")
        }
    }

    // -- KISS command helpers (matching Python's detect/setFrequency/etc.) --

    /**
     * Send detect command: requests DETECT, FW_VERSION, PLATFORM, MCU
     * (matches Python line 484)
     */
    private fun detect() {
        val cmd = byteArrayOf(
            KISS.FEND, KISS.CMD_DETECT, KISS.DETECT_REQ, KISS.FEND,
            KISS.FEND, KISS.CMD_FW_VERSION, 0x00, KISS.FEND,
            KISS.FEND, KISS.CMD_PLATFORM, 0x00, KISS.FEND,
            KISS.FEND, KISS.CMD_MCU, 0x00, KISS.FEND,
        )
        writeRaw(cmd)
    }

    private fun initRadio() {
        sendFrequency()
        sendBandwidth()
        sendTxPower()
        sendSpreadingFactor()
        sendCodingRate()
        sendRadioState(KISS.RADIO_STATE_ON)
    }

    private fun sendFrequency() {
        val data = ByteArray(4)
        data[0] = (frequency shr 24).toByte()
        data[1] = (frequency shr 16 and 0xFF).toByte()
        data[2] = (frequency shr 8 and 0xFF).toByte()
        data[3] = (frequency and 0xFF).toByte()
        sendKissCommand(KISS.CMD_FREQUENCY, data)
    }

    private fun sendBandwidth() {
        val data = ByteArray(4)
        data[0] = (bandwidth shr 24).toByte()
        data[1] = (bandwidth shr 16 and 0xFF).toByte()
        data[2] = (bandwidth shr 8 and 0xFF).toByte()
        data[3] = (bandwidth and 0xFF).toByte()
        sendKissCommand(KISS.CMD_BANDWIDTH, data)
    }

    private fun sendTxPower() {
        sendKissCommand(KISS.CMD_TXPOWER, byteArrayOf(txPower.toByte()))
    }

    private fun sendSpreadingFactor() {
        sendKissCommand(KISS.CMD_SF, byteArrayOf(spreadingFactor.toByte()))
    }

    private fun sendCodingRate() {
        sendKissCommand(KISS.CMD_CR, byteArrayOf(codingRate.toByte()))
    }

    private fun sendRadioState(state: Byte) {
        sendKissCommand(KISS.CMD_RADIO_STATE, byteArrayOf(state))
    }

    private fun sendKissCommand(command: Byte, data: ByteArray) {
        val escaped = KISS.escape(data)
        val frame = ByteArray(escaped.size + 3)
        frame[0] = KISS.FEND
        frame[1] = command
        System.arraycopy(escaped, 0, frame, 2, escaped.size)
        frame[frame.size - 1] = KISS.FEND
        writeRaw(frame)
    }

    private fun writeRaw(data: ByteArray) {
        try {
            outputStream.write(data)
            outputStream.flush()
        } catch (e: IOException) {
            log("Write error: ${e.message}")
            throw e
        }
    }

    private fun validateRadioState(): Boolean {
        var valid = true

        if (rFrequency != null && abs(frequency - rFrequency!!) > 100) {
            log("Frequency mismatch: configured=$frequency, reported=$rFrequency")
            valid = false
        }
        if (rBandwidth != null && bandwidth != rBandwidth) {
            log("Bandwidth mismatch: configured=$bandwidth, reported=$rBandwidth")
            valid = false
        }
        if (rTxpower != null && txPower != rTxpower) {
            log("TX power mismatch: configured=$txPower, reported=$rTxpower")
            valid = false
        }
        if (rSf != null && spreadingFactor != rSf) {
            log("Spreading factor mismatch: configured=$spreadingFactor, reported=$rSf")
            valid = false
        }

        return valid
    }

    private fun validateFirmware() {
        firmwareOk = when {
            majVersion > REQUIRED_FW_VER_MAJ -> true
            majVersion == REQUIRED_FW_VER_MAJ && minVersion >= REQUIRED_FW_VER_MIN -> true
            else -> false
        }

        if (!firmwareOk) {
            log("WARNING: Firmware $majVersion.$minVersion is below required $REQUIRED_FW_VER_MAJ.$REQUIRED_FW_VER_MIN")
        }
    }

    private fun updateBitrate() {
        try {
            val sf = rSf ?: return
            val cr = rCr ?: return
            val bw = rBandwidth ?: return
            // LoRa bitrate formula: SF * (4/CR) / (2^SF / (BW/1000)) * 1000
            computedBitrate = (sf * (4.0 / cr) / (2.0.pow(sf.toDouble()) / (bw / 1000.0)) * 1000).toInt()
        } catch (_: Exception) {
            computedBitrate = 0
        }
    }

    // -- Read loop: byte-level state machine matching Python readLoop (lines 743-1132) --

    /**
     * Byte-by-byte state machine processing KISS frames from the RNode.
     *
     * Unlike the generic [KISS.Deframer] (which only handles CMD_DATA),
     * this handles all ~30 RNode command types with their varying payload sizes:
     * - CMD_DATA: variable length, KISS-escaped packet data
     * - CMD_FREQUENCY/BANDWIDTH: 4 bytes big-endian
     * - CMD_TXPOWER/SF/CR/RADIO_STATE: 1 byte
     * - CMD_FW_VERSION: 2 bytes
     * - CMD_STAT_CHTM: 11 bytes (airtime, channel load, RSSI, noise floor)
     * - CMD_STAT_BAT: 2 bytes (state + percent)
     * - etc.
     */
    private suspend fun readLoop() {
        var inFrame = false
        var escape = false
        var command: Byte = KISS.CMD_UNKNOWN
        val dataBuffer = ByteArrayOutputStream(512)
        val commandBuffer = ByteArrayOutputStream(16)
        var lastReadMs = System.currentTimeMillis()

        val buf = ByteArray(1)

        try {
            while (ioScope.isActive && !detached.get()) {
                val bytesRead = withContext(Dispatchers.IO) {
                    inputStream.read(buf)
                }

                if (bytesRead <= 0) {
                    // Check for idle timeout
                    val timeSinceLast = System.currentTimeMillis() - lastReadMs
                    if (dataBuffer.size() > 0 && timeSinceLast > READ_TIMEOUT_MS) {
                        log("Read timeout in command ${command.toInt() and 0xFF}")
                        dataBuffer.reset()
                        commandBuffer.reset()
                        inFrame = false
                        command = KISS.CMD_UNKNOWN
                        escape = false
                    }
                    if (bytesRead == -1) break
                    delay(10)
                    continue
                }

                lastReadMs = System.currentTimeMillis()
                var byte = buf[0].toInt() and 0xFF

                if (inFrame && byte == (KISS.FEND.toInt() and 0xFF) && command == KISS.CMD_DATA) {
                    // End of data frame
                    inFrame = false
                    val data = dataBuffer.toByteArray()
                    dataBuffer.reset()
                    commandBuffer.reset()
                    if (data.isNotEmpty()) {
                        processIncoming(data)
                        rStatRssi = null
                        rStatSnr = null
                    }
                } else if (byte == (KISS.FEND.toInt() and 0xFF)) {
                    // Start of new frame
                    inFrame = true
                    command = KISS.CMD_UNKNOWN
                    dataBuffer.reset()
                    commandBuffer.reset()
                } else if (inFrame && dataBuffer.size() < hwMtu) {
                    if (dataBuffer.size() == 0 && commandBuffer.size() == 0 && command == KISS.CMD_UNKNOWN) {
                        // First byte after FEND is the command
                        command = byte.toByte()
                    } else if (command == KISS.CMD_DATA) {
                        // Data frame: accumulate with KISS unescaping
                        if (byte == (KISS.FESC.toInt() and 0xFF)) {
                            escape = true
                        } else {
                            if (escape) {
                                if (byte == (KISS.TFEND.toInt() and 0xFF)) byte = KISS.FEND.toInt() and 0xFF
                                if (byte == (KISS.TFESC.toInt() and 0xFF)) byte = KISS.FESC.toInt() and 0xFF
                                escape = false
                            }
                            dataBuffer.write(byte)
                        }
                    } else if (command == KISS.CMD_FREQUENCY) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 4) {
                                val b = commandBuffer.toByteArray()
                                rFrequency = (b[0].toLong() and 0xFF shl 24) or
                                    (b[1].toLong() and 0xFF shl 16) or
                                    (b[2].toLong() and 0xFF shl 8) or
                                    (b[3].toLong() and 0xFF)
                                commandBuffer.reset()
                                updateBitrate()
                            }
                        }
                    } else if (command == KISS.CMD_BANDWIDTH) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 4) {
                                val b = commandBuffer.toByteArray()
                                rBandwidth = (b[0].toLong() and 0xFF shl 24) or
                                    (b[1].toLong() and 0xFF shl 16) or
                                    (b[2].toLong() and 0xFF shl 8) or
                                    (b[3].toLong() and 0xFF)
                                commandBuffer.reset()
                                updateBitrate()
                            }
                        }
                    } else if (command == KISS.CMD_TXPOWER) {
                        rTxpower = byte
                    } else if (command == KISS.CMD_SF) {
                        rSf = byte
                        updateBitrate()
                    } else if (command == KISS.CMD_CR) {
                        rCr = byte
                        updateBitrate()
                    } else if (command == KISS.CMD_RADIO_STATE) {
                        rState = byte
                    } else if (command == KISS.CMD_RADIO_LOCK) {
                        rLock = byte
                    } else if (command == KISS.CMD_FW_VERSION) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 2) {
                                val b = commandBuffer.toByteArray()
                                majVersion = b[0].toInt() and 0xFF
                                minVersion = b[1].toInt() and 0xFF
                                commandBuffer.reset()
                                validateFirmware()
                            }
                        }
                    } else if (command == KISS.CMD_STAT_RX) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 4) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_TX) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 4) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_RSSI) {
                        rStatRssi = byte - RSSI_OFFSET
                    } else if (command == KISS.CMD_STAT_SNR) {
                        // Signed byte * 0.25
                        rStatSnr = byte.toByte().toDouble() * 0.25
                    } else if (command == KISS.CMD_ST_ALOCK) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 2) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_LT_ALOCK) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 2) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_CHTM) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 11) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_PHYPRM) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 12) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_CSMA) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 3) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_STAT_BAT) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 2) {
                                val b = commandBuffer.toByteArray()
                                rBatteryState = b[0].toInt() and 0xFF
                                rBatteryPercent = (b[1].toInt() and 0xFF).coerceIn(0, 100)
                                commandBuffer.reset()
                            }
                        }
                    } else if (command == KISS.CMD_STAT_TEMP) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 1) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_RANDOM) {
                        // Random byte from hardware RNG — currently unused
                    } else if (command == KISS.CMD_PLATFORM) {
                        platform = byte
                    } else if (command == KISS.CMD_MCU) {
                        mcu = byte
                    } else if (command == KISS.CMD_ERROR) {
                        val errorByte = byte.toByte()
                        when (errorByte) {
                            KISS.ERROR_INITRADIO -> log("Hardware error: radio initialization failure")
                            KISS.ERROR_TXFAILED -> log("Hardware error: TX failed")
                            KISS.ERROR_QUEUE_FULL -> log("Hardware error: queue full")
                            KISS.ERROR_MEMORY_LOW -> log("Hardware error: memory low")
                            KISS.ERROR_MODEM_TIMEOUT -> log("Hardware error: modem timeout")
                            else -> log("Hardware error: code 0x${"%02x".format(byte)}")
                        }
                    } else if (command == KISS.CMD_RESET) {
                        if (byte == 0xF8) {
                            if (online.get()) {
                                log("Device reset detected while online")
                            }
                        }
                    } else if (command == KISS.CMD_READY) {
                        processQueue()
                    } else if (command == KISS.CMD_DETECT) {
                        detected = (byte.toByte() == KISS.DETECT_RESP)
                    } else if (command == KISS.CMD_FB_READ) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 512) commandBuffer.reset()
                        }
                    } else if (command == KISS.CMD_DISP_READ) {
                        byte = unescapeByte(byte, escape).also { escape = it.second }.first
                        if (!escape) {
                            commandBuffer.write(byte)
                            if (commandBuffer.size() == 1024) commandBuffer.reset()
                        }
                    }
                    else {
                        // Unknown command — silently consume
                    }
                }
            }
        } catch (e: CancellationException) {
            // Normal shutdown
        } catch (e: IOException) {
            if (!detached.get()) {
                log("Read loop error: ${e.message}")
                online.set(false)
            }
        }

        online.set(false)
    }

    /**
     * Handle KISS escape sequences in command payloads.
     * Returns (processedByte, isCurrentlyEscaping).
     */
    private fun unescapeByte(byte: Int, wasEscaping: Boolean): Pair<Int, Boolean> {
        if (byte == (KISS.FESC.toInt() and 0xFF)) {
            return Pair(byte, true)
        }
        if (wasEscaping) {
            val resolved = when (byte) {
                KISS.TFEND.toInt() and 0xFF -> KISS.FEND.toInt() and 0xFF
                KISS.TFESC.toInt() and 0xFF -> KISS.FESC.toInt() and 0xFF
                else -> byte
            }
            return Pair(resolved, false)
        }
        return Pair(byte, false)
    }

    // -- TX path --

    override fun processOutgoing(data: ByteArray) {
        if (!online.get() || detached.get()) return

        synchronized(txLock) {
            if (!online.get()) return // re-check after acquiring lock

            if (interfaceReady) {
                if (flowControl) {
                    interfaceReady = false
                }
                transmit(data)
            } else {
                packetQueue.add(data)
            }
        }
    }

    private fun transmit(data: ByteArray) {
        val escaped = KISS.escape(data)
        val frame = ByteArray(escaped.size + 3)
        frame[0] = KISS.FEND
        frame[1] = KISS.CMD_DATA
        System.arraycopy(escaped, 0, frame, 2, escaped.size)
        frame[frame.size - 1] = KISS.FEND

        try {
            outputStream.write(frame)
            outputStream.flush()
            txBytes.addAndGet(data.size.toLong())
        } catch (e: IOException) {
            log("TX error: ${e.message}")
            online.set(false)
        }
    }

    private fun processQueue() {
        if (packetQueue.isNotEmpty()) {
            val data = packetQueue.removeFirstOrNull() ?: return
            interfaceReady = true
            processOutgoing(data)
        } else {
            interfaceReady = true
        }
    }

    // -- Lifecycle --

    override fun detach() {
        super.detach()
        try {
            sendRadioState(KISS.RADIO_STATE_OFF)
            val leaveCmd = byteArrayOf(KISS.FEND, KISS.CMD_LEAVE, 0xFF.toByte(), KISS.FEND)
            outputStream.write(leaveCmd)
            outputStream.flush()
        } catch (_: Exception) {}

        readJob?.cancel()
        ioScope.cancel()

        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
    }

    private fun platformName(): String = when (platform) {
        KISS.PLATFORM_ESP32.toInt() and 0xFF -> "ESP32"
        KISS.PLATFORM_NRF52.toInt() and 0xFF -> "nRF52"
        KISS.PLATFORM_AVR.toInt() and 0xFF -> "AVR"
        else -> "Unknown(${platform})"
    }

    private fun log(message: String) {
        println("[RNode:$name] $message")
    }

    override fun toString(): String = "RNodeInterface[$name]"
}
