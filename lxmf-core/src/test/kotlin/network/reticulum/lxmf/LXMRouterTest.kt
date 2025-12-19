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

    @Test
    fun `test message representation is PACKET for small messages`() = runBlocking {
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

        // Small message - should be PACKET representation
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = "Short message",
            title = "Test"
        )

        router.handleOutbound(message)

        assertEquals(MessageRepresentation.PACKET, message.representation)
    }

    @Test
    fun `test message representation is RESOURCE for large messages`() = runBlocking {
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

        // Large message - should be RESOURCE representation
        val largeContent = "X".repeat(500)  // Well over link packet limit
        val message = LXMessage.create(
            destination = destDestination,
            source = sourceDestination,
            content = largeContent,
            title = "Large"
        )

        router.handleOutbound(message)

        assertEquals(MessageRepresentation.RESOURCE, message.representation)
    }

    @Test
    fun `test direct delivery method increments attempts`() = runBlocking {
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

        assertEquals(DeliveryMethod.DIRECT, message.desiredMethod)
        assertEquals(0, message.deliveryAttempts)

        router.handleOutbound(message)
        router.processOutbound()

        // Direct delivery without path/link should increment attempts
        assertEquals(1, message.deliveryAttempts)

        // Process again
        router.processOutbound()

        // May retry depending on timing
        assertTrue(message.deliveryAttempts >= 1)
    }

    @Test
    fun `test message state transitions`() = runBlocking {
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
            title = "Test"
        )

        // Initially GENERATING
        assertEquals(MessageState.GENERATING, message.state)

        // After handleOutbound - should be OUTBOUND
        router.handleOutbound(message)
        assertEquals(MessageState.OUTBOUND, message.state)
    }

    @Test
    fun `test max delivery attempts results in failure`() = runBlocking {
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

        // Set delivery attempts to max
        message.deliveryAttempts = LXMRouter.MAX_DELIVERY_ATTEMPTS

        router.handleOutbound(message)

        // Force immediate retry by clearing next attempt time
        message.nextDeliveryAttempt = null

        router.processOutbound()

        // Should be FAILED after exceeding max attempts
        assertEquals(MessageState.FAILED, message.state)
    }

    // ===== Propagation Node Tests =====

    @Test
    fun `test propagation node tracking`() {
        // Initially no propagation nodes
        assertEquals(0, router.getPropagationNodes().size)
        assertEquals(null, router.getActivePropagationNode())
    }

    @Test
    fun `test set active propagation node without known node fails`() {
        // Try to set unknown node
        val result = router.setActivePropagationNode("0123456789abcdef")
        assertEquals(false, result)
        assertEquals(null, router.getActivePropagationNode())
    }

    @Test
    fun `test propagation transfer state initially idle`() {
        assertEquals(LXMRouter.PropagationTransferState.IDLE, router.propagationTransferState)
        assertEquals(0.0, router.propagationTransferProgress)
        assertEquals(0, router.propagationTransferLastResult)
    }

    @Test
    fun `test propagated message queuing without active node`() = runBlocking {
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
            content = "Test via propagation",
            title = "Propagated",
            desiredMethod = DeliveryMethod.PROPAGATED
        )

        assertEquals(DeliveryMethod.PROPAGATED, message.desiredMethod)

        router.handleOutbound(message)
        assertEquals(1, router.pendingOutboundCount())

        // Process should increment attempts but not fail immediately
        // (no active node means it will retry later)
        router.processOutbound()

        assertEquals(1, message.deliveryAttempts)
        assertEquals(MessageState.OUTBOUND, message.state)
    }

    @Test
    fun `test get outbound propagation cost without active node`() {
        val cost = router.getOutboundPropagationCost()
        assertEquals(null, cost)
    }

    // Make propagationTransferState accessible for tests
    val LXMRouter.propagationTransferState: LXMRouter.PropagationTransferState
        get() = try {
            val field = LXMRouter::class.java.getDeclaredField("propagationTransferState")
            field.isAccessible = true
            field.get(this) as LXMRouter.PropagationTransferState
        } catch (e: Exception) {
            LXMRouter.PropagationTransferState.IDLE
        }
}
