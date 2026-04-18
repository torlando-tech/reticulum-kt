import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import network.reticulum.common.*
import network.reticulum.crypto.*
import network.reticulum.identity.Identity
import network.reticulum.destination.Destination
import network.reticulum.interfaces.IfacUtils
import network.reticulum.interfaces.framing.HDLC
import network.reticulum.interfaces.framing.KISS
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.resource.ResourceConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.msgpack.core.MessagePack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private val gson = Gson()
private val crypto: CryptoProvider = BouncyCastleProvider()

fun main() {
    println("READY")
    System.out.flush()

    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        try {
            val request = gson.fromJson(trimmed, JsonObject::class.java)
            val id = request["id"].asString
            val command = request["command"].asString
            val params = request["params"].asJsonObject

            try {
                val result = handleCommand(command, params)
                val response = JsonObject().apply {
                    addProperty("id", id)
                    addProperty("success", true)
                    add("result", result)
                }
                println(gson.toJson(response))
            } catch (e: Exception) {
                val response = JsonObject().apply {
                    addProperty("id", id)
                    addProperty("success", false)
                    addProperty("error", e.message ?: "Unknown error")
                }
                println(gson.toJson(response))
            }
        } catch (e: Exception) {
            val response = JsonObject().apply {
                addProperty("id", "parse_error")
                addProperty("success", false)
                addProperty("error", "JSON parse error: ${e.message}")
            }
            println(gson.toJson(response))
        }
        System.out.flush()
    }
}

// --- Hex helpers ---

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.fromHex(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun JsonObject.hex(key: String): ByteArray {
    val s = get(key)?.asString ?: throw IllegalArgumentException("Missing param: $key")
    return s.fromHex()
}

fun JsonObject.hexOpt(key: String): ByteArray? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asString.fromHex()
}

fun JsonObject.int(key: String): Int {
    return get(key)?.asInt ?: throw IllegalArgumentException("Missing param: $key")
}

fun JsonObject.intOpt(key: String): Int? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asInt
}

fun JsonObject.double(key: String): Double {
    return get(key)?.asDouble ?: throw IllegalArgumentException("Missing param: $key")
}

fun JsonObject.str(key: String): String {
    return get(key)?.asString ?: throw IllegalArgumentException("Missing param: $key")
}

fun JsonObject.strOpt(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asString
}

fun JsonObject.bool(key: String): Boolean {
    return get(key)?.asBoolean ?: throw IllegalArgumentException("Missing param: $key")
}

fun JsonObject.boolOpt(key: String): Boolean? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return el.asBoolean
}

fun JsonObject.stringArray(key: String): List<String> {
    val el = get(key) ?: return emptyList()
    if (!el.isJsonArray) return emptyList()
    return el.asJsonArray.map { it.asString }
}

// --- Result builder helpers ---

fun result(vararg pairs: Pair<String, JsonElement>): JsonObject {
    return JsonObject().apply {
        pairs.forEach { (k, v) -> add(k, v) }
    }
}

fun hexVal(data: ByteArray): JsonPrimitive = JsonPrimitive(data.toHex())
fun strVal(s: String): JsonPrimitive = JsonPrimitive(s)
fun intVal(i: Int): JsonPrimitive = JsonPrimitive(i)
fun doubleVal(d: Double): JsonPrimitive = JsonPrimitive(d)
fun boolVal(b: Boolean): JsonPrimitive = JsonPrimitive(b)

// --- MessagePack helpers ---

fun packMsgPackArray(elements: List<Any?>): ByteArray {
    val out = ByteArrayOutputStream()
    val packer = MessagePack.newDefaultPacker(out)
    packer.packArrayHeader(elements.size)
    for (el in elements) {
        packMsgPackValue(packer, el)
    }
    packer.close()
    return out.toByteArray()
}

fun packMsgPackValue(packer: org.msgpack.core.MessagePacker, value: Any?) {
    when (value) {
        null -> packer.packNil()
        is ByteArray -> { packer.packBinaryHeader(value.size); packer.writePayload(value) }
        is Int -> packer.packLong(value.toLong())
        is Long -> packer.packLong(value)
        is Double -> packer.packDouble(value)
        is Float -> packer.packFloat(value)
        is String -> packer.packString(value)
        is Boolean -> packer.packBoolean(value)
        is List<*> -> {
            packer.packArrayHeader(value.size)
            for (item in value) packMsgPackValue(packer, item)
        }
        is Map<*, *> -> {
            packer.packMapHeader(value.size)
            for ((k, v) in value) {
                packMsgPackValue(packer, k)
                packMsgPackValue(packer, v)
            }
        }
        else -> throw IllegalArgumentException("Unsupported msgpack type: ${value::class}")
    }
}

fun packMsgPackDouble(d: Double): ByteArray {
    val out = ByteArrayOutputStream()
    val packer = MessagePack.newDefaultPacker(out)
    packer.packDouble(d)
    packer.close()
    return out.toByteArray()
}

fun packMsgPackInt(n: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val packer = MessagePack.newDefaultPacker(out)
    packer.packLong(n.toLong())
    packer.close()
    return out.toByteArray()
}

sealed class MsgPackVal {
    data class Binary(val data: ByteArray) : MsgPackVal()
    data class Str(val value: String) : MsgPackVal()
    data class IntVal(val value: Long) : MsgPackVal()
    data class FloatVal(val value: Double) : MsgPackVal()
    data class BoolVal(val value: Boolean) : MsgPackVal()
    data class ArrayVal(val items: List<MsgPackVal>) : MsgPackVal()
    data class MapVal(val entries: Map<MsgPackVal, MsgPackVal>) : MsgPackVal()
    object Nil : MsgPackVal()
}

fun unpackMsgPack(data: ByteArray): MsgPackVal {
    val unpacker = MessagePack.newDefaultUnpacker(data)
    val result = unpackMsgPackValue(unpacker)
    unpacker.close()
    return result
}

fun unpackMsgPackValue(unpacker: org.msgpack.core.MessageUnpacker): MsgPackVal {
    val format = unpacker.nextFormat
    return when (format.valueType) {
        org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); MsgPackVal.Nil }
        org.msgpack.value.ValueType.BOOLEAN -> MsgPackVal.BoolVal(unpacker.unpackBoolean())
        org.msgpack.value.ValueType.INTEGER -> MsgPackVal.IntVal(unpacker.unpackLong())
        org.msgpack.value.ValueType.FLOAT -> MsgPackVal.FloatVal(unpacker.unpackDouble())
        org.msgpack.value.ValueType.STRING -> MsgPackVal.Str(unpacker.unpackString())
        org.msgpack.value.ValueType.BINARY -> {
            val len = unpacker.unpackBinaryHeader()
            MsgPackVal.Binary(unpacker.readPayload(len))
        }
        org.msgpack.value.ValueType.ARRAY -> {
            val size = unpacker.unpackArrayHeader()
            val items = (0 until size).map { unpackMsgPackValue(unpacker) }
            MsgPackVal.ArrayVal(items)
        }
        org.msgpack.value.ValueType.MAP -> {
            val size = unpacker.unpackMapHeader()
            val entries = (0 until size).associate {
                unpackMsgPackValue(unpacker) to unpackMsgPackValue(unpacker)
            }
            MsgPackVal.MapVal(entries)
        }
        else -> { unpacker.skipValue(); MsgPackVal.Nil }
    }
}

fun msgPackValToDouble(v: MsgPackVal): Double = when (v) {
    is MsgPackVal.FloatVal -> v.value
    is MsgPackVal.IntVal -> v.value.toDouble()
    else -> 0.0
}

fun msgPackValToInt(v: MsgPackVal): Int = when (v) {
    is MsgPackVal.IntVal -> v.value.toInt()
    is MsgPackVal.FloatVal -> v.value.toInt()
    else -> 0
}

fun msgPackValToBinary(v: MsgPackVal): ByteArray = when (v) {
    is MsgPackVal.Binary -> v.data
    else -> ByteArray(0)
}

// --- Resource advertisement pack via msgpack ---

fun packResourceAdv(
    transferSize: Int, dataSize: Int, numParts: Int,
    hash: ByteArray, randomHash: ByteArray, originalHash: ByteArray,
    segmentIndex: Int, totalSegments: Int, requestId: ByteArray?,
    flags: Int, hashmap: ByteArray
): ByteArray {
    val out = ByteArrayOutputStream()
    val packer = MessagePack.newDefaultPacker(out)
    packer.packMapHeader(11)

    packer.packString("t"); packer.packInt(transferSize)
    packer.packString("d"); packer.packInt(dataSize)
    packer.packString("n"); packer.packInt(numParts)
    packer.packString("h"); packer.packBinaryHeader(hash.size); packer.writePayload(hash)
    packer.packString("r"); packer.packBinaryHeader(randomHash.size); packer.writePayload(randomHash)
    packer.packString("o"); packer.packBinaryHeader(originalHash.size); packer.writePayload(originalHash)
    packer.packString("i"); packer.packInt(segmentIndex)
    packer.packString("l"); packer.packInt(totalSegments)
    packer.packString("q")
    if (requestId != null) {
        packer.packBinaryHeader(requestId.size); packer.writePayload(requestId)
    } else {
        packer.packNil()
    }
    packer.packString("f"); packer.packInt(flags)
    packer.packString("m"); packer.packBinaryHeader(hashmap.size); packer.writePayload(hashmap)

    packer.close()
    return out.toByteArray()
}

data class ResourceAdvData(
    val transferSize: Int, val dataSize: Int, val numParts: Int,
    val hash: ByteArray, val randomHash: ByteArray, val originalHash: ByteArray,
    val segmentIndex: Int, val totalSegments: Int, val requestId: ByteArray?,
    val flags: Int, val hashmap: ByteArray,
    val encrypted: Boolean, val compressed: Boolean, val split: Boolean,
    val isRequest: Boolean, val isResponse: Boolean, val hasMetadata: Boolean
)

fun unpackResourceAdv(data: ByteArray): ResourceAdvData {
    val unpacker = MessagePack.newDefaultUnpacker(data)
    val mapSize = unpacker.unpackMapHeader()

    var transferSize = 0; var dataSize = 0; var numParts = 0
    var hash = ByteArray(0); var randomHash = ByteArray(0); var originalHash = ByteArray(0)
    var segmentIndex = 1; var totalSegments = 1; var requestId: ByteArray? = null
    var flags = 0; var hashmap = ByteArray(0)

    repeat(mapSize) {
        val key = unpacker.unpackString()
        when (key) {
            "t" -> transferSize = unpacker.unpackInt()
            "d" -> dataSize = unpacker.unpackInt()
            "n" -> numParts = unpacker.unpackInt()
            "h" -> { val len = unpacker.unpackBinaryHeader(); hash = unpacker.readPayload(len) }
            "r" -> { val len = unpacker.unpackBinaryHeader(); randomHash = unpacker.readPayload(len) }
            "o" -> { val len = unpacker.unpackBinaryHeader(); originalHash = unpacker.readPayload(len) }
            "m" -> { val len = unpacker.unpackBinaryHeader(); hashmap = unpacker.readPayload(len) }
            "f" -> flags = unpacker.unpackInt()
            "i" -> segmentIndex = unpacker.unpackInt()
            "l" -> totalSegments = unpacker.unpackInt()
            "q" -> {
                if (unpacker.tryUnpackNil()) { requestId = null }
                else { val len = unpacker.unpackBinaryHeader(); requestId = unpacker.readPayload(len) }
            }
            else -> unpacker.skipValue()
        }
    }
    unpacker.close()

    return ResourceAdvData(
        transferSize, dataSize, numParts, hash, randomHash, originalHash,
        segmentIndex, totalSegments, requestId, flags, hashmap,
        encrypted = (flags and 0x01) == 0x01,
        compressed = ((flags shr 1) and 0x01) == 0x01,
        split = ((flags shr 2) and 0x01) == 0x01,
        isRequest = ((flags shr 3) and 0x01) == 0x01,
        isResponse = ((flags shr 4) and 0x01) == 0x01,
        hasMetadata = ((flags shr 5) and 0x01) == 0x01
    )
}

// --- Leading zero bits counter ---

fun countLeadingZeroBits(hash: ByteArray): Int {
    var zeros = 0
    for (byte in hash) {
        if (byte == 0.toByte()) {
            zeros += 8
        } else {
            var b = byte.toInt() and 0xFF
            while (b and 0x80 == 0) { zeros++; b = b shl 1 }
            break
        }
    }
    return zeros
}

// --- Command dispatch ---

fun handleCommand(command: String, p: JsonObject): JsonObject {
    return when (command) {

        // === 1. Crypto — Key Generation & Exchange ===

        "x25519_generate" -> {
            val seed = p.hex("seed")
            val kp = crypto.x25519KeyPairFromSeed(seed)
            result("private_key" to hexVal(kp.privateKey), "public_key" to hexVal(kp.publicKey))
        }

        "x25519_public_from_private" -> {
            val priv = p.hex("private_key")
            val pub = crypto.x25519PublicFromPrivate(priv)
            result("public_key" to hexVal(pub))
        }

        "x25519_exchange" -> {
            val priv = p.hex("private_key")
            val peerPub = p.hex("peer_public_key")
            val shared = crypto.x25519Exchange(priv, peerPub)
            result("shared_secret" to hexVal(shared))
        }

        "ed25519_generate" -> {
            val seed = p.hex("seed")
            val kp = crypto.ed25519KeyPairFromSeed(seed)
            result("private_key" to hexVal(kp.privateKey), "public_key" to hexVal(kp.publicKey))
        }

        "ed25519_sign" -> {
            val priv = p.hex("private_key")
            val message = p.hex("message")
            val sig = crypto.ed25519Sign(priv, message)
            result("signature" to hexVal(sig))
        }

        "ed25519_verify" -> {
            val pub = p.hex("public_key")
            val message = p.hex("message")
            val signature = p.hex("signature")
            val valid = crypto.ed25519Verify(pub, message, signature)
            result("valid" to boolVal(valid))
        }

        // === 2. Crypto — Hashing ===

        "sha256" -> {
            val data = p.hex("data")
            result("hash" to hexVal(crypto.sha256(data)))
        }

        "sha512" -> {
            val data = p.hex("data")
            result("hash" to hexVal(crypto.sha512(data)))
        }

        "hmac_sha256" -> {
            val key = p.hex("key")
            val message = p.hex("message")
            result("hmac" to hexVal(crypto.hmacSha256(key, message)))
        }

        "truncated_hash" -> {
            val data = p.hex("data")
            result("hash" to hexVal(Hashes.truncatedHash(data)))
        }

        // === 3. Crypto — Key Derivation ===

        "hkdf" -> {
            val length = p.int("length")
            val ikm = p.hex("ikm")
            val salt = p.hexOpt("salt")
            val info = p.hexOpt("info")
            val derived = crypto.hkdf(length, ikm, salt, info)
            result("derived_key" to hexVal(derived))
        }

        // === 4. Crypto — Symmetric Encryption ===

        "aes_encrypt" -> {
            val plaintext = p.hex("plaintext")
            val key = p.hex("key")
            val iv = p.hex("iv")
            val mode = if (key.size == 16) AesMode.AES_128_CBC else AesMode.AES_256_CBC
            val encrypted = crypto.aesEncrypt(plaintext, key, iv, mode)
            result("ciphertext" to hexVal(encrypted))
        }

        "aes_decrypt" -> {
            val ciphertext = p.hex("ciphertext")
            val key = p.hex("key")
            val iv = p.hex("iv")
            val mode = if (key.size == 16) AesMode.AES_128_CBC else AesMode.AES_256_CBC
            val decrypted = crypto.aesDecrypt(ciphertext, key, iv, mode)
            result("plaintext" to hexVal(decrypted))
        }

        "pkcs7_pad" -> {
            val data = p.hex("data")
            val blockSize = 16
            val padLen = blockSize - (data.size % blockSize)
            val padded = data + ByteArray(padLen) { padLen.toByte() }
            result("padded" to hexVal(padded))
        }

        "pkcs7_unpad" -> {
            val data = p.hex("data")
            val padLen = data.last().toInt() and 0xFF
            if (padLen < 1 || padLen > 16 || padLen > data.size) {
                throw IllegalArgumentException("Invalid PKCS7 padding")
            }
            val unpadded = data.copyOf(data.size - padLen)
            result("unpadded" to hexVal(unpadded))
        }

        // === 5. Token Encryption ===

        "token_encrypt" -> {
            val key = p.hex("key")
            val plaintext = p.hex("plaintext")
            val token = Token(key, crypto)
            val encrypted = if (p.hexOpt("iv") != null) {
                token.encryptWithIv(plaintext, p.hex("iv"))
            } else {
                token.encrypt(plaintext)
            }
            result("token" to hexVal(encrypted))
        }

        "token_decrypt" -> {
            val key = p.hex("key")
            val tokenData = p.hex("token")
            val token = Token(key, crypto)
            val decrypted = token.decrypt(tokenData)
            result("plaintext" to hexVal(decrypted))
        }

        "token_verify_hmac" -> {
            val key = p.hex("key")
            val tokenData = p.hex("token")
            if (tokenData.size < 64) {
                result("valid" to boolVal(false))
            } else {
                // For AES-256 token: signing key = first 32 bytes of 64-byte key
                val signingKey = key.copyOf(32)
                val signedParts = tokenData.copyOfRange(0, tokenData.size - 32)
                val receivedHmac = tokenData.copyOfRange(tokenData.size - 32, tokenData.size)
                val expectedHmac = crypto.hmacSha256(signingKey, signedParts)
                val valid = receivedHmac.contentEquals(expectedHmac)
                result("valid" to boolVal(valid))
            }
        }

        // === 6. Identity ===

        "identity_from_private_key" -> {
            val privBytes = p.hex("private_key")
            val identity = Identity.fromPrivateKey(privBytes, crypto)
            result(
                "public_key" to hexVal(identity.getPublicKey()),
                "hash" to hexVal(identity.hash),
                "hexhash" to strVal(identity.hexHash)
            )
        }

        "identity_encrypt" -> {
            val pubBytes = p.hex("public_key")
            val plaintext = p.hex("plaintext")
            val identityHash = p.hex("identity_hash")

            val ephPrivBytes = p.hexOpt("ephemeral_private")
            val iv = p.hexOpt("iv")

            if (ephPrivBytes != null && iv != null) {
                // Deterministic encryption with provided ephemeral key and IV
                val encPub = pubBytes.copyOfRange(0, 32)
                val ephPub = crypto.x25519PublicFromPrivate(ephPrivBytes)
                val shared = crypto.x25519Exchange(ephPrivBytes, encPub)
                val derived = crypto.hkdf(64, shared, identityHash, null)
                val token = Token(derived, crypto)
                val encrypted = token.encryptWithIv(plaintext, iv)
                val ciphertext = ephPub + encrypted
                result(
                    "ciphertext" to hexVal(ciphertext),
                    "ephemeral_public" to hexVal(ephPub),
                    "shared_key" to hexVal(shared),
                    "derived_key" to hexVal(derived)
                )
            } else {
                // Random ephemeral key
                val identity = Identity.fromPublicKey(pubBytes, crypto)
                val ciphertext = identity.encrypt(plaintext)
                result(
                    "ciphertext" to hexVal(ciphertext),
                    "ephemeral_public" to hexVal(ciphertext.copyOfRange(0, 32)),
                    "shared_key" to strVal(""),
                    "derived_key" to strVal("")
                )
            }
        }

        "identity_decrypt" -> {
            val privBytes = p.hex("private_key")
            val ciphertext = p.hex("ciphertext")
            val identityHash = p.hex("identity_hash")
            val identity = Identity.fromPrivateKey(privBytes, crypto)
            val plaintext = identity.decrypt(ciphertext)
                ?: throw IllegalStateException("Decryption failed")
            result(
                "plaintext" to hexVal(plaintext),
                "shared_key" to strVal(""),
                "derived_key" to strVal("")
            )
        }

        "identity_sign" -> {
            val privBytes = p.hex("private_key")
            val message = p.hex("message")
            // Use Ed25519 signing seed (last 32 bytes of 64-byte private key)
            val sigPriv = privBytes.copyOfRange(32, 64)
            val sig = crypto.ed25519Sign(sigPriv, message)
            result("signature" to hexVal(sig))
        }

        "identity_verify" -> {
            val pubBytes = p.hex("public_key")
            val message = p.hex("message")
            val signature = p.hex("signature")
            // Ed25519 public key is last 32 bytes of 64-byte public key
            val sigPub = pubBytes.copyOfRange(32, 64)
            val valid = crypto.ed25519Verify(sigPub, message, signature)
            result("valid" to boolVal(valid))
        }

        "identity_hash" -> {
            val pubBytes = p.hex("public_key")
            val hash = Hashes.truncatedHash(pubBytes)
            result("hash" to hexVal(hash))
        }

        // === 7. Destination ===

        "name_hash" -> {
            val name = p.str("name")
            val parts = name.split(".")
            val appName = parts[0]
            val aspects = parts.drop(1)
            val nameHash = Destination.computeNameHash(appName, aspects)
            result("hash" to hexVal(nameHash))
        }

        "destination_hash" -> {
            val identityHash = p.hex("identity_hash")
            val appName = p.str("app_name")
            val aspects = p.stringArray("aspects")
            val nameHash = Destination.computeNameHash(appName, aspects)
            val combined = nameHash + identityHash
            val destHash = Hashes.truncatedHash(combined)
            result(
                "destination_hash" to hexVal(destHash),
                "name_hash" to hexVal(nameHash)
            )
        }

        // === 8. Packet ===

        "packet_hash" -> {
            val raw = p.hex("raw")
            val packet = Packet.unpack(raw) ?: throw IllegalArgumentException("Invalid packet")
            val hashablePart = packet.getHashablePart()
            val fullHash = Hashes.fullHash(hashablePart)
            val truncHash = fullHash.copyOf(16)
            result(
                "hash" to hexVal(fullHash),
                "truncated_hash" to hexVal(truncHash),
                "hashable_part" to hexVal(hashablePart)
            )
        }

        "packet_flags" -> {
            val ht = p.int("header_type")
            val cf = p.int("context_flag")
            val tt = p.int("transport_type")
            val dt = p.int("destination_type")
            val pt = p.int("packet_type")
            val flags = ((ht and 1) shl 6) or
                ((if (cf != 0) 1 else 0) shl 5) or
                ((tt and 1) shl 4) or
                ((dt and 3) shl 2) or
                (pt and 3)
            result("flags" to intVal(flags))
        }

        "packet_parse_flags" -> {
            val flags = p.int("flags")
            result(
                "header_type" to intVal((flags shr 6) and 1),
                "context_flag" to intVal((flags shr 5) and 1),
                "transport_type" to intVal((flags shr 4) and 1),
                "destination_type" to intVal((flags shr 2) and 3),
                "packet_type" to intVal(flags and 3)
            )
        }

        "packet_pack" -> {
            val ht = p.int("header_type")
            val cf = p.int("context_flag")
            val tt = p.int("transport_type")
            val dt = p.int("destination_type")
            val pt = p.int("packet_type")
            val hops = p.int("hops")
            val destHash = p.hex("destination_hash")
            val transportId = p.hexOpt("transport_id")
            val context = p.int("context")
            val data = p.hex("data")

            val packet = Packet.createRaw(
                destinationHash = destHash,
                data = data,
                packetType = PacketType.fromValue(pt),
                destinationType = DestinationType.fromValue(dt),
                context = PacketContext.fromValue(context),
                transportType = TransportType.fromValue(tt),
                headerType = HeaderType.fromValue(ht),
                transportId = transportId,
                contextFlag = ContextFlag.fromValue(cf),
                mtu = 65535 // Don't enforce MTU in bridge
            )
            packet.hops = hops
            val raw = packet.pack()
            result(
                "raw" to hexVal(raw),
                "header" to hexVal(raw.copyOf(2)),
                "size" to intVal(raw.size)
            )
        }

        "packet_unpack" -> {
            val raw = p.hex("raw")
            val packet = Packet.unpack(raw) ?: throw IllegalArgumentException("Invalid packet")
            val r = result(
                "header_type" to intVal(packet.headerType.value),
                "context_flag" to intVal(packet.contextFlag.value),
                "transport_type" to intVal(packet.transportType.value),
                "destination_type" to intVal(packet.destinationType.value),
                "packet_type" to intVal(packet.packetType.value),
                "hops" to intVal(packet.hops),
                "destination_hash" to hexVal(packet.destinationHash),
                "context" to intVal(packet.context.value),
                "data" to hexVal(packet.data)
            )
            packet.transportId?.let { r.add("transport_id", hexVal(it)) }
            r
        }

        "packet_parse_header" -> {
            val raw = p.hex("raw")
            if (raw.size < 2) throw IllegalArgumentException("Packet too short")
            val flags = raw[0].toInt() and 0xFF
            val hops = raw[1].toInt() and 0xFF
            val headerType = (flags shr 6) and 1
            val contextFlag = (flags shr 5) and 1
            val transportType = (flags shr 4) and 1
            val destinationType = (flags shr 2) and 3
            val packetType = flags and 3
            val hashLen = 16

            val transportId: ByteArray?
            val destHash: ByteArray
            val context: Int

            if (headerType == 0) {
                transportId = null
                destHash = raw.copyOfRange(2, minOf(2 + hashLen, raw.size))
                context = if (raw.size > 2 + hashLen) raw[2 + hashLen].toInt() and 0xFF else 0
            } else {
                transportId = raw.copyOfRange(2, minOf(2 + hashLen, raw.size))
                destHash = raw.copyOfRange(2 + hashLen, minOf(2 + 2 * hashLen, raw.size))
                context = if (raw.size > 2 + 2 * hashLen) raw[2 + 2 * hashLen].toInt() and 0xFF else 0
            }

            val r = result(
                "header_type" to intVal(headerType),
                "transport_type" to intVal(transportType),
                "destination_type" to intVal(destinationType),
                "packet_type" to intVal(packetType),
                "context_flag" to intVal(contextFlag),
                "hops" to intVal(hops),
                "destination_hash" to hexVal(destHash),
                "context" to intVal(context)
            )
            transportId?.let { r.add("transport_id", hexVal(it)) }
            r
        }

        // === 9. Framing ===

        "hdlc_escape" -> {
            val data = p.hex("data")
            result("escaped" to hexVal(HDLC.escape(data)))
        }

        "hdlc_frame" -> {
            val data = p.hex("data")
            result("framed" to hexVal(HDLC.frame(data)))
        }

        "kiss_escape" -> {
            val data = p.hex("data")
            result("escaped" to hexVal(KISS.escape(data)))
        }

        "kiss_frame" -> {
            val data = p.hex("data")
            result("framed" to hexVal(KISS.frame(data)))
        }

        // === 10. Announce ===

        "random_hash" -> {
            val randomBytes = p.hexOpt("random_bytes")
            val timestamp = p.intOpt("timestamp")
            val hash = ByteArray(10)
            if (randomBytes != null) {
                System.arraycopy(randomBytes, 0, hash, 0, minOf(5, randomBytes.size))
            } else {
                val rb = crypto.randomBytes(5)
                System.arraycopy(rb, 0, hash, 0, 5)
            }
            val ts: Long = timestamp?.toLong() ?: (System.currentTimeMillis() / 1000)
            hash[5] = ((ts shr 32) and 0xFF).toByte()
            hash[6] = ((ts shr 24) and 0xFF).toByte()
            hash[7] = ((ts shr 16) and 0xFF).toByte()
            hash[8] = ((ts shr 8) and 0xFF).toByte()
            hash[9] = (ts and 0xFF).toByte()
            val tsBytes = hash.copyOfRange(5, 10)
            result(
                "random_hash" to hexVal(hash),
                "random_bytes" to hexVal(hash.copyOf(5)),
                "timestamp" to intVal(ts.toInt()),
                "timestamp_bytes" to hexVal(tsBytes)
            )
        }

        "announce_pack" -> {
            val publicKey = p.hex("public_key")
            val nameHash = p.hex("name_hash")
            val randomHash = p.hex("random_hash")
            val ratchet = p.hexOpt("ratchet")
            val signature = p.hex("signature")
            val appData = p.hexOpt("app_data")
            val payload = ByteArrayOutputStream()
            payload.write(publicKey)
            payload.write(nameHash)
            payload.write(randomHash)
            ratchet?.let { payload.write(it) }
            payload.write(signature)
            appData?.let { payload.write(it) }
            val data = payload.toByteArray()
            result(
                "announce_data" to hexVal(data),
                "size" to intVal(data.size),
                "has_ratchet" to boolVal(ratchet != null)
            )
        }

        "announce_unpack" -> {
            val data = p.hex("announce_data")
            val hasRatchet = p.boolOpt("has_ratchet") ?: false
            var offset = 0
            val publicKey = data.copyOfRange(offset, offset + 64); offset += 64
            val nameHash = data.copyOfRange(offset, offset + 10); offset += 10
            val randomHash = data.copyOfRange(offset, offset + 10); offset += 10
            var ratchet: ByteArray? = null
            if (hasRatchet) {
                ratchet = data.copyOfRange(offset, offset + 32); offset += 32
            }
            val signature = data.copyOfRange(offset, offset + 64); offset += 64
            val appData = if (offset < data.size) data.copyOfRange(offset, data.size) else null
            val r = result(
                "public_key" to hexVal(publicKey),
                "name_hash" to hexVal(nameHash),
                "random_hash" to hexVal(randomHash),
                "signature" to hexVal(signature),
                "has_ratchet" to boolVal(hasRatchet)
            )
            ratchet?.let { r.add("ratchet", hexVal(it)) }
            if (appData != null && appData.isNotEmpty()) r.add("app_data", hexVal(appData))
            r
        }

        "announce_sign" -> {
            val privBytes = p.hex("private_key")
            val destHash = p.hex("destination_hash")
            val publicKey = p.hex("public_key")
            val nameHash = p.hex("name_hash")
            val randomHash = p.hex("random_hash")
            val ratchet = p.hexOpt("ratchet")
            val appData = p.hexOpt("app_data")
            val signedData = ByteArrayOutputStream()
            signedData.write(destHash)
            signedData.write(publicKey)
            signedData.write(nameHash)
            signedData.write(randomHash)
            ratchet?.let { signedData.write(it) }
            appData?.let { signedData.write(it) }
            val message = signedData.toByteArray()
            val sigPriv = privBytes.copyOfRange(32, 64)
            val sig = crypto.ed25519Sign(sigPriv, message)
            result("signature" to hexVal(sig), "signed_data" to hexVal(message))
        }

        "announce_verify" -> {
            val pubBytes = p.hex("public_key")
            val announceData = p.hex("announce_data")
            val destHash = p.hex("destination_hash")
            val hasRatchet = p.boolOpt("has_ratchet") ?: false
            val keySize = 64; val nameHashLen = 10; val randomHashLen = 10
            val ratchetSize = if (hasRatchet) 32 else 0; val sigLen = 64
            val publicKey = announceData.copyOfRange(0, keySize)
            val nameHash = announceData.copyOfRange(keySize, keySize + nameHashLen)
            val randomHash = announceData.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + randomHashLen)
            val sigStart = keySize + nameHashLen + randomHashLen + ratchetSize
            val signature = announceData.copyOfRange(sigStart, sigStart + sigLen)
            val appData = if (announceData.size > sigStart + sigLen) announceData.copyOfRange(sigStart + sigLen, announceData.size) else ByteArray(0)
            var ratchetData = ByteArray(0)
            if (hasRatchet) {
                ratchetData = announceData.copyOfRange(keySize + nameHashLen + randomHashLen, keySize + nameHashLen + randomHashLen + 32)
            }
            val signedData = ByteArrayOutputStream()
            signedData.write(destHash)
            signedData.write(publicKey)
            signedData.write(nameHash)
            signedData.write(randomHash)
            if (hasRatchet) signedData.write(ratchetData)
            if (appData.isNotEmpty()) signedData.write(appData)
            val sigPub = pubBytes.copyOfRange(32, 64)
            val sigValid = crypto.ed25519Verify(sigPub, signedData.toByteArray(), signature)
            val identityHash = Hashes.truncatedHash(publicKey)
            val expectedDestHash = Hashes.truncatedHash(nameHash + identityHash)
            val destValid = destHash.contentEquals(expectedDestHash)
            result(
                "valid" to boolVal(sigValid && destValid),
                "signature_valid" to boolVal(sigValid),
                "dest_hash_valid" to boolVal(destValid)
            )
        }

        // === 11. Link ===

        "link_derive_key" -> {
            val sharedKey = p.hex("shared_key")
            val linkId = p.hex("link_id")
            val derived = crypto.hkdf(64, sharedKey, linkId, null)
            result(
                "derived_key" to hexVal(derived),
                "encryption_key" to hexVal(derived.copyOfRange(32, 64)),
                "signing_key" to hexVal(derived.copyOfRange(0, 32))
            )
        }

        "link_encrypt" -> {
            val derivedKey = p.hex("derived_key")
            val plaintext = p.hex("plaintext")
            val token = Token(derivedKey, crypto)
            val encrypted = if (p.hexOpt("iv") != null) {
                token.encryptWithIv(plaintext, p.hex("iv"))
            } else {
                token.encrypt(plaintext)
            }
            result("ciphertext" to hexVal(encrypted))
        }

        "link_decrypt" -> {
            val derivedKey = p.hex("derived_key")
            val ciphertext = p.hex("ciphertext")
            val token = Token(derivedKey, crypto)
            val plaintext = token.decrypt(ciphertext)
            result("plaintext" to hexVal(plaintext))
        }

        "link_prove" -> {
            val identityPriv = p.hex("identity_private")
            val linkId = p.hex("link_id")
            val receiverPub = p.hex("receiver_pub")
            val receiverSigPub = p.hex("receiver_sig_pub")
            val signalling = p.hexOpt("signalling_bytes") ?: defaultMtuSignaling()
            val signedData = linkId + receiverPub + receiverSigPub + signalling
            val sigPriv = identityPriv.copyOfRange(32, 64)
            val sig = crypto.ed25519Sign(sigPriv, signedData)
            result("signature" to hexVal(sig), "signed_data" to hexVal(signedData))
        }

        "link_verify_proof" -> {
            val identityPub = p.hex("identity_public")
            val linkId = p.hex("link_id")
            val receiverPub = p.hex("receiver_pub")
            val receiverSigPub = p.hex("receiver_sig_pub")
            val signature = p.hex("signature")
            val signalling = p.hexOpt("signalling_bytes") ?: defaultMtuSignaling()
            val signedData = linkId + receiverPub + receiverSigPub + signalling
            val sigPub = identityPub.copyOfRange(32, 64)
            val valid = crypto.ed25519Verify(sigPub, signedData, signature)
            result("valid" to boolVal(valid))
        }

        "link_id_from_packet" -> {
            val raw = p.hex("raw")
            val packet = Packet.unpack(raw) ?: throw IllegalArgumentException("Invalid packet")
            var hashable = packet.getHashablePart()
            // If data > 64 bytes (ECPUBSIZE), trim signaling (3 bytes)
            if (packet.data.size > 64) {
                val diff = packet.data.size - 64
                if (hashable.size > diff) {
                    hashable = hashable.copyOfRange(0, hashable.size - diff)
                }
            }
            val linkId = Hashes.truncatedHash(hashable)
            result("link_id" to hexVal(linkId), "hashable_part" to hexVal(hashable))
        }

        "link_signalling_bytes" -> {
            val mtu = p.int("mtu")
            val mode = p.intOpt("mode") ?: 1
            val sigBytes = encodeSignaling(mtu, mode)
            val (decodedMtu, _) = decodeSignaling(sigBytes)
            result("signalling_bytes" to hexVal(sigBytes), "decoded_mtu" to intVal(decodedMtu))
        }

        "link_parse_signalling" -> {
            val sigBytes = p.hex("signalling_bytes")
            val (mtu, mode) = decodeSignaling(sigBytes)
            result("mtu" to intVal(mtu), "mode" to intVal(mode))
        }

        "link_request_pack" -> {
            val timestamp = p.double("timestamp")
            val pathHash = p.hex("path_hash")
            val dataHex = p.hexOpt("data")
            val elements: List<Any?> = listOf(timestamp, pathHash, dataHex)
            val packed = packMsgPackArray(elements)
            result("packed" to hexVal(packed))
        }

        "link_request_unpack" -> {
            val packed = p.hex("packed")
            val value = unpackMsgPack(packed)
            if (value !is MsgPackVal.ArrayVal || value.items.size < 3) {
                throw IllegalArgumentException("Expected array with 3 elements")
            }
            val timestamp = msgPackValToDouble(value.items[0])
            val pathHash = msgPackValToBinary(value.items[1])
            val r = result(
                "timestamp" to doubleVal(timestamp),
                "path_hash" to hexVal(pathHash)
            )
            when (val d = value.items[2]) {
                is MsgPackVal.Binary -> r.add("data", hexVal(d.data))
                else -> r.add("data", JsonNull.INSTANCE)
            }
            r
        }

        "link_rtt_pack" -> {
            val rtt = p.double("rtt")
            val packed = packMsgPackDouble(rtt)
            result("packed" to hexVal(packed))
        }

        "link_rtt_unpack" -> {
            val packed = p.hex("packed")
            val value = unpackMsgPack(packed)
            val rtt = msgPackValToDouble(value)
            result("rtt" to doubleVal(rtt))
        }

        // === 12. Resource ===

        "resource_adv_pack" -> {
            val transferSize = p.int("transfer_size")
            val dataSize = p.int("data_size")
            val numParts = p.int("num_parts")
            val resourceHash = p.hex("resource_hash")
            val randomHash = p.hex("random_hash")
            val originalHash = p.hexOpt("original_hash") ?: resourceHash
            val segmentIndex = p.int("segment_index")
            val totalSegments = p.int("total_segments")
            val requestId = p.hexOpt("request_id")
            val flags = p.int("flags")
            val hashmap = p.hex("hashmap")
            val packed = packResourceAdv(
                transferSize, dataSize, numParts, resourceHash, randomHash,
                originalHash, segmentIndex, totalSegments, requestId, flags, hashmap
            )
            result("packed" to hexVal(packed), "size" to intVal(packed.size))
        }

        "resource_adv_unpack" -> {
            val packed = p.hex("packed")
            val adv = unpackResourceAdv(packed)
            val r = result(
                "transfer_size" to intVal(adv.transferSize),
                "data_size" to intVal(adv.dataSize),
                "num_parts" to intVal(adv.numParts),
                "resource_hash" to hexVal(adv.hash),
                "random_hash" to hexVal(adv.randomHash),
                "original_hash" to hexVal(adv.originalHash),
                "segment_index" to intVal(adv.segmentIndex),
                "total_segments" to intVal(adv.totalSegments),
                "flags" to intVal(adv.flags),
                "hashmap" to hexVal(adv.hashmap),
                "encrypted" to boolVal(adv.encrypted),
                "compressed" to boolVal(adv.compressed),
                "split" to boolVal(adv.split),
                "is_request" to boolVal(adv.isRequest),
                "is_response" to boolVal(adv.isResponse),
                "has_metadata" to boolVal(adv.hasMetadata)
            )
            adv.requestId?.let { r.add("request_id", hexVal(it)) }
            r
        }

        "resource_hash" -> {
            val data = p.hex("data")
            val randomHash = p.hex("random_hash")
            val combined = randomHash + data
            val fullHash = Hashes.fullHash(combined)
            result(
                "hash" to hexVal(fullHash.copyOf(16)),
                "full_hash" to hexVal(fullHash)
            )
        }

        "resource_flags" -> {
            val mode = p.str("mode")
            if (mode == "encode") {
                val encrypted = p.boolOpt("encrypted") ?: false
                val compressed = p.boolOpt("compressed") ?: false
                val split = p.boolOpt("split") ?: false
                val isRequest = p.boolOpt("is_request") ?: false
                val isResponse = p.boolOpt("is_response") ?: false
                val hasMetadata = p.boolOpt("has_metadata") ?: false
                var flags = 0
                if (encrypted) flags = flags or 0x01
                if (compressed) flags = flags or 0x02
                if (split) flags = flags or 0x04
                if (isRequest) flags = flags or 0x08
                if (isResponse) flags = flags or 0x10
                if (hasMetadata) flags = flags or 0x20
                result("flags" to intVal(flags))
            } else {
                val flagsVal = p.int("flags")
                result(
                    "encrypted" to boolVal((flagsVal and 0x01) == 0x01),
                    "compressed" to boolVal(((flagsVal shr 1) and 0x01) == 0x01),
                    "split" to boolVal(((flagsVal shr 2) and 0x01) == 0x01),
                    "is_request" to boolVal(((flagsVal shr 3) and 0x01) == 0x01),
                    "is_response" to boolVal(((flagsVal shr 4) and 0x01) == 0x01),
                    "has_metadata" to boolVal(((flagsVal shr 5) and 0x01) == 0x01)
                )
            }
        }

        "resource_map_hash" -> {
            val partData = p.hex("part_data")
            val randomHash = p.hex("random_hash")
            val mapHash = Hashes.fullHash(partData + randomHash).copyOf(ResourceConstants.MAPHASH_LEN)
            result("map_hash" to hexVal(mapHash))
        }

        "resource_build_hashmap" -> {
            val partsHex = p.stringArray("parts")
            val randomHash = p.hex("random_hash")
            val hashmap = ByteArrayOutputStream()
            for (partHex in partsHex) {
                val partData = partHex.fromHex()
                val hash = Hashes.fullHash(partData + randomHash).copyOf(ResourceConstants.MAPHASH_LEN)
                hashmap.write(hash)
            }
            result(
                "hashmap" to hexVal(hashmap.toByteArray()),
                "num_parts" to intVal(partsHex.size)
            )
        }

        "resource_proof" -> {
            val data = p.hex("data")
            val resourceHash = p.hex("resource_hash")
            val fullHash = Hashes.fullHash(data + resourceHash)
            val proof = fullHash.copyOf(16)
            result("proof" to hexVal(proof))
        }

        // === 13. Ratchet ===

        "ratchet_id" -> {
            val ratchetPub = p.hex("ratchet_public")
            // Ratchet ID = SHA256[:10] (10 bytes, NAME_HASH_LENGTH)
            val fullHash = Hashes.fullHash(ratchetPub)
            val ratchetId = fullHash.copyOf(10)
            result("ratchet_id" to hexVal(ratchetId))
        }

        "ratchet_public_from_private" -> {
            val ratchetPriv = p.hex("ratchet_private")
            val pub = crypto.x25519PublicFromPrivate(ratchetPriv)
            result("ratchet_public" to hexVal(pub))
        }

        "ratchet_derive_key" -> {
            val ephPriv = p.hex("ephemeral_private")
            val ratchetPub = p.hex("ratchet_public")
            val identityHash = p.hex("identity_hash")
            val shared = crypto.x25519Exchange(ephPriv, ratchetPub)
            val derived = crypto.hkdf(64, shared, identityHash, null)
            result("shared_key" to hexVal(shared), "derived_key" to hexVal(derived))
        }

        "ratchet_encrypt" -> {
            val ratchetPub = p.hex("ratchet_public")
            val identityHash = p.hex("identity_hash")
            val plaintext = p.hex("plaintext")
            // Generate ephemeral key, do DH, HKDF, token encrypt
            val ephKp = crypto.generateX25519KeyPair()
            val shared = crypto.x25519Exchange(ephKp.privateKey, ratchetPub)
            val derived = crypto.hkdf(64, shared, identityHash, null)
            val token = Token(derived, crypto)
            val encrypted = token.encrypt(plaintext)
            val ciphertext = ephKp.publicKey + encrypted
            result("ciphertext" to hexVal(ciphertext))
        }

        "ratchet_decrypt" -> {
            val ratchetPriv = p.hex("ratchet_private")
            val identityHash = p.hex("identity_hash")
            val ciphertext = p.hex("ciphertext")
            val ephPubBytes = ciphertext.copyOfRange(0, 32)
            val tokenData = ciphertext.copyOfRange(32, ciphertext.size)
            val shared = crypto.x25519Exchange(ratchetPriv, ephPubBytes)
            val derived = crypto.hkdf(64, shared, identityHash, null)
            val token = Token(derived, crypto)
            val plaintext = token.decrypt(tokenData)
            result("plaintext" to hexVal(plaintext))
        }

        "ratchet_extract_from_announce" -> {
            val data = p.hex("announce_data")
            val hasRatchet = data.size >= 180
            if (hasRatchet) {
                val ratchetStart = 64 + 10 + 10
                val ratchet = data.copyOfRange(ratchetStart, ratchetStart + 32)
                val ratchetId = Hashes.fullHash(ratchet).copyOf(10)
                result(
                    "has_ratchet" to boolVal(true),
                    "ratchet" to hexVal(ratchet),
                    "ratchet_id" to hexVal(ratchetId)
                )
            } else {
                result("has_ratchet" to boolVal(false))
            }
        }

        // === 14. Channel ===

        "envelope_pack" -> {
            val msgType = p.int("msgtype")
            val sequence = p.intOpt("sequence") ?: 0
            val envelopeData = p.hex("data")
            val d = ByteArray(6 + envelopeData.size)
            d[0] = (msgType shr 8).toByte()
            d[1] = (msgType and 0xFF).toByte()
            d[2] = (sequence shr 8).toByte()
            d[3] = (sequence and 0xFF).toByte()
            val len = envelopeData.size
            d[4] = (len shr 8).toByte()
            d[5] = (len and 0xFF).toByte()
            System.arraycopy(envelopeData, 0, d, 6, envelopeData.size)
            result(
                "envelope" to hexVal(d),
                "msgtype" to intVal(msgType),
                "sequence" to intVal(sequence),
                "length" to intVal(len)
            )
        }

        "envelope_unpack" -> {
            val data = p.hex("envelope")
            if (data.size < 6) throw IllegalArgumentException("Envelope too short")
            val msgType = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val len = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val payload = if (data.size >= 6 + len) data.copyOfRange(6, 6 + len) else data.copyOfRange(6, data.size)
            result(
                "msgtype" to intVal(msgType),
                "sequence" to intVal(sequence),
                "length" to intVal(len),
                "data" to hexVal(payload)
            )
        }

        "stream_msg_pack" -> {
            val streamId = p.int("stream_id")
            val data = p.hex("data")
            val eof = p.boolOpt("eof") ?: false
            val compressed = p.boolOpt("compressed") ?: false
            // Stream message format: [header:2BE][data]
            // Header bits: bit 15 = EOF, bit 14 = compressed, bits 13-0 = stream_id
            var headerVal = streamId and 0x3FFF
            if (eof) headerVal = headerVal or 0x8000
            if (compressed) headerVal = headerVal or 0x4000
            val msg = ByteArray(2 + data.size)
            msg[0] = (headerVal shr 8).toByte()
            msg[1] = (headerVal and 0xFF).toByte()
            System.arraycopy(data, 0, msg, 2, data.size)
            result(
                "message" to hexVal(msg),
                "stream_id" to intVal(streamId),
                "eof" to boolVal(eof),
                "compressed" to boolVal(compressed)
            )
        }

        "stream_msg_unpack" -> {
            val packed = p.hex("message")
            if (packed.size < 2) throw IllegalArgumentException("Stream message too short")
            val headerVal = ((packed[0].toInt() and 0xFF) shl 8) or (packed[1].toInt() and 0xFF)
            val eof = (headerVal and 0x8000) != 0
            val compressed = (headerVal and 0x4000) != 0
            val streamId = headerVal and 0x3FFF
            val data = packed.copyOfRange(2, packed.size)
            result(
                "stream_id" to intVal(streamId),
                "eof" to boolVal(eof),
                "compressed" to boolVal(compressed),
                "data" to hexVal(data)
            )
        }

        // === 15. Transport ===

        "path_entry_serialize" -> {
            val destHash = p.hex("destination_hash")
            val timestamp = p.double("timestamp")
            val receivedFrom = p.hex("received_from")
            val hops = p.int("hops")
            val expires = p.double("expires")
            val randomBlobsHex = p.stringArray("random_blobs")
            val interfaceHash = p.hex("interface_hash")
            val packetHash = p.hex("packet_hash")
            val randomBlobs = randomBlobsHex.map { it.fromHex() }
            val elements: List<Any?> = listOf(
                destHash, timestamp, receivedFrom, hops.toLong(),
                expires, randomBlobs, interfaceHash, packetHash
            )
            val packed = packMsgPackArray(elements)
            result("serialized" to hexVal(packed))
        }

        "path_entry_deserialize" -> {
            val serialized = p.hex("serialized")
            val value = unpackMsgPack(serialized)
            if (value !is MsgPackVal.ArrayVal || value.items.size < 8) {
                throw IllegalArgumentException("Expected array with 8 elements")
            }
            val items = value.items
            val randomBlobs = if (items[5] is MsgPackVal.ArrayVal) {
                val arr = JsonArray()
                (items[5] as MsgPackVal.ArrayVal).items.forEach { blob ->
                    arr.add(JsonPrimitive(msgPackValToBinary(blob).toHex()))
                }
                arr
            } else JsonArray()
            result(
                "destination_hash" to hexVal(msgPackValToBinary(items[0])),
                "timestamp" to doubleVal(msgPackValToDouble(items[1])),
                "received_from" to hexVal(msgPackValToBinary(items[2])),
                "hops" to intVal(msgPackValToInt(items[3])),
                "expires" to doubleVal(msgPackValToDouble(items[4])),
                "random_blobs" to randomBlobs,
                "interface_hash" to hexVal(msgPackValToBinary(items[6])),
                "packet_hash" to hexVal(msgPackValToBinary(items[7]))
            )
        }

        "path_request_pack" -> {
            val destHash = p.hex("destination_hash")
            val pathHash = p.hexOpt("path_hash")
            val data = if (pathHash != null) destHash + pathHash else destHash
            result("data" to hexVal(data))
        }

        "path_request_unpack" -> {
            val data = p.hex("data")
            val destHash = data.copyOf(16)
            val r = result("destination_hash" to hexVal(destHash))
            if (data.size > 16) {
                r.add("path_hash", hexVal(data.copyOfRange(16, data.size)))
            }
            r
        }

        "packet_hashlist_pack" -> {
            val hashesHex = p.stringArray("hashes")
            val hashes = hashesHex.map { it.fromHex() }
            val packed = packMsgPackArray(hashes)
            result("serialized" to hexVal(packed), "count" to intVal(hashesHex.size))
        }

        "packet_hashlist_unpack" -> {
            val packed = p.hex("serialized")
            val value = unpackMsgPack(packed)
            if (value !is MsgPackVal.ArrayVal) throw IllegalArgumentException("Expected array")
            val hashes = JsonArray()
            for (item in value.items) {
                hashes.add(JsonPrimitive(msgPackValToBinary(item).toHex()))
            }
            result("hashes" to hashes, "count" to intVal(value.items.size))
        }

        // === 16. IFAC ===

        "ifac_derive_key" -> {
            val ifacOrigin = p.hex("ifac_origin")
            val ifacSalt = "adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8".fromHex()
            val ifacKey = crypto.hkdf(64, ifacOrigin, ifacSalt, null)
            result("ifac_key" to hexVal(ifacKey), "ifac_salt" to hexVal(ifacSalt))
        }

        "ifac_compute" -> {
            val ifacKey = p.hex("ifac_key")
            val packetData = p.hex("packet_data")
            val ifacSize = p.intOpt("ifac_size") ?: 16
            val ed25519Seed = ifacKey.copyOfRange(32, 64)
            val signature = crypto.ed25519Sign(ed25519Seed, packetData)
            val ifac = signature.copyOfRange(signature.size - ifacSize, signature.size)
            result("ifac" to hexVal(ifac), "signature" to hexVal(signature))
        }

        "ifac_verify" -> {
            val ifacKey = p.hex("ifac_key")
            val packetData = p.hex("packet_data")
            val expectedIfac = p.hex("expected_ifac")
            val ifacSize = expectedIfac.size
            val ed25519Seed = ifacKey.copyOfRange(32, 64)
            val signature = crypto.ed25519Sign(ed25519Seed, packetData)
            val computedIfac = signature.copyOfRange(signature.size - ifacSize, signature.size)
            val valid = computedIfac.contentEquals(expectedIfac)
            result("valid" to boolVal(valid), "computed_ifac" to hexVal(computedIfac))
        }

        "ifac_mask_packet" -> {
            val ifacKey = p.hex("ifac_key")
            val raw = p.hex("packet_data")
            val ifacSize = p.intOpt("ifac_size") ?: 16

            // 1. Compute IFAC
            val ed25519Seed = ifacKey.copyOfRange(32, 64)
            val signature = crypto.ed25519Sign(ed25519Seed, raw)
            val ifac = signature.copyOfRange(signature.size - ifacSize, signature.size)

            // 2. Set IFAC flag and insert IFAC bytes after 2-byte header
            val newRaw = ByteArray(raw.size + ifacSize)
            newRaw[0] = (raw[0].toInt() or 0x80).toByte()
            newRaw[1] = raw[1]
            System.arraycopy(ifac, 0, newRaw, 2, ifacSize)
            System.arraycopy(raw, 2, newRaw, 2 + ifacSize, raw.size - 2)

            // 3. Generate XOR mask
            val mask = crypto.hkdf(newRaw.size, ifac, ifacKey, null)

            // 4. Apply mask: XOR header and payload, leave IFAC bytes unmasked
            val masked = ByteArray(newRaw.size)
            for (i in newRaw.indices) {
                masked[i] = when {
                    i == 0 -> ((newRaw[i].toInt() xor mask[i].toInt()) or 0x80).toByte()
                    i == 1 || i > ifacSize + 1 -> (newRaw[i].toInt() xor mask[i].toInt()).toByte()
                    else -> newRaw[i] // IFAC bytes: no mask
                }
            }

            result("masked_packet" to hexVal(masked), "ifac" to hexVal(ifac))
        }

        "ifac_unmask_packet" -> {
            val ifacKey = p.hex("ifac_key")
            val masked = p.hex("masked_packet")
            val ifacSize = p.intOpt("ifac_size") ?: 16

            // 1. Check IFAC flag
            if (masked[0].toInt() and 0x80 != 0x80 || masked.size <= 2 + ifacSize) {
                result("valid" to boolVal(false))
            } else {
                // 2. Extract IFAC (unmasked bytes 2..2+ifacSize)
                val ifac = masked.copyOfRange(2, 2 + ifacSize)

                // 3. Generate XOR mask
                val mask = crypto.hkdf(masked.size, ifac, ifacKey, null)

                // 4. Unmask header and payload
                val unmasked = ByteArray(masked.size)
                for (i in masked.indices) {
                    unmasked[i] = when {
                        i <= 1 || i > ifacSize + 1 -> (masked[i].toInt() xor mask[i].toInt()).toByte()
                        else -> masked[i]
                    }
                }

                // 5. Clear IFAC flag and remove IFAC bytes
                val original = ByteArray(unmasked.size - ifacSize)
                original[0] = (unmasked[0].toInt() and 0x7F).toByte()
                original[1] = unmasked[1]
                System.arraycopy(unmasked, 2 + ifacSize, original, 2, unmasked.size - 2 - ifacSize)

                // 6. Validate: recompute IFAC over unmasked packet
                val ed25519Seed = ifacKey.copyOfRange(32, 64)
                val signature = crypto.ed25519Sign(ed25519Seed, original)
                val expectedIfac = signature.copyOfRange(signature.size - ifacSize, signature.size)
                val valid = ifac.contentEquals(expectedIfac)

                if (valid) {
                    result("valid" to boolVal(true), "packet_data" to hexVal(original), "ifac" to hexVal(ifac))
                } else {
                    result("valid" to boolVal(false), "ifac" to hexVal(ifac))
                }
            }
        }

        // === 17. Compression ===

        "bz2_compress" -> {
            val data = p.hex("data")
            val baos = ByteArrayOutputStream()
            BZip2CompressorOutputStream(baos, 9).use { it.write(data) }
            result("compressed" to hexVal(baos.toByteArray()))
        }

        "bz2_decompress" -> {
            val compressed = p.hex("compressed")
            val bais = ByteArrayInputStream(compressed)
            val decompressed = BZip2CompressorInputStream(bais).use { it.readBytes() }
            result("decompressed" to hexVal(decompressed), "size" to intVal(decompressed.size))
        }

        // === 18. LXMF ===

        "lxmf_pack" -> {
            val destHash = p.hex("destination_hash")
            val srcHash = p.hex("source_hash")
            val timestamp = p.double("timestamp")
            val title = p.strOpt("title") ?: ""
            val content = p.strOpt("content") ?: ""
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val elements: List<Any?> = listOf(timestamp, titleBytes, contentBytes, emptyMap<Any, Any>())
            val packedPayload = packMsgPackArray(elements)
            val hashedPart = destHash + srcHash + packedPayload
            val msgHash = Hashes.fullHash(hashedPart)
            val signedPart = hashedPart + msgHash
            result(
                "packed_payload" to hexVal(packedPayload),
                "hashed_part" to hexVal(hashedPart),
                "message_hash" to hexVal(msgHash),
                "signed_part" to hexVal(signedPart)
            )
        }

        "lxmf_unpack" -> {
            val lxmfBytes = p.hex("lxmf_bytes")
            val DEST_LEN = 16; val SIG_LEN = 64
            if (lxmfBytes.size < 2 * DEST_LEN + SIG_LEN) throw IllegalArgumentException("LXMF data too short")
            val destHash = lxmfBytes.copyOfRange(0, DEST_LEN)
            val srcHash = lxmfBytes.copyOfRange(DEST_LEN, 2 * DEST_LEN)
            val signature = lxmfBytes.copyOfRange(2 * DEST_LEN, 2 * DEST_LEN + SIG_LEN)
            val packedPayload = lxmfBytes.copyOfRange(2 * DEST_LEN + SIG_LEN, lxmfBytes.size)
            val value = unpackMsgPack(packedPayload)
            if (value !is MsgPackVal.ArrayVal || value.items.size < 3) {
                throw IllegalArgumentException("Invalid LXMF msgpack array")
            }
            val items = value.items.toMutableList()
            // Extract stamp if present (5th element)
            var stamp: ByteArray? = null
            if (items.size > 4 && items[4] is MsgPackVal.Binary) {
                stamp = (items[4] as MsgPackVal.Binary).data
                items.removeAt(4)
            }
            val ts = msgPackValToDouble(items[0])
            val titleData = msgPackValToBinary(items[1])
            val contentData = msgPackValToBinary(items[2])
            val titleStr = String(titleData, Charsets.UTF_8)
            val contentStr = String(contentData, Charsets.UTF_8)
            // Recompute hash without stamp
            val repackedPayload = if (stamp != null) {
                packMsgPackArray(items.map { mpValToNative(it) })
            } else packedPayload
            val hashedPart = destHash + srcHash + repackedPayload
            val msgHash = Hashes.fullHash(hashedPart)
            val r = result(
                "destination_hash" to hexVal(destHash),
                "source_hash" to hexVal(srcHash),
                "signature" to hexVal(signature),
                "timestamp" to doubleVal(ts),
                "title" to strVal(titleStr),
                "content" to strVal(contentStr),
                "message_hash" to hexVal(msgHash)
            )
            if (stamp != null) r.add("stamp", hexVal(stamp)) else r.add("stamp", JsonNull.INSTANCE)
            r
        }

        "lxmf_hash" -> {
            val destHash = p.hex("destination_hash")
            val srcHash = p.hex("source_hash")
            val timestamp = p.double("timestamp")
            val title = p.strOpt("title") ?: ""
            val content = p.strOpt("content") ?: ""
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val elements: List<Any?> = listOf(timestamp, titleBytes, contentBytes, emptyMap<Any, Any>())
            val packed = packMsgPackArray(elements)
            val hashInput = destHash + srcHash + packed
            val msgHash = Hashes.fullHash(hashInput)
            result("message_hash" to hexVal(msgHash))
        }

        "lxmf_stamp_workblock" -> {
            val messageId = p.hex("message_id")
            val expandRounds = p.intOpt("expand_rounds") ?: 3000
            val workblock = ByteArrayOutputStream()
            for (n in 0 until expandRounds) {
                val nPacked = packMsgPackInt(n)
                val salt = Hashes.fullHash(messageId + nPacked)
                val block = crypto.hkdf(256, messageId, salt, null)
                workblock.write(block)
            }
            val wb = workblock.toByteArray()
            result("workblock" to hexVal(wb), "size" to intVal(wb.size))
        }

        "lxmf_stamp_generate" -> {
            val messageId = p.hex("message_id")
            val stampCost = p.int("stamp_cost")
            val expandRounds = p.intOpt("expand_rounds") ?: 3000
            // Generate workblock
            val workblockStream = ByteArrayOutputStream()
            for (n in 0 until expandRounds) {
                val nPacked = packMsgPackInt(n)
                val salt = Hashes.fullHash(messageId + nPacked)
                val block = crypto.hkdf(256, messageId, salt, null)
                workblockStream.write(block)
            }
            val workblock = workblockStream.toByteArray()
            // Brute-force stamp
            var stamp = ByteArray(32)
            var value = 0
            val random = java.security.SecureRandom()
            while (true) {
                random.nextBytes(stamp)
                val hash = Hashes.fullHash(workblock + stamp)
                val zeros = countLeadingZeroBits(hash)
                if (zeros >= stampCost) {
                    value = zeros
                    break
                }
            }
            result("stamp" to hexVal(stamp), "value" to intVal(value))
        }

        "lxmf_stamp_valid" -> {
            val stamp = p.hex("stamp")
            val targetCost = p.int("target_cost")
            val workblock = p.hex("workblock")
            val hash = Hashes.fullHash(workblock + stamp)
            val zeros = countLeadingZeroBits(hash)
            val valid = zeros >= targetCost
            result("valid" to boolVal(valid), "value" to intVal(if (valid) zeros else 0))
        }

        else -> {
            if (command.startsWith("behavioral_")) {
                handleBehavioralCommand(command, p)
            } else {
                throw IllegalArgumentException("Unknown command: $command")
            }
        }
    }
}

// --- Link signaling helpers ---

fun defaultMtuSignaling(): ByteArray = encodeSignaling(RnsConstants.MTU, LinkConstants.MODE_DEFAULT)

fun encodeSignaling(mtu: Int, mode: Int): ByteArray {
    val value = (mtu and LinkConstants.MTU_BYTEMASK) +
        ((((mode shl 5) and LinkConstants.MODE_BYTEMASK) shl 16))
    return byteArrayOf(
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

fun decodeSignaling(bytes: ByteArray): Pair<Int, Int> {
    if (bytes.size < 3) return Pair(RnsConstants.MTU, LinkConstants.MODE_DEFAULT)
    val value = ((bytes[0].toInt() and 0xFF) shl 16) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        (bytes[2].toInt() and 0xFF)
    val mtu = value and LinkConstants.MTU_BYTEMASK
    val mode = (value shr 21) and 0x07
    return Pair(mtu, mode)
}

// --- MsgPackVal to native for repacking ---

fun mpValToNative(v: MsgPackVal): Any? = when (v) {
    is MsgPackVal.Binary -> v.data
    is MsgPackVal.Str -> v.value
    is MsgPackVal.IntVal -> v.value
    is MsgPackVal.FloatVal -> v.value
    is MsgPackVal.BoolVal -> v.value
    is MsgPackVal.ArrayVal -> v.items.map { mpValToNative(it) }
    is MsgPackVal.MapVal -> v.entries.map { (k, va) -> mpValToNative(k) to mpValToNative(va) }.toMap()
    MsgPackVal.Nil -> null
}
