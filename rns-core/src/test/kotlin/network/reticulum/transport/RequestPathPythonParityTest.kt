package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.storage.PathStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression guard for columba#1004 (PR 1 — reticulum-kt requestPath parity +
 * honest path-table read APIs).
 *
 * Python's `RNS.Transport.request_path` (RNS/Transport.py:2694) sends a path
 * request **unconditionally** — it neither short-circuits when a path already
 * exists nor rate-limits locally-originated requests. The Kotlin port had grown
 * two early-skip guards (`hasPath` and `PATH_REQUEST_MI`) that diverged from
 * this, and they were the reticulum-kt half of #1004: a stale/dangling cached
 * path could permanently suppress the path request DIRECT delivery needs.
 *
 * Separately, the read APIs (`hasPath`/`hopsTo`/`nextHop`) only checked expiry,
 * so a restored path pointing at an interface that no longer exists would still
 * report as usable — masking the need for a fresh request. Python never has such
 * entries because it validates interfaces at load time; Kotlin restores eagerly
 * (a persistence feature) and now validates lazily at the read APIs + cull job.
 *
 * Each test below notes its pre-fix (red) behavior.
 *
 * Uses the inline [CapturingInterface] pattern from
 * [TransportOutboundHeaderTypeTest] — `rns-core`'s test sourceset can't see the
 * conformance-bridge mocks.
 */
@DisplayName("Transport requestPath Python parity + honest path reads (columba#1004)")
class RequestPathPythonParityTest {

    private class CapturingInterface(
        override val name: String,
    ) : InterfaceRef {
        val sent = mutableListOf<ByteArray>()

        override val hash: ByteArray = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xAA.toByte() }
        override val canSend: Boolean = true
        override val canReceive: Boolean = true
        override val online: Boolean = true
        override val mode: InterfaceMode = InterfaceMode.FULL
        override val bitrate: Int = 1_000_000
        override val hwMtu: Int = RnsConstants.MTU

        override var tunnelId: ByteArray? = null
        override var wantsTunnel: Boolean = false

        override fun send(data: ByteArray) {
            sent.add(data.copyOf())
        }
    }

    /** Records every [removePath] call so the cull test can assert exact prunes. */
    private class RecordingPathStore : PathStore {
        val removed = mutableListOf<ByteArray>()

        override fun upsertPath(destHash: ByteArray, entry: PathEntry) = Unit
        override fun removePath(destHash: ByteArray) {
            removed.add(destHash.copyOf())
        }
        override fun loadAllPaths(): Map<ByteArrayKey, PathEntry> = emptyMap()
        override fun removeExpiredBefore(timestampMs: Long) = Unit

        fun removeCountFor(destHash: ByteArray): Int = removed.count { it.contentEquals(destHash) }
    }

    private lateinit var iface: CapturingInterface

    /** The PLAIN `rnstransport/path/request` destination — same construction Transport uses. */
    private val pathRequestDestHash: ByteArray =
        Destination.create(
            identity = null,
            direction = DestinationDirection.IN,
            type = DestinationType.PLAIN,
            appName = TransportConstants.APP_NAME,
            aspects = arrayOf("path", "request"),
        ).hash

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort — a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
        iface = CapturingInterface(name = "capture-${System.nanoTime()}")
        Transport.registerInterface(iface)
    }

    @AfterEach
    fun teardown() {
        try {
            Transport.deregisterInterface(iface)
        } catch (_: Exception) {
            // Best-effort — test 5 deregisters it already.
        }
        Transport.pathStore = null
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /** A live (unexpired) path entry reachable via [receivingIfaceHash]. */
    private fun livePathEntry(receivingIfaceHash: ByteArray, hops: Int = 1): PathEntry {
        val now = System.currentTimeMillis()
        return PathEntry(
            timestamp = now,
            nextHop = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xDD.toByte() },
            hops = hops,
            expires = now + 3_600_000L,
            randomBlobs = mutableListOf(),
            receivingInterfaceHash = receivingIfaceHash,
            announcePacketHash = ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xCC.toByte() },
            state = PathState.ACTIVE,
            failureCount = 0,
        )
    }

    private fun destHash(seed: Int): ByteArray =
        ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { (it + seed).toByte() }

    private val danglingIfaceHash: ByteArray =
        ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { 0xBB.toByte() }

    /** Count captured packets that are path requests for [destHash]. */
    private fun pathRequestCountFor(destHash: ByteArray): Int =
        iface.sent.count { wire ->
            val pkt = Packet.unpack(wire) ?: return@count false
            pkt.destinationHash.contentEquals(pathRequestDestHash) &&
                pkt.data.size >= RnsConstants.TRUNCATED_HASH_BYTES &&
                pkt.data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES).contentEquals(destHash)
        }

    @Test
    @DisplayName("requestPath sends even when a usable path already exists")
    fun requestPathSendsWhenPathExists() {
        val dest = destHash(seed = 1)
        Transport.pathTable[dest.toKey()] = livePathEntry(iface.hash)
        // Precondition: with a live path on a registered interface, hasPath is true.
        Transport.hasPath(dest) shouldBe true

        Transport.requestPath(dest)

        // Pre-fix: the hasPath early-return swallowed this — 0 packets.
        pathRequestCountFor(dest) shouldBe 1
    }

    @Test
    @DisplayName("requestPath sends again immediately for the same destination (no MI throttle)")
    fun requestPathSendsAgainImmediately() {
        val dest = destHash(seed = 2)
        Transport.hasPath(dest) shouldBe false

        Transport.requestPath(dest)
        Transport.requestPath(dest)

        // Pre-fix: the PATH_REQUEST_MI guard throttled the second call — 1 packet.
        pathRequestCountFor(dest) shouldBe 2
    }

    @Test
    @DisplayName("deregisterLink auto-re-request is MI-throttled at the call site")
    fun deregisterLinkReRequestIsThrottled() {
        // Two initiator links to the SAME destination, both timed-out and pending.
        // The throttle that used to live inside requestPath now lives at this call
        // site (Python Transport.py:516); two deregistrations within PATH_REQUEST_MI
        // must yield exactly one path request.
        val dest = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "columbatest",
            aspects = arrayOf("deregister"),
        )
        val link1 = Link.create(dest)
        val link2 = Link.create(dest)

        // Drop the LINKREQUEST packets emitted by Link.create's initializeAsInitiator.
        iface.sent.clear()

        // teardown(TIMEOUT) drives the real path: status -> CLOSED, then
        // Transport.deregisterLink(this), which expires the path and (MI-permitting)
        // re-requests it. The links were never ACTIVE, so no teardown packet is sent.
        link1.teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT) // fires requestPath, records pathRequests[dest]
        link2.teardown(LinkConstants.TEARDOWN_REASON_TIMEOUT) // within MI window -> throttled, no request

        pathRequestCountFor(dest.hash) shouldBe 1
    }

    @Test
    @DisplayName("hasPath/hopsTo/nextHop are false for a dangling entry when interfaces exist (non-destructive)")
    fun danglingEntryIsNotUsableWhenInterfacesExist() {
        val dest = destHash(seed = 3)
        Transport.pathTable[dest.toKey()] = livePathEntry(danglingIfaceHash)

        // iface (0xAA) is registered but the entry references 0xBB which is not.
        Transport.hasPath(dest) shouldBe false
        Transport.hopsTo(dest) shouldBe null
        Transport.nextHop(dest) shouldBe null

        // Non-destructive: the read APIs don't remove the entry (cull does).
        Transport.pathTable.containsKey(dest.toKey()) shouldBe true
    }

    @Test
    @DisplayName("hasPath stays true for a dangling entry when no interfaces are registered yet")
    fun danglingEntryUsableDuringRestoreWindow() {
        val dest = destHash(seed = 4)
        Transport.pathTable[dest.toKey()] = livePathEntry(danglingIfaceHash)

        // Simulate the restore-before-interfaces-register window: no interfaces.
        Transport.deregisterInterface(iface)

        // interfaces.isEmpty() preserves the entry (mirrors savePathTable's guard).
        Transport.hasPath(dest) shouldBe true
    }

    @Test
    @DisplayName("cull job prunes a dangling entry after the grace period and keeps a live one")
    fun cullPrunesDanglingEntryAfterGrace() {
        val recordingStore = RecordingPathStore()
        Transport.pathStore = recordingStore

        val danglingDest = destHash(seed = 5)
        val liveDest = destHash(seed = 6)
        Transport.pathTable[danglingDest.toKey()] = livePathEntry(danglingIfaceHash)
        Transport.pathTable[liveDest.toKey()] = livePathEntry(iface.hash)

        // Move start time back so the startup grace period has elapsed.
        Transport.setStartTimeForTest(
            System.currentTimeMillis() - TransportConstants.STARTUP_GRACE_PERIOD - 1_000L,
        )

        Transport.cullTablesNow()

        Transport.pathTable.containsKey(danglingDest.toKey()) shouldBe false
        Transport.pathTable.containsKey(liveDest.toKey()) shouldBe true
        recordingStore.removeCountFor(danglingDest) shouldBe 1
        recordingStore.removeCountFor(liveDest) shouldBe 0
    }
}
