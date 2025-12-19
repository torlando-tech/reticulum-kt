package network.reticulum.cli.daemon

import net.razorvine.pickle.Pickler
import net.razorvine.pickle.Unpickler
import network.reticulum.cli.logging.Logger
import network.reticulum.transport.Transport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * RPC server for Python RNS client compatibility.
 *
 * Implements the Python multiprocessing.connection protocol to allow
 * Python RNS clients to query interface stats and other daemon information.
 *
 * Protocol:
 * 1. Server sends challenge: "#CHALLENGE#" + "{sha256}" + 20 random bytes
 * 2. Client responds with HMAC-SHA256 of the challenge using authkey
 * 3. Server verifies and sends "#WELCOME#" or "#FAILURE#"
 * 4. Messages are length-prefixed (4 bytes big-endian) + pickle data
 */
class RpcServer(
    private val port: Int = DEFAULT_PORT,
    private val authKey: ByteArray
) {
    companion object {
        const val DEFAULT_PORT = 37429

        // Python multiprocessing.connection protocol constants
        private val CHALLENGE = "#CHALLENGE#".toByteArray()
        private val WELCOME = "#WELCOME#".toByteArray()
        private val FAILURE = "#FAILURE#".toByteArray()
        private const val MESSAGE_LENGTH = 20
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            running.set(true)

            acceptThread = thread(name = "RpcServer-accept", isDaemon = true) {
                acceptLoop()
            }

            Logger.debug("RPC server listening on port $port")
        } catch (e: Exception) {
            Logger.error("Failed to start RPC server: ${e.message}")
            throw e
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                thread(name = "RpcServer-client", isDaemon = true) {
                    handleClient(client)
                }
            } catch (e: SocketException) {
                if (running.get()) {
                    Logger.debug("RPC accept error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                Logger.debug("RPC accept error: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000 // 30 second timeout
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            // Perform authentication
            if (!authenticate(input, output)) {
                Logger.debug("RPC client authentication failed")
                client.close()
                return
            }

            Logger.debug("RPC client authenticated")

            // Handle requests
            while (running.get() && !client.isClosed) {
                try {
                    val request = receivePickle(input) ?: break
                    val response = handleRequest(request)
                    sendPickle(output, response)
                } catch (e: Exception) {
                    Logger.debug("RPC request error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Logger.debug("RPC client error: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
        try {
            // Phase 1: deliver_challenge - we send challenge, client responds
            val random = SecureRandom()
            val challenge = ByteArray(MESSAGE_LENGTH)
            random.nextBytes(challenge)

            val digestPrefix = "{sha256}".toByteArray()
            val fullChallenge = CHALLENGE + digestPrefix + challenge
            sendBytes(output, fullChallenge)

            // Receive client's response
            val response = receiveBytes(input, 256)
            if (response == null) {
                sendBytes(output, FAILURE)
                return false
            }

            // Parse response to extract digest name and MAC
            val (digestName, responseMac) = parseResponse(response)

            // Verify client's HMAC
            val message = digestPrefix + challenge
            val verified = when (digestName) {
                "sha256" -> {
                    val expectedMac = computeHmac(message, authKey)
                    responseMac.contentEquals(expectedMac)
                }
                "md5", "" -> {
                    val expectedMac = computeHmacMd5(message, authKey)
                    responseMac.contentEquals(expectedMac)
                }
                else -> false
            }

            if (!verified) {
                Logger.debug("Client HMAC verification failed")
                sendBytes(output, FAILURE)
                return false
            }

            sendBytes(output, WELCOME)

            // Phase 2: answer_challenge - client sends challenge, we respond
            val clientChallenge = receiveBytes(input, 256)
            if (clientChallenge == null || !clientChallenge.startsWith(CHALLENGE)) {
                Logger.debug("Invalid client challenge")
                return false
            }

            // Extract client's message (after #CHALLENGE#)
            val clientMessage = clientChallenge.copyOfRange(CHALLENGE.size, clientChallenge.size)

            // Parse client's digest preference
            val (clientDigest, _) = parseResponse(clientMessage)
            val useDigest = clientDigest.ifEmpty { "sha256" }

            // Compute our response to client's challenge
            val ourMac = if (useDigest == "md5") {
                computeHmacMd5(clientMessage, authKey)
            } else {
                computeHmac(clientMessage, authKey)
            }

            // Send response with digest prefix (modern protocol)
            val ourResponse = "{$useDigest}".toByteArray() + ourMac
            sendBytes(output, ourResponse)

            // Receive client's welcome/failure
            val clientResult = receiveBytes(input, 256)
            if (clientResult == null || !clientResult.contentEquals(WELCOME)) {
                Logger.debug("Client rejected our response")
                return false
            }

            return true
        } catch (e: Exception) {
            Logger.debug("Authentication error: ${e.message}")
            return false
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * Parse a response to extract digest name and MAC payload.
     * Modern format: {digestname}MAC
     * Legacy format: raw 16-byte MD5
     */
    private fun parseResponse(response: ByteArray): Pair<String, ByteArray> {
        // Legacy lengths: 16 (MD5 digest) or 20 (challenge)
        if (response.size == 16 || response.size == 20) {
            return "" to response
        }

        // Modern format: {digestname}payload
        if (response.isNotEmpty() && response[0] == '{'.code.toByte()) {
            val closeBrace = response.indexOf('}'.code.toByte())
            if (closeBrace > 0 && closeBrace < 20) {
                val digestName = String(response, 1, closeBrace - 1)
                val payload = response.copyOfRange(closeBrace + 1, response.size)
                return digestName to payload
            }
        }

        // Unknown format, return as-is
        return "" to response
    }

    private fun ByteArray.indexOf(byte: Byte): Int {
        for (i in indices) {
            if (this[i] == byte) return i
        }
        return -1
    }

    private fun computeHmac(message: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun computeHmacMd5(message: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(message)
    }

    private fun sendBytes(output: DataOutputStream, data: ByteArray) {
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }

    private fun receiveBytes(input: DataInputStream, maxSize: Int): ByteArray? {
        val size = input.readInt()
        if (size < 0 || size > maxSize) return null
        val data = ByteArray(size)
        input.readFully(data)
        return data
    }

    private fun sendPickle(output: DataOutputStream, obj: Any?) {
        val pickler = Pickler()
        val baos = ByteArrayOutputStream()
        pickler.dump(obj, baos)
        val data = baos.toByteArray()
        sendBytes(output, data)
    }

    @Suppress("UNCHECKED_CAST")
    private fun receivePickle(input: DataInputStream): Map<String, Any?>? {
        val data = receiveBytes(input, 1024 * 1024) ?: return null
        val unpickler = Unpickler()
        val obj = unpickler.load(ByteArrayInputStream(data))
        return obj as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleRequest(request: Map<String, Any?>): Any? {
        Logger.debug("RPC request: $request")

        if ("get" in request) {
            val path = request["get"] as? String
            return when (path) {
                "interface_stats" -> getInterfaceStats()
                "path_table" -> getPathTable(request["max_hops"] as? Int ?: 255)
                "rate_table" -> getRateTable()
                "link_count" -> getLinkCount()
                "next_hop_if_name" -> {
                    val hash = request["destination_hash"] as? ByteArray
                    if (hash != null) getNextHopIfName(hash) else null
                }
                "next_hop" -> {
                    val hash = request["destination_hash"] as? ByteArray
                    if (hash != null) getNextHop(hash) else null
                }
                "first_hop_timeout" -> {
                    val hash = request["destination_hash"] as? ByteArray
                    if (hash != null) getFirstHopTimeout(hash) else null
                }
                else -> {
                    Logger.debug("Unknown RPC get: $path")
                    null
                }
            }
        }

        if ("drop" in request) {
            val path = request["drop"] as? String
            return when (path) {
                "path" -> {
                    val hash = request["destination_hash"] as? ByteArray
                    if (hash != null) dropPath(hash) else false
                }
                "all_via" -> {
                    val hash = request["destination_hash"] as? ByteArray
                    if (hash != null) dropAllVia(hash) else false
                }
                "announce_queues" -> dropAnnounceQueues()
                else -> {
                    Logger.debug("Unknown RPC drop: $path")
                    false
                }
            }
        }

        return null
    }

    private fun getInterfaceStats(): Map<String, Any?> {
        val interfaces = mutableListOf<Map<String, Any?>>()

        for (iface in Transport.getInterfaces()) {
            val stats = mutableMapOf<String, Any?>()

            stats["name"] = iface.name
            stats["short_name"] = iface.name
            stats["hash"] = iface.hash
            stats["status"] = iface.online
            stats["mode"] = 0 // MODE_FULL
            stats["bitrate"] = iface.bitrate
            stats["mtu"] = iface.hwMtu
            stats["txb"] = 0L // TODO: track actual stats
            stats["rxb"] = 0L // TODO: track actual stats
            stats["clients"] = null // For server interfaces

            interfaces.add(stats)
        }

        return mapOf("interfaces" to interfaces)
    }

    private fun getPathTable(maxHops: Int): List<Map<String, Any?>> {
        // Return empty path table for now
        // TODO: implement when destination table is exposed
        return emptyList()
    }

    private fun getRateTable(): Map<ByteArray, Map<String, Any?>> {
        // Rate limiting not fully implemented yet
        return emptyMap()
    }

    private fun getLinkCount(): Int {
        // Return count of active links
        return 0 // TODO: implement when Link tracking is added to Transport
    }

    private fun getNextHopIfName(destHash: ByteArray): String? {
        // TODO: implement when destination table is exposed
        return null
    }

    private fun getNextHop(destHash: ByteArray): ByteArray? {
        // TODO: implement when destination table is exposed
        return null
    }

    private fun getFirstHopTimeout(destHash: ByteArray): Long {
        // Default first hop timeout
        return 5000L // 5 seconds
    }

    private fun dropPath(destHash: ByteArray): Boolean {
        // TODO: implement when destination table is exposed
        return true
    }

    private fun dropAllVia(destHash: ByteArray): Boolean {
        // TODO: implement when destination table is exposed
        return true
    }

    private fun dropAnnounceQueues(): Boolean {
        // Clear announce queues
        return true
    }
}
