package network.reticulum.interfaces.spp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.reticulum.identity.Identity
import network.reticulum.interfaces.IfacCredentials
import network.reticulum.interfaces.IfacUtils
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.backoff.ExponentialBackoff
import network.reticulum.interfaces.framing.HDLC
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bluetooth Classic SPP (Serial Port Profile) interface for Reticulum.
 *
 * SPP provides RFCOMM stream sockets — functionally identical to a serial port.
 * This interface uses HDLC framing (same as Python's SerialInterface) over the
 * byte stream, with automatic reconnection on connection loss.
 *
 * Architecture:
 * - Platform-specific Bluetooth operations are delegated to [SppDriver]
 * - HDLC framing, read loop, and reconnect logic live here (pure JVM)
 * - Lifecycle follows the same coroutine pattern as [TCPClientInterface]
 *
 * Modes:
 * - **Client mode** (serverMode=false): Connects to a known peer by MAC address
 * - **Server mode** (serverMode=true): Listens for incoming SPP connections
 *
 * @param name Human-readable interface name
 * @param driver Platform-specific SPP driver (AndroidSppDriver on Android, mock in tests)
 * @param targetAddress Bluetooth MAC address of the remote device (client mode)
 * @param serverMode true to listen for incoming connections, false to connect outgoing
 * @param maxReconnectAttempts Max reconnect attempts before giving up (null = default 10)
 * @param ifacNetname IFAC network name for network isolation
 * @param ifacNetkey IFAC network passphrase for network isolation
 * @param parentScope Parent coroutine scope for lifecycle-aware cancellation (Android service)
 */
class SppInterface(
    name: String,
    private val driver: SppDriver,
    private val targetAddress: String,
    private val serverMode: Boolean = false,
    private val secure: Boolean = true,
    private val maxReconnectAttempts: Int? = null,
    override val ifacNetname: String? = null,
    override val ifacNetkey: String? = null,
    private val parentScope: CoroutineScope? = null,
) : Interface(name) {

    companion object {
        /** Standard Bluetooth SIG UUID for Serial Port Profile (SPP). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** SDP service name for Reticulum SPP connections. */
        const val SERVICE_NAME = "Reticulum SPP"

        /**
         * Hardware MTU matching Python SerialInterface.HW_MTU.
         * Serial links don't negotiate MTU — this is a fixed upper bound.
         */
        const val HW_MTU = 564

        /**
         * Estimated bitrate for Bluetooth 2.0 SPP.
         * Practical throughput is ~700kbps; we use 1Mbps as a reasonable estimate.
         * Actual bitrate varies with signal quality and interference.
         */
        const val BITRATE_ESTIMATE = 1_000_000

        /** Read buffer size for the read loop. */
        private const val READ_BUFFER_SIZE = 4096

        /** Enable verbose debug logging via -Dreticulum.spp.debug=true */
        private val DEBUG = System.getProperty("reticulum.spp.debug", "false").toBoolean()
    }

    override val bitrate: Int = BITRATE_ESTIMATE
    override val hwMtu: Int = HW_MTU

    // Discovery support — advertises as "SerialInterface" to match Python
    override val supportsDiscovery: Boolean = true
    override val discoveryInterfaceType: String = "SerialInterface"
    override fun getDiscoveryData(): Map<Int, Any> = mapOf(
        network.reticulum.discovery.DiscoveryConstants.REACHABLE_ON to targetAddress,
    )

    // IFAC credentials
    private val _ifacCredentials: IfacCredentials? by lazy {
        IfacUtils.deriveIfacCredentials(ifacNetname, ifacNetkey)
    }

    override val ifacSize: Int
        get() = if (_ifacCredentials != null) 16 else 0

    override val ifacKey: ByteArray?
        get() = _ifacCredentials?.key

    override val ifacIdentity: Identity?
        get() = _ifacCredentials?.identity

    /** Remote device name, set when connection is established. */
    @Volatile var remoteDeviceName: String? = null
        private set

    /** Remote device address, set when connection is established. */
    @Volatile var remoteDeviceAddress: String? = null
        private set

    /** Whether this interface is in server mode. */
    val isServerMode: Boolean get() = serverMode

    // Connection state
    private var connection: SppConnection? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val reconnecting = AtomicBoolean(false)
    private val neverConnected = AtomicBoolean(true)
    private val txLock = Mutex()

    // Debug counters
    private val framesSent = AtomicLong(0)
    private val framesReceived = AtomicLong(0)

    // Exponential backoff: 5s initial (matching Python's fixed 5s), up to 60s
    private val backoff = ExponentialBackoff(
        initialDelayMs = 5000L,
        maxDelayMs = 60_000L,
        maxAttempts = maxReconnectAttempts ?: 10,
    )

    // Coroutine scope for I/O operations
    private val ioScope: CoroutineScope = createScope(parentScope).also {
        parentScope?.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                detach()
            }
        }
    }
    private var readJob: Job? = null
    private var connectJob: Job? = null

    private fun createScope(parent: CoroutineScope?): CoroutineScope {
        return if (parent != null) {
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
        } else {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    // HDLC deframer — identical to TCPClientInterface
    private val hdlcDeframer = HDLC.createDeframer { data ->
        val frameNum = framesReceived.incrementAndGet()
        if (DEBUG) {
            val hexPreview = data.take(16).joinToString(" ") { "%02x".format(it) }
            val suffix = if (data.size > 16) "..." else ""
            debugLog("RECV frame #$frameNum: ${data.size} bytes, data=[$hexPreview$suffix]")
        }
        processIncoming(data)
    }

    override fun start() {
        connectJob = ioScope.launch {
            if (!connect(initial = true)) {
                reconnect()
            }
        }
    }

    private suspend fun connect(initial: Boolean = false): Boolean {
        return try {
            if (initial) {
                if (serverMode) {
                    log("Listening for incoming SPP connection...")
                } else {
                    log("Connecting to SPP device $targetAddress...")
                }
            }

            val conn = withContext(Dispatchers.IO) {
                if (serverMode) {
                    driver.accept(SERVICE_NAME, SPP_UUID, secure)
                } else {
                    driver.connect(targetAddress, secure)
                }
            }

            connection = conn
            inputStream = conn.inputStream
            outputStream = conn.outputStream
            remoteDeviceName = conn.remoteName
            remoteDeviceAddress = conn.remoteAddress
            setOnline(true)
            neverConnected.set(false)

            if (initial) {
                val peer = conn.remoteName ?: conn.remoteAddress
                log("SPP connection established with $peer")
            }

            startReadLoop(conn.inputStream)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (initial) {
                log("Initial connection failed: ${e.message}")
                log("Will retry with exponential backoff (5s, 10s, 20s... up to 60s)")
            }
            false
        }
    }

    private suspend fun reconnect() {
        if (reconnecting.getAndSet(true)) return

        while (!online.value && !detached.get()) {
            val delayMs = backoff.nextDelay()

            if (delayMs == null) {
                log("Max reconnection attempts (${backoff.attemptCount}) reached, giving up")
                detach()
                break
            }

            delay(delayMs)

            if (!ioScope.isActive) break

            try {
                if (connect()) {
                    if (!neverConnected.get()) {
                        log("Reconnected successfully after ${backoff.attemptCount} attempts")
                    }
                    backoff.reset()
                    break
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log("Reconnection attempt ${backoff.attemptCount} failed: ${e.message}")
            }
        }

        reconnecting.set(false)
    }

    private fun startReadLoop(stream: InputStream) {
        readJob = ioScope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)

            try {
                while (isActive && online.value && !detached.get()) {
                    val bytesRead = withContext(Dispatchers.IO) {
                        stream.read(buffer)
                    }

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        hdlcDeframer.process(data)
                    } else if (bytesRead == -1) {
                        // Connection closed by remote
                        if (DEBUG) {
                            debugLog("Read loop: EOF, connection closed by peer")
                            debugLog("  frames sent: ${framesSent.get()}, received: ${framesReceived.get()}")
                        }
                        setOnline(false)
                        break
                    }
                }
            } catch (e: CancellationException) {
                if (DEBUG) debugLog("Read loop: cancelled (normal shutdown)")
            } catch (e: IOException) {
                if (DEBUG) {
                    debugLog("Read loop: IOException - ${e.javaClass.name}: ${e.message}")
                }
                if (!detached.get()) {
                    setOnline(false)
                }
            }

            // Connection lost — try to reconnect
            if (!detached.get() && isActive) {
                log("Connection lost, attempting to reconnect...")
                closeConnection()
                reconnect()
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        if (!online.value || detached.get()) {
            throw IllegalStateException("Interface is not online")
        }

        val stream = outputStream ?: throw IllegalStateException("Output stream is null")

        try {
            // Serialize writes with a mutex (same pattern as RNodeInterface txLock)
            // We use runBlocking here because processOutgoing is called synchronously
            // from Transport, matching the TCPClientInterface pattern
            kotlinx.coroutines.runBlocking {
                txLock.withLock {
                    val framedData = HDLC.frame(data)

                    withContext(Dispatchers.IO) {
                        stream.write(framedData)
                        stream.flush()
                    }

                    val frameNum = framesSent.incrementAndGet()
                    if (DEBUG) {
                        val hexPreview = data.take(16).joinToString(" ") { "%02x".format(it) }
                        val suffix = if (data.size > 16) "..." else ""
                        debugLog("SENT frame #$frameNum: ${data.size} bytes (framed: ${framedData.size}), data=[$hexPreview$suffix]")
                    }

                    txBytes.addAndGet(framedData.size.toLong())
                    parentInterface?.txBytes?.addAndGet(framedData.size.toLong())
                }
            }
        } catch (e: IOException) {
            log("Write error: ${e.message}")
            teardown()
            throw e
        }
    }

    /**
     * Notify the interface that the network/Bluetooth state has changed.
     * Resets backoff to allow quick reconnection attempts.
     */
    fun onBluetoothStateChanged() {
        log("Bluetooth state changed - resetting reconnection backoff")
        backoff.reset()

        if (!online.value && !detached.get() && !reconnecting.get()) {
            ioScope.launch {
                reconnect()
            }
        }
    }

    override fun detach() {
        super.detach()
        readJob?.cancel()
        connectJob?.cancel()
        if (serverMode) {
            driver.cancelAccept()
        }
        ioScope.cancel()
        closeConnection()
    }

    private fun teardown() {
        if (DEBUG) {
            debugLog("Teardown - frames sent: ${framesSent.get()}, received: ${framesReceived.get()}")
        }
        setOnline(false)
        closeConnection()

        if (!detached.get()) {
            ioScope.launch {
                reconnect()
            }
        }
    }

    private fun closeConnection() {
        try {
            connection?.close?.invoke()
        } catch (_: Exception) {
        }
        connection = null
        inputStream = null
        outputStream = null
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [$name] $message")
    }

    private fun debugLog(message: String) {
        if (DEBUG) {
            val timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            )
            println("[$timestamp] [$name] [DEBUG] $message")
        }
    }

    override fun toString(): String {
        val mode = if (serverMode) "server" else "client"
        return "SppInterface[$name $mode -> $targetAddress]"
    }
}
