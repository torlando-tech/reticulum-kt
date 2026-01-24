package network.reticulum.interop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridge to Python RNS implementation for interop testing.
 *
 * Communicates with bridge_server.py via JSON over stdin/stdout.
 */
class PythonBridge private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader
) : AutoCloseable {

    private val requestId = AtomicInteger(0)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        /**
         * Start a new Python bridge.
         *
         * @param pythonRnsPath Path to the Reticulum repository
         * @return Running Python bridge
         */
        fun start(pythonRnsPath: String = System.getenv("PYTHON_RNS_PATH") ?: "../../../Reticulum"): PythonBridge {
            val bridgeScript = findBridgeScript()
            val projectRoot = bridgeScript.parentFile.parentFile

            val processBuilder = ProcessBuilder("python3", bridgeScript.absolutePath)
                .directory(projectRoot)
                .redirectErrorStream(false)

            processBuilder.environment()["PYTHON_RNS_PATH"] = pythonRnsPath

            val process = processBuilder.start()
            val writer = process.outputStream.bufferedWriter()
            val reader = process.inputStream.bufferedReader()

            // Wait for READY signal with timeout
            val ready = reader.readLine()
            if (ready != "READY") {
                val stderr = process.errorStream.bufferedReader().readText()
                process.destroy()
                throw IllegalStateException(
                    "Python bridge failed to start. Got: $ready\nStderr: $stderr"
                )
            }

            return PythonBridge(process, writer, reader)
        }

        private fun findBridgeScript(): File {
            // Try various paths to find the bridge script
            val candidates = listOf(
                // From project root
                File("python-bridge/bridge_server.py"),
                // From rns-test module
                File("../python-bridge/bridge_server.py"),
                // Absolute path based on this class's location
                File(System.getProperty("user.dir"), "python-bridge/bridge_server.py"),
                File(System.getProperty("user.dir"), "../python-bridge/bridge_server.py")
            )

            // Also check by walking up from current directory
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                val candidate = File(dir, "python-bridge/bridge_server.py")
                if (candidate.exists()) {
                    return candidate
                }
                dir = dir.parentFile
            }

            return candidates.find { it.exists() }
                ?: throw IllegalStateException(
                    "Bridge script not found. Tried:\n${candidates.joinToString("\n") { "  ${it.absolutePath}" }}\n" +
                    "Current dir: ${File(".").absolutePath}"
                )
        }
    }

    /**
     * Execute a command on the Python bridge.
     *
     * @param command Command name
     * @param params Command parameters (values should be hex-encoded for byte arrays)
     * @return Response from Python
     */
    fun execute(command: String, params: Map<String, Any> = emptyMap()): BridgeResponse {
        val id = "req-${requestId.incrementAndGet()}"

        val request = buildString {
            append("{\"id\":\"$id\",\"command\":\"$command\",\"params\":{")
            params.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is String -> append("\"$value\"")
                    is List<*> -> {
                        append("[")
                        value.forEachIndexed { i, item ->
                            if (i > 0) append(",")
                            append("\"$item\"")
                        }
                        append("]")
                    }
                    is Map<*, *> -> {
                        append("{")
                        value.entries.forEachIndexed { i, (k, v) ->
                            if (i > 0) append(",")
                            append("\"$k\":")
                            when (v) {
                                is String -> append("\"$v\"")
                                is Number -> append(v)
                                is Boolean -> append(v)
                                else -> append("\"$v\"")
                            }
                        }
                        append("}")
                    }
                    is Number -> append(value)
                    is Boolean -> append(value)
                    else -> append("\"$value\"")
                }
            }
            append("}}")
        }

        synchronized(this) {
            writer.write(request)
            writer.newLine()
            writer.flush()

            val responseLine = reader.readLine()
                ?: throw IllegalStateException("Python bridge closed unexpectedly")

            val responseJson = json.parseToJsonElement(responseLine).jsonObject
            val responseId = responseJson["id"]?.jsonPrimitive?.content ?: "unknown"
            val success = responseJson["success"]?.jsonPrimitive?.boolean ?: false

            return if (success) {
                val result = responseJson["result"]?.jsonObject ?: JsonObject(emptyMap())
                BridgeResponse.Success(responseId, result)
            } else {
                val error = responseJson["error"]?.jsonPrimitive?.content ?: "Unknown error"
                val traceback = responseJson["traceback"]?.jsonPrimitive?.content
                BridgeResponse.Error(responseId, error, traceback)
            }
        }
    }

    /**
     * Execute a command and expect success.
     *
     * @throws AssertionError if the command fails
     */
    fun executeSuccess(command: String, params: Map<String, Any> = emptyMap()): JsonObject {
        val response = execute(command, params)
        return when (response) {
            is BridgeResponse.Success -> response.result
            is BridgeResponse.Error -> throw AssertionError(
                "Python command '$command' failed: ${response.error}\n${response.traceback ?: ""}"
            )
        }
    }

    override fun close() {
        try {
            writer.close()
            reader.close()
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

/**
 * Response from Python bridge.
 */
sealed class BridgeResponse {
    abstract val id: String

    data class Success(
        override val id: String,
        val result: JsonObject
    ) : BridgeResponse()

    data class Error(
        override val id: String,
        val error: String,
        val traceback: String?
    ) : BridgeResponse()
}

/**
 * Extension to get a string field from JsonObject.
 */
fun JsonObject.getString(key: String): String =
    this[key]?.jsonPrimitive?.content
        ?: throw IllegalStateException("Missing field: $key")

/**
 * Extension to get a boolean field from JsonObject.
 */
fun JsonObject.getBoolean(key: String): Boolean =
    this[key]?.jsonPrimitive?.boolean
        ?: throw IllegalStateException("Missing field: $key")

/**
 * Extension to get an int field from JsonObject.
 */
fun JsonObject.getInt(key: String): Int =
    this[key]?.jsonPrimitive?.int
        ?: throw IllegalStateException("Missing field: $key")

/**
 * Extension to get a double field from JsonObject.
 */
fun JsonObject.getDouble(key: String): Double =
    this[key]?.jsonPrimitive?.content?.toDouble()
        ?: throw IllegalStateException("Missing field: $key")

/**
 * Extension to get a float field from JsonObject.
 */
fun JsonObject.getFloat(key: String): Float =
    this[key]?.jsonPrimitive?.content?.toFloat()
        ?: throw IllegalStateException("Missing field: $key")

/**
 * Extension to get a list field from JsonObject.
 */
inline fun <reified T> JsonObject.getList(key: String): List<T> {
    val jsonArray = this[key]?.let { element ->
        if (element is kotlinx.serialization.json.JsonArray) element
        else throw IllegalStateException("Field $key is not an array")
    } ?: throw IllegalStateException("Missing field: $key")

    return jsonArray.map { element ->
        when (T::class) {
            String::class -> element.jsonPrimitive.content as T
            Int::class -> element.jsonPrimitive.int as T
            Double::class -> element.jsonPrimitive.content.toDouble() as T
            Boolean::class -> element.jsonPrimitive.boolean as T
            else -> throw IllegalStateException("Unsupported list element type: ${T::class}")
        }
    }
}

/**
 * Extension to get a hex-encoded byte array from JsonObject.
 */
fun JsonObject.getBytes(key: String): ByteArray =
    getString(key).hexToByteArray()

/**
 * Convert hex string to ByteArray.
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

/**
 * Compute truncated hash (first 16 bytes of SHA-256).
 */
fun truncatedHash(data: ByteArray): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val fullHash = digest.digest(data)
    return fullHash.copyOfRange(0, 16)
}

/**
 * Convert ByteArray to hex string.
 */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
