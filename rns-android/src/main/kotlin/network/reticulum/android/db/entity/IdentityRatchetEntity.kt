package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "identity_ratchets",
    indices = [Index("timestamp")]
)
data class IdentityRatchetEntity(
    @PrimaryKey
    @ColumnInfo(name = "dest_hash", typeAffinity = ColumnInfo.BLOB)
    val destHash: ByteArray,

    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val ratchet: ByteArray,

    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityRatchetEntity) return false
        return destHash.contentEquals(other.destHash)
    }

    override fun hashCode(): Int = destHash.contentHashCode()
}
