package network.reticulum.cli.config

/**
 * Configuration data classes matching Python RNS config structure.
 *
 * Configuration is read from ~/.reticulum/config (ConfigObj/INI format).
 */

/**
 * Top-level configuration container.
 */
data class ReticulumConfig(
    val reticulum: ReticulumSection = ReticulumSection(),
    val logging: LoggingSection = LoggingSection(),
    val interfaces: Map<String, InterfaceConfig> = emptyMap()
)

/**
 * [reticulum] section configuration.
 */
data class ReticulumSection(
    val enableTransport: Boolean = false,
    val shareInstance: Boolean = true,
    val instanceName: String = "default",
    val sharedInstancePort: Int = 37428,
    val instanceControlPort: Int = 37429,
    val sharedInstanceType: String? = null,
    val panicOnInterfaceError: Boolean = false,
    val enableRemoteManagement: Boolean = false,
    val remoteManagementAllowed: List<String> = emptyList(),
    val respondToProbes: Boolean = false,
    val linkMtuDiscovery: Boolean = true
)

/**
 * [logging] section configuration.
 */
data class LoggingSection(
    val loglevel: Int = 4  // LOG_INFO is default
)

/**
 * Interface configuration from [[interface_name]] subsection.
 */
data class InterfaceConfig(
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val options: Map<String, Any> = emptyMap()
) {
    // Common interface options
    val targetHost: String? get() = options["target_host"] as? String
    val targetPort: Int? get() = (options["target_port"] as? Number)?.toInt()
    val listenIp: String? get() = options["listen_ip"] as? String
    val listenPort: Int? get() = (options["listen_port"] as? Number)?.toInt()
    val mode: Int? get() = (options["selected_interface_mode"] as? Number)?.toInt()
    val bitrate: Int? get() = (options["configured_bitrate"] as? Number)?.toInt()

    // AutoInterface options
    val groupId: String? get() = options["group_id"] as? String
    val discoveryPort: Int? get() = (options["discovery_port"] as? Number)?.toInt()
    val dataPort: Int? get() = (options["data_port"] as? Number)?.toInt()
    val discoveryScope: String? get() = options["discovery_scope"] as? String

    @Suppress("UNCHECKED_CAST")
    val devices: List<String>? get() = when (val d = options["devices"]) {
        is List<*> -> d as? List<String>
        is String -> d.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    val ignoredDevices: List<String>? get() = when (val d = options["ignored_devices"]) {
        is List<*> -> d as? List<String>
        is String -> d.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> null
    }
}

/**
 * Supported interface types.
 */
enum class InterfaceType(val configName: String) {
    TCP_CLIENT("TCPClientInterface"),
    TCP_SERVER("TCPServerInterface"),
    UDP("UDPInterface"),
    AUTO("AutoInterface"),
    RNODE("RNodeInterface"),
    KISS("KISSInterface"),
    AX25_KISS("AX25KISSInterface"),
    I2P("I2PInterface"),
    BLE("BLEInterface"),
    UNKNOWN("Unknown");

    companion object {
        fun fromConfigName(name: String): InterfaceType {
            return entries.find { it.configName.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
