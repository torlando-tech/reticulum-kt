package network.reticulum.android.db.store

import network.reticulum.android.db.dao.TunnelDao
import network.reticulum.android.db.dao.TunnelPathDao
import network.reticulum.android.db.entity.TunnelEntity
import network.reticulum.android.db.entity.TunnelPathEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.storage.TunnelStore
import network.reticulum.transport.TunnelInfo
import network.reticulum.transport.TunnelPathEntry as CoreTunnelPathEntry
import java.util.concurrent.ExecutorService

class RoomTunnelStore(
    private val tunnelDao: TunnelDao,
    private val tunnelPathDao: TunnelPathDao,
    private val writeExecutor: ExecutorService
) : TunnelStore {

    override fun upsertTunnel(tunnelId: ByteArray, interfaceHash: ByteArray?, expires: Long) {
        val entity = TunnelEntity(
            tunnelId = tunnelId.copyOf(),
            interfaceHash = interfaceHash?.copyOf(),
            expires = expires
        )
        writeExecutor.execute { tunnelDao.upsert(entity) }
    }

    override fun upsertTunnelPath(tunnelId: ByteArray, destHash: ByteArray, path: CoreTunnelPathEntry) {
        val entity = TunnelPathEntity(
            tunnelId = tunnelId.copyOf(),
            destHash = destHash.copyOf(),
            timestamp = path.timestamp,
            receivedFrom = path.receivedFrom.copyOf(),
            hops = path.hops,
            expires = path.expires,
            packetHash = path.packetHash.copyOf()
        )
        writeExecutor.execute { tunnelPathDao.upsert(entity) }
    }

    override fun removeTunnel(tunnelId: ByteArray) {
        val id = tunnelId.copyOf()
        writeExecutor.execute { tunnelDao.deleteById(id) }
    }

    override fun loadAllTunnels(): Map<ByteArrayKey, TunnelInfo> {
        val tunnels = tunnelDao.getAll()
        return tunnels.associate { t ->
            val paths = tunnelPathDao.getByTunnelId(t.tunnelId)
            val pathMap = mutableMapOf<ByteArrayKey, CoreTunnelPathEntry>()
            for (p in paths) {
                pathMap[p.destHash.toKey()] = CoreTunnelPathEntry(
                    timestamp = p.timestamp,
                    receivedFrom = p.receivedFrom,
                    hops = p.hops,
                    expires = p.expires,
                    randomBlobs = mutableListOf(),
                    packetHash = p.packetHash
                )
            }
            t.tunnelId.toKey() to TunnelInfo(
                tunnelId = t.tunnelId,
                interface_ = null,
                expires = t.expires,
                paths = pathMap
            )
        }
    }

    override fun removeExpiredBefore(timestampMs: Long) {
        writeExecutor.execute { tunnelDao.deleteExpiredBefore(timestampMs) }
    }
}
