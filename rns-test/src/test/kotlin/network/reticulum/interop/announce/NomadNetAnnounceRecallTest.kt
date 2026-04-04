package network.reticulum.interop.announce

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.reticulum.identity.Identity
import network.reticulum.interop.RnsLiveTestBase
import network.reticulum.interop.getBoolean
import network.reticulum.interop.getBytes
import network.reticulum.interop.getString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Tests that Identity.recall works for NomadNet node announces.
 *
 * Reproduces the bug where NomadNet page browsing fails with
 * "Node identity not known" even after receiving a fresh announce.
 *
 * Flow:
 * 1. Python creates a nomadnetwork.node destination and announces it
 * 2. Kotlin receives the announce via TCP
 * 3. Kotlin calls Identity.recall(destHash) — should return the identity
 */
@DisplayName("NomadNet Announce Recall Test")
class NomadNetAnnounceRecallTest : RnsLiveTestBase() {

    // Use nomadnetwork.node aspect to match real NomadNet nodes
    override val appName: String = "nomadnetwork"
    override val aspects: Array<String> = arrayOf("node")

    @Test
    @DisplayName("Identity.recall finds identity after receiving nomadnetwork.node announce")
    @Timeout(30)
    fun `identity recall works after nomadnet announce`() {
        // pythonDestHash was populated by setupLiveRns which created
        // a Python destination and announced it
        val destHash = pythonDestHash!!
        println("[Test] Python nomadnetwork.node dest hash: ${destHash.joinToString("") { "%02x".format(it) }}")

        // Wait a bit for announce to propagate
        Thread.sleep(2000)

        // Try to recall the identity — this is what requestNomadnetPage does
        val recalled = Identity.recall(destHash)

        println("[Test] Identity.recall result: ${recalled != null}")
        if (recalled != null) {
            println("[Test] Recalled identity hash: ${recalled.hexHash}")
            println("[Test] Recalled identity has private key: ${recalled.hasPrivateKey}")
        } else {
            // Debug: check known destination count and if the hash exists
            println("[Test] Known destinations: ${Identity.knownDestinationCount()}")
            println("[Test] Is known: ${Identity.isKnown(destHash)}")

            // Try by identity hash instead
            val byIdentity = Identity.recallByIdentityHash(destHash)
            println("[Test] recallByIdentityHash: ${byIdentity != null}")
        }

        recalled shouldNotBe null
    }

    @Test
    @DisplayName("Path request triggers announce that enables Identity.recall")
    @Timeout(30)
    fun `path request populates identity for recall`() {
        val destHash = pythonDestHash!!
        val destHex = destHash.joinToString("") { "%02x".format(it) }

        // Clear any cached identity to simulate cold start
        // (In practice the identity might not be cached if the announce was missed)

        // Request path — this should trigger the Python node to re-announce
        println("[Test] Requesting path to $destHex...")
        network.reticulum.transport.Transport.requestPath(destHash)

        // Wait for path response (which includes an announce)
        val deadline = System.currentTimeMillis() + 15_000
        var recalled: Identity? = null
        while (recalled == null && System.currentTimeMillis() < deadline) {
            recalled = Identity.recall(destHash)
            if (recalled == null) Thread.sleep(250)
        }

        println("[Test] After path request, Identity.recall: ${recalled != null}")
        recalled shouldNotBe null
    }
}
