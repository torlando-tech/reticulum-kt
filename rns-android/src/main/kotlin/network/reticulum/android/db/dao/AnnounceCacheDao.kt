package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.AnnounceCacheEntity

@Dao
interface AnnounceCacheDao {
    @Upsert
    fun upsert(entity: AnnounceCacheEntity)

    @Query("SELECT * FROM announce_cache WHERE packet_hash = :packetHash")
    fun getByHash(packetHash: ByteArray): AnnounceCacheEntity?

    @Query("DELETE FROM announce_cache WHERE packet_hash = :packetHash")
    fun deleteByHash(packetHash: ByteArray)

    @Query("SELECT packet_hash FROM announce_cache")
    fun getAllHashes(): List<ByteArray>

    @Query("DELETE FROM announce_cache WHERE packet_hash NOT IN (:activeHashes)")
    fun deleteAllExcept(activeHashes: List<ByteArray>)
}
