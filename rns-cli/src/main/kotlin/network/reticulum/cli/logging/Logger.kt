package network.reticulum.cli.logging

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Singleton logger matching Python RNS logging behavior.
 *
 * Log format: [YYYY-MM-DD HH:MM:SS] [Level   ] Message
 */
object Logger {
    var level: LogLevel = LogLevel.NOTICE
    var destination: LogDestination = LogDestination.STDOUT
    var logFile: File? = null

    private var fileWriter: PrintWriter? = null
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Padding for level labels to align output
    private val levelPadding = mapOf(
        LogLevel.CRITICAL to "[Critical]",
        LogLevel.ERROR to "[Error]   ",
        LogLevel.WARNING to "[Warning] ",
        LogLevel.NOTICE to "[Notice]  ",
        LogLevel.INFO to "[Info]    ",
        LogLevel.VERBOSE to "[Verbose] ",
        LogLevel.DEBUG to "[Debug]   ",
        LogLevel.EXTREME to "[Extra]   "
    )

    /**
     * Log a message at the specified level.
     * Message is only output if the current log level is >= the message level.
     */
    fun log(message: String, messageLevel: LogLevel = LogLevel.NOTICE) {
        if (level.value >= messageLevel.value) {
            val timestamp = LocalDateTime.now().format(timestampFormatter)
            val levelStr = levelPadding[messageLevel] ?: "[Unknown] "
            val logLine = "[$timestamp] $levelStr $message"

            when (destination) {
                LogDestination.STDOUT -> println(logLine)
                LogDestination.FILE -> writeToFile(logLine)
            }
        }
    }

    /**
     * Configure logging for service mode (log to file).
     */
    fun configureForService(configDir: String) {
        destination = LogDestination.FILE
        logFile = File(configDir, "logfile")
        openLogFile()
    }

    /**
     * Configure logging for normal mode (stdout).
     */
    fun configureForStdout(verbosityLevel: Int) {
        destination = LogDestination.STDOUT
        // Default is NOTICE (3), adjust by verbosity
        val adjustedLevel = (LogLevel.NOTICE.value + verbosityLevel).coerceIn(0, 7)
        level = LogLevel.fromValue(adjustedLevel)
    }

    /**
     * Configure log level from config file value.
     */
    fun setLogLevel(configLevel: Int) {
        level = LogLevel.fromValue(configLevel)
    }

    private fun openLogFile() {
        logFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                fileWriter = PrintWriter(FileWriter(file, true), true)
            } catch (e: Exception) {
                System.err.println("Failed to open log file: ${e.message}")
                destination = LogDestination.STDOUT
            }
        }
    }

    private fun writeToFile(line: String) {
        if (fileWriter == null) {
            openLogFile()
        }
        fileWriter?.println(line) ?: println(line)
    }

    /**
     * Close the log file if open.
     */
    fun close() {
        fileWriter?.close()
        fileWriter = null
    }

    // Convenience methods for common log levels
    fun critical(message: String) = log(message, LogLevel.CRITICAL)
    fun error(message: String) = log(message, LogLevel.ERROR)
    fun warning(message: String) = log(message, LogLevel.WARNING)
    fun notice(message: String) = log(message, LogLevel.NOTICE)
    fun info(message: String) = log(message, LogLevel.INFO)
    fun verbose(message: String) = log(message, LogLevel.VERBOSE)
    fun debug(message: String) = log(message, LogLevel.DEBUG)
    fun extreme(message: String) = log(message, LogLevel.EXTREME)
}
