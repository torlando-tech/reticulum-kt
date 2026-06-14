package network.reticulum.destination

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.identity.Identity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for the announce-time own-ratchet store.
 *
 * Mirrors the conformance finding surfaced by
 * reticulum-conformance/tests/wire/test_enforce_ratchets.py
 * (test_destination_single_auto_ratchet_latest_id): python Destination.announce
 * stores the destination's OWN current ratchet public key under its own hash
 * (RNS.Identity._remember_ratchet(self.hash, ratchet), Destination.py:287), and
 * Destination.encrypt selects its ratchet via that same lookup
 * (get_ratchet(self.hash), Destination.py:596), recording latest_ratchet_id.
 * The kotlin port previously skipped the own-hash store, so encrypt fell back
 * to the static key and latest_ratchet_id was never set for the announcer's own
 * outbound messages. This test pins the fixed behavior.
 */
@DisplayName("Ratchet announce store")
class RatchetAnnounceStoreTest {

    private val tempDirs = mutableListOf<File>()

    @AfterEach
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun ratchetEnabledDestination(): Destination {
        val dir = Files.createTempDirectory("ratchet_announce_test_").toFile()
        tempDirs += dir
        val dest = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "ratchettest",
            aspects = arrayOf("announce-store"),
        )
        dest.enableRatchets(File(dir, "ratchets.bin").absolutePath)
        return dest
    }

    @Test
    fun `announce stores the destination's own current ratchet under its own hash`() {
        val dest = ratchetEnabledDestination()
        // Before announce: no ratchet rotation has happened, so the lookup misses.
        Destination.getRatchetForDestination(dest.hash) shouldBe null

        dest.announce(send = false)

        val current = dest.ratchetsSnapshotForTest().firstOrNull()
        current.shouldNotBeNull()
        val expectedPub = network.reticulum.crypto.defaultCryptoProvider()
            .x25519PublicFromPrivate(current)
        // The own current ratchet PUBLIC key is now resolvable under our own hash,
        // exactly as python's _remember_ratchet(self.hash, ratchet) makes it.
        Destination.getRatchetForDestination(dest.hash) shouldBe expectedPub
    }

    @Test
    fun `encrypt selects the announced ratchet and records latest_ratchet_id`() {
        val dest = ratchetEnabledDestination()
        dest.announce(send = false)

        // A real encrypt must pick the current ratchet (not the static key) and
        // record its id — None before, set after.
        dest.latestRatchetId shouldBe null
        val ciphertext = dest.encrypt("ratchet-probe".toByteArray())
        dest.latestRatchetId.shouldNotBeNull()

        // The recorded id is the current ratchet's id, and the round-trip
        // decrypts back to the same id.
        val expectedId = Identity.ratchetIdFor(
            Destination.getRatchetForDestination(dest.hash)!!,
        )
        dest.latestRatchetId!!.toList() shouldBe expectedId.toList()
        val plaintext = dest.decrypt(ciphertext)
        plaintext shouldNotBe null
        dest.latestRatchetId!!.toList() shouldBe expectedId.toList()
    }
}
