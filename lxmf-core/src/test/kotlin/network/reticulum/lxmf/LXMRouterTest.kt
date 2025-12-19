package network.reticulum.lxmf

import kotlinx.coroutines.runBlocking
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for LXMRouter.
 */
class LXMRouterTest {

    private lateinit var router: LXMRouter
    private lateinit var identity: Identity

    @BeforeEach
    fun setup() {
        identity = Identity.create()
        router = LXMRouter(identity = identity)
    }

    @AfterEach
    fun teardown() {
        router.stop()
    }

    @Test
    fun `test router creation`() {
        assertNotNull(router)
    }

    @Test
    fun `test register delivery identity`() {
        val destination = router.registerDeliveryIdentity(identity, "TestNode")

        assertNotNull(destination)
        assertEquals(DestinationDirection.IN, destination.direction)
        assertEquals(DestinationType.SINGLE, destination.type)
        assertEquals("lxmf", destination.appName)

        // Verify destination is stored
        val stored = router.getDeliveryDestination(destination.hexHash)
        assertNotNull(stored)
        assertEquals("TestNode", stored.displayName)
    }

    @Test
    fun `test register delivery callback`() {
        var receivedMessage: LXMessage? = null

        router.registerDeliveryCallback { message ->
            receivedMessage = message
        }

        // Callback is registered (we can't easily test it fires without full transport)
        assertNotNull(router)
    }

    @Test
    fun `test handle outbound message`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test message",
            title = "Test"
        )

        // Queue the message
        router.handleOutbound(message)

        // Verify message is queued
        assertEquals(1, router.pendingOutboundCount())
        assertEquals(MessageState.OUTBOUND, message.state)
        assertNotNull(message.packed)
    }

    @Test
    fun `test router start and stop`() {
        router.start()
        // Give it a moment to start
        Thread.sleep(100)

        router.stop()
        // Should stop without error
    }

    @Test
    fun `test multiple delivery destinations`() {
        val identity1 = Identity.create()
        val identity2 = Identity.create()

        val dest1 = router.registerDeliveryIdentity(identity1, "Node1")
        val dest2 = router.registerDeliveryIdentity(identity2, "Node2")

        assertNotNull(dest1)
        assertNotNull(dest2)

        val destinations = router.getDeliveryDestinations()
        assertEquals(2, destinations.size)
    }

    @Test
    fun `test failed delivery callback registration`() {
        var failedMessage: LXMessage? = null

        router.registerFailedDeliveryCallback { message ->
            failedMessage = message
        }

        // Callback is registered
        assertNotNull(router)
    }

    @Test
    fun `test process outbound with no messages`() = runBlocking {
        // Should not throw when processing empty queue
        router.processOutbound()

        assertEquals(0, router.pendingOutboundCount())
    }

    @Test
    fun `test message delivery attempt tracking`() = runBlocking {
        val destIdentity = Identity.create()

        val sourceDestination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val destDestination = Destination.create(
            identity = destIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )

        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Test",
            title = "Test",
            desiredMethod = DeliveryMethod.DIRECT
        )

        assertEquals(0, message.deliveryAttempts)

        router.handleOutbound(message)

        // Process should increment delivery attempts
        router.processOutbound()

        assertTrue(message.deliveryAttempts > 0)
    }
}
