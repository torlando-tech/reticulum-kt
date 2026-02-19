package network.reticulum.interfaces.spp

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Platform-agnostic contract for Bluetooth Classic SPP (Serial Port Profile) operations.
 *
 * SPP provides RFCOMM stream sockets — functionally identical to a serial port.
 * Unlike BLE (message-based with GATT characteristics), SPP returns plain
 * InputStream/OutputStream, so no fragmentation or MTU negotiation is needed.
 *
 * Implementations:
 * - [AndroidSppDriver] in rns-android: uses BluetoothAdapter/BluetoothSocket
 * - Test mocks: use PipedInputStream/PipedOutputStream
 *
 * This follows the same driver-abstraction pattern as [BLEDriver] → [AndroidBLEDriver],
 * but is far simpler due to SPP's stream-oriented nature.
 */
interface SppDriver {

    /**
     * Connect to a device by MAC address (client mode).
     *
     * This is a blocking operation that should be called from [Dispatchers.IO].
     *
     * @param address Bluetooth MAC address (e.g., "AA:BB:CC:DD:EE:FF")
     * @param secure true for encrypted RFCOMM (requires pairing), false for insecure RFCOMM
     * @return Active SPP connection with streams
     * @throws Exception if connection fails (device not in range, etc.)
     */
    suspend fun connect(address: String, secure: Boolean = true): SppConnection

    /**
     * Listen for an incoming SPP connection (server mode).
     *
     * Creates an RFCOMM server socket and blocks until a client connects.
     * Call [cancelAccept] from another coroutine to unblock.
     *
     * @param serviceName Human-readable name for the SDP service record
     * @param uuid Service UUID for the SDP service record
     * @param secure true for encrypted RFCOMM (requires pairing), false for insecure RFCOMM
     * @return Connection from the accepted client
     * @throws Exception if accept fails or is cancelled
     */
    suspend fun accept(serviceName: String, uuid: UUID, secure: Boolean = true): SppConnection

    /**
     * Cancel any pending [accept] call.
     *
     * Closes the server socket, causing accept() to throw and unblock.
     */
    fun cancelAccept()

    /**
     * List currently paired (bonded) Bluetooth devices.
     *
     * SPP requires prior pairing via Android Settings — there is no
     * runtime discovery/scan like BLE.
     *
     * @return List of paired devices
     */
    fun listPairedDevices(): List<SppDevice>

    /**
     * Shut down the driver and release all resources.
     *
     * Closes any open server sockets. After shutdown, the driver
     * cannot be reused.
     */
    fun shutdown()
}

/**
 * An active SPP connection with byte streams.
 *
 * Wraps a BluetoothSocket (on Android) or piped streams (in tests).
 * The streams are plain Java I/O — no BLE fragmentation needed.
 *
 * @property inputStream Read data from the remote device
 * @property outputStream Write data to the remote device
 * @property remoteAddress Bluetooth MAC address of the remote device
 * @property remoteName Human-readable name of the remote device, or null
 * @property close Lambda to close the underlying socket/connection
 */
data class SppConnection(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val remoteAddress: String,
    val remoteName: String?,
    val close: () -> Unit,
)

/**
 * A paired Bluetooth device that may support SPP.
 *
 * @property address Bluetooth MAC address
 * @property name Human-readable device name, or null if unavailable
 * @property bonded Whether the device is currently bonded (paired)
 */
data class SppDevice(
    val address: String,
    val name: String?,
    val bonded: Boolean,
)
