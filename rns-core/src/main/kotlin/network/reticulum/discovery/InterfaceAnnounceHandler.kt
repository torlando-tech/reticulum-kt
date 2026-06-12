package network.reticulum.discovery

import network.reticulum.common.RnsConstants
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.AnnounceHandler
import network.reticulum.common.ByteArrayKey
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack

/**
 * Handles incoming discovery announces from the network.
 *
 * Ported from python InterfaceAnnounceHandler.received_announce
 * (RNS/Discovery.py:214-362): source allowlist, flag/decrypt handling,
 * PoW stamp validation, msgpack decode, HARD field-type validation (any
 * violation rejects the whole announce), interface-type whitelist,
 * reachable_on address validation, name sanitization, per-type field
 * extraction with the ready-to-paste config_entry strings, and the
 * callback(null) path when INTERFACE_TYPE is absent.
 *
 * Since Kotlin's AnnounceHandler doesn't have aspect-based filtering,
 * this handler self-filters by computing the expected destination hash
 * for "rnstransport.discovery.interface" and comparing with each announce.
 */
class InterfaceAnnounceHandler(
    /** python: required_value=InterfaceAnnouncer.DEFAULT_STAMP_VALUE (Discovery.py:192). */
    val requiredValue: Int = DiscoveryConstants.DEFAULT_STAMP_VALUE,
    private val callback: ((DiscoveredInterface) -> Unit)? = null,
    /**
     * python reads RNS.Reticulum.interface_discovery_sources() live
     * (Discovery.py:216); kotlin injects the allowlist at construction.
     * Empty/null -> no source gating, exactly python's falsy check.
     */
    private val discoverySources: Set<ByteArrayKey>? = null,
    /**
     * Receives the decoded python-shaped info record — or null when the
     * announce decoded but carried no INTERFACE_TYPE (python fires the
     * callback with info=None in that case, Discovery.py:357).
     */
    private val rawCallback: ((Map<String, Any?>?) -> Unit)? = null,
) : AnnounceHandler {

    /** python: APP_NAME+".discovery.interface" (Discovery.py:200). */
    val aspectFilter: String = "${DiscoveryConstants.APP_NAME}.discovery.interface"

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

            // python Discovery.py:216-219 — authorized-source gate
            if (!discoverySources.isNullOrEmpty()) {
                if (ByteArrayKey(announcedIdentity.hash) !in discoverySources) {
                    log("Interface discovered from non-authorized network identity, ignoring")
                    return false
                }
            }

            // python: `if app_data and len(app_data) > STAMP_SIZE+1` (:221)
            if (appData == null || appData.size <= Stamper.STAMP_SIZE + 1) return false

            val flags = appData[0]
            var data = appData.copyOfRange(1, appData.size)
            val encrypted = (flags.toInt() and DiscoveryConstants.FLAG_ENCRYPTED.toInt()) != 0

            if (encrypted) {
                // python: requires Transport.has_network_identity() (:228-230)
                val networkId = Transport.networkIdentity ?: return false
                data = networkId.decrypt(data) ?: return false
            }

            if (data.size <= Stamper.STAMP_SIZE) return false

            // Split: packed info | stamp (last STAMP_SIZE bytes), python :232-233
            val stamp = data.copyOfRange(data.size - Stamper.STAMP_SIZE, data.size)
            val packed = data.copyOfRange(0, data.size - Stamper.STAMP_SIZE)

            // Validate stamp (python :234-243)
            val infohash = Hashes.fullHash(packed)
            val workblock = Stamper.generateWorkblock(infohash, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
            val value = Stamper.stampValue(workblock, stamp)
            val valid = Stamper.stampValid(stamp, requiredValue, workblock)

            if (!valid) {
                log("Ignored discovered interface with invalid stamp")
                return false
            }
            if (value < requiredValue) {
                log("Ignored discovered interface with stamp value $value")
                return false
            }

            // Unpack msgpack
            val unpacker = MessagePack.newDefaultUnpacker(packed)
            val mapSize = unpacker.unpackMapHeader()
            val fields = HashMap<Int, Any?>(mapSize)
            val present = HashSet<Int>(mapSize)
            repeat(mapSize) {
                val key = unpacker.unpackInt()
                present.add(key)
                fields[key] = unpackValue(unpacker)
            }

            // python: `if INTERFACE_TYPE in unpacked:` ... else info stays None
            // and the callback STILL fires with None (Discovery.py:246,357).
            if (DiscoveryConstants.INTERFACE_TYPE !in present) {
                rawCallback?.invoke(null)
                return false
            }

            // python indexes mandatory fields directly — a missing key raises
            // (KeyError) into the outer catch and the announce is dropped.
            fun required(key: Int): Any? {
                if (key !in present) throw IllegalArgumentException("missing field $key in announce")
                return fields[key]
            }

            val interfaceType = required(DiscoveryConstants.INTERFACE_TYPE) as? String
                ?: throw IllegalArgumentException("Invalid interface type in announce data")
            val name = DiscoveryUtil.sanitizeName(required(DiscoveryConstants.NAME) as? String)

            // python's hard type gates (Discovery.py:250-261) — any violation
            // rejects the whole announce.
            val transport = required(DiscoveryConstants.TRANSPORT)
            if (transport !is Boolean) throw IllegalArgumentException("Invalid data in transport field of announce")
            val latitude = required(DiscoveryConstants.LATITUDE)
            if (latitude != null && latitude !is Double) throw IllegalArgumentException("Invalid data in latitude field of announce")
            val longitude = required(DiscoveryConstants.LONGITUDE)
            if (longitude != null && longitude !is Double) throw IllegalArgumentException("Invalid data in longitude field of announce")
            val height = required(DiscoveryConstants.HEIGHT)
            if (height != null && height !is Double) throw IllegalArgumentException("Invalid data in height field of announce")
            val transportIdBytes = required(DiscoveryConstants.TRANSPORT_ID) as? ByteArray
            if (transportIdBytes == null || transportIdBytes.size != RnsConstants.TRUNCATED_HASH_BYTES) {
                throw IllegalArgumentException("Invalid data in transport_id field of announce")
            }
            if (interfaceType !in DiscoveryConstants.DISCOVERABLE_INTERFACE_TYPES) {
                throw IllegalArgumentException("Invalid interface type in announce data")
            }
            if (DiscoveryConstants.REACHABLE_ON in present) {
                val ro = fields[DiscoveryConstants.REACHABLE_ON] as? String
                if (ro == null || !(DiscoveryUtil.isIpAddress(ro) || DiscoveryUtil.isHostname(ro))) {
                    throw IllegalArgumentException("Invalid data in reachable_on field of announce")
                }
            }

            val transportIdHex = transportIdBytes.toHexString()
            val networkIdHex = announcedIdentity.hash.toHexString()
            // python: `name or f"Discovered {interface_type}"` — falsy check,
            // so a fully-sanitized-away EMPTY name also falls back.
            val effectiveName = if (name.isNullOrEmpty()) "Discovered $interfaceType" else name
            val hops = Transport.hopsTo(destinationHash) ?: 0
            val received = System.currentTimeMillis() / 1000L

            val ifacNetname = if (DiscoveryConstants.IFAC_NETNAME in present)
                fields[DiscoveryConstants.IFAC_NETNAME]?.toString() else null
            val ifacNetkey = if (DiscoveryConstants.IFAC_NETKEY in present)
                fields[DiscoveryConstants.IFAC_NETKEY]?.toString() else null

            val netnameStr = ifacNetname?.let { "\n  network_name = $it" } ?: ""
            val netkeyStr = ifacNetkey?.let { "\n  passphrase = $it" } ?: ""
            val identityStr = "\n  transport_identity = $transportIdHex"

            var reachableOn: String? = null
            var port: Int? = null
            var frequency: Long? = null
            var bandwidth: Long? = null
            var sf: Int? = null
            var cr: Int? = null
            var channel: Int? = null
            var modulation: String? = null
            var configEntry: String? = null

            when {
                interfaceType == "BackboneInterface" || interfaceType == "TCPServerInterface" -> {
                    // python: connection type depends on backbone support
                    // (everything but Windows), Discovery.py:280-296.
                    val backboneSupport = !System.getProperty("os.name", "")
                        .startsWith("Windows", ignoreCase = true)
                    reachableOn = required(DiscoveryConstants.REACHABLE_ON) as? String
                    port = (required(DiscoveryConstants.PORT) as? Number)?.toInt()
                    val connectionInterface = if (backboneSupport) "BackboneInterface" else "TCPClientInterface"
                    val remoteStr = if (backboneSupport) "remote" else "target_host"
                    configEntry = "[[${effectiveName}]]\n  type = $connectionInterface\n  enabled = yes\n  " +
                        "$remoteStr = $reachableOn\n  target_port = $port$identityStr$netnameStr$netkeyStr"
                }
                interfaceType == "I2PInterface" -> {
                    reachableOn = required(DiscoveryConstants.REACHABLE_ON) as? String
                    configEntry = "[[${effectiveName}]]\n  type = I2PInterface\n  enabled = yes\n  " +
                        "peers = $reachableOn$identityStr$netnameStr$netkeyStr"
                }
                interfaceType == "RNodeInterface" -> {
                    frequency = (required(DiscoveryConstants.FREQUENCY) as? Number)?.toLong()
                    bandwidth = (required(DiscoveryConstants.BANDWIDTH) as? Number)?.toLong()
                    sf = (required(DiscoveryConstants.SPREADING_FACTOR) as? Number)?.toInt()
                    cr = (required(DiscoveryConstants.CODING_RATE) as? Number)?.toInt()
                    configEntry = "[[${effectiveName}]]\n  type = RNodeInterface\n  enabled = yes\n  port = \n  " +
                        "frequency = $frequency\n  bandwidth = $bandwidth\n  spreadingfactor = $sf\n  " +
                        "codingrate = $cr\n  txpower = $netnameStr$netkeyStr"
                }
                interfaceType == "WeaveInterface" -> {
                    frequency = (required(DiscoveryConstants.FREQUENCY) as? Number)?.toLong()
                    bandwidth = (required(DiscoveryConstants.BANDWIDTH) as? Number)?.toLong()
                    channel = (required(DiscoveryConstants.CHANNEL) as? Number)?.toInt()
                    modulation = required(DiscoveryConstants.MODULATION) as? String
                    configEntry = "[[${effectiveName}]]\n  type = WeaveInterface\n  enabled = yes\n  " +
                        "port = $netnameStr$netkeyStr"
                }
                interfaceType == "KISSInterface" -> {
                    frequency = (required(DiscoveryConstants.FREQUENCY) as? Number)?.toLong()
                    bandwidth = (required(DiscoveryConstants.BANDWIDTH) as? Number)?.toLong()
                    modulation = required(DiscoveryConstants.MODULATION) as? String
                    configEntry = "[[${effectiveName}]]\n  type = KISSInterface\n  enabled = yes\n  port = \n  " +
                        "# Frequency: $frequency\n  # Bandwidth: $bandwidth\n  " +
                        "# Modulation: $modulation$identityStr$netnameStr$netkeyStr"
                }
            }

            // python: discovery_hash = full_hash(utf8(transport_id_hex + name))
            val discoveryHash = Hashes.fullHash(
                (transportIdHex + effectiveName).toByteArray(Charsets.UTF_8)
            )

            val info = DiscoveredInterface(
                type = interfaceType,
                transport = transport,
                name = effectiveName,
                received = received,
                stampValue = value,
                transportId = transportIdHex,
                networkId = networkIdHex,
                hops = hops,
                latitude = latitude as? Double,
                longitude = longitude as? Double,
                height = height as? Double,
                reachableOn = reachableOn,
                port = port,
                frequency = frequency,
                bandwidth = bandwidth,
                spreadingFactor = sf,
                codingRate = cr,
                modulation = modulation,
                channel = channel,
                ifacNetname = ifacNetname,
                ifacNetkey = ifacNetkey,
                discoveryHash = discoveryHash,
                stamp = stamp,
                configEntry = configEntry,
            )

            log("Discovered: $interfaceType \"$effectiveName\" from ${transportIdHex.take(12)}... " +
                "(stamp=$value, hops=$hops, encrypted=$encrypted)")

            callback?.invoke(info)
            rawCallback?.invoke(infoToMap(info))
            return true

        } catch (e: Exception) {
            log("An error occurred while trying to decode discovered interface. The contained exception was: ${e.message}")
            return false
        }
    }

    private fun log(msg: String) {
        println("[Discovery:Handler] $msg")
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            // Always read the widest signed form: a failed unpackInt() on a
            // value > Int.MAX (e.g. a 2.4 GHz frequency) consumes bytes before
            // throwing, corrupting the rest of the map. unpackLong covers every
            // int width the discovery record uses.
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                unpacker.readPayload(len)
            }
            else -> { unpacker.skipValue(); null }
        }
    }

    companion object {
        /**
         * The python-shaped info record (Discovery.py:263-356 key names) —
         * what the reference bridge surfaces to conformance tests.
         */
        fun infoToMap(info: DiscoveredInterface): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["type"] = info.type
            m["transport"] = info.transport
            m["name"] = info.name
            // python info["received"] = time.time() — a float wall-clock.
            m["received"] = info.received.toDouble()
            m["stamp"] = info.stamp
            m["value"] = info.stampValue
            m["transport_id"] = info.transportId
            m["network_id"] = info.networkId
            m["hops"] = info.hops
            m["latitude"] = info.latitude
            m["longitude"] = info.longitude
            m["height"] = info.height
            info.ifacNetname?.let { m["ifac_netname"] = it }
            info.ifacNetkey?.let { m["ifac_netkey"] = it }
            info.reachableOn?.let { m["reachable_on"] = it }
            info.port?.let { m["port"] = it }
            info.frequency?.let { m["frequency"] = it }
            info.bandwidth?.let { m["bandwidth"] = it }
            info.spreadingFactor?.let { m["sf"] = it }
            info.codingRate?.let { m["cr"] = it }
            info.channel?.let { m["channel"] = it }
            info.modulation?.let { m["modulation"] = it }
            info.configEntry?.let { m["config_entry"] = it }
            m["discovery_hash"] = info.discoveryHash
            return m
        }
    }
}
