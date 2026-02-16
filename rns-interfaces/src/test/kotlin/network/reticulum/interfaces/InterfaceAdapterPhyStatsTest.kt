package network.reticulum.interfaces

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for PHY stats propagation through InterfaceAdapter.
 *
 * Phase 4 added rStatRssi/rStatSnr/rStatQ fields to Interface (base class)
 * and InterfaceAdapter (delegating to Interface fields). These tests verify
 * that setting stats on the Interface is visible through the InterfaceRef
 * created by toRef().
 */
@DisplayName("InterfaceAdapter PHY Stats")
class InterfaceAdapterPhyStatsTest {

    /**
     * Minimal Interface implementation for testing.
     */
    private class StubInterface : Interface("StubPhyStats") {
        override fun processOutgoing(data: ByteArray) {}
        override fun start() { online.set(true) }
    }

    @Test
    fun `interface has null PHY stats by default`() {
        val iface = StubInterface()
        iface.rStatRssi shouldBe null
        iface.rStatSnr shouldBe null
        iface.rStatQ shouldBe null
    }

    @Test
    fun `interface PHY stats propagate through adapter`() {
        val iface = StubInterface()
        iface.rStatRssi = -92
        iface.rStatSnr = 6.25f
        iface.rStatQ = 0.75f

        val ref = iface.toRef()
        ref.rStatRssi shouldBe -92
        ref.rStatSnr shouldBe 6.25f
        ref.rStatQ shouldBe 0.75f
    }

    @Test
    fun `adapter reflects live updates to interface stats`() {
        val iface = StubInterface()
        val ref = iface.toRef()

        // Initially null
        ref.rStatRssi shouldBe null

        // Update interface
        iface.rStatRssi = -80

        // Adapter should see the change immediately (it's a delegate, not a copy)
        ref.rStatRssi shouldBe -80

        // Update again
        iface.rStatRssi = -60
        ref.rStatRssi shouldBe -60
    }

    @Test
    fun `same adapter returned for same interface`() {
        val iface = StubInterface()
        val ref1 = iface.toRef()
        val ref2 = iface.toRef()

        // Should be the exact same cached adapter instance
        (ref1 === ref2) shouldBe true
    }

    @Test
    fun `different interfaces have independent PHY stats`() {
        val iface1 = StubInterface()
        val iface2 = StubInterface()

        iface1.rStatRssi = -100
        iface2.rStatRssi = -50

        val ref1 = iface1.toRef()
        val ref2 = iface2.toRef()

        ref1.rStatRssi shouldBe -100
        ref2.rStatRssi shouldBe -50
    }
}
