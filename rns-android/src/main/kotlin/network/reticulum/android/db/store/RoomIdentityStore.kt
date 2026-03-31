package network.reticulum.android.db.store

import network.reticulum.android.db.dao.IdentityRatchetDao
import network.reticulum.android.db.dao.KnownDestinationDao
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.toKey
import network.reticulum.identity.Identity.IdentityData
import network.reticulum.storage.IdentityStore
import java.util.concurrent.ExecutorService

class RoomIdentityStore(
    private val knownDestDao: KnownDestinationDao,
    private val ratchetDao: IdentityRatchetDao,
    private val writeExecutor: ExecutorService
) : IdentityStore {

    // ===== Known Destinations =====

    override fun upsertKnownDestination(destHash: ByteArray, data: IdentityData) {
        val entity = KnownDestinationEntity(
            destHash = destHash.copyOf(),
            timestamp = data.timestamp,
            packetHash = data.packetHash.copyOf(),
            publicKey = data.publicKey.copyOf(),
            appData = data.appData?.copyOf()
        )
        writeExecutor.execute { knownDestDao.upsert(entity) }
    }

    override fun getKnownDestination(destHash: ByteArray): IdentityData? {
        val entity = knownDestDao.getByHash(destHash) ?: return null
        return IdentityData(
            timestamp = entity.timestamp,
            packetHash = entity.packetHash,
            publicKey = entity.publicKey,
            appData = entity.appData
        )
    }

    override fun loadAllKnownDestinations(): Map<ByteArrayKey, IdentityData> {
        return knownDestDao.getAll().associate { e ->
            e.destHash.toKey() to IdentityData(
                timestamp = e.timestamp,
                packetHash = e.packetHash,
                publicKey = e.publicKey,
                appData = e.appData
            )
        }
    }

    override fun knownDestinationCount(): Int = knownDestDao.count()

    // ===== Per-Peer Ratchets =====

    override fun upsertRatchet(destHash: ByteArray, ratchet: ByteArray, timestampMs: Long) {
        val entity = IdentityRatchetEntity(
            destHash = destHash.copyOf(),
            ratchet = ratchet.copyOf(),
            timestamp = timestampMs
        )
        writeExecutor.execute { ratchetDao.upsert(entity) }
    }

    override fun getRatchet(destHash: ByteArray): Pair<ByteArray, Long>? {
        val entity = ratchetDao.getByHash(destHash) ?: return null
        return entity.ratchet to entity.timestamp
    }

    override fun removeExpiredRatchets(maxAgeMs: Long) {
        val threshold = System.currentTimeMillis() - maxAgeMs
        writeExecutor.execute { ratchetDao.deleteExpiredBefore(threshold) }
    }
}
