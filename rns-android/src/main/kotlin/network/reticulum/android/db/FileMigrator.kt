package network.reticulum.android.db

import android.util.Log
import network.reticulum.android.db.entity.AnnounceCacheEntity
import network.reticulum.android.db.entity.DestinationRatchetEntity
import network.reticulum.android.db.entity.DiscoveredInterfaceEntity
import network.reticulum.android.db.entity.IdentityRatchetEntity
import network.reticulum.android.db.entity.KnownDestinationEntity
import network.reticulum.android.db.entity.PacketHashEntity
import network.reticulum.android.db.entity.PathEntity
import network.reticulum.android.db.entity.TunnelEntity
import network.reticulum.android.db.entity.TunnelPathEntity
import org.msgpack.core.MessagePack
import java.io.File

/**
 * One-time migration from file-based persistence to Room database.
 *
 * Reads existing text/msgpack files from Reticulum's storage and cache
 * directories and imports them into Room tables. Idempotent via a marker file.
 */
class FileMigrator(
    private val db: ReticulumDatabase,
    private val storagePath: String,
    private val cachePath: String,
    /**
     * Optional LXMF ratchet directory. When LXMF-kt is in the dependency tree
     * it writes per-destination ratchet private keys under
     * `$configDir/lxmf/ratchets/<hash>` (Kotlin) or `<hash>.ratchets` (Python
     * reference layout). Pass `$configDir/lxmf/ratchets` here and FileMigrator
     * will import those into the destination_ratchets Room table and scrub the
     * files. Leave null for bare rns-core deployments.
     */
    private val lxmfRatchetsPath: String? = null,
) {
    fun migrateIfNeeded() {
        val marker = File(storagePath, ".room_migrated")
        if (marker.exists()) {
            // Full migration already ran, but earlier versions of this migrator
            // didn't know about the LXMF destination-ratchet files (they were
            // added later) and left legacy source files on disk forever.
            // Re-import ratchets and run the scrub on every launch — upsert
            // is idempotent, so repeated runs are safe.
            migrateDestinationRatchets()
            deleteLegacySourceFiles()
            return
        }

        Log.i(TAG, "Starting file-to-Room migration...")
        val start = System.currentTimeMillis()

        try {
            migratePathTable()
            migratePacketHashlist()
            migrateKnownDestinations()
            migrateTunnels()
            migrateAnnounceCache()
            migrateRatchets()
            migrateDestinationRatchets()
            migrateDiscovery()

            // storagePath is no longer eagerly created by Reticulum.initialize(),
            // so make sure it exists before dropping the marker.
            marker.parentFile?.mkdirs()
            marker.createNewFile()
            // Room is authoritative for every entity we just imported — delete
            // the source files so they don't drift out of sync and don't leak
            // sensitive routing state via backup archives or forensic access.
            deleteLegacySourceFiles()
            Log.i(TAG, "Migration completed in ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed: ${e.message}", e)
            // Don't create marker — will retry next launch
        }
    }

    /**
     * Remove legacy file-backed state that is now mirrored in Room.
     * Best-effort: each delete is isolated so a single failure doesn't abort
     * the rest. Safe to run when the files are already gone.
     *
     * Note on `ratchets/`: `migrateRatchets()` intentionally skips `.out`
     * files, but those are just transient atomic-write staging (see
     * `Identity.persistRatchet()` — it writes to `$hash.out`, then renames
     * to `$hash`). A stranded `.out` file means a write was interrupted and
     * the data is not authoritative; safe to delete alongside the real
     * ratchet files that Room now owns.
     *
     * Note on `discovery/`: the migrator only touches
     * `discovery/interfaces/`, so we scope the delete there rather than
     * nuking the whole `discovery/` tree — future subdirectories should
     * not be silently wiped.
     */
    private fun deleteLegacySourceFiles() {
        val storageFiles = listOf(
            "destination_table",
            "packet_hashlist",
            "known_destinations",
            "known_destinations.tmp",
            "tunnels"
        )
        for (name in storageFiles) {
            runCatching { File(storagePath, name).delete() }
        }
        runCatching { File(cachePath, "announces").deleteRecursively() }
        runCatching { File(storagePath, "ratchets").deleteRecursively() }
        runCatching { File(storagePath, "discovery/interfaces").deleteRecursively() }
        // LXMF per-destination ratchets — only present when LXMF-kt is in use.
        // Both filename flavours (Kotlin's `<hash>`, Python's `<hash>.ratchets`)
        // landed under the same directory.
        lxmfRatchetsPath?.let { runCatching { File(it).deleteRecursively() } }
    }

    /**
     * Import LXMF per-destination inbound ratchets into Room.
     *
     * The on-disk blob (`{signature, ratchets}` msgpack) is stored opaquely —
     * `Destination.reloadRatchets` unpacks it and verifies the signature when
     * the router re-registers the destination. This keeps the migration
     * signature-agnostic, so it still works when the destination's identity
     * isn't materialized at migration time.
     *
     * Accepts both `<hash>` (LXMF-kt layout) and `<hash>.ratchets` (Python
     * LXMF reference layout); strips the suffix when parsing the hash.
     */
    private fun migrateDestinationRatchets() {
        val dir = lxmfRatchetsPath?.let { File(it) } ?: return
        if (!dir.exists() || !dir.isDirectory) return
        try {
            var count = 0
            var skipped = 0
            for (file in dir.listFiles() ?: emptyArray()) {
                if (!file.isFile) continue
                if (file.name.endsWith(".tmp")) continue
                val hashName = file.name.removeSuffix(".ratchets")
                val destHash =
                    try {
                        hexToBytes(hashName)
                    } catch (e: Exception) {
                        // deleteLegacySourceFiles will erase this file afterwards, so
                        // log loudly — the operator gets no other chance to catch an
                        // unparseable filename before it's gone.
                        Log.w(TAG, "Skipping non-hex ratchet filename ${file.name}: ${e.message}")
                        skipped++
                        continue
                    }
                try {
                    db.destinationRatchetDao().upsert(
                        DestinationRatchetEntity(destHash = destHash, data = file.readBytes()),
                    )
                    count++
                } catch (e: Exception) {
                    // Same reasoning as above — file is about to be deleted. If this
                    // was a transient SQLite error the destination loses ratchet
                    // history until the next rotation, so surface it clearly.
                    Log.w(
                        TAG,
                        "Failed to import ratchet file ${file.name} for destination " +
                            "${hashName.take(8)}... into Room: ${e.message}",
                    )
                    skipped++
                }
            }
            if (skipped > 0) {
                Log.w(TAG, "Migrated $count destination ratchets, skipped $skipped")
            } else {
                Log.i(TAG, "Migrated $count destination ratchets")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate destination ratchets: ${e.message}")
        }
    }

    private fun migratePathTable() {
        val file = File(storagePath, "destination_table")
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            var count = 0
            for (line in lines) {
                val parts = line.split("|")
                if (parts.size < 8) continue
                try {
                    val expires = parts[3].toLong()
                    if (System.currentTimeMillis() > expires) continue

                    db.pathDao().upsert(PathEntity(
                        destHash = hexToBytes(parts[0]),
                        nextHop = hexToBytes(parts[1]),
                        hops = parts[2].toInt(),
                        expires = expires,
                        timestamp = System.currentTimeMillis(),
                        interfaceHash = hexToBytes(parts[4]),
                        announceHash = hexToBytes(parts[5]),
                        state = parts[6].toInt(),
                        failureCount = parts[7].toInt()
                    ))
                    count++
                } catch (_: Exception) { }
            }
            Log.i(TAG, "Migrated $count path entries")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate path table: ${e.message}")
        }
    }

    private fun migratePacketHashlist() {
        val file = File(storagePath, "packet_hashlist")
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            val entities = lines.mapNotNull { line ->
                try {
                    PacketHashEntity(hash = hexToBytes(line.trim()), generation = 0)
                } catch (_: Exception) { null }
            }
            entities.chunked(500).forEach { batch ->
                db.packetHashDao().insertAll(batch)
            }
            Log.i(TAG, "Migrated ${entities.size} packet hashes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate packet hashlist: ${e.message}")
        }
    }

    private fun migrateKnownDestinations() {
        val file = File(storagePath, "known_destinations")
        if (!file.exists()) return
        try {
            val unpacker = MessagePack.newDefaultUnpacker(file.readBytes())
            val mapSize = unpacker.unpackMapHeader()
            var count = 0
            repeat(mapSize) {
                try {
                    val keyLen = unpacker.unpackBinaryHeader()
                    val destHash = ByteArray(keyLen)
                    unpacker.readPayload(destHash)

                    unpacker.unpackArrayHeader()
                    val timestamp = unpacker.unpackLong()

                    val phLen = unpacker.unpackBinaryHeader()
                    val packetHash = ByteArray(phLen)
                    unpacker.readPayload(packetHash)

                    val pkLen = unpacker.unpackBinaryHeader()
                    val publicKey = ByteArray(pkLen)
                    unpacker.readPayload(publicKey)

                    val appData = if (unpacker.tryUnpackNil()) null else {
                        val adLen = unpacker.unpackBinaryHeader()
                        ByteArray(adLen).also { unpacker.readPayload(it) }
                    }

                    db.knownDestinationDao().upsert(KnownDestinationEntity(
                        destHash = destHash,
                        timestamp = timestamp,
                        packetHash = packetHash,
                        publicKey = publicKey,
                        appData = appData
                    ))
                    count++
                } catch (_: Exception) { }
            }
            unpacker.close()
            Log.i(TAG, "Migrated $count known destinations")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate known destinations: ${e.message}")
        }
    }

    private fun migrateTunnels() {
        val file = File(storagePath, "tunnels")
        if (!file.exists()) return
        try {
            val unpacker = MessagePack.newDefaultUnpacker(file.readBytes())
            val tunnelCount = unpacker.unpackArrayHeader()
            var count = 0
            for (i in 0 until tunnelCount) {
                try {
                    unpacker.unpackArrayHeader() // 4 fields

                    val tidLen = unpacker.unpackBinaryHeader()
                    val tunnelId = ByteArray(tidLen)
                    unpacker.readPayload(tunnelId)

                    val interfaceHash = if (unpacker.tryUnpackNil()) null else {
                        val ihLen = unpacker.unpackBinaryHeader()
                        ByteArray(ihLen).also { unpacker.readPayload(it) }
                    }

                    val pathCount = unpacker.unpackArrayHeader()

                    // Collect paths first — we need to read expires (after paths) before inserting
                    val pendingPaths = mutableListOf<TunnelPathEntity>()
                    for (j in 0 until pathCount) {
                        unpacker.unpackArrayHeader() // 8 fields

                        val dhLen = unpacker.unpackBinaryHeader()
                        val destHash = ByteArray(dhLen)
                        unpacker.readPayload(destHash)

                        val timestamp = unpacker.unpackLong()

                        val rfLen = unpacker.unpackBinaryHeader()
                        val receivedFrom = ByteArray(rfLen)
                        unpacker.readPayload(receivedFrom)

                        val hops = unpacker.unpackInt()
                        val pathExpires = unpacker.unpackLong()

                        // Skip random_blobs array
                        val blobCount = unpacker.unpackArrayHeader()
                        repeat(blobCount) { unpacker.skipValue() }

                        // Skip interface_hash
                        unpacker.skipValue()

                        val phLen = unpacker.unpackBinaryHeader()
                        val packetHash = ByteArray(phLen)
                        unpacker.readPayload(packetHash)

                        if (System.currentTimeMillis() <= pathExpires) {
                            pendingPaths.add(TunnelPathEntity(
                                tunnelId = tunnelId,
                                destHash = destHash,
                                timestamp = timestamp,
                                receivedFrom = receivedFrom,
                                hops = hops,
                                expires = pathExpires,
                                packetHash = packetHash
                            ))
                        }
                    }

                    val expires = unpacker.unpackLong()

                    if (System.currentTimeMillis() <= expires) {
                        // Insert parent BEFORE children to satisfy FK constraint
                        db.tunnelDao().upsert(TunnelEntity(
                            tunnelId = tunnelId,
                            interfaceHash = interfaceHash,
                            expires = expires
                        ))
                        for (path in pendingPaths) {
                            db.tunnelPathDao().upsert(path)
                        }
                        count++
                    }
                } catch (_: Exception) { }
            }
            unpacker.close()
            Log.i(TAG, "Migrated $count tunnels")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate tunnels: ${e.message}")
        }
    }

    private fun migrateAnnounceCache() {
        val dir = File(cachePath, "announces")
        if (!dir.exists()) return
        try {
            var count = 0
            for (file in dir.listFiles() ?: emptyArray()) {
                try {
                    val data = file.readBytes()
                    val unpacker = MessagePack.newDefaultUnpacker(data)
                    unpacker.unpackArrayHeader()

                    val rawLen = unpacker.unpackBinaryHeader()
                    val raw = ByteArray(rawLen)
                    unpacker.readPayload(raw)

                    val interfaceName = unpacker.unpackString()
                    unpacker.close()

                    db.announceCacheDao().upsert(AnnounceCacheEntity(
                        packetHash = hexToBytes(file.name),
                        raw = raw,
                        interfaceName = interfaceName
                    ))
                    count++
                } catch (_: Exception) { }
            }
            Log.i(TAG, "Migrated $count cached announces")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate announce cache: ${e.message}")
        }
    }

    private fun migrateRatchets() {
        val dir = File(storagePath, "ratchets")
        if (!dir.exists()) return
        try {
            var count = 0
            for (file in dir.listFiles() ?: emptyArray()) {
                if (file.name.endsWith(".out")) continue
                try {
                    val unpacker = MessagePack.newDefaultUnpacker(file.readBytes())
                    val mapSize = unpacker.unpackMapHeader()
                    var ratchet: ByteArray? = null
                    var received = 0.0

                    repeat(mapSize) {
                        when (unpacker.unpackString()) {
                            "ratchet" -> {
                                val len = unpacker.unpackBinaryHeader()
                                ratchet = ByteArray(len)
                                unpacker.readPayload(ratchet!!)
                            }
                            "received" -> received = unpacker.unpackDouble()
                            else -> unpacker.skipValue()
                        }
                    }
                    unpacker.close()

                    val r = ratchet ?: continue
                    val timestampMs = (received * 1000).toLong()
                    val now = System.currentTimeMillis()
                    if (now - timestampMs > 2_592_000_000L) continue // 30 days expiry

                    db.identityRatchetDao().upsert(IdentityRatchetEntity(
                        destHash = hexToBytes(file.name),
                        ratchet = r,
                        timestamp = timestampMs
                    ))
                    count++
                } catch (_: Exception) { }
            }
            Log.i(TAG, "Migrated $count ratchets")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate ratchets: ${e.message}")
        }
    }

    private fun migrateDiscovery() {
        val dir = File(storagePath, "discovery/interfaces")
        if (!dir.exists()) return
        try {
            var count = 0
            for (file in dir.listFiles() ?: emptyArray()) {
                try {
                    val data = file.readBytes()
                    val unpacker = MessagePack.newDefaultUnpacker(data)
                    val mapSize = unpacker.unpackMapHeader()
                    val fields = HashMap<String, Any?>(mapSize)
                    repeat(mapSize) {
                        val key = unpacker.unpackString()
                        fields[key] = unpackValue(unpacker)
                    }
                    unpacker.close()

                    val discoveryHash = fields["discovery_hash"] as? ByteArray ?: continue
                    val type = fields["type"] as? String ?: continue
                    val name = fields["name"] as? String ?: continue
                    val transportId = fields["transport_id"] as? String ?: continue
                    val networkId = fields["network_id"] as? String ?: continue

                    db.discoveredInterfaceDao().upsert(DiscoveredInterfaceEntity(
                        discoveryHash = discoveryHash,
                        type = type,
                        transport = fields["transport"] as? Boolean ?: false,
                        name = name,
                        received = (fields["received"] as? Number)?.toLong() ?: 0,
                        stampValue = (fields["value"] as? Number)?.toInt() ?: 0,
                        transportId = transportId,
                        networkId = networkId,
                        hops = (fields["hops"] as? Number)?.toInt() ?: 0,
                        latitude = (fields["latitude"] as? Number)?.toDouble(),
                        longitude = (fields["longitude"] as? Number)?.toDouble(),
                        height = (fields["height"] as? Number)?.toDouble(),
                        reachableOn = fields["reachable_on"] as? String,
                        port = (fields["port"] as? Number)?.toInt(),
                        frequency = (fields["frequency"] as? Number)?.toLong(),
                        bandwidth = (fields["bandwidth"] as? Number)?.toLong(),
                        spreadingFactor = (fields["sf"] as? Number)?.toInt(),
                        codingRate = (fields["cr"] as? Number)?.toInt(),
                        modulation = fields["modulation"] as? String,
                        channel = (fields["channel"] as? Number)?.toInt(),
                        ifacNetname = fields["ifac_netname"] as? String,
                        ifacNetkey = fields["ifac_netkey"] as? String,
                        discovered = (fields["discovered"] as? Number)?.toLong() ?: 0,
                        lastHeard = (fields["last_heard"] as? Number)?.toLong() ?: 0,
                        heardCount = (fields["heard_count"] as? Number)?.toInt() ?: 0
                    ))
                    count++
                } catch (_: Exception) { }
            }
            Log.i(TAG, "Migrated $count discovered interfaces")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate discovered interfaces: ${e.message}")
        }
    }

    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        val format = unpacker.nextFormat
        return when (format.valueType) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                unpacker.readPayload(len)
            }
            else -> { unpacker.skipValue(); null }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4)
                + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    companion object {
        private const val TAG = "FileMigrator"
    }
}
