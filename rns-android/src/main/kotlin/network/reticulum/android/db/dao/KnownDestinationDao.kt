package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.KnownDestinationEntity

@Dao
interface KnownDestinationDao {
    @Upsert
    fun upsert(entity: KnownDestinationEntity)

    @Query("SELECT * FROM known_destinations WHERE dest_hash = :destHash")
    fun getByHash(destHash: ByteArray): KnownDestinationEntity?

    @Query("SELECT * FROM known_destinations")
    fun getAll(): List<KnownDestinationEntity>

    @Query("SELECT COUNT(*) FROM known_destinations")
    fun count(): Int
}
