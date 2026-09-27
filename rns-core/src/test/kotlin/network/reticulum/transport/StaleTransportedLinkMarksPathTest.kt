package network.reticulum.transport

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The transport-node arm of the stale pending-link handler.
 *
 * `Transport.markPathUnresponsive` had no caller inside the library. It is reachable from
 * the conformance bridge, and the conformance suite drives it directly and then checks the
 * announce branch that depends on it, so the function's behaviour was covered. What was
 * not covered, and not implemented, is the wiring: nothing in a running node ever called
 * it, so a path never left ACTIVE however often a link across it failed.
 *
 * These tests therefore assert the wiring specifically. Nothing here calls
 * `markPathUnresponsive`; the cull does, or it does not. Re-testing the announce branch
 * would prove nothing new, because the conformance suite already owns it.
 *
 * Reference: `Transport.py:884-950`.
 */
class StaleTransportedLinkMarksPathTest {

    private lateinit var identity: Identity

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        try { Transport.stop() } catch (e: Exception) { /* not running */ }
        Transport.setStoragePath(tempDir.toString())
        identity = Identity.create()
    }

    @AfterEach
    fun teardown() {
        try { Transport.stop() } catch (e: Exception) { /* already down */ }
    }

    /** A one-hop path, as if the destination had been local to one of our interfaces. */
    private fun seedPath(
        destHash: ByteArray,
        iface: InterfaceRef,
        hops: Int = 1,
    ) {
        Transport.pathTable[destHash.toKey()] = PathEntry(
            timestamp = System.currentTimeMillis(),
            nextHop = ByteArray(16) { 0x22 },
            hops = hops,
            expires = System.currentTimeMillis() + 600_000,
            randomBlobs = mutableListOf(ByteArray(10) { 0x55 }),
            receivingInterfaceHash = iface.hash,
            announcePacketHash = ByteArray(16) { 0x33 },
        )
    }

    /** A transported link whose proof deadline has already passed and never validated. */
    private fun seedStalePendingLink(
        destHash: ByteArray,
        iface: InterfaceRef,
        takenHops: Int,
    ) {
        Transport.linkTable[ByteArray(16) { 0x44 }.toKey()] = LinkEntry(
            timestamp = System.currentTimeMillis() - 60_000,
            nextHop = ByteArray(16) { 0x22 },
            nextHopInterfaceHash = iface.hash,
            remainingHops = 1,
            receivingInterfaceHash = iface.hash,
            takenHops = takenHops,
            destinationHash = destHash,
            validated = false,
            proofTimeout = System.currentTimeMillis() - 1_000,
        )
    }

    private fun destinationHash(): ByteArray =
        Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "test",
            aspects = arrayOf("stalelink"),
        ).hash

    @Test
    fun `a transported link that never validates marks its one-hop path unresponsive`() {
        Transport.start(identity, enableTransport = true)
        val iface = FakeInterface("Seeded", InterfaceMode.FULL)
        Transport.registerInterface(iface)

        val destHash = destinationHash()
        seedPath(destHash, iface, hops = 1)
        seedStalePendingLink(destHash, iface, takenHops = 1)

        assertEquals(
            PathState.ACTIVE,
            Transport.pathTable[destHash.toKey()]?.state,
            "precondition: the path starts ACTIVE",
        )

        Transport.forceCullForTest()

        val entry = Transport.pathTable[destHash.toKey()]
        assertNotNull(entry, "one failure must not expire the path outright")
        assertEquals(
            PathState.UNRESPONSIVE,
            entry!!.state,
            "the cull must mark the path through markPathUnresponsive, with no direct call",
        )
        assertEquals(1, entry.failureCount, "exactly one failure was recorded")
    }

    /**
     * The reference spares a boundary interface: it exists to keep two networks apart, so
     * a failure reaching us across one says nothing about the path on our side
     * (`Transport.py:927, :942`).
     */
    @Test
    fun `a failure arriving on a boundary interface does not mark the path`() {
        Transport.start(identity, enableTransport = true)
        val iface = FakeInterface("Boundary", InterfaceMode.BOUNDARY)
        Transport.registerInterface(iface)

        val destHash = destinationHash()
        seedPath(destHash, iface, hops = 1)
        seedStalePendingLink(destHash, iface, takenHops = 1)

        Transport.forceCullForTest()

        assertEquals(
            PathState.ACTIVE,
            Transport.pathTable[destHash.toKey()]?.state,
            "a boundary interface must be spared",
        )
    }

    /**
     * The mark is gated on being a transport node. A leaf node reaches the same failure
     * through [Transport.deregisterLink], which expires the path instead of marking it;
     * these are the two separate arms of the reference's handling.
     */
    @Test
    fun `a leaf node does not mark, because that arm is transport-only`() {
        Transport.start(identity, enableTransport = false)
        val iface = FakeInterface("Leaf", InterfaceMode.FULL)
        Transport.registerInterface(iface)

        val destHash = destinationHash()
        seedPath(destHash, iface, hops = 1)
        seedStalePendingLink(destHash, iface, takenHops = 1)

        Transport.forceCullForTest()

        assertEquals(
            PathState.ACTIVE,
            Transport.pathTable[destHash.toKey()]?.state,
            "marking is gated on transport being enabled",
        )
    }

    /**
     * A link request relayed from further away says nothing about our own path being the
     * problem, so the reference rediscovers without marking.
     */
    @Test
    fun `a multi-hop failure rediscovers without marking`() {
        Transport.start(identity, enableTransport = true)
        val iface = FakeInterface("Far", InterfaceMode.FULL)
        Transport.registerInterface(iface)

        val destHash = destinationHash()
        seedPath(destHash, iface, hops = 4)
        seedStalePendingLink(destHash, iface, takenHops = 3)

        Transport.forceCullForTest()

        assertEquals(
            PathState.ACTIVE,
            Transport.pathTable[destHash.toKey()]?.state,
            "neither the destination nor the initiator was one hop away",
        )
    }

    /** The stale entry is still removed, whichever arm ran. */
    @Test
    fun `the stale link entry is culled regardless`() {
        Transport.start(identity, enableTransport = true)
        val iface = FakeInterface("Culled", InterfaceMode.FULL)
        Transport.registerInterface(iface)

        val destHash = destinationHash()
        seedPath(destHash, iface, hops = 1)
        seedStalePendingLink(destHash, iface, takenHops = 1)

        Transport.forceCullForTest()

        assertTrue(Transport.linkTable.isEmpty(), "the timed-out link entry must be removed")
    }

    /** Same minimal shape the other transport tests use for an InterfaceRef. */
    private class FakeInterface(
        override val name: String,
        override val mode: InterfaceMode,
    ) : InterfaceRef {
        override val hash: ByteArray = name.toByteArray().copyOf(16)
        override val canSend = true
        override val canReceive = true
        override val online = true
        override val bitrate = 1_000_000
        override val hwMtu = 500
        override var tunnelId: ByteArray? = null
        override var wantsTunnel = false
        override fun send(data: ByteArray) = Unit
    }
}
