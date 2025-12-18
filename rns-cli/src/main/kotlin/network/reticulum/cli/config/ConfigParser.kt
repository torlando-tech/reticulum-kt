package network.reticulum.cli.config

import network.reticulum.cli.logging.Logger
import java.io.File

/**
 * Parser for ConfigObj/INI format configuration files.
 *
 * Supports:
 * - [section] for top-level sections
 * - [[subsection]] for nested sections (used for interfaces)
 * - key = value pairs
 * - Boolean values: True/False, Yes/No, yes/no, true/false
 * - Comments starting with #
 * - Indentation is ignored
 */
object ConfigParser {

    /**
     * Parse a configuration file and return a ReticulumConfig.
     */
    fun parse(configFile: File): ReticulumConfig {
        if (!configFile.exists()) {
            Logger.debug("Config file not found: ${configFile.absolutePath}")
            return ReticulumConfig()
        }

        val lines = configFile.readLines()
        val sections = parseSections(lines)

        @Suppress("UNCHECKED_CAST")
        val reticulumData = (sections["reticulum"] as? Map<String, Any>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val loggingData = (sections["logging"] as? Map<String, Any>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val interfacesData = (sections["interfaces"] as? Map<String, Any>) ?: emptyMap()

        val reticulumSection = parseReticulumSection(reticulumData)
        val loggingSection = parseLoggingSection(loggingData)
        val interfaces = parseInterfacesSection(interfacesData)

        return ReticulumConfig(
            reticulum = reticulumSection,
            logging = loggingSection,
            interfaces = interfaces
        )
    }

    /**
     * Parse all sections from the config file lines.
     */
    private fun parseSections(lines: List<String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var currentSection: String? = null
        var currentSubsection: String? = null
        val currentSectionData = mutableMapOf<String, Any>()
        val currentSubsectionData = mutableMapOf<String, Any>()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            // Check for subsection [[name]]
            if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
                // Save previous subsection if any
                if (currentSubsection != null && currentSection != null) {
                    val sectionMap = (result.getOrPut(currentSection) { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
                    sectionMap[currentSubsection] = currentSubsectionData.toMap()
                }
                currentSubsection = trimmed.substring(2, trimmed.length - 2).trim()
                currentSubsectionData.clear()
                continue
            }

            // Check for section [name]
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                // Save previous section data
                if (currentSection != null) {
                    if (currentSubsection != null) {
                        val sectionMap = (result.getOrPut(currentSection) { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
                        sectionMap[currentSubsection] = currentSubsectionData.toMap()
                    }
                    if (currentSectionData.isNotEmpty()) {
                        val sectionMap = (result.getOrPut(currentSection) { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
                        sectionMap.putAll(currentSectionData)
                    }
                }
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                currentSubsection = null
                currentSectionData.clear()
                currentSubsectionData.clear()
                result.getOrPut(currentSection) { mutableMapOf<String, Any>() }
                continue
            }

            // Parse key = value
            val equalsIndex = trimmed.indexOf('=')
            if (equalsIndex > 0) {
                val key = trimmed.substring(0, equalsIndex).trim()
                val value = trimmed.substring(equalsIndex + 1).trim()
                val parsedValue = parseValue(value)

                if (currentSubsection != null) {
                    currentSubsectionData[key] = parsedValue
                } else if (currentSection != null) {
                    currentSectionData[key] = parsedValue
                }
            }
        }

        // Save final section/subsection
        if (currentSection != null) {
            val sectionMap = (result.getOrPut(currentSection) { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
            if (currentSubsection != null) {
                sectionMap[currentSubsection] = currentSubsectionData.toMap()
            }
            sectionMap.putAll(currentSectionData)
        }

        return result
    }

    /**
     * Parse a value string into the appropriate type.
     */
    private fun parseValue(value: String): Any {
        // Remove inline comments
        val cleanValue = value.split("#").first().trim()

        // Boolean values
        if (cleanValue.equals("true", ignoreCase = true) ||
            cleanValue.equals("yes", ignoreCase = true)) {
            return true
        }
        if (cleanValue.equals("false", ignoreCase = true) ||
            cleanValue.equals("no", ignoreCase = true)) {
            return false
        }

        // None/null
        if (cleanValue.equals("none", ignoreCase = true) ||
            cleanValue.equals("null", ignoreCase = true)) {
            return "None"
        }

        // Integer
        cleanValue.toIntOrNull()?.let { return it }

        // Float
        cleanValue.toDoubleOrNull()?.let { return it }

        // String (strip quotes if present)
        return if ((cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) ||
            (cleanValue.startsWith("'") && cleanValue.endsWith("'"))) {
            cleanValue.substring(1, cleanValue.length - 1)
        } else {
            cleanValue
        }
    }

    /**
     * Parse [reticulum] section into ReticulumSection.
     */
    private fun parseReticulumSection(data: Map<String, Any>): ReticulumSection {
        return ReticulumSection(
            enableTransport = data["enable_transport"] as? Boolean ?: false,
            shareInstance = data["share_instance"] as? Boolean ?: true,
            instanceName = data["instance_name"] as? String ?: "default",
            sharedInstancePort = (data["shared_instance_port"] as? Number)?.toInt() ?: 37428,
            instanceControlPort = (data["instance_control_port"] as? Number)?.toInt() ?: 37429,
            sharedInstanceType = data["shared_instance_type"] as? String,
            panicOnInterfaceError = data["panic_on_interface_error"] as? Boolean ?: false,
            enableRemoteManagement = data["enable_remote_management"] as? Boolean ?: false,
            remoteManagementAllowed = parseStringList(data["remote_management_allowed"]),
            respondToProbes = data["respond_to_probes"] as? Boolean ?: false,
            linkMtuDiscovery = data["link_mtu_discovery"] as? Boolean ?: true
        )
    }

    /**
     * Parse [logging] section into LoggingSection.
     */
    private fun parseLoggingSection(data: Map<String, Any>): LoggingSection {
        return LoggingSection(
            loglevel = (data["loglevel"] as? Number)?.toInt() ?: 4
        )
    }

    /**
     * Parse [interfaces] section into map of interface configs.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseInterfacesSection(data: Map<String, Any>): Map<String, InterfaceConfig> {
        val interfaces = mutableMapOf<String, InterfaceConfig>()

        for ((name, value) in data) {
            // Each interface is a subsection (Map)
            if (value is Map<*, *>) {
                val options = value as Map<String, Any>
                val type = options["type"] as? String ?: continue

                // Check enabled state - can be "enabled" or "interface_enabled"
                val enabled = (options["enabled"] as? Boolean)
                    ?: (options["interface_enabled"] as? Boolean)
                    ?: true

                // Filter out type, enabled, name from options (they're already extracted)
                val filteredOptions = options.filterKeys {
                    it !in listOf("type", "enabled", "interface_enabled", "name")
                }

                interfaces[name] = InterfaceConfig(
                    name = options["name"] as? String ?: name,
                    type = type,
                    enabled = enabled,
                    options = filteredOptions
                )
            }
        }

        return interfaces
    }

    /**
     * Parse a comma-separated string list.
     */
    private fun parseStringList(value: Any?): List<String> {
        return when (value) {
            is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }
}
