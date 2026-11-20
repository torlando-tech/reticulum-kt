package network.reticulum.android.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import network.reticulum.android.db.entity.PacketHashEntity

@Dao
interface PacketHashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<PacketHashEntity>)

    @Query("SELECT * FROM packet_hashes WHERE generation = :generation")
    fun getByGeneration(generation: Int): List<PacketHashEntity>

    /**
     * At most [limit] rows of one generation, for bounded startup loads.
     * Row order is unspecified: any subset is acceptable dedup coverage.
     */
    @Query("SELECT * FROM packet_hashes WHERE generation = :generation LIMIT :limit")
    fun getByGenerationLimited(generation: Int, limit: Int): List<PacketHashEntity>

    @Query("DELETE FROM packet_hashes WHERE generation = :generation")
    fun deleteByGeneration(generation: Int)

    @Query("DELETE FROM packet_hashes")
    fun deleteAll()
}
