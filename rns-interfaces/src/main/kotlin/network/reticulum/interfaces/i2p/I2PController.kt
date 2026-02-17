package network.reticulum.interfaces.i2p

import network.reticulum.crypto.Hashes
import network.reticulum.transport.Transport
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * I2P tunnel controller — manages SAM sessions and tunnel lifecycle.
 *
 * Port of Python's I2PController. This is the bridge between the Reticulum
 * interface code and the I2P router's SAM API. It handles:
 *
 * - **Client tunnels**: Local TCP port → SAM → I2P destination
 *   (for connecting to remote I2P peers)
 * - **Server tunnels**: I2P incoming → SAM → local TCP port
 *   (for accepting connections from I2P peers)
 * - **Key management**: Generating and persisting I2P destination keys
 *
 * The controller uses SAM's STREAM FORWARD mode for server tunnels,
 * which tells SAM to forward incoming I2P connections to a local port.
 * For client tunnels, it creates a SAM session and connects streams on demand.
 *
 * @param storagePath Base Reticulum storage path (keys stored in `{storagePath}/i2p/`)
 * @param samAddress SAM API address (default: 127.0.0.1:7656)
 */
class I2PController(
    storagePath: String,
    private val samAddress: InetSocketAddress = I2PSamClient.getSamAddress()
) {
    /** Path where I2P destination key files are stored. */
    val keyStoragePath: String = "$storagePath/i2p"

    /** Tracks whether client tunnels are established. Key = I2P destination base64. */
    private val clientTunnelReady = ConcurrentHashMap<String, Boolean>()

    /** Tracks whether server tunnels are established. Key = base32 address. */
    private val serverTunnelReady = ConcurrentHashMap<String, Boolean>()

    /** Active SAM session connections (keep alive to maintain tunnels). */
    private val sessionConnections = ConcurrentHashMap<String, SamConnection>()

    /** Whether the controller has been stopped. */
    private val stopped = AtomicBoolean(false)

    init {
        val dir = File(keyStoragePath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * Set up a client tunnel to a remote I2P destination.
     *
     * Creates a SAM session and STREAM CONNECT, then returns the local
     * address that can be used to reach the remote destination.
     *
     * This method blocks until the tunnel is ready or fails.
     *
     * @param sessionName Unique session name for this tunnel.
     * @param i2pDestination The remote I2P destination (base64).
     * @return The SAM connection with an active stream to the remote peer,
     *         or null if tunnel setup failed.
     * @throws SamException if SAM API returns an error.
     */
    fun setupClientTunnel(sessionName: String, i2pDestination: String): SamConnection? {
        if (stopped.get()) return null

        clientTunnelReady[i2pDestination] = false

        try {
            log("Setting up client tunnel to $sessionName...")

            // Create a SAM session with a transient destination
            val sessionConn = I2PSamClient.createSession(
                sessionName = sessionName,
                samAddress = samAddress
            )
            sessionConnections[sessionName] = sessionConn

            // Now connect a stream through the session
            val streamConn = I2PSamClient.streamConnect(
                sessionName = sessionName,
                destination = i2pDestination,
                samAddress = samAddress
            )

            clientTunnelReady[i2pDestination] = true
            log("Client tunnel to $sessionName established")
            return streamConn

        } catch (e: Exception) {
            log("Client tunnel setup failed for $sessionName: ${e.message}")
            clientTunnelReady[i2pDestination] = false
            throw e
        }
    }

    /**
     * Set up a server tunnel for accepting incoming I2P connections.
     *
     * Creates or loads an I2P destination, creates a SAM session bound to it,
     * and sets up STREAM FORWARD to direct incoming connections to a local port.
     *
     * @param interfaceName Name of the interface (used for key file hashing).
     * @param localPort Local TCP port to forward incoming connections to.
     * @return The base32 address of the server's I2P destination.
     * @throws SamException if SAM API returns an error.
     */
    fun setupServerTunnel(interfaceName: String, localPort: Int): String {
        if (stopped.get()) throw SamException("Controller is stopped")

        // Wait for Transport identity to be available
        var waited = 0
        while (Transport.identity == null && waited < 30_000) {
            Thread.sleep(1000)
            waited += 1000
        }

        // Determine key file path (matching Python's dual-format approach)
        val dest = loadOrCreateDestination(interfaceName)
        val b32 = dest.base32
        val sessionName = I2PSamClient.generateSessionId()

        serverTunnelReady[b32] = false

        try {
            log("Setting up server tunnel, this may take a while...")

            // Create session with our persistent destination
            val sessionConn = I2PSamClient.createSession(
                sessionName = sessionName,
                samAddress = samAddress,
                destination = dest
            )
            sessionConnections["server-$b32"] = sessionConn

            // Set up stream forwarding to our local port
            val forwardConn = I2PSamClient.streamForward(
                sessionName = sessionName,
                port = localPort,
                samAddress = samAddress
            )
            sessionConnections["forward-$b32"] = forwardConn

            serverTunnelReady[b32] = true
            log("Server tunnel established. Reachable at: $b32.b32.i2p")
            return b32

        } catch (e: Exception) {
            log("Server tunnel setup failed: ${e.message}")
            serverTunnelReady[b32] = false
            throw e
        }
    }

    /**
     * Load an existing I2P destination from disk, or generate a new one.
     *
     * Follows Python's dual key file format:
     * - Old format: hash(hash(name))
     * - New format: hash(hash(name) + hash(transport_identity))
     * Uses old format if file exists, otherwise new format.
     */
    private fun loadOrCreateDestination(interfaceName: String): Destination {
        // Old format key file (Python compatibility)
        val oldHash = Hashes.fullHash(Hashes.fullHash(interfaceName.toByteArray()))
        val oldKeyFile = File(keyStoragePath, oldHash.toHex() + ".i2p")

        // New format with transport identity
        val transportHash = Transport.identity?.let { Hashes.fullHash(it.hash) } ?: ByteArray(0)
        val newHash = Hashes.fullHash(Hashes.fullHash(interfaceName.toByteArray()) + transportHash)
        val newKeyFile = File(keyStoragePath, newHash.toHex() + ".i2p")

        // Prefer old format if it exists (backward compatibility)
        val keyFile = if (oldKeyFile.exists()) oldKeyFile else newKeyFile

        return if (keyFile.exists()) {
            log("Loading I2P destination from ${keyFile.name}")
            val keyData = keyFile.readText().trim()
            Destination(keyData, hasPrivateKey = true)
        } else {
            log("Generating new I2P destination...")
            val dest = I2PSamClient.generateDestination(samAddress)
            keyFile.writeText(dest.privateKey!!.base64)
            log("Saved new I2P destination to ${keyFile.name}")
            dest
        }
    }

    /**
     * Stop all tunnels and close SAM connections.
     */
    fun stop() {
        stopped.set(true)
        for ((name, conn) in sessionConnections) {
            try {
                conn.close()
            } catch (e: Exception) {
                log("Error closing session $name: ${e.message}")
            }
        }
        sessionConnections.clear()
        clientTunnelReady.clear()
        serverTunnelReady.clear()
        log("Controller stopped")
    }

    /**
     * Check if a client tunnel is ready.
     */
    fun isClientTunnelReady(destination: String): Boolean {
        return clientTunnelReady[destination] == true
    }

    /**
     * Check if a server tunnel is ready.
     */
    fun isServerTunnelReady(b32: String): Boolean {
        return serverTunnelReady[b32] == true
    }

    private fun log(message: String) {
        val timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        println("[$timestamp] [I2PController] $message")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
