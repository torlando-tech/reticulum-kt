package network.reticulum.discovery

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import network.reticulum.crypto.Hashes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("InterfaceDiscovery Persistence")
class InterfaceDiscoveryTest {

    @TempDir
    lateinit var tempDir: File

    private fun makeDiscoveredInterface(
        name: String = "Test TCP",
        type: String = "TCPServerInterface",
        transportId: String = "0102030405060708090a0b0c0d0e0f10",
        received: Long = System.currentTimeMillis() / 1000L,
    ): DiscoveredInterface {
        val discoveryHash = Hashes.fullHash(
            (transportId + name).toByteArray(Charsets.UTF_8)
        )
        return DiscoveredInterface(
            type = type,
            transport = true,
            name = name,
            received = received,
            stampValue = 14,
            transportId = transportId,
            networkId = "aabbccdd11223344aabbccdd11223344",
            hops = 1,
            latitude = null,
            longitude = null,
            height = null,
            reachableOn = "192.168.1.1",
            port = 4242,
            frequency = null,
            bandwidth = null,
            spreadingFactor = null,
            codingRate = null,
            modulation = null,
            channel = null,
            ifacNetname = null,
            ifacNetkey = null,
            discoveryHash = discoveryHash,
            discovered = received,
            lastHeard = received,
            heardCount = 0,
        )
    }

    @Test
    @DisplayName("Save and list discovered interfaces")
    fun `save and list round trip`() {
        val discovery = InterfaceDiscovery(
            storagePath = tempDir.absolutePath,
            requiredValue = 14,
        )

        val now = System.currentTimeMillis() / 1000L
        val info = makeDiscoveredInterface(received = now)

        // Simulate the handler callback path — manually persist
        val dir = File(tempDir, "discovery/interfaces")
        dir.mkdirs()

        // Use the same persistence mechanism the class uses internally
        // We call the discovery's onInterfaceDiscovered indirectly by saving directly
        val filename = info.discoveryHash.joinToString("") { "%02x".format(it) }
        saveInfo(File(dir, filename), info)

        val listed = discovery.listDiscovered()
        listed shouldHaveSize 1
        listed[0].first.name shouldBe "Test TCP"
        listed[0].first.type shouldBe "TCPServerInterface"
        listed[0].second shouldBe "available"
    }

    @Test
    @DisplayName("Status thresholds: available, unknown, stale")
    fun `status thresholds work correctly`() {
        val dir = File(tempDir, "discovery/interfaces")
        dir.mkdirs()

        val now = System.currentTimeMillis() / 1000L

        // Recent — available
        val recent = makeDiscoveredInterface(name = "Recent", received = now)
        recent.lastHeard = now
        saveInfo(File(dir, recent.discoveryHash.joinToString("") { "%02x".format(it) }), recent)

        // 2 days ago — unknown
        val unknown = makeDiscoveredInterface(
            name = "Unknown",
            transportId = "ff02030405060708090a0b0c0d0e0f10",
            received = now - 2 * 24 * 60 * 60
        )
        unknown.lastHeard = now - 2 * 24 * 60 * 60
        saveInfo(File(dir, unknown.discoveryHash.joinToString("") { "%02x".format(it) }), unknown)

        // 5 days ago — stale
        val stale = makeDiscoveredInterface(
            name = "Stale",
            transportId = "ee02030405060708090a0b0c0d0e0f10",
            received = now - 5 * 24 * 60 * 60
        )
        stale.lastHeard = now - 5 * 24 * 60 * 60
        saveInfo(File(dir, stale.discoveryHash.joinToString("") { "%02x".format(it) }), stale)

        val discovery = InterfaceDiscovery(
            storagePath = tempDir.absolutePath,
            requiredValue = 14,
        )

        val listed = discovery.listDiscovered()
        listed shouldHaveSize 3

        // Sorted by status_code desc: available first, then unknown, then stale
        listed[0].second shouldBe "available"
        listed[0].first.name shouldBe "Recent"
        listed[1].second shouldBe "unknown"
        listed[1].first.name shouldBe "Unknown"
        listed[2].second shouldBe "stale"
        listed[2].first.name shouldBe "Stale"
    }

    @Test
    @DisplayName("Entries older than 7 days are removed")
    fun `stale entries are pruned`() {
        val dir = File(tempDir, "discovery/interfaces")
        dir.mkdirs()

        val now = System.currentTimeMillis() / 1000L

        val old = makeDiscoveredInterface(
            name = "Very Old",
            received = now - 8 * 24 * 60 * 60
        )
        old.lastHeard = now - 8 * 24 * 60 * 60
        val filename = old.discoveryHash.joinToString("") { "%02x".format(it) }
        val file = File(dir, filename)
        saveInfo(file, old)
        file.exists() shouldBe true

        val discovery = InterfaceDiscovery(
            storagePath = tempDir.absolutePath,
            requiredValue = 14,
        )

        val listed = discovery.listDiscovered()
        listed shouldHaveSize 0
        file.exists() shouldBe false
    }

    /**
     * Save a DiscoveredInterface to disk using msgpack, matching the format
     * used by InterfaceDiscovery's internal persistence.
     */
    private fun saveInfo(file: File, info: DiscoveredInterface) {
        val out = java.io.ByteArrayOutputStream()
        val packer = org.msgpack.core.MessagePack.newDefaultPacker(out)

        val fields = mutableMapOf<String, Any?>(
            "type" to info.type,
            "transport" to info.transport,
            "name" to info.name,
            "received" to info.received,
            "value" to info.stampValue,
            "transport_id" to info.transportId,
            "network_id" to info.networkId,
            "hops" to info.hops,
            "latitude" to info.latitude,
            "longitude" to info.longitude,
            "height" to info.height,
            "reachable_on" to info.reachableOn,
            "port" to info.port,
            "frequency" to info.frequency,
            "bandwidth" to info.bandwidth,
            "sf" to info.spreadingFactor,
            "cr" to info.codingRate,
            "modulation" to info.modulation,
            "channel" to info.channel,
            "ifac_netname" to info.ifacNetname,
            "ifac_netkey" to info.ifacNetkey,
            "discovery_hash" to info.discoveryHash,
            "discovered" to info.discovered,
            "last_heard" to info.lastHeard,
            "heard_count" to info.heardCount,
        )

        packer.packMapHeader(fields.size)
        for ((key, value) in fields) {
            packer.packString(key)
            when (value) {
                null -> packer.packNil()
                is Boolean -> packer.packBoolean(value)
                is Int -> packer.packInt(value)
                is Long -> packer.packLong(value)
                is Double -> packer.packDouble(value)
                is String -> packer.packString(value)
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                else -> packer.packString(value.toString())
            }
        }
        packer.flush()
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
