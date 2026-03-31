package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.TunnelPathEntity

@Dao
interface TunnelPathDao {
    @Upsert
    fun upsert(entity: TunnelPathEntity)

    @Query("SELECT * FROM tunnel_paths WHERE tunnel_id = :tunnelId")
    fun getByTunnelId(tunnelId: ByteArray): List<TunnelPathEntity>

    @Query("DELETE FROM tunnel_paths WHERE tunnel_id = :tunnelId")
    fun deleteByTunnelId(tunnelId: ByteArray)
}
