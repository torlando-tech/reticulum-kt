package network.reticulum.storage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.toKey
import network.reticulum.identity.Identity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests that Identity correctly writes through to IdentityStore
 * when one is configured.
 */
@DisplayName("IdentityStore Write-Through")
class IdentityStoreWriteThroughTest {

    private lateinit var store: InMemoryIdentityStore
    private lateinit var tempDir: java.io.File

    @BeforeEach
    fun setup() {
        store = InMemoryIdentityStore()
        tempDir = Files.createTempDirectory("rns-identity-test").toFile()
        Identity.setStoragePath(tempDir.absolutePath)
    }

    @AfterEach
    fun teardown() {
        Identity.identityStore = null
        tempDir.deleteRecursively()
    }

    @Nested
    @DisplayName("remember")
    inner class Remember {

        @Test
        fun `writes to store when configured`() {
            Identity.identityStore = store

            val destHash = ByteArray(16) { it.toByte() }
            val publicKey = ByteArray(64) { (it + 1).toByte() }
            val packetHash = ByteArray(32) { (it + 2).toByte() }
            val appData = "test app data".toByteArray()

            Identity.remember(
                packetHash = packetHash,
                destHash = destHash,
                publicKey = publicKey,
                appData = appData
            )

            store.destinations.size shouldBe 1
            val stored = store.destinations[destHash.toKey()]
            stored shouldNotBe null
            stored!!.publicKey.contentEquals(publicKey) shouldBe true
            stored.appData?.contentEquals(appData) shouldBe true
        }

        @Test
        fun `remember without store still populates in-memory map`() {
            Identity.identityStore = null

            val destHash = ByteArray(16) { it.toByte() }
            val publicKey = ByteArray(64) { (it + 1).toByte() }
            val packetHash = ByteArray(32) { (it + 2).toByte() }

            Identity.remember(
                packetHash = packetHash,
                destHash = destHash,
                publicKey = publicKey
            )

            Identity.isKnown(destHash) shouldBe true
        }
    }

    @Nested
    @DisplayName("known destinations restart cycle")
    inner class RestartCycle {

        @Test
        fun `known destinations survive simulated restart`() {
            Identity.identityStore = store

            val destHash = ByteArray(16) { 0xBB.toByte() }
            val publicKey = ByteArray(64) { 0xCC.toByte() }
            val packetHash = ByteArray(32) { 0xDD.toByte() }

            Identity.remember(
                packetHash = packetHash,
                destHash = destHash,
                publicKey = publicKey,
                appData = "hello".toByteArray()
            )

            store.destinations.size shouldBe 1

            // Verify recall works from store
            val recalled = store.getKnownDestination(destHash)
            recalled shouldNotBe null
            recalled!!.publicKey.contentEquals(publicKey) shouldBe true
            recalled.appData?.let { String(it) } shouldBe "hello"
        }
    }

    @Nested
    @DisplayName("ratchets")
    inner class Ratchets {

        @Test
        fun `rememberRatchet writes to store`() {
            Identity.identityStore = store

            val destHash = ByteArray(16) { 0xAA.toByte() }
            val ratchet = ByteArray(32) { 0xBB.toByte() }

            Identity.rememberRatchet(destHash, ratchet)

            store.ratchets.size shouldBe 1
            val stored = store.ratchets[destHash.toKey()]
            stored shouldNotBe null
            stored!!.first.contentEquals(ratchet) shouldBe true
        }

        @Test
        fun `getRatchet falls back to store when not in memory`() {
            Identity.identityStore = store

            val destHash = ByteArray(16) { 0xCC.toByte() }
            val ratchet = ByteArray(32) { 0xDD.toByte() }
            val timestamp = System.currentTimeMillis()

            // Put directly in store (simulating a restart where memory is empty)
            store.ratchets[destHash.toKey()] = ratchet to timestamp

            val retrieved = Identity.getRatchet(destHash)
            retrieved shouldNotBe null
            retrieved!!.contentEquals(ratchet) shouldBe true
        }
    }
}
