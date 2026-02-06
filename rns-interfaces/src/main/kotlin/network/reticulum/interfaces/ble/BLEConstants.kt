package network.reticulum.interfaces.ble

import java.util.UUID

/**
 * Protocol constants for the Reticulum BLE Interface.
 *
 * Values match the Python ble-reticulum reference implementation (BLEInterface.py,
 * BLEGATTServer.py, BLEFragmentation.py) and the Columba Kotlin implementation
 * (BleConstants.kt). Protocol version 2.2.
 *
 * The BLE interface uses a custom GATT service with three characteristics:
 * - RX: Central writes data to peripheral (fragments, identity handshake, keepalive)
 * - TX: Peripheral notifies central with data (fragments, keepalive)
 * - Identity: Read-only 16-byte Reticulum Transport identity hash
 */
object BLEConstants {

    // ---- GATT Service and Characteristic UUIDs ----

    /** Custom Reticulum BLE service UUID. All Reticulum BLE devices advertise this. */
    val SERVICE_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e3")

    /**
     * RX Characteristic UUID -- central writes data here to send to peripheral.
     * Properties: WRITE, WRITE_WITHOUT_RESPONSE
     */
    val RX_CHAR_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e5")

    /**
     * TX Characteristic UUID -- peripheral notifies central here to send data.
     * Properties: READ, NOTIFY
     */
    val TX_CHAR_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e4")

    /**
     * Identity Characteristic UUID -- read-only 16-byte Reticulum Transport identity hash.
     * Properties: READ
     *
     * Enables stable peer tracking across Android BLE MAC address rotations (~15 min cycle).
     */
    val IDENTITY_CHAR_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e6")

    /**
     * Client Characteristic Configuration Descriptor (CCCD) UUID.
     * Standard Bluetooth UUID for enabling notifications on TX characteristic.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Fragment Header ----

    /**
     * Fragment header size in bytes.
     * Format: [type:1][sequence:2][total:2] = 5 bytes, big-endian.
     * Always present, even on single-fragment packets.
     */
    const val FRAGMENT_HEADER_SIZE = 5

    /** Fragment type: first fragment of a multi-fragment message. */
    const val FRAGMENT_TYPE_START: Byte = 0x01

    /** Fragment type: middle fragment of a multi-fragment message. */
    const val FRAGMENT_TYPE_CONTINUE: Byte = 0x02

    /** Fragment type: last fragment of a multi-fragment message. */
    const val FRAGMENT_TYPE_END: Byte = 0x03

    // ---- MTU ----

    /**
     * Default MTU when negotiation doesn't happen or as fallback.
     * Typical for many Android devices. Fragment payload = MTU - FRAGMENT_HEADER_SIZE.
     */
    const val DEFAULT_MTU = 185

    /** Minimum usable MTU. BLE spec guarantees 23 bytes, but 20 is the minimum ATT payload. */
    const val MIN_MTU = 20

    /** Maximum MTU to request during negotiation. Android supports up to 517 (512 data + 5 header). */
    const val MAX_MTU = 517

    // ---- Keepalive ----

    /**
     * Keepalive byte value. A single 0x00 byte sent periodically to prevent
     * Android BLE supervision timeout (status code 8, typically 20-30s of inactivity).
     */
    const val KEEPALIVE_BYTE: Byte = 0x00

    /**
     * Keepalive interval in milliseconds.
     * Matches Columba/ble-reticulum: 15 seconds.
     */
    const val KEEPALIVE_INTERVAL_MS = 15_000L

    // ---- Timeouts ----

    /**
     * Reassembly timeout in milliseconds.
     * Incomplete fragment reassembly buffers are cleared after this time.
     * Matches Python _pending_identity_timeout and Columba REASSEMBLY_TIMEOUT_MS.
     */
    const val REASSEMBLY_TIMEOUT_MS = 30_000L

    /**
     * Identity handshake timeout in milliseconds.
     * Connections that haven't completed the identity exchange are dropped.
     * Matches Python _pending_identity_timeout = 30 seconds.
     */
    const val IDENTITY_HANDSHAKE_TIMEOUT_MS = 30_000L

    /**
     * Connection attempt timeout in milliseconds.
     * If a GATT connection doesn't complete in this time, consider it failed.
     */
    const val CONNECTION_TIMEOUT_MS = 30_000L

    /**
     * Individual GATT operation timeout in milliseconds.
     * Timeout for read/write/discover operations.
     */
    const val OPERATION_TIMEOUT_MS = 5_000L

    // ---- Identity ----

    /**
     * Size of the Reticulum Transport identity hash in bytes.
     * This is the truncated hash stored in the Identity GATT characteristic
     * and exchanged during the handshake.
     */
    const val IDENTITY_SIZE = 16

    // ---- Connection Limits ----

    /**
     * Maximum simultaneous BLE peer connections.
     * Android typically supports ~8 total BLE connections across all apps.
     */
    const val MAX_CONNECTIONS = 7

    /**
     * Minimum RSSI (dBm) to consider a peer for connection.
     * Devices weaker than this are ignored during discovery.
     */
    const val MIN_RSSI_DBM = -85
}
