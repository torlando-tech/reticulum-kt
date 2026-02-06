package network.reticulum.interfaces.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE mesh interface for Reticulum networking.
 *
 * Server-style parent interface (like LocalServerInterface) that orchestrates the
 * entire BLE mesh lifecycle: discovery, connection, identity handshake, and peer
 * management. Spawns [BLEPeerInterface] children for each connected peer and
 * registers them with Transport.
 *
 * Architecture:
 * - Dual-role: advertises as peripheral (GATT server) and scans as central simultaneously
 * - Identity handshake: central reads Identity characteristic, writes own identity to RX
 * - Connection direction: lower identity hash initiates as central (for dedup)
 * - processOutgoing() is a no-op -- Transport calls each spawned peer's processOutgoing()
 *
 * The caller (InterfaceManager) must set the transport identity on the BLEDriver
 * before constructing BLEInterface. BLEInterface does NOT downcast to AndroidBLEDriver.
 *
 * @param name Human-readable interface name
 * @param driver BLEDriver implementation (platform-specific, identity already set)
 * @param transportIdentity 16-byte Reticulum Transport identity hash
 * @param maxConnections Maximum simultaneous peer connections (default 5, max 7)
 */
class BLEInterface(
    name: String,
    private val driver: BLEDriver,
    private val transportIdentity: ByteArray,
    private val maxConnections: Int = 5,
) : Interface(name) {

    override val bitrate: Int = 40_000  // ~40 kbps practical BLE throughput
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Identity hex -> BLEPeerInterface
    private val peers = ConcurrentHashMap<String, BLEPeerInterface>()

    // BLE MAC address -> identity hex (for routing received data)
    private val addressToIdentity = ConcurrentHashMap<String, String>()

    // Identity hex -> BLE MAC address (for reverse lookup)
    private val identityToAddress = ConcurrentHashMap<String, String>()

    // Temporary blacklist: address -> blacklist expiry timestamp
    private val blacklist = ConcurrentHashMap<String, Long>()
    private val blacklistDurationMs = 60_000L // 60 seconds

    // Reconnection backoff: address -> next-attempt-after timestamp
    private val reconnectBackoff = ConcurrentHashMap<String, Long>()
    private val reconnectDelayMs = 7_000L // 7s within the 5-10s range

    init {
        spawnedInterfaces = mutableListOf()
    }

    // ---- Lifecycle ----

    /**
     * Start dual-role BLE operations: advertise first, then scan after 100ms delay.
     * Launches event collection coroutines for discovery, incoming connections,
     * disconnections, and periodic cleanup.
     */
    override fun start() {
        online.set(true)

        // Start advertising (peripheral role)
        scope.launch {
            try {
                driver.startAdvertising()
                log("Advertising started")
            } catch (e: Exception) {
                log("Failed to start advertising: ${e.message}")
            }
        }

        // Start scanning after brief delay (central role)
        scope.launch {
            delay(100) // Brief delay: advertise first, then scan
            try {
                driver.startScanning()
                log("Scanning started")
            } catch (e: Exception) {
                log("Failed to start scanning: ${e.message}")
            }
        }

        // Event collection coroutines
        scope.launch { collectDiscoveredPeers() }
        scope.launch { collectIncomingConnections() }
        scope.launch { collectDisconnections() }
        scope.launch { periodicCleanup() }
    }

    /**
     * No-op for server-style parent interface.
     *
     * Transport calls each spawned [BLEPeerInterface]'s processOutgoing() directly.
     * If the server also transmitted here, peers would receive packets twice.
     */
    override fun processOutgoing(data: ByteArray) {
        // No-op: Transport calls each spawned BLEPeerInterface's processOutgoing() directly
    }

    override fun detach() {
        super.detach()
        scope.cancel()

        // Detach all peer interfaces
        for ((_, peerInterface) in peers.toMap()) {
            try {
                Transport.deregisterInterface(peerInterface.toRef())
            } catch (e: Exception) {
                // Ignore during shutdown
            }
            peerInterface.detach()
        }
        peers.clear()
        addressToIdentity.clear()
        identityToAddress.clear()

        // Shut down the BLE driver
        driver.shutdown()

        log("Interface stopped")
    }

    // ---- Discovery ----

    /**
     * Collect discovered peers from scanning and attempt connections.
     * Skips peers that are: already connected, blacklisted, in backoff, or at capacity.
     */
    private suspend fun collectDiscoveredPeers() {
        driver.discoveredPeers.collect { peer ->
            // Skip if already connected to this address
            if (addressToIdentity.containsKey(peer.address)) return@collect

            // Skip if blacklisted
            if (isBlacklisted(peer.address)) return@collect

            // Skip if in reconnection backoff
            if (isInBackoff(peer.address)) return@collect

            // Skip if at capacity
            if (peers.size >= maxConnections) return@collect

            // Attempt connection in a separate coroutine
            scope.launch {
                connectToPeer(peer)
            }
        }
    }

    // ---- Outgoing Connection (Central Role) ----

    /**
     * Connect to a discovered peer and perform the identity handshake as central.
     *
     * Flow:
     * 1. Connect via BLEDriver
     * 2. Read peer's identity from Identity characteristic
     * 3. Write our identity to peer's RX characteristic
     * 4. Check for duplicate identity (keep newest, tear down oldest)
     * 5. Spawn BLEPeerInterface and register with Transport
     */
    private suspend fun connectToPeer(peer: DiscoveredPeer) {
        val address = peer.address
        log("Connecting to ${address.takeLast(8)}...")

        try {
            val connection = driver.connect(address)

            // Perform identity handshake (central side)
            val peerIdentity = performHandshakeAsCentral(connection)
            val identityHex = peerIdentity.toHex()

            // Check for duplicate identity (same identity at different address)
            val existingAddress = identityToAddress[identityHex]
            if (existingAddress != null && existingAddress != address) {
                log("Duplicate identity ${identityHex.take(8)}: replacing ${existingAddress.takeLast(8)} with ${address.takeLast(8)}")
                tearDownPeer(identityHex)
            }

            // Store identity mapping
            addressToIdentity[address] = identityHex
            identityToAddress[identityHex] = address

            // Spawn peer interface
            spawnPeerInterface(address, identityHex, peerIdentity, connection)

            log("Connected to ${identityHex.take(8)} at ${address.takeLast(8)}")

        } catch (e: CancellationException) {
            throw e // Don't catch coroutine cancellation
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            log("Handshake timeout for ${address.takeLast(8)}, blacklisting")
            blacklist[address] = System.currentTimeMillis() + blacklistDurationMs
            try { driver.disconnect(address) } catch (_: Exception) {}
        } catch (e: Exception) {
            log("Connection failed for ${address.takeLast(8)}: ${e.message}")
            reconnectBackoff[address] = System.currentTimeMillis() + reconnectDelayMs
        }
    }

    /**
     * Perform the identity handshake as central (initiator).
     *
     * 1. Read the peripheral's 16-byte identity from the Identity characteristic
     * 2. Write our 16-byte identity to the peripheral's RX characteristic
     *
     * Times out after [BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS] (30 seconds).
     */
    private suspend fun performHandshakeAsCentral(conn: BLEPeerConnection): ByteArray {
        return withTimeout(BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS) {
            // Step 1: Read peripheral's identity from Identity characteristic
            val peerIdentity = conn.readIdentity()
            require(peerIdentity.size == BLEConstants.IDENTITY_SIZE) {
                "Invalid identity size: ${peerIdentity.size} (expected ${BLEConstants.IDENTITY_SIZE})"
            }

            // Step 2: Write our identity to the peer's RX characteristic
            conn.writeIdentity(transportIdentity)

            peerIdentity
        }
    }

    // ---- Incoming Connection (Peripheral Role) ----

    /**
     * Collect incoming connections from the GATT server and handle identity handshake.
     */
    private suspend fun collectIncomingConnections() {
        driver.incomingConnections.collect { connection ->
            scope.launch {
                handleIncomingConnection(connection)
            }
        }
    }

    /**
     * Handle an incoming connection from a remote central.
     *
     * Peripheral side: wait for the central to write its 16-byte identity to RX.
     * The first 16-byte write on RX from the central is the identity handshake.
     */
    private suspend fun handleIncomingConnection(connection: BLEPeerConnection) {
        val address = connection.address
        log("Incoming connection from ${address.takeLast(8)}, waiting for identity handshake...")

        try {
            // Peripheral side: wait for central to write its identity to RX
            val peerIdentity = performHandshakeAsPeripheral(connection)
            val identityHex = peerIdentity.toHex()

            // Check for duplicate identity
            val existingAddress = identityToAddress[identityHex]
            if (existingAddress != null && existingAddress != address) {
                log("Duplicate identity ${identityHex.take(8)}: replacing ${existingAddress.takeLast(8)} with ${address.takeLast(8)}")
                tearDownPeer(identityHex)
            }

            // Store identity mapping
            addressToIdentity[address] = identityHex
            identityToAddress[identityHex] = address

            // Spawn peer interface
            spawnPeerInterface(address, identityHex, peerIdentity, connection)

            log("Accepted peer ${identityHex.take(8)} from ${address.takeLast(8)}")

        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            log("Identity handshake timeout for incoming ${address.takeLast(8)}, disconnecting")
            blacklist[address] = System.currentTimeMillis() + blacklistDurationMs
            try { driver.disconnect(address) } catch (_: Exception) {}
        } catch (e: Exception) {
            log("Failed to process incoming connection from ${address.takeLast(8)}: ${e.message}")
            try { driver.disconnect(address) } catch (_: Exception) {}
        }
    }

    /**
     * Perform the identity handshake as peripheral (responder).
     *
     * Wait for the central to write its 16-byte identity to the RX characteristic.
     * The first 16-byte write is the identity handshake data.
     *
     * Times out after [BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS] (30 seconds).
     */
    private suspend fun performHandshakeAsPeripheral(conn: BLEPeerConnection): ByteArray {
        return withTimeout(BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS) {
            // Wait for central to write its 16-byte identity to RX
            conn.receivedFragments.first { fragment ->
                fragment.size == BLEConstants.IDENTITY_SIZE
            }
        }
    }

    // ---- Identity Direction Sorting ----

    /**
     * Determine if we should initiate (as central) or wait (as peripheral).
     * Lower identity hash acts as central (initiator).
     *
     * Uses identity hash instead of MAC because Android cannot reliably
     * provide the local BLE MAC address (returns 02:00:00:00:00:00).
     *
     * Note: This is used for deduplication logic, not initial connection decisions.
     * Initial connections are made optimistically; sorting happens after handshake.
     */
    @Suppress("unused") // Reserved for Phase 22 dedup enhancements
    private fun shouldInitiateConnection(peerIdentity: ByteArray): Boolean {
        for (i in transportIdentity.indices) {
            val local = transportIdentity[i].toInt() and 0xFF
            val remote = peerIdentity[i].toInt() and 0xFF
            if (local < remote) return true   // We are lower -> we initiate
            if (local > remote) return false  // They are lower -> they initiate
        }
        // Equal identity hashes (astronomically unlikely) -- don't connect
        return false
    }

    // ---- Peer Lifecycle ----

    /**
     * Spawn a [BLEPeerInterface] for a connected peer and register with Transport.
     *
     * If an interface already exists for this identity (MAC rotation), updates
     * the existing interface's connection instead of creating a new one.
     */
    private fun spawnPeerInterface(
        address: String,
        identityHex: String,
        peerIdentity: ByteArray,
        connection: BLEPeerConnection,
    ) {
        // Check if interface already exists for this identity (MAC rotation)
        val existing = peers[identityHex]
        if (existing != null) {
            existing.updateConnection(connection, address)
            addressToIdentity[address] = identityHex
            identityToAddress[identityHex] = address
            log("Reused existing interface for ${identityHex.take(8)} at new address ${address.takeLast(8)}")
            return
        }

        val peerName = "BLE|${identityHex.take(8)}"
        val peerInterface = BLEPeerInterface(
            name = peerName,
            connection = connection,
            parentBleInterface = this,
            peerIdentity = peerIdentity,
        )
        peerInterface.parentInterface = this

        // Set callback BEFORE toRef() -- InterfaceAdapter only sets if null
        peerInterface.onPacketReceived = { data, iface ->
            onPacketReceived?.invoke(data, iface)
        }

        peers[identityHex] = peerInterface
        spawnedInterfaces?.add(peerInterface)

        peerInterface.startReceiving()

        try {
            Transport.registerInterface(peerInterface.toRef())
        } catch (e: Exception) {
            log("Could not register peer interface with Transport: ${e.message}")
        }

        log("Spawned peer interface: $peerName (total: ${peers.size})")
    }

    // ---- Disconnection Handling ----

    /**
     * Collect connection-lost events from the driver and tear down peers.
     * Sets reconnection backoff for the lost address.
     */
    private suspend fun collectDisconnections() {
        driver.connectionLost.collect { address ->
            val identityHex = addressToIdentity.remove(address)
            if (identityHex != null) {
                // Check if this was the current address for this identity
                if (identityToAddress[identityHex] == address) {
                    identityToAddress.remove(identityHex)
                    tearDownPeer(identityHex)
                }
                // Set backoff for reconnection
                reconnectBackoff[address] = System.currentTimeMillis() + reconnectDelayMs
            }
            log("Connection lost: ${address.takeLast(8)}")
        }
    }

    /**
     * Tear down a peer interface by identity. Deregisters from Transport and detaches.
     */
    internal fun tearDownPeer(identityHex: String) {
        val peerInterface = peers.remove(identityHex) ?: return
        spawnedInterfaces?.remove(peerInterface)

        try {
            Transport.deregisterInterface(peerInterface.toRef())
        } catch (e: Exception) {
            log("Could not deregister peer interface from Transport: ${e.message}")
        }

        peerInterface.detach()
        log("Torn down peer: ${peerInterface.name}")
    }

    /**
     * Called by [BLEPeerInterface] when it detects a fatal error (keepalive failure).
     */
    internal fun peerDisconnected(peerInterface: BLEPeerInterface) {
        val identityHex = peerInterface.peerIdentity.toHex()
        peers.remove(identityHex)
        spawnedInterfaces?.remove(peerInterface)

        try {
            Transport.deregisterInterface(peerInterface.toRef())
        } catch (e: Exception) {
            log("Could not deregister peer interface from Transport: ${e.message}")
        }

        log("Peer disconnected: ${peerInterface.name} (remaining: ${peers.size})")
    }

    // ---- Blacklist and Backoff ----

    private fun isBlacklisted(address: String): Boolean {
        val expiry = blacklist[address] ?: return false
        if (System.currentTimeMillis() > expiry) {
            blacklist.remove(address)
            return false
        }
        return true
    }

    private fun isInBackoff(address: String): Boolean {
        val nextAttemptAfter = reconnectBackoff[address] ?: return false
        if (System.currentTimeMillis() > nextAttemptAfter) {
            reconnectBackoff.remove(address)
            return false
        }
        return true
    }

    /**
     * Periodic cleanup of expired blacklist and backoff entries.
     * Runs every 30 seconds.
     */
    private suspend fun periodicCleanup() {
        while (online.get() && !detached.get()) {
            delay(30_000)
            val now = System.currentTimeMillis()

            // Clean expired blacklist entries
            blacklist.entries.removeIf { it.value < now }

            // Clean expired backoff entries
            reconnectBackoff.entries.removeIf { it.value < now }
        }
    }

    // ---- Utility ----

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun log(message: String) {
        println("[BLEInterface][$name] $message")
    }

    override fun toString(): String = "BLEInterface[$name]"
}
