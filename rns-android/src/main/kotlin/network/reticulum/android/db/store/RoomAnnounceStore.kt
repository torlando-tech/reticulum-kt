package network.reticulum.android.db.store

import network.reticulum.android.db.dao.AnnounceCacheDao
import network.reticulum.android.db.entity.AnnounceCacheEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.storage.AnnounceStore
import java.util.concurrent.ExecutorService

class RoomAnnounceStore(
    private val dao: AnnounceCacheDao,
    private val writeExecutor: ExecutorService
) : AnnounceStore {

    override fun cacheAnnounce(packetHash: ByteArray, raw: ByteArray, interfaceName: String) {
        val entity = AnnounceCacheEntity(
            packetHash = packetHash.copyOf(),
            raw = raw.copyOf(),
            interfaceName = interfaceName
        )
        writeExecutor.execute { dao.upsert(entity) }
    }

    override fun getAnnounce(packetHash: ByteArray): Pair<ByteArray, String?>? {
        val entity = dao.getByHash(packetHash) ?: return null
        return entity.raw to entity.interfaceName
    }

    override fun removeAnnounce(packetHash: ByteArray) {
        val hash = packetHash.copyOf()
        writeExecutor.execute { dao.deleteByHash(hash) }
    }

    override fun removeAllExcept(activeHashes: Set<ByteArrayKey>) {
        val activeList = activeHashes.map { it.bytes }
        writeExecutor.execute {
            // SQLite has a limit on IN clause parameters; chunk if needed
            if (activeList.isEmpty()) {
                // No active hashes means delete everything — but getAllHashes + deleteByHash
                // is simpler than a raw "DELETE FROM announce_cache"
                dao.deleteAllExcept(emptyList())
            } else {
                dao.deleteAllExcept(activeList)
            }
        }
    }
}
