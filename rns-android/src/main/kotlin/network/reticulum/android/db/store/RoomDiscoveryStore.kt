package network.reticulum.android.db.store

import network.reticulum.android.db.dao.DiscoveredInterfaceDao
import network.reticulum.android.db.entity.DiscoveredInterfaceEntity
import network.reticulum.discovery.DiscoveredInterface
import network.reticulum.storage.DiscoveryStore
import java.util.concurrent.ExecutorService

class RoomDiscoveryStore(
    private val dao: DiscoveredInterfaceDao,
    private val writeExecutor: ExecutorService
) : DiscoveryStore {

    override fun upsertDiscovered(info: DiscoveredInterface) {
        val entity = DiscoveredInterfaceEntity(
            discoveryHash = info.discoveryHash.copyOf(),
            type = info.type,
            transport = info.transport,
            name = info.name,
            received = info.received,
            stampValue = info.stampValue,
            transportId = info.transportId,
            networkId = info.networkId,
            hops = info.hops,
            latitude = info.latitude,
            longitude = info.longitude,
            height = info.height,
            reachableOn = info.reachableOn,
            port = info.port,
            frequency = info.frequency,
            bandwidth = info.bandwidth,
            spreadingFactor = info.spreadingFactor,
            codingRate = info.codingRate,
            modulation = info.modulation,
            channel = info.channel,
            ifacNetname = info.ifacNetname,
            ifacNetkey = info.ifacNetkey,
            discovered = info.discovered,
            lastHeard = info.lastHeard,
            heardCount = info.heardCount
        )
        writeExecutor.execute { dao.upsert(entity) }
    }

    override fun getDiscovered(discoveryHash: ByteArray): DiscoveredInterface? {
        val e = dao.getByHash(discoveryHash) ?: return null
        return toDiscoveredInterface(e)
    }

    override fun loadAllDiscovered(): List<DiscoveredInterface> {
        return dao.getAll().map { toDiscoveredInterface(it) }
    }

    override fun removeDiscovered(discoveryHash: ByteArray) {
        val hash = discoveryHash.copyOf()
        writeExecutor.execute { dao.deleteByHash(hash) }
    }

    override fun removeOlderThan(thresholdSeconds: Long) {
        writeExecutor.execute { dao.deleteOlderThan(thresholdSeconds) }
    }

    private fun toDiscoveredInterface(e: DiscoveredInterfaceEntity): DiscoveredInterface {
        return DiscoveredInterface(
            type = e.type,
            transport = e.transport,
            name = e.name,
            received = e.received,
            stampValue = e.stampValue,
            transportId = e.transportId,
            networkId = e.networkId,
            hops = e.hops,
            latitude = e.latitude,
            longitude = e.longitude,
            height = e.height,
            reachableOn = e.reachableOn,
            port = e.port,
            frequency = e.frequency,
            bandwidth = e.bandwidth,
            spreadingFactor = e.spreadingFactor,
            codingRate = e.codingRate,
            modulation = e.modulation,
            channel = e.channel,
            ifacNetname = e.ifacNetname,
            ifacNetkey = e.ifacNetkey,
            discoveryHash = e.discoveryHash,
            discovered = e.discovered,
            lastHeard = e.lastHeard,
            heardCount = e.heardCount
        )
    }
}
