package network.reticulum.lxmf

import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

/**
 * LXMF Message class.
 *
 * Represents a message in the LXMF format with support for packing/unpacking
 * that is byte-perfect compatible with Python LXMF.
 *
 * Wire format:
 * ```
 * [0:16]   Destination hash (16 bytes)
 * [16:32]  Source hash (16 bytes)
 * [32:96]  Ed25519 signature (64 bytes)
 * [96:]    Msgpack payload
 * ```
 *
 * Payload structure (msgpack list):
 * ```
 * [0] timestamp  - float64 (UNIX epoch seconds)
 * [1] title      - bytes (UTF-8)
 * [2] content    - bytes (UTF-8)
 * [3] fields     - dict (extensible)
 * [4] stamp      - bytes (optional, 32 bytes proof-of-work)
 * ```
 */
class LXMessage private constructor(
    /** Destination for this message */
    val destination: Destination?,

    /** Source destination (sender) */
    val source: Destination?,

    /** Destination hash (always available even if destination is null) */
    val destinationHash: ByteArray,

    /** Source hash (always available even if source is null) */
    val sourceHash: ByteArray,

    /** Message title */
    var title: String,

    /** Message content */
    var content: String,

    /** Extended fields dictionary */
    val fields: MutableMap<Int, Any> = mutableMapOf(),

    /** Desired delivery method */
    var desiredMethod: DeliveryMethod? = null
) {
    // ===== Message Identification =====

    /** Full message hash (32 bytes SHA-256) */
    var hash: ByteArray? = null
        private set

    /** Message ID (same as hash) */
    val messageId: ByteArray?
        get() = hash

    /** Transient ID for propagation (hash of encrypted data) */
    var transientId: ByteArray? = null
        private set

    // ===== State and Flags =====

    /** Current message state */
    var state: MessageState = MessageState.GENERATING

    /** Message representation (PACKET or RESOURCE) */
    var representation: MessageRepresentation = MessageRepresentation.UNKNOWN

    /** Actual delivery method used */
    var method: DeliveryMethod? = null

    /** Whether this is an incoming message */
    var incoming: Boolean = false

    /** Whether the signature has been validated */
    var signatureValidated: Boolean = false

    /** Reason why signature validation failed */
    var unverifiedReason: UnverifiedReason? = null

    // ===== Timestamps =====

    /** Message timestamp (UNIX epoch seconds as Double) */
    var timestamp: Double? = null

    // ===== Packed Data =====

    /** Packed message bytes (wire format) */
    var packed: ByteArray? = null
        private set

    /** Size of packed message */
    val packedSize: Int
        get() = packed?.size ?: 0

    /** Ed25519 signature (64 bytes) */
    var signature: ByteArray? = null
        private set

    /** Proof-of-work stamp (32 bytes, optional) */
    var stamp: ByteArray? = null

    // ===== Encryption State =====

    /** Whether message was transport-encrypted */
    var transportEncrypted: Boolean = false

    /** Description of transport encryption used */
    var transportEncryption: String? = null

    /** Progress of message delivery (0.0 to 1.0) */
    var progress: Double = 0.0

    // ===== Callbacks =====

    /** Callback when message is delivered */
    var deliveryCallback: ((LXMessage) -> Unit)? = null

    /** Callback when message delivery fails */
    var failedCallback: ((LXMessage) -> Unit)? = null

    // ===== Delivery Tracking =====

    /** Number of delivery attempts made */
    var deliveryAttempts: Int = 0

    /** Next delivery attempt timestamp (milliseconds) */
    var nextDeliveryAttempt: Long? = null

    /**
     * Pack the message into wire format.
     *
     * This creates the packed byte array that can be sent over the network.
     * The packing process:
     * 1. Create payload list: [timestamp, title, content, fields]
     * 2. Compute hash: SHA256(destHash + sourceHash + msgpack(payload))
     * 3. Sign: Ed25519(hashedPart + hash)
     * 4. Pack: destHash + sourceHash + signature + msgpack(payload)
     *
     * @return The packed message bytes
     * @throws IllegalStateException if source has no private key for signing
     */
    fun pack(): ByteArray {
        if (packed != null) {
            return packed!!
        }

        // Set timestamp if not set
        if (timestamp == null) {
            timestamp = System.currentTimeMillis() / 1000.0
        }

        // Get source identity for signing
        val sourceIdentity = source?.identity
            ?: throw IllegalStateException("Cannot pack message without source identity")
        require(sourceIdentity.hasPrivateKey) { "Cannot pack message: source has no private key" }

        // Build payload: [timestamp, title, content, fields]
        val payloadBytes = packPayload(timestamp!!, title, content, fields, stamp)

        // Build hashed part: destHash + sourceHash + msgpack(payload without stamp)
        val payloadWithoutStamp = packPayload(timestamp!!, title, content, fields, null)
        val hashedPart = destinationHash + sourceHash + payloadWithoutStamp

        // Compute message hash
        hash = Hashes.fullHash(hashedPart)

        // Build signed part: hashedPart + hash
        val signedPart = hashedPart + hash!!

        // Sign the message
        signature = sourceIdentity.sign(signedPart)
        signatureValidated = true

        // Build packed message: destHash + sourceHash + signature + payload
        packed = destinationHash + sourceHash + signature!! + payloadBytes

        // Determine delivery method and representation
        determineDeliveryMethod()

        return packed!!
    }

    /**
     * Determine the delivery method and representation based on message size.
     */
    private fun determineDeliveryMethod() {
        val contentSize = packed!!.size - LXMFConstants.LXMF_OVERHEAD

        // Default to DIRECT if not specified
        if (desiredMethod == null) {
            desiredMethod = DeliveryMethod.DIRECT
        }

        when (desiredMethod) {
            DeliveryMethod.OPPORTUNISTIC -> {
                if (contentSize > LXMFConstants.ENCRYPTED_PACKET_MAX_CONTENT) {
                    // Fall back to DIRECT for large messages
                    println("Opportunistic delivery requested but content too large ($contentSize bytes), falling back to DIRECT")
                    desiredMethod = DeliveryMethod.DIRECT
                    method = DeliveryMethod.DIRECT
                    representation = if (contentSize <= LXMFConstants.LINK_PACKET_MAX_CONTENT) {
                        MessageRepresentation.PACKET
                    } else {
                        MessageRepresentation.RESOURCE
                    }
                } else {
                    method = DeliveryMethod.OPPORTUNISTIC
                    representation = MessageRepresentation.PACKET
                }
            }
            DeliveryMethod.DIRECT -> {
                method = DeliveryMethod.DIRECT
                representation = if (contentSize <= LXMFConstants.LINK_PACKET_MAX_CONTENT) {
                    MessageRepresentation.PACKET
                } else {
                    MessageRepresentation.RESOURCE
                }
            }
            DeliveryMethod.PROPAGATED -> {
                method = DeliveryMethod.PROPAGATED
                // Propagated messages have additional encryption overhead
                representation = MessageRepresentation.RESOURCE // Conservative default
            }
            DeliveryMethod.PAPER -> {
                method = DeliveryMethod.PAPER
                representation = MessageRepresentation.PACKET
            }
            null -> {
                method = DeliveryMethod.DIRECT
                representation = MessageRepresentation.PACKET
            }
        }
    }

    /**
     * Pack payload into msgpack format.
     */
    private fun packPayload(
        timestamp: Double,
        title: String,
        content: String,
        fields: Map<Int, Any>,
        stamp: ByteArray?
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(buffer)

        // Pack as list with 4 or 5 elements
        val elementCount = if (stamp != null) 5 else 4
        packer.packArrayHeader(elementCount)

        // [0] timestamp as float64
        packer.packDouble(timestamp)

        // [1] title as bytes
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(titleBytes.size)
        packer.writePayload(titleBytes)

        // [2] content as bytes
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        packer.packBinaryHeader(contentBytes.size)
        packer.writePayload(contentBytes)

        // [3] fields as map
        packer.packMapHeader(fields.size)
        for ((key, value) in fields) {
            packer.packInt(key)
            packValue(packer, value)
        }

        // [4] stamp (optional)
        if (stamp != null) {
            packer.packBinaryHeader(stamp.size)
            packer.writePayload(stamp)
        }

        packer.close()
        return buffer.toByteArray()
    }

    /**
     * Pack a value into msgpack format (recursive for nested structures).
     */
    private fun packValue(packer: org.msgpack.core.MessagePacker, value: Any) {
        when (value) {
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                packer.packBinaryHeader(bytes.size)
                packer.writePayload(bytes)
            }
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Double -> packer.packDouble(value)
            is Float -> packer.packFloat(value)
            is Boolean -> packer.packBoolean(value)
            is List<*> -> {
                packer.packArrayHeader(value.size)
                for (item in value) {
                    if (item != null) packValue(packer, item)
                    else packer.packNil()
                }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                for ((k, v) in value) {
                    if (k != null) packValue(packer, k)
                    else packer.packNil()
                    if (v != null) packValue(packer, v)
                    else packer.packNil()
                }
            }
            else -> {
                // Default to string representation
                val str = value.toString().toByteArray(Charsets.UTF_8)
                packer.packBinaryHeader(str.size)
                packer.writePayload(str)
            }
        }
    }

    /**
     * Get title as bytes (UTF-8).
     */
    fun getTitleBytes(): ByteArray = title.toByteArray(Charsets.UTF_8)

    /**
     * Get content as bytes (UTF-8).
     */
    fun getContentBytes(): ByteArray = content.toByteArray(Charsets.UTF_8)

    /**
     * Set title from bytes.
     */
    fun setTitleFromBytes(bytes: ByteArray) {
        title = bytes.toString(Charsets.UTF_8)
    }

    /**
     * Set content from bytes.
     */
    fun setContentFromBytes(bytes: ByteArray) {
        content = bytes.toString(Charsets.UTF_8)
    }

    override fun toString(): String {
        val hashStr = hash?.toHexString()?.take(12) ?: "unpacked"
        return "<LXMessage $hashStr>"
    }

    companion object {
        /**
         * Create a new outbound LXMF message.
         *
         * @param destination The destination to send to
         * @param source The source destination (sender)
         * @param content Message content
         * @param title Message title (default empty)
         * @param fields Extended fields (default empty)
         * @param desiredMethod Desired delivery method (default DIRECT)
         * @return New LXMessage instance
         */
        fun create(
            destination: Destination,
            source: Destination,
            content: String,
            title: String = "",
            fields: MutableMap<Int, Any> = mutableMapOf(),
            desiredMethod: DeliveryMethod? = DeliveryMethod.DIRECT
        ): LXMessage {
            return LXMessage(
                destination = destination,
                source = source,
                destinationHash = destination.hash,
                sourceHash = source.hash,
                title = title,
                content = content,
                fields = fields,
                desiredMethod = desiredMethod
            )
        }

        /**
         * Unpack an LXMF message from wire format bytes.
         *
         * Wire format:
         * ```
         * [0:16]   Destination hash
         * [16:32]  Source hash
         * [32:96]  Signature
         * [96:]    Msgpack payload
         * ```
         *
         * @param lxmfBytes The packed message bytes
         * @param originalMethod The original delivery method (optional)
         * @return Unpacked LXMessage, or null if unpacking fails
         */
        fun unpackFromBytes(lxmfBytes: ByteArray, originalMethod: DeliveryMethod? = null): LXMessage? {
            try {
                // Minimum size: dest_hash (16) + source_hash (16) + signature (64) + some payload
                val minHeaderSize = 2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH
                if (lxmfBytes.size <= minHeaderSize) {
                    println("LXMF message too small: ${lxmfBytes.size} bytes (need > $minHeaderSize)")
                    return null
                }

                // Extract fixed-length fields
                val destinationHash = lxmfBytes.copyOfRange(0, LXMFConstants.DESTINATION_LENGTH)
                val sourceHash = lxmfBytes.copyOfRange(
                    LXMFConstants.DESTINATION_LENGTH,
                    2 * LXMFConstants.DESTINATION_LENGTH
                )
                val signature = lxmfBytes.copyOfRange(
                    2 * LXMFConstants.DESTINATION_LENGTH,
                    2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH
                )
                val packedPayload = lxmfBytes.copyOfRange(
                    2 * LXMFConstants.DESTINATION_LENGTH + LXMFConstants.SIGNATURE_LENGTH,
                    lxmfBytes.size
                )

                // Unpack msgpack payload
                val unpacker = MessagePack.newDefaultUnpacker(packedPayload)
                val arraySize = unpacker.unpackArrayHeader()

                if (arraySize < 4) {
                    println("Invalid LXMF payload: expected at least 4 elements, got $arraySize")
                    return null
                }

                // [0] timestamp
                val timestamp = unpacker.unpackDouble()

                // [1] title
                val titleLen = unpacker.unpackBinaryHeader()
                val titleBytes = ByteArray(titleLen)
                unpacker.readPayload(titleBytes)

                // [2] content
                val contentLen = unpacker.unpackBinaryHeader()
                val contentBytes = ByteArray(contentLen)
                unpacker.readPayload(contentBytes)

                // [3] fields
                val fields = unpackFields(unpacker)

                // [4] stamp (optional)
                val stamp: ByteArray? = if (arraySize > 4) {
                    val stampLen = unpacker.unpackBinaryHeader()
                    val stampBytes = ByteArray(stampLen)
                    unpacker.readPayload(stampBytes)
                    stampBytes
                } else {
                    null
                }

                unpacker.close()

                // Repack payload without stamp for hash verification
                val payloadWithoutStamp = repackPayload(timestamp, titleBytes, contentBytes, fields)

                // Build hashed part
                val hashedPart = destinationHash + sourceHash + payloadWithoutStamp

                // Compute message hash
                val messageHash = Hashes.fullHash(hashedPart)

                // Build signed part
                val signedPart = hashedPart + messageHash

                // Try to recall identities
                val destinationIdentity = Identity.recall(destinationHash)
                val sourceIdentity = Identity.recall(sourceHash)

                // Create destinations if identities are known
                val destination = if (destinationIdentity != null) {
                    // Note: We'd need to create a destination here, but for incoming
                    // messages we typically don't need the full destination object
                    null
                } else null

                val source = if (sourceIdentity != null) {
                    null
                } else null

                // Create message
                val message = LXMessage(
                    destination = destination,
                    source = source,
                    destinationHash = destinationHash,
                    sourceHash = sourceHash,
                    title = titleBytes.toString(Charsets.UTF_8),
                    content = contentBytes.toString(Charsets.UTF_8),
                    fields = fields,
                    desiredMethod = originalMethod
                )

                message.hash = messageHash
                message.signature = signature
                message.stamp = stamp
                message.incoming = true
                message.timestamp = timestamp
                message.packed = lxmfBytes

                // Validate signature if source identity is known
                if (sourceIdentity != null) {
                    try {
                        if (sourceIdentity.validate(signature, signedPart)) {
                            message.signatureValidated = true
                        } else {
                            message.signatureValidated = false
                            message.unverifiedReason = UnverifiedReason.SIGNATURE_INVALID
                        }
                    } catch (e: Exception) {
                        message.signatureValidated = false
                        println("Error validating LXMF signature: ${e.message}")
                    }
                } else {
                    message.signatureValidated = false
                    message.unverifiedReason = UnverifiedReason.SOURCE_UNKNOWN
                    println("Cannot validate LXMF signature: source identity unknown")
                }

                return message

            } catch (e: Exception) {
                println("Error unpacking LXMF message: ${e.message}")
                e.printStackTrace()
                return null
            }
        }

        /**
         * Unpack fields map from msgpack.
         */
        private fun unpackFields(unpacker: org.msgpack.core.MessageUnpacker): MutableMap<Int, Any> {
            val fields = mutableMapOf<Int, Any>()
            val mapSize = unpacker.unpackMapHeader()

            repeat(mapSize) {
                val key = unpacker.unpackInt()
                val value = unpackValue(unpacker)
                if (value != null) {
                    fields[key] = value
                }
            }

            return fields
        }

        /**
         * Unpack a value from msgpack.
         */
        private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
            val format = unpacker.nextFormat
            val valueType = format.valueType
            return when (valueType.name) {
                "NIL" -> {
                    unpacker.unpackNil()
                    null
                }
                "BOOLEAN" -> unpacker.unpackBoolean()
                "INTEGER" -> unpacker.unpackLong()
                "FLOAT" -> unpacker.unpackDouble()
                "STRING" -> unpacker.unpackString()
                "BINARY" -> {
                    val len = unpacker.unpackBinaryHeader()
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    bytes
                }
                "ARRAY" -> {
                    val size = unpacker.unpackArrayHeader()
                    val list = mutableListOf<Any?>()
                    repeat(size) {
                        list.add(unpackValue(unpacker))
                    }
                    list
                }
                "MAP" -> {
                    val size = unpacker.unpackMapHeader()
                    val map = mutableMapOf<Any?, Any?>()
                    repeat(size) {
                        val k = unpackValue(unpacker)
                        val v = unpackValue(unpacker)
                        map[k] = v
                    }
                    map
                }
                "EXTENSION" -> {
                    unpacker.skipValue()
                    null
                }
                else -> {
                    unpacker.skipValue()
                    null
                }
            }
        }

        /**
         * Repack payload without stamp for hash verification.
         */
        private fun repackPayload(
            timestamp: Double,
            titleBytes: ByteArray,
            contentBytes: ByteArray,
            fields: Map<Int, Any>
        ): ByteArray {
            val buffer = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(buffer)

            // Pack as 4-element list (without stamp)
            packer.packArrayHeader(4)

            // [0] timestamp
            packer.packDouble(timestamp)

            // [1] title
            packer.packBinaryHeader(titleBytes.size)
            packer.writePayload(titleBytes)

            // [2] content
            packer.packBinaryHeader(contentBytes.size)
            packer.writePayload(contentBytes)

            // [3] fields
            packer.packMapHeader(fields.size)
            for ((key, value) in fields) {
                packer.packInt(key)
                repackValue(packer, value)
            }

            packer.close()
            return buffer.toByteArray()
        }

        /**
         * Repack a value for hash verification.
         */
        private fun repackValue(packer: org.msgpack.core.MessagePacker, value: Any) {
            when (value) {
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                is String -> packer.packString(value)
                is Int -> packer.packInt(value)
                is Long -> packer.packLong(value)
                is Double -> packer.packDouble(value)
                is Float -> packer.packFloat(value)
                is Boolean -> packer.packBoolean(value)
                is List<*> -> {
                    packer.packArrayHeader(value.size)
                    for (item in value) {
                        if (item != null) repackValue(packer, item)
                        else packer.packNil()
                    }
                }
                is Map<*, *> -> {
                    packer.packMapHeader(value.size)
                    for ((k, v) in value) {
                        if (k != null) repackValue(packer, k)
                        else packer.packNil()
                        if (v != null) repackValue(packer, v)
                        else packer.packNil()
                    }
                }
                else -> packer.packString(value.toString())
            }
        }
    }
}
