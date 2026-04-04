package network.reticulum.android.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import network.reticulum.android.db.dao.AnnounceCacheDao
import network.reticulum.android.db.dao.DiscoveredInterfaceDao
import network.reticulum.android.db.dao.IdentityRatchetDao
import network.reticulum.android.db.dao.KnownDestinationDao
import network.reticulum.android.db.dao.PacketHashDao
import network.reticulum.android.db.dao.PathDao
import network.reticulum.android.db.dao.TunnelDao
import network.reticulum.android.db.dao.TunnelPathDao
import network.reticulum.android.db.entity.AnnounceCacheEntity
import network.reticulum.android.db.entity.DiscoveredInterfaceEntity
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.android.db.entity.PacketHashEntity
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.android.db.entity.TunnelEntity
import network.reticulum.android.db.entity.TunnelPathEntity

@Database(
    entities = [
        PathEntity::class,
        PacketHashEntity::class,
        KnownDestinationEntity::class,
        TunnelEntity::class,
        TunnelPathEntity::class,
        AnnounceCacheEntity::class,
        IdentityRatchetEntity::class,
        DiscoveredInterfaceEntity::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class ReticulumDatabase : RoomDatabase() {
    abstract fun pathDao(): PathDao
    abstract fun packetHashDao(): PacketHashDao
    abstract fun knownDestinationDao(): KnownDestinationDao
    abstract fun tunnelDao(): TunnelDao
    abstract fun tunnelPathDao(): TunnelPathDao
    abstract fun announceCacheDao(): AnnounceCacheDao
    abstract fun identityRatchetDao(): IdentityRatchetDao
    abstract fun discoveredInterfaceDao(): DiscoveredInterfaceDao

    companion object {
        /** Migration 1→2: Add random_blobs column to paths table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE paths ADD COLUMN random_blobs TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
