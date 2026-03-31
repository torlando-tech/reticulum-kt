package network.reticulum.android.db.store

import network.reticulum.android.db.dao.PathDao
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.storage.PathStore
import network.reticulum.transport.PathEntry
import network.reticulum.transport.PathState
import java.util.concurrent.ExecutorService

class RoomPathStore(
    private val dao: PathDao,
    private val writeExecutor: ExecutorService
) : PathStore {

    override fun upsertPath(destHash: ByteArray, entry: PathEntry) {
        val entity = PathEntity(
            destHash = destHash.copyOf(),
            nextHop = entry.nextHop.copyOf(),
            hops = entry.hops,
            expires = entry.expires,
            timestamp = entry.timestamp,
            interfaceHash = entry.receivingInterfaceHash.copyOf(),
            announceHash = entry.announcePacketHash.copyOf(),
            state = entry.state.ordinal,
            failureCount = entry.failureCount
        )
        writeExecutor.execute { dao.upsert(entity) }
    }

    override fun removePath(destHash: ByteArray) {
        val hash = destHash.copyOf()
        writeExecutor.execute { dao.deleteByHash(hash) }
    }

    override fun loadAllPaths(): Map<ByteArrayKey, PathEntry> {
        return dao.getAll().associate { e ->
            e.destHash.toKey() to PathEntry(
                timestamp = e.timestamp,
                nextHop = e.nextHop,
                hops = e.hops,
                expires = e.expires,
                randomBlobs = mutableListOf(),
                receivingInterfaceHash = e.interfaceHash,
                announcePacketHash = e.announceHash,
                state = PathState.entries.getOrElse(e.state) { PathState.ACTIVE },
                failureCount = e.failureCount
            )
        }
    }

    override fun removeExpiredBefore(timestampMs: Long) {
        writeExecutor.execute { dao.deleteExpiredBefore(timestampMs) }
    }
}
