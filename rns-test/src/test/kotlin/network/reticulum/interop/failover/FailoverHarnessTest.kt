package network.reticulum.interop.failover

import network.reticulum.common.InterfaceMode
import network.reticulum.interop.failover.FailoverHarness.Config
import network.reticulum.interop.failover.FailoverHarness.DeathMode
import network.reticulum.interop.failover.FailoverHarness.Leg
import network.reticulum.interop.failover.FailoverHarness.Mode
import network.reticulum.interop.failover.FailoverHarness.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The two-interfaces-one-peer rig, exercised.
 *
 * Discovery is testable today and is asserted. Failover is not built, so those cases assert
 * that the harness *says so* rather than asserting a failover that cannot happen — the
 * point of running them now is that the day B.3 lands, these turn from
 * [Outcome.NotImplemented] or [Outcome.NoFailover] into [Outcome.FailedOver] with no test
 * changes, and the ones that do not turn are the ones worth arguing about.
 *
 * Timeouts are generous because the failover cases deliberately wait out a detection budget.
 */
@DisplayName("Failover harness")
class FailoverHarnessTest {

    // --- discovery: meaningful now ---------------------------------------------------------

    @Test
    @Timeout(60)
    fun `the node learns both ways to a peer that announces over two interfaces`() {
        val outcome = FailoverHarness(Config(mode = Mode.DISCOVERY)).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertEquals(
            "Tor",
            outcome.selected,
            "the reference takes the NEWEST announce whatever its hops, so the last leg " +
                "to announce holds the path",
        )
        assertEquals(
            listOf("LAN"),
            outcome.alternates,
            "and the displaced leg must be kept, which is the whole point",
        )
    }

    /**
     * With the announces a timebase tick apart, the better leg wins whichever arrives first.
     */
    @Test
    @Timeout(60)
    fun `across a timebase tick, the newest announce holds the path and the old one is kept`() {
        val outcome = FailoverHarness(
            Config(mode = Mode.DISCOVERY, announceOrder = listOf("Tor", "LAN")),
        ).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertEquals("LAN", outcome.selected, "LAN announced last, so LAN holds the path")
        assertEquals(listOf("Tor"), outcome.alternates, "and Tor is retained as the alternate")
    }

    /**
     * Inside one second, arrival order wins and hop count does not — found by this harness
     * rather than reasoned out, and worth pinning because it is counter-intuitive and real.
     *
     * A path is replaced only by an announce with a strictly newer timebase, and that
     * applies even when the newcomer has a better hop count (`Transport.py:1620-1631`). The
     * timebase has seconds resolution, so two announces in the same second compare equal and
     * the second is refused however good it is.
     *
     * This is not hypothetical: a node whose interfaces come up together announces on all of
     * them at once, and then whichever leg happens to arrive first is the selected route
     * until something re-announces. Failover selection has to cope with having been handed
     * the worse leg to begin with.
     */
    @Test
    @Timeout(60)
    fun `inside one timebase second, arrival order decides and hop count does not`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.DISCOVERY,
                announceOrder = listOf("Tor", "LAN"),
                announceGapMs = 50, // same second on purpose
            ),
        ).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertEquals(
            "Tor",
            outcome.selected,
            "the three-hop leg arrived first and keeps the path: a better hop count is not " +
                "enough without a newer timebase",
        )
        assertEquals(
            listOf("LAN"),
            outcome.alternates,
            "and the better leg becomes the alternate, which is what makes it recoverable",
        )
    }

    @Test
    @Timeout(90)
    fun `a third way through is learned too`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.DISCOVERY,
                legs = listOf(
                    Leg("LAN", hops = 1),
                    Leg("Tor", hops = 3),
                    Leg("Radio", hops = 2),
                ),
            ),
        ).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertEquals("Radio", outcome.selected, "the last leg to announce holds the path")
        assertEquals(
            setOf("Tor", "LAN"),
            outcome.alternates.toSet(),
            "every distinct way through should be held, not just the runner-up",
        )
    }

    /**
     * One interface is one way through. Two announces over it are the same route and must
     * not look like redundancy the node does not have.
     */
    @Test
    @Timeout(60)
    fun `one interface announcing twice is not two ways through`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.DISCOVERY,
                legs = listOf(Leg("LAN", hops = 1)),
                announceOrder = listOf("LAN", "LAN"),
            ),
        ).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertTrue(
            outcome.alternates.isEmpty(),
            "a single interface must not produce a false alternate: ${outcome.alternates}",
        )
    }

    // --- failover: not built yet, and the harness should say so ----------------------------

    /**
     * The operator's case. Today this cannot succeed, and the value of running it is that
     * the reason is stated rather than inferred from a red assertion.
     *
     * When B.3 and A.2 land, this becomes the acceptance test for the whole feature with no
     * edit beyond deleting the not-yet branch.
     */
    @Test
    @Timeout(120)
    fun `killing the selected interface — the case the feature exists for`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.FAILOVER,
                deathMode = DeathMode.GOES_OFFLINE,
                detectionBudgetMs = 3_000, // short: nothing can succeed yet, do not wait 35 s
            ),
        ).run()

        when (outcome) {
            is Outcome.FailedOver -> {
                assertTrue(outcome.from != outcome.to, "traffic must have moved between legs")
                println("failover took ${outcome.latencyMs} ms onto ${outcome.to}")
            }
            is Outcome.NoFailover -> {
                assertTrue(outcome.alternatesHeld.isNotEmpty(), "an alternate must have been held")
                assertTrue(
                    outcome.why.contains("B.3") || outcome.why.contains("A.2"),
                    "a failure to fail over must name what is missing: ${outcome.why}",
                )
            }
            else -> throw AssertionError("unexpected outcome: $outcome")
        }
    }

    /**
     * The case no edge can see: the interface still says it is online and still takes
     * writes, and the frames go nowhere. Expected to defeat A.3 when it lands and to need
     * the hedge. Recorded now so the day it starts passing is visible.
     */
    @Test
    @Timeout(120)
    fun `a silently blackholed interface is the hard case`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.FAILOVER,
                deathMode = DeathMode.SILENT_BLACKHOLE,
                detectionBudgetMs = 3_000,
            ),
        ).run()

        assertTrue(
            outcome is Outcome.NoFailover || outcome is Outcome.FailedOver,
            "expected a verdict either way, got $outcome",
        )
    }

    @Test
    @Timeout(120)
    fun `a deregistered interface is a harsher death than going offline`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.FAILOVER,
                deathMode = DeathMode.DEREGISTERED,
                detectionBudgetMs = 3_000,
            ),
        ).run()

        assertTrue(
            outcome is Outcome.NoFailover || outcome is Outcome.FailedOver,
            "expected a verdict either way, got $outcome",
        )
    }

    /**
     * Marking is gated on the receiving interface not being a boundary, so a boundary leg
     * must not be treated as a failover candidate in the way a full one is. Pinned now
     * because the gate is easy to lose when selection is written.
     */
    @Test
    @Timeout(60)
    fun `a boundary interface still registers as a way through`() {
        val outcome = FailoverHarness(
            Config(
                mode = Mode.DISCOVERY,
                legs = listOf(
                    Leg("LAN", hops = 1),
                    Leg("Boundary", hops = 2, mode = InterfaceMode.BOUNDARY),
                ),
            ),
        ).run()

        assertTrue(outcome is Outcome.Discovered, "expected discovery, got $outcome")
        outcome as Outcome.Discovered
        assertEquals(listOf("LAN"), outcome.alternates, "Boundary announced last and holds the path")
    }
}
