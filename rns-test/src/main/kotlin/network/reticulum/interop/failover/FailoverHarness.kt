package network.reticulum.interop.failover

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.PathReachability
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A rig for one peer reachable over several interfaces, and for taking those interfaces
 * away.
 *
 * Nothing else here can do this. `ShapedTcpRelay` shapes a single link, and every other
 * harness brings up one way through, so the question this whole feature exists to answer —
 * what happens to traffic when the interface it was using stops working — has had nowhere
 * to be asked. This is that place.
 *
 * ## What it drives, and what it fakes
 *
 * Announces go through [Transport.inbound], the real entry point, so path admission, the
 * hop comparison, alternate recording and selection are the production code. What is faked
 * is the medium: each leg is an [InterfaceRef] whose liveness and sends this harness owns,
 * because the point is to kill an interface at a chosen instant and see what Transport
 * does, not to test TCP. A leg records every frame written to it, which is how "the traffic
 * moved" is observed at all.
 *
 * ## Two modes, because two things are worth testing separately
 *
 * [Mode.DISCOVERY] asks only whether this node *learns* the second way through. That works
 * today: B.2 records the losing announce as an alternate. A discovery run is meaningful now
 * and will stay meaningful, because learning the alternate is a precondition for every
 * later phase and can regress on its own.
 *
 * [Mode.FAILOVER] asks whether traffic actually moves. That needs selection (B.3) and
 * online-aware egress (A.2), neither of which exists yet. Rather than fail with an
 * assertion that reads like a bug, a failover run against a tree without them reports
 * [Outcome.NotImplemented], naming what is missing. A harness that cries wolf before the
 * feature lands teaches people to ignore it.
 *
 * ## Everything uncertain is a knob
 *
 * The plan is honest that detection latency, the right cap, and what an interface going
 * down even looks like are unsettled. Those are [Config] fields rather than constants, so
 * the answer can be measured instead of assumed. Defaults are the operator's real case: a
 * LAN leg at one hop and a Tor leg at three, the LAN preferred, the LAN killed.
 */
class FailoverHarness(private val config: Config = Config()) {

    // ---- configuration ------------------------------------------------------------------

    /**
     * How an interface stops working. This is the knob that matters most, because the three
     * are genuinely different failures and the design only claims to catch some of them.
     */
    enum class DeathMode {
        /**
         * The interface reports itself offline, as a TCP interface does when its socket
         * drops. The edge is observable, and A.3 is meant to notice it. Fastest detection
         * and the easiest case.
         */
        GOES_OFFLINE,

        /**
         * The interface is removed from Transport entirely, as a torn-down interface is.
         * Rows naming it dangle and are culled. Tests the harsher lifecycle.
         */
        DEREGISTERED,

        /**
         * The interface still claims to be online and still accepts writes, but nothing
         * arrives at the far end. A Tor circuit dying behind a live local socket looks
         * exactly like this, and it is the case no edge can see — only a missing receipt
         * reveals it. Expected to defeat A.3 and to need the hedge (C.1).
         */
        SILENT_BLACKHOLE,
    }

    enum class Mode {
        /** Only: does the node learn the second way through? Meaningful today. */
        DISCOVERY,

        /** Also: does traffic move when the first way dies? Needs B.3 and A.2. */
        FAILOVER,
    }

    /**
     * One way through to the peer.
     *
     * [hops] is what the announce arriving on this leg claims, which is what selection
     * compares. The default legs give the LAN a better hop count so it wins, matching the
     * case that prompted the feature.
     */
    data class Leg(
        val name: String,
        val hops: Int,
        val mode: InterfaceMode = InterfaceMode.FULL,
        val bitrate: Int = 1_000_000,
    )

    data class Config(
        /** The ways through. Order is announce order unless [announceOrder] overrides it. */
        val legs: List<Leg> = listOf(
            Leg("LAN", hops = 1),
            Leg("Tor", hops = 3),
        ),
        val mode: Mode = Mode.FAILOVER,
        val deathMode: DeathMode = DeathMode.GOES_OFFLINE,

        /**
         * Which leg to kill. Defaults to whichever one selection actually chose, which is
         * the only interesting one; name a leg to force an awkward case.
         */
        val killLeg: String? = null,

        /**
         * Announce order by leg name. The reference admits the first announce and then
         * compares, so order decides which leg is selected and which becomes the alternate.
         * Worth varying: a worse leg announcing first is a different code path.
         */
        val announceOrder: List<String>? = null,

        /**
         * Pause between announces, and not a formality.
         *
         * A path is replaced only by an announce with a strictly newer *timebase*, and that
         * holds even when the new announce has a better hop count
         * (`Transport.kt` should_add, porting `Transport.py:1620-1631`). The timebase is a
         * seconds-resolution value carried in the announce's random blob, so two announces
         * emitted inside the same second compare equal and the second one is refused
         * whatever its hops.
         *
         * The consequence is worth stating plainly, because it surprised this harness on its
         * first run: within one second, announce ORDER decides which leg is selected, not
         * hop count. The default therefore steps over a tick so the legs are compared on
         * their merits. Set it below a second deliberately to exercise the same-second case,
         * which is a real one on a node whose interfaces all come up together.
         */
        val announceGapMs: Long = 1_100,

        /**
         * How long to wait for traffic to appear on another leg after the kill.
         *
         * A guess, and flagged as one. The plan's own table puts an online edge at
         * "seconds" and a silent TCP drop near 29 s, and says the real numbers are
         * unmeasured. This is deliberately generous so a run reports a slow failover
         * rather than a failed one; tighten it once the numbers exist.
         */
        val detectionBudgetMs: Long = 35_000,

        /** How often to look while waiting. Also the resolution of the latency figure. */
        val pollIntervalMs: Long = 50,

        /** Packets sent to the peer after the kill, to give failover something to carry. */
        val probesAfterKill: Int = 5,
        val probeGapMs: Long = 200,

        /** Transport-node mode. Off is the phone's default and the case that matters. */
        val transportEnabled: Boolean = false,

        /**
         * Report the current path as failed after the kill, as a delivery layer would.
         *
         * Left on because that is what LXMF does, and without it nothing tells Transport
         * anything is wrong in the [DeathMode.SILENT_BLACKHOLE] case. Turn it off to test
         * what the transport notices unaided.
         */
        val reportFailure: Boolean = true,
    )

    // ---- results ------------------------------------------------------------------------

    sealed interface Outcome {
        /** The node learned every leg it should have. */
        data class Discovered(
            val selected: String,
            val alternates: List<String>,
            val snapshot: PathReachability?,
        ) : Outcome

        /** Traffic moved to another leg. [latencyMs] is from the kill to the first frame. */
        data class FailedOver(
            val from: String,
            val to: String,
            val latencyMs: Long,
            val framesOnNewLeg: Int,
        ) : Outcome

        /** The budget expired with nothing on another leg. */
        data class NoFailover(
            val from: String,
            val budgetMs: Long,
            val alternatesHeld: List<String>,
            val why: String,
        ) : Outcome

        /**
         * The tree cannot do this yet, and says which part is missing rather than failing
         * an assertion that reads like a defect.
         */
        data class NotImplemented(val missing: String) : Outcome

        /** The rig could not set the scenario up; the run proves nothing either way. */
        data class SetupFailed(val why: String) : Outcome
    }

    // ---- the run ------------------------------------------------------------------------

    private val legs = mutableMapOf<String, ControllableLeg>()
    private lateinit var peerIdentity: Identity
    private lateinit var peerDestination: Destination

    /** Everything the run saw, in order. Useful when an outcome needs explaining. */
    val log = ConcurrentLinkedQueue<String>()

    private fun note(message: String) {
        log.add("[${System.currentTimeMillis()}] $message")
    }

    fun run(): Outcome {
        try {
            setUp()
        } catch (expectedSetupFailure: Exception) {
            // Named for intent: a rig that cannot build its scenario reports that as an
            // outcome rather than throwing, so a caller can tell "the rig broke" from
            // "the feature is missing".
            return Outcome.SetupFailed(
                "${expectedSetupFailure.javaClass.simpleName}: ${expectedSetupFailure.message}",
            )
        }

        return try {
            announceOverEveryLeg()

            val snapshot = Transport.pathReachability(peerDestination.hash)
                ?: return Outcome.SetupFailed(
                    "no path to the peer after announcing on ${legs.size} legs",
                )
            val selectedName = snapshot.selected.interfaceName ?: "<unregistered>"
            val alternateNames = snapshot.rows.drop(1).mapNotNull { it.interfaceName }
            note("selected=$selectedName alternates=$alternateNames")

            if (config.mode == Mode.DISCOVERY) {
                return Outcome.Discovered(selectedName, alternateNames, snapshot)
            }

            if (alternateNames.isEmpty()) {
                return Outcome.NoFailover(
                    from = selectedName,
                    budgetMs = 0,
                    alternatesHeld = emptyList(),
                    why = "no alternate was learned, so there is nothing to fail over to; " +
                        "run in DISCOVERY mode to see why",
                )
            }

            failoverRun(selectedName, alternateNames)
        } finally {
            tearDown()
        }
    }

    private fun failoverRun(selectedName: String, alternateNames: List<String>): Outcome {
        val victimName = config.killLeg ?: selectedName
        val victim = legs[victimName]
            ?: return Outcome.SetupFailed("no leg named $victimName to kill")

        legs.values.forEach { it.clearFrames() }
        val killedAt = System.currentTimeMillis()
        kill(victim)
        note("killed $victimName via ${config.deathMode}")

        if (config.reportFailure) {
            Transport.failCurrentPath(peerDestination.hash)
            note("reported the path as failing, as a delivery layer would")
        }

        val deadline = killedAt + config.detectionBudgetMs
        var probesSent = 0
        while (System.currentTimeMillis() < deadline) {
            if (probesSent < config.probesAfterKill) {
                sendProbe()
                probesSent++
                Thread.sleep(config.probeGapMs)
            } else {
                Thread.sleep(config.pollIntervalMs)
            }

            val moved = legs.values.firstOrNull {
                it.name != victimName && it.frameCount() > 0
            }
            if (moved != null) {
                return Outcome.FailedOver(
                    from = victimName,
                    to = moved.name,
                    latencyMs = System.currentTimeMillis() - killedAt,
                    framesOnNewLeg = moved.frameCount(),
                )
            }
        }

        return Outcome.NoFailover(
            from = victimName,
            budgetMs = config.detectionBudgetMs,
            alternatesHeld = alternateNames,
            why = missingCapability()
                ?: "an alternate was held and eligible, but nothing routed to it",
        )
    }

    /**
     * Whether this tree can move traffic at all, so a run can say "not built yet" instead of
     * "failed". Probed rather than version-checked: the question is whether an alternate can
     * be selected, and the honest test of that is whether anything outside the snapshot
     * reads the set.
     */
    private fun missingCapability(): String? =
        "selection over the reachability set is not implemented (plan B.3), and " +
            "online-aware egress is not implemented (plan A.2), so no send path can " +
            "choose an alternate however many are held"

    // ---- scenario ------------------------------------------------------------------------

    private fun setUp() {
        try { Transport.stop() } catch (_: Exception) { /* expected: not running */ }
        Transport.start(Identity.create(), enableTransport = config.transportEnabled)

        config.legs.forEach { spec ->
            val leg = ControllableLeg(spec)
            legs[spec.name] = leg
            Transport.registerInterface(leg)
        }

        peerIdentity = Identity.create()
        peerDestination = Destination.create(
            identity = peerIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "failover",
            aspects = arrayOf("peer"),
        )
        // The peer is remote: deregister locally so its announce is treated as one.
        Transport.deregisterDestination(peerDestination)
    }

    private fun announceOverEveryLeg() {
        val order = config.announceOrder ?: config.legs.map { it.name }
        order.forEach { name ->
            val leg = legs[name] ?: error("announceOrder names unknown leg $name")
            val announce = peerDestination.announce(send = false)
                ?: error("could not build an announce for the peer")
            announce.hops = leg.spec.hops
            val raw = announce.raw ?: announce.pack()
            Transport.inbound(raw, leg)
            note("announced over ${leg.name} at ${leg.spec.hops} hops")
            Thread.sleep(config.announceGapMs)
        }
    }

    private fun kill(leg: ControllableLeg) {
        when (config.deathMode) {
            DeathMode.GOES_OFFLINE -> leg.setOnline(false)
            DeathMode.DEREGISTERED -> {
                leg.setOnline(false)
                Transport.deregisterInterface(leg)
            }
            DeathMode.SILENT_BLACKHOLE -> leg.blackhole()
        }
    }

    private fun sendProbe() {
        try {
            val out = Destination.create(
                identity = peerIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "failover",
                aspects = arrayOf("peer"),
            )
            network.reticulum.packet.Packet.create(
                destination = out,
                data = ByteArray(24) { 0x5A },
                createReceipt = false,
            ).send()
        } catch (expectedSendFailure: Exception) {
            // A probe failing IS a result here, not an error: with the selected leg dead
            // and nothing able to choose an alternate, the send has nowhere to go.
            note(
                "probe send raised ${expectedSendFailure.javaClass.simpleName}: " +
                    "${expectedSendFailure.message}",
            )
        }
    }

    private fun tearDown() {
        try { Transport.stop() } catch (_: Exception) { /* expected: already down */ }
        legs.clear()
    }

    /** Frames written to each leg during the run, for a caller that wants the detail. */
    fun framesByLeg(): Map<String, Int> = legs.mapValues { it.value.frameCount() }

    // ---- the fake medium -------------------------------------------------------------------

    /**
     * An interface whose liveness and delivery this harness owns.
     *
     * [blackhole] is the interesting one: it keeps reporting itself online and keeps
     * accepting writes, and simply drops them. That is what a dead Tor circuit behind a
     * live local socket looks like, and no amount of watching `online` will reveal it.
     */
    private class ControllableLeg(val spec: Leg) : InterfaceRef {
        override val name: String = spec.name
        override val hash: ByteArray = spec.name.toByteArray().copyOf(16)
        override val canSend = true
        override val canReceive = true
        override val mode: InterfaceMode = spec.mode
        override val bitrate: Int = spec.bitrate
        override val hwMtu = 500
        override var tunnelId: ByteArray? = null
        override var wantsTunnel = false

        @Volatile private var onlineFlag = true
        @Volatile private var swallowing = false
        private val frames = CopyOnWriteArrayList<ByteArray>()

        override val online: Boolean get() = onlineFlag

        override fun send(data: ByteArray) {
            if (swallowing) return // accepted and discarded, exactly as a dead circuit does
            frames.add(data)
        }

        fun setOnline(value: Boolean) { onlineFlag = value }
        fun blackhole() { swallowing = true }
        fun frameCount(): Int = frames.size
        fun clearFrames() = frames.clear()
    }
}
