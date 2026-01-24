package network.reticulum.lxmf.interop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Smoke test for direct delivery infrastructure.
 *
 * Verifies that Python and Kotlin Reticulum instances can start and connect.
 * This is the foundation for all Phase 6 direct delivery tests.
 */
class DirectDeliverySmokeTest : DirectDeliveryTestBase() {

    @Test
    fun `infrastructure starts successfully`() {
        // Python side should have destination
        pythonDestHash shouldNotBe null
        pythonDestHash!!.size shouldBe 16

        pythonIdentityHash shouldNotBe null
        pythonIdentityHash!!.size shouldBe 16

        // Kotlin side should have router
        kotlinRouter shouldNotBe null
        kotlinDestination shouldNotBe null
        kotlinDestination.hash.size shouldBe 16
    }

    @Test
    fun `TCP connection is active`() {
        // TCP client should be connected
        kotlinTcpClient shouldNotBe null
        kotlinTcpClient!!.online.get() shouldBe true
    }

    @Test
    fun `Python messages list starts empty`() {
        clearPythonMessages()
        val messages = getPythonMessages()
        messages.size shouldBe 0
    }
}
