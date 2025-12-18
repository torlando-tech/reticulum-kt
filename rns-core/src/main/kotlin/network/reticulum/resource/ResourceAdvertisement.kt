package network.reticulum.resource

import network.reticulum.link.LinkConstants
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Represents a resource advertisement, used to negotiate resource transfers.
 *
 * The advertisement contains metadata about the resource and a partial hashmap
 * of the resource parts to facilitate selective retransmission.
 */
class ResourceAdvertisement private constructor() {
    companion object {
        // Overhead includes all the fixed fields in the advertisement
        const val OVERHEAD = 134

        // Maximum number of hash entries that can fit in one advertisement
        val HASHMAP_MAX_LEN = floor(
            (LinkConstants.calculateMdu() - OVERHEAD).toDouble() / ResourceConstants.MAPHASH_LEN
        ).toInt()

        // Guard size to prevent collisions
        val COLLISION_GUARD_SIZE = 2 * ResourceConstants.WINDOW_MAX + HASHMAP_MAX_LEN

        init {
            require(HASHMAP_MAX_LEN > 0) {
                "The configured MTU is too small to include any map hashes in resource advertisements"
            }
        }

        /**
         * Check if advertisement packet represents a request.
         */
        fun isRequest(plaintext: ByteArray): Boolean {
            val adv = unpack(plaintext) ?: return false
            return adv.requestId != null && adv.isRequest
        }

        /**
         * Check if advertisement packet represents a response.
         */
        fun isResponse(plaintext: ByteArray): Boolean {
            val adv = unpack(plaintext) ?: return false
            return adv.requestId != null && adv.isResponseFlag
        }

        /**
         * Read request ID from advertisement packet.
         */
        fun readRequestId(plaintext: ByteArray): ByteArray? {
            val adv = unpack(plaintext) ?: return null
            return adv.requestId
        }

        /**
         * Read transfer size from advertisement packet.
         */
        fun readTransferSize(plaintext: ByteArray): Int? {
            val adv = unpack(plaintext) ?: return null
            return adv.transferSize
        }

        /**
         * Read data size from advertisement packet.
         */
        fun readSize(plaintext: ByteArray): Int? {
            val adv = unpack(plaintext) ?: return null
            return adv.dataSize
        }

        /**
         * Create advertisement from a Resource.
         */
        fun fromResource(resource: Resource): ResourceAdvertisement {
            return ResourceAdvertisement().apply {
                transferSize = resource.size
                dataSize = resource.totalSize
                numParts = resource.parts.size
                hash = resource.hash
                randomHash = resource.randomHash
                originalHash = resource.originalHash
                hashmap = resource.hashmapRaw
                compressed = resource.compressed
                encrypted = resource.encrypted
                split = resource.split
                hasMetadata = resource.hasMetadata
                segmentIndex = resource.segmentIndex
                totalSegments = resource.totalSegments
                requestId = resource.requestId
                isRequest = resource.requestId != null && !resource.isResponse
                isResponseFlag = resource.requestId != null && resource.isResponse

                // Build flags byte
                flags = buildFlags(encrypted, compressed, split, isRequest, isResponseFlag, hasMetadata)
            }
        }

        /**
         * Unpack advertisement from MessagePack data.
         */
        fun unpack(data: ByteArray): ResourceAdvertisement? {
            return try {
                val unpacker = MessagePack.newDefaultUnpacker(data)
                val mapSize = unpacker.unpackMapHeader()

                val adv = ResourceAdvertisement()

                repeat(mapSize) {
                    val key = unpacker.unpackString()
                    when (key) {
                        "t" -> adv.transferSize = unpacker.unpackInt()
                        "d" -> adv.dataSize = unpacker.unpackInt()
                        "n" -> adv.numParts = unpacker.unpackInt()
                        "h" -> {
                            val len = unpacker.unpackBinaryHeader()
                            adv.hash = unpacker.readPayload(len)
                        }
                        "r" -> {
                            val len = unpacker.unpackBinaryHeader()
                            adv.randomHash = unpacker.readPayload(len)
                        }
                        "o" -> {
                            val len = unpacker.unpackBinaryHeader()
                            adv.originalHash = unpacker.readPayload(len)
                        }
                        "m" -> {
                            val len = unpacker.unpackBinaryHeader()
                            adv.hashmap = unpacker.readPayload(len)
                        }
                        "f" -> adv.flags = unpacker.unpackInt()
                        "i" -> adv.segmentIndex = unpacker.unpackInt()
                        "l" -> adv.totalSegments = unpacker.unpackInt()
                        "q" -> {
                            if (unpacker.tryUnpackNil()) {
                                adv.requestId = null
                            } else {
                                val len = unpacker.unpackBinaryHeader()
                                adv.requestId = unpacker.readPayload(len)
                            }
                        }
                        else -> unpacker.skipValue()
                    }
                }
                unpacker.close()

                // Decode flags
                adv.encrypted = (adv.flags and 0x01) == 0x01
                adv.compressed = ((adv.flags shr 1) and 0x01) == 0x01
                adv.split = ((adv.flags shr 2) and 0x01) == 0x01
                adv.isRequest = ((adv.flags shr 3) and 0x01) == 0x01
                adv.isResponseFlag = ((adv.flags shr 4) and 0x01) == 0x01
                adv.hasMetadata = ((adv.flags shr 5) and 0x01) == 0x01

                adv
            } catch (e: Exception) {
                null
            }
        }

        private fun buildFlags(
            encrypted: Boolean,
            compressed: Boolean,
            split: Boolean,
            isRequest: Boolean,
            isResponse: Boolean,
            hasMetadata: Boolean
        ): Int {
            var flags = 0x00
            if (encrypted) flags = flags or 0x01
            if (compressed) flags = flags or 0x02
            if (split) flags = flags or 0x04
            if (isRequest) flags = flags or 0x08
            if (isResponse) flags = flags or 0x10
            if (hasMetadata) flags = flags or 0x20
            return flags
        }
    }

    // Transfer size (compressed if applicable)
    var transferSize: Int = 0

    // Total uncompressed data size
    var dataSize: Int = 0

    // Number of parts
    var numParts: Int = 0

    // Resource hash (16 bytes)
    var hash: ByteArray = ByteArray(0)

    // Random hash for uniqueness (4 bytes)
    var randomHash: ByteArray = ByteArray(0)

    // Original hash (first segment hash)
    var originalHash: ByteArray = ByteArray(0)

    // Partial hashmap of parts
    var hashmap: ByteArray = ByteArray(0)

    // Flags byte
    var flags: Int = 0

    // Segment index (for split resources)
    var segmentIndex: Int = 1

    // Total number of segments
    var totalSegments: Int = 1

    // Request ID (if this is a request/response)
    var requestId: ByteArray? = null

    // Flag: is encrypted
    var encrypted: Boolean = false

    // Flag: is compressed
    var compressed: Boolean = false

    // Flag: is split across multiple advertisements
    var split: Boolean = false

    // Flag: this is a request advertisement
    var isRequest: Boolean = false

    // Flag: this is a response advertisement
    var isResponseFlag: Boolean = false

    // Flag: resource has metadata
    var hasMetadata: Boolean = false

    /**
     * Pack this advertisement into MessagePack format.
     * @param segment Which segment of the hashmap to include (for large resources)
     */
    fun pack(segment: Int = 0): ByteArray {
        val hashmapStart = segment * HASHMAP_MAX_LEN * ResourceConstants.MAPHASH_LEN
        val hashmapEnd = min(
            (segment + 1) * HASHMAP_MAX_LEN * ResourceConstants.MAPHASH_LEN,
            hashmap.size
        )
        val hashmapSlice = if (hashmapEnd > hashmapStart) {
            hashmap.copyOfRange(hashmapStart, hashmapEnd)
        } else {
            ByteArray(0)
        }

        val output = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(output)

        packer.packMapHeader(11)

        packer.packString("t")
        packer.packInt(transferSize)

        packer.packString("d")
        packer.packInt(dataSize)

        packer.packString("n")
        packer.packInt(numParts)

        packer.packString("h")
        packer.packBinaryHeader(hash.size)
        packer.writePayload(hash)

        packer.packString("r")
        packer.packBinaryHeader(randomHash.size)
        packer.writePayload(randomHash)

        packer.packString("o")
        packer.packBinaryHeader(originalHash.size)
        packer.writePayload(originalHash)

        packer.packString("i")
        packer.packInt(segmentIndex)

        packer.packString("l")
        packer.packInt(totalSegments)

        packer.packString("q")
        if (requestId != null) {
            packer.packBinaryHeader(requestId!!.size)
            packer.writePayload(requestId!!)
        } else {
            packer.packNil()
        }

        packer.packString("f")
        packer.packInt(flags)

        packer.packString("m")
        packer.packBinaryHeader(hashmapSlice.size)
        packer.writePayload(hashmapSlice)

        packer.close()
        return output.toByteArray()
    }
}
