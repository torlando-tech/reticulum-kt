package network.reticulum.discovery

import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.ByteArrayKey
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack

/**
 * Handles incoming discovery announces from the network.
 *
 * Since Kotlin's AnnounceHandler doesn't have aspect-based filtering,
 * this handler self-filters by computing the expected destination hash
 * for "rnstransport.discovery.interface" and comparing with each announce.
 *
 * Parse flow:
 * 1. Self-filter: verify destination hash matches discovery aspect
 * 2. Check authorized sources
 * 3. Extract flags, optionally decrypt
 * 4. Split packed data from stamp (last 32 bytes)
 * 5. Validate PoW stamp
 * 6. Unpack msgpack → build DiscoveredInterface
 * 7. Invoke callback
 */
class InterfaceAnnounceHandler(
    private val requiredValue: Int = DiscoveryConstants.DEFAULT_STAMP_VALUE,
    private val callback: ((DiscoveredInterface) -> Unit)? = null,
    private val discoverySources: Set<ByteArrayKey>? = null,
) : AnnounceHandler {

    /** Pre-computed name hash for "rnstransport.discovery.interface". */
    private val nameHash: ByteArray = Destination.computeNameHash(
        DiscoveryConstants.APP_NAME,
        listOf("discovery", "interface")
    )

    override fun handleAnnounce(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?
    ): Boolean {
        try {
            // Self-filter: check if this announce is for the discovery aspect
            val expectedHash = Destination.computeHash(nameHash, announcedIdentity.hash)
            if (!destinationHash.contentEquals(expectedHash)) return false

            // Check authorized sources
            if (discoverySources != null) {
                val idKey = ByteArrayKey(announcedIdentity.hash)
                if (idKey !in discoverySources) return false
            }

            if (appData == null || appData.size <= Stamper.STAMP_SIZE + 1) return false

            // Extract flags byte
            val flags = appData[0]
            var data = appData.copyOfRange(1, appData.size)
            val encrypted = (flags.toInt() and DiscoveryConstants.FLAG_ENCRYPTED.toInt()) != 0

            // Decrypt if encrypted
            if (encrypted) {
                val networkId = Transport.networkIdentity ?: return false
                data = networkId.decrypt(data) ?: return false
            }

            if (data.size <= Stamper.STAMP_SIZE) return false

            // Split: packed info | stamp (last 32 bytes)
            val stamp = data.copyOfRange(data.size - Stamper.STAMP_SIZE, data.size)
            val packed = data.copyOfRange(0, data.size - Stamper.STAMP_SIZE)

            // Validate stamp
            val infohash = Hashes.fullHash(packed)
            val workblock = Stamper.generateWorkblock(infohash, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
            val value = Stamper.stampValue(workblock, stamp)
            val valid = Stamper.stampValid(stamp, requiredValue, workblock)

            if (!valid || value < requiredValue) return false

            // Unpack msgpack
            val unpacker = MessagePack.newDefaultUnpacker(packed)
            val mapSize = unpacker.unpackMapHeader()
            val fields = HashMap<Int, Any?>(mapSize)
            repeat(mapSize) {
                val key = unpacker.unpackInt()
                fields[key] = unpackValue(unpacker)
            }

            val interfaceType = fields[DiscoveryConstants.INTERFACE_TYPE] as? String ?: return false
            val transportId = fields[DiscoveryConstants.TRANSPORT_ID]
            val transportIdHex = when (transportId) {
                is ByteArray -> transportId.toHexString()
                else -> return false
            }

            val name = fields[DiscoveryConstants.NAME] as? String
                ?: "Discovered $interfaceType"

            // Compute discovery hash
            val discoveryHash = Hashes.fullHash(
                (transportIdHex + name).toByteArray(Charsets.UTF_8)
            )

            val info = DiscoveredInterface(
                type = interfaceType,
                transport = fields[DiscoveryConstants.TRANSPORT] as? Boolean ?: false,
                name = name,
                received = System.currentTimeMillis() / 1000L,
                stampValue = value,
                transportId = transportIdHex,
                networkId = announcedIdentity.hash.toHexString(),
                hops = Transport.hopsTo(destinationHash) ?: 0,
                latitude = (fields[DiscoveryConstants.LATITUDE] as? Number)?.toDouble(),
                longitude = (fields[DiscoveryConstants.LONGITUDE] as? Number)?.toDouble(),
                height = (fields[DiscoveryConstants.HEIGHT] as? Number)?.toDouble(),
                reachableOn = fields[DiscoveryConstants.REACHABLE_ON] as? String,
                port = (fields[DiscoveryConstants.PORT] as? Number)?.toInt(),
                frequency = (fields[DiscoveryConstants.FREQUENCY] as? Number)?.toLong(),
                bandwidth = (fields[DiscoveryConstants.BANDWIDTH] as? Number)?.toLong(),
                spreadingFactor = (fields[DiscoveryConstants.SPREADING_FACTOR] as? Number)?.toInt(),
                codingRate = (fields[DiscoveryConstants.CODING_RATE] as? Number)?.toInt(),
                modulation = fields[DiscoveryConstants.MODULATION] as? String,
                channel = (fields[DiscoveryConstants.CHANNEL] as? Number)?.toInt(),
                ifacNetname = fields[DiscoveryConstants.IFAC_NETNAME] as? String,
                ifacNetkey = fields[DiscoveryConstants.IFAC_NETKEY] as? String,
                discoveryHash = discoveryHash,
            )

            callback?.invoke(info)
            return true

        } catch (e: Exception) {
            println("[Discovery] Error decoding discovered interface: ${e.message}")
            return false
        }
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> {
                // Try int first, fall back to long
                try { unpacker.unpackInt() }
                catch (_: Exception) { unpacker.unpackLong() }
            }
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                unpacker.readPayload(len)
            }
            else -> { unpacker.skipValue(); null }
        }
    }
}
