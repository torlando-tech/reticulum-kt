package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tunnel_paths",
    primaryKeys = ["tunnel_id", "dest_hash"],
    foreignKeys = [ForeignKey(
        entity = TunnelEntity::class,
        parentColumns = ["tunnel_id"],
        childColumns = ["tunnel_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tunnel_id")]
)
data class TunnelPathEntity(
    @ColumnInfo(name = "tunnel_id", typeAffinity = ColumnInfo.BLOB)
    val tunnelId: ByteArray,

    @ColumnInfo(name = "dest_hash", typeAffinity = ColumnInfo.BLOB)
    val destHash: ByteArray,

    val timestamp: Long,

    @ColumnInfo(name = "received_from", typeAffinity = ColumnInfo.BLOB)
    val receivedFrom: ByteArray,

    val hops: Int,
    val expires: Long,

    @ColumnInfo(name = "packet_hash", typeAffinity = ColumnInfo.BLOB)
    val packetHash: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelPathEntity) return false
        return tunnelId.contentEquals(other.tunnelId) && destHash.contentEquals(other.destHash)
    }

    override fun hashCode(): Int {
        var result = tunnelId.contentHashCode()
        result = 31 * result + destHash.contentHashCode()
        return result
    }
}
