package network.reticulum.interop

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Base class for interoperability tests with Python RNS.
 *
 * Provides a shared Python bridge instance and utility methods
 * for comparing byte arrays.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class InteropTestBase {

    protected lateinit var bridge: PythonBridge

    @BeforeAll
    fun setupBridge() {
        // Find the Python RNS path
        val rnsPath = System.getenv("PYTHON_RNS_PATH")
            ?: findReticulumPath()
            ?: throw IllegalStateException(
                "Cannot find Reticulum. Set PYTHON_RNS_PATH environment variable."
            )

        bridge = PythonBridge.start(rnsPath)
    }

    @AfterAll
    fun teardownBridge() {
        if (::bridge.isInitialized) {
            bridge.close()
        }
    }

    /**
     * Execute a Python command and return the result.
     */
    protected fun python(command: String, vararg params: Pair<String, Any?>): JsonObject {
        val paramMap = params.mapNotNull { (key, value) ->
            when (value) {
                null -> null  // Skip null values
                is ByteArray -> key to value.toHex()
                is List<*> -> key to value  // Keep lists as-is
                is Map<*, *> -> key to value  // Keep maps as-is for JSON serialization
                is String -> key to value
                is Int -> key to value
                is Long -> key to value
                is Double -> key to value
                is Float -> key to value
                is Boolean -> key to value
                else -> key to value.toString()
            }
        }.toMap()
        return bridge.executeSuccess(command, paramMap)
    }

    /**
     * Assert that two byte arrays are equal, with detailed diff on failure.
     */
    protected fun assertBytesEqual(expected: ByteArray, actual: ByteArray, message: String = "") {
        if (!expected.contentEquals(actual)) {
            val diff = buildDiffMessage(expected, actual, message)
            throw AssertionError(diff)
        }
    }

    /**
     * Build a detailed diff message for byte array comparison.
     */
    private fun buildDiffMessage(expected: ByteArray, actual: ByteArray, message: String): String {
        return buildString {
            if (message.isNotEmpty()) {
                appendLine("Byte mismatch: $message")
            } else {
                appendLine("Byte mismatch")
            }
            appendLine("Expected (${expected.size} bytes): ${expected.toHex()}")
            appendLine("Actual   (${actual.size} bytes): ${actual.toHex()}")

            val diffs = mutableListOf<String>()
            val maxLen = maxOf(expected.size, actual.size)

            for (i in 0 until maxLen) {
                val exp = expected.getOrNull(i)
                val act = actual.getOrNull(i)
                if (exp != act) {
                    val expStr = exp?.let { "%02x".format(it) } ?: "??"
                    val actStr = act?.let { "%02x".format(it) } ?: "??"
                    diffs.add("[$i] $expStr != $actStr")
                    if (diffs.size >= 10) {
                        diffs.add("... (${maxLen - i - 1} more differences)")
                        break
                    }
                }
            }

            if (diffs.isNotEmpty()) {
                appendLine("Differences:")
                diffs.forEach { appendLine("  $it") }
            }
        }
    }

    /**
     * Find the Reticulum repository path.
     */
    private fun findReticulumPath(): String? {
        val userHome = System.getProperty("user.home")

        // Try common locations (including absolute paths)
        val candidates = listOf(
            // Absolute paths first (most reliable)
            File(userHome, "repos/Reticulum"),
            File(userHome, "repos/public/Reticulum"),
            // Relative paths from project
            File("../../../Reticulum"),
            File("../../Reticulum"),
            File("../Reticulum")
        )

        return candidates.find { it.exists() && File(it, "RNS").exists() }?.absolutePath
    }

    /**
     * Find the project root directory.
     */
    private fun findProjectRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists() ||
                File(dir, "python-bridge").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").absoluteFile
    }
}
