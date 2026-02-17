package network.reticulum.interfaces.i2p

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * SAM (Simple Anonymous Messaging) v3.1 protocol client for I2P.
 *
 * The SAM API is a text-based TCP protocol exposed by the I2P router
 * on localhost (default port 7656). It allows applications to create
 * anonymous tunnels, generate destinations, and stream data through
 * the I2P network without implementing the full I2P protocol stack.
 *
 * Protocol reference: https://geti2p.net/en/docs/api/samv3
 *
 * This is a port of Python's RNS.vendor.i2plib to Kotlin.
 */
object I2PSamClient {

    /** Default SAM API address (I2P router on localhost). */
    val DEFAULT_SAM_ADDRESS = InetSocketAddress("127.0.0.1", 7656)

    /** SAM protocol buffer size. */
    const val SAM_BUFSIZE = 4096

    /** SAM protocol version range. */
    const val MIN_VERSION = "3.1"
    const val MAX_VERSION = "3.1"

    /** Placeholder for transient (ephemeral) destinations. */
    const val TRANSIENT_DESTINATION = "TRANSIENT"

    // I2P uses a modified Base64 alphabet: '+' → '-', '/' → '~'
    private const val I2P_B64_CHARS = "-~"

    /**
     * Encode bytes to I2P-flavored Base64.
     * Standard Base64 with '+' replaced by '-' and '/' replaced by '~'.
     */
    fun i2pB64Encode(data: ByteArray): String {
        return Base64.getEncoder().encodeToString(data)
            .replace('+', '-')
            .replace('/', '~')
    }

    /**
     * Decode I2P-flavored Base64 to bytes.
     */
    fun i2pB64Decode(data: String): ByteArray {
        val standardB64 = data
            .replace('-', '+')
            .replace('~', '/')
        return Base64.getDecoder().decode(standardB64)
    }

    /**
     * Open a SAM socket with HELLO handshake.
     *
     * @return A connected [SamConnection] ready for commands.
     * @throws SamException if the handshake fails.
     */
    fun connect(samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS): SamConnection {
        val socket = Socket()
        socket.connect(samAddress, 10_000)
        val conn = SamConnection(socket)

        conn.writeLine("HELLO VERSION MIN=$MIN_VERSION MAX=$MAX_VERSION")
        val reply = conn.readReply()
        if (!reply.isOk) {
            conn.close()
            throw reply.toException()
        }
        return conn
    }

    /**
     * Generate a new I2P destination (keypair).
     *
     * @param sigType Signature type. Default 7 = EdDSA_SHA512_Ed25519.
     * @return A [Destination] with private key.
     */
    fun generateDestination(
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS,
        sigType: Int = Destination.EdDSA_SHA512_Ed25519
    ): Destination {
        val conn = connect(samAddress)
        conn.use {
            it.writeLine("DEST GENERATE SIGNATURE_TYPE=$sigType")
            val reply = it.readReply()
            val privKey = reply.opts["PRIV"] ?: throw SamException("No PRIV in DEST GENERATE reply")
            return Destination(privKey, hasPrivateKey = true)
        }
    }

    /**
     * Look up a full I2P destination from a .i2p domain or .b32.i2p address.
     */
    fun namingLookup(
        name: String,
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS
    ): Destination {
        val conn = connect(samAddress)
        conn.use {
            it.writeLine("NAMING LOOKUP NAME=$name")
            val reply = it.readReply()
            if (!reply.isOk) throw reply.toException()
            val value = reply.opts["VALUE"] ?: throw SamException("No VALUE in NAMING LOOKUP reply")
            return Destination(value)
        }
    }

    /**
     * Create a SAM session.
     *
     * The session connection must be kept alive for the session to persist.
     *
     * @param sessionName Unique session identifier.
     * @param destination Destination to bind (or null for TRANSIENT).
     * @param style Session style: "STREAM", "DATAGRAM", or "RAW".
     * @return A [SamConnection] that holds the session alive.
     */
    fun createSession(
        sessionName: String,
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS,
        destination: Destination? = null,
        style: String = "STREAM",
        options: Map<String, String> = emptyMap()
    ): SamConnection {
        val destString = if (destination?.privateKey != null) {
            destination.privateKey.base64
        } else {
            TRANSIENT_DESTINATION
        }

        val optStr = options.entries.joinToString(" ") { "${it.key}=${it.value}" }

        val conn = connect(samAddress)
        conn.writeLine("SESSION CREATE STYLE=$style ID=$sessionName DESTINATION=$destString $optStr")
        val reply = conn.readReply()
        if (!reply.isOk) {
            conn.close()
            throw reply.toException()
        }
        return conn
    }

    /**
     * Connect to a remote I2P destination via an existing session.
     *
     * @param sessionName The session to connect through.
     * @param destination The remote destination (base64 string or Destination).
     * @return A [SamConnection] representing the stream.
     */
    fun streamConnect(
        sessionName: String,
        destination: String,
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS
    ): SamConnection {
        val conn = connect(samAddress)
        conn.writeLine("STREAM CONNECT ID=$sessionName DESTINATION=$destination SILENT=false")
        val reply = conn.readReply()
        if (!reply.isOk) {
            conn.close()
            throw reply.toException()
        }
        return conn
    }

    /**
     * Accept an incoming I2P stream connection via an existing session.
     *
     * @param sessionName The session to accept on.
     * @return A [SamConnection] representing the accepted stream.
     */
    fun streamAccept(
        sessionName: String,
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS
    ): SamConnection {
        val conn = connect(samAddress)
        conn.writeLine("STREAM ACCEPT ID=$sessionName SILENT=false")
        val reply = conn.readReply()
        if (!reply.isOk) {
            conn.close()
            throw reply.toException()
        }
        return conn
    }

    /**
     * Set up stream forwarding: incoming I2P connections are forwarded
     * to a local TCP port.
     *
     * @param sessionName The session to forward.
     * @param port Local port to forward to.
     */
    fun streamForward(
        sessionName: String,
        port: Int,
        samAddress: InetSocketAddress = DEFAULT_SAM_ADDRESS
    ): SamConnection {
        val conn = connect(samAddress)
        conn.writeLine("STREAM FORWARD ID=$sessionName PORT=$port HOST=127.0.0.1")
        val reply = conn.readReply()
        if (!reply.isOk) {
            conn.close()
            throw reply.toException()
        }
        return conn
    }

    /**
     * Get a free local TCP port.
     */
    fun getFreePort(): Int {
        val sock = ServerSocket(0)
        val port = sock.localPort
        sock.close()
        return port
    }

    /**
     * Get the SAM address from the I2P_SAM_ADDRESS environment variable,
     * or use the default.
     */
    fun getSamAddress(): InetSocketAddress {
        val env = System.getenv("I2P_SAM_ADDRESS")
        if (env != null && env.contains(":")) {
            val parts = env.split(":")
            return InetSocketAddress(parts[0], parts[1].toInt())
        }
        return DEFAULT_SAM_ADDRESS
    }

    /**
     * Generate a random session ID matching Python's format.
     */
    fun generateSessionId(length: Int = 6): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = java.security.SecureRandom()
        val suffix = (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
        return "reticulum-$suffix"
    }
}

/**
 * A TCP connection to the SAM API.
 *
 * Wraps a socket with line-oriented reading (SAM is newline-delimited)
 * and raw byte access for stream data after tunnel setup.
 */
class SamConnection(val socket: Socket) : Closeable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    val outputStream: OutputStream = socket.getOutputStream()
    val inputStream = socket.getInputStream()

    fun writeLine(line: String) {
        outputStream.write("$line\n".toByteArray())
        outputStream.flush()
    }

    fun readReply(): SamReply {
        val line = reader.readLine()
            ?: throw SamException("SAM connection closed unexpectedly")
        println("[SAM] << $line")
        return SamReply.parse(line)
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

/**
 * Parsed SAM protocol reply.
 *
 * SAM replies have the format: `COMMAND ACTION KEY=VALUE KEY=VALUE ...`
 * For example: `HELLO REPLY RESULT=OK VERSION=3.1`
 */
data class SamReply(
    val cmd: String,
    val action: String,
    val opts: Map<String, String>
) {
    val isOk: Boolean get() = opts["RESULT"] == "OK"

    fun toException(): SamException {
        val result = opts["RESULT"] ?: "UNKNOWN"
        val message = opts["MESSAGE"] ?: ""
        return SAM_EXCEPTIONS[result]?.invoke("$result: $message")
            ?: SamException("SAM error: $result $message")
    }

    companion object {
        fun parse(line: String): SamReply {
            val parts = line.trim().split(" ", limit = 3)
            val cmd = parts.getOrElse(0) { "" }
            val action = parts.getOrElse(1) { "" }
            val optsStr = parts.getOrElse(2) { "" }

            val opts = mutableMapOf<String, String>()
            // Parse KEY=VALUE pairs, handling quoted values like MESSAGE="some text"
            var i = 0
            while (i < optsStr.length) {
                // Skip whitespace
                while (i < optsStr.length && optsStr[i] == ' ') i++
                if (i >= optsStr.length) break

                val eqIdx = optsStr.indexOf('=', i)
                if (eqIdx < 0) {
                    // No '=' — treat rest as a bare key
                    val key = optsStr.substring(i).trim()
                    if (key.isNotEmpty()) opts[key] = "true"
                    break
                }

                val key = optsStr.substring(i, eqIdx)
                i = eqIdx + 1

                if (i < optsStr.length && optsStr[i] == '"') {
                    // Quoted value — find closing quote
                    val closeQuote = optsStr.indexOf('"', i + 1)
                    if (closeQuote >= 0) {
                        opts[key] = optsStr.substring(i + 1, closeQuote)
                        i = closeQuote + 1
                    } else {
                        // No closing quote — take rest of string
                        opts[key] = optsStr.substring(i + 1)
                        break
                    }
                } else {
                    // Unquoted value — read until next space
                    val spaceIdx = optsStr.indexOf(' ', i)
                    if (spaceIdx >= 0) {
                        opts[key] = optsStr.substring(i, spaceIdx)
                        i = spaceIdx
                    } else {
                        opts[key] = optsStr.substring(i)
                        break
                    }
                }
            }
            return SamReply(cmd, action, opts)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// SAM Exceptions — match Python i2plib.exceptions exactly
// ──────────────────────────────────────────────────────────────────────

open class SamException(message: String) : java.io.IOException(message)
class CantReachPeerException(message: String = "") : SamException("Can't reach peer: $message")
class DuplicatedDestException(message: String = "") : SamException("Destination already in use: $message")
class DuplicatedIdException(message: String = "") : SamException("Session ID already in use: $message")
class I2PErrorException(message: String = "") : SamException("I2P error: $message")
class InvalidIdException(message: String = "") : SamException("Invalid session ID: $message")
class InvalidKeyException(message: String = "") : SamException("Invalid key: $message")
class KeyNotFoundException(message: String = "") : SamException("Key not found: $message")
class PeerNotFoundException(message: String = "") : SamException("Peer not found: $message")
class I2PTimeoutException(message: String = "") : SamException("I2P timeout: $message")

/** Map SAM RESULT strings to exception constructors. Matches Python's SAM_EXCEPTIONS dict. */
val SAM_EXCEPTIONS: Map<String, (String) -> SamException> = mapOf(
    "CANT_REACH_PEER" to ::CantReachPeerException,
    "DUPLICATED_DEST" to ::DuplicatedDestException,
    "DUPLICATED_ID" to ::DuplicatedIdException,
    "I2P_ERROR" to ::I2PErrorException,
    "INVALID_ID" to ::InvalidIdException,
    "INVALID_KEY" to ::InvalidKeyException,
    "KEY_NOT_FOUND" to ::KeyNotFoundException,
    "PEER_NOT_FOUND" to ::PeerNotFoundException,
    "TIMEOUT" to ::I2PTimeoutException,
)

// ──────────────────────────────────────────────────────────────────────
// I2P Destination & Private Key — match Python i2plib.sam
// ──────────────────────────────────────────────────────────────────────

/**
 * An I2P destination (public key + certificate), optionally with private key.
 *
 * Matches Python's `i2plib.sam.Destination`. The destination binary format is:
 * - 256 bytes public key
 * - 128 bytes signing key
 * - 3+ bytes certificate (first 2 bytes after signing key are cert length as uint16 BE)
 *
 * The base32 address is SHA-256 of the binary destination, base32-encoded, truncated to 52 chars.
 */
class Destination private constructor(
    /** Binary destination (public part only). */
    val data: ByteArray,
    /** Base64-encoded destination (I2P alphabet). */
    val base64: String,
    /** Private key, if available. */
    val privateKey: PrivateKey?
) {
    companion object {
        const val ECDSA_SHA256_P256 = 1
        const val ECDSA_SHA384_P384 = 2
        const val ECDSA_SHA512_P521 = 3
        const val EdDSA_SHA512_Ed25519 = 7

        private const val PUBKEY_SIZE = 256
        private const val SIGNKEY_SIZE = 128

        /**
         * Extract public destination bytes from a full keypair.
         * Format: pubkey(256) + signkey(128) + cert(3+)
         * Certificate length is at bytes 385-386 (uint16 BE).
         */
        private fun extractPublicDest(fullBytes: ByteArray): ByteArray {
            val certLen = ((fullBytes[385].toInt() and 0xFF) shl 8) or (fullBytes[386].toInt() and 0xFF)
            val destLen = PUBKEY_SIZE + SIGNKEY_SIZE + 3 + certLen
            return fullBytes.copyOfRange(0, destLen)
        }
    }

    /**
     * Create a Destination from base64 string.
     *
     * @param b64Data Base64 string (I2P alphabet)
     * @param hasPrivateKey If true, data includes the private key portion
     */
    constructor(b64Data: String, hasPrivateKey: Boolean = false) : this(
        data = if (hasPrivateKey) extractPublicDest(I2PSamClient.i2pB64Decode(b64Data))
               else I2PSamClient.i2pB64Decode(b64Data),
        base64 = if (hasPrivateKey) I2PSamClient.i2pB64Encode(extractPublicDest(I2PSamClient.i2pB64Decode(b64Data)))
                 else b64Data,
        privateKey = if (hasPrivateKey) PrivateKey(I2PSamClient.i2pB64Decode(b64Data)) else null
    )

    /**
     * Create a Destination from binary data.
     */
    constructor(binaryData: ByteArray, hasPrivateKey: Boolean = false) : this(
        data = if (hasPrivateKey) extractPublicDest(binaryData) else binaryData,
        base64 = I2PSamClient.i2pB64Encode(if (hasPrivateKey) extractPublicDest(binaryData) else binaryData),
        privateKey = if (hasPrivateKey) PrivateKey(binaryData) else null
    )

    /**
     * Base32 destination hash — the `.b32.i2p` address (52 chars, lowercase).
     * Computed as: base32(sha256(destination_bytes)), truncated to 52 chars.
     */
    val base32: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        base32Encode(digest).substring(0, 52).lowercase()
    }

    override fun toString(): String = "<Destination: $base32>"
}

/**
 * I2P private key (full keypair as binary or base64).
 */
class PrivateKey(data: Any) {
    val data: ByteArray
    val base64: String

    init {
        when (data) {
            is ByteArray -> {
                this.data = data
                this.base64 = I2PSamClient.i2pB64Encode(data)
            }
            is String -> {
                this.data = I2PSamClient.i2pB64Decode(data)
                this.base64 = data
            }
            else -> throw IllegalArgumentException("PrivateKey data must be ByteArray or String")
        }
    }
}

/**
 * RFC 4648 Base32 encoding (no padding).
 */
private fun base32Encode(data: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val sb = StringBuilder()
    var bits = 0
    var buffer = 0

    for (byte in data) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            sb.append(alphabet[(buffer shr bits) and 0x1F])
        }
    }
    if (bits > 0) {
        sb.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
    }
    return sb.toString()
}
