package network.reticulum

import network.reticulum.common.RnsConstants
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the Reticulum Network Stack.
 *
 * This class initializes and manages the Reticulum instance. You must create
 * exactly one instance of this class before performing any other RNS operations.
 *
 * Usage:
 * ```kotlin
 * // Initialize with defaults
 * val reticulum = Reticulum.start()
 *
 * // Or with custom config
 * val reticulum = Reticulum.start(
 *     configDir = "/path/to/config",
 *     enableTransport = true
 * )
 *
 * // Start as shared instance (other apps can connect)
 * val reticulum = Reticulum.start(
 *     shareInstance = true,
 *     sharedInstancePort = 37428
 * )
 *
 * // Connect to existing shared instance
 * val reticulum = Reticulum.start(
 *     connectToSharedInstance = true,
 *     sharedInstancePort = 37428
 * )
 *
 * // Create identity and destination
 * val identity = Identity.create()
 * val destination = Destination.create(identity, ...)
 *
 * // Shutdown when done
 * Reticulum.stop()
 * ```
 */
class Reticulum private constructor(
    val configDir: String,
    val enableTransport: Boolean,
    val shareInstance: Boolean,
    val sharedInstancePort: Int,
    val connectToSharedInstance: Boolean,
) {
    companion object {
        /**
         * MTU that Reticulum adheres to (500 bytes by default).
         * All peers must use the same MTU to communicate.
         */
        const val MTU = RnsConstants.MTU

        /**
         * Maximum Data Unit - maximum payload size in a single packet.
         */
        const val MDU = RnsConstants.MTU - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE

        /**
         * Whether link MTU discovery is enabled.
         */
        const val LINK_MTU_DISCOVERY = true

        /**
         * Maximum queued announces.
         */
        const val MAX_QUEUED_ANNOUNCES = 16384

        /**
         * Announce cap - maximum percentage of bandwidth for announces.
         */
        const val ANNOUNCE_CAP = 2

        /**
         * Minimum bitrate required for links (5 bps).
         */
        const val MINIMUM_BITRATE = 5

        /**
         * Default timeout per hop (6 seconds).
         */
        const val DEFAULT_PER_HOP_TIMEOUT = 6

        /**
         * Default TCP port for shared instance communication.
         */
        const val DEFAULT_SHARED_INSTANCE_PORT = 37428

        private var instance: Reticulum? = null
        private val started = AtomicBoolean(false)

        // Pre-set factories (set before start, applied to instance during init)
        private var pendingLocalClientFactory: ((Int, String) -> Any)? = null
        private var pendingLocalServerFactory: ((Int) -> Any)? = null
        private var pendingInterfaceRegistrar: ((Any) -> Unit)? = null

        /**
         * Set the LocalClientInterface factory before calling start().
         * This allows apps to connect to an existing shared Reticulum instance.
         */
        fun setLocalClientFactory(factory: (port: Int, host: String) -> Any) {
            pendingLocalClientFactory = factory
        }

        /**
         * Set the LocalServerInterface factory before calling start().
         * This allows apps to share their Reticulum instance with others.
         */
        fun setLocalServerFactory(factory: (port: Int) -> Any) {
            pendingLocalServerFactory = factory
        }

        /**
         * Set an interface registrar that can adapt and register interfaces with Transport.
         * This bridges rns-core and rns-interfaces by allowing the app to provide
         * the InterfaceAdapter.toRef() + Transport.registerInterface() logic.
         */
        fun setInterfaceRegistrar(registrar: (Any) -> Unit) {
            pendingInterfaceRegistrar = registrar
        }

        /**
         * Get the current Reticulum instance.
         * @throws IllegalStateException if not started
         */
        fun getInstance(): Reticulum = instance ?: throw IllegalStateException("Reticulum not started")

        /**
         * Check if Reticulum is currently running.
         */
        fun isStarted(): Boolean = started.get()

        /**
         * Start Reticulum with the given configuration.
         *
         * @param configDir Directory for configuration and storage
         * @param enableTransport Whether to enable transport layer (routing)
         * @param shareInstance Whether to share this instance with other apps (starts local server)
         * @param sharedInstancePort TCP port for shared instance communication
         * @param connectToSharedInstance Whether to connect to an existing shared instance
         * @return The Reticulum instance
         */
        fun start(
            configDir: String? = null,
            enableTransport: Boolean = false,
            shareInstance: Boolean = false,
            sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
            connectToSharedInstance: Boolean = false,
        ): Reticulum {
            if (started.compareAndSet(false, true)) {
                val dir = configDir ?: getDefaultConfigDir()
                val rns = Reticulum(dir, enableTransport, shareInstance, sharedInstancePort, connectToSharedInstance)
                instance = rns

                // Apply pre-set factories before initialize
                pendingLocalClientFactory?.let { rns.localClientInterfaceFactory = it }
                pendingLocalServerFactory?.let { rns.localServerInterfaceFactory = it }
                pendingInterfaceRegistrar?.let { rns.interfaceRegistrar = it }

                rns.initialize()
                return rns
            }
            return instance!!
        }

        /**
         * Check if a shared instance is already running on the given port.
         *
         * @param port TCP port to check
         * @param host Host to check (default: localhost)
         * @return true if a shared instance is responding on that port
         */
        fun isSharedInstanceRunning(
            port: Int = DEFAULT_SHARED_INSTANCE_PORT,
            host: String = "127.0.0.1",
        ): Boolean =
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1000)
                    true
                }
            } catch (e: Exception) {
                false
            }

        /**
         * Stop Reticulum and clean up resources.
         */
        fun stop() {
            if (started.compareAndSet(true, false)) {
                instance?.shutdown()
                instance = null
            }
        }

        /**
         * Get the default configuration directory.
         */
        private fun getDefaultConfigDir(): String {
            val userHome = System.getProperty("user.home")
            return "$userHome/.reticulum"
        }

        /**
         * Check if transport routing is enabled.
         */
        fun transportEnabled(): Boolean = instance?.enableTransport ?: false

        /**
         * Check if link MTU discovery is enabled.
         */
        fun linkMtuDiscovery(): Boolean = LINK_MTU_DISCOVERY

        private fun log(message: String) {
            val timestamp =
                java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                )
            println("[$timestamp] [Reticulum] $message")
        }
    }

    // Storage paths
    val storagePath: String = "$configDir/storage"
    val cachePath: String = "$configDir/cache"
    val identityPath: String = "$configDir/identities"

    // State
    private val interfaces = mutableListOf<Any>()
    private val shutdownHooks = mutableListOf<() -> Unit>()

    /** Whether this instance is the shared instance (has the local server running). */
    var isSharedInstance: Boolean = false
        private set

    /** Whether this instance is connected to a shared instance. */
    var isConnectedToSharedInstance: Boolean = false
        private set

    /** The shared instance interface (LocalServerInterface or LocalClientInterface). */
    private var sharedInterface: Any? = null

    /** Callback to create LocalServerInterface (set by rns-interfaces module). */
    var localServerInterfaceFactory: ((Int) -> Any)? = null

    /** Callback to create LocalClientInterface (set by rns-interfaces module). */
    var localClientInterfaceFactory: ((Int, String) -> Any)? = null

    /** Callback to adapt an interface and register it with Transport. */
    var interfaceRegistrar: ((Any) -> Unit)? = null

    /**
     * Initialize the Reticulum instance.
     */
    private fun initialize() {
        log("Initializing Reticulum...")

        // Ensure directories exist
        ensureDirectories()

        // Load or create transport identity
        val transportIdentity = loadOrCreateTransportIdentity()

        // Configure Transport and Identity storage paths
        Transport.setCachePath(cachePath)
        Transport.setStoragePath(storagePath)
        Identity.setStoragePath(storagePath)
        Identity.loadKnownDestinations()

        // Check if we should connect to an existing shared instance
        if (connectToSharedInstance) {
            if (tryConnectToSharedInstance(transportIdentity)) {
                log("Connected to shared instance on port $sharedInstancePort")
                return
            } else {
                log("No shared instance found, starting standalone")
            }
        }

        // Start Transport with identity
        Transport.start(transportIdentity = transportIdentity, enableTransport = enableTransport)

        // If shareInstance is enabled, start the local server
        if (shareInstance) {
            startLocalServer()
        }

        log("Reticulum started (transport=${if (enableTransport) "enabled" else "disabled"}, shared=$isSharedInstance)")
    }

    /**
     * Try to connect to an existing shared instance.
     *
     * @return true if connected successfully
     */
    private fun tryConnectToSharedInstance(transportIdentity: Identity): Boolean {
        val factory = localClientInterfaceFactory
        if (factory == null) {
            log("LocalClientInterface factory not set, cannot connect to shared instance")
            return false
        }

        if (!isSharedInstanceRunning(sharedInstancePort)) {
            return false
        }

        try {
            val clientInterface = factory(sharedInstancePort, "127.0.0.1")

            // Start Transport (without transport routing) so inbound() works
            Transport.start(transportIdentity = transportIdentity, enableTransport = false)

            // Start the interface
            clientInterface::class.java.getMethod("start").invoke(clientInterface)

            // Register with Transport so packets flow through
            val registrar = interfaceRegistrar
            if (registrar != null) {
                registrar(clientInterface)
            } else {
                log("WARNING: No interface registrar set, packets will not be processed")
            }

            // Set state only after all steps succeed (matches Python Reticulum.py:414-416)
            sharedInterface = clientInterface
            isConnectedToSharedInstance = true
            Transport.isConnectedToSharedInstance = true

            log("Connected to shared instance")
            return true
        } catch (e: Exception) {
            log("Failed to connect to shared instance: ${e.message}")
            isConnectedToSharedInstance = false
            Transport.isConnectedToSharedInstance = false
            return false
        }
    }

    /**
     * Start the local server interface for sharing this instance.
     */
    private fun startLocalServer() {
        val factory = localServerInterfaceFactory
        if (factory == null) {
            log("LocalServerInterface factory not set, cannot share instance")
            return
        }

        try {
            val serverInterface = factory(sharedInstancePort)
            sharedInterface = serverInterface
            isSharedInstance = true

            // Start the interface
            serverInterface::class.java.getMethod("start").invoke(serverInterface)

            // Register with Transport
            val registrar = interfaceRegistrar
            if (registrar != null) {
                registrar(serverInterface)
            } else {
                log("WARNING: No interface registrar set for server interface")
            }

            log("Started shared instance server on port $sharedInstancePort")
        } catch (e: Exception) {
            log("Failed to start shared instance server: ${e.message}")
        }
    }

    /**
     * Load transport identity from file or create a new one.
     * The identity is persisted to ensure consistent RPC keys across restarts.
     */
    private fun loadOrCreateTransportIdentity(): Identity {
        val identityFile = File("$storagePath/transport_identity")

        return if (identityFile.exists()) {
            val identity = Identity.fromFile(identityFile.absolutePath)
            if (identity != null) {
                log("Loaded transport identity")
                identity
            } else {
                log("Failed to load transport identity, creating new")
                createAndSaveTransportIdentity(identityFile)
            }
        } else {
            log("No transport identity found, creating new")
            createAndSaveTransportIdentity(identityFile)
        }
    }

    /**
     * Create a new transport identity and save it to file.
     */
    private fun createAndSaveTransportIdentity(file: File): Identity {
        val identity = Identity.create()
        identity.toFile(file.absolutePath)
        return identity
    }

    /**
     * Ensure required directories exist.
     */
    private fun ensureDirectories() {
        listOf(configDir, storagePath, cachePath, identityPath).forEach { path ->
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Add an interface to Reticulum.
     */
    fun addInterface(iface: Any) {
        interfaces.add(iface)
        // Interface will be registered with Transport separately
    }

    /**
     * Remove an interface from Reticulum.
     */
    fun removeInterface(iface: Any) {
        interfaces.remove(iface)
    }

    /**
     * Get all registered interfaces.
     */
    fun getInterfaces(): List<Any> = interfaces.toList()

    /**
     * Get the path table as a map of destination hash (hex) to hop count.
     */
    fun getPathTable(): Map<String, Int> =
        Transport.pathTable
            .mapKeys { it.key.toString() }
            .mapValues { it.value.hops }

    /**
     * Register a destination with Transport.
     */
    fun registerDestination(destination: Destination) {
        Transport.registerDestination(destination)
    }

    /**
     * Register a shutdown hook to be called when Reticulum stops.
     */
    fun addShutdownHook(hook: () -> Unit) {
        shutdownHooks.add(hook)
    }

    /**
     * Get the first hop timeout for a destination.
     */
    fun getFirstHopTimeout(destinationHash: ByteArray): Long {
        val hops = Transport.hopsTo(destinationHash) ?: 1
        return DEFAULT_PER_HOP_TIMEOUT * 1000L * maxOf(1, hops)
    }

    /**
     * Shutdown Reticulum.
     */
    private fun shutdown() {
        log("Shutting down Reticulum...")

        // Persist known destinations before shutdown so identities survive restart
        try {
            Identity.saveKnownDestinations()
        } catch (e: Exception) {
            log("Error saving known destinations: ${e.message}")
        }

        // Run shutdown hooks
        shutdownHooks.forEach { hook ->
            try {
                hook()
            } catch (e: Exception) {
                log("Error in shutdown hook: ${e.message}")
            }
        }
        shutdownHooks.clear()

        // Detach shared interface
        sharedInterface?.let { iface ->
            try {
                iface::class.java.getMethod("detach").invoke(iface)
            } catch (e: Exception) {
                log("Error detaching shared interface: ${e.message}")
            }
        }
        sharedInterface = null
        isSharedInstance = false
        isConnectedToSharedInstance = false
        Transport.isConnectedToSharedInstance = false

        // Detach interfaces
        interfaces.clear()

        // Stop Transport (only if we're not just a client)
        if (!connectToSharedInstance || !isConnectedToSharedInstance) {
            Transport.stop()
        }

        log("Reticulum stopped")
    }

    /**
     * Get the number of clients connected to this shared instance.
     *
     * @return number of connected clients, or 0 if not a shared instance
     */
    fun getSharedInstanceClientCount(): Int {
        val server = sharedInterface ?: return 0
        return try {
            server::class.java.getMethod("clientCount").invoke(server) as Int
        } catch (e: Exception) {
            0
        }
    }
}
