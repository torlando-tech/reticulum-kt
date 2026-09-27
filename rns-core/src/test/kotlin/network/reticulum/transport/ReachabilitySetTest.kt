package network.reticulum.transport

import network.reticulum.common.InterfaceMode
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import network.reticulum.identity.Identity
import java.nio.file.Path

/**
 * The reachability set: the rows the reference throws away.
 *
 * The reference keeps one path per destination and discards the loser of every comparison
 * (`Transport.py:1620-1681`), so a node reachable two ways forgets one and has nothing to
 * fall back to. Phase B.2 keeps the loser as an alternate when it arrived on a different
 * interface.
 *
 * What these tests must show, in this order of importance:
 *  - the selected row, and therefore everything on the wire, is unchanged. This is still a
 *    faithful port; the set is observation only until B.3 selects from it.
 *  - alternates appear only when they are genuinely an alternate way through.
 *  - the set is bounded by something the node controls, not by anything a peer sends.
 *
 * Rows are seeded directly rather than driven through announce processing: the invariants
 * here are about the container, and an announce path would test the announce path.
 */
class ReachabilitySetTest {

    private lateinit var identity: Identity

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        try { Transport.stop() } catch (e: Exception) { /* not running */ }
        Transport.setStoragePath(tempDir.toString())
        identity = Identity.create()
        Transport.start(identity, enableTransport = true)
    }

    @AfterEach
    fun teardown() {
        try { Transport.stop() } catch (e: Exception) { /* already down */ }
    }

    private fun iface(name: String): InterfaceRef =
        FakeInterface(name).also { Transport.registerInterface(it) }

    private fun entry(
        on: InterfaceRef,
        hops: Int,
        nextHop: Byte = 0x22,
        ageMs: Long = 0,
    ) = PathEntry(
        timestamp = System.currentTimeMillis() - ageMs,
        nextHop = ByteArray(16) { nextHop },
        hops = hops,
        expires = System.currentTimeMillis() + 600_000,
        randomBlobs = mutableListOf(ByteArray(10) { 0x55 }),
        receivingInterfaceHash = on.hash,
        announcePacketHash = ByteArray(16) { 0x33 },
    )

    private fun seedSelected(dest: ByteArray, on: InterfaceRef, hops: Int = 1) {
        Transport.pathTable[dest.toKey()] = entry(on, hops)
    }

    private fun dest(tag: Byte) = ByteArray(16) { tag }

    // --- the property that matters most: nothing about routing moved --------------------

    @Test
    fun `an alternate does not become the path`() {
        val d = dest(0x01)
        val lan = iface("LAN")
        val tor = iface("Tor")
        seedSelected(d, lan, hops = 1)

        Transport.recordAlternateForTest(d, entry(tor, hops = 3))

        val selected = Transport.pathTable[d.toKey()]
        assertNotNull(selected)
        assertTrue(
            selected!!.receivingInterfaceHash.contentEquals(lan.hash),
            "the selected row must still be the one selection chose",
        )
        assertEquals(1, selected.hops, "and its hop count must not move")
        assertEquals(1, Transport.hopsTo(d), "the public accessor answers from the selected row")
    }

    // --- when a row is, and is not, an alternate ----------------------------------------

    @Test
    fun `a second interface to the same destination is kept`() {
        val d = dest(0x02)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))

        assertEquals(1, Transport.alternatePathCountForTest(d))
        val set = Transport.pathReachability(d)
        assertNotNull(set)
        assertEquals(2, set!!.rows.size)
        assertTrue(set.selected.selected, "the first row is the selected one")
        assertEquals("Tor", set.rows[1].interfaceName)
    }

    @Test
    fun `a second announce over the same interface is not an alternate`() {
        val d = dest(0x03)
        val lan = iface("LAN")
        seedSelected(d, lan)

        Transport.recordAlternateForTest(d, entry(lan, hops = 4, nextHop = 0x77))

        assertEquals(
            0,
            Transport.alternatePathCountForTest(d),
            "one interface is one way through; a second row on it fails at the same moment",
        )
    }

    @Test
    fun `a row on an unregistered interface is not kept`() {
        val d = dest(0x04)
        seedSelected(d, iface("LAN"))
        val ghost = FakeInterface("Ghost") // never registered

        Transport.recordAlternateForTest(d, entry(ghost, hops = 2))

        assertEquals(0, Transport.alternatePathCountForTest(d))
    }

    @Test
    fun `an alternate for a destination with no path is not kept`() {
        val d = dest(0x05)
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 2))
        assertEquals(0, Transport.alternatePathCountForTest(d))
        assertNull(Transport.pathReachability(d), "no path, no set")
    }

    // --- bounds -------------------------------------------------------------------------

    @Test
    fun `one row per interface, replaced rather than accumulated`() {
        val d = dest(0x06)
        seedSelected(d, iface("LAN"))
        val tor = iface("Tor")

        Transport.recordAlternateForTest(d, entry(tor, hops = 5))
        Transport.recordAlternateForTest(d, entry(tor, hops = 2))

        assertEquals(1, Transport.alternatePathCountForTest(d), "still one row for that interface")
        assertEquals(2, Transport.pathReachability(d)!!.rows[1].hops, "and it is the newer one")
    }

    @Test
    fun `the set is capped`() {
        val d = dest(0x07)
        seedSelected(d, iface("LAN"))
        repeat(TransportConstants.MAX_ALTERNATE_ROWS + 3) { i ->
            Transport.recordAlternateForTest(d, entry(iface("Extra$i"), hops = 2 + i))
        }
        assertEquals(
            TransportConstants.MAX_ALTERNATE_ROWS,
            Transport.alternatePathCountForTest(d),
        )
    }

    // --- lifecycle ----------------------------------------------------------------------

    @Test
    fun `expiring a path takes its alternates with it`() {
        val d = dest(0x08)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))
        assertEquals(1, Transport.alternatePathCountForTest(d))

        Transport.expirePath(d)

        assertEquals(0, Transport.alternatePathCountForTest(d), "no alternate outlives its path")
        assertNull(Transport.pathReachability(d))
    }

    @Test
    fun `the snapshot reports interface liveness for the UI that consumes it`() {
        val d = dest(0x09)
        seedSelected(d, iface("LAN"))
        val tor = FakeInterface("Tor", onlineNow = false).also { Transport.registerInterface(it) }
        Transport.recordAlternateForTest(d, entry(tor, hops = 3))

        val set = Transport.pathReachability(d)!!
        assertTrue(set.rows[0].interfaceOnline, "LAN is up")
        assertTrue(!set.rows[1].interfaceOnline, "Tor is down")
        assertTrue(set.usableAlternates.isEmpty(), "an offline alternate is not usable")
    }

    // --- failCurrentPath: reference behaviour until there is somewhere else to go ------

    /**
     * The case that exists today, and the one that must not regress. The reference drops
     * the path and rediscovers (`LXMRouter.py:2746`), because with one way through that is
     * the only way forward. Marking instead would leave a known-bad path in use while the
     * failure counter climbed to three.
     */
    @Test
    fun `with no alternate the path is dropped, as the reference drops it`() {
        val d = dest(0x0A)
        seedSelected(d, iface("LAN"))

        Transport.failCurrentPath(d)

        assertNull(Transport.pathTable[d.toKey()], "the path is gone, not degraded")
        assertNull(Transport.pathReachability(d))
    }

    /**
     * The divergence, and the whole point of the set: an alternate is a reason not to throw
     * the destination away.
     */
    @Test
    fun `with a usable alternate the row is marked and the destination kept`() {
        val d = dest(0x0B)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))

        Transport.failCurrentPath(d)

        val still = Transport.pathTable[d.toKey()]
        assertNotNull(still, "the destination must survive: there is another way to it")
        assertEquals(PathState.UNRESPONSIVE, still!!.state)
        assertEquals(1, still.failureCount)
        assertEquals(1, Transport.alternatePathCountForTest(d), "the alternate is untouched")
    }

    /**
     * An alternate on a dead interface is not somewhere else to go, so the reference branch
     * must still be taken. This is the case that would quietly strand a node otherwise.
     */
    @Test
    fun `an offline alternate does not count as somewhere to go`() {
        val d = dest(0x0C)
        seedSelected(d, iface("LAN"))
        val dead = FakeInterface("Dead", onlineNow = false).also { Transport.registerInterface(it) }
        Transport.recordAlternateForTest(d, entry(dead, hops = 3))

        Transport.failCurrentPath(d)

        assertNull(
            Transport.pathTable[d.toKey()],
            "an unusable alternate is no alternate; drop as the reference does",
        )
    }

    @Test
    fun `reporting a failure for an unknown destination does nothing`() {
        Transport.failCurrentPath(dest(0x0D))  // must not throw
        assertNull(Transport.pathReachability(dest(0x0D)))
    }
    // --- no alternate outlives its destination, at EVERY drop site ---------------------
    //
    // Holding the alternates beside the path table rather than inside it avoids a great
    // deal of churn, and costs exactly this: two structures the type system cannot keep in
    // step. The failure is silent — rows for a destination that no longer has a path,
    // naming an interface that no longer resolves, invisible until something reads the
    // snapshot. It is a leak now and it would be a ROUTE once selection lands, so every
    // site that drops a destination is pinned here, not just the obvious one.

    @Test
    fun `the cull drops alternates whose destination lost its path`() {
        val d = dest(0x10)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))

        // The path goes without passing through expirePath, as an eviction elsewhere would.
        Transport.pathTable.remove(d.toKey())
        Transport.forceCullForTest()

        assertEquals(0, Transport.alternatePathCountForTest(d), "orphaned rows must not survive a cull")
    }

    @Test
    fun `the cull drops an alternate whose interface has gone`() {
        val d = dest(0x11)
        seedSelected(d, iface("LAN"))
        val tor = iface("Tor")
        Transport.recordAlternateForTest(d, entry(tor, hops = 3))
        assertEquals(1, Transport.alternatePathCountForTest(d))

        Transport.deregisterInterface(tor)
        Transport.forceCullForTest()

        assertEquals(
            0,
            Transport.alternatePathCountForTest(d),
            "a row naming an interface that no longer resolves is not a route",
        )
        assertNotNull(Transport.pathTable[d.toKey()], "and the selected row is untouched")
    }

    @Test
    fun `the cull drops an expired alternate`() {
        val d = dest(0x12)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(
            d,
            entry(iface("Tor"), hops = 3).copy(expires = System.currentTimeMillis() - 1_000),
        )

        Transport.forceCullForTest()

        assertEquals(0, Transport.alternatePathCountForTest(d), "an expired row is not a route")
    }

    /**
     * Blackholing removes a destination outside the cull, so it prunes on its own path
     * rather than waiting. Driven properly: the destination has to be recallable to the
     * blackholed identity or nothing is dropped and the test proves nothing, which is how
     * the first version of it passed for the wrong reason.
     */
    @Test
    fun `blackholing a destination takes its alternates`() {
        val peer = Identity.create()
        val d = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x14 }
        Identity.remember(
            ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0x09 },
            d,
            peer.getPublicKey(),
        )
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))
        assertEquals(1, Transport.alternatePathCountForTest(d), "precondition: a row is held")

        Transport.blackholeIdentity(peer.hash)

        assertNull(Transport.pathTable[d.toKey()], "precondition: the destination was dropped")
        assertEquals(
            0,
            Transport.alternatePathCountForTest(d),
            "a destination removed for blackholing must not keep rows pointing at it",
        )
    }

    @Test
    fun `stopping clears the whole set`() {
        val d = dest(0x13)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))
        assertEquals(1, Transport.alternatePathCountForTest(d))

        Transport.stop()

        assertEquals(0, Transport.alternatePathCountForTest(d), "nothing survives a stop")
    }

    // --- the coverage canary -----------------------------------------------------------
    //
    // Nothing selects from the set yet, so the only way to know the mechanism is alive on
    // a real node is to count what it holds. These pin the count itself, because a wrong
    // count is worse than none: it would answer the question of whether failover has
    // anything to fall back on, and answer it wrongly.

    @Test
    fun `a node with no alternates reports none`() {
        val d = dest(0x20)
        seedSelected(d, iface("LAN"))

        val stats = Transport.alternatePathStats()
        assertEquals(0, stats.destinationsWithAlternate)
        assertEquals(0, stats.alternateRows, "a selected row is not an alternate")
    }

    @Test
    fun `the count follows destinations and rows separately`() {
        val lan = iface("LAN")
        val tor = iface("Tor")
        val kiss = iface("KISS")

        val one = dest(0x21)
        seedSelected(one, lan)
        Transport.recordAlternateForTest(one, entry(tor, hops = 3))
        Transport.recordAlternateForTest(one, entry(kiss, hops = 5))

        val two = dest(0x22)
        seedSelected(two, lan)
        Transport.recordAlternateForTest(two, entry(tor, hops = 2))

        // A destination with a path and no second way through counts for neither.
        seedSelected(dest(0x23), lan)

        val stats = Transport.alternatePathStats()
        assertEquals(2, stats.destinationsWithAlternate, "two destinations have a second way")
        assertEquals(3, stats.alternateRows, "carrying three alternate rows between them")
    }

    @Test
    fun `a replaced row does not inflate the count`() {
        val d = dest(0x24)
        val tor = iface("Tor")
        seedSelected(d, iface("LAN"))

        Transport.recordAlternateForTest(d, entry(tor, hops = 3))
        Transport.recordAlternateForTest(d, entry(tor, hops = 2))

        val stats = Transport.alternatePathStats()
        assertEquals(1, stats.destinationsWithAlternate)
        assertEquals(
            1,
            stats.alternateRows,
            "a fresher announce over the same interface replaces the row, it does not add one",
        )
    }

    @Test
    fun `the count returns to zero when the alternates are pruned away`() {
        val d = dest(0x25)
        seedSelected(d, iface("LAN"))
        Transport.recordAlternateForTest(d, entry(iface("Tor"), hops = 3))
        assertEquals(1, Transport.alternatePathStats().destinationsWithAlternate)

        // Losing the destination's path takes its alternates with it.
        Transport.expirePath(d)

        assertEquals(
            0,
            Transport.alternatePathStats().destinationsWithAlternate,
            "learned-then-pruned reads zero, which is why the count alone cannot " +
                "distinguish it from never-learned",
        )
    }
    private class FakeInterface(
        override val name: String,
        private val onlineNow: Boolean = true,
    ) : InterfaceRef {
        override val hash: ByteArray = name.toByteArray().copyOf(16)
        override val canSend = true
        override val canReceive = true
        override val online get() = onlineNow
        override val mode = InterfaceMode.FULL
        override val bitrate = 1_000_000
        override val hwMtu = 500
        override var tunnelId: ByteArray? = null
        override var wantsTunnel = false
        override fun send(data: ByteArray) = Unit
    }
}
