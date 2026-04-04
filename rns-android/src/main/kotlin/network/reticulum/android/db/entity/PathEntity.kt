package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "paths",
    indices = [Index("expires")]
)
data class PathEntity(
    @PrimaryKey
    @ColumnInfo(name = "dest_hash", typeAffinity = ColumnInfo.BLOB)
    val destHash: ByteArray,

    @ColumnInfo(name = "next_hop", typeAffinity = ColumnInfo.BLOB)
    val nextHop: ByteArray,

    val hops: Int,
    val expires: Long,
    val timestamp: Long,

    @ColumnInfo(name = "interface_hash", typeAffinity = ColumnInfo.BLOB)
    val interfaceHash: ByteArray,

    @ColumnInfo(name = "announce_hash", typeAffinity = ColumnInfo.BLOB)
    val announceHash: ByteArray,

    val state: Int,

    @ColumnInfo(name = "failure_count")
    val failureCount: Int,

    /** Serialized random blobs: hex strings joined by commas. */
    @ColumnInfo(name = "random_blobs", defaultValue = "")
    val randomBlobs: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathEntity) return false
        return destHash.contentEquals(other.destHash)
    }

    override fun hashCode(): Int = destHash.contentHashCode()
}
