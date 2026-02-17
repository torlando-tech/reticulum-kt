package network.reticulum.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.transport.ByteArrayKey
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * High-level interface discovery manager.
 *
 * Coordinates:
 * - Registering an InterfaceAnnounceHandler with Transport
 * - Persisting discovered interfaces to disk
 * - Status tracking (available / unknown / stale / remove)
 * - Auto-connect via a pluggable factory
 * - Monitoring auto-connected interfaces for liveness
 *
 * Matches Python Discovery.py InterfaceDiscovery class.
 */
class InterfaceDiscovery(
    private val storagePath: String,
    private val requiredValue: Int = DiscoveryConstants.DEFAULT_STAMP_VALUE,
    private val discoverySources: Set<ByteArrayKey>? = null,
    private val autoConnectFactory: ((DiscoveredInterface) -> InterfaceRef?)? = null,
    private val maxAutoConnected: Int = 0,
    private val discoveryCallback: ((DiscoveredInterface) -> Unit)? = null,
) {
    private var handler: InterfaceAnnounceHandler? = null
    private val monitoredInterfaces = mutableListOf<MonitoredInterface>()
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val discoveryDir: File
        get() = File(storagePath, "discovery/interfaces").also { it.mkdirs() }

    private data class MonitoredInterface(
        val ref: InterfaceRef,
        val endpointHash: ByteArray,
        var downSince: Long? = null,
    )

    fun start() {
        handler = InterfaceAnnounceHandler(
            requiredValue = requiredValue,
            callback = ::onInterfaceDiscovered,
            discoverySources = discoverySources,
        )
        Transport.registerAnnounceHandler(handler!!)

        // Reconnect previously discovered interfaces
        if (autoConnectFactory != null && maxAutoConnected > 0) {
            scope.launch { connectDiscovered() }
        }
    }

    fun stop() {
        handler?.let { Transport.deregisterAnnounceHandler(it) }
        handler = null
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * List all discovered interfaces from disk, applying status thresholds and pruning stale entries.
     * Returns sorted by (status_code desc, stamp_value desc, last_heard desc).
     */
    fun listDiscovered(): List<Pair<DiscoveredInterface, String>> {
        val now = System.currentTimeMillis() / 1000L
        val results = mutableListOf<Pair<DiscoveredInterface, String>>()
        val dir = discoveryDir
        if (!dir.exists()) return results

        for (file in dir.listFiles() ?: emptyArray()) {
            try {
                val info = loadDiscoveredInterface(file) ?: continue
                val heardDelta = now - info.lastHeard

                // Check removal thresholds
                val shouldRemove = heardDelta > DiscoveryConstants.THRESHOLD_REMOVE
                    || (discoverySources != null && !discoverySources.contains(
                        ByteArrayKey(hexToBytes(info.networkId))))

                if (shouldRemove) {
                    file.delete()
                    continue
                }

                val status = when {
                    heardDelta > DiscoveryConstants.THRESHOLD_STALE -> "stale"
                    heardDelta > DiscoveryConstants.THRESHOLD_UNKNOWN -> "unknown"
                    else -> "available"
                }

                results.add(info to status)
            } catch (e: Exception) {
                println("[Discovery] Error loading discovered interface data from ${file.name}: ${e.message}")
            }
        }

        val statusOrder = mapOf(
            "available" to DiscoveryConstants.STATUS_AVAILABLE,
            "unknown" to DiscoveryConstants.STATUS_UNKNOWN,
            "stale" to DiscoveryConstants.STATUS_STALE,
        )
        results.sortWith(compareByDescending<Pair<DiscoveredInterface, String>> {
            statusOrder[it.second] ?: 0
        }.thenByDescending { it.first.stampValue }.thenByDescending { it.first.lastHeard })

        return results
    }

    private fun onInterfaceDiscovered(info: DiscoveredInterface) {
        try {
            val filename = info.discoveryHash.toHexString()
            val file = File(discoveryDir, filename)

            if (!file.exists()) {
                info.discovered = info.received
                info.lastHeard = info.received
                info.heardCount = 0
            } else {
                val existing = loadDiscoveredInterface(file)
                info.discovered = existing?.discovered ?: info.received
                info.heardCount = (existing?.heardCount ?: 0) + 1
                info.lastHeard = info.received
            }

            saveDiscoveredInterface(file, info)
        } catch (e: Exception) {
            println("[Discovery] Error persisting discovered interface: ${e.message}")
            return
        }

        autoconnect(info)

        try {
            discoveryCallback?.invoke(info)
        } catch (e: Exception) {
            println("[Discovery] Error in external discovery callback: ${e.message}")
        }
    }

    private fun autoconnect(info: DiscoveredInterface) {
        if (autoConnectFactory == null || maxAutoConnected <= 0) return
        if (info.type !in DiscoveryConstants.AUTOCONNECT_TYPES) return

        val currentAutoconnected = monitoredInterfaces.size
        if (currentAutoconnected >= maxAutoConnected) return

        // Check for duplicate endpoint
        val endpointSpecifier = buildString {
            info.reachableOn?.let { append(it) }
            info.port?.let { append(":$it") }
        }
        val endpointHash = Hashes.fullHash(endpointSpecifier.toByteArray(Charsets.UTF_8))

        val alreadyExists = monitoredInterfaces.any {
            it.endpointHash.contentEquals(endpointHash)
        }
        if (alreadyExists) return

        try {
            val iface = autoConnectFactory.invoke(info) ?: return
            val monitored = MonitoredInterface(iface, endpointHash)
            monitoredInterfaces.add(monitored)
            startMonitorIfNeeded()
        } catch (e: Exception) {
            println("[Discovery] Error auto-connecting discovered interface: ${e.message}")
        }
    }

    private fun connectDiscovered() {
        try {
            val discovered = listDiscovered()
            for ((info, _) in discovered) {
                if (monitoredInterfaces.size >= maxAutoConnected) break
                autoconnect(info)
            }
        } catch (e: Exception) {
            println("[Discovery] Error reconnecting discovered interfaces: ${e.message}")
        }
    }

    private fun startMonitorIfNeeded() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        while (coroutineContext[kotlinx.coroutines.Job]?.isActive != false && monitoredInterfaces.isNotEmpty()) {
            delay(DiscoveryConstants.MONITOR_INTERVAL * 1000)
            val toDetach = mutableListOf<MonitoredInterface>()
            var onlineCount = 0

            for (monitored in monitoredInterfaces) {
                try {
                    if (monitored.ref.online) {
                        onlineCount++
                        monitored.downSince = null
                    } else {
                        if (monitored.downSince == null) {
                            monitored.downSince = System.currentTimeMillis() / 1000L
                        } else {
                            val downFor = (System.currentTimeMillis() / 1000L) - monitored.downSince!!
                            if (downFor >= DiscoveryConstants.DETACH_THRESHOLD) {
                                toDetach.add(monitored)
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[Discovery] Error checking auto-connected interface state: ${e.message}")
                }
            }

            for (m in toDetach) {
                monitoredInterfaces.remove(m)
            }
        }
    }

    // ==================== Persistence ====================

    private fun saveDiscoveredInterface(file: File, info: DiscoveredInterface) {
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)

        // Pack as a map matching Python's dict structure
        val fields = mutableMapOf<String, Any?>()
        fields["type"] = info.type
        fields["transport"] = info.transport
        fields["name"] = info.name
        fields["received"] = info.received
        fields["value"] = info.stampValue
        fields["transport_id"] = info.transportId
        fields["network_id"] = info.networkId
        fields["hops"] = info.hops
        fields["latitude"] = info.latitude
        fields["longitude"] = info.longitude
        fields["height"] = info.height
        fields["reachable_on"] = info.reachableOn
        fields["port"] = info.port
        fields["frequency"] = info.frequency
        fields["bandwidth"] = info.bandwidth
        fields["sf"] = info.spreadingFactor
        fields["cr"] = info.codingRate
        fields["modulation"] = info.modulation
        fields["channel"] = info.channel
        fields["ifac_netname"] = info.ifacNetname
        fields["ifac_netkey"] = info.ifacNetkey
        fields["discovery_hash"] = info.discoveryHash
        fields["discovered"] = info.discovered
        fields["last_heard"] = info.lastHeard
        fields["heard_count"] = info.heardCount

        packer.packMapHeader(fields.size)
        for ((key, value) in fields) {
            packer.packString(key)
            packValue(packer, value)
        }
        packer.flush()

        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    private fun loadDiscoveredInterface(file: File): DiscoveredInterface? {
        if (!file.exists()) return null
        val data = file.readBytes()
        val unpacker = MessagePack.newDefaultUnpacker(data)
        val mapSize = unpacker.unpackMapHeader()
        val fields = HashMap<String, Any?>(mapSize)
        repeat(mapSize) {
            val key = unpacker.unpackString()
            fields[key] = unpackValue(unpacker)
        }

        val discoveryHash = fields["discovery_hash"]
        val hashBytes = when (discoveryHash) {
            is ByteArray -> discoveryHash
            else -> return null
        }

        return DiscoveredInterface(
            type = fields["type"] as? String ?: return null,
            transport = fields["transport"] as? Boolean ?: false,
            name = fields["name"] as? String ?: return null,
            received = (fields["received"] as? Number)?.toLong() ?: 0,
            stampValue = (fields["value"] as? Number)?.toInt() ?: 0,
            transportId = fields["transport_id"] as? String ?: return null,
            networkId = fields["network_id"] as? String ?: return null,
            hops = (fields["hops"] as? Number)?.toInt() ?: 0,
            latitude = (fields["latitude"] as? Number)?.toDouble(),
            longitude = (fields["longitude"] as? Number)?.toDouble(),
            height = (fields["height"] as? Number)?.toDouble(),
            reachableOn = fields["reachable_on"] as? String,
            port = (fields["port"] as? Number)?.toInt(),
            frequency = (fields["frequency"] as? Number)?.toLong(),
            bandwidth = (fields["bandwidth"] as? Number)?.toLong(),
            spreadingFactor = (fields["sf"] as? Number)?.toInt(),
            codingRate = (fields["cr"] as? Number)?.toInt(),
            modulation = fields["modulation"] as? String,
            channel = (fields["channel"] as? Number)?.toInt(),
            ifacNetname = fields["ifac_netname"] as? String,
            ifacNetkey = fields["ifac_netkey"] as? String,
            discoveryHash = hashBytes,
            discovered = (fields["discovered"] as? Number)?.toLong() ?: 0,
            lastHeard = (fields["last_heard"] as? Number)?.toLong() ?: 0,
            heardCount = (fields["heard_count"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun packValue(packer: org.msgpack.core.MessagePacker, value: Any?) {
        when (value) {
            null -> packer.packNil()
            is Boolean -> packer.packBoolean(value)
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is Double -> packer.packDouble(value)
            is Float -> packer.packFloat(value)
            is String -> packer.packString(value)
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            else -> packer.packString(value.toString())
        }
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> {
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

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4)
                + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
