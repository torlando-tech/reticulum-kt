import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import network.reticulum.Reticulum
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.InterfaceMode
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.discovery.DiscoveredInterface
import network.reticulum.discovery.DiscoveryConstants
import network.reticulum.discovery.DiscoveryUtil
import network.reticulum.discovery.InterfaceAnnounceHandler
import network.reticulum.discovery.InterfaceAnnouncer
import network.reticulum.discovery.Stamper
import network.reticulum.identity.Identity
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport

/**
 * Conformance arms for the discovery_* family. Every protocol decision —
 * announce building, stamp PoW, receive-path validation — is made by the
 * real library (InterfaceAnnouncer / InterfaceAnnounceHandler / Stamper /
 * DiscoveryUtil); these arms only set environmental state, build the
 * stand-in interface python's reference bridge also builds, and decompose
 * results. The one adversarial arm (discovery_craft_announce) mutates the
 * DECODED dict of a genuine library-built announce and re-packs it with the
 * library's own packer, mirroring the reference's ADVERSARIAL_CORRUPTORS
 * discipline.
 */

/** Stand-in interface carrying the discovery_* attributes the library reads
 * (the kotlin analogue of the reference's dynamically-named python class). */
private class DiscoveryStandIn(
    override val name: String,
    private val interfaceType: String,
    private val fields: JsonObject,
    private val stampValue: Int,
    private val encrypt: Boolean,
) : InterfaceRef {
    override val hash: ByteArray = Hashes.fullHash(name.toByteArray(Charsets.UTF_8))
    override val canSend: Boolean = false
    override val canReceive: Boolean = false
    override val online: Boolean = false
    override val mode: InterfaceMode = InterfaceMode.FULL
    override val bitrate: Int = 1_000_000
    override val announceCap: Double = 0.02
    override val hwMtu: Int = 500
    override val supportsLinkMtuDiscovery: Boolean = false
    override val ifacSize: Int = 0
    override val ifacKey: ByteArray? = null
    override val ifacIdentity: Identity? = null
    override var tunnelId: ByteArray? = null
    override var wantsTunnel: Boolean = false
    override fun send(data: ByteArray) { /* stand-in never transmits */ }

    override val discoveryInterfaceType: String get() = interfaceType
    override val kissFraming: Boolean get() = fields.boolOpt("kiss_framing") ?: false
    override val discoveryName: String? get() = fields.strOpt("name")
    override val discoveryEncrypt: Boolean get() = encrypt
    override val discoveryStampValue: Int? get() = stampValue
    override val discoveryPublishIfac: Boolean get() = fields.boolOpt("publish_ifac") ?: false
    override val ifacNetname: String? get() = fields.strOpt("ifac_netname")
    override val ifacNetkey: String? get() = fields.strOpt("ifac_netkey")
    private fun numericField(key: String): Double? =
        fields.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    override val discoveryLatitude: Double? get() = numericField("latitude")
    override val discoveryLongitude: Double? get() = numericField("longitude")
    override val discoveryHeight: Double? get() = numericField("height")

    override fun getDiscoveryData(): Map<Int, Any>? {
        // Per-type fields in python's exact insertion order (Discovery.py:
        // 115-162). The builder's centralized rules (reachable_on validation,
        // KISS rewrite, TCPClient abort) run on top of this map.
        val data = LinkedHashMap<Int, Any>()
        when (interfaceType) {
            "BackboneInterface", "TCPServerInterface" -> {
                fields.strOpt("reachable_on")?.let { data[DiscoveryConstants.REACHABLE_ON] = it }
                fields.intOpt("port")?.let { data[DiscoveryConstants.PORT] = it }
            }
            "I2PInterface" -> {
                if ((fields.boolOpt("connectable") == true) && fields.strOpt("b32") != null) {
                    data[DiscoveryConstants.REACHABLE_ON] = fields.str("b32")
                }
            }
            "RNodeInterface" -> {
                fields.get("frequency")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.FREQUENCY] = it.asLong }
                fields.get("bandwidth")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.BANDWIDTH] = it.asLong }
                fields.intOpt("sf")?.let { data[DiscoveryConstants.SPREADING_FACTOR] = it }
                fields.intOpt("cr")?.let { data[DiscoveryConstants.CODING_RATE] = it }
            }
            "WeaveInterface" -> {
                fields.get("frequency")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.FREQUENCY] = it.asLong }
                fields.get("bandwidth")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.BANDWIDTH] = it.asLong }
                fields.intOpt("channel")?.let { data[DiscoveryConstants.CHANNEL] = it }
                fields.strOpt("modulation")?.let { data[DiscoveryConstants.MODULATION] = it }
            }
            "KISSInterface", "TCPClientInterface" -> {
                fields.get("frequency")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.FREQUENCY] = it.asLong }
                fields.get("bandwidth")?.takeIf { !it.isJsonNull }?.let { data[DiscoveryConstants.BANDWIDTH] = it.asLong }
                fields.strOpt("modulation")?.let { data[DiscoveryConstants.MODULATION] = it }
            }
        }
        return if (data.isEmpty()) null else data
    }
}

private fun ensureTransportIdentity(p: JsonObject): Identity {
    val priv = p.hexOpt("transport_identity_priv")
    if (priv != null) {
        Transport.identity = Identity.fromPrivateKey(priv, defaultCryptoProvider())
    } else if (Transport.identity == null) {
        Transport.identity = Identity.create(defaultCryptoProvider())
    }
    return Transport.identity!!
}

private fun buildAnnounceAppdata(p: JsonObject): JsonObject {
    val interfaceType = p.str("interface_type")
    val fields = p.get("fields")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    val stampValue = p.intOpt("stamp_value") ?: 14
    val encrypt = p.boolOpt("encrypt") ?: false

    Transport.transportEnabled = p.boolOpt("transport_enabled") ?: false
    ensureTransportIdentity(p)
    Transport.networkIdentity = p.hexOpt("network_identity_priv")?.let {
        Identity.fromPrivateKey(it, defaultCryptoProvider())
    }

    val announcer = InterfaceAnnouncer()
    val iface = DiscoveryStandIn("conformance-discovery", interfaceType, fields, stampValue, encrypt)
    var appData = announcer.getInterfaceAnnounceData(iface)
        ?: return result("aborted" to boolVal(true), "app_data" to JsonNull.INSTANCE)

    val stampSize = Stamper.STAMP_SIZE

    // python's duck-typed stand-in passes WRONG-TYPED coordinate values
    // straight through to msgpack so the RECEIVER's type gates can be
    // exercised. kotlin's typed InterfaceRef cannot carry them, so the
    // equivalent adversarial path is: build genuinely (typed field null),
    // then mutate the DECODED map and re-pack with the library's own packer
    // + a fresh real stamp — the same discipline as discovery_craft_announce.
    val wrongTyped = LinkedHashMap<Int, Any?>()
    for ((jsonKey, infoKey) in listOf(
        "latitude" to DiscoveryConstants.LATITUDE,
        "longitude" to DiscoveryConstants.LONGITUDE,
        "height" to DiscoveryConstants.HEIGHT,
    )) {
        val el = fields.get(jsonKey) ?: continue
        if (el.isJsonPrimitive && !el.asJsonPrimitive.isNumber) {
            wrongTyped[infoKey] = if (el.asJsonPrimitive.isBoolean) el.asBoolean else el.asString
        }
    }
    if (wrongTyped.isNotEmpty() && !encrypt) {
        val origPacked = appData.copyOfRange(1, appData.size - stampSize)
        val decoded = unpackMsgPack(origPacked) as MsgPackVal.MapVal
        val info = LinkedHashMap<Int, Any?>()
        for ((k, v) in decoded.entries) info[(mpValToNative(k) as Number).toInt()] = mpValToNative(v)
        info.putAll(wrongTyped)
        val newPacked = announcer.packInfoDict(info)
        val wb = Stamper.generateWorkblock(Hashes.fullHash(newPacked), DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
        val r = runBlocking { Stamper.generateStamp(wb, stampValue) }
        val stamp2 = r.stamp ?: return result("aborted" to boolVal(true), "app_data" to JsonNull.INSTANCE)
        appData = byteArrayOf(appData[0]) + newPacked + stamp2
    }

    val packed = appData.copyOfRange(1, appData.size - stampSize)
    val stamp = appData.copyOfRange(appData.size - stampSize, appData.size)
    return result(
        "aborted" to boolVal(false),
        "app_data" to hexVal(appData),
        "flags" to intVal(appData[0].toInt() and 0xFF),
        "packed_info" to hexVal(packed),
        "stamp" to hexVal(stamp),
        "infohash" to hexVal(Hashes.fullHash(packed)),
        "stamp_size" to intVal(stampSize),
        "transport_id" to hexVal(Transport.identity!!.hash),
        "transport_enabled" to boolVal(Transport.transportEnabled),
        "default_stamp_value" to intVal(DiscoveryConstants.DEFAULT_STAMP_VALUE),
    )
}

/** python InterfaceDiscovery.STATUS_CODE_MAP (Discovery.py:375). */
private fun statusCode(status: String): Int = when (status) {
    "available" -> DiscoveryConstants.STATUS_AVAILABLE
    "unknown" -> DiscoveryConstants.STATUS_UNKNOWN
    else -> DiscoveryConstants.STATUS_STALE
}

private fun discoveryThresholds(into: JsonObject): JsonObject = into.apply {
    addProperty("threshold_unknown", DiscoveryConstants.THRESHOLD_UNKNOWN)
    addProperty("threshold_stale", DiscoveryConstants.THRESHOLD_STALE)
    addProperty("threshold_remove", DiscoveryConstants.THRESHOLD_REMOVE)
    addProperty("status_available", DiscoveryConstants.STATUS_AVAILABLE)
    addProperty("status_unknown", DiscoveryConstants.STATUS_UNKNOWN)
    addProperty("status_stale", DiscoveryConstants.STATUS_STALE)
}

/** Build a genuine TCPServer announce and run it through the real receive
 * path to obtain the library's DiscoveredInterface record (no fabrication). */
private fun receiveGenuineRecord(rec: JsonObject, name: String, cost: Int): DiscoveredInterface? {
    val fields = JsonObject().apply {
        addProperty("name", name)
        addProperty("reachable_on", "example.com")
        addProperty("port", 4242)
    }
    val built = buildAnnounceAppdata(JsonObject().apply {
        addProperty("interface_type", "TCPServerInterface")
        addProperty("stamp_value", cost)
        addProperty("transport_enabled", true)
        add("fields", fields)
    })
    if (built.get("aborted").asBoolean) return null
    val appData = built.get("app_data").asString.fromHex()
    val announced = Identity.create(defaultCryptoProvider())
    var captured: DiscoveredInterface? = null
    InterfaceAnnounceHandler(requiredValue = cost, callback = { captured = it })
        .handleAnnounce(
            network.reticulum.destination.Destination.hashFromNameAndIdentity(
                "${DiscoveryConstants.APP_NAME}.discovery.interface", announced.hash),
            announced, appData)
    return captured
}

fun handleDiscoveryCommand(command: String, p: JsonObject): JsonObject {
    return when (command) {

        "discovery_build_announce_appdata" -> buildAnnounceAppdata(p)

        "discovery_receive_announce" -> {
            val appData = p.hex("app_data")
            Transport.transportEnabled = p.boolOpt("transport_enabled") ?: false
            Transport.networkIdentity = p.hexOpt("network_identity_priv")?.let {
                Identity.fromPrivateKey(it, defaultCryptoProvider())
            }
            val sources: Set<ByteArrayKey>? = p.get("discovery_sources")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?.map { ByteArrayKey(it.asString.fromHex()) }?.toSet()

            val announced = p.hexOpt("announce_identity_priv")
                ?.let { Identity.fromPrivateKey(it, defaultCryptoProvider()) }
                ?: Identity.create(defaultCryptoProvider())

            // The kotlin handler self-filters on the discovery destination
            // hash (python filters via aspect_filter upstream), so the
            // effective python behavior — received_announce always running —
            // needs the announce addressed to the announced identity's
            // discovery destination unless the test overrides it.
            val destHash = p.hexOpt("destination_hash")
                ?: network.reticulum.destination.Destination.hashFromNameAndIdentity(
                    "${DiscoveryConstants.APP_NAME}.discovery.interface", announced.hash)

            var callbackInvoked = false
            var infoNone = false
            var captured: Map<String, Any?>? = null
            val handler = if (p.boolOpt("default_required_value") == true) {
                InterfaceAnnounceHandler(rawCallback = { info ->
                    callbackInvoked = true
                    if (info == null) infoNone = true else captured = info
                })
            } else {
                InterfaceAnnounceHandler(
                    requiredValue = p.intOpt("required_value") ?: 14,
                    rawCallback = { info ->
                        callbackInvoked = true
                        if (info == null) infoNone = true else captured = info
                    },
                    discoverySources = sources,
                )
            }
            handler.handleAnnounce(destHash, announced, appData)

            val out = result(
                "callback_invoked" to boolVal(callbackInvoked),
                "callback_info_none" to boolVal(infoNone),
                "info_present" to boolVal(captured != null),
                "accepted" to boolVal(captured != null),
                "announce_identity_hash" to hexVal(announced.hash),
                "aspect_filter" to strVal(handler.aspectFilter),
                "required_value" to intVal(handler.requiredValue),
                "default_stamp_value" to intVal(DiscoveryConstants.DEFAULT_STAMP_VALUE),
            )
            captured?.let { info ->
                val safe = JsonObject()
                for ((k, v) in info) {
                    when (v) {
                        null -> safe.add(k, JsonNull.INSTANCE)
                        is ByteArray -> safe.addProperty(k, v.toHex())
                        is Boolean -> safe.addProperty(k, v)
                        is Number -> safe.addProperty(k, v)
                        else -> safe.addProperty(k, v.toString())
                    }
                }
                out.add("info", safe)
            }
            out
        }

        "discovery_stamp" -> when (val op = p.str("op")) {
            "workblock" -> {
                val wb = Stamper.generateWorkblock(
                    p.hex("material"), p.intOpt("expand_rounds") ?: DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
                result("workblock" to hexVal(wb), "length" to intVal(wb.size))
            }
            "value" -> result("value" to intVal(
                Stamper.stampValue(p.hex("workblock"), p.hex("stamp"))))
            "valid" -> result("valid" to boolVal(
                Stamper.stampValid(p.hex("stamp"), p.int("cost"), p.hex("workblock"))))
            "generate" -> {
                val wb = Stamper.generateWorkblock(
                    p.hex("material"), p.intOpt("expand_rounds") ?: DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
                val r = runBlocking { Stamper.generateStamp(wb, p.int("cost")) }
                result(
                    "stamp" to (r.stamp?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                    "value" to intVal(r.value),
                    "stamp_size" to intVal(Stamper.STAMP_SIZE),
                )
            }
            "default_cost" -> result(
                "default_stamp_value" to intVal(DiscoveryConstants.DEFAULT_STAMP_VALUE),
                "handler_default_required_value" to intVal(InterfaceAnnounceHandler().requiredValue),
            )
            else -> throw IllegalArgumentException("unknown discovery_stamp op: $op")
        }

        "discovery_validate_address" -> {
            val addr = p.str("address")
            result(
                "is_ip_address" to boolVal(DiscoveryUtil.isIpAddress(addr)),
                "is_ygg_ipv6" to boolVal(DiscoveryUtil.isYggIpv6(addr)),
                "is_hostname" to boolVal(
                    try { DiscoveryUtil.isHostname(addr) } catch (e: Exception) { false }),
            )
        }

        "discovery_sanitize_name" -> {
            val name = p.strOpt("name")
            result(
                "sanitize_name" to (DiscoveryUtil.sanitizeName(name)?.let { strVal(it) } ?: JsonNull.INSTANCE),
                "sanitize" to (DiscoveryUtil.sanitizeAnnouncerString(name)?.let { strVal(it) } ?: JsonNull.INSTANCE),
            )
        }

        "discovery_craft_announce" -> {
            // ADVERSARIAL: mutate the DECODED dict of a genuine library-built
            // announce, re-pack with the library's own packer, re-stamp with
            // the real Stamper. Flag byte 0x00 is the spec literal for an
            // unencrypted announce.
            val base = buildAnnounceAppdata(p)
            if (base.get("aborted").asBoolean) {
                return result("aborted" to boolVal(true), "app_data" to JsonNull.INSTANCE)
            }
            val packed = base.get("packed_info").asString.fromHex()
            val decoded = unpackMsgPack(packed) as? MsgPackVal.MapVal
                ?: throw IllegalStateException("library announce info did not decode to a map")
            val info = LinkedHashMap<Int, Any?>()
            for ((k, v) in decoded.entries) {
                info[(mpValToNative(k) as Number).toInt()] = mpValToNative(v)
            }

            p.get("drop_field")?.takeIf { !it.isJsonNull }?.let { info.remove(it.asInt) }
            p.strOpt("set_interface_type")?.let { info[DiscoveryConstants.INTERFACE_TYPE] = it }
            p.get("set_fields")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                val spec = el.asJsonObject
                val key = spec.get("key").asInt
                val value: Any? = when (spec.strOpt("kind") ?: "str") {
                    "bytes" -> spec.str("value").fromHex()
                    "int" -> spec.get("value").asLong
                    "float" -> spec.get("value").asDouble
                    "bool" -> spec.get("value").asBoolean
                    else -> spec.str("value")
                }
                info[key] = value
            }

            val announcer = InterfaceAnnouncer()
            val newPacked = announcer.packInfoDict(info)
            val infohash = Hashes.fullHash(newPacked)
            val wb = Stamper.generateWorkblock(infohash, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
            val r = runBlocking { Stamper.generateStamp(wb, p.intOpt("stamp_value") ?: 14) }
            val stamp = r.stamp
                ?: return result("aborted" to boolVal(true), "app_data" to JsonNull.INSTANCE)
            result(
                "aborted" to boolVal(false),
                "app_data" to hexVal(byteArrayOf(0x00) + newPacked + stamp),
                "stamp_value" to intVal(r.value),
                "stamp_size" to intVal(Stamper.STAMP_SIZE),
            )
        }

        "discovery_announce_identity" -> {
            // The library's identity selection (networkIdentity ?: identity,
            // matching python Discovery.py:54-58) runs in the real
            // InterfaceAnnouncer init, which builds the real discovery
            // Destination the hash is read off.
            val hasNet = p.boolOpt("has_network_identity") ?: false
            val netIdentity = p.hexOpt("network_identity_priv")
                ?.let { Identity.fromPrivateKey(it, defaultCryptoProvider()) }
            val baseIdentity = p.hexOpt("identity_priv")
                ?.let { Identity.fromPrivateKey(it, defaultCryptoProvider()) }
            Transport.networkIdentity = if (hasNet) netIdentity else null
            Transport.identity = baseIdentity ?: Transport.identity ?: Identity.create(defaultCryptoProvider())

            val announcer = InterfaceAnnouncer()
            val chosen = if (hasNet) netIdentity!! else (baseIdentity ?: Transport.identity!!)
            result(
                "discovery_destination_hash" to hexVal(announcer.discoveryDestination.hash),
                "chosen_identity_hash" to hexVal(chosen.hash),
                "network_identity_hash" to (netIdentity?.let { hexVal(it.hash) } ?: JsonNull.INSTANCE),
                "identity_hash" to (baseIdentity?.let { hexVal(it.hash) } ?: JsonNull.INSTANCE),
                "app_name" to strVal(DiscoveryConstants.APP_NAME),
            )
        }

        "discovery_inject_records" -> {
            Transport.transportEnabled = true
            ensureTransportIdentity(p)
            val storage = java.nio.file.Files.createTempDirectory("rns_inject_").toFile()
            try {
                val disc = network.reticulum.discovery.InterfaceDiscovery(storagePath = storage.absolutePath)
                val nowSec = System.currentTimeMillis() / 1000L
                val requested = JsonArray()
                for (recEl in p.get("records").asJsonArray) {
                    val rec = recEl.asJsonObject
                    val name = rec.str("name")
                    val age = rec.get("age_seconds").asLong
                    val cost = rec.intOpt("stamp_value") ?: 6
                    val record = receiveGenuineRecord(rec, name, cost)
                        ?: throw IllegalStateException("could not build genuine record for $name")
                    val aged = record.copy(
                        received = nowSec - age,
                        lastHeard = nowSec - age,
                        stampValue = rec.intOpt("value") ?: record.stampValue,
                    )
                    disc.interfaceDiscovered(aged)
                    requested.add(JsonObject().apply {
                        addProperty("name", name)
                        addProperty("discovery_hash", record.discoveryHash.toHex())
                    })
                }
                val listed = JsonArray()
                for ((info, status) in disc.listDiscovered()) {
                    listed.add(JsonObject().apply {
                        addProperty("name", info.name)
                        addProperty("status", status)
                        addProperty("status_code", statusCode(status))
                        addProperty("value", info.stampValue)
                        addProperty("last_heard", info.lastHeard)
                        addProperty("discovery_hash", info.discoveryHash.toHex())
                    })
                }
                discoveryThresholds(result(
                    "requested" to requested,
                    "listed" to listed,
                ))
            } finally {
                storage.deleteRecursively()
            }
        }

        "discovery_store_record" -> {
            Transport.transportEnabled = true
            ensureTransportIdentity(p)
            val storage = java.nio.file.Files.createTempDirectory("rns_store_").toFile()
            try {
                val stampValue = p.intOpt("stamp_value") ?: 6
                val setType = p.strOpt("set_interface_type")
                val repeat = p.intOpt("repeat") ?: 1
                val fields = p.get("fields")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: JsonObject().apply {
                        addProperty("name", p.strOpt("name") ?: "Node")
                        addProperty("reachable_on", "example.com")
                        addProperty("port", 4242)
                    }
                val announced = p.hexOpt("announce_identity_priv")
                    ?.let { Identity.fromPrivateKey(it, defaultCryptoProvider()) }
                    ?: Identity.create(defaultCryptoProvider())

                // Build the announce app_data once: genuine, or adversarially
                // retyped when set_interface_type forces a type the sender
                // would otherwise rewrite.
                val base = JsonObject().apply {
                    addProperty("interface_type", "TCPServerInterface")
                    addProperty("stamp_value", stampValue)
                    addProperty("transport_enabled", true)
                    add("fields", fields)
                }
                val appData = if (setType != null) {
                    base.addProperty("set_interface_type", setType)
                    handleDiscoveryCommand("discovery_craft_announce", base).get("app_data").asString.fromHex()
                } else {
                    buildAnnounceAppdata(base).get("app_data").asString.fromHex()
                }

                // List-time source allowlist (may differ from receive time).
                val listSources: Set<ByteArrayKey>? = p.get("list_sources")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.map { ByteArrayKey(it.asString.fromHex()) }?.toSet()
                val recvSources: Set<ByteArrayKey>? = p.get("recv_sources")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.map { ByteArrayKey(it.asString.fromHex()) }?.toSet()

                // The store path applies no source filter; the listing does.
                val disc = network.reticulum.discovery.InterfaceDiscovery(
                    storagePath = storage.absolutePath,
                    requiredValue = stampValue,
                    discoverySources = listSources,
                )

                var recordType: String? = null
                var discoveryHash: ByteArray? = null
                var receivedOk = false
                repeat(repeat) {
                    var captured: DiscoveredInterface? = null
                    val handler = InterfaceAnnounceHandler(
                        requiredValue = stampValue,
                        callback = { captured = it },
                        discoverySources = recvSources,
                    )
                    handler.handleAnnounce(
                        network.reticulum.destination.Destination.hashFromNameAndIdentity(
                            "${DiscoveryConstants.APP_NAME}.discovery.interface", announced.hash),
                        announced, appData)
                    val rec = captured ?: return@repeat
                    receivedOk = true
                    recordType = rec.type
                    discoveryHash = rec.discoveryHash
                    disc.interfaceDiscovered(rec)
                }

                val storedFile = discoveryHash?.let {
                    java.io.File(java.io.File(storage, "discovery/interfaces"), it.toHexString())
                }
                val stored = storedFile?.isFile == true

                val listed = disc.listDiscovered()
                val listedNames = JsonArray().apply { listed.forEach { add(it.first.name) } }
                val heardCount = discoveryHash?.let { dh ->
                    listed.firstOrNull { it.first.discoveryHash.contentEquals(dh) }?.first?.heardCount
                }

                result(
                    "received" to boolVal(receivedOk),
                    "record_type" to (recordType?.let { strVal(it) } ?: JsonNull.INSTANCE),
                    "stored" to boolVal(stored),
                    "heard_count" to (heardCount?.let { intVal(it) } ?: JsonNull.INSTANCE),
                    "discovery_hash" to (discoveryHash?.let { hexVal(it) } ?: JsonNull.INSTANCE),
                    "listed_names" to listedNames,
                    "announce_identity_hash" to hexVal(announced.hash),
                    "discoverable_types" to JsonArray().apply {
                        DiscoveryConstants.STORAGE_DISCOVERABLE_TYPES.forEach { add(it) }
                    },
                    "discoverable_interface_types" to JsonArray().apply {
                        DiscoveryConstants.DISCOVERABLE_INTERFACE_TYPES.forEach { add(it) }
                    },
                )
            } finally {
                storage.deleteRecursively()
            }
        }

        "discovery_feature_defaults" -> {
            // Read off a FRESH library Interface instance, exactly as the
            // reference reads a fresh RNS Interface() (Interface.py:105-106).
            val fresh = object : network.reticulum.interfaces.Interface("conformance-fresh") {
                override fun processOutgoing(data: ByteArray) {}
                override fun start() {}
            }
            result(
                "interface_discoverable" to boolVal(fresh.discoverable),
                "interface_supports_discovery" to boolVal(fresh.supportsDiscovery),
                "discover_interfaces" to boolVal(Reticulum.discoverInterfaces),
                "should_autoconnect_discovered_interfaces" to boolVal(
                    Reticulum.shouldAutoconnectDiscoveredInterfaces()),
                "max_autoconnected_interfaces" to intVal(Reticulum.maxAutoconnectedInterfaces()),
            )
        }

        else -> throw IllegalArgumentException("Unknown discovery command: $command")
    }
}
