package network.reticulum.cli.logging

/**
 * Log levels matching Python RNS exactly.
 *
 * Python RNS log levels (from RNS/__init__.py):
 * - 0: LOG_CRITICAL
 * - 1: LOG_ERROR
 * - 2: LOG_WARNING
 * - 3: LOG_NOTICE (default)
 * - 4: LOG_INFO
 * - 5: LOG_VERBOSE
 * - 6: LOG_DEBUG
 * - 7: LOG_EXTREME
 */
enum class LogLevel(val value: Int, val label: String) {
    CRITICAL(0, "Critical"),
    ERROR(1, "Error"),
    WARNING(2, "Warning"),
    NOTICE(3, "Notice"),
    INFO(4, "Info"),
    VERBOSE(5, "Verbose"),
    DEBUG(6, "Debug"),
    EXTREME(7, "Extra");

    companion object {
        fun fromValue(value: Int): LogLevel {
            return entries.find { it.value == value } ?: NOTICE
        }
    }
}

enum class LogDestination {
    STDOUT,
    FILE
}
