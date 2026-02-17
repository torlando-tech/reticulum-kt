package network.reticulum.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.transport.ByteArrayKey
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

/**
 * Periodically sends discovery announces for local interfaces.
 *
 * Each announce cycle:
 * 1. Find interfaces where supportsDiscovery && discoverable && overdue for announce
 * 2. Pick the most overdue interface
 * 3. Build info dict + PoW stamp (cached by content hash)
 * 4. Optionally encrypt with network identity
 * 5. Send via discoveryDestination.announce(appData = payload)
 */
class InterfaceAnnouncer {
    private var shouldRun = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stampCache = HashMap<ByteArrayKey, ByteArray>()

    val discoveryDestination: Destination

    init {
        val identity = Transport.networkIdentity ?: Transport.identity
            ?: throw IllegalStateException("Cannot create InterfaceAnnouncer without Transport identity")

        discoveryDestination = Destination.create(
            identity,
            DestinationDirection.IN,
            DestinationType.SINGLE,
            DiscoveryConstants.APP_NAME,
            "discovery", "interface"
        )
    }

    fun start() {
        if (shouldRun) return
        shouldRun = true
        job = scope.launch { runJob() }
    }

    fun stop() {
        shouldRun = false
        job?.cancel()
        job = null
    }

    private suspend fun runJob() {
        while (shouldRun && coroutineContext[kotlinx.coroutines.Job]?.isActive != false) {
            delay(DiscoveryConstants.JOB_INTERVAL * 1000)
            try {
                val now = System.currentTimeMillis() / 1000L
                val dueInterfaces = Transport.getInterfaces()
                    .filter { it.supportsDiscovery && it.discoverable }
                    .filter { now > it.lastDiscoveryAnnounce + it.discoveryAnnounceInterval }
                    .sortedByDescending { now - it.lastDiscoveryAnnounce }

                if (dueInterfaces.isNotEmpty()) {
                    val selected = dueInterfaces.first()
                    selected.lastDiscoveryAnnounce = System.currentTimeMillis() / 1000L
                    val appData = getInterfaceAnnounceData(selected)
                    if (appData != null) {
                        discoveryDestination.announce(appData = appData)
                    }
                }
            } catch (e: Exception) {
                // Log and continue
                println("[Discovery] Error preparing interface discovery announces: ${e.message}")
            }
        }
    }

    internal fun getInterfaceAnnounceData(iface: InterfaceRef): ByteArray? {
        val interfaceType = iface.discoveryInterfaceType
        if (interfaceType !in DiscoveryConstants.DISCOVERABLE_INTERFACE_TYPES) return null

        val stampValue = iface.discoveryStampValue ?: DiscoveryConstants.DEFAULT_STAMP_VALUE
        val transportIdentity = Transport.identity ?: return null

        var flags: Byte = 0x00

        // Build base info dict
        val info = HashMap<Int, Any?>()
        info[DiscoveryConstants.INTERFACE_TYPE] = interfaceType
        info[DiscoveryConstants.TRANSPORT] = Transport.transportEnabled
        info[DiscoveryConstants.TRANSPORT_ID] = transportIdentity.hash
        info[DiscoveryConstants.NAME] = sanitize(iface.discoveryName ?: iface.name)
        info[DiscoveryConstants.LATITUDE] = iface.discoveryLatitude
        info[DiscoveryConstants.LONGITUDE] = iface.discoveryLongitude
        info[DiscoveryConstants.HEIGHT] = iface.discoveryHeight

        // Add type-specific data
        val typeData = iface.getDiscoveryData()
        if (typeData != null) {
            info.putAll(typeData)
        }

        // Publish IFAC credentials if requested
        if (iface.discoveryPublishIfac) {
            iface.ifacNetname?.let { info[DiscoveryConstants.IFAC_NETNAME] = sanitize(it) }
            iface.ifacNetkey?.let { info[DiscoveryConstants.IFAC_NETKEY] = sanitize(it) }
        }

        // Pack with msgpack
        val packed = packInfoDict(info)
        val infohash = Hashes.fullHash(packed)
        val infohashKey = ByteArrayKey(infohash)

        // Get or generate stamp
        val stamp = stampCache[infohashKey] ?: run {
            val workblock = Stamper.generateWorkblock(infohash, DiscoveryConstants.WORKBLOCK_EXPAND_ROUNDS)
            val result = runBlocking { Stamper.generateStamp(workblock, stampValue) }
            result.stamp ?: return null
        }
        stampCache[infohashKey] = stamp

        // Optionally encrypt
        val payload: ByteArray
        if (iface.discoveryEncrypt) {
            flags = (flags.toInt() or DiscoveryConstants.FLAG_ENCRYPTED.toInt()).toByte()
            val networkId = Transport.networkIdentity
            if (networkId == null) return null
            payload = networkId.encrypt(packed + stamp)
        } else {
            payload = packed + stamp
        }

        return byteArrayOf(flags) + payload
    }

    /**
     * Pack info dict using msgpack with integer keys.
     * Keys 0x00-0x0E are type-specific, 0xFE-0xFF are base.
     * Key 0xFF must encode as uint8 (0xcc 0xff), not fixint.
     */
    internal fun packInfoDict(info: Map<Int, Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)

        // Filter out null values to match Python behavior (None values are included in Python)
        packer.packMapHeader(info.size)
        for ((key, value) in info) {
            packer.packInt(key)
            packValue(packer, value)
        }
        packer.flush()
        return out.toByteArray()
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

    private fun sanitize(str: String): String =
        str.replace("\n", "").replace("\r", "").trim()
}
