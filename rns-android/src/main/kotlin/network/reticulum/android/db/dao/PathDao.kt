package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.PathEntity

@Dao
interface PathDao {
    @Upsert
    fun upsert(entity: PathEntity)

    @Query("SELECT * FROM paths")
    fun getAll(): List<PathEntity>

    @Query("DELETE FROM paths WHERE dest_hash = :destHash")
    fun deleteByHash(destHash: ByteArray)

    @Query("DELETE FROM paths WHERE expires < :timestampMs")
    fun deleteExpiredBefore(timestampMs: Long)
}
