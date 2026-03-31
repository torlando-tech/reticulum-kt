package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "packet_hashes",
    indices = [Index("generation")]
)
data class PacketHashEntity(
    @PrimaryKey
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val hash: ByteArray,

    /** 0 = current generation, 1 = previous generation. */
    val generation: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketHashEntity) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
