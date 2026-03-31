package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tunnels")
data class TunnelEntity(
    @PrimaryKey
    @ColumnInfo(name = "tunnel_id", typeAffinity = ColumnInfo.BLOB)
    val tunnelId: ByteArray,

    @ColumnInfo(name = "interface_hash", typeAffinity = ColumnInfo.BLOB)
    val interfaceHash: ByteArray?,

    val expires: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunnelEntity) return false
        return tunnelId.contentEquals(other.tunnelId)
    }

    override fun hashCode(): Int = tunnelId.contentHashCode()
}
