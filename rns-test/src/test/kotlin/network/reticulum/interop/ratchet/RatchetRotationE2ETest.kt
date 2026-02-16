package network.reticulum.interop.ratchet

import network.reticulum.identity.Identity
import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getString
import network.reticulum.interop.hexToByteArray
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for Ratchet rotation between Kotlin and Python.
 *
 * Ratchets provide forward secrecy: a destination generates an X25519 keypair,
 * embeds the public half in announces, and peers use it for an extra encryption
 * layer. When the ratchet rotates, a new keypair is generated and announced.
 * Old ratchets remain available for backward compatibility.
 */
@DisplayName("Ratchet Rotation E2E Tests")
class RatchetRotationE2ETest : RnsLiveTestBase() {

    @Test
    @DisplayName("Kotlin receives ratchet from Python announce")
    @Timeout(30)
    fun `kotlin receives ratchet from python announce`() {
        // Enable ratchets on Python destination
        println("  [Test] Enabling ratchets on Python destination...")
        val enableResult = python("rns_enable_ratchets")
        assertTrue(enableResult.getBoolean("enabled"), "Ratchets should be enabled")

        // Re-announce with ratchet
        println("  [Test] Re-announcing Python destination with ratchet...")
        python("rns_announce_destination")

        // Get Python's current ratchet ID (the one just announced)
        val pythonRatchetInfo = python("rns_get_ratchet_info")
        val pythonRatchetId = pythonRatchetInfo.getString("ratchet_id")
        assertNotNull(pythonRatchetId, "Python should have a ratchet ID after announce")
        println("  [Test] Python ratchet ID: $pythonRatchetId")

        // Wait for Kotlin to receive the specific ratchet from this announce
        println("  [Test] Waiting for Kotlin to receive ratchet...")
        val deadline = System.currentTimeMillis() + 15_000
        var kotlinRatchetId: ByteArray? = null

        while (System.currentTimeMillis() < deadline) {
            kotlinRatchetId = Identity.currentRatchetId(pythonDestHash!!)
            if (kotlinRatchetId != null && kotlinRatchetId.toHex() == pythonRatchetId) break
            Thread.sleep(500)
        }

        assertNotNull(kotlinRatchetId, "Kotlin should have received a ratchet ID")
        println("  [Test] Kotlin ratchet ID: ${kotlinRatchetId.toHex()}")

        assertEquals(
            pythonRatchetId,
            kotlinRatchetId.toHex(),
            "Kotlin and Python ratchet IDs should match"
        )

        println("  [Test] Ratchet IDs match between Kotlin and Python!")
    }

    @Test
    @DisplayName("Ratchet rotation updates Kotlin's stored ratchet")
    @Timeout(45)
    fun `ratchet rotation updates kotlin stored ratchet`() {
        // Enable ratchets
        println("  [Test] Enabling ratchets on Python destination...")
        val enableResult = python("rns_enable_ratchets")
        assertTrue(enableResult.getBoolean("enabled"), "Ratchets should be enabled")

        // Announce with initial ratchet
        python("rns_announce_destination")

        // Get Python's initial ratchet ID
        val initialPythonInfo = python("rns_get_ratchet_info")
        val initialPythonRatchetId = initialPythonInfo.getString("ratchet_id")
        assertNotNull(initialPythonRatchetId, "Python should have initial ratchet ID")

        // Wait for Kotlin to receive this specific initial ratchet
        println("  [Test] Waiting for initial ratchet...")
        var initialRatchetId: ByteArray? = null
        val deadline1 = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline1) {
            initialRatchetId = Identity.currentRatchetId(pythonDestHash!!)
            if (initialRatchetId != null && initialRatchetId.toHex() == initialPythonRatchetId) break
            Thread.sleep(500)
        }

        assertNotNull(initialRatchetId, "Kotlin should receive initial ratchet")
        val initialRatchet = Identity.getRatchet(pythonDestHash!!)
        assertNotNull(initialRatchet, "Initial ratchet key should be stored")
        println("  [Test] Initial ratchet ID: ${initialRatchetId.toHex()}")

        // Rotate ratchet on Python side
        println("  [Test] Rotating ratchet on Python side...")
        val rotateResult = python("rns_rotate_ratchet")
        assertTrue(rotateResult.getBoolean("announced"), "Rotation should re-announce")
        val newPythonRatchetId = rotateResult.getString("new_ratchet_id")
        println("  [Test] Python new ratchet ID: $newPythonRatchetId")

        // The new ratchet ID should differ from the initial one
        assertNotEquals(
            initialRatchetId.toHex(),
            newPythonRatchetId,
            "Rotated ratchet ID should differ from initial"
        )

        // Wait for Kotlin to receive the new ratchet
        println("  [Test] Waiting for Kotlin to receive rotated ratchet...")
        val deadline2 = System.currentTimeMillis() + 15_000
        var newKotlinRatchetId: ByteArray? = null
        while (System.currentTimeMillis() < deadline2) {
            newKotlinRatchetId = Identity.currentRatchetId(pythonDestHash!!)
            if (newKotlinRatchetId != null && !newKotlinRatchetId.contentEquals(initialRatchetId)) {
                break
            }
            Thread.sleep(500)
        }

        assertNotNull(newKotlinRatchetId, "Kotlin should have a ratchet after rotation")
        assertEquals(
            newPythonRatchetId,
            newKotlinRatchetId.toHex(),
            "Kotlin's new ratchet ID should match Python's rotated ID"
        )

        // Verify old ratchet is still available in the fallback list
        val allRatchets = Identity.getRatchets(pythonDestHash!!)
        assertTrue(allRatchets.size >= 2, "Should have at least 2 ratchets (old + new)")
        println("  [Test] Total stored ratchets: ${allRatchets.size}")

        // The newest ratchet (index 0) should match the rotated one
        val newestRatchet = allRatchets[0]
        val newestRatchetId = network.reticulum.crypto.Hashes.fullHash(newestRatchet)
            .copyOfRange(0, 10)
        assertEquals(
            newPythonRatchetId,
            newestRatchetId.toHex(),
            "Newest stored ratchet should match rotated ratchet"
        )

        println("  [Test] Ratchet rotation verified — old ratchet preserved, new ratchet active!")
    }
}
