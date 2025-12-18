package network.reticulum

import network.reticulum.common.RnsConstants
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import java.io.File
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
    val enableTransport: Boolean
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

        private var instance: Reticulum? = null
        private val started = AtomicBoolean(false)

        /**
         * Get the current Reticulum instance.
         * @throws IllegalStateException if not started
         */
        fun getInstance(): Reticulum {
            return instance ?: throw IllegalStateException("Reticulum not started")
        }

        /**
         * Check if Reticulum is currently running.
         */
        fun isStarted(): Boolean = started.get()

        /**
         * Start Reticulum with the given configuration.
         *
         * @param configDir Directory for configuration and storage
         * @param enableTransport Whether to enable transport layer (routing)
         * @return The Reticulum instance
         */
        fun start(
            configDir: String? = null,
            enableTransport: Boolean = false
        ): Reticulum {
            if (started.compareAndSet(false, true)) {
                val dir = configDir ?: getDefaultConfigDir()
                val rns = Reticulum(dir, enableTransport)
                instance = rns
                rns.initialize()
                return rns
            }
            return instance!!
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
            println("[Reticulum] $message")
        }
    }

    // Storage paths
    val storagePath: String = "$configDir/storage"
    val cachePath: String = "$configDir/cache"
    val identityPath: String = "$configDir/identities"

    // State
    private val interfaces = mutableListOf<Any>()
    private val shutdownHooks = mutableListOf<() -> Unit>()

    /**
     * Initialize the Reticulum instance.
     */
    private fun initialize() {
        log("Initializing Reticulum...")

        // Ensure directories exist
        ensureDirectories()

        // Start Transport
        Transport.start(enableTransport = enableTransport)

        log("Reticulum started (transport=${if (enableTransport) "enabled" else "disabled"})")
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
    fun getPathTable(): Map<String, Int> {
        return Transport.pathTable.mapKeys { it.key.toString() }
            .mapValues { it.value.hops }
    }

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

        // Run shutdown hooks
        shutdownHooks.forEach { hook ->
            try {
                hook()
            } catch (e: Exception) {
                log("Error in shutdown hook: ${e.message}")
            }
        }
        shutdownHooks.clear()

        // Detach interfaces
        interfaces.clear()

        // Stop Transport
        Transport.stop()

        log("Reticulum stopped")
    }
}
