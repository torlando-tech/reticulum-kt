package network.reticulum.interfaces.tcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests for TCPClientInterface backoff integration.
 *
 * These tests verify that ExponentialBackoff is properly integrated
 * into TCPClientInterface. The core backoff logic is tested in
 * ExponentialBackoffTest - these tests focus on the integration.
 */
class TCPClientInterfaceBackoffTest {

    // Track interfaces for cleanup
    private val interfaces = mutableListOf<TCPClientInterface>()

    @AfterEach
    fun cleanup() {
        interfaces.forEach { it.stop() }
        interfaces.clear()
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `onNetworkChanged is callable and doesn't throw`() {
        val iface = createInterface("TestBackoff", maxReconnectAttempts = 1)

        // Should not throw - verifies method exists and is wired correctly
        iface.onNetworkChanged()

        // Interface should still be in valid state
        assertFalse(iface.detached.get())
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `interface accepts maxReconnectAttempts parameter`() {
        // Verify the constructor parameter works without throwing
        val iface1 = createInterface("TestAttempts1", maxReconnectAttempts = 1)
        val iface5 = createInterface("TestAttempts5", maxReconnectAttempts = 5)
        val ifaceDefault = createInterface("TestAttemptsDefault", maxReconnectAttempts = null)

        // All should be created successfully
        assertNotNull(iface1)
        assertNotNull(iface5)
        assertNotNull(ifaceDefault)
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `onNetworkChanged can be called multiple times`() {
        val iface = createInterface("TestMultipleChanges", maxReconnectAttempts = 1)

        // Simulate multiple network changes (e.g., WiFi -> Cellular -> WiFi)
        repeat(3) {
            iface.onNetworkChanged()
        }

        // Should not have thrown or corrupted state
        assertFalse(iface.detached.get())
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `onNetworkChanged works after interface stop`() {
        val iface = createInterface("TestStopThenChange", maxReconnectAttempts = 1)

        iface.stop()

        // Should not throw even after stop
        iface.onNetworkChanged()

        // Interface should be detached
        assertTrue(iface.detached.get())
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `interface with parent scope cancels cleanly`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val iface = TCPClientInterface(
            name = "TestParentScope",
            targetHost = "192.0.2.1", // TEST-NET, unreachable
            targetPort = 12345,
            maxReconnectAttempts = 1,
            parentScope = parentScope
        )
        interfaces.add(iface)

        // Give interface time to initialize
        delay(100)

        // Cancel parent scope
        parentScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        // Wait for cancellation to propagate
        delay(200)

        // Interface should be detached after parent cancellation
        assertTrue(iface.detached.get())
    }

    /**
     * Helper to create interface with TEST-NET address (RFC 5737).
     * 192.0.2.0/24 is reserved for documentation and will not connect.
     */
    private fun createInterface(
        name: String,
        maxReconnectAttempts: Int?
    ): TCPClientInterface {
        val iface = TCPClientInterface(
            name = name,
            targetHost = "192.0.2.1", // TEST-NET, unreachable
            targetPort = 12345,
            maxReconnectAttempts = maxReconnectAttempts
        )
        interfaces.add(iface)
        return iface
    }
}
