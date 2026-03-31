package network.reticulum.android.db.store

import network.reticulum.android.db.dao.PacketHashDao
import network.reticulum.android.db.entity.PacketHashEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.storage.PacketHashStore
import java.util.concurrent.ExecutorService

class RoomPacketHashStore(
    private val dao: PacketHashDao,
    private val writeExecutor: ExecutorService
) : PacketHashStore {

    override fun saveAll(hashes: Set<ByteArrayKey>, generation: Int) {
        val entities = hashes.map { PacketHashEntity(hash = it.bytes, generation = generation) }
        writeExecutor.execute {
            dao.deleteByGeneration(generation)
            // Insert in batches to avoid SQLite variable limit
            entities.chunked(500).forEach { batch ->
                dao.insertAll(batch)
            }
        }
    }

    override fun loadAll(): Pair<Set<ByteArrayKey>, Set<ByteArrayKey>> {
        val current = dao.getByGeneration(0).map { it.hash.toKey() }.toSet()
        val prev = dao.getByGeneration(1).map { it.hash.toKey() }.toSet()
        return current to prev
    }

    override fun clear() {
        writeExecutor.execute { dao.deleteAll() }
    }
}
