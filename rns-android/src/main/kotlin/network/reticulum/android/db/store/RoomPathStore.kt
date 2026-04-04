package network.reticulum.android.db.store

import network.reticulum.android.db.dao.PathDao
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.storage.PathStore
import network.reticulum.transport.PathEntry
import network.reticulum.transport.PathState
import network.reticulum.transport.TransportConstants
import java.util.concurrent.ExecutorService

class RoomPathStore(
    private val dao: PathDao,
    private val writeExecutor: ExecutorService
) : PathStore {

    override fun upsertPath(destHash: ByteArray, entry: PathEntry) {
        // Serialize up to PERSIST_RANDOM_BLOBS most recent blobs as hex CSV
        val blobsCsv = entry.randomBlobs
            .takeLast(TransportConstants.PERSIST_RANDOM_BLOBS)
            .joinToString(",") { it.toHexString() }
        val entity = PathEntity(
            destHash = destHash.copyOf(),
            nextHop = entry.nextHop.copyOf(),
            hops = entry.hops,
            expires = entry.expires,
            timestamp = entry.timestamp,
            interfaceHash = entry.receivingInterfaceHash.copyOf(),
            announceHash = entry.announcePacketHash.copyOf(),
            state = entry.state.ordinal,
            failureCount = entry.failureCount,
            randomBlobs = blobsCsv
        )
        writeExecutor.execute { dao.upsert(entity) }
    }

    override fun removePath(destHash: ByteArray) {
        val hash = destHash.copyOf()
        writeExecutor.execute { dao.deleteByHash(hash) }
    }

    override fun loadAllPaths(): Map<ByteArrayKey, PathEntry> {
        return dao.getAll().associate { e ->
            val blobs = if (e.randomBlobs.isNotBlank()) {
                e.randomBlobs.split(",")
                    .filter { it.isNotBlank() }
                    .map { hex -> hexToBytes(hex) }
                    .toMutableList()
            } else {
                mutableListOf()
            }
            e.destHash.toKey() to PathEntry(
                timestamp = e.timestamp,
                nextHop = e.nextHop,
                hops = e.hops,
                expires = e.expires,
                randomBlobs = blobs,
                receivingInterfaceHash = e.interfaceHash,
                announcePacketHash = e.announceHash,
                state = PathState.entries.getOrElse(e.state) { PathState.ACTIVE },
                failureCount = e.failureCount
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override fun removeExpiredBefore(timestampMs: Long) {
        writeExecutor.execute { dao.deleteExpiredBefore(timestampMs) }
    }
}
