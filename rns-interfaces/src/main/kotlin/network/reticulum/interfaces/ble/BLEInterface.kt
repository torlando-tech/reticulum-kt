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

    // Exponential blacklist: address -> expiry + failure count
    private data class BlacklistEntry(val expiry: Long, val failureCount: Int)
    private val blacklist = ConcurrentHashMap<String, BlacklistEntry>()

    // Reconnection backoff: address -> next-attempt-after timestamp
    private val reconnectBackoff = ConcurrentHashMap<String, Long>()
    private val reconnectDelayMs = 7_000L // 7s within the 5-10s range

    // Addresses currently being connected to (prevents concurrent attempts to same address)
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()

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
        log("start() called — launching BLE coroutines")

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
        scope.launch { zombieDetectionLoop() }
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
        pendingConnections.clear()

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

            // Skip if already attempting connection to this address
            if (pendingConnections.contains(peer.address)) return@collect

            // Skip if blacklisted (don't clear on re-discovery — let blacklist expire naturally)
            if (isBlacklisted(peer.address)) return@collect

            // Skip if in reconnection backoff
            if (isInBackoff(peer.address)) return@collect

            // At capacity: check if new peer is significantly better than worst existing
            if (peers.size >= maxConnections) {
                val (lowestIdentity, lowestScore) = findLowestScoredPeer() ?: return@collect
                val newScore = peer.connectionScore()

                if (newScore > lowestScore + BLEConstants.EVICTION_MARGIN) {
                    log("Evicting ${lowestIdentity.take(8)} (score=${String.format("%.2f", lowestScore)}) for ${peer.address.takeLast(8)} (score=${String.format("%.2f", newScore)})")
                    tearDownPeer(lowestIdentity)
                    // Fall through to connection attempt
                } else {
                    return@collect  // New peer not significantly better
                }
            }

            // Attempt connection in a separate coroutine
            pendingConnections.add(peer.address)
            scope.launch {
                try {
                    connectToPeer(peer)
                } finally {
                    pendingConnections.remove(peer.address)
                }
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

            // Check for duplicate identity (same peer at different address due to MAC rotation)
            val existingAddress = identityToAddress[identityHex]
            if (existingAddress != null && existingAddress != address) {
                val existing = peers[identityHex]
                if (existing != null && existing.online.get() && !existing.detached.get()) {
                    // Existing connection is healthy — keep it, reject new
                    log("Duplicate identity ${identityHex.take(8)}: keeping existing at ${existingAddress.takeLast(8)}, rejecting outgoing ${address.takeLast(8)}")
                    reconnectBackoff[address] = System.currentTimeMillis() + 30_000
                    try { driver.disconnect(address) } catch (_: Exception) {}
                    return
                }
                // Existing connection is dead — replace it
                log("Duplicate identity ${identityHex.take(8)}: replacing dead ${existingAddress.takeLast(8)} with ${address.takeLast(8)}")
                tearDownPeer(identityHex)
            }

            // Store identity mapping
            addressToIdentity[address] = identityHex
            identityToAddress[identityHex] = address

            // Spawn peer interface
            spawnPeerInterface(address, identityHex, peerIdentity, connection, rssi = peer.rssi, isOutgoing = true)

            log("Connected to ${identityHex.take(8)} at ${address.takeLast(8)}")

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Re-check: an incoming connection may have completed handshake while we timed out.
            // If so, don't disconnect — it would kill the healthy connection at this address.
            if (addressToIdentity.containsKey(address)) {
                log("Outgoing handshake timed out for ${address.takeLast(8)} but incoming connection already active, not disconnecting")
                return
            }
            log("Handshake timeout for ${address.takeLast(8)}, blacklisting")
            addToBlacklist(address)
            try { driver.disconnect(address) } catch (_: Exception) {}
        } catch (e: CancellationException) {
            throw e // Don't catch coroutine cancellation
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

        // Skip if we already have a connection to this address (prevents dual central+peripheral
        // connections to the same device, where the peripheral handshake would hang forever and
        // then driver.disconnect() would kill the healthy central connection too)
        if (addressToIdentity.containsKey(address)) {
            log("Already connected to ${address.takeLast(8)}, skipping incoming handshake")
            return  // Don't disconnect — would kill the healthy connection at this address
        }

        log("Incoming connection from ${address.takeLast(8)}, waiting for identity handshake...")

        try {
            // Peripheral side: wait for central to write its identity to RX
            val peerIdentity = performHandshakeAsPeripheral(connection)
            val identityHex = peerIdentity.toHex()

            // Check for duplicate identity (same peer at different address due to MAC rotation)
            val existingAddress = identityToAddress[identityHex]
            if (existingAddress != null && existingAddress != address) {
                val existing = peers[identityHex]
                if (existing != null && existing.online.get() && !existing.detached.get()) {
                    // Existing connection is healthy — keep it, reject incoming
                    log("Duplicate identity ${identityHex.take(8)}: keeping existing at ${existingAddress.takeLast(8)}, rejecting incoming ${address.takeLast(8)}")
                    reconnectBackoff[address] = System.currentTimeMillis() + 30_000
                    try { driver.disconnect(address) } catch (_: Exception) {}
                    return
                }
                // Existing connection is dead — replace it
                log("Duplicate identity ${identityHex.take(8)}: replacing dead ${existingAddress.takeLast(8)} with incoming ${address.takeLast(8)}")
                tearDownPeer(identityHex)
            }

            // Store identity mapping
            addressToIdentity[address] = identityHex
            identityToAddress[identityHex] = address

            // Handle capacity: accept incoming, then evaluate
            if (peers.size >= maxConnections) {
                // Spawn the new peer first so we can score it
                spawnPeerInterface(address, identityHex, peerIdentity, connection, rssi = -70, isOutgoing = false)

                // Now find the lowest scorer among ALL peers (including new one)
                val (lowestIdentity, _) = findLowestScoredPeer() ?: return

                if (lowestIdentity == identityHex) {
                    // New peer is the worst -- evict it
                    log("Incoming peer ${identityHex.take(8)} scored lowest at capacity, removing")
                    tearDownPeer(identityHex)
                } else {
                    // Evict existing worst peer to make room
                    log("Evicting ${lowestIdentity.take(8)} to accept incoming ${identityHex.take(8)}")
                    tearDownPeer(lowestIdentity)
                }

                log("Accepted peer ${identityHex.take(8)} from ${address.takeLast(8)}")
                return  // Already spawned (or evicted) above
            }

            // Spawn peer interface (incoming connections don't have scan RSSI, use mid-range default)
            spawnPeerInterface(address, identityHex, peerIdentity, connection, rssi = -70, isOutgoing = false)

            log("Accepted peer ${identityHex.take(8)} from ${address.takeLast(8)}")

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Re-check: a healthy outgoing connection may have completed while we were waiting.
            // If so, don't disconnect — it would kill the healthy connection at this address.
            if (addressToIdentity.containsKey(address)) {
                log("Incoming handshake timed out for ${address.takeLast(8)} but outgoing connection already active, not disconnecting")
                return
            }
            log("Identity handshake timeout for incoming ${address.takeLast(8)}, disconnecting")
            addToBlacklist(address)
            try { driver.disconnect(address) } catch (_: Exception) {}
        } catch (e: CancellationException) {
            throw e
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
        rssi: Int = -100,
        isOutgoing: Boolean = true,
    ) {
        // Check if interface already exists for this identity (MAC rotation)
        val existing = peers[identityHex]
        if (existing != null) {
            existing.updateConnection(connection, address)
            existing.discoveryRssi = rssi
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
        peerInterface.discoveryRssi = rssi
        peerInterface.isOutgoing = isOutgoing

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
        val entry = blacklist[address] ?: return false
        if (System.currentTimeMillis() > entry.expiry) {
            blacklist.remove(address)
            return false
        }
        return true
    }

    /**
     * Add an address to the blacklist with exponential backoff.
     * Duration grows: 60s, 120s, 180s, 240s, 300s, 360s, 420s, 480s (capped at 8x base).
     */
    private fun addToBlacklist(address: String) {
        val existing = blacklist[address]
        val count = (existing?.failureCount ?: 0) + 1
        val duration = BLEConstants.BLACKLIST_BASE_DURATION_MS * minOf(count, BLEConstants.BLACKLIST_MAX_MULTIPLIER)
        blacklist[address] = BlacklistEntry(
            expiry = System.currentTimeMillis() + duration,
            failureCount = count,
        )
        log("Blacklisted ${address.takeLast(8)} for ${duration / 1000}s (failure #$count)")
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
            blacklist.entries.removeIf { it.value.expiry < now }

            // Clean expired backoff entries
            reconnectBackoff.entries.removeIf { it.value < now }
        }
    }

    // ---- Zombie Detection ----

    /**
     * Periodically check for zombie peers (connected but unresponsive).
     *
     * A peer is declared zombie if no traffic (data or keepalive) has been received
     * for [BLEConstants.ZOMBIE_TIMEOUT_MS] (45 seconds = 3 missed keepalives).
     *
     * Flow: graceful disconnect -> grace period -> force teardown -> blacklist.
     */
    private suspend fun zombieDetectionLoop() {
        while (online.get() && !detached.get()) {
            delay(BLEConstants.ZOMBIE_CHECK_INTERVAL_MS)
            val now = System.currentTimeMillis()

            for ((identityHex, peerInterface) in peers.toMap()) {
                val lastTraffic = peerInterface.lastTrafficReceived
                if (now - lastTraffic > BLEConstants.ZOMBIE_TIMEOUT_MS) {
                    log("Zombie detected: ${identityHex.take(8)} (no traffic for ${(now - lastTraffic) / 1000}s)")

                    // Attempt graceful disconnect first
                    val address = identityToAddress[identityHex]
                    if (address != null) {
                        try { driver.disconnect(address) } catch (_: Exception) {}
                    }

                    // Grace period for clean close
                    delay(BLEConstants.ZOMBIE_GRACE_PERIOD_MS)

                    // Force teardown if still present
                    if (peers.containsKey(identityHex)) {
                        tearDownPeer(identityHex)
                        // Blacklist after zombie teardown
                        if (address != null) addToBlacklist(address)
                    }
                }
            }
        }
    }

    // ---- Eviction Scoring ----

    /**
     * Compute a connection score for an already-connected peer.
     * Creates a synthetic [DiscoveredPeer] from the peer's current state and calls [DiscoveredPeer.connectionScore].
     */
    private fun computePeerScore(peerInterface: BLEPeerInterface): Double {
        val identityHex = peerInterface.peerIdentity.toHex()
        val address = identityToAddress[identityHex] ?: return 0.0
        return DiscoveredPeer(
            address = address,
            rssi = peerInterface.discoveryRssi,
            lastSeen = peerInterface.lastTrafficReceived,
            connectionAttempts = 1,
            connectionSuccesses = 1  // Connected peer = at least 1 success
        ).connectionScore()
    }

    /**
     * Find the lowest-scored connected peer for eviction decisions.
     * @return Pair of (identityHex, score) or null if no peers connected
     */
    private fun findLowestScoredPeer(): Pair<String, Double>? {
        return peers.entries.minByOrNull { (_, peerInterface) ->
            computePeerScore(peerInterface)
        }?.let { (identityHex, peerInterface) ->
            identityHex to computePeerScore(peerInterface)
        }
    }

    // ---- Utility ----

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun log(message: String) {
        println("[BLEInterface][$name] $message")
    }

    override fun toString(): String = "BLEInterface[$name]"
}
