package network.reticulum.cli.daemon

import network.reticulum.Reticulum
import network.reticulum.cli.RnsdCommand
import network.reticulum.cli.config.ConfigParser
import network.reticulum.cli.config.InterfaceConfigFactory
import network.reticulum.cli.config.ReticulumConfig
import network.reticulum.cli.logging.Logger
import network.reticulum.cli.logging.LogLevel
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Daemon runner that initializes and runs the Reticulum network stack.
 *
 * This mirrors Python rnsd's program_setup() behavior.
 */
class DaemonRunner(
    private val configDir: String?,
    private val verbosity: Int,
    private val quietness: Int,
    private val service: Boolean,
    private val interactive: Boolean
) {
    private val running = AtomicBoolean(true)
    private var localServerInterface: LocalServerInterface? = null
    private var rpcServer: RpcServer? = null

    /**
     * Run the daemon.
     */
    fun run() {
        try {
            // Calculate effective verbosity
            val targetVerbosity = verbosity - quietness

            // Configure logging
            if (service) {
                val effectiveConfigDir = configDir ?: getDefaultConfigDir()
                Logger.configureForService(effectiveConfigDir)
                // In service mode, log level is determined by config file
            } else {
                Logger.configureForStdout(targetVerbosity)
            }

            // Load configuration
            val config = loadConfig()

            // Apply config log level (may be overridden by CLI in non-service mode)
            if (service) {
                Logger.setLogLevel(config.logging.loglevel)
            }

            // Initialize Reticulum
            val reticulum = initializeReticulum(config)

            // Log startup
            Logger.notice("Started rnsd-kt version ${RnsdCommand.VERSION}")

            // Create and register interfaces from config
            setupInterfaces(config)

            // Set up shared instance if enabled
            setupSharedInstance(config)

            // Set up RPC server for Python client compatibility
            setupRpcServer(config)

            // Handle interactive mode
            if (interactive) {
                Logger.warning("Interactive mode is not yet supported in rnsd-kt")
            }

            // Install shutdown hook
            installShutdownHook()

            // Run daemon loop
            runDaemonLoop()

        } catch (e: Exception) {
            Logger.critical("Fatal error: ${e.message}")
            throw e
        } finally {
            shutdown()
        }
    }

    /**
     * Load configuration from the config directory.
     */
    private fun loadConfig(): ReticulumConfig {
        val effectiveConfigDir = configDir ?: getDefaultConfigDir()
        val configFile = File(effectiveConfigDir, "config")

        if (configFile.exists()) {
            Logger.verbose("Loading configuration from ${configFile.absolutePath}")
            return ConfigParser.parse(configFile)
        } else {
            Logger.verbose("No config file found at ${configFile.absolutePath}, using defaults")
            return ReticulumConfig()
        }
    }

    /**
     * Initialize the Reticulum instance with configuration.
     */
    private fun initializeReticulum(config: ReticulumConfig): Reticulum {
        Logger.verbose("Initializing Reticulum...")

        val reticulum = Reticulum.start(
            configDir = configDir,
            enableTransport = config.reticulum.enableTransport
        )

        Logger.verbose("Reticulum initialized (transport=${if (config.reticulum.enableTransport) "enabled" else "disabled"})")

        return reticulum
    }

    /**
     * Create and register interfaces from configuration.
     */
    private fun setupInterfaces(config: ReticulumConfig) {
        var count = 0

        for ((name, ifaceConfig) in config.interfaces) {
            if (!ifaceConfig.enabled) {
                Logger.debug("Skipping disabled interface: $name")
                continue
            }

            Logger.verbose("Creating interface: $name (${ifaceConfig.type})")

            val interfaceRef = InterfaceConfigFactory.createInterface(ifaceConfig)
            if (interfaceRef != null) {
                Transport.registerInterface(interfaceRef)
                count++
                Logger.notice("Started interface: $name")
            } else {
                Logger.warning("Failed to create interface: $name (${ifaceConfig.type})")
            }
        }

        if (count == 0) {
            Logger.warning("No interfaces configured or started")
        } else {
            Logger.info("Started $count interface(s)")
        }
    }

    /**
     * Set up the shared instance interface if enabled.
     * This creates a TCP server that Python RNS apps can connect to.
     *
     * Note: Python RNS prefers abstract Unix sockets on Linux, but Java's
     * standard UnixDomainSocketAddress doesn't support abstract sockets.
     * We use TCP port 37428 which Python RNS supports as fallback.
     *
     * To use with Python RNS, set shared_instance_type = tcp in config.
     */
    private fun setupSharedInstance(config: ReticulumConfig) {
        if (!config.reticulum.shareInstance) {
            Logger.debug("Shared instance disabled")
            return
        }

        val port = config.reticulum.sharedInstancePort

        Logger.verbose("Creating shared instance interface on TCP port $port")

        try {
            // Create the LocalServerInterface with TCP (Python RNS compatible)
            // Python RNS will connect via TCP if shared_instance_type = tcp
            localServerInterface = LocalServerInterface(
                name = "SharedInstance",
                tcpPort = port
            )

            // Wire up packet callback to Transport
            val ifaceRef = localServerInterface!!.toRef()
            localServerInterface!!.onPacketReceived = { data, _ ->
                Transport.inbound(data, ifaceRef)
            }

            // Start the interface
            localServerInterface!!.start()

            // Register with Transport for outbound packets
            Transport.registerInterface(ifaceRef)

            Logger.notice("Started shared instance on TCP port $port")
        } catch (e: Exception) {
            Logger.error("Failed to start shared instance: ${e.message}")
            // Don't fail startup - shared instance is optional
            localServerInterface = null
        }
    }

    /**
     * Set up the RPC server for Python client compatibility.
     *
     * Python RNS clients use an RPC interface to query interface statistics
     * and other daemon information. This server implements that protocol.
     */
    private fun setupRpcServer(config: ReticulumConfig) {
        if (!config.reticulum.shareInstance) {
            // RPC server only needed when sharing instance
            return
        }

        val port = config.reticulum.instanceControlPort

        Logger.verbose("Creating RPC server on TCP port $port")

        try {
            // Compute RPC auth key from Transport identity
            // This matches Python: full_hash(Transport.identity.get_private_key())
            val transportIdentity = Transport.identity
            if (transportIdentity == null) {
                Logger.warning("Transport identity not available, RPC server disabled")
                return
            }

            val privateKey = transportIdentity.getPrivateKey()
            if (privateKey == null) {
                Logger.warning("Transport identity has no private key, RPC server disabled")
                return
            }

            // Compute full hash of private key (SHA-256)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val rpcKey = digest.digest(privateKey)

            rpcServer = RpcServer(port, rpcKey)
            rpcServer!!.start()

            Logger.notice("Started RPC server on TCP port $port")
        } catch (e: Exception) {
            Logger.error("Failed to start RPC server: ${e.message}")
            // Don't fail startup - RPC is optional
            rpcServer = null
        }
    }

    /**
     * Install JVM shutdown hook for graceful shutdown.
     */
    private fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.notice("Shutdown signal received")
            running.set(false)
        })
    }

    /**
     * Main daemon loop - just sleep and let interfaces run.
     */
    private fun runDaemonLoop() {
        Logger.debug("Entering daemon loop")

        while (running.get()) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Logger.debug("Daemon loop interrupted")
                break
            }
        }

        Logger.debug("Exiting daemon loop")
    }

    /**
     * Shutdown Reticulum and cleanup.
     */
    private fun shutdown() {
        Logger.notice("Shutting down rnsd-kt...")

        // Stop RPC server
        rpcServer?.let {
            Logger.debug("Stopping RPC server...")
            it.stop()
            rpcServer = null
        }

        // Stop shared instance
        localServerInterface?.let {
            Logger.debug("Stopping shared instance...")
            it.detach()
            localServerInterface = null
        }

        Reticulum.stop()
        Logger.close()
    }

    /**
     * Get the default configuration directory.
     */
    private fun getDefaultConfigDir(): String {
        val userHome = System.getProperty("user.home")
        return "$userHome/.reticulum"
    }
}
