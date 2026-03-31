package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_destinations")
data class KnownDestinationEntity(
    @PrimaryKey
    @ColumnInfo(name = "dest_hash", typeAffinity = ColumnInfo.BLOB)
    val destHash: ByteArray,

    val timestamp: Long,

    @ColumnInfo(name = "packet_hash", typeAffinity = ColumnInfo.BLOB)
    val packetHash: ByteArray,

    @ColumnInfo(name = "public_key", typeAffinity = ColumnInfo.BLOB)
    val publicKey: ByteArray,

    @ColumnInfo(name = "app_data", typeAffinity = ColumnInfo.BLOB)
    val appData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownDestinationEntity) return false
        return destHash.contentEquals(other.destHash)
    }

    override fun hashCode(): Int = destHash.contentHashCode()
}
