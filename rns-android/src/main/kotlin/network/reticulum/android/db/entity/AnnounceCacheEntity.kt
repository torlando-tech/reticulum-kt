package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "announce_cache")
data class AnnounceCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "packet_hash", typeAffinity = ColumnInfo.BLOB)
    val packetHash: ByteArray,

    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val raw: ByteArray,

    @ColumnInfo(name = "interface_name")
    val interfaceName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnnounceCacheEntity) return false
        return packetHash.contentEquals(other.packetHash)
    }

    override fun hashCode(): Int = packetHash.contentHashCode()
}
