package network.reticulum.interfaces

import kotlinx.coroutines.*
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.udp.UDPInterface
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Tests for lifecycle-aware scope injection in TCP and UDP interfaces.
 *
 * Verifies:
 * - Parent scope cancellation propagates to interface
 * - Cancellation completes within 1 second (roadmap requirement)
 * - Standalone mode (null parent) continues working
 * - CancellationException treated as expected shutdown
 * - Interfaces stay alive while parent scope is active (positive path)
 * - SupervisorJob isolation prevents cascading failures
 */
class ScopeInjectionTest {

    private val interfaces = mutableListOf<Interface>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanup() {
        interfaces.forEach {
            try { it.detach() } catch (e: Exception) { /* ignore */ }
        }
        interfaces.clear()
        parentScopes.forEach {
            try { it.cancel() } catch (e: Exception) { /* ignore */ }
        }
        parentScopes.clear()
        Thread.sleep(100)
    }

    // ========================
    // UDP Scope Injection Tests
    // ========================

    @Test
    @Timeout(5)
    fun `UDP - standalone mode works without parent scope`() {
        // Create UDP interface without parentScope (standalone mode)
        val udp = UDPInterface(
            name = "standalone-udp",
            bindPort = 15001,
            forwardIp = "127.0.0.1",
            forwardPort = 15002
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get(), "Should be online")

        // Explicit stop should work
        udp.stop()
        Thread.sleep(100)
        assertFalse(udp.online.get(), "Should be offline after stop")
    }

    @Test
    @Timeout(5)
    fun `UDP - parent scope cancellation stops interface`() = runBlocking {
        // Create parent scope (simulates serviceScope)
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        // Create UDP interface with parent scope
        val udp = UDPInterface(
            name = "child-udp",
            bindPort = 15011,
            forwardIp = "127.0.0.1",
            forwardPort = 15012,
            parentScope = parentScope
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get(), "Should be online")

        // Cancel parent scope (simulates service stop)
        parentScope.cancel()

        // Wait for cancellation to propagate (should be quick)
        delay(500)
        assertFalse(udp.online.get(), "Should be offline after parent cancellation")
    }

    @Test
    @Timeout(2) // Strict 1-second requirement with margin
    fun `UDP - cancellation completes within 1 second`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val udp = UDPInterface(
            name = "timing-udp",
            bindPort = 15021,
            forwardIp = "127.0.0.1",
            forwardPort = 15022,
            parentScope = parentScope
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get())

        // Measure cancellation time
        val startTime = System.currentTimeMillis()
        parentScope.cancel()

        // Poll for completion (max 1 second)
        var elapsed = 0L
        while (udp.online.get() && elapsed < 1000) {
            delay(50)
            elapsed = System.currentTimeMillis() - startTime
        }

        assertFalse(udp.online.get(), "Should be offline within 1 second")
        assertTrue(elapsed < 1000, "Cancellation took ${elapsed}ms, should be < 1000ms")
    }

    @Test
    @Timeout(5)
    fun `UDP - multiple interfaces with same parent all cancel`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        // Create multiple UDP interfaces with same parent
        val udp1 = UDPInterface(
            name = "multi-udp-1",
            bindPort = 15031,
            forwardIp = "127.0.0.1",
            forwardPort = 15032,
            parentScope = parentScope
        )
        val udp2 = UDPInterface(
            name = "multi-udp-2",
            bindPort = 15033,
            forwardIp = "127.0.0.1",
            forwardPort = 15034,
            parentScope = parentScope
        )
        interfaces.add(udp1)
        interfaces.add(udp2)

        udp1.start()
        udp2.start()
        assertTrue(udp1.online.get() && udp2.online.get(), "Both should be online")

        // Cancel parent
        parentScope.cancel()
        delay(500)

        assertFalse(udp1.online.get(), "UDP1 should be offline")
        assertFalse(udp2.online.get(), "UDP2 should be offline")
    }

    // ========================
    // TCP Scope Injection Tests
    // ========================

    @Test
    @Timeout(5)
    fun `TCP - standalone mode works without parent scope`() {
        // Create TCP interface without parentScope (standalone mode)
        // Note: This won't actually connect, but verifies scope creation works
        val tcp = TCPClientInterface(
            name = "standalone-tcp",
            targetHost = "127.0.0.1",
            targetPort = 15101,
            connectTimeoutMs = 100,
            maxReconnectAttempts = 0
        )
        interfaces.add(tcp)

        // Don't start - just verify it can be created and stopped
        tcp.stop()
        // Should not throw
    }

    @Test
    @Timeout(5)
    fun `TCP - parent scope cancellation triggers cleanup`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        // Create TCP with parent scope
        val tcp = TCPClientInterface(
            name = "child-tcp",
            targetHost = "127.0.0.1",
            targetPort = 15111,
            connectTimeoutMs = 100,
            maxReconnectAttempts = 0,
            parentScope = parentScope
        )
        interfaces.add(tcp)

        // Start (will try to connect and fail, but that's fine)
        tcp.start()
        delay(200) // Let it attempt connection

        // Cancel parent
        parentScope.cancel()
        delay(500)

        assertTrue(tcp.detached.get(), "Should be detached after parent cancellation")
    }

    @Test
    @Timeout(2)
    fun `TCP - cancellation completes within 1 second`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val tcp = TCPClientInterface(
            name = "timing-tcp",
            targetHost = "127.0.0.1",
            targetPort = 15121,
            connectTimeoutMs = 100,
            maxReconnectAttempts = 0,
            parentScope = parentScope
        )
        interfaces.add(tcp)

        tcp.start()
        delay(200)

        val startTime = System.currentTimeMillis()
        parentScope.cancel()

        var elapsed = 0L
        while (!tcp.detached.get() && elapsed < 1000) {
            delay(50)
            elapsed = System.currentTimeMillis() - startTime
        }

        assertTrue(tcp.detached.get(), "Should be detached within 1 second")
        assertTrue(elapsed < 1000, "Cancellation took ${elapsed}ms, should be < 1000ms")
    }

    // ========================
    // Positive Path Tests (Connections remain alive when parent active)
    // ========================

    @Test
    @Timeout(5)
    fun `UDP - interface continues operating while parent scope is active`() = runBlocking {
        // This test verifies the "connections remain alive when backgrounded" requirement
        // The parent scope simulates serviceScope which remains active when app is backgrounded
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val udp = UDPInterface(
            name = "active-parent-udp",
            bindPort = 15301,
            forwardIp = "127.0.0.1",
            forwardPort = 15302,
            parentScope = parentScope
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get(), "Should be online after start")

        // Wait and verify interface STAYS online while parent is active
        // This simulates the app being backgrounded (service keeps running)
        delay(500)
        assertTrue(udp.online.get(), "Should STILL be online after 500ms with active parent")

        delay(500)
        assertTrue(udp.online.get(), "Should STILL be online after 1 second with active parent")

        // Parent scope is still active - interface should remain operational
        assertTrue(parentScope.isActive, "Parent scope should still be active")
    }

    @Test
    @Timeout(5)
    fun `TCP - interface continues operating while parent scope is active`() = runBlocking {
        // Similar test for TCP - verifies connections remain alive when backgrounded
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val tcp = TCPClientInterface(
            name = "active-parent-tcp",
            targetHost = "127.0.0.1",
            targetPort = 15311,
            connectTimeoutMs = 100,
            maxReconnectAttempts = 0,
            parentScope = parentScope
        )
        interfaces.add(tcp)

        tcp.start()

        // Wait for connection attempt (will fail but that's ok)
        delay(300)

        // The key test: interface should NOT be detached while parent is active
        // (only detached when parent is cancelled or explicit stop)
        assertTrue(parentScope.isActive, "Parent scope should still be active")
        assertFalse(tcp.detached.get(), "Interface should NOT be detached while parent is active")

        // Wait longer - still should not auto-detach
        delay(500)
        assertFalse(tcp.detached.get(), "Interface should STILL not be detached after 800ms")
    }

    // ========================
    // Isolation Tests (SupervisorJob)
    // ========================

    @Test
    @Timeout(5)
    fun `interfaces with same parent are isolated - one failure does not cancel others`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        // UDP that will work
        val udp = UDPInterface(
            name = "working-udp",
            bindPort = 15201,
            forwardIp = "127.0.0.1",
            forwardPort = 15202,
            parentScope = parentScope
        )

        // TCP that will fail to connect
        val tcp = TCPClientInterface(
            name = "failing-tcp",
            targetHost = "127.0.0.1",
            targetPort = 15203,
            connectTimeoutMs = 100,
            maxReconnectAttempts = 0,
            parentScope = parentScope
        )

        interfaces.add(udp)
        interfaces.add(tcp)

        udp.start()
        tcp.start()

        // Wait for TCP to fail
        delay(500)

        // UDP should still be online despite TCP failure
        assertTrue(udp.online.get(), "UDP should remain online when sibling TCP fails")
    }

    @Test
    @Timeout(5)
    fun `detach is idempotent - multiple calls are safe`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val udp = UDPInterface(
            name = "idempotent-test-udp",
            bindPort = 15401,
            forwardIp = "127.0.0.1",
            forwardPort = 15402,
            parentScope = parentScope
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get())

        // Multiple detach calls should be safe
        udp.detach()
        udp.detach()
        udp.detach()

        assertTrue(udp.detached.get(), "Should be detached")
        assertFalse(udp.online.get(), "Should be offline")
    }

    @Test
    @Timeout(5)
    fun `stop delegates to detach correctly`() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parentScopes.add(parentScope)

        val udp = UDPInterface(
            name = "stop-test-udp",
            bindPort = 15501,
            forwardIp = "127.0.0.1",
            forwardPort = 15502,
            parentScope = parentScope
        )
        interfaces.add(udp)

        udp.start()
        assertTrue(udp.online.get())

        // stop() should work the same as detach()
        udp.stop()

        assertTrue(udp.detached.get(), "Should be detached after stop()")
        assertFalse(udp.online.get(), "Should be offline after stop()")
    }
}
