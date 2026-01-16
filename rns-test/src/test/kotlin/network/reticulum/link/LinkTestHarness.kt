package network.reticulum.link

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.local.LocalServerInterface
import network.reticulum.interfaces.toRef
import network.reticulum.packet.Packet
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Test harness for two-sided Link tests.
 *
 * Creates two Transport instances connected via LocalInterface for testing
 * full link lifecycle: establishment, packet exchange, and teardown.
 *
 * Usage:
 * ```kotlin
 * val harness = TwoNodeTestHarness()
 * harness.start()
 * try {
 *     // Create destination on node2
 *     val dest = harness.createDestination(appName = "test", aspects = arrayOf("echo"))
 *
 *     // Initiate link from node1
 *     val link = harness.initiateLink(dest)
 *
 *     // Wait for establishment
 *     harness.awaitLinkEstablished(link, timeout = 5.seconds)
 * } finally {
 *     harness.cleanup()
 * }
 * ```
 */
class TwoNodeTestHarness(
    /** TCP port for LocalInterface communication. */
    private val port: Int = 37500 + (Math.random() * 1000).toInt()
) {
    // Server interface (node2 - responder)
    private var server: LocalServerInterface? = null

    // Client interface (node1 - initiator)
    private var client: LocalClientInterface? = null

    // Track created destinations for cleanup
    private val destinations = mutableListOf<Destination>()

    // Track created links for cleanup
    private val links = mutableListOf<Link>()

    // Identity for responder destination
    private var responderIdentity: Identity? = null

    /**
     * Start the test harness.
     *
     * Creates two connected Transport nodes:
     * - node1: Initiator (client interface)
     * - node2: Responder (server interface)
     *
     * NOTE: Due to Transport being a singleton with hashlist duplicate detection,
     * packets that loop back through the same Transport are filtered. For full
     * end-to-end testing, use multi-process tests with Python interop.
     */
    fun start() {
        // Stop any existing Transport
        Transport.stop()
        Thread.sleep(100)

        // Start Transport
        Transport.start(enableTransport = false)

        // Create server interface (responder)
        server = LocalServerInterface(name = "TestServer", tcpPort = port)
        // Set up packet callback to route to Transport
        // Clear hashlist before inbound to allow loopback in single-Transport tests
        server!!.onPacketReceived = { data, fromInterface ->
            Transport.clearPacketHashlist()  // Allow "duplicate" packets for testing
            Transport.inbound(data, fromInterface.toRef())
        }
        server!!.start()
        Transport.registerInterface(server!!.toRef())

        // Create client interface (initiator)
        client = LocalClientInterface(name = "TestClient", tcpPort = port)
        // Set up packet callback to route to Transport
        client!!.onPacketReceived = { data, fromInterface ->
            Transport.clearPacketHashlist()  // Allow "duplicate" packets for testing
            Transport.inbound(data, fromInterface.toRef())
        }
        client!!.start()
        Transport.registerInterface(client!!.toRef())

        // Wait for connection
        Thread.sleep(200)
    }

    /**
     * Create a destination on the responder node.
     *
     * @param appName Application name for the destination
     * @param aspects Additional aspects for the destination hash
     * @param acceptLinks Whether this destination accepts incoming link requests
     * @return The created Destination
     */
    fun createDestination(
        appName: String = "test",
        vararg aspects: String = arrayOf("link"),
        acceptLinks: Boolean = true
    ): Destination {
        val identity = Identity.create()
        responderIdentity = identity

        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = aspects
        )

        if (acceptLinks) {
            destination.acceptLinkRequests = true
        }

        Transport.registerDestination(destination)
        destinations.add(destination)

        return destination
    }

    /**
     * Initiate a link to a destination.
     *
     * @param destination The destination to link to
     * @param establishedCallback Optional callback when link is established
     * @param closedCallback Optional callback when link is closed
     * @return The created Link
     */
    fun initiateLink(
        destination: Destination,
        establishedCallback: ((Link) -> Unit)? = null,
        closedCallback: ((Link) -> Unit)? = null
    ): Link {
        val link = Link.create(
            destination = destination,
            establishedCallback = establishedCallback,
            closedCallback = closedCallback
        )
        links.add(link)
        return link
    }

    /**
     * Wait for a link to reach ACTIVE status.
     *
     * @param link The link to wait for
     * @param timeout Maximum time to wait
     * @return true if link became active, false on timeout
     */
    fun awaitLinkActive(link: Link, timeout: Duration = 5.seconds): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds

        while (System.currentTimeMillis() < deadline) {
            if (link.status == LinkConstants.ACTIVE) {
                return true
            }
            if (link.status == LinkConstants.CLOSED) {
                return false
            }
            Thread.sleep(50)
        }

        return link.status == LinkConstants.ACTIVE
    }

    /**
     * Wait for a link to reach CLOSED status.
     *
     * @param link The link to wait for
     * @param timeout Maximum time to wait
     * @return true if link closed, false on timeout
     */
    fun awaitLinkClosed(link: Link, timeout: Duration = 5.seconds): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds

        while (System.currentTimeMillis() < deadline) {
            if (link.status == LinkConstants.CLOSED) {
                return true
            }
            Thread.sleep(50)
        }

        return link.status == LinkConstants.CLOSED
    }

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        // Teardown links
        links.forEach { link ->
            try {
                if (link.status != LinkConstants.CLOSED) {
                    link.teardown()
                }
            } catch (_: Exception) {}
        }
        links.clear()

        // Clear destinations
        destinations.clear()

        // Detach interfaces
        try { client?.detach() } catch (_: Exception) {}
        try { server?.detach() } catch (_: Exception) {}
        client = null
        server = null

        // Stop Transport
        Transport.stop()

        // Give sockets time to close
        Thread.sleep(100)
    }
}

/**
 * Records link callback events for testing.
 *
 * Usage:
 * ```kotlin
 * val recorder = LinkCallbackRecorder()
 * val link = Link.create(destination,
 *     establishedCallback = recorder::onEstablished,
 *     closedCallback = recorder::onClosed
 * )
 *
 * // Wait for establishment
 * val established = recorder.awaitEstablished(timeout = 5.seconds)
 * ```
 */
class LinkCallbackRecorder {
    /** Links that have been established. */
    val establishedLinks = ConcurrentLinkedQueue<Link>()

    /** Links that have been closed with their teardown reasons. */
    val closedLinks = ConcurrentLinkedQueue<Pair<Link, Int>>()

    /** Packets received on links (link, data, packet). */
    val receivedPackets = ConcurrentLinkedQueue<Triple<Link, ByteArray, Packet>>()

    // Latches for synchronization
    private val establishedLatch = AtomicReference(CountDownLatch(1))
    private val closedLatch = AtomicReference(CountDownLatch(1))
    private val packetLatch = AtomicReference(CountDownLatch(1))

    /**
     * Callback for link established events.
     */
    fun onEstablished(link: Link) {
        establishedLinks.add(link)
        establishedLatch.get().countDown()
    }

    /**
     * Callback for link closed events.
     */
    fun onClosed(link: Link) {
        closedLinks.add(link to link.teardownReason)
        closedLatch.get().countDown()
    }

    /**
     * Callback for packet received events.
     */
    fun onPacket(data: ByteArray, packet: Packet, link: Link) {
        receivedPackets.add(Triple(link, data, packet))
        packetLatch.get().countDown()
    }

    /**
     * Create a packet callback bound to a specific link.
     */
    fun packetCallbackFor(link: Link): (ByteArray, Packet) -> Unit {
        return { data, packet -> onPacket(data, packet, link) }
    }

    /**
     * Wait for a link to be established.
     *
     * @param timeout Maximum time to wait
     * @return The established link, or null on timeout
     */
    fun awaitEstablished(timeout: Duration = 5.seconds): Link? {
        if (establishedLatch.get().await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            return establishedLinks.poll()
        }
        return null
    }

    /**
     * Wait for a link to be closed.
     *
     * @param timeout Maximum time to wait
     * @return Pair of (link, teardownReason), or null on timeout
     */
    fun awaitClosed(timeout: Duration = 5.seconds): Pair<Link, Int>? {
        if (closedLatch.get().await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            return closedLinks.poll()
        }
        return null
    }

    /**
     * Wait for a packet to be received.
     *
     * @param timeout Maximum time to wait
     * @return Triple of (link, data, packet), or null on timeout
     */
    fun awaitPacket(timeout: Duration = 5.seconds): Triple<Link, ByteArray, Packet>? {
        if (packetLatch.get().await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            return receivedPackets.poll()
        }
        return null
    }

    /**
     * Reset the recorder for reuse.
     */
    fun reset() {
        establishedLinks.clear()
        closedLinks.clear()
        receivedPackets.clear()
        establishedLatch.set(CountDownLatch(1))
        closedLatch.set(CountDownLatch(1))
        packetLatch.set(CountDownLatch(1))
    }

    /**
     * Reset latches for expecting new events.
     *
     * @param established Number of establishment events to expect
     * @param closed Number of close events to expect
     * @param packets Number of packet events to expect
     */
    fun expectEvents(established: Int = 1, closed: Int = 1, packets: Int = 1) {
        if (established > 0) establishedLatch.set(CountDownLatch(established))
        if (closed > 0) closedLatch.set(CountDownLatch(closed))
        if (packets > 0) packetLatch.set(CountDownLatch(packets))
    }
}

/**
 * Extension to wait for multiple packets.
 */
fun LinkCallbackRecorder.awaitPackets(
    count: Int,
    timeout: Duration = 5.seconds
): List<Triple<Link, ByteArray, Packet>> {
    val result = mutableListOf<Triple<Link, ByteArray, Packet>>()
    val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds

    while (result.size < count && System.currentTimeMillis() < deadline) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break

        val packet = awaitPacket(remaining.coerceAtLeast(0).let { Duration.parse("${it}ms") })
        if (packet != null) {
            result.add(packet)
            expectEvents(packets = 1)  // Reset for next packet
        }
    }

    return result
}

/**
 * Helper to get human-readable link status.
 */
fun Link.statusName(): String = when (status) {
    LinkConstants.PENDING -> "PENDING"
    LinkConstants.HANDSHAKE -> "HANDSHAKE"
    LinkConstants.ACTIVE -> "ACTIVE"
    LinkConstants.STALE -> "STALE"
    LinkConstants.CLOSED -> "CLOSED"
    else -> "UNKNOWN($status)"
}

/**
 * Helper to get human-readable teardown reason.
 */
fun teardownReasonName(reason: Int): String = when (reason) {
    LinkConstants.TEARDOWN_REASON_UNKNOWN -> "UNKNOWN"
    LinkConstants.TIMEOUT -> "TIMEOUT"
    LinkConstants.INITIATOR_CLOSED -> "INITIATOR_CLOSED"
    LinkConstants.DESTINATION_CLOSED -> "DESTINATION_CLOSED"
    LinkConstants.TEARDOWN_REASON_LINK_ERROR -> "LINK_ERROR"
    else -> "REASON_$reason"
}
