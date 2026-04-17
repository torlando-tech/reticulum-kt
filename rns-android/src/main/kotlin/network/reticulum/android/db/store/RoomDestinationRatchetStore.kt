package network.reticulum.android.db.store

import network.reticulum.android.db.dao.DestinationRatchetDao
import network.reticulum.android.db.entity.DestinationRatchetEntity
import network.reticulum.storage.DestinationRatchetStore
import java.util.concurrent.ExecutorService

/**
 * Room-backed `DestinationRatchetStore`. Reads synchronously (called from
 * `Destination.enableRatchets` on router startup) and writes through the
 * injected executor so the caller's ratchet-rotation path doesn't block
 * on disk.
 */
class RoomDestinationRatchetStore(
    private val dao: DestinationRatchetDao,
    private val writeExecutor: ExecutorService,
) : DestinationRatchetStore {
    override fun save(
        destHash: ByteArray,
        data: ByteArray,
    ) {
        writeExecutor.execute {
            dao.upsert(DestinationRatchetEntity(destHash = destHash, data = data))
        }
    }

    override fun load(destHash: ByteArray): ByteArray? = dao.getByHash(destHash)?.data

    override fun delete(destHash: ByteArray) {
        writeExecutor.execute { dao.deleteByHash(destHash) }
    }
}
