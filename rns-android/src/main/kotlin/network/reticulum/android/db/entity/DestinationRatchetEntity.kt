package network.reticulum.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-inbound-destination ratchet state.
 *
 * The blob is the signed msgpack envelope `Destination.persistRatchets` produces:
 * `{signature: bytes, ratchets: bytes}`. The store treats it opaquely; unpacking,
 * signature verification, and rotation all live in `Destination`.
 */
@Entity(tableName = "destination_ratchets")
data class DestinationRatchetEntity(
    @PrimaryKey
    @ColumnInfo(name = "dest_hash", typeAffinity = ColumnInfo.BLOB)
    val destHash: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DestinationRatchetEntity) return false
        return destHash.contentEquals(other.destHash) && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = destHash.contentHashCode()
}
