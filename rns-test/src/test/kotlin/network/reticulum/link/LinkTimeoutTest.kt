package network.reticulum.link

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for Link timeout and keepalive behavior.
 *
 * Note: Some timeout tests require full link establishment or very long wait times.
 * These tests verify timeout configuration and basic behavior.
 */
@DisplayName("Link Timeout Tests")
class LinkTimeoutTest {

    @BeforeEach
    fun setup() {
        Transport.stop()
        Thread.sleep(100)
        Transport.start(enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        Transport.stop()
        Thread.sleep(100)
    }

    @Test
    @DisplayName("Link timeout constants are correctly configured")
    @Timeout(5)
    fun `link timeout constants are correctly configured`() {
        // Verify timeout constants match Python reference values
        assertEquals(360_000L, LinkConstants.KEEPALIVE_MAX,
            "KEEPALIVE_MAX should be 360 seconds")
        assertEquals(5_000L, LinkConstants.KEEPALIVE_MIN,
            "KEEPALIVE_MIN should be 5 seconds")
        assertEquals(5_000L, LinkConstants.STALE_GRACE,
            "STALE_GRACE should be 5 seconds")
        assertEquals(2, LinkConstants.STALE_FACTOR,
            "STALE_FACTOR should be 2")
        assertEquals(720_000L, LinkConstants.STALE_TIME,
            "STALE_TIME should be 2x KEEPALIVE (720 seconds)")
        assertEquals(6_000L, LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP,
            "ESTABLISHMENT_TIMEOUT_PER_HOP should be 6 seconds")
    }

    @Test
    @DisplayName("Link establishment timeout is calculated per-hop")
    @Timeout(5)
    fun `link establishment timeout is calculated per-hop`() {
        // Verify that establishment timeout scales with hop count
        val perHop = LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP

        // For 1 hop
        val oneHop = perHop * 1
        assertEquals(6_000L, oneHop, "1 hop timeout should be 6 seconds")

        // For 3 hops
        val threeHops = perHop * 3
        assertEquals(18_000L, threeHops, "3 hop timeout should be 18 seconds")

        // For 10 hops (max typical)
        val tenHops = perHop * 10
        assertEquals(60_000L, tenHops, "10 hop timeout should be 60 seconds")
    }

    @Test
    @DisplayName("New link has correct initial state")
    @Timeout(5)
    fun `new link has correct initial state`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "timeout",
            aspects = arrayOf("test", "init")
        )

        val link = Link.create(destination)

        // New link should be in PENDING state
        assertEquals(LinkConstants.PENDING, link.status)

        // Should not be STALE or CLOSED
        assertNotEquals(LinkConstants.STALE, link.status)
        assertNotEquals(LinkConstants.CLOSED, link.status)
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("Established link does not immediately timeout")
    @Timeout(15)
    fun `established link does not immediately timeout`() {
        // This test requires full link establishment.
        // See LinkInteropTest for end-to-end testing.
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("Activity updates last inbound time")
    @Timeout(15)
    fun `activity updates last inbound time`() {
        // This test requires full link establishment.
        // See LinkInteropTest for end-to-end testing.
    }

    @Test
    @Disabled("This test takes too long (6+ minutes) - enable for full validation")
    @DisplayName("Link transitions to STALE after STALE_TIME")
    @Timeout(value = 800)
    fun `link transitions to STALE after STALE_TIME`() {
        // This test requires waiting for STALE_TIME (720 seconds = 12 minutes)
        // Enable manually for full validation.
    }

    @Test
    @Disabled("This test takes too long (6+ minutes) - enable for full validation")
    @DisplayName("Keepalive packet prevents stale transition")
    @Timeout(value = 800)
    fun `keepalive packet prevents stale transition`() {
        // This test requires waiting beyond STALE_TIME
        // Enable manually for full validation.
    }
}
