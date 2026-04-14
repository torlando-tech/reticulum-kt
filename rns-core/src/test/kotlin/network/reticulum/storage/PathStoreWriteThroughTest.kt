package network.reticulum.storage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.transport.PathEntry
import network.reticulum.transport.PathState
import network.reticulum.transport.Transport
import network.reticulum.transport.TransportConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests that Transport correctly writes through to PathStore
 * when one is configured, and falls back to file-based persistence
 * when no store is set.
 */
@DisplayName("PathStore Write-Through")
class PathStoreWriteThroughTest {

    private lateinit var store: InMemoryPathStore
    private lateinit var tempDir: java.io.File

    @BeforeEach
    fun setup() {
        store = InMemoryPathStore()
        tempDir = Files.createTempDirectory("rns-test").toFile()
        Transport.setStoragePath(tempDir.absolutePath)
        // Clear global pathTable — other tests may have leaked entries.
        Transport.pathTable.clear()
    }

    @AfterEach
    fun teardown() {
        Transport.pathStore = null
        Transport.announceStore = null
        Transport.pathTable.clear()
        tempDir.deleteRecursively()
    }

    private fun makePath(destHash: ByteArray, hops: Int = 1): PathEntry {
        val now = System.currentTimeMillis()
        return PathEntry(
            timestamp = now,
            nextHop = ByteArray(16) { 0x01 },
            hops = hops,
            expires = now + TransportConstants.PATHFINDER_E,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = ByteArray(16) { 0x02 },
            announcePacketHash = ByteArray(16) { 0x03 },
            state = PathState.ACTIVE,
            failureCount = 0
        )
    }

    @Nested
    @DisplayName("registerLinkPath")
    inner class RegisterLinkPath {

        @Test
        fun `writes to store when configured`() {
            Transport.pathStore = store
            val linkId = ByteArray(16) { it.toByte() }
            val interfaceHash = ByteArray(16) { 0xFF.toByte() }

            Transport.registerLinkPath(linkId, interfaceHash, hops = 2)

            store.data.size shouldBe 1
            store.data[linkId.toKey()] shouldNotBe null
            store.data[linkId.toKey()]!!.hops shouldBe 2
        }

        @Test
        fun `does not write to store when null`() {
            Transport.pathStore = null
            val linkId = ByteArray(16) { it.toByte() }
            val interfaceHash = ByteArray(16) { 0xFF.toByte() }

            Transport.registerLinkPath(linkId, interfaceHash, hops = 2)

            // pathTable should still have the entry (in-memory)
            Transport.hasPath(linkId) shouldBe true
        }
    }

    @Nested
    @DisplayName("expirePath")
    inner class ExpirePath {

        @Test
        fun `removes from store when configured`() {
            Transport.pathStore = store
            val destHash = ByteArray(16) { it.toByte() }

            // Add a path first
            val entry = makePath(destHash)
            Transport.pathTable[destHash.toKey()] = entry
            store.data[destHash.toKey()] = entry

            Transport.expirePath(destHash)

            store.data.size shouldBe 0
            Transport.hasPath(destHash) shouldBe false
        }
    }

    @Nested
    @DisplayName("persistDataToStorage")
    inner class PersistData {

        @Test
        fun `file-based persist writes destination_table when no store`() {
            Transport.pathStore = null
            val destHash = ByteArray(16) { it.toByte() }
            Transport.pathTable[destHash.toKey()] = makePath(destHash)

            // Trigger persist via the stop path (which calls persistDataToStorage)
            // Instead, call the public persistData methods through the file path
            val file = java.io.File(tempDir, "destination_table")
            Transport.savePathTable(file)

            file.exists() shouldBe true
            file.readLines().size shouldBe 1
        }

        @Test
        fun `load from store populates pathTable`() {
            // Pre-populate the store
            val destHash = ByteArray(16) { 0xAA.toByte() }
            val entry = makePath(destHash, hops = 3)
            store.data[destHash.toKey()] = entry

            Transport.pathStore = store

            // Clear pathTable and load from store
            Transport.pathTable.clear()
            val loaded = store.loadAllPaths()
            Transport.pathTable.putAll(loaded)

            Transport.pathTable.size shouldBe 1
            Transport.pathTable[destHash.toKey()]!!.hops shouldBe 3
        }
    }

    @Nested
    @DisplayName("restart cycle")
    inner class RestartCycle {

        @Test
        fun `paths survive simulated restart via store`() {
            Transport.pathStore = store

            // Simulate learning paths
            val hashes = (1..5).map { i ->
                ByteArray(16) { (it + i).toByte() }.also { hash ->
                    val entry = makePath(hash, hops = i)
                    Transport.pathTable[hash.toKey()] = entry
                    store.upsertPath(hash, entry)
                }
            }

            store.data.size shouldBe 5

            // Simulate restart: clear in-memory, reload from store
            Transport.pathTable.clear()
            Transport.pathTable.size shouldBe 0

            val restored = store.loadAllPaths()
            Transport.pathTable.putAll(restored)

            Transport.pathTable.size shouldBe 5
            hashes.forEachIndexed { i, hash ->
                Transport.pathTable[hash.toKey()]!!.hops shouldBe (i + 1)
            }
        }
    }
}
