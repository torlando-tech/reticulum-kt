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
        writeExecutor.execute {
            // Fetch all hashes from DB, compute which to delete, then delete in chunks.
            // This avoids the SQLite 999-variable limit on NOT IN clauses.
            val allHashes = dao.getAllHashes()
            val activeSet = activeHashes.map { it.bytes.toList() }.toSet()
            val toDelete = allHashes.filter { it.toList() !in activeSet }
            toDelete.chunked(900).forEach { chunk ->
                for (hash in chunk) {
                    dao.deleteByHash(hash)
                }
            }
        }
    }
}
