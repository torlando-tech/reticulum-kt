package network.reticulum.interfaces.ble

import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction interface for BLE operations.
 *
 * This defines the contract between pure-JVM protocol logic (in rns-interfaces)
 * and platform-specific BLE implementation (AndroidBLEDriver in rns-sample-app).
 *
 * The driver is message-based (not stream-based) because BLE GATT characteristics
 * are inherently message-oriented -- each write/notification is a discrete chunk.
 * This differs from the RNode interface which uses PipedInputStream/OutputStream.
 *
 * All methods are suspend functions to allow the Android implementation to bridge
 * between GATT callbacks and coroutines. Event streams use SharedFlow to allow
 * multiple collectors (e.g., BLEInterface orchestrator + logging).
 *
 * Thread safety: Implementations must be safe to call from any coroutine context.
 * The Android implementation should use internal Mutex/Channel for GATT serialization.
 */
interface BLEDriver {

    // ---- Lifecycle ----

    /**
     * Start advertising the Reticulum BLE service.
     * The device becomes discoverable as a peripheral (GATT server).
     * Advertising includes the [BLEConstants.SERVICE_UUID] so other Reticulum
     * devices can discover it via service UUID filtering.
     */
    suspend fun startAdvertising()

    /**
     * Stop advertising. The device is no longer discoverable as a peripheral.
     */
    suspend fun stopAdvertising()

    /**
     * Start scanning for nearby Reticulum BLE devices.
     * Discovered peers are emitted on [discoveredPeers].
     * Uses a single long-running scan with service UUID filter to avoid
     * Android's 5-starts-per-30-seconds throttling.
     */
    suspend fun startScanning()

    /**
     * Stop scanning for nearby devices.
     */
    suspend fun stopScanning()

    /**
     * Initiate an outgoing connection to a peer at the given BLE address.
     * Returns a [BLEPeerConnection] representing the GATT client connection.
     *
     * The caller is responsible for performing the identity handshake after
     * connection is established.
     *
     * @param address BLE MAC address of the peer to connect to
     * @return Active peer connection
     * @throws Exception if connection fails or times out
     */
    suspend fun connect(address: String): BLEPeerConnection

    /**
     * Disconnect from a peer at the given BLE address.
     * Closes both GATT client and server connections if they exist.
     *
     * @param address BLE MAC address of the peer to disconnect from
     */
    suspend fun disconnect(address: String)

    /**
     * Shut down the driver completely.
     * Stops advertising, scanning, and disconnects all peers.
     * After shutdown, the driver cannot be reused.
     */
    fun shutdown()

    // ---- Event Flows ----

    /**
     * Flow of discovered peers from BLE scanning.
     * Emitted each time a scan result is received. The same peer may be emitted
     * multiple times with updated RSSI values.
     */
    val discoveredPeers: SharedFlow<DiscoveredPeer>

    /**
     * Flow of incoming connections from remote peers (via GATT server).
     * Emitted when a remote central connects and the GATT server accepts.
     * The connection's identity is not yet known -- the handshake hasn't happened.
     */
    val incomingConnections: SharedFlow<BLEPeerConnection>

    /**
     * Flow of peer addresses for connections that have been lost.
     * Emitted when a GATT client or server connection drops unexpectedly.
     * The String value is the BLE MAC address of the disconnected peer.
     */
    val connectionLost: SharedFlow<String>

    // ---- State ----

    /**
     * The local device's BLE MAC address, or null if not yet known.
     * On Android, the local address is often unavailable due to privacy restrictions.
     * When available, it's used for MAC-based connection direction sorting.
     */
    val localAddress: String?

    /**
     * Whether the driver is currently running (advertising or scanning).
     */
    val isRunning: Boolean
}

/**
 * Represents an active BLE connection to a single peer.
 *
 * This abstracts the GATT client/server connection details. Fragment-level
 * send/receive operations are exposed as suspend functions and SharedFlow.
 * The BLEInterface orchestrator uses this to perform identity handshake,
 * send/receive fragments, and manage connection lifecycle.
 *
 * A connection may be either:
 * - Outgoing (we are central, connected via BLEDriver.connect)
 * - Incoming (we are peripheral, received via BLEDriver.incomingConnections)
 */
interface BLEPeerConnection {

    /** BLE MAC address of the remote peer. */
    val address: String

    /**
     * Negotiated MTU for this connection.
     * Fragment payload size = mtu - [BLEConstants.FRAGMENT_HEADER_SIZE].
     * Defaults to [BLEConstants.DEFAULT_MTU] if negotiation hasn't occurred.
     */
    val mtu: Int

    /**
     * Remote peer's 16-byte Reticulum identity hash, or null if the
     * identity handshake hasn't completed yet.
     */
    val identity: ByteArray?

    /**
     * Send a raw fragment (header + payload) to the remote peer.
     * The fragment must include the 5-byte header.
     * On the central side, this writes to the RX characteristic.
     * On the peripheral side, this sends a TX notification.
     *
     * @param data Complete fragment bytes (header + payload)
     * @throws Exception if the write fails or connection is closed
     */
    suspend fun sendFragment(data: ByteArray)

    /**
     * Flow of received fragments from the remote peer.
     * Each emission is a complete fragment (header + payload).
     * On the central side, these come from TX characteristic notifications.
     * On the peripheral side, these come from RX characteristic writes.
     *
     * Note: Keepalive bytes (single 0x00) and identity handshake data are
     * also received here -- the consumer must disambiguate by length and context.
     */
    val receivedFragments: SharedFlow<ByteArray>

    /**
     * Read the remote peer's identity from the Identity GATT characteristic.
     * Used by the central side during the identity handshake.
     *
     * @return 16-byte identity hash
     * @throws Exception if the read fails or times out
     */
    suspend fun readIdentity(): ByteArray

    /**
     * Write our identity to the remote peer.
     * On the central side, this writes to the RX characteristic as the first
     * packet after reading the peer's identity (completing the handshake).
     *
     * @param identity Our 16-byte Reticulum Transport identity hash
     * @throws Exception if the write fails
     */
    suspend fun writeIdentity(identity: ByteArray)

    /**
     * Read the current RSSI (signal strength) for this connection.
     * Only supported on outgoing (central) connections that hold a GATT client handle.
     *
     * @return RSSI value in dBm
     * @throws UnsupportedOperationException if not supported on this connection type
     */
    suspend fun readRemoteRssi(): Int

    /**
     * Close this connection.
     * Disconnects the GATT client/server and releases resources.
     * After close, no further operations are valid.
     */
    fun close()
}
