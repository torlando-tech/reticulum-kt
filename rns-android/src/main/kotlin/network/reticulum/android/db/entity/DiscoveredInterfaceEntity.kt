package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "discovered_interfaces",
    indices = [Index("last_heard")]
)
data class DiscoveredInterfaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "discovery_hash", typeAffinity = ColumnInfo.BLOB)
    val discoveryHash: ByteArray,

    val type: String,
    val transport: Boolean,
    val name: String,
    val received: Long,

    @ColumnInfo(name = "stamp_value")
    val stampValue: Int,

    @ColumnInfo(name = "transport_id")
    val transportId: String,

    @ColumnInfo(name = "network_id")
    val networkId: String,

    val hops: Int,
    val latitude: Double?,
    val longitude: Double?,
    val height: Double?,

    @ColumnInfo(name = "reachable_on")
    val reachableOn: String?,

    val port: Int?,
    val frequency: Long?,
    val bandwidth: Long?,

    @ColumnInfo(name = "spreading_factor")
    val spreadingFactor: Int?,

    @ColumnInfo(name = "coding_rate")
    val codingRate: Int?,

    val modulation: String?,
    val channel: Int?,

    @ColumnInfo(name = "ifac_netname")
    val ifacNetname: String?,

    @ColumnInfo(name = "ifac_netkey")
    val ifacNetkey: String?,

    val discovered: Long,

    @ColumnInfo(name = "last_heard")
    val lastHeard: Long,

    @ColumnInfo(name = "heard_count")
    val heardCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredInterfaceEntity) return false
        return discoveryHash.contentEquals(other.discoveryHash)
    }

    override fun hashCode(): Int = discoveryHash.contentHashCode()
}
