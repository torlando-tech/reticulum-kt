package tech.torlando.reticulumkt.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.android.ReticulumConfig
import network.reticulum.android.ReticulumService
import tech.torlando.reticulumkt.data.PreferencesManager
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
import tech.torlando.reticulumkt.service.InterfaceManager
import tech.torlando.reticulumkt.service.InterfaceStatus
import tech.torlando.reticulumkt.ui.screens.BatteryMode
import tech.torlando.reticulumkt.ui.screens.DarkModeOption
import tech.torlando.reticulumkt.ui.theme.PresetTheme
import java.util.UUID

data class ServiceState(
    val isRunning: Boolean = false,
    val enableTransport: Boolean = false,
    val activeInterfaces: Int = 0,
    val knownPeers: Int = 0,
    val activeLinks: Int = 0,
    val knownPaths: Int = 0,
)

data class MonitorState(
    val networkAvailable: Boolean = true,
    val connectionType: String = "WiFi",
    val isMetered: Boolean = false,
    val wifiSsid: String = "Unknown",
    val wifiSignalStrength: String = "Unknown",
    val wifiLinkSpeed: String = "Unknown",
    val cellularStatus: String = "Not connected",
    val cellularType: String = "Unknown",
    val cellularSignal: String = "Unknown",
    val batteryLevel: Int = 100,
    val batteryStatus: String = "Unknown",
    val powerSaveMode: Boolean = false,
    val inDoze: Boolean = false,
    val batteryExempt: Boolean = false,
    val heapUsedMb: Long = 0,
    val heapMaxMb: Long = 0,
    val byteArrayPoolMb: Float = 0f,
)

data class PacketLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val direction: String,
    val interfaceName: String,
    val packetType: String,
    val size: Int,
    val hexDump: String? = null,
)

sealed class SharedInstanceStatus {
    object Stopped : SharedInstanceStatus()
    object Starting : SharedInstanceStatus()
    data class Running(val clientCount: Int) : SharedInstanceStatus()
    data class ConnectedToExisting(val port: Int) : SharedInstanceStatus()
    data class Error(val message: String) : SharedInstanceStatus()
}

class ReticulumViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    // Interface manager for hot-reload support
    private var interfaceManager: InterfaceManager? = null

    // Periodic status refresh job
    private var statusRefreshJob: Job? = null

    // Actual interface statuses (from running interfaces, not config)
    private val _interfaceStatuses = MutableStateFlow<Map<String, InterfaceStatus>>(emptyMap())
    val interfaceStatuses: StateFlow<Map<String, InterfaceStatus>> = _interfaceStatuses.asStateFlow()

    // Service state
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    // Monitor state
    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    // Packet logs
    private val _packetLogs = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val packetLogs: StateFlow<List<PacketLogEntry>> = _packetLogs.asStateFlow()

    // Preferences as StateFlows
    val theme: StateFlow<PresetTheme> = preferencesManager.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, PresetTheme.VIBRANT)

    val darkMode: StateFlow<DarkModeOption> = preferencesManager.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, DarkModeOption.SYSTEM)

    val developerMode: StateFlow<Boolean> = preferencesManager.developerMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoStart: StateFlow<Boolean> = preferencesManager.autoStart
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showNotification: StateFlow<Boolean> = preferencesManager.showNotification
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val enableTransport: StateFlow<Boolean> = preferencesManager.enableTransport
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val batteryMode: StateFlow<BatteryMode> = preferencesManager.batteryMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, BatteryMode.BALANCED)

    val maxHashlistSize: StateFlow<Int> = preferencesManager.maxHashlistSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 50)

    val maxQueuedAnnounces: StateFlow<Int> = preferencesManager.maxQueuedAnnounces
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val interfaces: StateFlow<List<StoredInterfaceConfig>> = preferencesManager.interfaces
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val shareInstance: StateFlow<Boolean> = preferencesManager.shareInstance
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sharedInstancePort: StateFlow<Int> = preferencesManager.sharedInstancePort
        .stateIn(viewModelScope, SharingStarted.Eagerly, 37428)

    // Shared instance status
    private val _sharedInstanceStatus = MutableStateFlow<SharedInstanceStatus>(SharedInstanceStatus.Stopped)
    val sharedInstanceStatus: StateFlow<SharedInstanceStatus> = _sharedInstanceStatus.asStateFlow()

    // Service control
    fun startService() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val transport = enableTransport.value
            val share = shareInstance.value
            val port = sharedInstancePort.value
            val battery = batteryMode.value

            // Build config from current settings
            val config = ReticulumConfig(
                enableTransport = transport,
                shareInstance = share,
                sharedInstancePort = port,
                batteryOptimization = when (battery) {
                    BatteryMode.MAXIMUM_BATTERY -> ReticulumConfig.BatteryMode.MAXIMUM_BATTERY
                    BatteryMode.BALANCED -> ReticulumConfig.BatteryMode.BALANCED
                    BatteryMode.PERFORMANCE -> ReticulumConfig.BatteryMode.PERFORMANCE
                }
            )

            // Start the actual service
            ReticulumService.start(context, config)

            _serviceState.value = _serviceState.value.copy(isRunning = true, enableTransport = transport)
            _sharedInstanceStatus.value = SharedInstanceStatus.Starting

            // Wait for Reticulum to initialize
            kotlinx.coroutines.delay(2000)

            // Start interface manager for hot-reload support
            // This observes interface config changes and dynamically starts/stops interfaces
            interfaceManager = InterfaceManager(
                scope = viewModelScope,
                interfacesFlow = preferencesManager.interfaces,
            ).also { it.startObserving() }

            // Start periodic status refresh (every 2 seconds)
            statusRefreshJob = viewModelScope.launch {
                while (isActive) {
                    updateServiceStatus()
                    updateMonitorState()
                    delay(2000)
                }
            }

            addLogEntry("Service", "TX", "System", "ServiceStart", 0)
        }
    }

    fun stopService() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            // Stop periodic status refresh
            statusRefreshJob?.cancel()
            statusRefreshJob = null

            // Stop interface manager first (gracefully shuts down all interfaces)
            interfaceManager?.stopAll()
            interfaceManager = null

            // Clear interface statuses
            _interfaceStatuses.value = emptyMap()

            // Stop the actual service
            ReticulumService.stop(context)

            _serviceState.value = _serviceState.value.copy(
                isRunning = false,
                activeInterfaces = 0,
                knownPeers = 0,
                activeLinks = 0,
                knownPaths = 0,
            )
            _sharedInstanceStatus.value = SharedInstanceStatus.Stopped
            addLogEntry("Service", "TX", "System", "ServiceStop", 0)
        }
    }

    private fun updateServiceStatus() {
        try {
            if (Reticulum.isStarted()) {
                val rns = Reticulum.getInstance()
                val clientCount = rns.getSharedInstanceClientCount()

                _sharedInstanceStatus.value = when {
                    rns.isSharedInstance -> SharedInstanceStatus.Running(clientCount)
                    rns.isConnectedToSharedInstance -> SharedInstanceStatus.ConnectedToExisting(sharedInstancePort.value)
                    else -> SharedInstanceStatus.Running(0)
                }

                // Update service state with actual values from Transport
                val pathCount = network.reticulum.transport.Transport.pathTable.size
                val linkCount = network.reticulum.transport.Transport.linkTable.size
                val transportInterfaces = network.reticulum.transport.Transport.getInterfaces()

                // Get actual interface statuses from InterfaceManager
                val manager = interfaceManager
                val onlineCount = manager?.getOnlineCount() ?: 0
                val statuses = manager?.getInterfaceStatuses() ?: emptyMap()

                // Debug: log interface statuses
                if (statuses.isNotEmpty()) {
                    android.util.Log.d("ReticulumViewModel", "Interface statuses: ${statuses.map { "${it.key}=${it.value.isOnline}" }}")
                } else {
                    android.util.Log.d("ReticulumViewModel", "No interface statuses from InterfaceManager (manager=${manager != null}, running=${manager?.getRunningCount() ?: -1})")
                }

                _interfaceStatuses.value = statuses

                _serviceState.value = _serviceState.value.copy(
                    activeInterfaces = transportInterfaces.size,
                    knownPaths = pathCount,
                    activeLinks = linkCount,
                    // Update enableTransport from actual Reticulum state
                    enableTransport = rns.enableTransport,
                )
            }
        } catch (e: Exception) {
            _sharedInstanceStatus.value = SharedInstanceStatus.Error(e.message ?: "Unknown error")
        }
    }

    // Settings updates
    fun setTheme(theme: PresetTheme) {
        viewModelScope.launch { preferencesManager.setTheme(theme) }
    }

    fun setDarkMode(mode: DarkModeOption) {
        viewModelScope.launch { preferencesManager.setDarkMode(mode) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDeveloperMode(enabled) }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setAutoStart(enabled) }
    }

    fun setShowNotification(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShowNotification(enabled) }
    }

    fun setEnableTransport(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setEnableTransport(enabled) }
    }

    fun setBatteryMode(mode: BatteryMode) {
        viewModelScope.launch { preferencesManager.setBatteryMode(mode) }
    }

    fun setMaxHashlistSize(size: Int) {
        viewModelScope.launch { preferencesManager.setMaxHashlistSize(size) }
    }

    fun setMaxQueuedAnnounces(count: Int) {
        viewModelScope.launch { preferencesManager.setMaxQueuedAnnounces(count) }
    }

    fun setShareInstance(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShareInstance(enabled) }
    }

    fun setSharedInstancePort(port: Int) {
        viewModelScope.launch { preferencesManager.setSharedInstancePort(port) }
    }

    // Interface management
    fun addInterface(config: StoredInterfaceConfig) {
        viewModelScope.launch { preferencesManager.addInterface(config) }
    }

    fun removeInterface(id: String) {
        viewModelScope.launch { preferencesManager.removeInterface(id) }
    }

    fun updateInterface(config: StoredInterfaceConfig) {
        viewModelScope.launch { preferencesManager.updateInterface(config) }
    }

    // Monitor
    fun refreshMonitor() {
        viewModelScope.launch {
            updateServiceStatus()  // Refresh interface statuses from actual state
            updateMonitorState()
        }
    }

    @Suppress("DEPRECATION")
    private fun updateMonitorState() {
        val context = getApplication<Application>()
        val runtime = Runtime.getRuntime()

        // Get system services
        val connectivityManager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val wifiManager = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val telephonyManager = context.getSystemService(android.telephony.TelephonyManager::class.java)
        val batteryManager = context.getSystemService(android.os.BatteryManager::class.java)
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)

        // Network info
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        val networkAvailable = capabilities != null
        val isWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isMetered = connectivityManager?.isActiveNetworkMetered == true

        val connectionType = when {
            isWifi -> "WiFi"
            isCellular -> "Cellular"
            networkAvailable -> "Other"
            else -> "None"
        }

        // WiFi info (requires ACCESS_WIFI_STATE and location permissions)
        val (wifiSsid, wifiSignalStrength, wifiLinkSpeed) = try {
            val wifiInfo = wifiManager?.connectionInfo
            android.util.Log.i("ReticulumViewModel", "WiFi SSID raw: ${wifiInfo?.ssid}")
            android.util.Log.i("ReticulumViewModel", "WiFi RSSI: ${wifiInfo?.rssi}")
            android.util.Log.i("ReticulumViewModel", "WiFi Link Speed: ${wifiInfo?.linkSpeed}")

            val rawSsid = wifiInfo?.ssid?.removeSurrounding("\"")
            val ssid = when {
                rawSsid == null -> "Unknown"
                rawSsid == "<unknown ssid>" -> "Unknown"
                rawSsid.isBlank() -> "Unknown"
                else -> rawSsid
            }
            val rssi = wifiInfo?.rssi ?: -100
            val signalStrength = when {
                rssi >= -50 -> "Excellent ($rssi dBm)"
                rssi >= -60 -> "Good ($rssi dBm)"
                rssi >= -70 -> "Fair ($rssi dBm)"
                rssi >= -80 -> "Weak ($rssi dBm)"
                else -> "Very Weak ($rssi dBm)"
            }
            val linkSpeed = if (wifiInfo != null && wifiInfo.linkSpeed > 0) {
                "${wifiInfo.linkSpeed} Mbps"
            } else {
                "Unknown"
            }
            android.util.Log.i("ReticulumViewModel", "WiFi SSID processed: $ssid, signal: $signalStrength, speed: $linkSpeed")
            Triple(ssid, signalStrength, linkSpeed)
        } catch (e: SecurityException) {
            android.util.Log.e("ReticulumViewModel", "SecurityException reading WiFi info", e)
            Triple("Permission denied", "Permission denied", "Permission denied")
        } catch (e: Exception) {
            android.util.Log.e("ReticulumViewModel", "Error reading WiFi info", e)
            Triple("Error", "Error", "Error")
        }

        // Cellular info (requires READ_PHONE_STATE permission which we don't request)
        val (cellularStatus, cellularType, cellularSignal) = try {
            val status = if (isCellular) "Connected" else "Not connected"
            val type = when (telephonyManager?.dataNetworkType) {
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                else -> "Unknown"
            }
            val signal = "N/A" // Signal strength requires phone state permission
            Triple(status, type, signal)
        } catch (e: SecurityException) {
            // Don't have READ_PHONE_STATE permission - show minimal info
            val status = if (isCellular) "Connected" else "Not connected"
            Triple(status, "Permission required", "N/A")
        }

        // Battery info
        val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val batteryStatus = when (batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        val powerSaveMode = powerManager?.isPowerSaveMode == true

        // Doze info
        val inDoze = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager?.isDeviceIdleMode == true
        } else {
            false
        }
        val batteryExempt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        _monitorState.value = _monitorState.value.copy(
            networkAvailable = networkAvailable,
            connectionType = connectionType,
            isMetered = isMetered,
            wifiSsid = if (isWifi) wifiSsid else "Not connected",
            wifiSignalStrength = if (isWifi) wifiSignalStrength else "Not connected",
            wifiLinkSpeed = if (isWifi) wifiLinkSpeed else "Not connected",
            cellularStatus = cellularStatus,
            cellularType = cellularType,
            cellularSignal = cellularSignal,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus,
            powerSaveMode = powerSaveMode,
            inDoze = inDoze,
            batteryExempt = batteryExempt,
            heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            heapMaxMb = runtime.maxMemory() / 1024 / 1024,
        )
    }

    // Logging
    private fun addLogEntry(
        timestamp: String,
        direction: String,
        interfaceName: String,
        packetType: String,
        size: Int,
        hexDump: String? = null,
    ) {
        val entry = PacketLogEntry(
            timestamp = timestamp,
            direction = direction,
            interfaceName = interfaceName,
            packetType = packetType,
            size = size,
            hexDump = hexDump,
        )
        _packetLogs.value = (_packetLogs.value + entry).takeLast(500)
    }

    fun clearLogs() {
        _packetLogs.value = emptyList()
    }

    // Memory management
    fun trimMemory() {
        // Trim Reticulum's internal caches (path table, announce queues, etc.)
        if (Reticulum.isStarted()) {
            network.reticulum.transport.Transport.trimMemory()
        }
        System.gc()
        updateMonitorState()
    }

    fun forceGc() {
        // Aggressive cleanup of Reticulum's internal state
        if (Reticulum.isStarted()) {
            network.reticulum.transport.Transport.aggressiveTrimMemory()
        }
        System.gc()
        System.runFinalization()
        System.gc()
        updateMonitorState()
    }

    // Diagnostics
    fun getDiagnosticsText(): String {
        val runtime = Runtime.getRuntime()
        val state = _serviceState.value
        val monitor = _monitorState.value

        return buildString {
            appendLine("=== Reticulum Diagnostics ===")
            appendLine()
            appendLine("Service State:")
            appendLine("  Running: ${state.isRunning}")
            appendLine("  Transport: ${if (state.enableTransport) "Enabled" else "Disabled"}")
            appendLine("  Active Interfaces: ${state.activeInterfaces}")
            appendLine("  Known Peers: ${state.knownPeers}")
            appendLine("  Active Links: ${state.activeLinks}")
            appendLine("  Known Paths: ${state.knownPaths}")
            appendLine()
            appendLine("Memory:")
            appendLine("  Heap Used: ${monitor.heapUsedMb} MB")
            appendLine("  Heap Max: ${monitor.heapMaxMb} MB")
            appendLine()
            appendLine("Network:")
            appendLine("  Available: ${monitor.networkAvailable}")
            appendLine("  Type: ${monitor.connectionType}")
            appendLine()
            appendLine("Battery:")
            appendLine("  Level: ${monitor.batteryLevel}%")
            appendLine("  Status: ${monitor.batteryStatus}")
            appendLine("  Power Save: ${monitor.powerSaveMode}")
            appendLine()
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        }
    }
}
