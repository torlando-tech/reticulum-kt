package tech.torlando.reticulumkt.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.android.ReticulumConfig
import network.reticulum.android.ReticulumService
import tech.torlando.reticulumkt.data.PreferencesManager
import tech.torlando.reticulumkt.data.StoredInterfaceConfig
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

            // Check status after a delay
            kotlinx.coroutines.delay(2000)
            updateServiceStatus()
            updateMonitorState()
            addLogEntry("Service", "TX", "System", "ServiceStart", 0)
        }
    }

    fun stopService() {
        viewModelScope.launch {
            val context = getApplication<Application>()

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

                // Update service state with actual values
                val pathCount = network.reticulum.transport.Transport.pathTable.size
                val linkCount = network.reticulum.transport.Transport.linkTable.size
                val interfaceCount = network.reticulum.transport.Transport.getInterfaces().size

                _serviceState.value = _serviceState.value.copy(
                    activeInterfaces = interfaceCount,
                    knownPaths = pathCount,
                    activeLinks = linkCount,
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
        viewModelScope.launch { updateMonitorState() }
    }

    private fun updateMonitorState() {
        val runtime = Runtime.getRuntime()
        _monitorState.value = _monitorState.value.copy(
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
        System.gc()
        updateMonitorState()
    }

    fun forceGc() {
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
