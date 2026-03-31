package network.reticulum.storage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.common.toKey
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests that Transport correctly uses AnnounceStore for cache operations.
 */
@DisplayName("AnnounceStore Write-Through")
class AnnounceStoreWriteThroughTest {

    private lateinit var store: InMemoryAnnounceStore

    @BeforeEach
    fun setup() {
        store = InMemoryAnnounceStore()
    }

    @AfterEach
    fun teardown() {
        Transport.announceStore = null
    }

    @Nested
    @DisplayName("getCachedAnnouncePacket")
    inner class GetCached {

        @Test
        fun `retrieves from store when configured`() {
            Transport.announceStore = store

            val hash = ByteArray(32) { it.toByte() }
            val raw = ByteArray(100) { (it + 1).toByte() }
            val ifaceName = "TestInterface"

            // Populate store directly (simulating a previous write-through)
            store.cacheAnnounce(hash, raw, ifaceName)

            val result = Transport.getCachedAnnouncePacket(hash)
            result shouldNotBe null
            result!!.first.contentEquals(raw) shouldBe true
            result.second shouldBe ifaceName
        }

        @Test
        fun `returns null for unknown hash`() {
            Transport.announceStore = store

            val result = Transport.getCachedAnnouncePacket(ByteArray(32) { 0xFF.toByte() })
            result shouldBe null
        }
    }

    @Nested
    @DisplayName("removeAllExcept")
    inner class Cleanup {

        @Test
        fun `removes inactive announces from store`() {
            val active = ByteArray(32) { 0x01 }
            val inactive = ByteArray(32) { 0x02 }

            store.cacheAnnounce(active, ByteArray(10), "iface1")
            store.cacheAnnounce(inactive, ByteArray(10), "iface2")

            store.data.size shouldBe 2

            store.removeAllExcept(setOf(active.toKey()))

            store.data.size shouldBe 1
            store.data[active.toKey()] shouldNotBe null
            store.data[inactive.toKey()] shouldBe null
        }
    }
}
