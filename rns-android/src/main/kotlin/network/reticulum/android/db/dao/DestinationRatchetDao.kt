package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.DestinationRatchetEntity

@Dao
interface DestinationRatchetDao {
    @Upsert
    fun upsert(entity: DestinationRatchetEntity)

    @Query("SELECT * FROM destination_ratchets WHERE dest_hash = :destHash")
    fun getByHash(destHash: ByteArray): DestinationRatchetEntity?

    @Query("DELETE FROM destination_ratchets WHERE dest_hash = :destHash")
    fun deleteByHash(destHash: ByteArray)
}
