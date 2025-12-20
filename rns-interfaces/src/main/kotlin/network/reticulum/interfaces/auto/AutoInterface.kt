package network.reticulum.interfaces.auto

import kotlinx.coroutines.*
import network.reticulum.crypto.Hashes
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AutoInterface enables automatic peer discovery on local networks using IPv6 multicast.
 *
 * It discovers other Reticulum nodes on the same LAN and creates peer connections
 * for direct communication. This matches the Python RNS AutoInterface implementation.
 *
 * Features:
 * - IPv6 multicast discovery announcements
 * - Automatic peer timeout and removal
 * - Per-peer UDP unicast data connections
 * - Multi-interface support
 */
class AutoInterface(
    name: String = "AutoInterface",
    /** Group ID for peer discovery (nodes must share the same group to communicate). */
    private val groupId: ByteArray = AutoInterfaceConstants.DEFAULT_GROUP_ID,
    /** IPv6 multicast scope (link-local by default). */
    private val discoveryScope: String = AutoInterfaceConstants.SCOPE_LINK,
    /** Multicast address type (temporary by default). */
    private val multicastAddressType: String = AutoInterfaceConstants.MULTICAST_TEMPORARY_ADDRESS_TYPE,
    /** Port for multicast discovery announcements. */
    private val discoveryPort: Int = AutoInterfaceConstants.DEFAULT_DISCOVERY_PORT,
    /** Port for UDP data transmission. */
    private val dataPort: Int = AutoInterfaceConstants.DEFAULT_DATA_PORT,
    /** Specific network interfaces to use (null = all suitable interfaces). */
    private val allowedDevices: List<String>? = null,
    /** Network interfaces to ignore. */
    private val ignoredDevices: List<String> = emptyList(),
    /** Configured bitrate override. */
    val configuredBitrate: Int? = null
) : Interface(name) {

    // Peer tracking
    private data class PeerInfo(
        val interfaceName: String,
        var lastHeard: Long = System.currentTimeMillis()
    )

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val spawnedPeers = ConcurrentHashMap<String, AutoInterfacePeer>()

    // Network sockets per interface
    private val discoverySocketsIn = ConcurrentHashMap<String, MulticastSocket>()
    private val discoverySocketsOut = ConcurrentHashMap<String, MulticastSocket>()
    private val dataSockets = ConcurrentHashMap<String, DatagramSocket>()

    // Multicast group address derived from group_id
    private lateinit var multicastGroup: InetSocketAddress

    // Link-local addresses per interface
    private val linkLocalAddresses = ConcurrentHashMap<String, Inet6Address>()

    // Recent packets for duplicate detection (multi-interface)
    private data class RecentPacket(val hash: Int, val timestamp: Long)
    private val recentPackets = ConcurrentHashMap.newKeySet<RecentPacket>()

    // Coroutine management
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    // Multicast echo tracking for carrier detection
    private val multicastEchoes = ConcurrentHashMap<String, Long>()

    override val bitrate: Int
        get() = configuredBitrate ?: AutoInterfaceConstants.BITRATE_GUESS

    override val hwMtu: Int = AutoInterfaceConstants.HW_MTU

    override fun start() {
        if (running.getAndSet(true)) return

        log("Starting AutoInterface...")

        try {
            // Derive multicast address from group ID
            multicastGroup = InetSocketAddress(deriveMulticastAddress(), discoveryPort)
            log("Multicast group: ${multicastGroup.address.hostAddress}")

            // Find and configure suitable network interfaces
            setupNetworkInterfaces()

            // Start discovery receivers for each interface
            startDiscoveryReceivers()

            // Start data receivers for each interface
            startDataReceivers()

            // Start announcement loop
            startAnnouncementLoop()

            // Start peer management job
            startPeerManagementJob()

            online.set(true)
            log("AutoInterface started on ${linkLocalAddresses.size} interface(s)")

        } catch (e: Exception) {
            log("Failed to start AutoInterface: ${e.message}")
            e.printStackTrace()
            running.set(false)
        }
    }

    override fun detach() {
        if (!running.getAndSet(false)) return

        log("Detaching AutoInterface...")
        online.set(false)
        detached.set(true)

        // Cancel all coroutines
        scope.cancel()

        // Close all sockets
        discoverySocketsIn.values.forEach { it.close() }
        discoverySocketsOut.values.forEach { it.close() }
        dataSockets.values.forEach { it.close() }

        // Detach all spawned peers
        spawnedPeers.values.forEach { it.detach() }
        spawnedPeers.clear()
        peers.clear()

        log("AutoInterface detached")
    }

    override fun processOutgoing(data: ByteArray) {
        // AutoInterface doesn't send directly - data goes through spawned peers
        // This is called when Transport wants to broadcast on all interfaces
        // We forward to all spawned peer interfaces
        spawnedPeers.values.forEach { peer ->
            if (peer.online.get()) {
                peer.processOutgoing(data)
            }
        }
    }

    /**
     * Derive the IPv6 multicast address from the group ID.
     *
     * The address is constructed as: ff[type][scope]:xxxx:xxxx:xxxx:xxxx
     * where the x's are derived from the hash of the group ID.
     */
    private fun deriveMulticastAddress(): Inet6Address {
        val groupHash = Hashes.fullHash(groupId)

        // Build IPv6 multicast address (16 bytes)
        val addrBytes = ByteArray(16)

        // First byte: 0xFF (multicast prefix)
        addrBytes[0] = 0xFF.toByte()

        // Second byte: [type nibble][scope nibble]
        val typeNibble = multicastAddressType.toInt(16)
        val scopeNibble = discoveryScope.toInt(16)
        addrBytes[1] = ((typeNibble shl 4) or scopeNibble).toByte()

        // Python hardcodes the first 16-bit group to 0, then uses hash bytes 2-13
        // See AutoInterface.py lines 199-207: gt = "0" (not using hash bytes 0-1)
        addrBytes[2] = 0
        addrBytes[3] = 0
        System.arraycopy(groupHash, 2, addrBytes, 4, 12)

        return Inet6Address.getByAddress(null, addrBytes, null) as Inet6Address
    }

    /**
     * Find and configure suitable network interfaces for discovery.
     */
    private fun setupNetworkInterfaces() {
        val ignoredSet = buildIgnoredSet()

        NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { netIf ->
            val ifName = netIf.name

            // Skip if interface is not usable
            if (!netIf.isUp || netIf.isLoopback || !netIf.supportsMulticast()) {
                return@forEach
            }

            // Skip if in ignored list
            if (ignoredSet.contains(ifName)) {
                return@forEach
            }

            // Skip if allowedDevices is set and this interface isn't in it
            if (allowedDevices != null && !allowedDevices.contains(ifName)) {
                return@forEach
            }

            // Find link-local IPv6 address
            val linkLocal = netIf.inetAddresses.asSequence()
                .filterIsInstance<Inet6Address>()
                .firstOrNull { it.isLinkLocalAddress }

            if (linkLocal == null) {
                log("Skipping $ifName: no IPv6 link-local address")
                return@forEach
            }

            log("Using interface $ifName with ${linkLocal.hostAddress}")
            linkLocalAddresses[ifName] = linkLocal

            try {
                // Create discovery input socket (multicast receiver)
                val discoveryIn = MulticastSocket(discoveryPort).apply {
                    reuseAddress = true
                    networkInterface = netIf
                    joinGroup(multicastGroup, netIf)
                }
                discoverySocketsIn[ifName] = discoveryIn

                // Create discovery output socket (for sending announcements)
                // Must use MulticastSocket and set the outgoing interface
                val discoveryOut = MulticastSocket().apply {
                    reuseAddress = true
                    networkInterface = netIf
                }
                discoverySocketsOut[ifName] = discoveryOut

                // Create data socket (for receiving unicast data)
                // Use DatagramChannel for potential SO_REUSEPORT support
                val dataChannel = DatagramChannel.open(StandardProtocolFamily.INET6).apply {
                    setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    // Try to set SO_REUSEPORT via reflection (JDK 14+)
                    trySetReusePort(this)
                    bind(InetSocketAddress(linkLocal, dataPort))
                    configureBlocking(true)
                }
                dataSockets[ifName] = dataChannel.socket()

            } catch (e: Exception) {
                log("Failed to setup sockets for $ifName: ${e.message}")
            }
        }

        if (linkLocalAddresses.isEmpty()) {
            throw IllegalStateException("No suitable network interfaces found")
        }
    }

    /**
     * Build the set of interfaces to ignore.
     */
    private fun buildIgnoredSet(): Set<String> {
        val set = mutableSetOf<String>()
        set.addAll(ignoredDevices)
        set.addAll(AutoInterfaceConstants.ALL_IGNORE_IFS)

        // Add platform-specific ignores
        val osName = System.getProperty("os.name", "").lowercase()
        when {
            osName.contains("linux") -> set.addAll(AutoInterfaceConstants.LINUX_IGNORE_IFS)
            osName.contains("mac") || osName.contains("darwin") -> set.addAll(AutoInterfaceConstants.DARWIN_IGNORE_IFS)
        }

        // Check for Android
        try {
            Class.forName("android.os.Build")
            set.addAll(AutoInterfaceConstants.ANDROID_IGNORE_IFS)
        } catch (e: ClassNotFoundException) {
            // Not Android
        }

        return set
    }

    /**
     * Start discovery receiver threads for each interface.
     */
    private fun startDiscoveryReceivers() {
        discoverySocketsIn.forEach { (ifName, socket) ->
            scope.launch {
                discoveryHandler(ifName, socket)
            }
        }
    }

    /**
     * Handle discovery packets on a specific interface.
     */
    private suspend fun discoveryHandler(ifName: String, socket: MulticastSocket) {
        // SHA-256 produces 32 bytes
        val buffer = ByteArray(32)

        log("Discovery handler started for $ifName")

        while (running.get() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    socket.receive(packet)
                }

                val receivedHash = packet.data.copyOf(packet.length)
                val senderAddr = packet.address
                val senderAddrRaw = senderAddr.hostAddress ?: continue

                // Only process IPv6 link-local addresses
                if (senderAddr !is Inet6Address || !senderAddr.isLinkLocalAddress) {
                    continue
                }

                val senderAddrStr = senderAddrRaw

                // Skip our own announcements
                if (isOwnAddress(senderAddrStr)) {
                    // But record multicast echo for carrier detection
                    multicastEchoes[ifName] = System.currentTimeMillis()
                    continue
                }

                // Log all non-own packets for debugging
                log("Received discovery from $senderAddrStr on $ifName")

                // Validate the discovery token
                val expectedHash = computePeeringHash(senderAddrStr)
                if (!receivedHash.contentEquals(expectedHash)) {
                    log("Hash mismatch from $senderAddrStr")
                    continue
                }

                // Valid peer discovered
                addPeer(senderAddrStr, ifName)

            } catch (e: SocketException) {
                if (running.get()) {
                    log("Discovery socket error on $ifName: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (running.get()) {
                    log("Discovery handler error on $ifName: ${e.message}")
                }
            }
        }

        log("Discovery handler stopped for $ifName")
    }

    /**
     * Start data receiver threads for each interface.
     */
    private fun startDataReceivers() {
        dataSockets.forEach { (ifName, socket) ->
            scope.launch {
                dataHandler(ifName, socket)
            }
        }
    }

    /**
     * Handle incoming data packets on a specific interface.
     */
    private suspend fun dataHandler(ifName: String, socket: DatagramSocket) {
        val buffer = ByteArray(AutoInterfaceConstants.HW_MTU + 64)

        log("Data handler started for $ifName")

        while (running.get() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    socket.receive(packet)
                }

                val data = packet.data.copyOf(packet.length)
                val senderAddr = packet.address

                if (senderAddr !is Inet6Address) continue

                val senderAddrRaw = senderAddr.hostAddress ?: continue
                // Strip scope ID for consistent lookup (addresses stored without scope)
                val senderAddrStr = senderAddrRaw.substringBefore('%')

                // Skip our own packets
                if (isOwnAddress(senderAddrStr)) continue

                // Check for duplicate (multi-interface deduplication)
                val packetHash = data.contentHashCode()
                if (isDuplicate(packetHash)) continue

                // Find the peer interface for this sender
                val peer = spawnedPeers[senderAddrStr]
                if (peer != null) {
                    // Refresh peer and forward data
                    peer.refresh()
                    peer.handleIncomingData(data)
                } else {
                    // Unknown peer - might be a new discovery
                    log("Data from unknown peer: $senderAddrStr")
                }

            } catch (e: SocketException) {
                if (running.get()) {
                    log("Data socket error on $ifName: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (running.get()) {
                    log("Data handler error on $ifName: ${e.message}")
                }
            }
        }

        log("Data handler stopped for $ifName")
    }

    /**
     * Check if packet is duplicate (for multi-interface deduplication).
     */
    private fun isDuplicate(packetHash: Int): Boolean {
        val now = System.currentTimeMillis()

        // Clean old entries
        recentPackets.removeIf { now - it.timestamp > AutoInterfaceConstants.MULTI_IF_DEQUE_TTL_MS }

        // Check if we've seen this packet
        val entry = RecentPacket(packetHash, now)
        return !recentPackets.add(entry)
    }

    /**
     * Start the announcement loop.
     */
    private fun startAnnouncementLoop() {
        scope.launch {
            log("Announcement loop started")
            while (running.get()) {
                sendDiscoveryAnnouncements()
                delay(AutoInterfaceConstants.ANNOUNCE_INTERVAL_MS)
            }
            log("Announcement loop stopped")
        }
    }

    /**
     * Send discovery announcements on all interfaces.
     */
    private fun sendDiscoveryAnnouncements() {
        linkLocalAddresses.forEach { (ifName, linkLocal) ->
            try {
                val socket = discoverySocketsOut[ifName] ?: return@forEach
                val addrStr = linkLocal.hostAddress ?: return@forEach

                // Compute discovery token: hash(group_id + link_local_address)
                val token = computePeeringHash(addrStr)

                // Send to multicast group
                val packet = DatagramPacket(token, token.size, multicastGroup)
                socket.send(packet)

            } catch (e: Exception) {
                log("Failed to send announcement on $ifName: ${e.message}")
            }
        }
    }

    /**
     * Compute the peering hash for a given address.
     */
    private fun computePeeringHash(address: String): ByteArray {
        // Clean address (remove %interface suffix if present) and compress
        val cleanAddr = address.substringBefore('%')
        val compressedAddr = compressIPv6(cleanAddr)
        return Hashes.fullHash(groupId + compressedAddr.toByteArray(Charsets.UTF_8))
    }

    /**
     * Compress an IPv6 address to canonical form (matching Python's format).
     * e.g., "fe80:0:0:0:1234:5678:abcd:ef01" -> "fe80::1234:5678:abcd:ef01"
     */
    private fun compressIPv6(address: String): String {
        return try {
            // Parse and re-format to get compressed form
            val inet = InetAddress.getByName(address) as Inet6Address
            // Java's getHostAddress returns expanded form, so we need to compress manually
            val parts = address.split(":")
            if (parts.size != 8) return address

            // Find longest run of zeros
            var longestStart = -1
            var longestLen = 0
            var currentStart = -1
            var currentLen = 0

            for (i in parts.indices) {
                if (parts[i] == "0") {
                    if (currentStart == -1) currentStart = i
                    currentLen++
                    if (currentLen > longestLen) {
                        longestStart = currentStart
                        longestLen = currentLen
                    }
                } else {
                    currentStart = -1
                    currentLen = 0
                }
            }

            // Build compressed address
            if (longestLen >= 2) {
                val before = parts.subList(0, longestStart).joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
                val after = parts.subList(longestStart + longestLen, 8).joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
                when {
                    longestStart == 0 && longestLen == 8 -> "::"
                    longestStart == 0 -> "::$after"
                    longestStart + longestLen == 8 -> "$before::"
                    else -> "$before::$after"
                }
            } else {
                parts.joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
            }
        } catch (e: Exception) {
            address
        }
    }

    /**
     * Check if an address belongs to us.
     */
    private fun isOwnAddress(address: String): Boolean {
        val cleanAddr = address.substringBefore('%')
        return linkLocalAddresses.values.any {
            it.hostAddress?.substringBefore('%') == cleanAddr
        }
    }

    /**
     * Start the peer management job.
     */
    private fun startPeerManagementJob() {
        scope.launch {
            log("Peer management job started")
            while (running.get()) {
                delay(AutoInterfaceConstants.PEER_JOB_INTERVAL_MS)
                peerJobs()
            }
            log("Peer management job stopped")
        }
    }

    /**
     * Periodic peer management: timeout stale peers, log status.
     */
    private fun peerJobs() {
        val now = System.currentTimeMillis()
        val timeout = AutoInterfaceConstants.PEERING_TIMEOUT_MS

        val toRemove = mutableListOf<String>()

        peers.forEach { (addr, info) ->
            if (now - info.lastHeard > timeout) {
                toRemove.add(addr)
            }
        }

        toRemove.forEach { addr ->
            removePeer(addr)
        }

        // Check for multicast echo timeout (carrier detection)
        multicastEchoes.forEach { (ifName, lastEcho) ->
            if (now - lastEcho > AutoInterfaceConstants.MCAST_ECHO_TIMEOUT_MS) {
                log("Warning: No multicast echo on $ifName for ${(now - lastEcho) / 1000}s")
            }
        }
    }

    /**
     * Add a newly discovered peer.
     */
    private fun addPeer(address: String, interfaceName: String) {
        val cleanAddr = address.substringBefore('%')

        // Check if already known
        if (peers.containsKey(cleanAddr)) {
            // Just refresh
            refreshPeer(cleanAddr)
            return
        }

        log("Discovered peer: $cleanAddr on $interfaceName")

        // Create peer info
        peers[cleanAddr] = PeerInfo(interfaceName)

        // Create AutoInterfacePeer interface
        // For IPv6 link-local addresses, we need to include the scope ID (interface)
        // to ensure proper routing
        val networkInterface = NetworkInterface.getByName(interfaceName)
        val peerInet6Addr = Inet6Address.getByAddress(
            null,
            InetAddress.getByName(cleanAddr).address,
            networkInterface
        )
        val targetAddr = InetSocketAddress(peerInet6Addr, dataPort)

        val peer = AutoInterfacePeer(
            name = "$name[$cleanAddr]",
            parent = this,
            targetAddress = targetAddr,
            interfaceName = interfaceName
        )

        peer.start()
        spawnedPeers[cleanAddr] = peer

        // Wire up to Transport
        val peerRef = peer.toRef()
        peer.onPacketReceived = { data, _ ->
            Transport.inbound(data, peerRef)
        }
        Transport.registerInterface(peerRef)

        log("Peer $cleanAddr added and registered with Transport")
    }

    /**
     * Remove a timed-out peer.
     */
    private fun removePeer(address: String) {
        log("Removing timed-out peer: $address")

        peers.remove(address)

        val peer = spawnedPeers.remove(address)
        if (peer != null) {
            peer.detach()
            // Note: Transport.deregisterInterface would need to be implemented
        }
    }

    /**
     * Refresh a peer's last-heard timestamp.
     */
    fun refreshPeer(address: String) {
        val cleanAddr = address.substringBefore('%')
        peers[cleanAddr]?.lastHeard = System.currentTimeMillis()
    }

    /**
     * Get the number of currently known peers.
     */
    fun peerCount(): Int = peers.size

    /**
     * Try to set SO_REUSEPORT on a DatagramChannel via reflection.
     * This option allows multiple sockets to bind to the same port.
     */
    private fun trySetReusePort(channel: DatagramChannel) {
        try {
            // Try to access jdk.net.ExtendedSocketOptions.SO_REUSEPORT via reflection
            val extOptClass = Class.forName("jdk.net.ExtendedSocketOptions")
            val reusePortField = extOptClass.getField("SO_REUSEPORT")
            @Suppress("UNCHECKED_CAST")
            val reusePortOption = reusePortField.get(null) as SocketOption<Boolean>
            channel.setOption(reusePortOption, true)
        } catch (e: Exception) {
            // SO_REUSEPORT not available on this JDK version or platform
        }
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalTime.now().toString().take(12)
        println("[$timestamp] [$name] $message")
    }

    override fun toString(): String = "AutoInterface[$name, ${peerCount()} peers]"
}
