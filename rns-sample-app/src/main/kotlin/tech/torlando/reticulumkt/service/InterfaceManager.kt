package tech.torlando.reticulumkt.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.transport.Transport
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
import tech.torlando.reticulumkt.ui.screens.InterfaceType
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages running network interfaces and handles hot-reload when configuration changes.
 *
 * Unlike Python RNS which requires a process restart to change interfaces,
 * this manager observes configuration changes and dynamically starts/stops
 * interfaces as needed.
 */
class InterfaceManager(
    private val scope: CoroutineScope,
    private val interfacesFlow: Flow<List<StoredInterfaceConfig>>,
) {
    companion object {
        private const val TAG = "InterfaceManager"
    }

    /** Running interfaces keyed by config ID. */
    private val runningInterfaces = ConcurrentHashMap<String, Interface>()

    /** Job for observing interface configuration changes. */
    private var observeJob: Job? = null

    /**
     * Start observing interface configuration changes.
     * Call this after Reticulum is initialized.
     */
    fun startObserving() {
        Log.i(TAG, "Starting interface observation")
        observeJob?.cancel()
        observeJob = scope.launch {
            interfacesFlow.collect { configs ->
                Log.d(TAG, "Received ${configs.size} interface configs")
                syncInterfaces(configs)
            }
        }
    }

    /**
     * Stop observing and shut down all managed interfaces.
     */
    fun stopAll() {
        observeJob?.cancel()
        observeJob = null

        runningInterfaces.forEach { (id, iface) ->
            stopInterface(id, iface)
        }
        runningInterfaces.clear()
    }

    /**
     * Synchronize running interfaces with the given configuration.
     * Starts new interfaces, stops removed ones, and leaves unchanged ones running.
     */
    private fun syncInterfaces(configs: List<StoredInterfaceConfig>) {
        val configIds = configs.filter { it.enabled }.map { it.id }.toSet()
        val runningIds = runningInterfaces.keys.toSet()

        // Find interfaces to stop (in running but not in config, or disabled)
        val toStop = runningIds - configIds
        for (id in toStop) {
            runningInterfaces.remove(id)?.let { iface ->
                Log.i(TAG, "Stopping removed interface: ${iface.name}")
                stopInterface(id, iface)
            }
        }

        // Find interfaces to start (in config but not running)
        val toStart = configIds - runningIds
        Log.d(TAG, "Interfaces to start: $toStart")
        for (id in toStart) {
            val config = configs.find { it.id == id } ?: continue
            startInterface(config)?.let { iface ->
                runningInterfaces[id] = iface
                Log.i(TAG, "Started new interface: ${iface.name} (id=$id, online=${iface.online.get()})")
            }
        }
        Log.d(TAG, "Running interfaces: ${runningInterfaces.keys.toList()}")

        // Check for configuration changes on existing interfaces
        for (id in configIds.intersect(runningIds)) {
            val config = configs.find { it.id == id } ?: continue
            val running = runningInterfaces[id] ?: continue

            // For now, if config changed, restart the interface
            // A more sophisticated approach would check specific fields
            if (hasConfigChanged(config, running)) {
                Log.i(TAG, "Interface config changed, restarting: ${config.name}")
                stopInterface(id, running)
                startInterface(config)?.let { newIface ->
                    runningInterfaces[id] = newIface
                }
            }
        }
    }

    /**
     * Check if interface config has changed from what's running.
     */
    private fun hasConfigChanged(config: StoredInterfaceConfig, running: Interface): Boolean {
        // Check name change
        if (config.name != running.name) return true

        // Check type-specific parameters
        when (running) {
            is TCPClientInterface -> {
                // We can't easily check host/port on a running interface,
                // so for TCP we'd need to store the original config.
                // For now, assume unchanged if same name.
                return false
            }
            else -> return false
        }
    }

    /**
     * Start an interface from configuration.
     */
    private fun startInterface(config: StoredInterfaceConfig): Interface? {
        val type = try {
            InterfaceType.valueOf(config.type)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown interface type: ${config.type}")
            return null
        }

        return when (type) {
            InterfaceType.TCP_CLIENT -> {
                val host = config.host ?: return null
                val port = config.port ?: 4242

                try {
                    val iface = TCPClientInterface(
                        name = config.name,
                        targetHost = host,
                        targetPort = port,
                        ifacNetname = config.networkName,
                        ifacNetkey = config.passphrase,
                        parentScope = scope,  // Wire service scope for lifecycle-aware cancellation
                    )

                    // Set up packet callback to forward to Transport
                    iface.onPacketReceived = { data, fromInterface ->
                        Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
                    }

                    // Start and register
                    iface.start()
                    Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))

                    val ifacInfo = if (config.networkName != null || config.passphrase != null) " (IFAC enabled)" else ""
                    Log.i(TAG, "Started TCP interface: ${config.name} -> $host:$port$ifacInfo")
                    iface
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start TCP interface ${config.name}: ${e.message}")
                    null
                }
            }

            InterfaceType.AUTO -> {
                try {
                    // Use config fields, with defaults matching Python RNS
                    val groupIdString = config.groupId ?: "reticulum"
                    val groupId = groupIdString.toByteArray(Charsets.UTF_8)
                    val discoveryPort = config.discoveryPort ?: 29716

                    val iface = AutoInterface(
                        name = config.name,
                        groupId = groupId,
                        discoveryScope = "2", // Link-local (default)
                        discoveryPort = discoveryPort,
                        dataPort = 42671, // Default data port
                        allowedDevices = null, // Use all available interfaces
                        ignoredDevices = emptyList()
                    )

                    // Set up packet callback to forward to Transport
                    iface.onPacketReceived = { data, fromInterface ->
                        Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
                    }

                    // Start and register
                    iface.start()
                    Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))

                    Log.i(TAG, "Created AutoInterface: ${config.name}")
                    iface
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create AutoInterface: ${e.message}", e)
                    null
                }
            }

            InterfaceType.BLE -> {
                // BLE interface would require Android Bluetooth APIs
                // Not implemented yet
                Log.w(TAG, "BLE interface not yet implemented")
                null
            }

            InterfaceType.RNODE -> {
                // RNode interface would require serial/BLE communication
                // Not implemented yet
                Log.w(TAG, "RNode interface not yet implemented")
                null
            }

            InterfaceType.UDP -> {
                // UDP interface - could implement if needed
                Log.w(TAG, "UDP interface not yet implemented")
                null
            }
        }
    }

    /**
     * Stop and deregister an interface.
     *
     * Note: With parentScope wiring (11-04), explicit stop calls become a backup path -
     * the primary cleanup happens automatically when the service scope is cancelled.
     */
    private fun stopInterface(id: String, iface: Interface) {
        try {
            // Deregister from Transport first
            Transport.deregisterInterface(InterfaceAdapter.getOrCreate(iface))

            // Use stop() if available (TCPClientInterface) for explicit lifecycle API,
            // or fall back to detach() for other interface types
            when (iface) {
                is TCPClientInterface -> iface.stop()
                // Add UDPInterface case when used in production
                else -> iface.detach()
            }

            Log.i(TAG, "Stopped interface: ${iface.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping interface ${iface.name}: ${e.message}")
        }
    }

    /**
     * Get the count of currently running interfaces.
     */
    fun getRunningCount(): Int = runningInterfaces.size

    /**
     * Get the count of online (connected) interfaces.
     */
    fun getOnlineCount(): Int = runningInterfaces.values.count { it.online.get() }

    /**
     * Get names of running interfaces.
     */
    fun getRunningNames(): List<String> = runningInterfaces.values.map { it.name }

    /**
     * Get status of all running interfaces.
     */
    fun getInterfaceStatuses(): Map<String, InterfaceStatus> {
        return runningInterfaces.mapValues { (_, iface) ->
            InterfaceStatus(
                name = iface.name,
                isOnline = iface.online.get(),
                isDetached = iface.detached.get(),
                rxBytes = iface.rxBytes.get(),
                txBytes = iface.txBytes.get(),
            )
        }
    }

    /**
     * Check if a specific interface (by config ID) is online.
     */
    fun isInterfaceOnline(configId: String): Boolean {
        return runningInterfaces[configId]?.online?.get() ?: false
    }
}

/**
 * Status snapshot of a running interface.
 */
data class InterfaceStatus(
    val name: String,
    val isOnline: Boolean,
    val isDetached: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
)
