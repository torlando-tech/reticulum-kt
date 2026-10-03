package network.reticulum.resource

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.RnsConstants
import network.reticulum.common.toKey
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for the two concrete bugs documented in reticulum-kt#65.
 *
 * The perf investigation in that issue is OUT OF SCOPE here; these two tests
 * pin the two real bugs the perf investigation surfaced.
 *
 * Bug 2 (failed-callback registration race): Resource.create()/accept() spawn
 * the watchdog (create() via advertise(); accept() via startWatchdog()) before
 * the application can install callbacks.failed, so a failure that fires in that
 * window is invoked on null (?. no-op) and never propagated up to
 * LXMessage.state. Fixed by a failedCallback parameter that is installed on
 * callbacks BEFORE the watchdog can fire.
 *
 * Bug 1 (sender watchdog has no recovery actions): the pre-fix kotlin watchdog
 * only retried on the receiver side (`if (!initiator) requestNext()`); a sender
 * in ADVERTISED or AWAITING_PROOF simply counted to MAX_RETRIES and cancelled.
 * Python Resource.py:560-670 re-sends the advertisement on an ADVERTISED timeout
 * and queries the proof from the network cache (Transport.cache_request) on an
 * AWAITING_PROOF timeout, only cancelling on a TRANSFERRING timeout. These tests
 * drive the per-iteration watchdog check (watchdogTickForTest) synchronously on a
 * real, encryptable sender Resource, so the recovery actions are observable
 * without a live link:
 *  - ADVERTISED + timeout + retries left -> a fresh RESOURCE_ADV frame appears at
 *    the link's interface (the re-send), status stays ADVERTISED.
 *  - ADVERTISED + timeout + no retries  -> the transfer is cancelled (no re-send).
 *  - AWAITING_PROOF + timeout + retries left -> Transport.cache_request is invoked
 *    for the expected proof (observed via proofCacheQueriesForTest), status stays
 *    AWAITING_PROOF.
 *
 * The sender Resource is built over a fresh (never-ACTIVE) link whose linkId and
 * derivedKey are primed via reflection, mirroring the reference harness driving a
 * python Resource on a stubbed link. Resource has a private constructor and
 * private fields, so the link is primed the same way the existing integrity tests
 * build their Resources (see ResourceAssemblyIntegrityFailureTest).
 */
@DisplayName("Resource issue #65 bugs (watchdog recovery + failed-callback race)")
class ResourceIssue65BugTest {

    /** Records every raw frame the link layer emits. */
    private class CapturingInterface(override val name: String) : InterfaceRef {
        val frames = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

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
            frames.add(data.copyOf())
        }
    }

    private lateinit var iface: CapturingInterface

    /** HEADER_1 layout: [flags][hops][dest:16][context][body] -> context at 18. */
    private fun countFrames(iface: CapturingInterface, contextByte: Int): Int {
        synchronized(iface.frames) {
            var count = 0
            for (raw in iface.frames) {
                if (raw.size > 19 && (raw[18].toInt() and 0xFF) == contextByte) count++
            }
            return count
        }
    }

    // ===== reflection helpers (Resource and Link have private fields) =====

    private fun <T> set(target: Any, klass: Class<*>, name: String, value: T) {
        val f = klass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    /**
     * Prime a fresh (never-ACTIVE) link so it is a usable SENDER endpoint:
     * a 16-byte linkId (createRaw's destination_hash requirement) and a 32-byte
     * derivedKey (so link.encrypt() has a Token key). A real initiator link only
     * gets these after a handshake; the reference harness tests the Resource
     * state machine against a stubbed link, which is exactly what this mirrors.
     */
    private fun primeWorkingLink(link: Link) {
        set(link, Link::class.java, "linkId", ByteArray(RnsConstants.TRUNCATED_HASH_BYTES) { (it + 1).toByte() })
        set(link, Link::class.java, "derivedKey", ByteArray(32) { (it * 7).toByte() })
    }

    /** Build a real SENDER Resource (encryptable, with parts/hash/expectedProof). */
    private fun senderResource(link: Link): Resource {
        primeWorkingLink(link)
        return Resource.create(
            data = ByteArray(4096) { (it % 251).toByte() },
            link = link,
            advertise = false, // suppress the auto watchdog; the test drives it
            autoCompress = false,
        )
    }

    private fun freshOutLink(appName: String, aspect: String): Link {
        val dest = Destination.create(
            identity = Identity.create(),
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = appName,
            aspects = arrayOf(aspect),
        )
        return Link.create(dest)
    }

    /** Route the link's DATA traffic to the capturing interface. */
    private fun routeLinkToIface(link: Link) {
        Transport.registerLinkPath(link.linkId, iface.hash, 1)
    }

    @BeforeEach
    fun setup() {
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort - a prior test may have left things in an odd state.
        }
        Transport.pathTable.clear()
        Transport.start(Identity.create(), enableTransport = false)
        iface = CapturingInterface(name = "issue65-${System.nanoTime()}")
        Transport.registerInterface(iface)
    }

    @AfterEach
    fun teardown() {
        Resource.watchdogDisabledForTest = false
        // The cache-sweep test pauses the job loop; restore it so later tests
        // running against the shared Transport singleton are not affected
        // (start()/stop() do not reset paused themselves).
        Transport.paused.set(false)
        try {
            Transport.deregisterInterface(iface)
        } catch (_: Exception) {
            // Best-effort.
        }
        Transport.pathTable.clear()
        try {
            Transport.stop()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    // ===== Bug 2: failed-callback registration race =====

    @Test
    @DisplayName("create() installs the failed callback synchronously before the watchdog can fire")
    fun createInstallsFailedCallbackBeforeWatchdog() {
        val link = freshOutLink("issue65cb", "failedcb")
        primeWorkingLink(link)
        var failed = false

        // The failedCallback parameter is installed on callbacks BEFORE
        // advertise() could spawn the watchdog (issue #65). Pre-fix this
        // parameter did not exist (compile-fail red): the application set
        // callbacks.failed AFTER create() returned, leaving a window where the
        // watchdog could fire failed on null (?. no-op).
        val res = Resource.create(
            data = ByteArray(128) { 7 },
            link = link,
            advertise = false,
            autoCompress = false,
            failedCallback = { failed = true },
        )
        assertNotNull(res.callbacks.failed, "create() must install the failed callback")

        // Drive cancel() to confirm the installed callback actually fires and
        // propagates (the bug was that it fired on null and was swallowed).
        res.cancel()
        assertTrue(failed, "the failed callback installed via create() must fire on cancel")
    }

    @Test
    @DisplayName("a failure from the live watchdog fires the callback passed to create()")
    fun liveWatchdogFailureFiresCreateFailedCallback() {
        val link = freshOutLink("issue65cb2", "liverace")
        routeLinkToIface(link)
        primeWorkingLink(link)
        val fired = java.util.concurrent.CountDownLatch(1)

        // advertise=true so create() actually spawns the watchdog (the real
        // race window). The failedCallback is installed on callbacks.failed
        // BEFORE that happens.
        Resource.create(
            data = ByteArray(256) { 3 },
            link = link,
            advertise = true,
            autoCompress = false,
            failedCallback = { fired.countDown() },
        )

        // Force the running watchdog's ADVERTISED timeout to fire immediately
        // (lastActivity in the past) with no retries left (cancel branch, not
        // re-send), mirroring "link broken at create time". Pre-fix, the app
        // could not hand the callback into create(), so by the time the
        // watchdog fired, callbacks.failed was still null -> the failure was a
        // no-op and this latch never counted down (red). Post-fix the callback
        // is already installed, so the watchdog's cancel invokes it.
        val outgoingField = Link::class.java.getDeclaredField("outgoingResources")
        outgoingField.isAccessible = true
        val outgoing = @Suppress("UNCHECKED_CAST") outgoingField.get(link) as MutableList<Resource>
        assertEquals(1, outgoing.size, "create(advertise=true) must register the outgoing resource")
        val res = outgoing.first()
        set(res, Resource::class.java, "lastActivity", 0L)
        set(res, Resource::class.java, "retriesLeft", 0)

        assertTrue(fired.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "the live watchdog's failure must invoke the failedCallback passed to create()")
    }

    // ===== Bug 1: sender watchdog recovery actions =====

    @Test
    @DisplayName("sender in ADVERTISED with a timeout re-sends the advertisement")
    fun senderAdvertisedTimeoutResendsAdvertisement() {
        val link = freshOutLink("issue65adv", "resend")
        primeWorkingLink(link) // 16-byte linkId before path routing
        routeLinkToIface(link)
        val res = senderResource(link)

        // Prime the ADVERTISED state: status set, advSent/lastActivity in the
        // past (so the timeout check fires), and the ADVERTISED retry budget
        // primed the way python __advertise_job does (max_adv_retries).
        set(res, Resource::class.java, "status", ResourceConstants.ADVERTISED)
        set(res, Resource::class.java, "advSent", 0L)
        set(res, Resource::class.java, "lastActivity", 0L)
        set(res, Resource::class.java, "retriesLeft", ResourceConstants.MAX_ADV_RETRIES)

        val before = countFrames(iface, PacketContext.RESOURCE_ADV.value)
        res.watchdogTickForTest()

        // The recovery action (python Resource.py:584-585) re-sends the
        // advertisement: a fresh RESOURCE_ADV frame appears at the interface,
        // and the transfer is NOT failed.
        assertEquals(before + 1, countFrames(iface, PacketContext.RESOURCE_ADV.value),
            "ADVERTISED timeout must re-send the advertisement")
        assertEquals(ResourceConstants.ADVERTISED, res.status, "a re-send must not fail the transfer")
    }

    @Test
    @DisplayName("sender in ADVERTISED with retries exhausted cancels (no re-send)")
    fun senderAdvertisedRetriesExhaustedCancels() {
        val link = freshOutLink("issue65adv2", "exhaust")
        primeWorkingLink(link) // 16-byte linkId before path routing
        routeLinkToIface(link)
        val res = senderResource(link)

        set(res, Resource::class.java, "status", ResourceConstants.ADVERTISED)
        set(res, Resource::class.java, "advSent", 0L)
        set(res, Resource::class.java, "lastActivity", 0L)
        set(res, Resource::class.java, "retriesLeft", 0) // no retries left

        val before = countFrames(iface, PacketContext.RESOURCE_ADV.value)
        res.watchdogTickForTest()

        // python Resource.py:576-578: no retries left -> cancel, no re-send.
        assertEquals(before, countFrames(iface, PacketContext.RESOURCE_ADV.value),
            "no retries left must NOT re-send the advertisement")
        assertEquals(ResourceConstants.FAILED, res.status, "ADVERTISED timeout with no retries must cancel")
    }

    @Test
    @DisplayName("sender in AWAITING_PROOF with a timeout queries the proof from the cache")
    fun senderAwaitingProofTimeoutQueriesCache() {
        val link = freshOutLink("issue65prf", "proof")
        primeWorkingLink(link) // 16-byte linkId before path routing
        routeLinkToIface(link)
        val res = senderResource(link)

        set(res, Resource::class.java, "status", ResourceConstants.AWAITING_PROOF)
        set(res, Resource::class.java, "lastPartSent", 0L)
        set(res, Resource::class.java, "lastActivity", 0L)
        set(res, Resource::class.java, "retriesLeft", ResourceConstants.MAX_RETRIES)

        val before = res.proofCacheQueriesForTest()
        res.watchdogTickForTest()

        // The recovery action (python Resource.py:651-657) queries the proof
        // from the network cache via Transport.cache_request.
        assertEquals(before + 1, res.proofCacheQueriesForTest(),
            "AWAITING_PROOF timeout must query the proof from the network cache")
        assertEquals(ResourceConstants.AWAITING_PROOF, res.status, "a proof-cache query must not fail the transfer")
    }

    @Test
    @DisplayName("AWAITING_PROOF retry re-anchors the timeout, so the next tick does not immediately re-fire")
    fun awaitingProofRetryReanchorsTimeout() {
        val link = freshOutLink("issue65prf2", "reanchor")
        primeWorkingLink(link) // 16-byte linkId before path routing
        routeLinkToIface(link)
        val res = senderResource(link)

        // Prime AWAITING_PROOF with lastPartSent in the past so the first tick
        // fires. Pre-fix (issue #65 / PR review P1), the gate anchored AWAITING_PROOF
        // on last_activity, not last_part_sent: after a retry only lastPartSent was
        // re-anchored (queryProofFromCache), lastActivity stayed old, so the gate
        // re-tripped on the very next tick. The sender then burned every one of its
        // retries in ~16 ticks (one per 1s watchdog cycle) instead of giving each
        // attempt a full rtt*PROOF_TIMEOUT_FACTOR + SENDER_GRACE window, and a
        // genuinely slow proof arrived only after the transfer had already failed.
        set(res, Resource::class.java, "status", ResourceConstants.AWAITING_PROOF)
        set(res, Resource::class.java, "lastPartSent", 0L)
        set(res, Resource::class.java, "lastActivity", 0L)
        set(res, Resource::class.java, "retriesLeft", ResourceConstants.MAX_RETRIES)

        val before = res.proofCacheQueriesForTest()
        res.watchdogTickForTest()
        assertEquals(before + 1, res.proofCacheQueriesForTest(),
            "the first AWAITING_PROOF timeout must query the proof once")

        // The tick's recovery re-anchors lastPartSent to "now" (python
        // Resource.py:657). A follow-up tick fired immediately must therefore see
        // the proof window as still open and NOT issue another cache query. This
        // is the exact regression: pre-fix the gate used lastActivity (still 0),
        // so this second tick re-fired and queried again.
        set(res, Resource::class.java, "retriesLeft", ResourceConstants.MAX_RETRIES)
        val afterFirst = res.proofCacheQueriesForTest()
        res.watchdogTickForTest()
        assertEquals(
            afterFirst,
            res.proofCacheQueriesForTest(),
            "a re-anchored AWAITING_PROOF timeout must not re-fire on the next tick (retry cadence regression)"
        )
    }

    @Test
    @DisplayName("prove() caches the proof packet so the sender's AWAITING_PROOF cache query can find it")
    fun proveCachesProofPacket() {
        val link = freshOutLink("issue65prf3", "cacheproof")
        primeWorkingLink(link)
        val res = senderResource(link)
        // Prove uses the receiver-side assembled data (with metadata) to compute
        // proof = fullHash(data + hash); a non-null assembled buffer is enough
        // here - the point is that prove() stores the proof packet in the
        // transport cache, not the exact proof bytes.
        set(res, Resource::class.java, "uncompressedData", ByteArray(64) { 5 })

        // Prove() calls packet.send() before Transport.cache(packet, force_cache),
        // and send() mutates the packet (receipt/flags), so its stored packetHash
        // is not trivially reconstructable from scratch. Instead of hashing, diff
        // the transport cache before/after and inspect the new entry's raw bytes:
        // the proof packet's body is proof_payload = hash + proof (64 bytes).
        val cacheMap = @Suppress("UNCHECKED_CAST") run {
            val f = Transport::class.java.getDeclaredField("packetCache")
            f.isAccessible = true
            f.get(Transport) as java.util.concurrent.ConcurrentHashMap<*, *>
        }
        val before = cacheMap.size
        val beforeRaws = cacheMap.values.map { (it as Transport.CachedPacket).raw.toList() }

        res.proveForTest()

        // python Resource.py:759: Transport.cache(proof_packet, force_cache=True)
        // after sending. Without this the sender's AWAITING_PROOF recovery
        // (Transport.cache_request in queryProofFromCache) can never find the
        // proof, so the recovery is a no-op - "lost proofs stay lost".
        assertEquals(before + 1, cacheMap.size,
            "prove() must store exactly one new packet (the proof) in the transport cache")
        val newEntry = cacheMap.values
            .map { (it as Transport.CachedPacket).raw.toList() }
            .first { it !in beforeRaws }
        // The proof body (hash + proof, 64 bytes) must appear in the cached raw.
        val payload = res.hash + network.reticulum.crypto.Hashes.fullHash(ByteArray(64) { 5 } + res.hash)
        val containsPayload = (0 until newEntry.size - payload.size + 1).any { i ->
            (0 until payload.size).all { newEntry[i + it] == payload[it] }
        }
        assertTrue(containsPayload, "the cached proof packet must contain the hash+proof payload")
    }

    @Test
    fun `cleanCache removes expired entries and spares fresh ones`() {
        // PR review P1: prove() now force-caches one proof per completed
        // transfer, but the in-memory packet cache had no scheduled sweep
        // (cleanCache() was never called; only lazy-on-read eviction in
        // getCachedPacket), so force-cached packets that nothing re-reads
        // accumulate for the process lifetime. runJobs() now schedules
        // cleanCache() (python Transport.py:951-956). This test drives the
        // sweep directly: an expired entry must be removed, a fresh one kept.
        val cacheMap = @Suppress("UNCHECKED_CAST") run {
            val f = Transport::class.java.getDeclaredField("packetCache")
            f.isAccessible = true
            f.get(Transport) as java.util.concurrent.ConcurrentHashMap<Any, Any>
        }
        // Pause the job loop so its periodic cleanCache() can't race this test:
        // if it swept between us resetting the throttle and inserting the
        // expired entry, it would reset the timer and our cleanCache() call
        // below would be a no-op, failing the test even though cleanup works.
        // cleanCache() itself does not consult paused, so it still runs.
        Transport.paused.set(true)
        // Reset the throttle so cleanCache() runs this call, regardless of when
        // Transport.start() last set it.
        val throttle = Transport::class.java.getDeclaredField("packetCacheLastCleaned")
        throttle.isAccessible = true
        throttle.set(Transport, 0L)

        val now = System.currentTimeMillis()
        val timeout = 60L * 60 * 1000 // TransportConstants.PACKET_CACHE_TIMEOUT
        val freshHash = ByteArray(32) { 0x11 }
        val expiredHash = ByteArray(32) { 0x22 }
        cacheMap[freshHash.toKey()] = Transport.CachedPacket(ByteArray(8) { 1 }, now, null)
        cacheMap[expiredHash.toKey()] = Transport.CachedPacket(ByteArray(8) { 2 }, now - 2 * timeout, null)

        Transport.cleanCache()

        // The expired entry is gone, the fresh one survives.
        assertTrue(!cacheMap.containsKey(expiredHash.toKey()),
            "cleanCache must remove an entry past its TTL")
        assertTrue(cacheMap.containsKey(freshHash.toKey()),
            "cleanCache must spare an entry still within its TTL")
    }
}
