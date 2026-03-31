package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import network.reticulum.android.db.entity.DiscoveredInterfaceEntity

@Dao
interface DiscoveredInterfaceDao {
    @Upsert
    fun upsert(entity: DiscoveredInterfaceEntity)

    @Query("SELECT * FROM discovered_interfaces WHERE discovery_hash = :discoveryHash")
    fun getByHash(discoveryHash: ByteArray): DiscoveredInterfaceEntity?

    @Query("SELECT * FROM discovered_interfaces")
    fun getAll(): List<DiscoveredInterfaceEntity>

    @Query("DELETE FROM discovered_interfaces WHERE discovery_hash = :discoveryHash")
    fun deleteByHash(discoveryHash: ByteArray)

    @Query("DELETE FROM discovered_interfaces WHERE last_heard < :thresholdSeconds")
    fun deleteOlderThan(thresholdSeconds: Long)
}
