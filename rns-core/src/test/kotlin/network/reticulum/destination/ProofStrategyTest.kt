package network.reticulum.destination

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for Destination.shouldProve() proof strategy logic.
 *
 * Phase 3 added shouldProve() which encapsulates the PROVE_NONE/PROVE_APP/PROVE_ALL
 * decision. These tests verify the logic without needing a live Transport or Link.
 */
@DisplayName("Destination Proof Strategy")
class ProofStrategyTest {

    /**
     * Create a minimal destination for proof strategy testing.
     * Uses a public no-identity destination since we only test the proof strategy logic.
     */
    private fun createTestDestination(): Destination {
        return Destination.create(
            identity = null,
            direction = network.reticulum.common.DestinationDirection.IN,
            type = network.reticulum.common.DestinationType.PLAIN,
            appName = "prooftest",
            aspects = arrayOf("test")
        )
    }

    @Test
    fun `default proof strategy is PROVE_NONE`() {
        val dest = createTestDestination()
        dest.getProofStrategy() shouldBe Destination.PROVE_NONE
    }

    @Test
    fun `PROVE_NONE never proves`() {
        val dest = createTestDestination()
        dest.setProofStrategy(Destination.PROVE_NONE)
        dest.shouldProve("any packet") shouldBe false
    }

    @Test
    fun `PROVE_ALL always proves`() {
        val dest = createTestDestination()
        dest.setProofStrategy(Destination.PROVE_ALL)
        dest.shouldProve("any packet") shouldBe true
    }

    @Test
    fun `PROVE_APP delegates to callback`() {
        val dest = createTestDestination()
        dest.setProofStrategy(Destination.PROVE_APP)

        // No callback set → should not prove
        dest.shouldProve("packet") shouldBe false

        // Set callback that always returns true
        dest.setProofRequestedCallback { true }
        dest.shouldProve("packet") shouldBe true

        // Set callback that always returns false
        dest.setProofRequestedCallback { false }
        dest.shouldProve("packet") shouldBe false
    }

    @Test
    fun `PROVE_APP handles callback exceptions gracefully`() {
        val dest = createTestDestination()
        dest.setProofStrategy(Destination.PROVE_APP)

        // Callback that throws
        dest.setProofRequestedCallback { throw RuntimeException("boom") }
        dest.shouldProve("packet") shouldBe false
    }

    @Test
    fun `setProofStrategy changes strategy`() {
        val dest = createTestDestination()

        dest.setProofStrategy(Destination.PROVE_ALL)
        dest.getProofStrategy() shouldBe Destination.PROVE_ALL
        dest.shouldProve("x") shouldBe true

        dest.setProofStrategy(Destination.PROVE_NONE)
        dest.getProofStrategy() shouldBe Destination.PROVE_NONE
        dest.shouldProve("x") shouldBe false
    }
}
