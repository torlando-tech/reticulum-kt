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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue

/**
 * Tests for Link callback invocation.
 *
 * Note: Full end-to-end callback testing requires two separate Transport
 * instances for complete link establishment. These tests verify local callback
 * registration and invocation where possible. Full end-to-end tests should use
 * Python interop (see LinkInteropTest).
 */
@DisplayName("Link Callback Tests")
class LinkCallbackTest {

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
    @DisplayName("Closed callback fires on teardown")
    @Timeout(5)
    fun `closed callback fires on teardown`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "callbacks",
            aspects = arrayOf("test", "closed")
        )

        val closedCallbackFired = AtomicBoolean(false)

        val link = Link.create(
            destination = destination,
            closedCallback = { closedCallbackFired.set(true) }
        )

        // Teardown should fire the callback
        link.teardown()

        assertTrue(closedCallbackFired.get(),
            "Closed callback should fire on teardown")
    }

    @Test
    @DisplayName("Callback can be set via setter method")
    @Timeout(5)
    fun `callback can be set via setter method`() {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "callbacks",
            aspects = arrayOf("test", "setter")
        )

        val closedCallbackFired = AtomicBoolean(false)

        val link = Link.create(destination)

        // Set callback after creation
        link.setLinkClosedCallback { closedCallbackFired.set(true) }

        link.teardown()

        assertTrue(closedCallbackFired.get(),
            "Callback set via setter should fire")
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("linkEstablished callback invoked on successful establishment")
    @Timeout(10)
    fun `linkEstablished callback invoked on successful establishment`() {
        // This test requires full end-to-end link establishment
        // which needs two separate Transport instances or Python interop.
        // See LinkInteropTest for end-to-end callback testing.
    }

    @Test
    @Disabled("Requires full link establishment - use Python interop tests")
    @DisplayName("packetReceived callback invoked for sent packets")
    @Timeout(10)
    fun `packetReceived callback invoked for sent packets`() {
        // This test requires full end-to-end link establishment.
        // See LinkInteropTest for end-to-end testing.
    }
}
