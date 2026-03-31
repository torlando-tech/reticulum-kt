package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.TunnelEntity

@Dao
interface TunnelDao {
    @Upsert
    fun upsert(entity: TunnelEntity)

    @Query("SELECT * FROM tunnels")
    fun getAll(): List<TunnelEntity>

    @Query("DELETE FROM tunnels WHERE tunnel_id = :tunnelId")
    fun deleteById(tunnelId: ByteArray)

    @Query("DELETE FROM tunnels WHERE expires < :timestampMs")
    fun deleteExpiredBefore(timestampMs: Long)
}
