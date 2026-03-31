package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.IdentityRatchetEntity

@Dao
interface IdentityRatchetDao {
    @Upsert
    fun upsert(entity: IdentityRatchetEntity)

    @Query("SELECT * FROM identity_ratchets WHERE dest_hash = :destHash")
    fun getByHash(destHash: ByteArray): IdentityRatchetEntity?

    @Query("DELETE FROM identity_ratchets WHERE timestamp < :thresholdMs")
    fun deleteExpiredBefore(thresholdMs: Long)
}
