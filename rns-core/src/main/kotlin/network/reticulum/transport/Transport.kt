package network.reticulum.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.reticulum.common.ByteArrayKey
import network.reticulum.common.RnsLog
import network.reticulum.common.constantTimeEquals
import network.reticulum.common.ContextFlag
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.HeaderType
import network.reticulum.common.InterfaceMode
import network.reticulum.common.PacketContext
import network.reticulum.common.PacketType
import network.reticulum.common.Platform
import network.reticulum.common.RnsConstants
import network.reticulum.common.TransportType
import network.reticulum.common.concatBytes
import network.reticulum.common.toHexString
import network.reticulum.common.toKey
import network.reticulum.crypto.CryptoProvider
import network.reticulum.crypto.Hashes
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.packet.Packet
import network.reticulum.packet.PacketReceipt
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Handler for incoming announces.
 */
fun interface AnnounceHandler {
    /**
     * Called when an announce is received.
     *
     * @param destinationHash The destination hash being announced
     * @param announcedIdentity The identity from the announce (public keys only)
     * @param appData Application data included in the announce
     * @return value is IGNORED. Dispatch is unconditional: EVERY registered
     *   handler (whose aspect filter matches) receives EVERY announce, mirroring
     *   RNS where Transport calls all `announce_handlers` (Transport.py — no
     *   first-handler-wins / early-out). A handler cannot claim exclusive
     *   ownership of an announce; the `Boolean` is retained only for source
     *   compatibility.
     */
    fun handleAnnounce(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?,
    ): Boolean
}

/**
 * Extended announce handler that receives full announce context including
 * the matched aspect, hop count, and receiving interface name.
 *
 * Transport determines the aspect by computing
 * `Destination.hashFromNameAndIdentity(aspectFilter, identity)` for each
 * registered handler and comparing against the packet's destination hash
 * — the same approach Python uses (Transport.py:1895-1896).
 */
interface RichAnnounceHandler : AnnounceHandler {
    /**
     * Whether this handler wants PATH_RESPONSE-context announces. Mirrors
     * python's `hasattr(handler, "receive_path_responses") and
     * handler.receive_path_responses == True` gate (Transport.py:2050-2052):
     * a handler without it (the default) is skipped for path responses but
     * still receives live announces.
     */
    val receivePathResponses: Boolean get() = false

    /**
     * Like [handleAnnounce] but with full context. The `Boolean` return is
     * likewise IGNORED — dispatch is unconditional (every matching handler is
     * called); the type is kept only for source compatibility.
     */
    fun handleAnnounceWithContext(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?,
        hops: Int,
        receivingInterfaceName: String?,
        matchedAspect: String?,
        /** The 32-byte announce packet hash (python's 4-param dispatch arm,
         * Transport.py:2063-2069). Null when unknown. */
        announcePacketHash: ByteArray? = null,
    ): Boolean

    override fun handleAnnounce(
        destinationHash: ByteArray,
        announcedIdentity: Identity,
        appData: ByteArray?,
    ): Boolean = handleAnnounceWithContext(destinationHash, announcedIdentity, appData, 0, null, null, null)
}

/**
 * A handler paired with its optional aspect filter, mirroring Python's
 * `handler.aspect_filter` attribute (Transport.py:1890).
 *
 * When [aspectFilter] is non-null, Transport only calls the handler for
 * announces whose destination hash matches `Destination.hashFromNameAndIdentity(aspectFilter, identity)`.
 * When null, the handler receives all announces.
 */
internal data class RegisteredHandler(
    val handler: AnnounceHandler,
    val aspectFilter: String?,
)

/**
 * Callback for packet delivery.
 */
fun interface PacketCallback {
    fun onPacket(
        data: ByteArray,
        packet: Packet,
    )
}

/**
 * Callback for proof reception.
 */
fun interface ProofCallback {
    /**
     * Called when a proof is received for a packet.
     *
     * @param packet The proof packet
     * @return true if the proof was validated successfully
     */
    fun onProofReceived(packet: Packet): Boolean
}

/**
 * The Transport singleton manages routing and packet delivery.
 *
 * This is the core routing engine that:
 * - Maintains path tables for known destinations
 * - Processes incoming packets from interfaces
 * - Routes outgoing packets to appropriate interfaces
 * - Handles announce propagation and path discovery
 */
object Transport {
    @Volatile
    private var receiptCallbackExecutor: java.util.concurrent.ExecutorService? = null

    /**
     * Submit a packet-receipt callback for asynchronous execution on Transport's
     * receipt-callback executor. The executor is owned by Transport so it can be
     * drained and shut down cleanly on [stop]. If Transport is stopped, callbacks
     * are silently dropped rather than fired against torn-down state.
     */
    internal fun submitReceiptCallback(task: Runnable) {
        try {
            receiptCallbackExecutor?.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Transport is stopping; drop the callback silently. A racing stop()
            // call can shutdownNow() the executor between our null-check and
            // execute(), after which submissions throw.
        }
    }

    // ===== State =====

    /** Transport identity for this node. Public-settable, as python's
     * `RNS.Transport.identity` module attribute is (the conformance bridge
     * injects it exactly as the python reference bridge does). */
    var identity: Identity? = null

    /** Whether transport is enabled (routing for other nodes). Public-settable,
     * as python's `Reticulum.__transport_enabled` is via the reference
     * bridge's setattr injection. */
    var transportEnabled: Boolean = false

    /** Whether this instance is connected to a local shared instance (Python: Transport.owner.is_connected_to_shared_instance). */
    @Volatile
    var isConnectedToSharedInstance: Boolean = false

    /** Optional network identity for discovery encryption. */
    var networkIdentity: Identity? = null

    /** Interface discovery announcer. */
    private var interfaceAnnouncer: network.reticulum.discovery.InterfaceAnnouncer? = null

    /** Interface discovery handler. */
    private var discoveryHandler: network.reticulum.discovery.InterfaceDiscovery? = null

    /** Whether transport has been started. */
    private val started = AtomicBoolean(false)

    /** Crypto provider for IFAC operations. */
    private val crypto: CryptoProvider by lazy { defaultCryptoProvider() }

    /** Lock for job execution. */
    private val jobsLock = ReentrantLock()

    // ---- inbound queue (python Transport.py:141-146, 314-317, 528, 1891-1911) ----

    /**
     * Whether [inbound] queues packets for the drainer thread (python USE_INBOUND_QUEUE,
     * default True) or processes them on the calling thread.
     *
     * Queued is the shipping behaviour and the one RNS 1.5.2 defines: an interface thread
     * that reads a frame spends only the preprocessing cost on it and never waits behind
     * another interface's packet. Synchronous mode exists for the same reason the reference
     * keeps the flag — a benchmark that wants to time the processing path, not the enqueue,
     * and a caller that must see the result of one packet before injecting the next.
     */
    @Volatile var useInboundQueue: Boolean = true

    /**
     * A packet that has been through [preprocessInbound] and is waiting to be drained. Carries
     * what the second stage needs and would otherwise have to recompute: the wire bytes (for
     * byte accounting and broadcast forwarding), the announce validation result (one Ed25519
     * verify per announce, not two), and the packet-hash key (one allocation, not two).
     */
    private class InboundItem(
        val packet: Packet,
        val interfaceRef: InterfaceRef,
        val preValidatedAnnounce: AnnounceData?,
        val pktKey: ByteArrayKey,
    ) {
        /** The wire bytes as unpacked; `Packet.unpack` keeps its own copy, so the item holds no second one. */
        val raw: ByteArray get() = packet.raw ?: ByteArray(0)
    }

    @Volatile private var inboundQueues: InboundQueues<InboundItem>? = null
    @Volatile private var inboundThread: Thread? = null

    /**
     * Packets accepted by [inbound] and not yet finished by the drainer. The test seam
     * [awaitInboundIdle] waits on this; it is the one honest definition of "everything I
     * handed to inbound() has had its effect".
     */
    private val inboundInFlight = AtomicInteger(0)

    /** How long the drainer waits on an empty queue before re-checking whether it should still run. */
    private const val INBOUND_POLL_MS = 250L

    /**
     * Path requests received and not yet answered, destination -> first-seen time (python
     * inflight_path_requests, Transport.py:188). Once inbound became asynchronous a burst of
     * requests for one destination could sit in the queue together; this batches the later
     * ones onto the first instead of processing each. Released when the request is answered,
     * when the matching announce arrives, or after PATH_REQUEST_GATE_TIMEOUT.
     */
    private val inflightPathRequests = ConcurrentHashMap<ByteArrayKey, Long>()

    /**
     * Test-only: sleep for [millis] with `jobsLock` temporarily released, then
     * re-acquire the same hold count before returning. Used by the race-inducer
     * hook in `Link.validateRequest` (post-prove seam) so that DATA packets
     * arriving on other ingest threads during the widened window can actually
     * contend on the lock and exercise the bookkeeping race, instead of being
     * serialized behind the still-held jobsLock.
     *
     * If `jobsLock` is not held by the calling thread, this falls back to a
     * plain `Thread.sleep(millis)`. Production code MUST NOT call this.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun raceInducerSleepReleasingJobsLock(millis: Long) {
        if (millis <= 0L) return
        if (!jobsLock.isHeldByCurrentThread) {
            Thread.sleep(millis)
            return
        }
        // Fully release the lock (handles reentrant holds), sleep, then re-acquire
        // the same number of times so the caller's invariant is preserved.
        val holdCount = jobsLock.holdCount
        repeat(holdCount) { jobsLock.unlock() }
        try {
            Thread.sleep(millis)
        } finally {
            repeat(holdCount) { jobsLock.lock() }
        }
    }

    /** Whether jobs are currently running. */
    private val jobsRunning = AtomicBoolean(false)

    /** Whether Transport is paused (drops inbound/outbound, skips job work). */
    val paused = AtomicBoolean(false)

    // ===== Coroutine Support (for Android) =====

    /** Coroutine scope for job loop (null when using thread-based loop). */
    private var jobLoopScope: CoroutineScope? = null

    /** Coroutine job for job loop (null when using thread-based loop). */
    private var jobLoopJob: Job? = null

    /** Custom job interval in milliseconds (null uses default). */
    @Volatile
    var customJobIntervalMs: Long? = null

    /** Custom tables cull interval in milliseconds (null uses default). */
    @Volatile
    var customTablesCullIntervalMs: Long? = null

    /** Custom announces check interval in milliseconds (null uses default). */
    @Volatile
    var customAnnouncesCheckIntervalMs: Long? = null

    /** Whether to use coroutine-based job loop instead of thread-based. */
    @Volatile
    var useCoroutineJobLoop: Boolean = false

    /** Interface modes that trigger recursive path forwarding for unknown destinations. */
    // python Interface.DISCOVER_PATHS_FOR (Interface.py:55)
    private val DISCOVER_PATHS_FOR =
        setOf(
            InterfaceMode.ACCESS_POINT,
            InterfaceMode.GATEWAY,
            InterfaceMode.ROAMING,
            // Internal interfaces still search for unknown destinations: the mode contains
            // ANNOUNCES, not path discovery, and a private segment that could not resolve a
            // path would not be able to reach anything outside itself.
            InterfaceMode.INTERNAL,
        )

    /**
     * Where a BOUNDARY-mode interface's path request may be forwarded
     * (python `Interface.BOUNDARY_SEARCH_MODES`, `Interface.py:56`).
     *
     * RNS 1.5.2 lets a boundary interface search for unknown destinations, which it
     * previously would not (`Transport.py:3431-3433`), but only outward across boundary and
     * gateway links. A boundary sits between two networks; letting its requests reach the
     * interior would pull traffic from the far side into a network that never asked for it,
     * which is the separation the mode exists to provide.
     */
    private val BOUNDARY_SEARCH_MODES =
        setOf(
            InterfaceMode.BOUNDARY,
            InterfaceMode.GATEWAY,
        )

    // ===== Tables =====

    /** Path table: destination_hash -> PathEntry. */
    val pathTable = ConcurrentHashMap<ByteArrayKey, PathEntry>()

    /** Link table: link_id -> LinkEntry. */
    val linkTable = ConcurrentHashMap<ByteArrayKey, LinkEntry>()

    /** Reverse table: packet_truncated_hash -> ReverseEntry. */
    val reverseTable = ConcurrentHashMap<ByteArrayKey, ReverseEntry>()

    /** Announce table: destination_hash -> AnnounceEntry. */
    val announceTable = ConcurrentHashMap<ByteArrayKey, AnnounceEntry>()

    /** Packet hash list for duplicate detection. */
    private val packetHashlist = ConcurrentHashMap.newKeySet<ByteArrayKey>()
    private val packetHashlistPrev = ConcurrentHashMap.newKeySet<ByteArrayKey>()

    // ===== Packet Cache =====

    /** Cached packet data class. */
    data class CachedPacket(
        val raw: ByteArray,
        val timestamp: Long,
        val receivingInterfaceHash: ByteArray?,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > TransportConstants.PACKET_CACHE_TIMEOUT
    }

    /** Pending discovery path request entry (Python: discovery_path_requests). */
    data class DiscoveryPathRequest(
        val destinationHash: ByteArray,
        val timeout: Long,
        val requestingInterface: InterfaceRef,
        /**
         * python "engaged": true once recursive forwarding has actually gone out for this
         * destination. A duplicate request batched during inbound preprocessing creates a
         * non-engaged entry, which path_request still forwards for; only an engaged one
         * makes it say "already waiting" (Transport.py:1880, 3541, 3561).
         */
        val engaged: Boolean = true,
    ) {
        /** Every interface that asked for this destination (python "requesting_interfaces"). */
        val requestingInterfaces: MutableList<InterfaceRef> = CopyOnWriteArrayList(listOf(requestingInterface))

        fun addRequestingInterface(iface: InterfaceRef) {
            if (requestingInterfaces.none { it.hash.contentEquals(iface.hash) }) requestingInterfaces.add(iface)
        }
    }

    /** Interface traffic statistics. */
    data class InterfaceTrafficStats(
        var txBytes: Long = 0,
        var rxBytes: Long = 0,
        var txPackets: Long = 0,
        var rxPackets: Long = 0,
        var announcesSent: Long = 0,
        var lastActivity: Long = System.currentTimeMillis(),
    )

    /** Packet cache: packet_hash -> CachedPacket. */
    private val packetCache = ConcurrentHashMap<ByteArrayKey, CachedPacket>()

    /** Last time cache was cleaned. */
    private var cacheLastCleaned: Long = 0
    private var identitiesLastSaved: Long = 0

    /** Cache path for persistent announce storage. Set by Reticulum during init. */
    @Volatile
    private var cachePath: String = System.getProperty("user.home") + "/.reticulum/cache"

    /** Storage path for persistent data. Set by Reticulum during init. */
    @Volatile
    private var storagePath: String = System.getProperty("user.home") + "/.reticulum/storage"

    // ===== Registered Objects =====

    /** Registered interfaces. */
    private val interfaces = CopyOnWriteArrayList<InterfaceRef>()

    /**
     * Monitor guarding compound mutations of [isConnectedToSharedInstance]
     * together with the `interfaces` collection scan that decides whether
     * to clear it. The `interfaces` list itself is a `CopyOnWriteArrayList`
     * (individual add/remove are atomic), but `deregisterInterface`'s
     * "remove this ref, then scan to see if any other shared-instance
     * interface remains" is a compound action that races with a concurrent
     * `registerInterface` setting the flag back to `true` between the two
     * steps. Python sets/clears the equivalent flag only at single-threaded
     * `Reticulum.__init__` time (Reticulum.py:417, 425-435) and so doesn't
     * face this race — kotlin allows runtime register/deregister churn
     * (Carina toggling host/consume on one process), so we serialize the
     * flag-flip sites here. JVM-memory-model category (a) deviation,
     * documented in port-deviations.md.
     */
    private val sharedInstanceFlagLock = Any()

    /** Registered destinations. */
    private val destinations = CopyOnWriteArrayList<Destination>()

    /**
     * Hash -> Destination index over [destinations], maintained in
     * register/deregisterDestination. Turns "is this one of ours?" — asked on every inbound
     * announce and at the local-destination skip — from an O(n) linear scan with contentEquals
     * into an O(1) lookup. [destinations] stays the source of truth (order, iteration).
     */
    private val destinationIndex = ConcurrentHashMap<ByteArrayKey, Destination>()

    /** Registered announce handlers with their aspect filters. */
    private val announceHandlers = CopyOnWriteArrayList<RegisteredHandler>()

    /**
     * Invoked when an announce arrives for one of our OWN (local) destinations that we
     * did NOT emit — i.e. another node is announcing our identity. The usual cause is one
     * identity imported into several installs, which silently splits inbound messages
     * across instances. Our own announces echoing off a shared instance are filtered out
     * (matched by packetHash against our self-emitted announces; packetHash excludes the
     * hops byte, so an echo with a bumped hop count still matches), so this fires only for
     * the genuine collision. The announce is still skipped for path/identity processing
     * regardless — this is a pure observation hook.
     */
    @Volatile
    var onForeignLocalAnnounce: ((destinationHash: ByteArray, packetHash: ByteArray, hops: Int) -> Unit)? = null

    /** Max self-emitted announce hashes retained for echo discrimination. */
    private const val SELF_ANNOUNCE_HASH_MAX = 512

    /**
     * Bounded FIFO set of packetHashes of announces we originated for our own
     * destinations. Lets onForeignLocalAnnounce distinguish our own shared-instance echo
     * (silent) from a foreign announce of our identity (surfaced).
     */
    private val selfAnnounceHashes: MutableSet<ByteArrayKey> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                object : LinkedHashMap<ByteArrayKey, Boolean>(64, 0.75f, false) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<ByteArrayKey, Boolean>?,
                    ): Boolean = size > SELF_ANNOUNCE_HASH_MAX
                },
            ),
        )

    /** Known aspects for resolving destination hashes in null-filter handlers. */
    private val knownAspects = ConcurrentHashMap.newKeySet<String>()

    /** Control destination hashes (path requests, etc.). */
    private val controlHashes = ConcurrentHashMap.newKeySet<ByteArrayKey>()

    /** Rate limiting: destination_hash -> list of announce timestamps. */
    private val announceRateTable = ConcurrentHashMap<ByteArrayKey, AnnounceRateEntry>()

    /** Registered links: link_id -> Link. */
    // Python uses lists (not maps) so multiple links with the same link_id can coexist
    // (e.g., client + server links in the same process when connecting to own hub)
    private val pendingLinks = CopyOnWriteArrayList<Link>()
    private val activeLinks = CopyOnWriteArrayList<Link>()

    /**
     * All outgoing packet receipts, keyed by TRUNCATED packet hash (proof packets carry
     * the original packet's truncated hash as their destination, matching Python's
     * get_hash() usage). One structure serves both proof matching and timeout tracking;
     * key insertion order gives Python's oldest-first culling. Python's receipts list
     * can hold several receipts for the same hash (a resend builds a fresh receipt),
     * so each key maps to an append-only list. Every access is guarded by
     * `synchronized(receipts)`; [receiptCount] is the number of individual receipts.
     */
    private val receipts = LinkedHashMap<ByteArrayKey, ArrayList<PacketReceipt>>()
    private var receiptCount = 0

    /** Arbitrary proof callbacks registered via [registerReceipt] (packetHash, callback). */
    private val pendingProofCallbacks = ConcurrentHashMap<ByteArrayKey, ProofCallback>()
    private var receiptsLastChecked: Long = 0

    /** Last run of the announce-retransmit pass (python `announces_last_checked`). */
    private var announcesLastChecked: Long = 0

    /** Announce timing per destination: dest_hash -> allowed_at timestamp. */
    private val announceAllowedAt = ConcurrentHashMap<ByteArrayKey, Long>()

    /** Mechanism 1: Announce table entries held during path request handling.
     *  dest_hash -> AnnounceEntry. Restored to announceTable after rebroadcast. */
    private val heldAnnounceEntries = ConcurrentHashMap<ByteArrayKey, AnnounceEntry>()

    /** Local client interfaces (shared instance clients). */
    private val localClientInterfaces = CopyOnWriteArrayList<InterfaceRef>()

    /** Per-interface traffic statistics. */
    private val interfaceStats = ConcurrentHashMap<ByteArrayKey, InterfaceTrafficStats>()

    /** Per-interface announce queues. */
    private val interfaceAnnounceQueues = ConcurrentHashMap<ByteArrayKey, MutableList<QueuedAnnounce>>()

    /** Per-interface announce allowed timestamps. */
    private val interfaceAnnounceAllowedAt = ConcurrentHashMap<ByteArrayKey, Long>()

    // ===== Blackhole (port of RNS/Transport.py:3406-3538) =====

    /** Blackholed identities: identity-hash -> {source, until(ms)?, reason?}. */
    val blackholedIdentities = ConcurrentHashMap<ByteArrayKey, BlackholeEntry>()

    /** Trusted remote blackhole-source identity hashes (python
     * Reticulum.blackhole_sources(); kotlin has no config layer, so this list
     * is the source of truth, mutated by config / the conformance bridge). */
    val blackholeSources = CopyOnWriteArrayList<ByteArray>()

    /** Remote-management ACL: identity hashes allowed to use the transport's
     * remote-management destination (python Transport.remote_management_allowed,
     * Transport.py; populated from the enable_remote_management config knob). */
    val remoteManagementAllowed = CopyOnWriteArrayList<ByteArray>()

    /** Trusted interface-discovery-source identity hashes (python
     * Reticulum.interface_discovery_sources(); populated from the
     * interface_discovery_sources config knob). */
    val interfaceDiscoverySources = CopyOnWriteArrayList<ByteArray>()

    @Volatile private var blackholeLastChecked: Long = 0
    private val blackholeCheckIntervalMs = 60_000L

    /** Storage dir for blackhole persistence (python Reticulum.blackholepath). */
    private val blackholePath: String get() = "$storagePath/blackhole"

    // ===== Tunnels =====

    /** Active tunnels: tunnel_id -> TunnelInfo. */
    private val tunnels = ConcurrentHashMap<ByteArrayKey, TunnelInfo>()

    /** Tunnel interfaces: tunnel_id -> InterfaceRef. */
    private val tunnelInterfaces = ConcurrentHashMap<ByteArrayKey, InterfaceRef>()

    // ===== Traffic Stats =====

    var trafficRxBytes: Long = 0
        private set
    var trafficTxBytes: Long = 0
        private set

    /** Current RX speed in bytes/sec. */
    var speedRx: Long = 0
        private set

    /** Current TX speed in bytes/sec. */
    var speedTx: Long = 0
        private set

    /** Last traffic snapshot for speed calculation. */
    private var lastTrafficSnapshot = Pair(0L, 0L)
    private var lastTrafficTime = 0L

    // ===== Timestamps =====

    private var startTime: Long = 0

    /** python `Transport.last_mgmt_announce` / `mgmt_announce_interval` (Transport.py:265-266). */
    @Volatile private var lastMgmtAnnounce: Long = 0
    private val MGMT_ANNOUNCE_INTERVAL_MS: Long = 2L * 60 * 60 * 1000
    private var tablesLastCulled: Long = 0
    private var hashlistLastCleaned: Long = 0

    /**
     * Initialize and start the transport system.
     *
     * @param transportIdentity Identity for this transport node (or null to generate)
     * @param enableTransport Whether to enable transport (routing for others)
     */
    fun start(
        transportIdentity: Identity? = null,
        enableTransport: Boolean = false,
    ) {
        if (started.getAndSet(true)) {
            return // Already started
        }

        // Built first: inbound() checks `started` and then reaches for the queues, so they
        // must exist before anything else in start() can cause a packet to arrive. Sized from
        // the Reticulum config knobs, or the Transport defaults when there is no instance
        // (python Transport.start, Transport.py:310-317).
        inboundQueues =
            InboundQueues(
                intArrayOf(
                    network.reticulum.Reticulum.inboundDataQueueLength(),
                    network.reticulum.Reticulum.inboundAnnounceQueueLength(),
                    network.reticulum.Reticulum.inboundPrQueueLength(),
                    network.reticulum.Reticulum.inboundIlQueueLength(),
                ),
            )
        inboundInFlight.set(0)

        // Warm the crypto primitives off-thread so the first link's RTT measurement does not
        // include JIT compilation (see CryptoWarmup for the keepalive asymmetry this avoids).
        network.reticulum.crypto.CryptoWarmup.runAsync()

        receiptCallbackExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "PacketReceipt-callbacks").apply { isDaemon = true }
            }

        identity = transportIdentity ?: Identity.create()
        transportEnabled = enableTransport
        startTime = System.currentTimeMillis()
        tablesLastCulled = startTime
        hashlistLastCleaned = startTime

        // Initialize path request destination
        initializeControlDestinations()

        // python Transport.py:397: the first management announce goes out 15 s after start.
        lastMgmtAnnounce = System.currentTimeMillis() - MGMT_ANNOUNCE_INTERVAL_MS + 15_000
        // Load persisted tunnel table
        if (enableTransport) {
            loadTunnelTable()
        }

        // Load path table and packet hashlist from storage
        loadPersistedDataFromStorage()

        // Load persisted blackhole entries (python Transport.start:239)
        try { reloadBlackhole() } catch (e: Exception) { log("blackhole reload failed: ${e.message}") }

        // Start background job loop
        // On Android with coroutine scope provided, use coroutines
        // Otherwise, use traditional thread-based approach
        if (useCoroutineJobLoop && jobLoopScope != null) {
            startCoroutineJobLoop()
            log("Transport started with coroutine job loop (transport=${if (enableTransport) "enabled" else "disabled"})")
        } else {
            thread(name = "Transport-jobs", isDaemon = true) {
                jobLoop()
            }
            log("Transport started with thread job loop (transport=${if (enableTransport) "enabled" else "disabled"})")
        }

        // The inbound drainer (python Transport.py:528, one worker). A plain thread on every
        // platform, coroutine job loop or not: it spends its life blocked on a condition and
        // costs nothing idle, and the reference runs it as a thread too.
        // Registered BEFORE it starts: the loop's first act is to check that it is the
        // registered drainer, and the kotlin `thread {}` helper would have started it
        // before this assignment landed.
        val drainer = Thread({ inboundJob() }, "Transport-inbound")
        drainer.isDaemon = true
        inboundThread = drainer
        drainer.start()
    }

    /**
     * Initialize control destinations (path request, tunnel synthesize, etc.).
     */
    private fun initializeControlDestinations() {
        // Create path request destination
        pathRequestDestination =
            Destination.create(
                identity = null,
                direction = DestinationDirection.IN,
                type = DestinationType.PLAIN,
                appName = TransportConstants.APP_NAME,
                aspects = arrayOf("path", "request"),
            )
        controlHashes.add(pathRequestDestination!!.hash.toKey())
        log("Path request destination: ${pathRequestDestination!!.hexHash}")

        // Create tunnel synthesize destination
        tunnelSynthesizeDestination =
            Destination.create(
                identity = null,
                direction = DestinationDirection.IN,
                type = DestinationType.PLAIN,
                appName = TransportConstants.APP_NAME,
                aspects = arrayOf("tunnel", "synthesize"),
            )
        controlHashes.add(tunnelSynthesizeDestination!!.hash.toKey())
        log("Tunnel synthesize destination: ${tunnelSynthesizeDestination!!.hexHash}")

        initializeManagementDestinations()
    }

    /**
     * Register the probe responder and the remote management endpoint, each behind its own
     * config knob (python Transport.py:367-373 and :511-518).
     *
     * Both are keyed to the transport identity rather than being PLAIN like the control
     * destinations above: their addresses must be unique to this node, because the point of
     * each is to answer questions *about this node*.
     *
     * A local client attached to a shared instance registers neither — the reference gates
     * both on `not Transport.owner.is_connected_to_shared_instance`, and our
     * `probeDestinationEnabled`/`remoteManagementEnabled` fold that check in. Otherwise
     * every client behind one shared instance would answer probes for the same network.
     */
    /** Deregister the probe and remote-management destinations of a node that has become a shared-instance client. */
    private fun dropManagementDestinations() {
        val dropped = mgmtDestinations.toList()
        if (dropped.isEmpty()) return
        for (dest in dropped) {
            try {
                deregisterDestination(dest)
            } catch (e: Exception) {
                log("Could not deregister management destination ${dest.hexHash}: ${e.message}")
            }
        }
        mgmtDestinations.clear()
        probeDestination = null
        remoteManagementDestination = null
        log("Dropped ${dropped.size} management destination(s): this node is a shared-instance client")
    }

    private fun initializeManagementDestinations() {
        mgmtDestinations.clear()
        mgmtHashes.clear()
        probeDestination = null
        remoteManagementDestination = null

        val id = identity ?: return

        if (network.reticulum.Reticulum.remoteManagementEnabled()) {
            val dest = TransportManagement.createRemoteManagementDestination(id)
            remoteManagementDestination = dest
            mgmtDestinations.add(dest)
            mgmtHashes.add(dest.hash)
            log("Enabled remote management on ${dest.hexHash}")
        }

        if (network.reticulum.Reticulum.probeDestinationEnabled()) {
            val dest = TransportManagement.createProbeDestination(id)
            probeDestination = dest
            // python appends the probe destination to mgmt_destinations but NOT to
            // mgmt_hashes (Transport.py:515) — it is announced, not ACL-gated.
            mgmtDestinations.add(dest)
            log("Transport instance will respond to probe requests on ${dest.hexHash}")
        }
    }

    /**
     * Stop the transport system.
     */
    fun stop() {
        if (!started.getAndSet(false)) {
            return
        }

        // Stop coroutine job loop if active
        stopCoroutineJobLoop()
        jobLoopScope = null
        useCoroutineJobLoop = false

        // Stop the inbound drainer and discard whatever it had not reached. `started` is
        // already false, so its loop exits on the next poll; the interrupt only shortens the
        // wait. The in-flight count goes with the queue — anything it counted was either
        // processed or is being thrown away here, and a restart must not inherit it.
        val drainer = inboundThread
        inboundThread = null
        drainer?.interrupt()
        // Bounded join so a drainer mid-packet finishes that packet before the tables
        // below are cleared under it. Skipped when stop() runs ON the drainer — an
        // app callback tearing everything down — where joining would wait on ourselves.
        if (drainer != null && drainer !== Thread.currentThread()) {
            try {
                drainer.join(2_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        inboundQueues?.clear()
        inboundInFlight.set(0)
        inflightPathRequests.clear()

        // Cancel all link watchdog coroutines
        network.reticulum.link.Link
            .cancelAllWatchdogs()

        // Persist data BEFORE clearing tables
        if (transportEnabled) {
            persistData()
        }
        persistDataToStorage()
        network.reticulum.identity.Identity.flushUsedMarkers()

        // Clear all tables
        pathTable.clear()
        pathAlternates.clear()
        linkTable.clear()
        reverseTable.clear()
        announceTable.clear()
        packetHashlist.clear()
        packetHashlistPrev.clear()
        announceAllowedAt.clear()
        // Per-interface announce egress state. Left behind, a restart inherits the
        // previous session's gate: an interface that comes back under the same hash —
        // a mock named the same, or a real interface re-created after an in-process
        // restart — finds announce_allowed_at still hours in the future and emits
        // nothing. The sleeping drain thread from the old session is unaffected by
        // this and simply finds an empty queue when it wakes.
        interfaceAnnounceAllowedAt.clear()
        interfaceAnnounceQueues.clear()
        heldAnnounceEntries.clear()
        // Registered interfaces are dropped with everything else, symmetric with
        // deregisterInterface. Without this a detached interface stays registered
        // across an in-process restart, and findInterfaceByHash happily resolves a
        // reloaded path's interface hash to the dead object: processOutgoing then
        // refuses the packet and the send fails silently. Cleared, the stale hash
        // resolves to null and the existing recovery in processOutbound drops the
        // path and falls back to broadcast. Python has no in-process restart, so
        // there is nothing to diverge from here (exit_handler, Transport.py:3977,
        // voids queues and persists because the process is ending).
        interfaces.clear()
        localClientInterfaces.clear()
        announceRateTable.clear()
        pathRequests.clear()
        synchronized(discoveryPrTagsLock) { discoveryPrTags.clear(); discoveryPrTagsPrev.clear() }
        discoveryPathRequests.clear()
        synchronized(receipts) {
            receipts.clear()
            receiptCount = 0
        }
        pendingProofCallbacks.clear()
        announceHandlers.clear()
        knownAspects.clear()
        selfAnnounceHashes.clear()
        tearDownLinksForShutdown()
        pendingLinks.clear()
        activeLinks.clear()
        tunnels.clear()
        tunnelInterfaces.clear()
        // Blackhole state must not leak across instances (singleton reset).
        blackholedIdentities.clear()
        blackholeSources.clear()
        // Config-derived ACL / discovery-source lists must not leak across
        // singleton restarts either (the conformance bridge starts a fresh
        // Reticulum per test in the same JVM).
        remoteManagementAllowed.clear()
        mgmtDestinations.clear()
        mgmtHashes.clear()
        probeDestination = null
        remoteManagementDestination = null
        interfaceDiscoverySources.clear()
        blackholeLastChecked = 0

        // Stop discovery
        interfaceAnnouncer?.stop()
        interfaceAnnouncer = null
        discoveryHandler?.stop()
        discoveryHandler = null

        // Reset speed tracking
        speedRx = 0
        speedTx = 0
        lastTrafficSnapshot = Pair(0L, 0L)
        lastTrafficTime = 0L

        // Cancel and shut down the packet-receipt callback executor; any
        // callbacks that have been submitted but not yet started are dropped.
        receiptCallbackExecutor?.shutdownNow()
        receiptCallbackExecutor = null

        log("Transport stopped")
    }

    /**
     * Set the cache path for persistent announce storage.
     * Called by Reticulum during initialization.
     */
    internal fun setCachePath(path: String) {
        cachePath = path
    }

    /**
     * Set the storage path for persistent data.
     * Called by Reticulum during initialization.
     */
    internal fun setStoragePath(path: String) {
        storagePath = path
    }

    // ===== Pluggable Storage Backends =====

    /** Persistent path table storage. When null, falls back to file-based persistence. */
    var pathStore: network.reticulum.storage.PathStore? = null

    /** Persistent packet hashlist storage. When null, falls back to file-based persistence. */
    var packetHashStore: network.reticulum.storage.PacketHashStore? = null

    /** Persistent tunnel table storage. When null, falls back to file-based persistence. */
    var tunnelStore: network.reticulum.storage.TunnelStore? = null

    /** Persistent announce cache storage. When null, falls back to file-based persistence. */
    var announceStore: network.reticulum.storage.AnnounceStore? = null

    /** Persistent discovery storage. When null, falls back to file-based persistence. */
    var discoveryStore: network.reticulum.storage.DiscoveryStore? = null

    /** Persistent per-destination inbound ratchet storage. When null, falls back to file-based persistence. */
    var destinationRatchetStore: network.reticulum.storage.DestinationRatchetStore? = null

    // ===== Memory Management =====

    /**
     * Trim memory usage for low-memory situations.
     *
     * This method reduces memory consumption by:
     * - Clearing the byte array pool
     * - Trimming hashlists to minimum required
     * - Clearing stale entries from tables
     *
     * Call this when Android reports onTrimMemory() or similar.
     */
    fun trimMemory() {
        log("Trimming memory...")

        // Clear byte array pool
        network.reticulum.common.ByteArrayPool
            .clear()

        // Trim packet hashlists (keep recent, drop old)
        val now = System.currentTimeMillis()
        val maxHashlistSize = network.reticulum.common.Platform.recommendedHashlistSize / 2

        if (packetHashlist.size > maxHashlistSize) {
            val toRemove = packetHashlist.size - maxHashlistSize
            val iterator = packetHashlist.iterator()
            repeat(toRemove) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }

        // Clear previous hashlist entirely during memory pressure
        packetHashlistPrev.clear()

        // Trim announce table to reduce memory
        if (announceTable.size > 1000) {
            val expireThreshold = now - TransportConstants.DESTINATION_TIMEOUT
            announceTable.entries.removeIf { (_, entry) ->
                entry.timestamp < expireThreshold
            }
        }

        // Force garbage collection hint
        System.gc()

        log("Memory trimmed: hashlist=${packetHashlist.size}, announces=${announceTable.size}")
    }

    /**
     * Aggressive memory trimming for critical low-memory situations.
     *
     * This clears more aggressively than trimMemory(), potentially
     * affecting network performance temporarily.
     */
    fun aggressiveTrimMemory() {
        log("Aggressive memory trim...")

        // Clear byte array pool
        network.reticulum.common.ByteArrayPool
            .clear()

        // Clear all hashlists (will cause some duplicate packet processing)
        packetHashlist.clear()
        packetHashlistPrev.clear()

        // Clear path requests (will be re-requested as needed)
        pathRequests.clear()
        synchronized(discoveryPrTagsLock) { discoveryPrTags.clear(); discoveryPrTagsPrev.clear() }
        discoveryPathRequests.clear()

        // Clear held announces (will be re-announced)
        heldAnnounceEntries.clear()

        // Clear announce rate table
        announceRateTable.clear()

        // Clear interface announce queues
        interfaceAnnounceQueues.clear()

        // Force GC
        System.gc()

        log("Aggressive memory trim complete")
    }

    /**
     * Get current memory statistics.
     */
    fun getMemoryStats(): MemoryStats =
        MemoryStats(
            pathTableSize = pathTable.size,
            linkTableSize = linkTable.size,
            announceTableSize = announceTable.size,
            packetHashlistSize = packetHashlist.size,
            // The global announce queue was removed (announces are queued per-interface);
            // the field is kept because MemoryStats is public. It was always 0.
            queuedAnnouncesSize = 0,
            tunnelsSize = tunnels.size,
            byteArrayPoolBytes =
                network.reticulum.common.ByteArrayPool
                    .pooledBytes(),
            heapUsedBytes = network.reticulum.common.Platform.usedHeapMemory,
            heapMaxBytes = network.reticulum.common.Platform.maxHeapMemory,
        )

    /**
     * Memory statistics for monitoring.
     */
    data class MemoryStats(
        val pathTableSize: Int,
        val linkTableSize: Int,
        val announceTableSize: Int,
        val packetHashlistSize: Int,
        val queuedAnnouncesSize: Int,
        val tunnelsSize: Int,
        val byteArrayPoolBytes: Long,
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
    ) {
        val heapUsedPercent: Int
            get() = if (heapMaxBytes > 0) ((heapUsedBytes * 100) / heapMaxBytes).toInt() else 0
    }

    // ===== Network State Management =====

    /**
     * Data saver mode flag.
     * When enabled, reduces network traffic for metered connections.
     */
    @Volatile
    private var dataSaverMode = false

    /**
     * Network availability flag.
     */
    @Volatile
    private var networkAvailable = true

    /**
     * Enable or disable data saver mode.
     *
     * When enabled, Transport will:
     * - Reduce announce rebroadcast rate
     * - Defer non-critical operations
     * - Reduce keepalive frequency
     *
     * @param enabled true to enable data saver mode
     */
    fun setDataSaverMode(enabled: Boolean) {
        if (dataSaverMode != enabled) {
            dataSaverMode = enabled
            if (enabled) {
                log("Data saver mode enabled - reducing network activity")
            } else {
                log("Data saver mode disabled - resuming normal activity")
            }
        }
    }

    /**
     * Check if data saver mode is enabled.
     */
    fun isDataSaverMode(): Boolean = dataSaverMode

    /**
     * Called when network becomes available.
     * Resumes any paused network operations.
     */
    fun onNetworkAvailable() {
        if (!networkAvailable) {
            networkAvailable = true
            log("Network available - resuming operations")
            // Could trigger path re-discovery or interface reconnection here
        }
    }

    /**
     * Called when network is lost.
     * Pauses network operations to conserve resources.
     */
    fun onNetworkLost() {
        if (networkAvailable) {
            networkAvailable = false
            log("Network lost - pausing operations")
            // Could pause announce propagation, path requests, etc.
        }
    }

    /**
     * Check if network is currently available.
     */
    fun isNetworkAvailable(): Boolean = networkAvailable

    /**
     * Doze mode flag.
     * When enabled, Transport operates in maintenance-window mode.
     */
    @Volatile
    private var dozeMode = false

    /**
     * Enable or disable Doze mode handling.
     *
     * When enabled, Transport will:
     * - Queue non-critical operations for maintenance windows
     * - Reduce background activity
     * - Prioritize essential traffic only
     *
     * @param enabled true when device is in Doze mode
     */
    fun setDozeMode(enabled: Boolean) {
        if (dozeMode != enabled) {
            dozeMode = enabled
            if (enabled) {
                log("Doze mode enabled - reducing to maintenance windows")
            } else {
                log("Doze mode disabled - resuming normal activity")
            }
        }
    }

    /**
     * Check if Doze mode handling is enabled.
     */
    fun isDozeMode(): Boolean = dozeMode

    // ===== Interface Management =====

    /**
     * Register an interface with transport.
     * Automatically detects and tracks local client interfaces (Python RNS compatibility).
     */
    fun registerInterface(interfaceRef: InterfaceRef) {
        // Idempotent, as the reference is:
        //
        //     def add_interface(interface):
        //         with Transport.interfaces_lock:
        //             if not interface in Transport.interfaces:
        //                 Transport.interfaces.append(interface)
        //
        // The membership check was dropped in the port, which was harmless only while
        // nothing registered the same interface twice. Spawned server children now
        // register themselves, and several consumers already register them from the
        // outside, so without this an interface lands in `interfaces` twice and every
        // broadcast iterates it twice — one transmit per duplicate.
        //
        // Identity comparison is the right one here: `Interface.toRef()` caches its
        // adapter (InterfaceAdapter.getOrCreate), so both registrations of one
        // interface pass the same InterfaceRef instance.
        if (interfaceRef in interfaces) return

        interfaces.add(interfaceRef)

        // Python: Track interfaces spawned by local shared instance server
        // Matches Python logic: if interface.parent_interface?.is_local_shared_instance
        if (interfaceRef.parentInterface?.isLocalSharedInstance == true) {
            localClientInterfaces.add(interfaceRef)
            log("Registered local client interface: ${interfaceRef.name}")
            // NOTE: the cached-announce replay is NOT triggered here — registration happens
            // before the interface is started/online (issue #74 ordering), so it can't yet
            // receive. The spawning server calls replayCachedAnnouncesToLocalClient() after
            // start() instead.
        } else if (interfaceRef.isConnectedToSharedInstance) {
            // Client connecting TO shared instance (not spawned BY server).
            //
            // Flip the global Transport.isConnectedToSharedInstance flag here so
            // that callers who instantiate a LocalClientInterface directly
            // (e.g. Carina's ReticulumService, Eridanus' shared-instance
            // attach, the conformance wire bridge) get the same behavior as
            // the factory-driven Reticulum.start(connectToSharedInstance=true)
            // path. Without this, processOutbound's `hops == 1 +
            // isConnectedToSharedInstance + isHeader1` branch (line ~3088)
            // never fires for manually-constructed clients, the outbound
            // LINKREQUEST is sent as HEADER_1 instead of HEADER_2 with the
            // master's identity as transport_id, and the master is forced
            // to compensate with the H1→H2 raw mutation — which then
            // produces a divergent link_id (see reticulum-kt issue catalogued
            // in reticulum-conformance test_link_via_shared_master.py).
            // Python's equivalent flag (Reticulum.py:417
            // is_connected_to_shared_instance = True) lives on the
            // Reticulum singleton and is set inside __start_local_interface
            // regardless of how the interface was instantiated; pinning the
            // kotlin flag at registerInterface time gives us the same
            // implementation-agnostic guarantee.
            //
            // Serialize the flag write against deregisterInterface's
            // compound check-then-clear; see sharedInstanceFlagLock kdoc.
            synchronized(sharedInstanceFlagLock) {
                isConnectedToSharedInstance = true
            }
            log("Registered interface connected to shared instance: ${interfaceRef.name}")
            // python decides at attach time (Reticulum.py:437-440) that a client of a
            // shared instance has no probe or management destination; here start() ran
            // before the local client attached, so they were built and are taken down
            // now.
            dropManagementDestinations()
        } else {
            log("Registered interface: ${interfaceRef.name}")
        }
    }

    /**
     * Deregister an interface.
     * Cleans up associated statistics and announce queues.
     */
    fun deregisterInterface(interfaceRef: InterfaceRef) {
        interfaces.remove(interfaceRef)
        localClientInterfaces.remove(interfaceRef)

        // Clean up interface-related data
        val interfaceHash = ByteArrayKey(interfaceRef.hash)
        interfaceStats.remove(interfaceHash)
        interfaceAnnounceQueues.remove(interfaceHash)
        interfaceAnnounceAllowedAt.remove(interfaceHash)

        // Symmetric with registerInterface: if the deregistered interface was
        // our last shared-instance client attachment, clear the global flag.
        // Python clears equivalent state on the failure paths in
        // __start_local_interface (Reticulum.py:425-427, 433-435); the kotlin
        // analog runs when an app explicitly tears down its shared-instance
        // client interface (e.g. Carina toggling between hosting / consuming
        // the shared instance on the same process).
        //
        // The check-then-clear pair is compound (interfaces.remove above and
        // the interfaces.none scan below are two separate operations on a
        // CopyOnWriteArrayList), so serialize against registerInterface's
        // matching flag-set under sharedInstanceFlagLock. Without this, a
        // concurrent registerInterface that adds a new shared-instance
        // interface and sets the flag true between `interfaces.remove(...)`
        // and the `interfaces.none {...}` scan could be clobbered by the
        // trailing `isConnectedToSharedInstance = false` — exactly the
        // false-negative-flag state this PR was originally fixing.
        if (interfaceRef.isConnectedToSharedInstance) {
            synchronized(sharedInstanceFlagLock) {
                if (interfaces.none { it.isConnectedToSharedInstance }) {
                    isConnectedToSharedInstance = false
                }
            }
        }

        log("Deregistered interface: ${interfaceRef.name}")
    }

    /**
     * Get all registered interfaces.
     */
    fun getInterfaces(): List<InterfaceRef> = interfaces.toList()

    // ===== Destination Management =====

    /**
     * Register a destination with transport.
     */
    fun registerDestination(destination: Destination) {
        // Only IN (incoming/local) destinations belong in the local-destination table.
        // Python's register_destination (Transport.py:2898) appends only when
        // direction == IN; Destination.__init__ calls it unconditionally and the impl
        // filters. The port kept the unconditional call but dropped the filter, so OUT
        // destinations (remote peers we send to) polluted `destinations` and were
        // mis-classified as local -- suppressing their announces (isLocalDestination
        // skip) and corrupting the announce-forward / path-response local checks.
        if (destination.direction != DestinationDirection.IN) return

        // Prevent duplicate registration (matches Python Transport.py:2223-2225)
        val key = destination.hash.toKey()
        if (destinationIndex.containsKey(key)) {
            return
        }
        destinations.add(destination)
        destinationIndex[key] = destination
        log("Registered destination: ${destination.hexHash}")
    }

    /**
     * Deregister a destination.
     */
    fun deregisterDestination(destination: Destination) {
        destinations.remove(destination)
        // Remove from the index only if it still maps to this exact destination.
        destinationIndex.remove(destination.hash.toKey(), destination)
        log("Deregistered destination: ${destination.hexHash}")
    }

    /**
     * Get all registered destinations.
     * Used by Identity.recall() for fallback lookups.
     */
    internal fun getDestinations(): List<Destination> = destinations.toList()

    /**
     * Find a registered destination by hash.
     */
    fun findDestination(hash: ByteArray): Destination? {
        return destinationIndex[hash.toKey()]
    }

    // ===== Receipt Tracking =====

    /**
     * Register a packet receipt for timeout tracking and proof handling.
     * Called automatically when packets are sent with createReceipt=true.
     */
    fun registerReceipt(receipt: PacketReceipt) {
        // Keyed by TRUNCATED hash — proof packets carry the original packet's
        // truncated hash as their destination, matching Python's get_hash() usage.
        // A re-registration under the same truncated hash (resend with a fresh
        // receipt) APPENDS: Python tracks both receipts.
        synchronized(receipts) {
            receipts.getOrPut(receipt.truncatedHash.toKey()) { ArrayList(1) }.add(receipt)
            receiptCount++
        }
        logDebug { "Registered receipt for ${receipt.truncatedHash.toHexString()}" }
    }

    /**
     * Find a receipt by packet hash (full or truncated). With several receipts under
     * the same hash the oldest is returned, as the former list scan did.
     */
    fun findReceipt(packetHash: ByteArray): PacketReceipt? {
        val truncated =
            if (packetHash.size > RnsConstants.TRUNCATED_HASH_BYTES) {
                packetHash.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)
            } else {
                packetHash
            }
        return synchronized(receipts) {
            receipts[truncated.toKey()]?.firstOrNull {
                it.hash.contentEquals(packetHash) || it.truncatedHash.contentEquals(packetHash)
            }
        }
    }

    // ===== Announce Handlers =====

    /**
     * Register an announce handler.
     *
     * @param handler The handler to call when announces arrive
     * @param aspectFilter If non-null, only call this handler for announces whose
     *   destination hash matches `Destination.hashFromNameAndIdentity(aspectFilter, identity)`.
     *   If null, the handler receives all announces. Matches Python's `handler.aspect_filter`.
     */
    fun registerAnnounceHandler(
        handler: AnnounceHandler,
        aspectFilter: String? = null,
    ) {
        announceHandlers.add(RegisteredHandler(handler, aspectFilter))
        if (aspectFilter != null) knownAspects.add(aspectFilter)
    }

    /**
     * Deregister an announce handler.
     */
    fun deregisterAnnounceHandler(handler: AnnounceHandler) {
        // Collect the aspect filters of the handlers being removed
        val removedAspects = announceHandlers
            .filter { it.handler === handler }
            .mapNotNull { it.aspectFilter }

        announceHandlers.removeIf { it.handler === handler }

        // Remove aspects from knownAspects only if no remaining handler uses them
        for (aspect in removedAspects) {
            val stillUsed = announceHandlers.any { it.aspectFilter == aspect }
            if (!stillUsed) {
                knownAspects.remove(aspect)
            }
        }
    }

    // ===== Interface Discovery =====

    /**
     * Whether a network identity has been configured (for encrypted discovery).
     */
    fun hasNetworkIdentity(): Boolean = networkIdentity != null

    /**
     * Enable interface discovery announcing for discoverable interfaces.
     * Creates an InterfaceAnnouncer that periodically sends discovery announces.
     */
    fun enableDiscovery() {
        if (interfaceAnnouncer == null) {
            interfaceAnnouncer = network.reticulum.discovery.InterfaceAnnouncer()
            interfaceAnnouncer?.start()
            log("Interface discovery announcing enabled")
        }
    }

    fun disableDiscovery() {
        interfaceAnnouncer?.stop()
        interfaceAnnouncer = null
        discoveryHandler?.stop()
        discoveryHandler = null
        log("Interface discovery disabled")
    }

    fun isDiscoveryEnabled(): Boolean = interfaceAnnouncer != null

    /**
     * Start listening for interface discovery announces.
     *
     * @param requiredValue Minimum PoW stamp value to accept
     * @param discoverySources If non-null, only accept announces from these identity hashes
     * @param autoConnectFactory Factory to create interfaces from discoveries (null = no auto-connect)
     * @param maxAutoConnected Maximum number of auto-connected interfaces
     * @param callback Called for each valid discovery
     */
    fun discoverInterfaces(
        requiredValue: Int = network.reticulum.discovery.DiscoveryConstants.DEFAULT_STAMP_VALUE,
        discoverySources: Set<ByteArrayKey>? = null,
        autoConnectFactory: ((network.reticulum.discovery.DiscoveredInterface) -> InterfaceRef?)? = null,
        maxAutoConnected: Int = 0,
        callback: ((network.reticulum.discovery.DiscoveredInterface) -> Unit)? = null,
    ) {
        if (discoveryHandler == null) {
            discoveryHandler =
                network.reticulum.discovery.InterfaceDiscovery(
                    storagePath = storagePath,
                    requiredValue = requiredValue,
                    discoverySources = discoverySources,
                    autoConnectFactory = autoConnectFactory,
                    maxAutoConnected = maxAutoConnected,
                    discoveryCallback = callback,
                    discoveryStore = discoveryStore,
                )
            discoveryHandler?.start()
            log("Interface discovery listening enabled")
        }
    }

    /**
     * List discovered interfaces (from disk persistence).
     * Returns list of (DiscoveredInterface, status) pairs.
     */
    fun listDiscoveredInterfaces(): List<Pair<network.reticulum.discovery.DiscoveredInterface, String>> = discoveryHandler?.listDiscovered() ?: emptyList()

    /**
     * Get the set of currently auto-connected endpoint strings ("host:port").
     */
    fun getAutoconnectedEndpoints(): Set<String> = discoveryHandler?.getAutoconnectedEndpoints() ?: emptySet()

    /**
     * Update the auto-connect limit at runtime without restarting.
     */
    fun setMaxAutoConnected(count: Int) {
        discoveryHandler?.setMaxAutoConnected(count)
    }

    // ===== Link Management =====

    /**
     * Number of currently half-open (pending / unproven) links. Used by
     * Link.validateRequest to bound half-open links against a LINKREQUEST flood.
     *
     * Counts initiator links still in [pendingLinks] AND receiver-side links that
     * [registerLink] placed straight into [activeLinks] (python parity) but which
     * have not reached ACTIVE yet. Without the second term the cap never saw the
     * very links a LINKREQUEST flood creates, so it bounded nothing.
     */
    fun pendingLinkCount(): Int =
        pendingLinks.size + activeLinks.count { !it.initiator && it.status != LinkConstants.ACTIVE }

    /**
     * Register a link (pending or active).
     */
    fun registerLink(link: Any) {
        // The parameter stays `Any` for API stability; only Link is ever registered.
        if (link !is Link) {
            log("registerLink: ignoring non-Link registrant ${link::class.java.name}")
            return
        }
        val linkId = link.linkId
        // Python Transport.py:2244-2247: initiator links go to pending_links,
        // non-initiator (server) links go to active_links
        if (link.initiator) {
            pendingLinks.add(link)
            log("Registered pending link: ${linkId.toHexString()}")
        } else {
            activeLinks.add(link)
            log("Registered active link: ${linkId.toHexString()}")
        }
    }

    /**
     * Activate a link (move from pending to active).
     */
    fun activateLink(link: Any) {
        if (link !is Link) {
            log("activateLink: ignoring non-Link ${link::class.java.name}")
            return
        }
        pendingLinks.remove(link)
        activeLinks.add(link)
        log("Activated link: ${link.linkId.toHexString()}")
    }

    /**
     * Register a path entry for a link so that outbound packets use the correct interface.
     * This should be called when a link is established with the receiving interface hash.
     *
     * The `hops` parameter (the traversed hop count of the establishment packet) only
     * sizes the entry's lifetime; the stored path entry always uses `hops = 1`.
     *
     * Lifetime / persistence: the entry is an in-memory routing hint for the link's
     * establishment window only. It expires on the link establishment timeout
     * (ESTABLISHMENT_TIMEOUT_PER_HOP * hops + KEEPALIVE, the same figure
     * Link.validateRequest computes), is never written to [pathStore], and is removed
     * by [deregisterLink]. Python never puts link ids in path_table at all; an
     * unauthenticated LINKREQUEST must not buy a 7-day persisted path row. After
     * expiry, [outbound] pins link packets to the link's attached interface in its
     * broadcast branch (python Transport.py:1454), so established links keep working.
     *
     * Link DATA packets must never be HEADER_2-wrapped: their `destination_hash` IS the
     * `linkId`, which no transport node's identity matches. Any HEADER_2 wrap that uses
     * `nextHop = linkId` as `transport_id` is dropped by the intermediate transport as
     * "in transport for other transport instance" (see Python `Transport.py:1428`).
     *
     * This is enforced by TWO checks — belt and suspenders:
     *  1. `hops = 1` here, so [outbound] won't take the `hops > 1` HEADER_2 branch.
     *  2. An `isLink` guard in [outbound] that also skips the shared-instance HEADER_2
     *     wrap branch (`hops == 1 + isConnectedToSharedInstance`), which would
     *     otherwise still re-wrap with `nextHop = linkId`.
     *
     * Net effect: link DATA always goes on the HEADER_1 path. Intermediate transports
     * (both Python and Kotlin) forward link DATA by looking `linkId` up in their own
     * [linkTable], which is populated during LINKREQUEST / LRPROOF traversal. Python
     * RNS never adds link_id entries to its `path_table` at all — this is the closest
     * Kotlin-side equivalent given the existing PathEntry-centric routing primitives.
     */
    fun registerLinkPath(
        linkId: ByteArray,
        receivingInterfaceHash: ByteArray,
        hops: Int = 1,
    ) {
        val now = System.currentTimeMillis()
        val establishmentTimeout =
            LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP * maxOf(1, hops) + LinkConstants.KEEPALIVE
        val entry =
            PathEntry(
                timestamp = now,
                // `nextHop` is not read by [outbound] on the paths link DATA takes
                // (direct transmit uses `packet.raw`), but it's logged, so keep it
                // as linkId for symmetry with the entry's key.
                nextHop = linkId,
                // Always 1 — see doc above for why.
                hops = 1,
                expires = now + establishmentTimeout,
                randomBlobs = mutableListOf(),
                receivingInterfaceHash = receivingInterfaceHash,
                announcePacketHash = linkId,
            )
        pathTable[linkId.toKey()] = entry
        // Deliberately NOT persisted to pathStore — see the doc comment above.
        log("Registered link path for ${linkId.toHexString()} via interface ${receivingInterfaceHash.toHexString()} (expires in ${establishmentTimeout}ms)")
    }

    /**
     * Deregister a link.
     *
     * If the link was removed from [pendingLinks] (i.e. it never activated) and was
     * torn down due to establishment timeout, and we are an endpoint (not a transport
     * node), expire the path to the destination and kick off a fresh path request.
     * Mirrors Python `Transport.py:498-522`: if a leaf node can't establish a link
     * over its cached path, the path is almost certainly stale, so invalidate it and
     * rediscover. The path is expired unconditionally; the rediscovery [requestPath]
     * is rate-limited via [TransportConstants.PATH_REQUEST_MI] here at the call site
     * (Python guards it at `Transport.py:516`) so repeat failures on the same
     * destination don't spam the network. [requestPath] itself now sends
     * unconditionally for Python parity, so the throttle must live here.
     *
     * Transport nodes skip path expiry: they forward for unrelated clients and
     * shouldn't churn their path table on downstream failures (Python guard at
     * `Transport.py:477`).
     */
    fun deregisterLink(link: Any) {
        if (link !is Link) {
            log("deregisterLink: ignoring non-Link ${link::class.java.name}")
            return
        }
        val wasPending = pendingLinks.remove(link)
        activeLinks.remove(link)
        // Drop the link-id path entry registered by registerLinkPath (python never has
        // one). The pathStore removal also clears rows persisted by earlier builds that
        // wrote link ids to the store.
        pathTable.remove(link.linkId.toKey())
        pathStore?.removePath(link.linkId)
        log("Deregistered link: ${link.linkId.toHexString()}")

        if (wasPending && !transportEnabled &&
            link.initiator &&
            link.teardownReason == LinkConstants.TEARDOWN_REASON_TIMEOUT
        ) {
            val destHash = link.destination?.hash ?: return
            log(
                "Pending link to ${destHash.toHexString()} never established; " +
                    "expiring path and requesting rediscovery",
            )
            expirePath(destHash)
            // requestPath no longer self-throttles (Python parity); apply the
            // PATH_REQUEST_MI rate-limit for this automated rediscovery here at the
            // call site, matching the jobloop pending-link handler
            // (Python Transport.py:505-520).
            val lastRequest = pathRequests[destHash.toKey()]
            if (lastRequest == null ||
                System.currentTimeMillis() - lastRequest > TransportConstants.PATH_REQUEST_MI
            ) {
                requestPath(destHash)
            }
        }
    }

    /**
     * A link this node was transporting for someone else timed out before it validated.
     *
     * The transport-node half of the stale pending-link handling, mirroring python
     * `Transport.py:884-950`. [deregisterLink] ports the other half, the leaf-node arm at
     * `Transport.py:498-522`, which runs when a link *we* initiated fails; the two are
     * separate and neither substitutes for the other.
     *
     * Four arms, in the reference's order, and only the last two mark:
     *  - the path vanished while the link request was in flight: rediscover.
     *  - the request came from a local client (`takenHops == 0`): rediscover.
     *  - the destination was one hop away, so it was local to one of our interfaces and
     *    has probably roamed: rediscover, and mark the old path unresponsive.
     *  - the initiator was one hop away, so the topology moved: same.
     *
     * Marking is gated on this node being a transport node and on the receiving interface
     * not being a boundary, exactly as the reference gates it (`Transport.py:927, :942`).
     * A boundary interface exists to keep two networks apart, so a failure across it says
     * nothing about the path on our side.
     *
     * Without this, [markPathUnresponsive] has no caller in the library at all: the path
     * table's failure count never leaves zero, no path ever leaves ACTIVE, and the
     * worse-hop announce branch that depends on [isPathUnresponsive] can never fire on a
     * running node. The function and that branch are covered by the conformance suite,
     * which drives the mark directly through the bridge, so what was missing is the
     * wiring rather than the behaviour.
     */
    private fun handleStaleTransportedLink(
        linkEntry: LinkEntry,
        now: Long,
    ) {
        val destHash = linkEntry.destinationHash
        val lastRequest = pathRequests[destHash.toKey()] ?: 0L
        val throttled = now - lastRequest < TransportConstants.PATH_REQUEST_MI

        var shouldRequest = false
        var blockedInterface: InterfaceRef? = null

        when {
            !hasPath(destHash) -> {
                log(
                    "Rediscovering path to ${destHash.toHexString()}: a transported link " +
                        "never established and the path is now missing",
                )
                shouldRequest = true
            }

            throttled -> return // every remaining arm is throttled; nothing to do

            linkEntry.takenHops == 0 -> {
                log(
                    "Rediscovering path to ${destHash.toHexString()}: a transported link " +
                        "from a local client never established",
                )
                shouldRequest = true
            }

            hopsTo(destHash) == 1 || linkEntry.takenHops == 1 -> {
                log(
                    "Rediscovering path to ${destHash.toHexString()}: a transported link " +
                        "never established and the destination or its initiator was local " +
                        "to one of our interfaces",
                )
                shouldRequest = true
                blockedInterface = findInterfaceByHash(linkEntry.receivingInterfaceHash)
                if (transportEnabled && blockedInterface?.mode != InterfaceMode.BOUNDARY) {
                    markPathUnresponsive(destHash)
                }
            }
        }

        if (!shouldRequest) return

        // The reference queues these and later requests on every interface except the one
        // the failure came in on (`Transport.py:1229-1232, 1259-1263`). Asking the same
        // interface again would re-learn the path that just failed.
        if (blockedInterface == null) {
            requestPath(destHash)
        } else {
            for (iface in interfaces) {
                if (!iface.hash.contentEquals(blockedInterface.hash)) {
                    requestPath(destHash, onInterface = iface)
                }
            }
        }
    }

    /**
     * Find a link by ID.
     */
    fun findLink(linkId: ByteArray): Any? {
        val key = linkId.toKey()
        return activeLinks.find { it.linkId.toKey() == key }
            ?: pendingLinks.find { it.linkId.toKey() == key }
    }

    // ===== Receipt Management =====

    /**
     * Register a callback for when a proof is received for a packet.
     *
     * @param packetHash The hash of the packet to wait for proof
     * @param callback The callback to invoke when proof is received
     */
    fun registerReceipt(
        packetHash: ByteArray,
        callback: ProofCallback,
    ) {
        pendingProofCallbacks[packetHash.toKey()] = callback
        logDebug { "Registered receipt for ${packetHash.toHexString()}" }
    }

    /**
     * Deregister a pending proof callback registered via [registerReceipt] (packetHash, callback).
     *
     * @param packetHash The packet hash to stop waiting for
     */
    fun deregisterReceipt(packetHash: ByteArray) {
        pendingProofCallbacks.remove(packetHash.toKey())
    }

    // ===== Path Table Operations =====

    /**
     * True when [entry]'s receiving interface no longer exists while other
     * interfaces are registered — i.e. a restored path pointing at a dead
     * interface. Such an entry is not usable and must not satisfy [hasPath],
     * [hopsTo] or [nextHop]. Mirrors Python's load-time interface validation
     * (`Transport.py:284-298`, "the interface is no longer available"); kotlin
     * restores persisted paths eagerly and validates lazily (see
     * `port-deviations.md`).
     *
     * Guarded on [interfaces].isNotEmpty() to preserve the
     * restore-before-interfaces-register window — the same guard [savePathTable]
     * uses, mirroring `Transport.py:2905-2910`. This check is non-destructive;
     * [cullTables] prunes dangling entries after the startup grace period.
     */
    private fun isDanglingPath(entry: PathEntry): Boolean =
        interfaces.isNotEmpty() && findInterfaceByHash(entry.receivingInterfaceHash) == null

    /**
     * Check if a usable path exists to a destination.
     */
    fun hasPath(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false
        if (entry.isExpired()) return false
        return !isDanglingPath(entry)
    }

    /**
     * Check whether a discovery path request is currently pending for a
     * destination, meaning this transport has forwarded (or is about to
     * forward) a path request for that destination on behalf of another
     * peer. Observable proof that DISCOVER_PATHS_FOR gating allowed the
     * forward for the receiving interface's mode.
     *
     * Exposed primarily for the conformance bridge; production callers
     * typically don't need this.
     */
    fun hasDiscoveryPathRequest(destinationHash: ByteArray): Boolean =
        discoveryPathRequests.containsKey(destinationHash.toKey())

    /**
     * Emit a path-request packet for `destinationHash`.
     *
     * Retained as a source-compatible alias for the conformance bridge.
     * [requestPath] now sends unconditionally (Python parity — the early-skip
     * guards it used to carry were a deviation and have been removed), so the
     * two are equivalent; new callers should use [requestPath] directly.
     */
    @Deprecated(
        "requestPath now sends unconditionally (Python parity); call it directly.",
        ReplaceWith("requestPath(destinationHash)"),
    )
    fun sendPathRequestUnconditional(destinationHash: ByteArray) {
        requestPath(destinationHash)
    }

    /**
     * Get hop count to a destination.
     *
     * @return Hop count, or null if no path
     */
    fun hopsTo(destinationHash: ByteArray): Int? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        if (isDanglingPath(entry)) return null
        return entry.hops
    }

    /**
     * Get next hop for a destination.
     *
     * @return Next hop transport ID (16 bytes), or null if no path
     */
    fun nextHop(destinationHash: ByteArray): ByteArray? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        if (isDanglingPath(entry)) return null
        return entry.nextHop.copyOf()
    }

    /**
     * Expire (remove) a path.
     */
    fun expirePath(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        pathTable.remove(key)
        pathAlternates.remove(key)
        pathStore?.removePath(destinationHash)
    }

    // ---- Reachability set (multi-link failover, phase B.1/B.2) ---------------------------
    //
    // The reference keeps exactly one path per destination and discards the loser of every
    // comparison, so a node that can reach a destination two ways forgets one of them and
    // has nothing to fall back to when the other dies. These are the rows it threw away.
    //
    // Deliberately a SEPARATE map rather than turning pathTable into a set. pathTable is
    // read at 55 sites in this file alone and 60 more in tests, all of them expecting one
    // entry, and the selected row genuinely is that entry. Widening the type would churn
    // every one of those for no behaviour gain, and the public accessors — hasPath, hopsTo,
    // nextHop, nextHopInterface* — must keep returning the selected row regardless. Keeping
    // pathTable as the selected view means those sites and those accessors do not move.
    //
    // The cost is two structures to hold in step. Every place that drops a destination's
    // path drops its alternates in the same breath: expirePath here, the cull, and the
    // dangling-row prune. There is no path by which an alternate outlives its destination.
    //
    // Nothing selects from this set yet. B.2 fills it, B.3 selects, A.2 uses the selection.
    // Until B.3 lands this is observation only, and behaviour is exactly the reference's.

    /**
     * Alternate rows per destination, one per interface, never including the selected row.
     *
     * Bounded twice over: an interface can hold at most one row, and [MAX_ALTERNATE_ROWS]
     * caps the total. A destination is therefore bounded by the number of registered
     * interfaces, which the node controls — an announcing peer cannot inflate it, because
     * the key is the receiving interface and not anything the peer chooses. That is the
     * answer to the state-exhaustion question the plan raises.
     */
    private val pathAlternates = ConcurrentHashMap<ByteArrayKey, MutableList<PathEntry>>()

    /**
     * Record a path this node learned but did not select.
     *
     * Called where the reference drops the losing announce. A row is an alternate only if
     * it arrived on a different interface than the selected row: two announces over the
     * same interface are the same way of reaching the destination, and keeping both would
     * be a second row that fails at the same moment as the first.
     */
    private fun recordAlternatePath(
        destinationHash: ByteArray,
        candidate: PathEntry,
    ) {
        val key = destinationHash.toKey()
        val selected = pathTable[key] ?: return
        if (selected.receivingInterfaceHash.contentEquals(candidate.receivingInterfaceHash)) return
        if (findInterfaceByHash(candidate.receivingInterfaceHash) == null) return

        val rows = pathAlternates.computeIfAbsent(key) { CopyOnWriteArrayList() }
        var firstForDestination = false
        synchronized(rows) {
            val existing = rows.indexOfFirst {
                it.receivingInterfaceHash.contentEquals(candidate.receivingInterfaceHash)
            }
            when {
                existing >= 0 -> rows[existing] = candidate
                rows.size < TransportConstants.MAX_ALTERNATE_ROWS -> {
                    firstForDestination = rows.isEmpty()
                    rows.add(candidate)
                }
                else -> {
                    // Full: replace the oldest, so a live interface can still take a slot
                    // from one that has stopped announcing.
                    val oldest = rows.indices.minByOrNull { rows[it].timestamp } ?: return
                    if (rows[oldest].timestamp < candidate.timestamp) rows[oldest] = candidate
                }
            }
        }

        // Logged on the 0 -> 1 transition only, and outside the lock.
        //
        // This branch runs for every losing announce, on a node whose path table reaches
        // thousands of destinations, so a line per call would bury the one line that
        // matters. What matters is that a destination became reachable two ways at all:
        // until something selects from this set, that is the only evidence the mechanism
        // is alive on a real node rather than only in a test.
        //
        // It is deliberately paired with [alternatePathStats]. A count alone cannot tell
        // "never learned" from "learned, then pruned away" -- both read zero -- and those
        // are opposite defects. This line fires in the second case and not the first.
        if (firstForDestination) {
            val via = findInterfaceByHash(candidate.receivingInterfaceHash)?.name ?: "unknown"
            log(
                "First alternate path for ${destinationHash.toHexString().take(16)} " +
                    "via $via at ${candidate.hops} hops",
            )
        }
    }

    /** Drop alternates whose interface has gone, and any left for a destination with no path. */
    private fun pruneAlternates() {
        for ((key, rows) in pathAlternates) {
            if (pathTable[key] == null) {
                pathAlternates.remove(key)
                continue
            }
            synchronized(rows) {
                rows.removeAll { row ->
                    row.isExpired() || findInterfaceByHash(row.receivingInterfaceHash) == null ||
                        pathTable[key]?.receivingInterfaceHash
                            ?.contentEquals(row.receivingInterfaceHash) == true
                }
            }
            if (rows.isEmpty()) pathAlternates.remove(key)
        }
    }

    /**
     * Every way this node currently knows of reaching [destinationHash], selected row
     * first, or null if it knows none.
     *
     * A read-only snapshot, taken for observers: the AIDL surface a consumer's UI needs,
     * and the shape a metric would later score. Callers get copies, so nothing they hold
     * can move the routing state underneath Transport.
     */
    fun pathReachability(destinationHash: ByteArray): PathReachability? {
        val key = destinationHash.toKey()
        val selected = pathTable[key] ?: return null
        val rows = mutableListOf(snapshotRow(selected, isSelected = true))
        pathAlternates[key]?.let { alternates ->
            synchronized(alternates) {
                alternates.forEach { rows.add(snapshotRow(it, isSelected = false)) }
            }
        }
        return PathReachability(destinationHash.copyOf(), rows)
    }

    private fun snapshotRow(
        entry: PathEntry,
        isSelected: Boolean,
    ): PathRow =
        PathRow(
            interfaceHash = entry.receivingInterfaceHash.copyOf(),
            interfaceName = findInterfaceByHash(entry.receivingInterfaceHash)?.name,
            interfaceOnline = findInterfaceByHash(entry.receivingInterfaceHash)?.online ?: false,
            nextHop = entry.nextHop.copyOf(),
            hops = entry.hops,
            learnedAt = entry.timestamp,
            expiresAt = entry.expires,
            state = entry.state,
            failureCount = entry.failureCount,
            selected = isSelected,
        )

    /**
     * How many destinations currently hold an alternate, and how many rows in total.
     *
     * Production surface, not a test seam: a host polls this beside the path table size
     * to answer whether failover has anything to fall back on in this deployment. Empty
     * lists are not counted -- one exists transiently between a destination's first
     * candidate being admitted and the row being added -- so a destination counts only
     * once it genuinely holds a second way through.
     */
    fun alternatePathStats(): AlternatePathStats {
        var destinations = 0
        var rows = 0
        for (entry in pathAlternates.values) {
            val size = entry.size
            if (size > 0) {
                destinations++
                rows += size
            }
        }
        return AlternatePathStats(destinationsWithAlternate = destinations, alternateRows = rows)
    }

    /** Alternate rows held for a destination. Test seam; the snapshot is the public view. */
    @network.reticulum.RnsTestSeam
    fun alternatePathCountForTest(destinationHash: ByteArray): Int =
        pathAlternates[destinationHash.toKey()]?.size ?: 0

    /**
     * Record an alternate directly, without driving an announce through.
     *
     * The invariants worth testing here are the container's — which rows are kept, what
     * bounds them, when they are dropped. Reaching them through announce processing would
     * exercise announce processing.
     */
    @network.reticulum.RnsTestSeam
    fun recordAlternateForTest(
        destinationHash: ByteArray,
        candidate: PathEntry,
    ) = recordAlternatePath(destinationHash, candidate)

    /**
     * Mark a path as unresponsive.
     *
     * Tracks failure count and transitions path through states:
     * ACTIVE -> UNRESPONSIVE (after first failure)
     * UNRESPONSIVE -> STALE (after 3 failures)
     * STALE paths are automatically expired.
     */
    fun markPathUnresponsive(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        val entry = pathTable[key] ?: return

        entry.failureCount++

        when {
            entry.failureCount >= 3 -> {
                entry.state = PathState.STALE
                log("Path to ${destinationHash.toHexString()} marked STALE, expiring")
                expirePath(destinationHash)
            }
            entry.failureCount >= 1 -> {
                entry.state = PathState.UNRESPONSIVE
                log("Path to ${destinationHash.toHexString()} marked UNRESPONSIVE (${entry.failureCount} failures)")
            }
        }
    }

    /**
     * Report that the current way of reaching a destination is not working.
     *
     * For a caller that has tried and failed to deliver and wants the routing layer to do
     * something about it. The reference's answer, in both RNS and LXMF, is to drop the
     * path and rediscover: `LXMRouter.py:2746` calls `drop_path` after its pathless
     * retries. That is right when there is only ever one path, because the only way
     * forward is to find a new one.
     *
     * It stops being right once this node holds more than one way through. Dropping then
     * throws away a working alternate along with the failing selection, and pays for a
     * rediscovery it did not need.
     *
     * So the behaviour splits on whether there is anywhere else to go:
     *
     *  - **No usable alternate** — drop the path, exactly as the reference does and
     *    exactly as [expirePath] does today. This is every case until B.3 makes the
     *    reachability set selectable, so nothing observable changes yet.
     *  - **A usable alternate exists** — mark the failing row instead, leaving the
     *    alternate in place to be selected. Marking also lets a worse-hop announce
     *    replace the path, which an ACTIVE one refuses.
     *
     * The second branch is a deliberate divergence from the reference, recorded in
     * `port-deviations.md` alongside the online-aware egress it belongs with. The first
     * branch exists so the divergence costs nothing where it buys nothing.
     */
    fun failCurrentPath(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        if (pathTable[key] == null) return

        val alternates = pathReachability(destinationHash)?.usableAlternates.orEmpty()
        if (alternates.isEmpty()) {
            log(
                "No alternate way to ${destinationHash.toHexString()}; " +
                    "dropping the path and rediscovering, as the reference does",
            )
            expirePath(destinationHash)
            return
        }

        log(
            "Path to ${destinationHash.toHexString()} reported failing with " +
                "${alternates.size} alternate(s) held; marking rather than dropping",
        )
        markPathUnresponsive(destinationHash)
    }

    /**
     * Mark a path as responsive (reset failure state).
     */
    fun markPathResponsive(destinationHash: ByteArray) {
        val key = destinationHash.toKey()
        val entry = pathTable[key] ?: return

        if (entry.state != PathState.ACTIVE || entry.failureCount > 0) {
            entry.state = PathState.ACTIVE
            entry.failureCount = 0
            log("Path to ${destinationHash.toHexString()} marked ACTIVE")
        }
    }

    /**
     * Check if a path is currently unresponsive.
     */
    fun pathIsUnresponsive(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false
        return entry.state == PathState.UNRESPONSIVE || entry.state == PathState.STALE
    }

    /**
     * Mark a path as unknown state (reset for re-discovery).
     */
    fun markPathUnknownState(destinationHash: ByteArray) {
        val entry = pathTable[destinationHash.toKey()] ?: return
        entry.state = PathState.ACTIVE
        entry.failureCount = 0
    }

    /**
     * Get path state for a destination.
     *
     * @param destinationHash The destination to check
     * @return Path state constant (PATH_STATE_*), or PATH_STATE_UNKNOWN if no path exists
     */
    fun getPathState(destinationHash: ByteArray): Int {
        val entry = pathTable[destinationHash.toKey()] ?: return TransportConstants.PATH_STATE_UNKNOWN

        return when (entry.state) {
            PathState.ACTIVE -> TransportConstants.PATH_STATE_RESPONSIVE
            PathState.UNRESPONSIVE, PathState.STALE -> TransportConstants.PATH_STATE_UNRESPONSIVE
        }
    }

    /**
     * Check if a path is expired or has been unresponsive for too long.
     *
     * @param destinationHash The destination to check
     * @return true if path is unresponsive beyond timeout threshold
     */
    fun isPathUnresponsive(destinationHash: ByteArray): Boolean {
        val entry = pathTable[destinationHash.toKey()] ?: return false

        // Check if path is marked unresponsive
        if (entry.state == PathState.UNRESPONSIVE || entry.state == PathState.STALE) {
            return true
        }

        // Check if path has been inactive too long
        val timeSinceActivity = System.currentTimeMillis() - entry.timestamp
        return timeSinceActivity > TransportConstants.PATH_UNRESPONSIVE_TIMEOUT
    }

    /**
     * Get the interface for the next hop to a destination.
     */
    fun nextHopInterface(destinationHash: ByteArray): InterfaceRef? {
        val entry = pathTable[destinationHash.toKey()] ?: return null
        if (entry.isExpired()) return null
        return findInterfaceByHash(entry.receivingInterfaceHash)
    }

    // ===== Interface Latency Calculations =====

    /**
     * Get the bitrate of the next-hop interface.
     *
     * @param destinationHash The destination to check
     * @return Bitrate in bits per second, or null if unknown
     */
    fun nextHopInterfaceBitrate(destinationHash: ByteArray): Int? {
        val iface = nextHopInterface(destinationHash) ?: return null
        return iface.bitrate
    }

    /**
     * Get the hardware MTU of the next-hop interface.
     *
     * @param destinationHash The destination to check
     * @return Hardware MTU in bytes, or null if unknown
     */
    fun nextHopInterfaceHwMtu(destinationHash: ByteArray): Int? {
        val iface = nextHopInterface(destinationHash) ?: return null
        return if (iface.supportsLinkMtuDiscovery) iface.hwMtu else null
    }

    /**
     * Calculate per-bit latency for next hop (seconds).
     *
     * @param destinationHash The destination to check
     * @return Latency per bit in seconds, or null if bitrate unknown
     */
    fun nextHopPerBitLatency(destinationHash: ByteArray): Double? {
        val bitrate = nextHopInterfaceBitrate(destinationHash) ?: return null
        if (bitrate == 0) return null
        return 1.0 / bitrate
    }

    /**
     * Calculate per-byte latency for next hop (seconds).
     *
     * @param destinationHash The destination to check
     * @return Latency per byte in seconds, or null if bitrate unknown
     */
    fun nextHopPerByteLatency(destinationHash: ByteArray): Double? {
        val perBit = nextHopPerBitLatency(destinationHash) ?: return null
        return perBit * 8
    }

    /**
     * Calculate first hop timeout based on MTU and bitrate.
     * Matches Python RNS implementation: MTU * per_byte_latency + DEFAULT_PER_HOP_TIMEOUT.
     *
     * @param destinationHash The destination to check
     * @return Timeout in milliseconds
     */
    fun firstHopTimeout(destinationHash: ByteArray): Long {
        val perByteLatency = nextHopPerByteLatency(destinationHash)
        return if (perByteLatency != null) {
            val transmitTime = (RnsConstants.MTU * perByteLatency * 1000).toLong()
            transmitTime + TransportConstants.DEFAULT_PER_HOP_TIMEOUT
        } else {
            TransportConstants.DEFAULT_PER_HOP_TIMEOUT
        }
    }

    /**
     * Calculate extra timeout for link proofs based on interface characteristics.
     *
     * @param interfaceRef The interface to calculate timeout for
     * @return Extra timeout in milliseconds
     */
    fun extraLinkProofTimeout(interfaceRef: InterfaceRef?): Long {
        if (interfaceRef == null) return 0L
        val bitrate = interfaceRef.bitrate
        if (bitrate <= 0) return 0L

        // Calculate time to transmit MTU bytes: (1/bitrate) * 8 * MTU
        val perBitLatency = 1.0 / bitrate
        val timeSeconds = perBitLatency * 8 * RnsConstants.MTU
        return (timeSeconds * 1000).toLong()
    }

    // ===== Announce Queue Management =====

    /**
     * Drop all pending announces (for cleanup).
     */
    fun dropAnnounceQueues() {
        announceRateTable.clear()
        announceAllowedAt.clear()
        interfaceAnnounceQueues.clear()
        interfaceAnnounceAllowedAt.clear()
        log("Dropped all announce queues")
    }

    /**
     * Queue an announce for transmission on a specific interface.
     *
     * @param destinationHash The destination being announced
     * @param raw The raw announce packet
     * @param interfaceRef The interface to queue the announce on
     * @param hops Current hop count
     * @param emitted When the announce was originally emitted
     * @return true if queued successfully, false if queue is full
     */
    fun queueAnnounce(
        destinationHash: ByteArray,
        raw: ByteArray,
        interfaceRef: InterfaceRef,
        hops: Int,
        emitted: Long,
    ): Boolean {
        val ifaceKey = interfaceRef.hash.toKey()
        val queue = interfaceAnnounceQueues.getOrPut(ifaceKey) { mutableListOf() }

        // Under the queue's monitor: processAnnounceQueue iterates and removes under
        // `synchronized(queue)` on its own thread, and this method mutated the same list
        // without it. A ConcurrentModificationException there landed in its catch and
        // cleared the whole per-interface queue, up to 4096 rebroadcasts; the `size == 1`
        // check could also schedule two processing threads for one interface.
        // Python's list is serialised by the GIL per statement.
        synchronized(queue) {
            // Check queue size limit
            if (queue.size >= TransportConstants.MAX_QUEUED_ANNOUNCES) {
                log("Announce queue full on ${interfaceRef.name}, dropping announce")
                return false
            }

            // Check if already queued
            val existing = queue.find { it.destinationHash.contentEquals(destinationHash) }

            if (existing != null) {
                // Update if this is newer
                if (emitted > existing.emitted) {
                    queue.remove(existing)
                } else {
                    // Already queued with same or newer announce
                    return false
                }
            }

            // Calculate queue time
            val now = System.currentTimeMillis()
            val graceMs = TransportConstants.PATH_REQUEST_GRACE
            val randomMs = (Math.random() * TransportConstants.PATHFINDER_RW * 1000).toLong()
            val queueTime = now + graceMs + randomMs

            // Create queued announce
            val queued =
                QueuedAnnounce(
                    destinationHash = destinationHash.copyOf(),
                    time = queueTime,
                    hops = hops,
                    emitted = emitted,
                    raw = raw.copyOf(),
                )

            queue.add(queued)

            // Schedule processing if this is the first item
            if (queue.size == 1) {
                scheduleAnnounceQueueProcessing(interfaceRef)
            }

            log("Queued announce for ${destinationHash.toHexString()} on ${interfaceRef.name} (queue size: ${queue.size})")
            return true
        }
    }

    /**
     * Schedule processing of the announce queue for an interface.
     */
    private fun scheduleAnnounceQueueProcessing(interfaceRef: InterfaceRef) {
        val ifaceKey = interfaceRef.hash.toKey()
        val allowedAt = interfaceAnnounceAllowedAt[ifaceKey] ?: 0L
        val now = System.currentTimeMillis()
        val waitTime = maxOf(allowedAt - now, 0L)

        thread(name = "AnnounceQueue-${interfaceRef.name}", isDaemon = true) {
            Thread.sleep(waitTime)
            processAnnounceQueue(interfaceRef)
        }
    }

    /**
     * Process queued announces for a specific interface.
     *
     * Removes stale announces, selects the best announce to send based on hop count,
     * and respects bandwidth limits.
     */
    fun processAnnounceQueue(interfaceRef: InterfaceRef) {
        val ifaceKey = interfaceRef.hash.toKey()
        val queue = interfaceAnnounceQueues[ifaceKey] ?: return

        synchronized(queue) {
            try {
                val now = System.currentTimeMillis()

                // Remove stale announces
                queue.removeAll { now > it.time + TransportConstants.QUEUED_ANNOUNCE_LIFE }

                if (queue.isEmpty()) {
                    return
                }

                // Select announce with minimum hops
                val minHops = queue.minOf { it.hops }
                val candidates = queue.filter { it.hops == minHops }

                // Sort by time and select earliest
                val selected = candidates.minByOrNull { it.time } ?: return

                // Calculate transmission time and wait time based on bitrate
                // A bitrate of 0 (an interface that never reported one) made the wait 0 and
                // the announce cap a no-op; python's interfaces always carry a bitrate. The
                // configured floor stands in.
                val bitrate = maxOf(interfaceRef.bitrate, network.reticulum.config.InterfaceConfig.MINIMUM_BITRATE)
                val announceCap = interfaceRef.announceCap

                val txTime = (selected.raw.size * 8.0) / bitrate

                val waitTime =
                    if (announceCap > 0) {
                        (txTime / announceCap * 1000).toLong()
                    } else {
                        0L
                    }

                // Update allowed timestamp
                interfaceAnnounceAllowedAt[ifaceKey] = now + waitTime

                // Transmit the announce via Transport.transmit so IFAC
                // masking is applied on interfaces with IFAC configured. The
                // prior direct `interfaceRef.send(raw)` call here bypassed
                // masking, which was invisible while TCPServerInterface's
                // fan-out delivered the original (already-masked) bytes to
                // sibling clients; once the fan-out was removed for #46,
                // queued announces hit the wire unmasked and were silently
                // dropped by the peer's IFAC unmasker.
                try {
                    transmit(interfaceRef, selected.raw)
                    recordAnnounceSent(interfaceRef)
                    log("Sent queued announce for ${selected.destinationHash.toHexString()} on ${interfaceRef.name}")
                } catch (e: Exception) {
                    log("Failed to send queued announce on ${interfaceRef.name}: ${e.message}")
                }

                // Remove from queue
                queue.remove(selected)

                // Schedule next processing if queue not empty
                if (queue.isNotEmpty()) {
                    scheduleAnnounceQueueProcessing(interfaceRef)
                }
            } catch (e: Exception) {
                log("Error processing announce queue on ${interfaceRef.name}: ${e.message}")
                queue.clear()
            }
        }
    }

    /**
     * Check if announce is allowed (rate limiting).
     */
    private fun announceAllowed(destinationHash: ByteArray): Boolean {
        val allowedAt = announceAllowedAt[destinationHash.toKey()] ?: return true
        return System.currentTimeMillis() >= allowedAt
    }

    /**
     * Schedule next announce time for a destination.
     */
    private fun scheduleNextAnnounce(
        destinationHash: ByteArray,
        delayMs: Long,
    ) {
        announceAllowedAt[destinationHash.toKey()] = System.currentTimeMillis() + delayMs
    }

    // ===== Held Announces =====

    /**
     * Get count of held announces (both mechanisms combined).
     */
    fun heldAnnounceCount(): Int = heldAnnounceEntries.size + interfaces.sumOf { it.heldAnnounceCount() }

    // ===== Traffic Statistics =====

    /**
     * Record transmitted bytes for an interface.
     *
     * @param interfaceRef The interface that transmitted data
     * @param bytes Number of bytes transmitted
     */
    fun recordTxBytes(
        interfaceRef: InterfaceRef,
        bytes: Int,
    ) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.txBytes += bytes
        stats.txPackets++
        stats.lastActivity = System.currentTimeMillis()
    }

    /**
     * Record received bytes for an interface.
     *
     * @param interfaceRef The interface that received data
     * @param bytes Number of bytes received
     */
    fun recordRxBytes(
        interfaceRef: InterfaceRef,
        bytes: Int,
    ) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.rxBytes += bytes
        stats.rxPackets++
        stats.lastActivity = System.currentTimeMillis()
    }

    /**
     * Record that an announce was sent on an interface.
     *
     * @param interfaceRef The interface that sent the announce
     */
    fun recordAnnounceSent(interfaceRef: InterfaceRef) {
        val stats = interfaceStats.getOrPut(interfaceRef.hash.toKey()) { InterfaceTrafficStats() }
        stats.announcesSent++
    }

    /**
     * Get traffic statistics for an interface.
     *
     * @param interfaceRef The interface to get stats for
     * @return Traffic stats or null if no data recorded
     */
    fun getInterfaceStats(interfaceRef: InterfaceRef): InterfaceTrafficStats? = interfaceStats[interfaceRef.hash.toKey()]

    /**
     * Get all interfaces prioritized by capacity and current load.
     * Higher capacity and lower recent traffic = higher priority.
     *
     * @return List of interfaces sorted by priority (highest first)
     */
    fun prioritizeInterfaces(): List<InterfaceRef> =
        interfaces.sortedByDescending { interfaceRef ->
            val stats = interfaceStats[interfaceRef.hash.toKey()]
            val bitrate = interfaceRef.bitrate.toLong()
            val recentTx = stats?.txBytes ?: 0L

            // Higher bitrate and lower recent tx = higher priority
            if (bitrate > 0) {
                bitrate.toDouble() / (recentTx + 1)
            } else {
                // Interfaces without bitrate info get lower priority
                1.0 / (recentTx + 1)
            }
        }

    /**
     * Get active interfaces (online and can send).
     *
     * @return List of active interfaces
     */
    fun getActiveInterfaces(): List<InterfaceRef> = interfaces.filter { it.online && it.canSend }

    // ===== Local Client Support =====

    /**
     * Register a local client interface.
     */
    fun registerLocalClientInterface(interfaceRef: InterfaceRef) {
        localClientInterfaces.add(interfaceRef)
        interfaces.add(interfaceRef)
        log("Registered local client interface: ${interfaceRef.name}")
    }

    /**
     * Deregister a local client interface.
     */
    fun deregisterLocalClientInterface(interfaceRef: InterfaceRef) {
        localClientInterfaces.remove(interfaceRef)
        interfaces.remove(interfaceRef)
        log("Deregistered local client interface: ${interfaceRef.name}")
    }

    /**
     * Check if interface is a local client.
     */
    fun isLocalClientInterface(interfaceRef: InterfaceRef?): Boolean =
        interfaceRef != null && localClientInterfaces.any { it.hash.contentEquals(interfaceRef.hash) }

    /**
     * Check if packet came from a local client.
     */
    fun fromLocalClient(interfaceRef: InterfaceRef): Boolean = isLocalClientInterface(interfaceRef)

    /**
     * Handle shared connection disappearing.
     */
    fun sharedConnectionDisappeared() {
        localClientInterfaces.clear()
        log("Shared connection disappeared, cleared local clients")
    }

    /**
     * Handle shared connection reappearing.
     */
    fun sharedConnectionReappeared() {
        // Re-announce local destinations
        for (dest in destinations) {
            if (dest.direction == DestinationDirection.IN) {
                val announcePacket = Packet.createAnnounce(dest)
                if (announcePacket != null) {
                    outbound(announcePacket)
                }
            }
        }
        log("Shared connection reappeared, re-announced destinations")
    }

    /**
     * Get count of local client interfaces.
     */
    fun localClientCount(): Int = localClientInterfaces.size

    // ===== Persistence =====

    /**
     * Save path table to a file.
     *
     * Format: one line per entry, fields separated by |
     * destHash|nextHop|hops|expires|interfaceHash|announceHash|state|failureCount
     */
    fun savePathTable(file: java.io.File) {
        try {
            val lines =
                pathTable.entries.mapNotNull { (key, entry) ->
                    if (entry.isExpired()) return@mapNotNull null
                    // Only skip paths with missing interfaces when interfaces are registered
                    // (matches Python Transport.py:2905-2910)
                    if (interfaces.isNotEmpty() && findInterfaceByHash(entry.receivingInterfaceHash) == null) {
                        return@mapNotNull null
                    }
                    // Persist up to PERSIST_RANDOM_BLOBS most recent blobs
                    val blobsHex = entry.randomBlobs
                        .takeLast(TransportConstants.PERSIST_RANDOM_BLOBS)
                        .joinToString(",") { it.toHexString() }
                    listOf(
                        key.toString(),
                        entry.nextHop.toHexString(),
                        entry.hops.toString(),
                        entry.expires.toString(),
                        entry.receivingInterfaceHash.toHexString(),
                        entry.announcePacketHash.toHexString(),
                        entry.state.ordinal.toString(),
                        entry.failureCount.toString(),
                        entry.timestamp.toString(),
                        blobsHex,
                    ).joinToString("|")
                }
            file.writeText(lines.joinToString("\n"))
            log("Saved ${lines.size} path entries to ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to save path table: ${e.message}")
        }
    }

    /**
     * Load path table from a file.
     */
    fun loadPathTable(file: java.io.File) {
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            var loaded = 0
            for (line in lines) {
                val parts = line.split("|")
                if (parts.size < 8) continue

                try {
                    val destHash = hexToBytes(parts[0])
                    val nextHop = hexToBytes(parts[1])
                    val hops = parts[2].toInt()
                    val expires = parts[3].toLong()
                    val interfaceHash = hexToBytes(parts[4])
                    val announceHash = hexToBytes(parts[5])
                    val stateOrdinal = parts[6].toInt()
                    val failureCount = parts[7].toInt()
                    // New fields (backward-compatible: missing = defaults)
                    val timestamp = parts.getOrNull(8)?.toLongOrNull()
                        ?: System.currentTimeMillis()
                    val randomBlobs = parts.getOrNull(9)
                        ?.split(",")
                        ?.filter { it.isNotBlank() }
                        ?.map { hexToBytes(it) }
                        ?.toMutableList()
                        ?: mutableListOf()

                    // Skip expired entries
                    if (System.currentTimeMillis() > expires) continue

                    val entry =
                        PathEntry(
                            timestamp = timestamp,
                            nextHop = nextHop,
                            hops = hops,
                            expires = expires,
                            randomBlobs = randomBlobs,
                            receivingInterfaceHash = interfaceHash,
                            announcePacketHash = announceHash,
                            state = PathState.entries.getOrElse(stateOrdinal) { PathState.ACTIVE },
                            failureCount = failureCount,
                        )
                    pathTable[destHash.toKey()] = entry
                    loaded++
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            log("Loaded $loaded path entries from ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to load path table: ${e.message}")
        }
    }

    /**
     * Save packet hashlist to a file.
     *
     * Format: one hash per line, hex-encoded
     */
    fun savePacketHashlist(file: java.io.File) {
        try {
            val hashes = (packetHashlist + packetHashlistPrev).map { it.toString() }
            file.writeText(hashes.joinToString("\n"))
            log("Saved ${hashes.size} packet hashes to ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to save packet hashlist: ${e.message}")
        }
    }

    /**
     * Load packet hashlist from a file.
     */
    fun loadPacketHashlist(file: java.io.File) {
        if (!file.exists()) return
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            var loaded = 0
            for (line in lines) {
                try {
                    val hash = hexToBytes(line.trim())
                    packetHashlist.add(hash.toKey())
                    loaded++
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            log("Loaded $loaded packet hashes from ${file.absolutePath}")
        } catch (e: Exception) {
            log("Failed to load packet hashlist: ${e.message}")
        }
    }

    /**
     * Persist all transport data to a directory.
     *
     * @param directory Directory to save data to (created if needed)
     */
    fun persistData(directory: java.io.File) {
        directory.mkdirs()
        savePathTable(java.io.File(directory, "path_table.txt"))
        savePacketHashlist(java.io.File(directory, "packet_hashlist.txt"))
    }

    /**
     * Load all transport data from a directory.
     *
     * @param directory Directory to load data from
     */
    fun loadPersistedData(directory: java.io.File) {
        if (!directory.exists()) return
        loadPathTable(java.io.File(directory, "path_table.txt"))
        loadPacketHashlist(java.io.File(directory, "packet_hashlist.txt"))
    }

    // ===== Packet Caching =====

    /**
     * Determine if a packet should be cached.
     * Currently returns false (caching disabled by default, matching Python RNS).
     */
    fun shouldCache(
        @Suppress("UNUSED_PARAMETER") packet: Packet,
    ): Boolean {
        // TODO: Implement caching policy (e.g., for Resource proofs)
        // Currently disabled to match Python RNS behavior
        return false
    }

    /**
     * Cache a packet for later retrieval.
     *
     * @param packet The packet to cache
     * @param forceCache Force caching even if shouldCache returns false
     * @param receivingInterface The interface that received the packet
     */
    fun cache(
        packet: Packet,
        forceCache: Boolean = false,
        receivingInterface: InterfaceRef? = null,
    ) {
        if (!forceCache && !shouldCache(packet)) return

        val raw = packet.raw ?: return
        val hash = packet.packetHash

        packetCache[hash.toKey()] =
            CachedPacket(
                raw = raw.copyOf(),
                timestamp = System.currentTimeMillis(),
                receivingInterfaceHash = receivingInterface?.hash,
            )
    }

    /**
     * python `Transport.cache_request` (Transport.py:3114-3123): a packet we hold in the
     * local cache is replayed through `inbound`; otherwise the peer is asked over the
     * link with a CACHE_REQUEST packet carrying the hash. Cache requests are not
     * encrypted (Packet.py:219-220). Note the reference's `should_cache` is False for
     * everything but announces, so on 1.5.2 a proof re-query never finds the proof;
     * the port mirrors that rather than caching proofs on its own.
     */
    fun requestFromCache(
        packetHash: ByteArray,
        link: Link,
    ) {
        val cached = getCachedPacket(packetHash)
        val iface = cached?.receivingInterfaceHash?.let { findInterfaceByHash(it) }
        if (cached != null && iface != null) {
            inbound(cached.raw, iface)
            return
        }
        Packet.createRaw(
            destinationHash = link.linkId,
            data = packetHash,
            packetType = PacketType.DATA,
            destinationType = DestinationType.LINK,
            context = PacketContext.CACHE_REQUEST,
            mtu = link.mtu,
        ).send()
    }

    /**
     * Retrieve a cached packet by hash.
     *
     * @param packetHash The packet hash to look up
     * @return The cached packet or null if not found/expired
     */
    fun getCachedPacket(packetHash: ByteArray): CachedPacket? {
        val cached = packetCache[packetHash.toKey()] ?: return null

        if (cached.isExpired()) {
            packetCache.remove(packetHash.toKey())
            return null
        }

        return cached
    }

    /**
     * Handle a cache request by re-injecting a cached packet.
     *
     * @param packetHash The requested packet hash
     * @param requestingInterface The interface requesting the packet
     * @return True if packet was found and re-injected
     */
    fun cacheRequest(
        packetHash: ByteArray,
        requestingInterface: InterfaceRef,
    ): Boolean {
        val cached = getCachedPacket(packetHash) ?: return false

        // Re-inject the cached packet
        inbound(cached.raw, requestingInterface)
        return true
    }

    /**
     * Clean expired packets from the cache.
     */
    fun cleanCache() {
        val now = System.currentTimeMillis()
        if (now - cacheLastCleaned < TransportConstants.CACHE_CLEAN_INTERVAL) return

        var removed = 0
        val iterator = packetCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
                removed++
            }
        }

        if (removed > 0) {
            log("Cleaned $removed expired packets from cache")
        }

        cacheLastCleaned = now
    }

    /**
     * Graceful shutdown - persist data before stopping.
     *
     * @param dataDirectory Directory to save data to
     */
    fun shutdown(dataDirectory: java.io.File? = null) {
        if (!started.get()) return

        if (dataDirectory != null) {
            persistData(dataDirectory)
        }

        stop()
    }

    /**
     * Convert hex string to bytes.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] =
                (
                    (Character.digit(hex[i], 16) shl 4) +
                        Character.digit(hex[i + 1], 16)
                ).toByte()
            i += 2
        }
        return data
    }

    // ===== Path Requests =====

    /** Pending path requests: destination_hash -> request timestamp. */
    private val pathRequests = ConcurrentHashMap<ByteArrayKey, Long>()

    /** Discovery path request tags to avoid duplicates. */
    // Path-request tag dedup. Backed by two rotating sets under a lock (O(1)
    // add/contains), mirroring python's discovery_pr_tags / _prev + max_pr_tags
    // (Transport.py:188-190, 821-824). A prior CopyOnWriteArrayList made every
    // inbound path request O(n): contains-scan + array-copy add + a
    // removeAt(0)-in-a-loop trim. Path requests hit the well-known PLAIN
    // path-request destination unauthenticated and bypass hashlist dedup, so that
    // was an algorithmic-complexity CPU DoS the reference does not have.
    private val discoveryPrTagsLock = Any()
    private var discoveryPrTags = LinkedHashSet<ByteArrayKey>()
    private var discoveryPrTagsPrev = LinkedHashSet<ByteArrayKey>()
    private val maxPrTags = 16000 // python Transport.max_pr_tags

    /** Pending discovery path requests: dest_hash_key -> DiscoveryPathRequest. */
    private val discoveryPathRequests = ConcurrentHashMap<ByteArrayKey, DiscoveryPathRequest>()

    /** Path request destination for sending requests. */
    private var pathRequestDestination: Destination? = null

    /** Tunnel synthesis destination for receiving tunnel requests. */
    private var tunnelSynthesizeDestination: Destination? = null

    /**
     * Probe responder destination, or null when `respond_to_probes` is off — which is the
     * default (python `Transport.probe_destination`, Transport.py:511-518).
     */
    var probeDestination: Destination? = null
        private set

    /**
     * Remote management destination, or null when the knob is off (python
     * `Transport.remote_management_destination`, Transport.py:367-373).
     */
    var remoteManagementDestination: Destination? = null
        private set

    /**
     * Destinations the transport instance announces on its management interval, and their
     * hashes (python `Transport.mgmt_destinations` / `mgmt_hashes`, Transport.py:228-229).
     */
    val mgmtDestinations = CopyOnWriteArrayList<Destination>()
    val mgmtHashes = CopyOnWriteArrayList<ByteArray>()

    // --- Read-only snapshots consumed by the remote-management request handlers. ---

    /** Path table as (destination hash, entry) pairs. */
    internal fun pathTableSnapshot(): List<Pair<ByteArray, PathEntry>> =
        pathTable.map { it.key.bytes to it.value }

    /** Announce rate table as (destination hash, entry) pairs. */
    internal fun announceRateSnapshot(): List<Pair<ByteArray, AnnounceRateEntry>> =
        announceRateTable.map { it.key.bytes to it.value }

    /** When announces from a destination are next permitted, or 0 if unblocked. */
    internal fun announceAllowedAtFor(destHash: ByteArray): Long =
        announceAllowedAt[destHash.toKey()] ?: 0L

    /** One interface's reportable state, flattened for the `/status` handler. */
    internal data class InterfaceStatsSnapshot(
        val name: String,
        val hash: ByteArray,
        val mode: Int,
        val bitrate: Int,
        val online: Boolean,
        val txBytes: Long,
        val rxBytes: Long,
    )

    internal fun interfaceStatsSnapshot(): List<InterfaceStatsSnapshot> =
        interfaces.map { iface ->
            val stats = interfaceStats[iface.hash.toKey()]
            InterfaceStatsSnapshot(
                name = iface.name,
                hash = iface.hash,
                mode = iface.mode.ordinal + 1, // our enum is ordered to python's MODE_* values
                bitrate = iface.bitrate,
                online = iface.online,
                txBytes = stats?.txBytes ?: 0L,
                rxBytes = stats?.rxBytes ?: 0L,
            )
        }

    /** Name of a registered interface by hash, or a hex placeholder if it is gone. */
    internal fun interfaceNameForHash(hash: ByteArray): String =
        interfaces.firstOrNull { it.hash.contentEquals(hash) }?.name ?: hash.toHexString()

    /** Total links, pending and active (python `Transport.link_count`). */
    internal fun linkCount(): Int = pendingLinks.size + activeLinks.size

    /** python `Transport.active_link_count()`. */
    internal fun activeLinkCount(): Int = activeLinks.size

    /** Milliseconds since [start], 0 when not started (python `time.time() - Transport.start_time`). */
    internal fun uptimeMs(): Long = if (startTime == 0L) 0L else System.currentTimeMillis() - startTime

    /** python `Transport.lowest_interface_bitrate`: the slowest online interface, or null. */
    fun lowestInterfaceBitrate(): Int? = interfaces.filter { it.online && it.bitrate > 0 }.minOfOrNull { it.bitrate }
    /** Whether this instance routes for others (python `Reticulum.transport_enabled`). */
    internal fun isTransportEnabled(): Boolean = transportEnabled

    internal fun trafficRxb(): Long = trafficRxBytes
    internal fun trafficTxb(): Long = trafficTxBytes

    internal fun logManagement(message: String) = log(message)

    /**
     * Request a path to a destination from the network.
     *
     * This broadcasts a path request packet. If another node on the network
     * knows a path, it will respond with an announce.
     *
     * Sends **unconditionally**, mirroring Python `RNS.Transport.request_path`
     * (RNS/Transport.py:2541): it neither short-circuits when a path already
     * exists nor rate-limits locally-originated requests. Stale-path refresh
     * depends on this — a cached-but-dangling path must not suppress a fresh
     * request. The [TransportConstants.PATH_REQUEST_MI] throttle that previously
     * lived here now sits at the sole automated re-request site that needs it
     * ([deregisterLink], Python Transport.py:486-492). The [started] guard is a
     * Kotlin lifecycle necessity with no Python equivalent at this call site.
     *
     * @param destinationHash The destination to find a path to
     * @param onInterface Optional specific interface to send request on
     * @param callback Optional callback when path is found
     */
    fun requestPath(
        destinationHash: ByteArray,
        onInterface: InterfaceRef? = null,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (!started.get()) {
            callback?.invoke(false)
            return
        }

        // Generate request tag
        val requestTag = Hashes.getRandomHash()

        // Build path request data
        val pathRequestData =
            if (transportEnabled && identity != null) {
                // Transport mode: include our transport ID
                concatBytes(destinationHash, identity!!.hash, requestTag)
            } else {
                // Client mode: just destination and tag
                concatBytes(destinationHash, requestTag)
            }

        // Ensure path request destination exists (created during start)
        val prDest = pathRequestDestination
        if (prDest == null) {
            log("Path request destination not initialized")
            callback?.invoke(false)
            return
        }

        // Create and send the packet
        val packet =
            Packet.createRaw(
                destinationHash = prDest.hash,
                data = pathRequestData,
                packetType = PacketType.DATA,
                destinationType = DestinationType.PLAIN,
                transportType = TransportType.BROADCAST,
            )

        // Send on specific interface or all
        val sent =
            if (onInterface != null) {
                try {
                    onInterface.send(packet.pack())
                    true
                } catch (e: Exception) {
                    log("Failed to send path request: ${e.message}")
                    false
                }
            } else {
                outbound(packet)
            }

        if (sent) {
            pathRequests[destinationHash.toKey()] = System.currentTimeMillis()
            log("Sent path request for ${destinationHash.toHexString()}")

            // Set up timeout callback if provided
            if (callback != null) {
                thread(name = "PathRequest-timeout", isDaemon = true) {
                    Thread.sleep(TransportConstants.PATH_REQUEST_TIMEOUT)
                    val found = hasPath(destinationHash)
                    callback(found)
                }
            }
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * Internal path request used for forwarding. Supports explicit tag (to avoid loops)
     * and recursive mode (which throttles based on announce cap).
     * Python Transport.py:2541-2588
     */
    /**
     * Test seam: issue a path request with an EXPLICIT request tag. The public
     * [requestPath] always mints a fresh random tag; this lets the conformance
     * bridge thread the harness-supplied tag through so the emitted payload tag
     * and the returned tag match what the test sent (python's requestPath accepts
     * a tag, Transport.py:2783). Conformance-bridge is a separate gradle module
     * and cannot see the private [requestPathInternal].
     */
    fun requestPathWithTagForTest(
        destinationHash: ByteArray,
        onInterface: InterfaceRef? = null,
        tag: ByteArray,
    ) = requestPathInternal(destinationHash, onInterface, tag, recursive = false)

    private fun requestPathInternal(
        destinationHash: ByteArray,
        onInterface: InterfaceRef? = null,
        tag: ByteArray? = null,
        recursive: Boolean = false,
    ) {
        val requestTag = tag ?: Hashes.getRandomHash()

        val pathRequestData =
            if (transportEnabled && identity != null) {
                concatBytes(destinationHash, identity!!.hash, requestTag)
            } else {
                concatBytes(destinationHash, requestTag)
            }

        val prDest = pathRequestDestination ?: return

        val packet =
            Packet.createRaw(
                destinationHash = prDest.hash,
                data = pathRequestData,
                packetType = PacketType.DATA,
                destinationType = DestinationType.PLAIN,
                transportType = TransportType.BROADCAST,
            )

        // Recursive path requests are throttled by announce cap to avoid flooding
        // Python Transport.py:2563-2585
        if (onInterface != null && recursive) {
            val ifaceKey = onInterface.hash.toKey()
            val queue = interfaceAnnounceQueues[ifaceKey]
            if (queue != null && queue.isNotEmpty()) {
                log("Blocking recursive path request on ${onInterface.name} due to queued announces")
                return
            }

            val now = System.currentTimeMillis()
            val allowedAt = interfaceAnnounceAllowedAt[ifaceKey] ?: 0L
            if (now < allowedAt) {
                log("Blocking recursive path request on ${onInterface.name} due to active announce cap")
                return
            }

            // Update announce allowed time based on transmission time
            val bitrate = maxOf(onInterface.bitrate, network.reticulum.config.InterfaceConfig.MINIMUM_BITRATE)
            val announceCap = onInterface.announceCap
            if (announceCap > 0) {
                val txTime = ((pathRequestData.size + RnsConstants.HEADER_MIN_SIZE) * 8.0) / bitrate
                val waitTime = (txTime / announceCap * 1000).toLong()
                interfaceAnnounceAllowedAt[ifaceKey] = now + waitTime
            }
        }

        if (onInterface != null) {
            try {
                onInterface.send(packet.pack())
                onInterface.recordOutgoingPathRequest()
                pathRequests[destinationHash.toKey()] = System.currentTimeMillis()
            } catch (e: Exception) {
                log("Failed to send path request on ${onInterface.name}: ${e.message}")
            }
        } else {
            outbound(packet)
            pathRequests[destinationHash.toKey()] = System.currentTimeMillis()
        }
    }

    /**
     * Handle an incoming path request packet.
     */
    private fun handlePathRequest(
        data: ByteArray,
        packet: Packet,
        receivingInterface: InterfaceRef,
    ) {
        // Tag de-duplication and in-flight batching already happened in preprocessInbound,
        // before the packet was queued (python Transport.py:1828-1884). By the time a path
        // request is drained it is known to be new; this stage only routes it.
        val parsed = parsePathRequest(data) ?: return

        // Determine if the path request came from a local client
        val isFromLocalClient = fromLocalClient(receivingInterface)

        // Check if we know the path. The ingress-limited decision was made against the
        // interface's state when the packet ARRIVED and rode along in the packet; the
        // interface may have calmed down by now, and python uses the arrival-time answer.
        processPathRequest(
            parsed.destinationHash,
            isFromLocalClient,
            receivingInterface,
            parsed.requestingTransportId,
            parsed.tagBytes,
            ingressLimited = packet.trafficClass == TransportConstants.TC_INGRESS_LIMITED,
        )
    }

    /** The three fields of a path request payload (python Transport.py:1830-1836, 3392-3401). */
    private class ParsedPathRequest(
        val destinationHash: ByteArray,
        val requestingTransportId: ByteArray?,
        val tagBytes: ByteArray,
    )

    /**
     * Split a path request payload into destination, optional requesting transport id and
     * tag. Returns null for a payload too short to carry a destination, or one with no tag
     * — the reference drops both before they reach the queue, and so does [preprocessInbound].
     */
    private fun parsePathRequest(data: ByteArray): ParsedPathRequest? {
        if (data.size < RnsConstants.TRUNCATED_HASH_BYTES) return null

        val destinationHash = data.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)

        // Extract requesting transport ID if present
        val requestingTransportId =
            if (data.size > RnsConstants.TRUNCATED_HASH_BYTES * 2) {
                data.copyOfRange(RnsConstants.TRUNCATED_HASH_BYTES, RnsConstants.TRUNCATED_HASH_BYTES * 2)
            } else {
                null
            }

        // Extract tag
        val tagOffset =
            if (requestingTransportId != null) {
                RnsConstants.TRUNCATED_HASH_BYTES * 2
            } else {
                RnsConstants.TRUNCATED_HASH_BYTES
            }

        if (data.size <= tagOffset) {
            log("Ignoring tagless path request")
            return null
        }

        val tagBytes = data.copyOfRange(tagOffset, minOf(tagOffset + RnsConstants.TRUNCATED_HASH_BYTES, data.size))
        return ParsedPathRequest(destinationHash, requestingTransportId, tagBytes)
    }

    /**
     * Record a path request tag and report whether it was already seen (python
     * Transport.py:1847-1856). O(1) set ops under one lock; the two sets rotate (dropping the
     * older half) at the cap.
     */
    private fun isDuplicatePathRequestTag(destinationHash: ByteArray, tagBytes: ByteArray): Boolean {
        val uniqueTag = concatBytes(destinationHash, tagBytes).toKey()
        return synchronized(discoveryPrTagsLock) {
            if (discoveryPrTags.contains(uniqueTag) || discoveryPrTagsPrev.contains(uniqueTag)) {
                true
            } else {
                discoveryPrTags.add(uniqueTag)
                if (discoveryPrTags.size > maxPrTags) {
                    discoveryPrTagsPrev = discoveryPrTags
                    discoveryPrTags = LinkedHashSet()
                }
                false
            }
        }
    }

    /**
     * Process a path request - check if we know the destination and can announce it.
     * Python: Transport.path_request()
     *
     * Falls through 4 branches after the "known path" case:
     * 1. From local client → forward to all external interfaces
     * 2. Should search for unknown → forward on all interfaces (with throttling)
     * 3. Not from local, local clients exist → forward to local clients
     * 4. None of the above → log and ignore
     */
    private fun processPathRequest(
        destinationHash: ByteArray,
        isFromLocalClient: Boolean,
        receivingInterface: InterfaceRef,
        requestingTransportId: ByteArray?,
        tag: ByteArray,
        ingressLimited: Boolean = false,
    ) {
        val destHex = destinationHash.toHexString()

        // python Transport.py:3427 — limited if preprocessing classified the packet as
        // TC_INGRESS_LIMITED when it arrived, or if the interface is limiting right now.
        val shouldIngressLimit = ingressLimited || receivingInterface.shouldIngressLimitPr()

        // python Transport.py:3428-3433 — whether to forward for unknown destinations, and
        // if so whether the forward is restricted to a subset of interface modes.
        val shouldSearchForUnknown =
            transportEnabled &&
                (receivingInterface.mode in DISCOVER_PATHS_FOR ||
                    receivingInterface.mode == InterfaceMode.BOUNDARY)
        val searchModeFilter =
            if (transportEnabled && receivingInterface.mode == InterfaceMode.BOUNDARY) {
                BOUNDARY_SEARCH_MODES
            } else {
                null
            }

        // Case 1: Local destination — announce it directly, targeted at the requesting interface
        // Python: local_destination.announce(path_response=True, tag=tag, attached_interface=attached_interface)
        val localDest = findDestination(destinationHash)
        if (localDest != null) {
            log("Responding to path request with local destination $destHex on ${receivingInterface.name}")
            Identity.usedDestinationData(destinationHash) // python Transport.py:3448
            localDest.announce(
                pathResponse = true,
                tag = tag,
                attachedInterface = receivingInterface,
            )
            // python Transport.py:3595-3599 — answered, so it is no longer in flight.
            inflightPathRequests.remove(destinationHash.toKey())
            return
        }

        // Case 2: Known path — forward cached announce
        // Python Transport.py:2723-2781
        val pathEntry = pathTable[destinationHash.toKey()]
        if (pathEntry != null &&
            !pathEntry.isExpired() &&
            (transportEnabled || isFromLocalClient)
        ) {
            log("Path to $destHex known (${pathEntry.hops} hops), retrieving cached announce")
            // python Transport.py:3463 sets `answered = True` the moment the path is known,
            // BEFORE the cache-miss, roaming-same-interface and next-hop-is-requestor
            // returns below, and pops the in-flight entry on `answered` (3595-3599).
            // Releasing only on the two branches that queue a reply left the destination
            // in flight for 45 s on the other three, during which every further request
            // for it was batched and dropped — and a peer could pick the branch.
            inflightPathRequests.remove(destinationHash.toKey())
            val cachedData = getCachedAnnouncePacket(pathEntry.announcePacketHash)

            if (cachedData == null) {
                log("Could not retrieve cached announce for $destHex")
                return
            }

            val (raw, interfaceName) = cachedData
            val cachedPacket = Packet.unpack(raw)
            if (cachedPacket == null) {
                log("Could not unpack cached announce for $destHex")
                return
            }

            // Find the original receiving interface
            val originalInterface =
                interfaceName?.let { name ->
                    interfaces.find { it.name == name }
                }

            // Set hop count from path table (Python line 2736)
            cachedPacket.hops = pathEntry.hops

            // Target the response at the requesting interface only (Python line 2781:
            // announce_table entry stores attached_interface = requesting_interface).
            // queueAnnounceRetransmit below respects attachedInterface for targeted
            // emission. Without this the response would be broadcast, inflating
            // hop counts on unrelated peers that receive the stale cached announce.
            cachedPacket.attachedInterface = receivingInterface

            // Roaming mode check: don't answer if path is on the same roaming-mode interface
            // Python line 2731-2732
            if (receivingInterface.mode == InterfaceMode.ROAMING &&
                originalInterface != null &&
                receivingInterface.hash.contentEquals(originalInterface.hash)
            ) {
                log("Not answering path request on roaming-mode interface (same interface)")
                return
            }

            // Loop prevention: don't answer if next hop is the requestor
            // Python line 2738-2745
            if (requestingTransportId != null &&
                pathEntry.nextHop.contentEquals(requestingTransportId)
            ) {
                log("Not answering path request, next hop is the requestor")
                return
            }

            log("Answering path request for $destHex, forwarding cached announce")

            // Hold any existing announce table entry to avoid losing an in-flight announce
            // Python line 2777-2779 (mechanism 1: transport-level held entries)
            val existingEntry = announceTable[cachedPacket.destinationHash.toKey()]
            if (existingEntry != null) {
                heldAnnounceEntries[cachedPacket.destinationHash.toKey()] = existingEntry
            }

            // Queue for retransmission via the announce forwarding system. The answer is
            // deliberately DELAYED: python holds it for PATH_REQUEST_GRACE so a peer with a
            // more direct path gets to answer first, and adds PATH_REQUEST_RG on top when
            // answering over a roaming interface, where our own path is least likely to be
            // the best one (Transport.py:3490-3506). A local client, or a destination that
            // sits on a local-client interface, is answered at once — there is no
            // better-placed peer to defer to.
            val answerAt = when {
                isFromLocalClient -> System.currentTimeMillis()
                isLocalClientInterface(nextHopInterface(destinationHash)) -> {
                    logDebug {
                        "Path request destination $destHex is on a local client interface, " +
                            "rebroadcasting immediately"
                    }
                    System.currentTimeMillis()
                }
                else -> {
                    var at = System.currentTimeMillis() + TransportConstants.PATH_REQUEST_GRACE
                    if (receivingInterface.mode == InterfaceMode.ROAMING) {
                        at += TransportConstants.PATH_REQUEST_RG
                    }
                    at
                }
            }
            queueAnnounceRetransmit(
                destinationHash,
                cachedPacket,
                originalInterface ?: receivingInterface,
                blockRebroadcasts = true,
                attachedInterface = receivingInterface,
                hopsOverride = pathEntry.hops,
                retransmitAt = answerAt,
            )
            // python Transport.py:3595-3599 — answered, so it is no longer in flight.
            inflightPathRequests.remove(destinationHash.toKey())
            return
        }

        // Case 3: From local client, path unknown — forward to all external interfaces
        // Python Transport.py:2783-2790
        if (isFromLocalClient) {
            log("Forwarding path request from local client for $destHex to all other interfaces")
            val requestTag = Hashes.getRandomHash()
            for (iface in interfaces) {
                if (!iface.hash.contentEquals(receivingInterface.hash)) {
                    requestPathInternal(destinationHash, onInterface = iface, tag = requestTag)
                }
            }
            return
        }

        // Case 4: Transport node, path unknown — recursive forwarding to discover
        // Python Transport.py:2792-2806
        if (shouldSearchForUnknown) {
            val destKey = destinationHash.toKey()
            val existing = discoveryPathRequests[destKey]
            if (existing != null && existing.engaged) {
                log("Already a waiting path request for $destHex, not forwarding again")
            } else if (shouldIngressLimit) {
                // python Transport.py:3547-3551 — a peer under path-request ingress limiting
                // does not get to make this node fan a discovery out on every other
                // interface. The request is dropped here, not queued: the whole point of
                // the limit is that it costs us nothing.
                log("Not sending recursive path request for $destHex on ${receivingInterface.name} due to active ingress limiting")
            } else {
                log("Attempting to discover unknown path to $destHex via recursive forwarding")
                // python Transport.py:3559-3570 — engage, carrying over every interface that
                // was batched onto a non-engaged entry while this request sat in the queue.
                // python Transport.py:3555: the discovery timeout also covers a full round
                // trip for an MTU on the slowest online interface.
                val discoveryTimeout = maxOf(TransportConstants.PATH_REQUEST_TIMEOUT, mediumPathTimeout())
                val entry =
                    DiscoveryPathRequest(
                        destinationHash = destinationHash,
                        timeout = System.currentTimeMillis() + discoveryTimeout,
                        requestingInterface = receivingInterface,
                        engaged = true,
                    )
                existing?.requestingInterfaces?.forEach { entry.addRequestingInterface(it) }
                discoveryPathRequests[destKey] = entry

                for (iface in interfaces) {
                    if (iface.hash.contentEquals(receivingInterface.hash)) continue
                    // A boundary-originated search only crosses boundary and gateway links
                    // (python Transport.py:3575).
                    if (searchModeFilter != null && iface.mode !in searchModeFilter) continue
                    // python Transport.py:3576-3581: offline interfaces are skipped, and an
                    // interface under path-request egress limiting is not fanned out on.
                    if (!iface.online) continue
                    if (iface.shouldEgressLimitPr()) {
                        logDebug { "Not sending recursive path request on ${iface.name} due to active egress limiting" }
                        continue
                    }
                    // Re-use the original tag to avoid loops
                    requestPathInternal(
                        destinationHash,
                        onInterface = iface,
                        tag = tag,
                        recursive = true,
                    )
                }
            }
            return
        }

        // Case 5: External request, local clients exist — forward to local clients
        // Python Transport.py:2808-2813
        if (!isFromLocalClient && localClientInterfaces.isNotEmpty()) {
            log("Forwarding path request for $destHex to ${localClientInterfaces.size} local clients")
            for (iface in localClientInterfaces) {
                requestPathInternal(destinationHash, onInterface = iface)
            }
            return
        }

        // Case 6: No path known, nothing to do
        log("Ignoring path request for $destHex, no path known")
    }

    // ===== Packet Processing =====

    /**
     * Process an incoming packet from an interface.
     *
     * @param raw Raw packet bytes
     * @param interfaceRef Interface that received the packet
     */
    fun inbound(
        raw: ByteArray,
        interfaceRef: InterfaceRef,
        // python `tc`: the traffic class the caller already knows this packet belongs to.
        // Preprocessing only ever raises it. The one caller that passes it is held-announce
        // release, which re-injects as TC_INGRESS_LIMITED.
        tc: Int = TransportConstants.TC_DATA,
        // python `ifac_handled`: the frame has already had its IFAC removed and verified,
        // so skip that stage. Set by held-announce release, which stores the unmasked frame.
        ifacHandled: Boolean = false,
    ) {
        if (!started.get()) {
            log("Transport not started, dropping packet")
            return
        }
        if (paused.get()) return
        if (raw.size < RnsConstants.HEADER_MIN_SIZE) {
            log("Packet too small (${raw.size} < ${RnsConstants.HEADER_MIN_SIZE}), dropping")
            return
        }
        // Reject oversized frames before unpack/allocation (python Transport.py:1700:
        // `len(raw) > interface.HW_MTU + (interface.ifac_size or 0)` -> protocol
        // violation). Bounds against the on-wire size (raw still carries the IFAC
        // bytes here), using this interface's real MTU as surfaced by the adapter.
        val maxFrameSize = interfaceRef.hwMtu + interfaceRef.ifacSize
        if (raw.size > maxFrameSize) {
            log("Frame size ${raw.size} exceeds MTU $maxFrameSize on ${interfaceRef.name}, dropping")
            return
        }

        // Handle IFAC unmasking if interface has IFAC enabled (python Transport.py:1764-1786,
        // the whole block sits under `if not ifac_handled`).
        val processedRaw =
            if (ifacHandled) {
                raw
            } else if (interfaceRef.ifacIdentity != null && interfaceRef.ifacSize > 0) {
                val unmasked = removeIfacMasking(raw, interfaceRef)
                if (unmasked == null) {
                    // IFAC authentication failed, drop packet silently
                    return
                }
                unmasked
            } else {
                // No IFAC on interface - check if packet has IFAC flag set (shouldn't)
                if (raw[0].toInt() and 0x80 == 0x80) {
                    // Drop packets with IFAC flag on non-IFAC interface
                    return
                }
                raw
            }

        // python Transport.py:1891 — the synchronous path. Preprocess and process in one
        // go, under the jobs lock as before, on the caller's thread.
        if (!useInboundQueue) {
            jobsLock.withLock {
                try {
                    preprocessInbound(processedRaw, interfaceRef, tc)?.let { processInboundItem(it) }
                } catch (e: Exception) {
                    log("Error processing inbound packet: ${e.message}")
                }
            }
            return
        }

        // python Transport.py:1892-1896 — classify on this thread, then hand off. Preprocessing
        // is lock-free: everything it touches is a concurrent container, an atomic on the
        // interface, or a lock of its own (the path-request tag sets), and the reference runs
        // it on the interface thread without a transport-wide lock too.
        val item =
            try {
                preprocessInbound(processedRaw, interfaceRef, tc)
            } catch (e: Exception) {
                log("Error preprocessing inbound packet on ${interfaceRef.name}: ${e.message}")
                null
            } ?: return

        val queues = inboundQueues
        if (queues == null) {
            log("Inbound queues not ready, dropping packet from ${interfaceRef.name}")
            return
        }
        inboundInFlight.incrementAndGet()
        if (!queues.put(item.packet.trafficClass, item)) {
            inboundInFlight.decrementAndGet()
            logDebug { "Dropping inbound packet, queue is full (tc=${item.packet.trafficClass})" }
        }
    }

    /**
     * The drainer (python Transport.inbound_job, Transport.py:1899-1911). One thread pulls
     * from the queues in priority order and processes each packet under the jobs lock, so
     * processing is exactly as serialized as it was when inbound() did it inline — only the
     * thread it happens on has changed.
     */
    private fun inboundJob() {
        // A drainer serves exactly one session: the loop also checks that this thread is
        // still the registered drainer, so a drainer that was mid-packet when stop() ran
        // exits when it comes up for air instead of draining the NEXT session's queues
        // alongside the new thread.
        val self = Thread.currentThread()
        while (started.get() && inboundThread === self) {
            val queues = inboundQueues ?: break
            val item =
                try {
                    queues.get(INBOUND_POLL_MS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
            try {
                jobsLock.withLock { processInboundItem(item) }
            } catch (t: Throwable) {
                // Throwable, not Exception: this is the only thread that processes inbound
                // for every interface. An Error escaping here — an OutOfMemoryError under
                // pressure is the realistic one — killed it silently, after which inbound()
                // kept accepting into queues that filled and dropped. Python's
                // `except Exception` catches MemoryError, which is an Exception there.
                log("Error while processing inbound packet from ${item.interfaceRef.name}: ${t.javaClass.simpleName}: ${t.message}")
                if (t is InterruptedException) Thread.currentThread().interrupt()
            } finally {
                // Clamped: stop()/start() resets the counter, and a decrement from the old
                // session must not drive it negative and make awaitInboundIdle lie.
                inboundInFlight.updateAndGet { if (it > 0) it - 1 else 0 }
            }
        }
    }

    /**
     * Block until every packet accepted by [inbound] so far has been fully processed, or
     * [timeoutMillis] elapses. Returns true when idle was reached.
     *
     * This is the synchronization point that a caller who wants to observe the effect of a
     * packet must use now that inbound() only queues. The conformance bridge calls it before
     * answering "did that frame create a path"; unit tests call it between inject and assert.
     * The reference bridge does the same thing by polling with a timeout; this is the exact
     * version. In synchronous mode there is nothing to wait for and it returns at once.
     */
    fun awaitInboundIdle(timeoutMillis: Long = 5_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (inboundInFlight.get() > 0) {
            if (System.nanoTime() > deadline) return false
            LockSupport.parkNanos(200_000L)
        }
        return true
    }

    /** Current inbound queue heights and drop counts, or null when transport is not started. */
    fun inboundQueueSnapshot(): InboundQueues.Snapshot? = inboundQueues?.snapshot()

    /**
     * A full round trip for one MTU on the slowest online interface, plus one hop's default
     * timeout, in milliseconds (python Transport.medium_path_timeout, Transport.py:3205-3208).
     * Zero when no interface is online. The reference caches the slowest bitrate in
     * prioritize_interfaces; computing it per call here gives the same value over a list
     * that is never long, without a cache that can go stale when an interface drops.
     */
    fun mediumPathTimeout(): Long {
        val lowest = interfaces.filter { it.online && it.bitrate > 0 }.minOfOrNull { it.bitrate } ?: return 0L
        val bitrate = maxOf(lowest, network.reticulum.config.InterfaceConfig.MINIMUM_BITRATE)
        return 2L * (RnsConstants.MTU * 8L * 1000L / bitrate) + TransportConstants.DEFAULT_PER_HOP_TIMEOUT
    }

    /**
     * Remove IFAC (Interface Access Code) masking from a packet.
     * Returns null if authentication fails.
     */
    private fun removeIfacMasking(
        raw: ByteArray,
        interfaceRef: InterfaceRef,
    ): ByteArray? {
        val ifacIdentity = interfaceRef.ifacIdentity ?: return raw
        val ifacKey = interfaceRef.ifacKey ?: return raw
        val ifacSize = interfaceRef.ifacSize
        if (ifacSize <= 0) return raw

        // Check if IFAC flag is set
        if (raw[0].toInt() and 0x80 != 0x80) {
            // IFAC flag not set but should be - drop packet
            return null
        }

        // Check packet length
        if (raw.size <= 2 + ifacSize) {
            return null
        }

        // Extract IFAC from bytes 2 to 2+ifacSize
        val ifac = raw.copyOfRange(2, 2 + ifacSize)

        // Generate mask using HKDF
        val mask =
            crypto.hkdf(
                length = raw.size,
                ikm = ifac,
                salt = ifacKey,
                info = null,
            )

        // Unmask the payload
        val unmaskedRaw = ByteArray(raw.size)
        for (i in raw.indices) {
            unmaskedRaw[i] =
                if (i <= 1 || i > ifacSize + 1) {
                    // Unmask header bytes and payload (after IFAC)
                    (raw[i].toInt() xor mask[i].toInt()).toByte()
                } else {
                    // Don't unmask IFAC itself
                    raw[i]
                }
        }

        // Clear IFAC flag
        val newHeader =
            byteArrayOf(
                (unmaskedRaw[0].toInt() and 0x7F).toByte(),
                unmaskedRaw[1],
            )

        // Re-assemble packet without IFAC
        val newRaw = newHeader + unmaskedRaw.copyOfRange(2 + ifacSize, unmaskedRaw.size)

        // Calculate expected IFAC
        val signature = ifacIdentity.sign(newRaw)
        val expectedIfac = signature.copyOfRange(signature.size - ifacSize, signature.size)

        // Verify authentication
        if (!ifac.constantTimeEquals(expectedIfac)) {
            return null
        }

        return newRaw
    }

    /**
     * Stage one of inbound processing (python Transport.preprocess_inbound, Transport.py:
     * 1753-1896): everything that has to happen on the interface thread, before the packet is
     * queued. Returns the item to queue, or null when the packet was dropped, held, or batched.
     *
     * The split point is the reference's: parse, filter, count the hop, validate an announce's
     * signature, and make the two early decisions that decide WHICH queue a packet enters —
     * whether an announce is held for ingress limiting, and whether a path request is a
     * duplicate, in-flight, or from a peer under path-request limiting. Everything that
     * mutates routing state happens in [processInboundItem], on the drainer, under the jobs
     * lock. Nothing here takes that lock; see [inbound] for why that is safe.
     */
    private fun preprocessInbound(
        raw: ByteArray,
        interfaceRef: InterfaceRef,
        tc: Int,
    ): InboundItem? {
        // Parse the packet
        val packet = Packet.unpack(raw)
        if (packet == null) {
            log("Failed to parse packet (${raw.size} bytes)")
            return null
        }

        // Track which interface this packet was received on
        packet.receivingInterfaceHash = interfaceRef.hash

        // Copy physical layer stats from receiving interface to packet
        interfaceRef.rStatRssi?.let { packet.rssi = it }
        interfaceRef.rStatSnr?.let { packet.snr = it }
        interfaceRef.rStatQ?.let { packet.q = it }

        // Log wire-side hops before the +1 increment for diagnostics.
        // Pairs with the TX PACKET log in transmit() so hop progression
        // across the mesh can be reconstructed from logs alone.
        if (packet.packetType == PacketType.ANNOUNCE) {
            logDebug {
                "RX ANNOUNCE: dest=${packet.destinationHash.toHexString()} " +
                    "wire_hops=${packet.hops} iface=${interfaceRef.name} " +
                    "ctx=${packet.context}"
            }
        }

        // Filter on the WIRE hop count, BEFORE the increment (python
        // Transport.py:1705 runs packet_filter before `packet.hops += 1`). The
        // PLAIN/GROUP filter checks `hops <= 1`, which must see the on-wire hops,
        // or a legitimately relayed 1-hop PLAIN/GROUP packet is wrongly dropped.
        // Build the packet-hash key once; packetFilter's dedup and addPacketHash both reuse it.
        val pktKey = packet.packetHash.toKey()
        if (!packetFilter(packet, interfaceRef, pktKey)) {
            logDebug { "FILTERED: ${packet.packetType} dest=${packet.destinationHash.toHexString()} from ${interfaceRef.name}" }
            return null
        }

        // Increment hop count (Python Transport.py:1319)
        packet.hops++

        // Python RNS: Decrement hops for packets from local client interfaces
        // This makes destinations behind shared instance appear directly reachable (hops=0)
        // Must happen AFTER increment to match Python order: +1 then -1 = net 0
        // Matches Python Transport.inbound():1343-1348
        if (localClientInterfaces.isNotEmpty()) {
            if (interfaceRef.parentInterface?.isLocalSharedInstance == true) {
                packet.hops = maxOf(0, packet.hops - 1)
            }
        } else if (interfaceRef.isConnectedToSharedInstance) {
            // Client connected to shared instance (not server-spawned)
            packet.hops = maxOf(0, packet.hops - 1)
        }

        // The caller's class is a floor: preprocessing only raises it (python Transport.py:
        // 1796, 1805, 1829). A held announce re-injected as TC_INGRESS_LIMITED stays there.
        var trafficClass = tc

        // Drop an announce with an invalid signature/binding BEFORE it
        // consumes a hashlist slot (python validates in inbound and returns
        // before add_packet_hash). validateAnnounce is side-effect-free, so its
        // result is kept and handed to processAnnounce below instead of
        // re-validating there — that second call was a full Ed25519 verify on
        // every announce, doubling the hottest path's crypto for no gain.
        // Nothing between here and the dispatch mutates packet.data or the
        // identity/ratchet state the validation reads.
        var preValidatedAnnounce: AnnounceData? = null
        if (packet.packetType == PacketType.ANNOUNCE) {
            // python Transport.py:1804 — an announce frame larger than
            // Reticulum.MTU is a protocol violation, dropped BEFORE signature
            // validation. Interfaces with hwMtu > MTU (TCP, Local, Auto, Pipe)
            // otherwise admit it and the retransmit pack() aborts mid-processing
            // after Identity.remember / pathTable / cache writes have already run.
            if (raw.size > RnsConstants.MTU) {
                log("Dropping announce from ${interfaceRef.name}: frame size ${raw.size} exceeds MTU ${RnsConstants.MTU}")
                return null
            }
            if (trafficClass < TransportConstants.TC_ANNOUNCE) trafficClass = TransportConstants.TC_ANNOUNCE
            preValidatedAnnounce = validateAnnounce(packet)
            if (preValidatedAnnounce == null) {
                log("Dropping invalid announce from ${interfaceRef.name}")
                return null
            }

            // python Transport.py:1811-1825 — count it, then decide whether to hold it. This
            // runs BEFORE the packet is queued and before anything is remembered about it:
            // a held announce has not been processed at all, and when it is released it
            // comes back through inbound() and is processed in full. (This used to sit in
            // processAnnounce, after Identity.remember and the hashlist write.)
            interfaceRef.recordIncomingAnnounce()
            val destKey = packet.destinationHash.toKey()
            // Only announces for destinations NOT already in the path table are ingress
            // limited; re-announces of known destinations are governed by the announce rate
            // limiter instead.
            val isKnownDestination = pathTable.containsKey(destKey)
            // An announce that answers an outstanding path request is exempt, checked BEFORE
            // the limit. Ingress limiting sheds *unsolicited* floods; a solicited response is
            // the one way an unknown destination becomes a known one, and holding it defeats
            // path discovery outright.
            val answersPendingPathRequest =
                pathRequests.containsKey(destKey) || discoveryPathRequests.containsKey(destKey)
            if (!isKnownDestination && !answersPendingPathRequest && interfaceRef.shouldIngressLimit()) {
                logDebug { "Holding announce for ${packet.destinationHash.toHexString()} due to ingress limiting on ${interfaceRef.name}" }
                interfaceRef.holdAnnounce(packet.destinationHash, packet.raw ?: packet.pack(), packet.hops, interfaceRef)
                return null
            }
        } else if (pathRequestDestination?.let { packet.destinationHash.contentEquals(it.hash) } == true) {
            // python Transport.py:1828-1884 — path requests are classified, de-duplicated and
            // batched before they are queued, so a flood of them can only ever fill the
            // path-request queue, and duplicates never reach the drainer at all.
            if (trafficClass < TransportConstants.TC_PATH_REQUEST) trafficClass = TransportConstants.TC_PATH_REQUEST

            val parsed = parsePathRequest(packet.data) ?: return null
            if (isDuplicatePathRequestTag(parsed.destinationHash, parsed.tagBytes)) {
                logDebug { "Ignoring duplicate path request for ${parsed.destinationHash.toHexString()}" }
                return null
            }

            interfaceRef.recordIncomingPathRequest()
            if (interfaceRef.shouldIngressLimitPr()) trafficClass = TransportConstants.TC_INGRESS_LIMITED

            // In-flight batching (python Transport.py:1862-1884). The first request for a
            // destination registers it and goes through; every later one, while that first
            // is still unanswered, is folded into the discovery entry — so the requesting
            // interface is remembered — and dropped here. A request from a peer under
            // ingress limiting is not even folded in.
            val prKey = parsed.destinationHash.toKey()
            val now = System.currentTimeMillis()
            if (inflightPathRequests.putIfAbsent(prKey, now) != null) {
                if (trafficClass != TransportConstants.TC_INGRESS_LIMITED) {
                    logDebug {
                        "Path request on ${interfaceRef.name} for ${parsed.destinationHash.toHexString()} " +
                            "already in-flight, batching to existing"
                    }
                    discoveryPathRequests.compute(prKey) { _, existing ->
                        if (existing != null) {
                            existing.addRequestingInterface(interfaceRef)
                            existing
                        } else {
                            DiscoveryPathRequest(
                                destinationHash = parsed.destinationHash,
                                // python Transport.py:1878
                                timeout = now + maxOf(TransportConstants.PATH_REQUEST_TIMEOUT, mediumPathTimeout()),
                                requestingInterface = interfaceRef,
                                engaged = false,
                            )
                        }
                    }
                }
                return null
            }
        }

        packet.trafficClass = trafficClass
        return InboundItem(packet, interfaceRef, preValidatedAnnounce, pktKey)
    }

    /**
     * Stage two of inbound processing (python Transport._inbound, Transport.py:1914 on):
     * everything that reads or writes routing state. Runs on the drainer thread under the
     * jobs lock, or on the caller's thread in synchronous mode — either way, one packet at a
     * time, which is what the tables below assume.
     */
    private fun processInboundItem(item: InboundItem) {
        val packet = item.packet
        val raw = item.raw
        val interfaceRef = item.interfaceRef
        val preValidatedAnnounce = item.preValidatedAnnounce
        val pktKey = item.pktKey

        // python Transport.py:1915-1916 — an interface that went offline while the packet
        // sat in the queue does not get its packet processed.
        if (!interfaceRef.online) return

        // One ByteArrayKey for the destination hash, reused by every table lookup below
        // (was re-allocated at each of four sites per packet).
        val destKey = packet.destinationHash.toKey()

        // Add to hashlist (with some exceptions)
        val rememberHash =
            when {
                linkTable.containsKey(destKey) -> false
                packet.packetType == PacketType.PROOF &&
                    packet.context == PacketContext.LRPROOF -> false
                else -> true
            }

        if (rememberHash) {
            addPacketHash(pktKey)
        }

        trafficRxBytes += raw.size
        recordRxBytes(interfaceRef, raw.size)

        // Python RNS: Plain broadcast routing for shared instances
        // Matches Python Transport.inbound():1384-1398
        // Only applies to PLAIN+BROADCAST packets (path requests, control packets).
        // Announces (SINGLE+BROADCAST) are NOT forwarded here — they are handled
        // by processAnnounce() which re-packages them with HEADER_2 transport headers.
        // Whether this packet arrived from one of our local (shared-instance) clients.
        // Computed once here; reused by the broadcast branch below and by the
        // for-local-client detection after it (was computed twice per packet).
        val fromLocalClient = localClientInterfaces.any { it.hash.contentEquals(interfaceRef.hash) }

        if (packet.destinationType == DestinationType.PLAIN &&
            packet.transportType == TransportType.BROADCAST &&
            !controlHashes.contains(destKey)
        ) {
            logDebug { "BROADCAST ROUTING: packet from ${interfaceRef.name}, localClients=${localClientInterfaces.size}" }
            logDebug { "BROADCAST ROUTING: fromLocalClient=$fromLocalClient" }

            if (fromLocalClient) {
                // FROM local client → rebroadcast to ALL interfaces EXCEPT originator
                logDebug { "BROADCAST ROUTING: FROM local client, forwarding to ${interfaces.size - 1} interfaces" }
                for (iface in interfaces) {
                    if (!iface.hash.contentEquals(interfaceRef.hash) && iface.canSend && iface.online) {
                        try {
                            logDebug { "BROADCAST ROUTING: Forwarding from local client to ${iface.name}" }
                            iface.send(raw)
                        } catch (e: Exception) {
                            log("Error forwarding from local client to ${iface.name}: ${e.message}")
                        }
                    }
                }
            } else if (localClientInterfaces.isNotEmpty()) {
                // FROM external interface → rebroadcast to ALL local clients
                logDebug { "BROADCAST ROUTING: FROM external, forwarding to ${localClientInterfaces.size} local clients" }
                for (iface in localClientInterfaces) {
                    if (iface.canSend && iface.online) {
                        try {
                            logDebug {
                                "BROADCAST ROUTING: Forwarding ${packet.packetType} (destType=${packet.destinationType}, transport=${packet.transportType}) to local client ${iface.name}"
                            }
                            iface.send(raw)
                        } catch (e: Exception) {
                            log("Error forwarding from external to local client ${iface.name}: ${e.message}")
                        }
                    }
                }
            } else {
                logDebug { "BROADCAST ROUTING: No local clients to forward to" }
            }
        }

        // Detect packets for local clients (Python Transport.py:1378-1382)
        val forLocalClient =
            packet.packetType != PacketType.ANNOUNCE &&
                pathTable[destKey]?.let { it.hops == 0 } == true
        val forLocalClientLink =
            packet.packetType != PacketType.ANNOUNCE &&
                linkTable[destKey]?.let { entry ->
                    localClientInterfaces.any { it.hash.contentEquals(entry.receivingInterfaceHash) } ||
                        localClientInterfaces.any { it.hash.contentEquals(entry.nextHopInterfaceHash) }
                } == true

        // Synthesize transport_id for local client routing (Python Transport.py:1413-1414)
        // When a HEADER_1 packet arrives for a destination behind a local client,
        // we insert our identity as transport_id so the transport forwarding can handle it.
        // LRPROOF is excluded: it is forwarded exclusively via link_table in processProof,
        // never via pathTable-based general transport forwarding.
        if (packet.transportId == null &&
            forLocalClient &&
            packet.context != PacketContext.LRPROOF
        ) {
            packet.transportId = identity?.hash
        }

        // Note: the previous "Server-side defense" block that mutated
        // packet.raw to upgrade a HEADER_1 inbound from a local client to
        // HEADER_2 has been removed. The mutation broke the
        // packet.raw ↔ packet.headerType invariant relied on by
        // Packet.getHashablePart(): headerType is `val`, set at unpack
        // time, and getHashablePart() slicing branches on it; mutating
        // raw without refreshing headerType caused the inserted
        // transport_id to land inside the hashable slice and produced a
        // divergent link_id (LRPROOFs returning from the destination then
        // missed the master's link_table and were silently dropped).
        //
        // The defense was only needed because kotlin shared-instance
        // clients packed HEADER_1 outbound — they failed to set
        // Transport.isConnectedToSharedInstance for manually-constructed
        // LocalClientInterface registrations, so the outbound branch at
        // processOutbound's `hops == 1 && isConnectedToSharedInstance &&
        // isHeader1 && !isLink` never fired. That's now fixed at the
        // source: registerInterface widens the global flag whenever a
        // shared-instance client attaches, matching python's
        // single-entry-point guarantee at Reticulum.py:417. Kotlin
        // clients now pack HEADER_2 with master's identity as
        // transport_id on the same code path as python
        // (Transport.py:1097-1108), so this server-side compensation is
        // unreachable for well-behaved clients and removing it brings
        // the master back into line with python (Transport.py:1488-1489
        // sets packet.transport_id only for for_local_client and only as
        // a field — never mutates raw). See port-deviations.md.

        // General transport handling (Python Transport.py:1404-1510)
        // This runs for ALL packet types (LINKREQUEST, DATA, PROOF) before type-specific handling.
        // It forwards packets where we are the designated next transport hop.
        if (transportEnabled || fromLocalClient || forLocalClient || forLocalClientLink) {
            // LRPROOF is forwarded exclusively via link_table in processProof
            // (Python Transport.py:2016-2039), never via pathTable. Without this guard,
            // LRPROOF can loop because it is not added to the packet hash filter.
            if (packet.transportId != null &&
                packet.packetType != PacketType.ANNOUNCE &&
                packet.context != PacketContext.LRPROOF
            ) {
                val myHash = identity?.hash
                if (myHash != null && packet.transportId!!.contentEquals(myHash)) {
                    val pathEntry = pathTable[packet.destinationHash.toKey()]
                    if (pathEntry != null) {
                        val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
                        if (outboundInterface != null) {
                            val packetRaw = packet.raw
                            if (packetRaw != null) {
                                // Build forwarded raw based on remaining hops (Python:1433-1449)
                                var newRaw =
                                    when {
                                        pathEntry.hops > 1 -> {
                                            // Keep HEADER_2, update next hop
                                            val nr = packetRaw.copyOf()
                                            nr[1] = packet.hops.toByte()
                                            System.arraycopy(pathEntry.nextHop, 0, nr, 2, RnsConstants.TRUNCATED_HASH_BYTES)
                                            nr
                                        }
                                        pathEntry.hops == 1 -> {
                                            // Strip transport header → HEADER_1
                                            // Destination is one hop away, no transport needed
                                            val newFlags =
                                                (HeaderType.HEADER_1.value shl 6) or
                                                    (TransportType.BROADCAST.value shl 4) or
                                                    (packetRaw[0].toInt() and 0x0F)
                                            val nr = ByteArray(packetRaw.size - RnsConstants.TRUNCATED_HASH_BYTES)
                                            nr[0] = newFlags.toByte()
                                            nr[1] = packet.hops.toByte()
                                            System.arraycopy(
                                                packetRaw,
                                                2 + RnsConstants.TRUNCATED_HASH_BYTES,
                                                nr,
                                                2,
                                                packetRaw.size - 2 - RnsConstants.TRUNCATED_HASH_BYTES,
                                            )
                                            nr
                                        }
                                        else -> {
                                            // hops==0: destination is behind a local client.
                                            // Just update hop count in original raw (Python:1446-1449).
                                            // The raw may be HEADER_1 (if transport_id was synthesized)
                                            // or HEADER_2 — keep its format as-is.
                                            val nr = packetRaw.copyOf()
                                            nr[1] = packet.hops.toByte()
                                            nr
                                        }
                                    }

                                // For LINKREQUEST: clamp the link MTU signalling to what THIS
                                // hop can actually carry, then create the link_table entry
                                // (python Transport.py:2064-2086, immediately before its own
                                // link_entry). Without the clamp the two endpoints negotiate an
                                // MTU from their own interfaces, we forward it untouched, and
                                // every resource part sized to that MDU is larger than this hop
                                // can deframe — the parts vanish below Transport, the receiver
                                // re-requests forever and the transfer times out. Small control
                                // packets (ADV/REQ/KEEPALIVE) fit either way, so only bulk
                                // transfers through a relay fail, and they fail silently.
                                if (packet.packetType == PacketType.LINKREQUEST) {
                                    newRaw = clampLinkRequestMtuForForwarding(
                                        newRaw,
                                        packet,
                                        outboundInterface,
                                        interfaceRef,
                                    ) ?: run {
                                        // python calls protocol_violation and returns: the
                                        // signalling bytes were undecodable, so the request is
                                        // dropped rather than forwarded with a bad MTU.
                                        interfaceRef.protocolViolation("Undecodable path MTU signalling bytes")
                                        log("Dropping link request with undecodable path MTU signalling")
                                        return
                                    }
                                    val linkId = Link.linkIdFromLrPacket(packet)
                                    val now = System.currentTimeMillis()
                                    // python Transport.py:2060-2062: one MTU at the outbound
                                    // interface's bitrate, then 6 s per remaining hop.
                                    val proofTimeout =
                                        now + extraLinkProofTimeout(outboundInterface) +
                                            LinkConstants.ESTABLISHMENT_TIMEOUT_PER_HOP * maxOf(1, pathEntry.hops)
                                    val linkEntry =
                                        LinkEntry(
                                            timestamp = now,
                                            nextHop = pathEntry.nextHop,
                                            nextHopInterfaceHash = pathEntry.receivingInterfaceHash,
                                            remainingHops = pathEntry.hops,
                                            receivingInterfaceHash = interfaceRef.hash,
                                            takenHops = packet.hops,
                                            destinationHash = packet.destinationHash,
                                            validated = false,
                                            proofTimeout = proofTimeout,
                                        )
                                    linkTable[linkId.toKey()] = linkEntry
                                    logDebug { "Created link table entry for ${linkId.toHexString()} (remaining=${pathEntry.hops}, taken=${packet.hops})" }
                                } else {
                                    // For other types: create reverse_table entry (Python:1495-1501)
                                    val reverseEntry =
                                        ReverseEntry(
                                            receivingInterfaceHash = interfaceRef.hash,
                                            outboundInterfaceHash = outboundInterface.hash,
                                            timestamp = System.currentTimeMillis(),
                                        )
                                    reverseTable[packet.truncatedHash.toKey()] = reverseEntry
                                }

                                transmit(outboundInterface, newRaw)
                                // Compare-by-identity before writing back the
                                // touched timestamp: `transmit()` releases
                                // `jobsLock` for the blocking socket I/O, so
                                // another thread (typically `Transport.inbound`
                                // processing a fresher announce on the same
                                // destination) may have replaced this entry
                                // during the release window. Only touch if the
                                // entry is still the one we observed before
                                // transmit; otherwise the fresher entry wins
                                // and our touch would be a stale overwrite.
                                // Python avoids this via per-table
                                // `path_table_lock` (Transport.py:134); kotlin
                                // uses optimistic identity-CAS — see
                                // port-deviations.md.
                                val key = packet.destinationHash.toKey()
                                if (pathTable[key] === pathEntry) {
                                    val touched = pathEntry.touch()
                                    pathTable[key] = touched
                                    pathStore?.upsertPath(packet.destinationHash, touched)
                                }
                                log(
                                    "Transport forwarding ${packet.packetType} for ${packet.destinationHash.toHexString()} via ${outboundInterface.name} (remaining_hops=${pathEntry.hops})",
                                )
                            }
                        } else {
                            logDebug { "Transport forwarding: path exists for ${packet.destinationHash.toHexString()} but interface not found" }
                        }
                    } else {
                        logDebug { "Got packet in transport, but no known path to ${packet.destinationHash.toHexString()}" }
                    }
                }
            }
        }

        // Link transport forwarding (Python Transport.py:1512-1549). Python
        // places this BEFORE the type dispatch so it fires for DATA *and*
        // PROOF packets (including RESOURCE_PRF) on links we transit. Keep
        // it outside processData so RESOURCE_PRF — which dispatches to
        // processProof and would otherwise get dropped on the hub — still
        // gets forwarded to the correct spawned-child. Exclusion list
        // matches Python: ANNOUNCE goes through its own retransmit path,
        // LINKREQUEST creates the link_table entry via transport-mode
        // forwarding (not by a reverse link_table lookup), and LRPROOF
        // has its own dedicated forwarding in processProof.
        if (packet.packetType != PacketType.ANNOUNCE &&
            packet.packetType != PacketType.LINKREQUEST &&
            packet.context != PacketContext.LRPROOF
        ) {
            forwardViaLinkTable(packet, interfaceRef)
        }

        // Route based on packet type (Python:1559+, 1937+, 1968+)
        // This runs AFTER transport forwarding — a packet may be both forwarded and delivered locally.
        when (packet.packetType) {
            PacketType.ANNOUNCE -> processAnnounce(packet, interfaceRef, preValidatedAnnounce)
            PacketType.LINKREQUEST -> processLinkRequest(packet, interfaceRef)
            PacketType.PROOF -> processProof(packet, interfaceRef)
            PacketType.DATA -> processData(packet, interfaceRef)
        }
    }

    /**
     * Forward a link-attached packet via the link_table if we transit it.
     *
     * Mirrors Python Transport.py:1514-1549. Returns `true` if forwarded.
     * Does not early-return the caller — Python falls through to further
     * processing even on a successful forward (the commented-out `return`
     * at Transport.py:1553 is a historical TODO, not active behavior).
     */
    private fun forwardViaLinkTable(
        packet: Packet,
        interfaceRef: InterfaceRef,
    ): Boolean {
        val linkEntry = linkTable[packet.destinationHash.toKey()] ?: return false
        // python Transport.py:2126-2128 — a packet on a link-table entry that has
        // not yet been validated by an LRPROOF is a protocol violation and is not
        // forwarded. Otherwise a forged LINKREQUEST opens an unauthenticated relay
        // toward the destination for the whole proof-timeout window.
        if (!linkEntry.validated) {
            log("Link packet for ${packet.destinationHash.toHexString()} received before link validation on ${interfaceRef.name}, not forwarding")
            return false
        }
        val nhIface = findInterfaceByHash(linkEntry.nextHopInterfaceHash)
        val rcvdIface = findInterfaceByHash(linkEntry.receivingInterfaceHash)
        val outboundInterface =
            when {
                // Same interface for both directions — just repeat (Python lines 1521-1525).
                nhIface != null &&
                    rcvdIface != null &&
                    nhIface.hash.contentEquals(rcvdIface.hash) -> {
                    if (packet.hops == linkEntry.remainingHops || packet.hops == linkEntry.takenHops) {
                        nhIface
                    } else {
                        null
                    }
                }
                // Different interfaces — transmit on opposite side (Python lines 1526-1537).
                nhIface != null && interfaceRef.hash.contentEquals(nhIface.hash) -> {
                    if (packet.hops == linkEntry.remainingHops) rcvdIface else null
                }
                rcvdIface != null && interfaceRef.hash.contentEquals(rcvdIface.hash) -> {
                    if (packet.hops == linkEntry.takenHops) nhIface else null
                }
                else -> null
            }
        if (outboundInterface == null) return false

        addPacketHash(packet.packetHash) // Python line 1543
        val raw = packet.raw ?: packet.pack()
        val newRaw = raw.copyOf()
        newRaw[1] = packet.hops.toByte()
        transmit(outboundInterface, newRaw)
        // Optimistic identity-CAS: don't overwrite a fresher linkEntry that
        // another thread may have written during transmit's lock release.
        // See port-deviations.md (path/link table identity-CAS).
        val linkKey = packet.destinationHash.toKey()
        if (linkTable[linkKey] === linkEntry) {
            linkTable[linkKey] = linkEntry.copy(timestamp = System.currentTimeMillis())
        }
        log(
            "Forwarding ${packet.packetType}/${packet.context} for " +
                "${packet.destinationHash.toHexString()} via ${outboundInterface.name}",
        )
        return true
    }

    /**
     * Send a packet.
     *
     * @param packet Packet to send
     * @return true if sent successfully
     */
    /**
     * Conformance test seam: a tap invoked for every packet handed to outbound,
     * letting the bridge capture the on-wire packets a link emits during
     * receive/prove/teardown (LINKCLOSE, the 0xFE keepalive answer, LRPROOF, ...).
     * This is the kotlin equivalent of the reference bridge wrapping
     * RNS.Packet.send (reticulum-conformance reference/wire_tcp.py). Set around a
     * synchronous operation and cleared after; null in normal operation. The tap
     * receives the live packet — read context/destinationHash/data (or call
     * pack()) inside the tap, as the packet may be mutated by processOutbound.
     */
    @Volatile
    @network.reticulum.RnsTestSeam
    var outboundTapForTest: ((Packet) -> Unit)? = null

    fun outbound(packet: Packet): Boolean {
        if (!started.get()) return false
        if (paused.get()) return false

        // Record announces we originate for our own destinations (hops == 0, local dest),
        // so the local-destination skip can tell our own shared-instance echo from a
        // foreign announce of our identity. packetHash excludes hops, so the echo matches
        // even with an incremented hop count. Forwarded announces (hops >= 1) are excluded.
        if (packet.packetType == PacketType.ANNOUNCE && packet.hops == 0 &&
            destinationIndex.containsKey(packet.destinationHash.toKey())
        ) {
            selfAnnounceHashes.add(packet.packetHash.toKey())
        }

        outboundTapForTest?.let { tap -> runCatching { tap(packet) } }

        return jobsLock.withLock {
            try {
                processOutbound(packet)
            } catch (e: Exception) {
                log("Error processing outbound packet: ${e.message}")
                false
            }
        }
    }

    private fun processOutbound(packet: Packet): Boolean {
        // TTL guard: don't emit/forward a packet at or past the hop ceiling
        // (python Transport._outbound returns False on hops > PATHFINDER_M-1,
        // Transport.py:1305).
        if (packet.hops >= TransportConstants.PATHFINDER_M) {
            log("Dropping outbound packet at hop ceiling (${packet.hops} >= ${TransportConstants.PATHFINDER_M})")
            return false
        }
        // Use the bytes send() already packed; pack here only if the packet arrived unpacked
        // (direct Transport.outbound callers). The unconditional re-pack repeated the full
        // ephemeral X25519 + HKDF + AES for every SINGLE-dest packet and threw the first
        // result away. Python's Transport._outbound only ever reads packet.raw.
        val packedData = packet.raw ?: packet.pack()
        var sent = false
        val destHex = packet.destinationHash.toHexString()

        // Debug for LINK packets
        if (packet.destinationType == DestinationType.LINK) {
            logDebug { "Outbound LINK packet: dest=$destHex, context=${packet.context}, size=${packedData.size}" }
        }

        // Local loopback for links with both endpoints in this process.
        //
        // When an app acts as both shared instance transport node and client (e.g.
        // an RRC app which is both hub and client), a link to its own destination
        // has both endpoints in the client process. Without this optimization, LINK
        // DATA packets would round-trip through the transport node: the client sends
        // to the transport node, the transport node's link_table forwarding bounces
        // it back, and the client delivers to both link endpoints. The Python
        // reference handles this bounce correctly, but the Kotlin implementation
        // currently has issues with bounced packets being delivered to both the
        // initiator and responder, causing an infinite send loop.
        //
        // This local loopback avoids the round-trip entirely by delivering directly
        // to the peer link endpoint when both are in the same process. This is also
        // more efficient than bouncing through the transport node.
        // TODO: investigate why bounced link packets cause a send loop — the Python
        // reference handles the same scenario without issues.
        if (packet.destinationType == DestinationType.LINK && packet.link != null) {
            val key = packet.destinationHash.toKey()
            val senderLink = packet.link
            val peerLink =
                activeLinks.find {
                    it.linkId.toKey() == key && it !== senderLink
                }
            if (peerLink != null) {
                try {
                    peerLink.receive(packet)
                    sent = true
                    logDebug { "Local loopback delivery for $destHex" }
                } catch (e: Exception) {
                    log("Failed local loopback: ${e.message}")
                }
                if (sent) return true
            }
        }

        // Check if we have a known path
        val pathEntry = pathTable[packet.destinationHash.toKey()]

        // Use path routing when we have a valid, unexpired path (Python
        // Transport.py:972-1019).
        val usePathRouting =
            pathEntry != null &&
                !pathEntry.isExpired() &&
                packet.packetType != PacketType.ANNOUNCE &&
                packet.destinationType != DestinationType.PLAIN &&
                packet.destinationType != DestinationType.GROUP

        if (usePathRouting) {
            // We have a path - use it
            val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
            if (outboundInterface == null) {
                // Interface no longer available (e.g., AutoInterface recreated with new hash
                // after app restart). Drop the stale path entry — matches Python behavior
                // (Transport.py:105: "The interface is no longer available").
                // The broadcast fallback below will handle the packet, and a path request
                // will naturally re-discover the route with the current interface hash.
                log("Removing stale path for $destHex: interface hash no longer matches any registered interface")
                pathTable.remove(packet.destinationHash.toKey())
            }
            if (outboundInterface != null) {
                logDebug { "Sending to $destHex via path (${pathEntry.hops} hops) on ${outboundInterface.name}" }

                // Python-parity branching (Transport.py:980-1019):
                //   hops > 1  + HEADER_1  → wrap in HEADER_2 with nextHop as transport_id
                //   hops == 1 + shared-instance + HEADER_1 → same wrap (Python:993-1011)
                //   hops == 1 + direct                     → transmit packet.raw as-is
                //   hops > 1  + HEADER_2                   → fall through to broadcast
                //                                            (Python's own clients don't
                //                                            generate HEADER_2 outbound; if
                //                                            a caller supplies one, we don't
                //                                            double-wrap — identical to Python)
                val isHeader1 = packet.headerType == HeaderType.HEADER_1
                // Link DATA packets must never be HEADER_2-wrapped: their
                // destination_hash IS the linkId, which no transport's
                // identity matches, so any HEADER_2 wrap with `nextHop =
                // linkId` as transport_id is dropped by the intermediate
                // as "in transport for other transport instance".
                // Intermediate transports (Python and Kotlin) forward link
                // DATA by looking the linkId up in their own link_table —
                // that lookup requires HEADER_1 so it actually runs.
                val isLink = packet.destinationType == DestinationType.LINK
                when {
                    pathEntry.hops > 1 && isHeader1 && !isLink -> {
                        val transportRaw = insertIntoTransport(packet, pathEntry.nextHop)
                        transmit(outboundInterface, transportRaw)
                        sent = true
                    }
                    pathEntry.hops == 1 && isConnectedToSharedInstance && isHeader1 && !isLink -> {
                        // Python Transport.py:993-1011: a 1-hop destination behind a shared
                        // instance still needs transport wrapping so the instance forwards.
                        val transportRaw = insertIntoTransport(packet, pathEntry.nextHop)
                        transmit(outboundInterface, transportRaw)
                        sent = true
                    }
                    pathEntry.hops <= 1 || isLink -> {
                        // Direct transmission (hops==0 for self/local-client, hops==1 direct,
                        // or any Link destination — see isLink comment above).
                        transmit(outboundInterface, packedData)
                        sent = true
                    }
                    // pathEntry.hops > 1 but packet is already HEADER_2: fall through to
                    // broadcast below, matching Python's "sent stays False" behavior.
                }

                if (sent) {
                    // Update path timestamp. Optimistic identity-CAS: only
                    // touch if the entry is still ours; transmit's lock
                    // release may have allowed a fresher inbound update to
                    // replace pathTable[key]. See port-deviations.md
                    // (path/link table identity-CAS).
                    val key = packet.destinationHash.toKey()
                    if (pathTable[key] === pathEntry) {
                        val touched = pathEntry.touch()
                        pathTable[key] = touched
                        pathStore?.upsertPath(packet.destinationHash, touched)
                    }
                }
            } else {
                log("Path exists for $destHex but interface not found")
            }
        }

        if (!sent) {
            addPacketHash(packet.packetHash)

            // If packet has an attached interface, only send on that interface
            // Python: attached_interface restricts announce to a single interface
            val targetInterface = packet.attachedInterface
            if (targetInterface != null) {
                logDebug { "Sending to $destHex on attached interface ${targetInterface.name} (${packedData.size} bytes)" }
                if (targetInterface.canSend && targetInterface.online) {
                    transmit(targetInterface, packedData)
                    sent = true
                } else {
                    log("Attached interface ${targetInterface.name} is not available")
                }
            } else {
                // Broadcast on all interfaces
                logDebug {
                    // The name list existed only for this line; build it inside the lazy lambda.
                    val ifaceNames = interfaces.filter { it.canSend && it.online }.map { it.name }
                    "Broadcasting to $destHex on ${ifaceNames.size} interfaces: $ifaceNames (${packedData.size} bytes)"
                }

                for (iface in interfaces) {
                    if (!iface.canSend || !iface.online) continue

                    // LINK packets should only be sent on the link's attached interface
                    // (Python Transport.py:1031-1035)
                    if (packet.destinationType == DestinationType.LINK) {
                        val linkObj = packet.link
                        if (linkObj != null) {
                            val attachedHash = (linkObj as? Link)?.attachedInterfaceHash
                            if (attachedHash != null && !iface.hash.contentEquals(attachedHash)) {
                                continue
                            }
                        } else {
                            log("WARNING: LINK packet missing link reference, broadcasting on all interfaces")
                        }
                    }

                    if (packet.packetType == PacketType.ANNOUNCE) {
                        val isLocal = destinationIndex.containsKey(packet.destinationHash.toKey())
                        // An announce for a destination that is neither ours nor one we
                        // hold a route to is not broadcast at all (python Transport.py:
                        // 1452-1456, "next hop interface doesn't exist"). There is nothing
                        // we could vouch for: we did not originate it and cannot forward
                        // toward it, so emitting it would only seed a path nobody can use.
                        if (!isLocal && nextHopInterface(packet.destinationHash) == null) {
                            logDebug { "Blocking announce broadcast on ${iface.name}: next hop interface doesn't exist" }
                            continue
                        }
                        // Mode-based announce filtering for locally-originated announces
                        // (Python Transport.py:1040-1084). No source interface here — the
                        // announce originates on this node — so only the outgoing
                        // interface's own policy applies.
                        if (!AnnounceFilter.shouldForward(
                                outgoingMode = iface.mode,
                                isLocalDestination = isLocal,
                                sourceMode = null,
                                outgoingAnnouncesFromInternal = iface.announcesFromInternal,
                            )
                        ) continue
                    }

                    transmit(iface, packedData)
                    sent = true
                }
            }
        }

        // Create receipt after successful transmit, with guards matching Python Transport.py:947-956
        if (sent &&
            packet.createReceipt &&
            packet.packetType == PacketType.DATA &&
            packet.destination?.type != DestinationType.PLAIN &&
            !(packet.context.value in PacketContext.KEEPALIVE.value..PacketContext.LRPROOF.value) &&
            !(packet.context.value in PacketContext.RESOURCE.value..PacketContext.RESOURCE_RCL.value)
        ) {
            packet.receipt = PacketReceipt(packet)
            registerReceipt(packet.receipt!!)
        }

        return sent
    }

    /**
     * Transmit raw data on an interface.
     * Applies IFAC masking if the interface has IFAC enabled.
     *
     * Releases [jobsLock] across the blocking [InterfaceRef.send] call. The lock
     * scope of the caller (typically [outbound] or [inbound]) covers the routing
     * decision and any state writes, both of which complete before transmit is
     * called. The actual socket I/O may block — TCP write waiting for kernel
     * buffer drain, AutoInterface waiting on UDP socket, etc — and holding
     * jobsLock across that block prevents other threads from processing inbound
     * packets, including the very acks/requests we need to make progress on
     * resource transfers. Release+re-acquire pattern matches what
     * [raceInducerSleepReleasingJobsLock] does for tests; here it's a perf fix.
     *
     * Safety: the routing decision (path lookup, link table) is committed before
     * we get here. Concurrent transmits on the same interface are serialized
     * inside the interface's own send path. Re-acquiring jobsLock after the
     * write returns lets the caller's loop continue with whatever state mutated
     * during the released window — same posture as if the inbound packet that
     * mutated it had arrived a few microseconds later.
     */
    private fun transmit(
        interfaceRef: InterfaceRef,
        data: ByteArray,
    ) {
        try {
            val transmitData =
                if (interfaceRef.ifacIdentity != null && interfaceRef.ifacSize > 0) {
                    applyIfacMasking(data, interfaceRef)
                } else {
                    data
                }
            // Debug: log packet header bytes — all of it inside the lazy lambda; the two
            // "%02x" formats and the hex join previously ran per transmitted frame at INFO.
            if (data.size >= 19) {
                logDebug {
                    val flags = data[0].toInt() and 0xFF
                    val hops = data[1].toInt() and 0xFF
                    val destHash = data.copyOfRange(2, 18).joinToString("") { "%02x".format(it) }
                    val context = data[18].toInt() and 0xFF
                    "TX PACKET: flags=0x${"%02x".format(flags)} hops=$hops dest=${destHash.take(16)}... ctx=0x${"%02x".format(context)} size=${data.size}"
                }
            }

            val heldByCurrent = jobsLock.isHeldByCurrentThread
            val holdCount = if (heldByCurrent) jobsLock.holdCount else 0
            if (holdCount > 0) {
                repeat(holdCount) { jobsLock.unlock() }
            }
            try {
                interfaceRef.send(transmitData)
            } finally {
                if (holdCount > 0) {
                    repeat(holdCount) { jobsLock.lock() }
                }
            }

            trafficTxBytes += transmitData.size
            recordTxBytes(interfaceRef, transmitData.size)
        } catch (e: Exception) {
            log("Transmit error on ${interfaceRef.name}: ${e.message}")
        }
    }

    /**
     * Apply IFAC (Interface Access Code) masking to a packet.
     * This authenticates the packet for the specific interface.
     */
    private fun applyIfacMasking(
        raw: ByteArray,
        interfaceRef: InterfaceRef,
    ): ByteArray {
        val ifacIdentity = interfaceRef.ifacIdentity ?: return raw
        val ifacKey = interfaceRef.ifacKey ?: return raw
        val ifacSize = interfaceRef.ifacSize
        if (ifacSize <= 0) return raw

        // Calculate packet access code by signing and taking last ifacSize bytes
        val signature = ifacIdentity.sign(raw)
        val ifac = signature.copyOfRange(signature.size - ifacSize, signature.size)

        // Generate mask using HKDF
        val mask =
            crypto.hkdf(
                length = raw.size + ifacSize,
                ikm = ifac,
                salt = ifacKey,
                info = null,
            )

        // Set IFAC flag in header (bit 7)
        val newHeader =
            byteArrayOf(
                (raw[0].toInt() or 0x80).toByte(),
                raw[1],
            )

        // Assemble new payload: header + ifac + rest of packet
        val newRaw = newHeader + ifac + raw.copyOfRange(2, raw.size)

        // Mask the payload
        val maskedRaw = ByteArray(newRaw.size)
        for (i in newRaw.indices) {
            maskedRaw[i] =
                when {
                    i == 0 -> {
                        // Mask first header byte but keep IFAC flag set
                        ((newRaw[i].toInt() xor mask[i].toInt()) or 0x80).toByte()
                    }
                    i == 1 || i > ifacSize + 1 -> {
                        // Mask second header byte and payload (after IFAC)
                        (newRaw[i].toInt() xor mask[i].toInt()).toByte()
                    }
                    else -> {
                        // Don't mask the IFAC itself
                        newRaw[i]
                    }
                }
        }

        return maskedRaw
    }

    /**
     * Insert a packet into transport by adding HEADER_2.
     *
     * Expects a HEADER_1 input. Double-wrapping a HEADER_2 packet would shift the
     * original transport_id out of its expected offset and produce a malformed
     * packet whose destination hash lands in the wrong position — a silent
     * corruption the receiver would just drop. Enforce the invariant at the
     * entry point so any caller bug fails loudly in tests.
     */
    private fun insertIntoTransport(
        packet: Packet,
        nextHop: ByteArray,
    ): ByteArray {
        val raw = packet.raw ?: packet.pack()
        require(packet.headerType == HeaderType.HEADER_1) {
            "insertIntoTransport expects a HEADER_1 packet; got ${packet.headerType}"
        }

        // Build new flags with HEADER_2 and TRANSPORT type
        val newFlags =
            (HeaderType.HEADER_2.value shl 6) or
                (TransportType.TRANSPORT.value shl 4) or
                (raw[0].toInt() and 0x0F)

        // Build new packet: flags + hops + transport_id + rest of original
        val result = ByteArray(raw.size + RnsConstants.TRUNCATED_HASH_BYTES)
        result[0] = newFlags.toByte()
        result[1] = raw[1] // hops
        System.arraycopy(nextHop, 0, result, 2, RnsConstants.TRUNCATED_HASH_BYTES)
        System.arraycopy(raw, 2, result, 2 + RnsConstants.TRUNCATED_HASH_BYTES, raw.size - 2)

        return result
    }

    // ===== Packet Type Handlers =====

    /**
     * Extract the 5-byte big-endian emission timestamp from a 10-byte random_blob.
     *
     * The random_blob layout is 5 bytes of random material + 5 bytes of emission time
     * (seconds since epoch, big-endian). Matches Python Transport.py:2935-2936
     * `timebase_from_random_blob`.
     */
    private fun timebaseFromRandomBlob(randomBlob: ByteArray): Long {
        if (randomBlob.size < 10) return 0L
        var value = 0L
        for (i in 5..9) {
            value = (value shl 8) or (randomBlob[i].toLong() and 0xFF)
        }
        return value
    }

    /**
     * Take the max emission timestamp across a list of random_blobs. Matches Python
     * Transport.py:2938-2945 `timebase_from_random_blobs`.
     */
    private fun timebaseFromRandomBlobs(randomBlobs: List<ByteArray>): Long =
        randomBlobs.maxOfOrNull { timebaseFromRandomBlob(it) } ?: 0L

    private fun processAnnounce(
        packet: Packet,
        interfaceRef: InterfaceRef,
        // Result of the side-effect-free pre-drop validation in processInbound, so the
        // Ed25519 verify runs once per announce. Falls back to validating here if absent.
        preValidated: AnnounceData? = null,
    ) {
        // Validate and extract announce data (reusing the inbound pre-validation when supplied)
        val announceData = preValidated ?: validateAnnounce(packet)
        if (announceData == null) {
            logDebug { "Announce validation failed for ${packet.destinationHash.toHexString()}" }
            return
        }

        val destHash = packet.destinationHash
        val identity = announceData.identity
        val appData = announceData.appData

        // Store the identity and ratchet unconditionally on a valid announce,
        // matching Python Identity.validate_announce (Identity.py:457,478). These
        // must happen BEFORE the path-table should_add check because ratchet and
        // identity recall are needed for decryption regardless of whether the
        // announce also updates our routing path. The previous Kotlin placement
        // inside the should_add branch meant a stricter replacement rule (e.g.,
        // rejecting a re-announce that arrives within the same emission-second)
        // would silently drop ratchet rotation.
        Identity.remember(
            packetHash = packet.packetHash,
            destHash = destHash,
            publicKey = identity.getPublicKey(),
            appData = appData,
        )
        announceData.ratchet?.let { ratchet ->
            network.reticulum.destination.Destination.setRatchetForDestination(destHash, ratchet)
            Identity.rememberRatchet(destHash, ratchet)
        }

        // Announce counting and ingress-limit holding happen in preprocessInbound now, before
        // the packet is queued and before the identity above is remembered (python
        // Transport.py:1811-1825). An announce that reaches this point was not held.
        val destKey = destHash.toKey()

        // Skip announces for our own local destinations (Python Transport.py:2175-2176).
        // When connected to a shared instance, our own announces bounce back from the
        // transport node. Processing them would create erroneous 0-hop pathTable entries
        // that cause forwarding loops (e.g., LRPROOF loop via spurious link_table entries).
        val isLocalDestination = destinationIndex.containsKey(destKey)
        if (isLocalDestination) {
            // Surface announces for our own destination that we did NOT emit (a foreign
            // node announcing our identity — typically one identity in several installs).
            // Our own shared-instance echo is filtered by packetHash. The skip itself is
            // unchanged: we never process a local-destination announce into the path table.
            if (!selfAnnounceHashes.contains(packet.packetHash.toKey())) {
                onForeignLocalAnnounce?.let { handler ->
                    try {
                        handler(destHash.copyOf(), packet.packetHash.copyOf(), packet.hops)
                    } catch (e: Exception) {
                        log("Error in onForeignLocalAnnounce handler: ${e.message}")
                    }
                }
            }
            logDebug { "Skipping announce for local destination ${destHash.toHexString()}" }
            return
        }

        // Determine next hop: transport_id for HEADER_2 announces, dest hash for direct
        // Python Transport.py:1575-1600
        val receivedFrom =
            if (packet.transportId != null) {
                cancelPendingRebroadcastIfHeard(destHash, packet)
                packet.transportId!!.copyOf()
            } else {
                destHash.copyOf()
            }

        // Check if this announce should update the path table (Python:1604-1686).
        //
        // Python requires two conditions for a same-or-better-hop replacement:
        //   (a) random_blob has not been seen (replay protection), AND
        //   (b) announce_emitted > max(emission_time stored in random_blobs)
        // The Kotlin port previously checked only (a), which let stale announces
        // (e.g., a path_response holding an old cached route) overwrite a fresh
        // direct path if their random_blobs happened to differ. The worse-hop
        // branch is similarly emission-time-aware in Python.
        val existingEntry = pathTable[destHash.toKey()]
        val shouldAdd =
            if (existingEntry != null) {
                val announceEmitted = timebaseFromRandomBlob(announceData.randomHash)
                val pathTimebase = timebaseFromRandomBlobs(existingEntry.randomBlobs)
                val blobIsNew = !existingEntry.randomBlobs.any { it.contentEquals(announceData.randomHash) }

                if (packet.hops <= existingEntry.hops) {
                    // Equal or better hop count — accept only if blob is new AND the
                    // announce is strictly more recent than any existing blob. Python
                    // Transport.py:1620-1631.
                    blobIsNew && announceEmitted > pathTimebase
                } else {
                    // Worse hop count — accept only under specific conditions (Python
                    // Transport.py:1632-1681).
                    val now = System.currentTimeMillis()
                    when {
                        now >= existingEntry.expires -> blobIsNew
                        announceEmitted > pathTimebase -> blobIsNew
                        announceEmitted == pathTimebase && isPathUnresponsive(destHash) -> true
                        else -> false
                    }
                }
            } else {
                true // Unknown destination, always add
            }

        if (!shouldAdd) {
            // The reference stops here and the announce is forgotten. Before dropping it,
            // keep it as an alternate row if it arrived on a different interface than the
            // selected path: that is this node reaching the same destination a second way,
            // and it is precisely what there is nothing to fall back on today.
            //
            // Recording only. The path table, retransmission and everything on the wire
            // are untouched, so behaviour here is still the reference's
            // (`Transport.py:1620-1681`). B.3 makes the set selectable; until then this is
            // observation that a consumer can read through pathReachability.
            recordAlternatePath(
                destHash,
                PathEntry(
                    timestamp = System.currentTimeMillis(),
                    nextHop = receivedFrom,
                    hops = packet.hops,
                    expires = System.currentTimeMillis() +
                        AnnounceFilter.pathExpiryForMode(interfaceRef.mode),
                    randomBlobs = mutableListOf(announceData.randomHash),
                    receivingInterfaceHash = interfaceRef.hash,
                    announcePacketHash = packet.packetHash,
                ),
            )

            // Python: when should_add is False, no retransmission or path update happens.
            // Do NOT retransmit to local clients here — doing so would cause clients
            // to learn incorrect multi-hop paths to their own destinations from bounced
            // announces, breaking self-connect through shared instances.
            return
        }

        // python Transport.py — local_and_hops_condition gates path admission on
        // `packet.hops < PATHFINDER_M+1` (i.e. <= PATHFINDER_M). An announce that has
        // already traveled more than PATHFINDER_M hops is neither admitted to the path
        // table nor retransmitted.
        if (packet.hops > TransportConstants.PATHFINDER_M) {
            log("Dropping announce for ${destHash.toHexString()}: hops ${packet.hops} exceed PATHFINDER_M ceiling")
            return
        }

        // Update path table
        val randomBlobs = existingEntry?.randomBlobs?.toMutableList() ?: mutableListOf()
        if (!randomBlobs.any { it.contentEquals(announceData.randomHash) }) {
            randomBlobs.add(announceData.randomHash)
        }
        // Keep only the most recent random blobs (Python: MAX_RANDOM_BLOBS = 64)
        while (randomBlobs.size > 64) {
            randomBlobs.removeAt(0)
        }

        val pathEntry =
            PathEntry(
                timestamp = System.currentTimeMillis(),
                nextHop = receivedFrom,
                hops = packet.hops,
                expires = System.currentTimeMillis() + AnnounceFilter.pathExpiryForMode(interfaceRef.mode),
                randomBlobs = randomBlobs,
                receivingInterfaceHash = interfaceRef.hash,
                announcePacketHash = packet.packetHash,
            )

        // The row being replaced is a way through too, and until now it was the selected
        // one. It is displaced because this announce is more recent, not because the route
        // stopped working — the reference takes the newest announce whatever its hop count
        // (`Transport.py:2268`), so a node whose interfaces announce in turn replaces its
        // path each time and, before this, forgot the previous one every time.
        //
        // Recording only the announces that should_add REJECTS, as the first version did,
        // therefore missed the commonest source of a second route on a real node. Found by
        // the failover rig: two interfaces announcing a second apart left the set empty.
        // Recorded AFTER the new row is installed, not before: recordAlternatePath compares
        // the candidate against the currently selected row and refuses a row on the same
        // interface. Run first, it would be comparing the displaced row against itself.
        pathTable[destHash.toKey()] = pathEntry
        existingEntry?.let { recordAlternatePath(destHash, it) }
        pathStore?.upsertPath(destHash, pathEntry)

        // python Transport.py:2478-2481 — the announce resolves any in-flight path request
        // for this destination, so the next request for it is processed, not batched.
        inflightPathRequests.remove(destHash.toKey())

        // python Transport.py:2463: a path we were asked for counts as a use of the identity.
        if (pathRequests.containsKey(destHash.toKey())) Identity.usedDestinationData(destHash)
        answerWaitingDiscoveryRequests(packet, destHash)

        // If receiving interface has a tunnel, also store path in tunnel for persistence
        addPathToTunnel(
            destHash = destHash,
            pathEntry = pathEntry,
            packet = packet,
            interface_ = interfaceRef,
        )

        // Identity and ratchet are already stored above (before the should_add
        // branch), matching Python's validate_announce.

        logDebug { "Learned path to ${destHash.toHexString()} via ${interfaceRef.name} (${packet.hops} hops)" }

        // Notify announce handlers
        notifyAnnounceHandlers(
            destHash, identity, appData, packet.hops, interfaceRef.qualifiedName,
            packet.packetHash, packet.context == PacketContext.PATH_RESPONSE,
        )

        // Cache the announce packet for later path request responses
        // Python Transport.py:1867 — cache pre-increment raw announce to disk
        // Only cache if not connected to a shared instance (the server handles caching)
        if (!isConnectedToSharedInstance) {
            cacheAnnouncePacket(packet, interfaceRef)
        }

        retransmitAnnounceToLocalClients(packet, interfaceRef)

        // Retransmit if transport is enabled OR announce came from a local client.
        // PATH_RESPONSE is excluded to match Python Transport.py:1741 — path responses
        // are targeted replies to a specific requester and must not be rebroadcast as
        // fresh announces, which would inflate hop counts and flood the mesh.
        val fromLocal = fromLocalClient(interfaceRef)
        if ((transportEnabled || fromLocal) &&
            packet.context != PacketContext.PATH_RESPONSE &&
            packet.hops < TransportConstants.PATHFINDER_M
        ) {
            queueAnnounceRetransmit(destHash, packet, interfaceRef, fromLocalClient = fromLocal)
        }
    }

    /**
     * Notify registered announce handlers, applying aspect filtering.
     *
     * Mirrors Python Transport.py:1884-1896:
     * - If handler.aspect_filter is None → execute callback
     * - Otherwise, compute hash_from_name_and_identity(aspect_filter, identity)
     *   and only execute if it matches the packet's destination hash.
     */
    private fun notifyAnnounceHandlers(
        destHash: ByteArray,
        identity: Identity,
        appData: ByteArray?,
        hops: Int,
        interfaceName: String?,
        announcePacketHash: ByteArray? = null,
        isPathResponse: Boolean = false,
    ) {
        var resolvedAspect: String? = null // cached for multiple null-filter handlers
        var aspectResolved = false
        // python dispatches to EVERY matching handler (Transport.py:2035-2087):
        // the handler return value is ignored — there is no "first handler wins"
        // short-circuit — and per-handler exceptions are isolated.
        for (registered in announceHandlers) {
            try {
                val handler = registered.handler

                // Aspect filtering (Python Transport.py:2045-2047)
                val matchedAspect: String?
                if (registered.aspectFilter != null) {
                    val expectedHash =
                        Destination.hashFromNameAndIdentity(
                            registered.aspectFilter,
                            identity,
                        )
                    if (!expectedHash.contentEquals(destHash)) continue
                    matchedAspect = registered.aspectFilter
                } else {
                    // No filter — resolve aspect for RichAnnounceHandler callers
                    matchedAspect =
                        if (handler is RichAnnounceHandler) {
                            if (!aspectResolved) {
                                aspectResolved = true
                                resolveAspect(destHash, identity).also { resolvedAspect = it }
                            } else {
                                resolvedAspect
                            }
                        } else {
                            null
                        }
                }

                // PATH_RESPONSE gate (Transport.py:2049-2053): a path response
                // reaches a handler ONLY if it opts in via receivePathResponses;
                // a plain (non-Rich) handler never opts in, so it is skipped.
                if (isPathResponse) {
                    val wants = (handler as? RichAnnounceHandler)?.receivePathResponses == true
                    if (!wants) continue
                }

                if (handler is RichAnnounceHandler) {
                    handler.handleAnnounceWithContext(
                        destHash,
                        identity,
                        appData,
                        hops,
                        interfaceName,
                        matchedAspect,
                        announcePacketHash,
                    )
                } else {
                    handler.handleAnnounce(destHash, identity, appData)
                }
            } catch (e: Exception) {
                log("Announce handler error: ${e.message}")
            }
        }
    }

    /**
     * Resolve which aspect a destination hash belongs to by trying all
     * known aspects. Used when a null-filter RichAnnounceHandler needs
     * to know the aspect.
     */
    private fun resolveAspect(
        destHash: ByteArray,
        identity: Identity,
    ): String? {
        for (aspect in knownAspects) {
            try {
                val expectedHash = Destination.hashFromNameAndIdentity(aspect, identity)
                if (expectedHash.contentEquals(destHash)) return aspect
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Register an aspect for resolution by null-filter RichAnnounceHandlers.
     * Aspects from filtered handlers are automatically included.
     */
    fun registerKnownAspect(aspect: String) {
        knownAspects.add(aspect)
    }

    /**
     * Immediately retransmit an announce to all local client interfaces.
     * Python Transport.py:1790-1833
     *
     * Re-packages with HEADER_2 + TRANSPORT type + our identity as transport_id,
     * so local clients know the announce came through a transport node (us).
     */
    /**
     * python Transport.py:2431-2450: when an announce arrives for a destination that a
     * discovery path request is waiting on, the entry is popped and the announce is
     * sent as a PATH_RESPONSE, HEADER_2 with our identity as transport id, to every
     * interface that asked. Before this the requesters only got an answer if the
     * announce happened to be rebroadcast their way.
     */
    private fun answerWaitingDiscoveryRequests(
        packet: Packet,
        destHash: ByteArray,
    ) {
        val entry = discoveryPathRequests.remove(destHash.toKey()) ?: return
        val transportIdentityHash = Transport.identity?.hash ?: return
        for (requesting in entry.requestingInterfaces) {
            try {
                val answer =
                    Packet.createRaw(
                        destinationHash = packet.destinationHash,
                        data = packet.data,
                        packetType = PacketType.ANNOUNCE,
                        destinationType = packet.destinationType,
                        context = PacketContext.PATH_RESPONSE,
                        headerType = HeaderType.HEADER_2,
                        transportType = TransportType.TRANSPORT,
                        transportId = transportIdentityHash,
                        contextFlag = packet.contextFlag,
                        createReceipt = false,
                    )
                answer.hops = packet.hops
                requesting.send(answer.pack())
                logDebug { "Got matching announce, answering waiting discovery path request for ${destHash.toHexString()} on ${requesting.name}" }
            } catch (e: Exception) {
                log("Could not answer discovery path request on ${requesting.name}: ${e.message}")
            }
        }
    }

    private fun retransmitAnnounceToLocalClients(
        packet: Packet,
        receivingInterface: InterfaceRef,
    ) {
        if (localClientInterfaces.isEmpty()) return

        val transportIdentityHash = Transport.identity?.hash
        if (transportIdentityHash == null) {
            log("Cannot forward announce to local clients: no transport identity")
            return
        }

        logDebug { "Immediately retransmitting announce to ${localClientInterfaces.size} local clients" }

        val forwardPacket =
            Packet.createRaw(
                destinationHash = packet.destinationHash,
                data = packet.data,
                packetType = PacketType.ANNOUNCE,
                destinationType = packet.destinationType,
                context = PacketContext.NONE,
                headerType = HeaderType.HEADER_2,
                transportType = TransportType.TRANSPORT,
                transportId = transportIdentityHash,
                contextFlag = packet.contextFlag,
                createReceipt = false,
            )
        forwardPacket.hops = packet.hops
        val forwardRaw = forwardPacket.pack()

        for (localInterface in localClientInterfaces) {
            if (!localInterface.hash.contentEquals(receivingInterface.hash)) {
                try {
                    localInterface.send(forwardRaw)
                    logDebug { "Sent announce for ${packet.destinationHash.toHexString()} to local client ${localInterface.name}" }
                } catch (e: Exception) {
                    log("Error sending announce to local client ${localInterface.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * Replay our known destinations to a just-connected local client, re-packaged the same way
     * [retransmitAnnounceToLocalClients] does (HEADER_2 + TRANSPORT + our identity as
     * transport_id). Announces are only forwarded to clients connected at receive time, so a
     * client that joins after a destination announced would never learn it — and a local client
     * discovers destinations by scanning its own path table (it does not path-request), so
     * without this it stays blind to peers that announced earlier. Mirrors a shared instance
     * bringing a new client up to date. Only replays paths NOT learned via the new client
     * itself (it originated none yet, but guard for correctness).
     *
     * Called by the spawning server AFTER the client's interface is started/online (issue #74
     * registers before start, so registerInterface is too early — send() would fail). No-op if
     * the interface is not a tracked local client or is offline.
     */
    fun replayCachedAnnouncesToLocalClient(newClient: InterfaceRef) {
        if (!newClient.online) return
        if (localClientInterfaces.none { it.hash.contentEquals(newClient.hash) }) return
        val transportIdentityHash = Transport.identity?.hash ?: return
        for ((_, pathEntry) in pathTable) {
            if (pathEntry.isExpired()) continue
            if (newClient.hash.contentEquals(pathEntry.receivingInterfaceHash)) continue
            val cached = getCachedAnnouncePacket(pathEntry.announcePacketHash) ?: continue
            val announcePacket = Packet.unpack(cached.first) ?: continue
            val forwardPacket =
                Packet.createRaw(
                    destinationHash = announcePacket.destinationHash,
                    data = announcePacket.data,
                    packetType = PacketType.ANNOUNCE,
                    destinationType = announcePacket.destinationType,
                    context = PacketContext.NONE,
                    headerType = HeaderType.HEADER_2,
                    transportType = TransportType.TRANSPORT,
                    transportId = transportIdentityHash,
                    contextFlag = announcePacket.contextFlag,
                    createReceipt = false,
                )
            forwardPacket.hops = announcePacket.hops
            try {
                newClient.send(forwardPacket.pack())
                logDebug { "Replayed cached announce for ${announcePacket.destinationHash.toHexString()} to new local client ${newClient.name}" }
            } catch (e: Exception) {
                log("Error replaying cached announce to new local client ${newClient.name}: ${e.message}")
            }
        }
    }

    /**
     * Drop a pending rebroadcast when another node is heard carrying the same announce
     * (python `Transport.py:2182-2200`).
     *
     * This is what makes the deferred rebroadcast worth deferring. Two distinct signals,
     * and they mean different things:
     *
     *  - `hops - 1 == entry.hops`: a peer at OUR distance rebroadcast it, so the copy is
     *    circulating at this level. Count it; once `LOCAL_REBROADCASTS_MAX` of them have
     *    been heard and we have already sent ours, there is nothing left for us to add.
     *  - `hops - 1 == entry.hops + 1`: a peer one hop FURTHER out already carried our
     *    rebroadcast onward. If that happened before our retry was due, the retry would
     *    only duplicate what the network already has, so the entry is dropped outright.
     *
     * Both are gated on `retransmits > 0` — an entry that has not gone out yet cannot have
     * been passed on by anyone, and what we are hearing is somebody else's announce.
     */
    private fun cancelPendingRebroadcastIfHeard(destHash: ByteArray, packet: Packet) {
        if (!transportEnabled) return
        val destKey = destHash.toKey()
        val entry = announceTable[destKey] ?: return

        if (packet.hops - 1 == entry.hops) {
            entry.localRebroadcasts++
            if (entry.retransmits > 0 &&
                entry.localRebroadcasts >= TransportConstants.LOCAL_REBROADCASTS_MAX
            ) {
                logDebug {
                    "Completed announce processing for ${destHash.toHexString()}, " +
                        "local rebroadcast limit reached"
                }
                announceTable.remove(destKey)
                return
            }
        }

        if (packet.hops - 1 == entry.hops + 1 && entry.retransmits > 0) {
            if (System.currentTimeMillis() < entry.retransmitTimeout) {
                logDebug {
                    "Rebroadcasted announce for ${destHash.toHexString()} has been passed " +
                        "on to another node, no further tries needed"
                }
                announceTable.remove(destKey)
            }
        }
    }

    /**
     * Admit an announce to the retransmit table (python `Transport.py:2352-2372`).
     *
     * This does NOT emit anything. The reference defers every rebroadcast to a timed job
     * ([announceRetransmitJob]) so that a node which hears the same announce arrive from a
     * better-placed peer inside the grace window can drop its own pending rebroadcast
     * instead of adding another copy to the network. Emitting on receipt, as this used to,
     * makes that cancellation impossible — the packet is already gone — and a dense mesh
     * then carries one rebroadcast per node rather than one per region.
     *
     * The retransmit timeout is a RANDOM point inside `PATHFINDER_RW`, which is what stops
     * every node that heard the same announce from rebroadcasting in the same instant.
     */
    private fun queueAnnounceRetransmit(
        destinationHash: ByteArray,
        packet: Packet,
        receivingInterface: InterfaceRef,
        fromLocalClient: Boolean = false,
        blockRebroadcasts: Boolean = false,
        attachedInterface: InterfaceRef? = null,
        hopsOverride: Int? = null,
        retransmitAt: Long? = null,
        receivedFrom: ByteArray? = null,
    ) {
        val destKey = destinationHash.toKey()
        val now = System.currentTimeMillis()

        // A rate-blocked destination is not admitted to the retransmit table, so its
        // announce is never rebroadcast — but the caller has already updated the path
        // table, which is deliberate: blocking suppresses propagation, not learning.
        if (!blockRebroadcasts && applyAnnounceRateLimit(destKey, receivingInterface, now)) {
            logDebug {
                "Blocking rebroadcast of announce from ${destinationHash.toHexString()} " +
                    "due to excessive announce rate"
            }
            return
        }

        // An announce from a local client is emitted immediately and exactly once: retries
        // starts at PATHFINDER_R so the job completes the entry after that single send
        // (python Transport.py:2358-2361). A path-request answer is the same shape.
        var retries = 0
        var retransmitTimeout =
            now + (Math.random() * TransportConstants.PATHFINDER_RW * 1000).toLong()
        if (fromLocalClient || blockRebroadcasts) {
            retransmitTimeout = now
            retries = TransportConstants.PATHFINDER_R
        }
        retransmitAt?.let { retransmitTimeout = it }

        // python Transport.py:3521: queuing an announce for rebroadcast is a use of the
        // identity, except on a shared-instance client.
        if (!isConnectedToSharedInstance) Identity.usedDestinationData(destinationHash)
        announceTable[destKey] = AnnounceEntry(
            destinationHash = destinationHash.copyOf(),
            timestamp = now,
            retransmits = retries,
            retransmitTimeout = retransmitTimeout,
            raw = packet.raw ?: packet.pack(),
            hops = hopsOverride ?: packet.hops,
            receivingInterfaceHash = receivingInterface.hash,
            localRebroadcasts = 0,
            blockRebroadcasts = blockRebroadcasts,
            attachedInterfaceHash = attachedInterface?.hash,
            receivedFrom = receivedFrom
                ?: packet.transportId?.copyOf()
                ?: destinationHash.copyOf(),
        )
    }

    /**
     * Advance a destination's announce-rate state and report whether it is blocked
     * (python `Transport.py:2301-2330`).
     *
     * Only runs on an interface that set `announce_rate_target`; without it the reference
     * does not build a rate entry at all, so neither do we. PATH_RESPONSE announces are
     * exempt and never reach here — a response is solicited traffic, and counting it
     * against the destination would let a third party get a destination blocked simply by
     * requesting its path repeatedly.
     */
    private fun applyAnnounceRateLimit(
        destKey: ByteArrayKey,
        receivingInterface: InterfaceRef,
        now: Long,
    ): Boolean {
        val target = receivingInterface.announceRateTarget ?: return false
        val existing = announceRateTable[destKey]
        if (existing == null) {
            // First announce from this destination: recorded, never blocked. There is no
            // previous arrival to measure a rate against yet.
            announceRateTable[destKey] = AnnounceRateEntry(
                last = now,
                timestamps = mutableListOf(now),
            )
            return false
        }

        existing.timestamps.add(now)
        while (existing.timestamps.size > TransportConstants.MAX_RATE_TIMESTAMPS) {
            existing.timestamps.removeAt(0)
        }

        if (now <= existing.blockedUntil) return true

        val currentRate = now - existing.last
        if (currentRate < target * 1000L) {
            existing.rateViolations++
        } else {
            existing.rateViolations = maxOf(0, existing.rateViolations - 1)
        }

        if (existing.rateViolations > receivingInterface.announceRateGrace) {
            // Measured from `last`, which is NOT advanced here: a destination that keeps
            // announcing while blocked cannot push its own unblock time further out.
            existing.blockedUntil = existing.last +
                (target * 1000L) +
                (receivingInterface.announceRatePenalty * 1000L)
            return true
        }

        existing.last = now
        return false
    }

    /** Announce-rate state for a destination, or null if none has been recorded. */
    fun announceRateEntry(destHash: ByteArray): AnnounceRateEntry? =
        announceRateTable[destHash.toKey()]

    /**
     * Emit one pending announce rebroadcast.
     *
     * Split out of the admission path so the timed job owns every emission. The packet is
     * rebuilt here rather than at admission because its context depends on
     * [AnnounceEntry.blockRebroadcasts], which a later path request can set.
     */
    private fun emitAnnounceRebroadcast(entry: AnnounceEntry) {
        val packet = Packet.unpack(entry.raw) ?: run {
            log("Cannot rebroadcast announce for ${entry.destinationHash.toHexString()}: unpack failed")
            return
        }
        val destinationHash = entry.destinationHash
        val now = System.currentTimeMillis()
        val receivingInterface = findInterfaceByHash(entry.receivingInterfaceHash)
        val attached = entry.attachedInterfaceHash?.let { findInterfaceByHash(it) }

        // Create a HEADER_2 retransmission packet with our transport identity.
        // Matches Python Transport.py:544-556 — transport nodes always re-wrap
        // announces as HEADER_2 with transport_type=TRANSPORT and their own
        // identity hash as transport_id, so recipients know the announce came
        // through a transport node and can route back through it.
        val transportIdentityHash = Transport.identity?.hash
        if (transportIdentityHash == null) {
            log("Cannot retransmit announce: no transport identity")
            return
        }

        val retransmitPacket =
            Packet.createRaw(
                destinationHash = packet.destinationHash,
                data = packet.data,
                packetType = PacketType.ANNOUNCE,
                destinationType = packet.destinationType,
                // python Transport.py:783-786: a rebroadcast answering a path request goes
                // out as PATH_RESPONSE, which tells every hearer not to carry it onward. A
                // plain announce would invite exactly that.
                context = if (entry.blockRebroadcasts) {
                    PacketContext.PATH_RESPONSE
                } else {
                    PacketContext.NONE
                },
                headerType = HeaderType.HEADER_2,
                transportType = TransportType.TRANSPORT,
                transportId = transportIdentityHash,
                contextFlag = packet.contextFlag,
                createReceipt = false,
            )
        // The STORED hop count, not the packet's: an entry re-armed by a path request
        // carries the hop count read from the path table (python Transport.py:812).
        retransmitPacket.hops = entry.hops
        val retransmitRaw = retransmitPacket.pack()

        // Queue on all interfaces except the receiving one, applying mode-based filtering
        // Matches Python Transport.py:1040-1084
        // Python: announce retransmit goes through outbound() which checks `interface.OUT`.
        // Spawned local client interfaces have OUT=False in Python (LocalInterface.py:417),
        // so they are excluded from announce retransmission. Local clients receive announces
        // through retransmitAnnounceToLocalClients() instead.
        //
        // If the packet has an attachedInterface set, this is a targeted emission
        // (e.g., a path response replying to a specific requester). Restrict to that
        // interface only, matching Python's `attached_interface` semantics in
        // Transport.py:2781 where path-response announces carry the requesting
        // interface as their attached_interface.
        val isLocal = destinationIndex.containsKey(destinationHash.toKey())
        val sourceInterface = nextHopInterface(destinationHash)
        val sourceMode = sourceInterface?.mode
        val targetInterface = attached ?: packet.attachedInterface

        // Targeted path-response addressed to a local client: spawned local-client
        // interfaces have OUT=false and are skipped by the broadcast loop below, so a
        // response explicitly attached to one would be dropped. Deliver it directly,
        // mirroring retransmitAnnounceToLocalClients()'s direct send. (Python answers a
        // local client's path request via the announce_table retransmit, which reaches
        // the requesting local-client interface — Transport.py:3000 + retransmit loop.)
        if (targetInterface != null && isLocalClientInterface(targetInterface)) {
            runCatching { targetInterface.send(retransmitRaw) }
                .onFailure { log("Error sending targeted path response to local client ${targetInterface.name}: ${it.message}") }
            return
        }

        for (iface in interfaces) {
            if (!iface.canSend || !iface.online || isLocalClientInterface(iface)) {
                continue
            }

            if (targetInterface != null) {
                // Targeted emission (path response): emit ONLY on the attached
                // interface, ignoring the receiving-interface skip. The skip
                // rule exists to prevent broadcast announce loops; it doesn't
                // apply when the caller has explicitly asked us to reply on a
                // specific interface, and applying it here would silently
                // drop the packet when targetInterface == receivingInterface
                // (the common case for path_request handling where
                // originalInterface is null and the fallback resolves to
                // receivingInterface).
                if (!iface.hash.contentEquals(targetInterface.hash)) {
                    continue
                }
            } else {
                // Broadcast retransmit: skip the interface we received on to
                // avoid re-emitting an announce back to its source (Python
                // loop-prevention via packet hashlist is the real guard;
                // this is a cheap local optimization).
                if (receivingInterface != null && iface.hash.contentEquals(receivingInterface.hash)) {
                    continue
                }
            }

            // The outgoing interface decides whether it will carry announces that came
            // from an internal segment; the SOURCE interface decides whether its announces
            // may be pushed onto one (python Transport.py:1471, :1479-1489).
            if (AnnounceFilter.shouldForward(
                    outgoingMode = iface.mode,
                    isLocalDestination = isLocal,
                    sourceMode = sourceMode,
                    outgoingAnnouncesFromInternal = iface.announcesFromInternal,
                    sourceAnnouncesToInternal = sourceInterface?.announcesToInternal,
                )
            ) {
                queueAnnounce(
                    destinationHash = destinationHash,
                    raw = retransmitRaw,
                    interfaceRef = iface,
                    hops = entry.hops,
                    emitted = now,
                )
            }
        }
    }

    /**
     * Emit the announce rebroadcasts whose grace window has expired, and retire the
     * entries that are finished (python `Transport.jobs`, Transport.py:765-830).
     *
     * Two independent completion conditions, both from the reference: an entry that has
     * been rebroadcast at least once and has since heard `LOCAL_REBROADCASTS_MAX` local
     * copies is done, and one that has exhausted `PATHFINDER_R` retries is done. Neither
     * is a timeout — an announce nobody else repeats gets retried, and one the
     * neighbourhood is already carrying gets dropped.
     *
     * The batch is emitted in ascending hop order (python `handle_outgoing_announces`,
     * Transport.py:1268-1269), so a peer choosing between paths sees the shorter one first.
     */
    private fun announceRetransmitJob(now: Long) {
        val completed = mutableListOf<ByteArrayKey>()
        val outgoing = mutableListOf<AnnounceEntry>()

        for ((destKey, entry) in announceTable) {
            if (entry.retransmits > 0 &&
                entry.localRebroadcasts >= TransportConstants.LOCAL_REBROADCASTS_MAX
            ) {
                logDebug {
                    "Completed announce processing for ${entry.destinationHash.toHexString()}, " +
                        "local rebroadcast limit reached"
                }
                completed.add(destKey)
            } else if (entry.retransmits > TransportConstants.PATHFINDER_R) {
                logDebug {
                    "Completed announce processing for ${entry.destinationHash.toHexString()}, " +
                        "retry limit reached"
                }
                completed.add(destKey)
            } else if (now > entry.retransmitTimeout) {
                entry.retransmitTimeout = now +
                    (TransportConstants.PATHFINDER_G * 1000L) +
                    (TransportConstants.PATHFINDER_RW * 1000).toLong()
                entry.retransmits++
                outgoing.add(entry)

                // A path request that arrived while this announce was still pending stashed
                // the original entry; reinstate it now the request has been served
                // (python Transport.py:823-826).
                heldAnnounceEntries.remove(destKey)?.let {
                    announceTable[destKey] = it
                    logDebug { "Reinserting held announce into table" }
                }
            }
        }

        for (destKey in completed) announceTable.remove(destKey)
        for (entry in outgoing.sortedBy { it.hops }) emitAnnounceRebroadcast(entry)
    }

    private fun processData(
        packet: Packet,
        interfaceRef: InterfaceRef,
    ) {
        logDebug { "processData: dest=${packet.destinationHash.toHexString()}, ${packet.data.size} bytes from ${interfaceRef.name}" }

        // Check if this is a control packet (path request, tunnel synthesis, etc.)
        if (controlHashes.contains(packet.destinationHash.toKey())) {
            // Check if this is a path request
            if (pathRequestDestination != null &&
                packet.destinationHash.contentEquals(pathRequestDestination!!.hash)
            ) {
                handlePathRequest(packet.data, packet, interfaceRef)
                return
            }

            // Check if this is a tunnel synthesis request
            if (tunnelSynthesizeDestination != null &&
                packet.destinationHash.contentEquals(tunnelSynthesizeDestination!!.hash)
            ) {
                log("Received tunnel synthesis request on ${interfaceRef.name}")
                tunnelSynthesizeHandler(packet.data, packet, interfaceRef)
                return
            }
        }

        // Check if this is for a local destination
        val destination = findDestination(packet.destinationHash)
        logDebug { "processData: findDestination result = ${destination?.hexHash ?: "null"}" }

        // python Transport.py:2155 — local delivery requires the destination's type
        // to match the packet's destination_type. A SINGLE packet whose hash collides
        // with a locally-registered PLAIN destination (or vice-versa) is NOT delivered.
        if (destination != null && destination.type == packet.destinationType) {
            // Deliver locally
            deliverPacket(destination, packet)
            return
        }

        // Check if this is data for a local link (destination hash is a link_id)
        // Python iterates ALL matching links and checks attached_interface (Transport.py:1971-1984)
        //
        // python Transport.py:2571 gates the active-link lookup on
        // `packet.destination_type == RNS.Destination.LINK`. Without that gate a
        // captured link DATA packet whose destination-type bits are rewritten to
        // PLAIN/GROUP (and hops set to 0) bypasses the packet hashlist entirely ΓÇö
        // packetFilter never deduplicates PLAIN/GROUP packets ΓÇö and is decrypted
        // and re-delivered to link.receive without limit, replaying authenticated
        // link traffic to the application.
        val key = packet.destinationHash.toKey()
        val matchingLinks =
            if (packet.destinationType == DestinationType.LINK) {
                activeLinks.filter { it.linkId.toKey() == key }
            } else {
                emptyList()
            }
        if (matchingLinks.isNotEmpty()) {
            for (link in matchingLinks) {
                // Python: if link.attached_interface == packet.receiving_interface
                val attachedHash = link.attachedInterfaceHash
                val pktIfaceHash = packet.receivingInterfaceHash
                if (attachedHash != null && pktIfaceHash != null && !attachedHash.contentEquals(pktIfaceHash)) {
                    log("WARNING: Link interface mismatch on ${packet.destinationHash.toHexString()}, potential communication manipulation or misconfiguration")
                    continue
                }
                if (packet.context == PacketContext.CACHE_REQUEST) {
                    // python Transport.py:2576-2582: answer from the packet cache by
                    // re-sending the cached packet's data over the link; nothing else.
                    val cached = getCachedPacket(packet.data)
                    if (cached != null) {
                        val cachedPacket = Packet.unpack(cached.raw)
                        if (cachedPacket != null) {
                            try {
                                Packet.createRaw(
                                    destinationHash = link.linkId,
                                    data = cachedPacket.data,
                                    packetType = cachedPacket.packetType,
                                    destinationType = DestinationType.LINK,
                                    context = cachedPacket.context,
                                    mtu = link.mtu,
                                ).send()
                            } catch (e: Exception) {
                                log("Could not answer cache request on $link: ${e.message}")
                            }
                        }
                    }
                    continue
                }
                try {
                    link.receive(packet)
                } catch (e: Exception) {
                    log("Failed to deliver to local link: ${e.message}")
                }
            }
            return
        }

        // A HEADER_2 packet addressed to us as transport_id is relayed EXACTLY ONCE,
        // by the general transport-relay block (Transport.py:1404-1510), which runs
        // earlier in processInbound and gates on transport being enabled / a
        // local-client flow. Python's DATA dispatch (Transport.py:2082-2160) only
        // delivers locally — it never re-forwards. Re-forwarding here would both
        // double-emit (the general block already sent it) and forward even when
        // transport is disabled. Skip those packets; the reverse entry was already
        // created by the general block (Transport.py:1495-1501).
        val myHash = identity?.hash
        if (packet.transportId != null && myHash != null && packet.transportId!!.contentEquals(myHash)) {
            return
        }

        // python Transport.py:1997 ΓÇö forwarding of any kind happens only inside
        // `if transport_enabled() or from_local_client or for_local_client or
        // for_local_client_link:`. A node with transport disabled must never relay
        // packets between its interfaces (including from an open interface into an
        // IFAC-protected one), and must not grow reverseTable for foreign traffic.
        // A for_local_client packet already had its transport_id synthesised in
        // processInbound and was relayed by the general block (then returned by the
        // transport_id == myHash check above); what remains reaching here is the
        // shared-instance flow from a local client, so gate on that.
        if (!transportEnabled && !fromLocalClient(interfaceRef)) {
            return
        }

        // Check if we have a path to forward this packet
        val pathEntry = pathTable[packet.destinationHash.toKey()]
        if (pathEntry != null) {
            val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash)
            if (outboundInterface != null && !outboundInterface.hash.contentEquals(interfaceRef.hash)) {
                logDebug { "Forwarding data packet for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}" }
                transmit(outboundInterface, packet.raw ?: packet.pack())

                // Create reverse entry for proof routing back to sender
                // This is critical for shared instance clients to receive delivery proofs
                val reverseEntry =
                    ReverseEntry(
                        receivingInterfaceHash = interfaceRef.hash,
                        outboundInterfaceHash = outboundInterface.hash,
                        timestamp = System.currentTimeMillis(),
                    )
                reverseTable[packet.truncatedHash.toKey()] = reverseEntry
                logDebug { "Created reverse entry for proof routing: ${packet.truncatedHash.toHexString()} -> ${interfaceRef.name}" }

                return
            }
        }

        // Link-table forwarding for in-transit data/proof packets now lives in
        // processInboundItem() before the type dispatch (see forwardViaLinkTable),
        // so PROOF packets on an active link get forwarded too. Prior to that
        // move, this block sat here and RESOURCE_PRF was silently dropped on
        // hub nodes because processProof doesn't carry a link_table lookup.
    }

    private fun processLinkRequest(
        packet: Packet,
        interfaceRef: InterfaceRef,
    ) {
        // Python Transport.py:1937-1966: Only deliver locally if transport_id is null
        // (HEADER_1/direct) or matches our identity (we are the final transport hop).
        // Transport forwarding for HEADER_2 packets is handled in processInboundItem().
        val myHash = identity?.hash
        if (packet.transportId != null && (myHash == null || !packet.transportId!!.contentEquals(myHash))) {
            return
        }

        // python Transport.py:2543 — `if destination and destination.type ==
        // packet.destination_type:`. A LINKREQUEST with its destination-type bits
        // rewritten to PLAIN/GROUP skips the hashlist in packetFilter, so without
        // this check every replay of one captured request costs a fresh ECDH +
        // signature + LRPROOF transmit and a new half-open Link object.
        val destination = findDestination(packet.destinationHash)
        if (destination != null && destination.type == packet.destinationType) {
            // Clamp MTU signalling to receiving interface (Python Transport.py:1938-1963)
            clampLinkRequestMtuForLocal(packet, interfaceRef)
            deliverPacket(destination, packet)
        }
    }

    /**
     * Clamp link MTU signalling bytes in raw wire data for forwarding.
     * Matches Python Transport.py:1453-1480.
     *
     * @param raw The raw wire bytes to potentially modify
     * @param packet The parsed packet (for accessing data portion)
     * @param outboundInterface The interface we're forwarding to
     * @param receivingInterface The interface we received from
     * @return Modified raw bytes with clamped MTU, or stripped MTU bytes
     */
    private fun clampLinkRequestMtuForForwarding(
        raw: ByteArray,
        packet: Packet,
        outboundInterface: InterfaceRef,
        receivingInterface: InterfaceRef,
    ): ByteArray? {
        val pathMtu = Link.mtuFromLrPacket(packet) ?: return raw
        val mode = Link.modeFromLrPacket(packet)

        // If outbound interface doesn't support MTU discovery, strip the MTU bytes
        if (!outboundInterface.supportsLinkMtuDiscovery) {
            log("Outbound interface ${outboundInterface.name} doesn't support MTU discovery, stripping signalling bytes")
            return raw.copyOf(raw.size - LinkConstants.LINK_MTU_SIZE)
        }

        // Clamp to min(next_hop, prev_hop) if either is less than current path MTU
        val nhMtu = outboundInterface.hwMtu
        val phMtu = if (receivingInterface.supportsLinkMtuDiscovery) receivingInterface.hwMtu else null

        if (nhMtu < pathMtu || (phMtu != null && phMtu < pathMtu)) {
            val clampedMtu = if (phMtu != null) minOf(nhMtu, phMtu) else nhMtu
            return try {
                val clampedBytes = Link.signallingBytes(clampedMtu, mode)
                log("Clamping link MTU from $pathMtu to $clampedMtu")
                val result = raw.copyOf()
                System.arraycopy(clampedBytes, 0, result, result.size - LinkConstants.LINK_MTU_SIZE, LinkConstants.LINK_MTU_SIZE)
                result
            } catch (e: Exception) {
                // python: protocol_violation("Undecodable path MTU signalling bytes") + return.
                // Forwarding the request unmodified would advertise an MTU this hop cannot
                // carry, which is the failure this function exists to prevent.
                receivingInterface.protocolViolation("Undecodable path MTU signalling bytes")
                log("Error clamping link MTU: ${e.message}, dropping link request")
                null
            }
        }

        return raw
    }

    /**
     * Clamp link MTU signalling in packet.data for local delivery.
     * Matches Python Transport.py:1938-1963.
     *
     * Modifies packet.data in-place to clamp signalling bytes to the
     * receiving interface's MTU.
     */
    private fun clampLinkRequestMtuForLocal(
        packet: Packet,
        receivingInterface: InterfaceRef,
    ) {
        val pathMtu = Link.mtuFromLrPacket(packet) ?: return
        val mode = Link.modeFromLrPacket(packet)

        // Match Python Transport.py:1938-1963
        // Python checks: if HW_MTU is None → strip signalling bytes
        //                 elif AUTOCONFIGURE_MTU or FIXED_MTU → use HW_MTU for clamping
        //                 else → use default MTU (500) for clamping
        val nhMtu =
            if (receivingInterface.supportsLinkMtuDiscovery) {
                receivingInterface.hwMtu
            } else if (receivingInterface.hwMtu > 0) {
                // Interface has a HW_MTU but doesn't auto-configure — use default MTU
                RnsConstants.MTU
            } else {
                // No HW_MTU at all — strip signalling bytes (Python: HW_MTU == None)
                log("No next-hop HW MTU, stripping link MTU signalling bytes")
                packet.data = packet.data.copyOf(packet.data.size - LinkConstants.LINK_MTU_SIZE)
                return
            }

        if (nhMtu < pathMtu) {
            try {
                val clampedBytes = Link.signallingBytes(nhMtu, mode)
                log("Clamping link MTU to $nhMtu for local delivery")
                System.arraycopy(
                    clampedBytes,
                    0,
                    packet.data,
                    packet.data.size - LinkConstants.LINK_MTU_SIZE,
                    LinkConstants.LINK_MTU_SIZE,
                )
            } catch (e: Exception) {
                log("Error clamping link MTU for local delivery: ${e.message}")
            }
        }
    }

    /**
     * Validate an LRPROOF signature before forwarding it through the link table.
     * Matches Python Transport.py:781-802.
     *
     * Returns true if the proof signature is valid, false otherwise.
     * If the peer identity cannot be recalled the proof cannot be checked and is
     * NOT forwarded (python Transport.py:2650-2651: `Identity.recall(...)` returning
     * None raises on `.get_public_key()` and the except at :2670 drops the proof).
     */
    private fun validateLrproofForTransport(packet: Packet, linkEntry: LinkEntry): Boolean {
        val sigLength = RnsConstants.SIGNATURE_SIZE
        val pubSize = LinkConstants.KEYSIZE

        // Check data length matches expected LRPROOF format (Python:781)
        val expectedLen = sigLength + pubSize
        val expectedLenWithMtu = expectedLen + LinkConstants.LINK_MTU_SIZE
        if (packet.data.size != expectedLen && packet.data.size != expectedLenWithMtu) {
            log("LRPROOF data size mismatch: ${packet.data.size} (expected $expectedLen or $expectedLenWithMtu)")
            return false
        }

        // Extract signature and peer public key
        val signature = packet.data.copyOfRange(0, sigLength)
        val peerPubBytes = packet.data.copyOfRange(sigLength, sigLength + pubSize)

        // Recall the peer identity for the destination (Python:787)
        // noUse: transport bookkeeping, not an application using the identity. python's
        // recall here (Transport.py:2650) marks nothing persistent; ours upserted the
        // identity store on every call, one DB write per LRPROOF a peer chose to send.
        val peerIdentity = Identity.recall(linkEntry.destinationHash, noUse = true)
        if (peerIdentity == null) {
            // python Transport.py:2650-2651/2670 — no identity, no signature check,
            // no forwarding. Forwarding "optimistically" let a forged LRPROOF of the
            // right length transit us and flip the link-table entry to validated.
            log("Cannot recall identity for ${linkEntry.destinationHash.toHexString()}, not transporting LRPROOF")
            return false
        }

        val peerSigPubBytes = peerIdentity.getPublicKey()
            .copyOfRange(LinkConstants.KEYSIZE, LinkConstants.ECPUBSIZE)

        // Build signalling bytes if MTU info present
        var signallingBytes = ByteArray(0)
        if (packet.data.size == expectedLenWithMtu) {
            val mtuBytes = packet.data.copyOfRange(expectedLen, expectedLenWithMtu)
            signallingBytes = mtuBytes
        }

        // Build signed data (Python:790)
        val signedData = packet.destinationHash + peerPubBytes + peerSigPubBytes + signallingBytes

        return peerIdentity.validate(signature, signedData)
    }

    private fun processProof(
        packet: Packet,
        interfaceRef: InterfaceRef,
    ) {
        logDebug { "Processing proof: dest=${packet.destinationHash.toHexString()}, context=${packet.context}, from ${interfaceRef.name}" }

        // Proof handling uses when {} to match the Python if/elif/else structure
        // (Transport.py:2013-2115). This prevents LRPROOF and RESOURCE_PRF from
        // ever falling through to the reverse_table handler — in Python, only the
        // else branch (non-LRPROOF, non-RESOURCE_PRF) reaches reverse_table.
        // Without this structure, an unroutable LRPROOF on a shared instance client
        // would bounce via reverse_table back to the server in an infinite loop.
        when (packet.context) {
            // LRPROOF (Link Request Proof) — Transport.py:2013-2073
            // Check pending links FIRST — when hub and client share the same Transport
            // (same-process), the proof must be delivered locally before attempting to forward.
            PacketContext.LRPROOF -> {
                val pendingLink = pendingLinks.find { it.linkId.toKey() == packet.destinationHash.toKey() }
                if (pendingLink != null) {
                    // Hop count validation (Python Transport.py:828):
                    // Check that the LRPROOF traveled the expected number of hops.
                    // Also allow PATHFINDER_M as a fallback when hops were unknown
                    // at link creation time.
                    val expectedHops = pendingLink.expectedHops

                    if (packet.hops != expectedHops &&
                        expectedHops != TransportConstants.PATHFINDER_M
                    ) {
                        log("LRPROOF hop mismatch for pending link: packet.hops=${packet.hops} != expectedHops=$expectedHops, ignoring")
                        return
                    }

                    // Add to packet hash filter BEFORE validation (Python Transport.py:832).
                    // This prevents duplicate LRPROOFs from being re-processed after the
                    // link transitions from pendingLinks to activeLinks.
                    addPacketHash(packet.packetHash)

                    logDebug { "Delivering LRPROOF to pending link ${packet.destinationHash.toHexString()}" }
                    try {
                        if (!pendingLink.validateProof(packet)) {
                            log("LRPROOF validation failed for link ${packet.destinationHash.toHexString()}, proof consumed")
                        }
                    } catch (e: Exception) {
                        log("Error validating link proof for ${packet.destinationHash.toHexString()}: ${e.message}")
                    }
                    return
                }

                // No local pending link — forward via link table
                // Matches Python Transport.py:2016-2039
                val linkEntry = linkTable[packet.destinationHash.toKey()]
                if (linkEntry != null) {
                    // Check hop count matches remaining hops (Python:2018)
                    if (packet.hops != linkEntry.remainingHops) {
                        log("LRPROOF hop mismatch: packet.hops=${packet.hops} != remainingHops=${linkEntry.remainingHops}, dropping")
                        return
                    }

                    // Check proof arrived on the expected next-hop interface (Python:2019)
                    val nhIface = findInterfaceByHash(linkEntry.nextHopInterfaceHash)
                    if (nhIface == null || !interfaceRef.hash.contentEquals(nhIface.hash)) {
                        log("LRPROOF received on wrong interface, not transporting it")
                        return
                    }

                    // Validate LRPROOF signature before forwarding (Python Transport.py:781-802).
                    // This prevents invalid proofs from being propagated through the network.
                    val proofValid = try {
                        validateLrproofForTransport(packet, linkEntry)
                    } catch (e: Exception) {
                        log("Error validating LRPROOF for transport: ${e.message}")
                        false
                    }
                    if (!proofValid) {
                        log("Invalid LRPROOF signature for ${packet.destinationHash.toHexString()}, dropping")
                        return
                    }

                    // python Transport.py:2661-2664 — validated is set only inside the
                    // signature-valid branch, immediately before the transmit toward
                    // the initiator. If the interface to forward on is gone there is
                    // nothing to validate the entry for; leave it unvalidated so it is
                    // culled at proofTimeout instead of living LINK_TIMEOUT.
                    val outboundInterface = findInterfaceByHash(linkEntry.receivingInterfaceHash)
                    if (outboundInterface == null) {
                        log("LRPROOF for ${packet.destinationHash.toHexString()} validated but the receiving interface is gone, not transporting it")
                        return
                    }
                    // Update hop byte in raw before forwarding (Python:2035-2036)
                    val raw = packet.raw ?: packet.pack()
                    val newRaw = raw.copyOf()
                    newRaw[1] = packet.hops.toByte()
                    logDebug { "Forwarding LRPROOF for ${packet.destinationHash.toHexString()} via ${outboundInterface.name}" }
                    linkEntry.validated = true
                    transmit(outboundInterface, newRaw)
                    return
                }

                log("LRPROOF dest=${packet.destinationHash.toHexString()} not found in pending_links (${pendingLinks.size}) or link_table (${linkTable.size})")
            }

            // RESOURCE_PRF (Resource Proof) — Transport.py:2075-2078
            PacketContext.RESOURCE_PRF -> {
                val activeLink = activeLinks.find { it.linkId.toKey() == packet.destinationHash.toKey() }
                if (activeLink != null) {
                    logDebug { "Delivering RESOURCE_PRF to active link ${packet.destinationHash.toHexString()}" }
                    try {
                        activeLink.receive(packet)
                    } catch (e: Exception) {
                        log("Error delivering resource proof to link: ${e.message}")
                    }
                }
            }

            // All other proof types — Transport.py:2079-2115
            // Try reverse table, then pending receipts
            else -> {
                var reverseEntry = reverseTable[packet.destinationHash.toKey()]
                if (reverseEntry == null) {
                    reverseEntry = reverseTable[packet.truncatedHash.toKey()]
                }

                if (reverseEntry != null) {
                    // python Transport.py:2256 — only transport the proof if it arrived
                    // on the entry's OUTBOUND interface (the one we forwarded the original
                    // packet to). A proof heard on any other interface is NOT transported
                    // ("Proof received on wrong interface, not transporting it"). The reverse
                    // entry is popped either way (Transport.py:2255).
                    if (interfaceRef.hash.contentEquals(reverseEntry.outboundInterfaceHash)) {
                        val outboundInterface = findInterfaceByHash(reverseEntry.receivingInterfaceHash)
                        if (outboundInterface != null) {
                            logDebug { "Proof received on correct interface, transporting it via ${outboundInterface.name}" }
                            transmit(outboundInterface, packet.raw ?: packet.pack())
                        }
                    } else {
                        log("Proof received on wrong interface, not transporting it")
                    }
                    reverseTable.remove(packet.destinationHash.toKey())
                    reverseTable.remove(packet.truncatedHash.toKey())
                    return
                }

                // Check if there's a pending receipt for this proof (local destination)
                // Peek first, only remove on successful validation (Python Transport.py:2102-2115)
                var matchedKey: ByteArrayKey? = null
                var matchedReceipts: List<PacketReceipt> = emptyList()
                var callback: ProofCallback? = null
                // Lookup order (unchanged): destination hash, then the full hash carried in
                // the proof data, then the truncated hash derived from it. At each key the
                // receipt map is consulted before the generic proof-callback map. The
                // receipt list is snapshotted under the lock; validation runs outside it.
                fun lookup(key: ByteArrayKey): Boolean {
                    matchedReceipts = synchronized(receipts) { receipts[key]?.toList() } ?: emptyList()
                    if (matchedReceipts.isEmpty()) callback = pendingProofCallbacks[key]
                    if (matchedReceipts.isNotEmpty() || callback != null) {
                        matchedKey = key
                        return true
                    }
                    return false
                }

                var found = lookup(packet.destinationHash.toKey())

                if (!found && packet.data.size >= RnsConstants.FULL_HASH_BYTES) {
                    val fullHash = packet.data.copyOfRange(0, RnsConstants.FULL_HASH_BYTES)
                    found = lookup(fullHash.toKey())
                    if (found) {
                        logDebug { "Found pending receipt by full hash from proof data" }
                    } else {
                        // Receipts are registered by truncated hash (16 bytes), but the
                        // proof data contains the full hash (32 bytes). For link proofs,
                        // packet.destinationHash is the link ID (not the packet hash),
                        // so the first lookup misses. Try truncated hash derived from
                        // the full hash in the proof data.
                        val truncHash = fullHash.copyOfRange(0, RnsConstants.TRUNCATED_HASH_BYTES)
                        found = lookup(truncHash.toKey())
                        if (found) {
                            logDebug { "Found pending receipt by truncated hash from proof data" }
                        }
                    }
                }

                val key = matchedKey
                if (found && key != null) {
                    try {
                        var validated = false
                        if (matchedReceipts.isNotEmpty()) {
                            // Python Transport.py:2100-2113 tests EVERY receipt whose hash
                            // matches and removes each one that validates.
                            for (r in matchedReceipts) {
                                // python Transport.py:2758 — only a receipt still in
                                // SENT can be concluded by a proof. A receipt already
                                // FAILED by checkReceiptTimeouts (and awaiting removal)
                                // must not be flipped to DELIVERED by a late proof.
                                if (r.status != PacketReceipt.SENT) continue
                                if (r.validateProofPacket(packet)) {
                                    validated = true
                                    removeReceipt(key, r)
                                }
                            }
                        } else {
                            validated = callback?.onProofReceived(packet) ?: false
                            if (validated) pendingProofCallbacks.remove(key)
                        }
                        if (validated) {
                            logDebug { "Proof validated for ${packet.destinationHash.toHexString()}" }
                            markPathResponsive(packet.destinationHash)
                        } else {
                            log("Proof validation failed for ${packet.destinationHash.toHexString()}")
                        }
                    } catch (e: Exception) {
                        log("Proof callback error: ${e.message}")
                    }
                } else {
                    log(
                        "Proof dest=${packet.destinationHash.toHexString()} not found in link_table (${linkTable.size} entries) or reverse_table (${reverseTable.size} entries)",
                    )
                }
            }
        }
    }

    private fun forwardPacket(
        packet: Packet,
        receivingInterface: InterfaceRef,
    ) {
        val pathEntry = pathTable[packet.destinationHash.toKey()] ?: return
        val outboundInterface = findInterfaceByHash(pathEntry.receivingInterfaceHash) ?: return

        val raw = packet.raw ?: return

        when {
            pathEntry.hops > 1 -> {
                // Update transport header with next hop (Python Transport.py:1433-1438)
                val newRaw = raw.copyOf()
                newRaw[1] = packet.hops.toByte()
                System.arraycopy(pathEntry.nextHop, 0, newRaw, 2, RnsConstants.TRUNCATED_HASH_BYTES)
                transmit(outboundInterface, newRaw)
            }
            pathEntry.hops == 1 -> {
                // Strip transport header, convert to HEADER_1 (Python Transport.py:1439-1444)
                val newFlags =
                    (HeaderType.HEADER_1.value shl 6) or
                        (TransportType.BROADCAST.value shl 4) or
                        (raw[0].toInt() and 0x0F)
                val newRaw = ByteArray(raw.size - RnsConstants.TRUNCATED_HASH_BYTES)
                newRaw[0] = newFlags.toByte()
                newRaw[1] = packet.hops.toByte()
                System.arraycopy(
                    raw,
                    2 + RnsConstants.TRUNCATED_HASH_BYTES,
                    newRaw,
                    2,
                    raw.size - 2 - RnsConstants.TRUNCATED_HASH_BYTES,
                )
                transmit(outboundInterface, newRaw)
            }
            else -> {
                // hops==0: local client, just update hop count (Python Transport.py:1445-1449)
                val newRaw = raw.copyOf()
                newRaw[1] = packet.hops.toByte()
                transmit(outboundInterface, newRaw)
            }
        }

        // Record reverse entry for proofs
        val reverseEntry =
            ReverseEntry(
                receivingInterfaceHash = receivingInterface.hash,
                outboundInterfaceHash = outboundInterface.hash,
                timestamp = System.currentTimeMillis(),
            )
        reverseTable[packet.truncatedHash.toKey()] = reverseEntry

        // Update path timestamp
        val touched = pathEntry.touch()
        pathTable[packet.destinationHash.toKey()] = touched
        pathStore?.upsertPath(packet.destinationHash, touched)
    }

    private fun deliverPacket(
        destination: Destination,
        packet: Packet,
    ) {
        val data = packet.data
        if (data.isEmpty()) {
            log("Ignoring empty packet for ${destination.hexHash}")
            return
        }

        // Set the destination on the packet so it can be proved
        packet.destination = destination

        try {
            // Handle based on packet type
            when (packet.packetType) {
                PacketType.DATA -> {
                    // Decrypt the data if needed
                    val plaintext =
                        when (destination.type) {
                            DestinationType.PLAIN -> data
                            else ->
                                destination.decrypt(data) ?: run {
                                    log("Failed to decrypt packet for ${destination.hexHash}")
                                    return
                                }
                        }

                    // Deliver via callback
                    val callback = destination.packetCallback
                    if (callback != null) {
                        callback(plaintext, packet)
                        logDebug { "Delivered packet to ${destination.hexHash} (${plaintext.size} bytes)" }
                    } else {
                        log("No callback registered for ${destination.hexHash}")
                    }

                    // Receiver-side single-packet PROOF emission per the
                    // destination's proof strategy. python Transport.inbound
                    // (Transport.py:2157-2165): after a successful
                    // destination.receive() (a truthy decrypt — the decrypt
                    // early-return above is the equivalent guard) the packet is
                    // proved iff proof_strategy is PROVE_ALL, or PROVE_APP with the
                    // proof_requested callback returning true; PROVE_NONE proves
                    // nothing. Destination.shouldProve() encapsulates that decision
                    // and packet.prove() signs+sends the PROOF back to the sender.
                    if (destination.shouldProve(packet)) {
                        runCatching { packet.prove() }
                    }
                }

                PacketType.LINKREQUEST -> {
                    // Check if destination accepts link requests
                    if (!destination.acceptLinkRequests) {
                        log("Destination ${destination.hexHash} not accepting link requests")
                        return
                    }

                    // Validate and create the incoming link. The destination's
                    // link-established callback is invoked from Link.rttPacket() once the
                    // link reaches ACTIVE state, matching Python RNS (RNS/Link.py
                    // rtt_packet). Invoking it here would fire while status is still
                    // HANDSHAKE — at which point link.send() silently fails and any
                    // caller-side signalling is dropped.
                    val link = Link.validateRequest(destination, packet.data, packet)
                    if (link != null) {
                        logDebug { "Link request for ${destination.hexHash} accepted: ${link.linkId.toHexString()}" }
                    } else {
                        log("Link request for ${destination.hexHash} rejected (validation failed)")
                    }
                }

                else -> {
                    log("Unexpected packet type ${packet.packetType} for delivery")
                }
            }
        } catch (e: Exception) {
            log("Error delivering packet to ${destination.hexHash}: ${e.message}")
        }
    }

    // ===== Announce Validation =====

    private data class AnnounceData(
        val identity: Identity,
        val nameHash: ByteArray,
        val randomHash: ByteArray,
        val appData: ByteArray?,
        val ratchet: ByteArray?,
    )

    private fun validateAnnounce(packet: Packet): AnnounceData? {
        val data = packet.data
        if (data.size < RnsConstants.ANNOUNCE_MIN_SIZE) {
            log("Announce too small: ${data.size} < ${RnsConstants.ANNOUNCE_MIN_SIZE}")
            return null
        }

        // Announce format:
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [signature: 64] [app_data: variable]
        // Or with ratchet (when context_flag is SET):
        // [public_key: 64] [name_hash: 10] [random_hash: 10] [ratchet: 32] [signature: 64] [app_data: variable]

        val keySize = RnsConstants.IDENTITY_PUBLIC_KEY_SIZE // 64
        val nameHashLen = 10
        val randomHashLen = 10
        val ratchetSize = 32
        val sigLen = 64

        val publicKey = data.copyOfRange(0, keySize)
        val nameHash = data.copyOfRange(keySize, keySize + nameHashLen)
        val randomHash = data.copyOfRange(keySize + nameHashLen, keySize + nameHashLen + randomHashLen)

        // Determine if ratchet is present based on context flag
        val hasRatchet = packet.contextFlag == ContextFlag.SET

        val ratchet: ByteArray
        val signature: ByteArray
        // python Identity.py:514/525 — app_data defaults to b"" (empty), NOT None,
        // when the announce carries no trailing bytes. The post-signing override
        // below nulls it only for the ratchetless no-app_data layout.
        var appData: ByteArray?

        if (hasRatchet) {
            val ratchetStart = keySize + nameHashLen + randomHashLen
            val ratchetEnd = ratchetStart + ratchetSize
            val sigEnd = ratchetEnd + sigLen

            if (data.size < sigEnd) {
                log("Announce data too short for ratchet+signature: ${data.size} < $sigEnd")
                return null
            }

            ratchet = data.copyOfRange(ratchetStart, ratchetEnd)
            signature = data.copyOfRange(ratchetEnd, sigEnd)
            appData = if (data.size > sigEnd) data.copyOfRange(sigEnd, data.size) else ByteArray(0)
        } else {
            ratchet = ByteArray(0)
            val sigStart = keySize + nameHashLen + randomHashLen
            val sigEnd = sigStart + sigLen

            if (data.size < sigEnd) {
                log("Announce data too short for signature: ${data.size} < $sigEnd")
                return null
            }

            signature = data.copyOfRange(sigStart, sigEnd)
            appData = if (data.size > sigEnd) data.copyOfRange(sigEnd, data.size) else ByteArray(0)
        }

        // Create identity from public key
        val identity =
            try {
                Identity.fromPublicKey(publicKey)
            } catch (e: Exception) {
                log("Failed to create identity from public key: ${e.message}")
                return null
            }

        // python Identity.py:537-540 — an announce from a blackholed identity is
        // invalidated and dropped here (before signature validation), so it can
        // never create a path. This is the inbound validate path that feeds
        // processAnnounce's pathTable insert + Identity.remember.
        if (blackholedIdentities.isNotEmpty() && isBlackholed(identity.hash)) {
            log("Invalidated and dropped announce from blackholed identity ${identity.hash.toHexString()}")
            return null
        }

        // Verify destination hash matches
        val computedDestHash = Destination.computeHash(nameHash, identity.hash)
        if (!computedDestHash.contentEquals(packet.destinationHash)) {
            log("Destination hash mismatch: computed=${computedDestHash.toHexString()}, packet=${packet.destinationHash.toHexString()}")
            return null
        }

        // Build signed data: destination_hash + public_key + name_hash + random_hash + ratchet + app_data
        // IMPORTANT: The destination_hash from packet header is included in signed data!
        val signedData = packet.destinationHash + publicKey + nameHash + randomHash + ratchet + appData

        if (!identity.validate(signature, signedData)) {
            log("Signature validation failed")
            return null
        }

        // python Identity.py:531-532 — ONLY the ratchetless no-app_data layout
        // (data length == keysize+name_hash+random_hash+sig_len, the threshold
        // WITHOUT the 32-byte ratchet term) nulls app_data after signing. A
        // ratcheted no-app_data announce exceeds this threshold by the ratchet, so
        // it keeps the b"" sentinel — recall returns empty bytes, not None.
        val ratchetlessThreshold = keySize + nameHashLen + randomHashLen + sigLen
        if (!(data.size > ratchetlessThreshold)) {
            appData = null
        }

        return AnnounceData(
            identity,
            nameHash,
            randomHash,
            appData,
            ratchet = if (hasRatchet && ratchet.isNotEmpty()) ratchet else null,
        )
    }

    // ===== Packet Filter (Duplicate Detection) =====

    private fun packetFilter(
        packet: Packet,
        receivingInterface: InterfaceRef,
        // The packet-hash key, supplied by the inbound path so addPacketHash can reuse it
        // instead of re-keying (one ByteArrayKey per packet instead of two).
        key: ByteArrayKey = packet.packetHash.toKey(),
    ): Boolean {
        // Python RNS: Bypass local filtering if connected to shared instance
        // (Python Transport.py:1187-1190)
        if (isConnectedToSharedInstance) {
            return true
        }

        // Filter packets intended for other transport instances (Python:1192-1196)
        // Skip this check for packets from local clients — they may have incorrect
        // transport headers since the Kotlin client builds its own path table
        // (unlike Python where clients delegate routing to the shared instance)
        val fromLocalClient = localClientInterfaces.any { it.hash.contentEquals(receivingInterface.hash) }
        if (!fromLocalClient && packet.transportId != null && packet.packetType != PacketType.ANNOUNCE) {
            val myHash = identity?.hash ?: return false
            if (!packet.transportId!!.contentEquals(myHash)) {
                return false
            }
        }

        // Context-based bypass (Python:1198-1203)
        when (packet.context) {
            PacketContext.KEEPALIVE, PacketContext.RESOURCE_REQ,
            PacketContext.RESOURCE_PRF, PacketContext.RESOURCE,
            PacketContext.CACHE_REQUEST, PacketContext.CHANNEL,
            -> return true
            else -> {}
        }

        // PLAIN destination validation (Python:1205-1214)
        if (packet.destinationType == DestinationType.PLAIN) {
            if (packet.packetType != PacketType.ANNOUNCE) {
                return packet.hops <= 1
            } else {
                return false
            }
        }

        // GROUP destination validation (Python:1216-1225)
        if (packet.destinationType == DestinationType.GROUP) {
            if (packet.packetType != PacketType.ANNOUNCE) {
                return packet.hops <= 1
            } else {
                return false
            }
        }

        // Hashlist dedup (Python:1227-1238) — `key` is the caller-supplied packet-hash key.
        if (!packetHashlist.contains(key) && !packetHashlistPrev.contains(key)) {
            return true
        } else if (packet.packetType == PacketType.ANNOUNCE &&
            packet.destinationType == DestinationType.SINGLE
        ) {
            return true // Allow duplicate SINGLE announces through
        }

        return false
    }

    private fun addPacketHash(hash: ByteArray) = addPacketHash(hash.toKey())

    /** Key-taking variant so the inbound path reuses the key packetFilter already built. */
    private fun addPacketHash(key: ByteArrayKey) {
        packetHashlist.add(key)

        // Rotate hashlist if too large. Rotate at HASHLIST_MAXSIZE/2 so the two
        // sets together hold at most HASHLIST_MAXSIZE entries, matching python's
        // `len(packet_hashlist) > hashlist_maxsize//2` swap (Transport.py:803-805).
        // (Rotating at the full size kept up to ~2x the intended ceiling.)
        if (packetHashlist.size > TransportConstants.HASHLIST_MAXSIZE / 2) {
            packetHashlistPrev.clear()
            packetHashlistPrev.addAll(packetHashlist)
            packetHashlist.clear()
        }
    }

    /**
     * Clear the packet hashlist. Used for testing only.
     * WARNING: This will cause duplicate packets to be processed.
     */
    @JvmStatic
    fun clearPacketHashlist() {
        jobsLock.withLock {
            packetHashlist.clear()
            packetHashlistPrev.clear()
        }
    }

    // ===== Background Jobs =====

    /**
     * Get the effective job interval.
     * Uses custom interval if set, platform-appropriate default otherwise.
     */
    private fun getJobInterval(): Long =
        customJobIntervalMs ?: if (Platform.isAndroid) {
            Platform.recommendedJobIntervalMs
        } else {
            TransportConstants.JOB_INTERVAL
        }

    /**
     * Thread-based job loop (legacy, for JVM).
     */
    private fun jobLoop() {
        while (started.get()) {
            try {
                Thread.sleep(getJobInterval())
                if (!paused.get()) {
                    runJobs()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log("Job error: ${e.message}")
            }
        }
    }

    /**
     * Start coroutine-based job loop.
     * Called internally when useCoroutineJobLoop is true and scope is set.
     */
    private fun startCoroutineJobLoop() {
        val scope = jobLoopScope ?: return

        jobLoopJob =
            scope.launch {
                while (isActive && started.get()) {
                    try {
                        delay(getJobInterval())
                        if (!paused.get()) {
                            runJobs()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        log("Coroutine job error: ${e.message}")
                    }
                }
            }
    }

    /**
     * Configure coroutine-based job loop for Android.
     * Call this before start() to use coroutines instead of threads.
     *
     * @param scope The coroutine scope to use for the job loop
     * @param intervalMs Custom job interval in milliseconds (default: 250ms)
     * @param tablesCullIntervalMs Custom tables cull interval (default: 5000ms)
     * @param announcesCheckIntervalMs Custom announces check interval (default: 1000ms)
     */
    fun configureCoroutineJobLoop(
        scope: CoroutineScope,
        intervalMs: Long? = null,
        tablesCullIntervalMs: Long? = null,
        announcesCheckIntervalMs: Long? = null,
    ) {
        jobLoopScope = scope
        customJobIntervalMs = intervalMs
        customTablesCullIntervalMs = tablesCullIntervalMs
        customAnnouncesCheckIntervalMs = announcesCheckIntervalMs
        useCoroutineJobLoop = true
    }

    /**
     * Stop the coroutine job loop.
     * Called automatically when Transport stops.
     */
    private fun stopCoroutineJobLoop() {
        jobLoopJob?.cancel()
        jobLoopJob = null
    }

    /**
     * Run maintenance jobs.
     * This is the subset of runJobs() suitable for periodic WorkManager execution.
     * Does not include time-critical operations.
     */
    fun runMaintenanceJobs() {
        val now = System.currentTimeMillis()

        // Cull stale table entries
        cullTables()
        tablesLastCulled = now

        // Clean hashlist
        packetHashlistPrev.clear()
        hashlistLastCleaned = now

        // Save tunnel table if transport is enabled
        if (transportEnabled) {
            saveTunnelTable()
        }

        // Save path table and packet hashlist periodically
        persistDataToStorage()
    }

    /** Last time the 5 s interface jobs ran (python `interface_jobs_interval`, Transport.py:264). */
    private var interfaceJobsLastRun = 0L

    private fun runJobs() {
        val now = System.currentTimeMillis()

        // Synthesize tunnels for interfaces that want them
        synthesizeTunnelsForWaitingInterfaces()

        // Process per-interface held announces
        for (iface in interfaces) {
            iface.processHeldAnnounces()
        }

        // python Transport.py:1154-1156 ticks the path-request ingress limiter on every
        // interface every 5 s, so its three-call deactivation cooldown drains during a quiet
        // period. Without the tick the cooldown only advanced when a path request arrived,
        // which meant the first two LEGITIMATE requests after a burst were the ones that
        // paid it — classified ingress-limited and refused recursive discovery. The
        // announce limiter needs no tick here: processHeldAnnounces() above calls it.
        if (now - interfaceJobsLastRun >= TransportConstants.INTERFACE_JOBS_INTERVAL) {
            interfaceJobsLastRun = now
            for (iface in interfaces) {
                iface.shouldIngressLimitPr()
            }
        }

        // Update traffic speed
        updateTrafficSpeed(now)

        // Emit announce rebroadcasts whose grace window has expired
        if (now - announcesLastChecked > TransportConstants.ANNOUNCES_CHECK_INTERVAL) {
            announceRetransmitJob(now)
            announcesLastChecked = now
        }

        // Check receipt timeouts (cheap operation, always use 1s interval)
        if (now - receiptsLastChecked > TransportConstants.RECEIPTS_CHECK_INTERVAL) {
            checkReceiptTimeouts()
            receiptsLastChecked = now
        }

        // Expire blackhole entries past their `until` (python Transport.py:971-995)
        expireBlackholeEntries(now)

        // Announce the management destinations (python Transport.py:1179-1186).
        if (mgmtDestinations.isNotEmpty() && now > lastMgmtAnnounce + MGMT_ANNOUNCE_INTERVAL_MS) {
            lastMgmtAnnounce = now
            for (dest in mgmtDestinations.toList()) {
                try {
                    dest.announce()
                } catch (e: Exception) {
                    log("Error while sending management announce for ${dest.hexHash}: ${e.message}")
                }
            }
        }
        // Cull stale table entries (expensive, use battery-adjusted interval)
        val tablesCullInterval = customTablesCullIntervalMs ?: TransportConstants.TABLES_CULL_INTERVAL
        if (now - tablesLastCulled > tablesCullInterval) {
            cullTables()
            tablesLastCulled = now
        }

        // Clean hashlist (size-based, run every 5 minutes regardless of battery mode)
        if (now - hashlistLastCleaned > TransportConstants.CACHE_CLEAN_INTERVAL) {
            packetHashlistPrev.clear()
            hashlistLastCleaned = now
        }

        // Clean announce cache periodically (run every 5 minutes regardless of battery mode)
        if (now - cacheLastCleaned > TransportConstants.CACHE_CLEAN_INTERVAL) {
            cleanAnnounceCache()
            cacheLastCleaned = now
        }

        // Persist known destinations periodically (every 5 minutes)
        // Ensures identities survive process kills without clean shutdown
        if (now - identitiesLastSaved > TransportConstants.CACHE_CLEAN_INTERVAL) {
            try {
                Identity.saveKnownDestinations()
            } catch (e: Exception) {
                log("Error saving known destinations: ${e.message}")
            }
            identitiesLastSaved = now
        }
    }

    /**
     * Check receipt timeouts and cull old receipts.
     * Matches Python RNS behavior.
     */
    private fun checkReceiptTimeouts() {
        // Cull excess receipts (oldest first — LinkedHashMap insertion order). Culled
        // receipts are detached under the lock, then concluded outside it so that
        // checkTimeout()'s callback submission never runs while holding the map lock.
        // MAX_RECEIPTS bounds individual receipts, not keys.
        val culled = mutableListOf<PacketReceipt>()
        val remaining: List<PacketReceipt> =
            synchronized(receipts) {
                var excess = receiptCount - TransportConstants.MAX_RECEIPTS
                if (excess > 0) {
                    val oldestFirst = receipts.entries.iterator()
                    while (excess > 0 && oldestFirst.hasNext()) {
                        val list = oldestFirst.next().value
                        while (excess > 0 && list.isNotEmpty()) {
                            culled.add(list.removeAt(0))
                            receiptCount--
                            excess--
                        }
                        if (list.isEmpty()) oldestFirst.remove()
                    }
                }
                receipts.values.flatten()
            }
        for (receipt in culled) {
            // Force timeout with CULLED status
            receipt.checkTimeout()
        }

        // Check all receipts for timeout
        val toRemove = mutableListOf<PacketReceipt>()
        for (receipt in remaining) {
            receipt.checkTimeout()
            if (receipt.status != PacketReceipt.SENT) {
                toRemove.add(receipt)
            }
        }
        for (receipt in toRemove) {
            removeReceipt(receipt.truncatedHash.toKey(), receipt)
        }
    }

    /**
     * Remove one specific receipt from [receipts] (identity match), dropping the key
     * when its list empties. No-op if the receipt is no longer tracked.
     */
    private fun removeReceipt(
        key: ByteArrayKey,
        receipt: PacketReceipt,
    ) {
        synchronized(receipts) {
            val list = receipts[key] ?: return
            val idx = list.indexOfFirst { it === receipt }
            if (idx < 0) return
            list.removeAt(idx)
            receiptCount--
            if (list.isEmpty()) receipts.remove(key)
        }
    }

    /**
     * Update traffic speed calculations.
     */
    private fun updateTrafficSpeed(now: Long) {
        val elapsed = now - lastTrafficTime

        if (elapsed >= TransportConstants.SPEED_UPDATE_INTERVAL) {
            val rxDelta = trafficRxBytes - lastTrafficSnapshot.first
            val txDelta = trafficTxBytes - lastTrafficSnapshot.second

            speedRx = if (elapsed > 0) (rxDelta * 1000) / elapsed else 0
            speedTx = if (elapsed > 0) (txDelta * 1000) / elapsed else 0

            lastTrafficSnapshot = Pair(trafficRxBytes, trafficTxBytes)
            lastTrafficTime = now
        }
    }

    /** Test seam: run the periodic table cull synchronously. */
    internal fun cullTablesNow() = cullTables()

    /**
     * Test seam: backdate [startTime] so the startup grace period
     * ([TransportConstants.STARTUP_GRACE_PERIOD]) has elapsed, allowing
     * [cullTables] to exercise its dangling-interface prune.
     */
    internal fun setStartTimeForTest(timeMs: Long) {
        startTime = timeMs
    }

    // ===== Conformance test seams =====
    // The behavioral conformance bridge needs to observe and drive Transport
    // state the way python's reference bridge sets RNS.Transport module
    // attributes. These seams keep that surface out of the public API.

    /**
     * Force a synchronous cull pass with the startup grace elapsed — the
     * kotlin analogue of the reference's `tables_last_culled = 0; jobs()`.
     * Seeded entries already aged past their timeouts are evicted; fresh
     * ones survive.
     */
    /**
     * Tear down every live link before the tables are dropped (python
     * `Transport.detach_interfaces`, `Transport.py:3625-3637`).
     *
     * Without this a peer learns the link is gone only when its watchdog gives up, so it
     * reports TIMEOUT — indistinguishable, from the application's side, from a node that
     * crashed or fell off the network. Tearing down sends LINKCLOSE, and the peer closes
     * with DESTINATION_CLOSED: the difference between "they hung up" and "they vanished",
     * which is exactly what an application needs to decide whether to reconnect.
     *
     * The brief pause afterwards is the reference's, and it is load-bearing: the close
     * packets have been handed to the interfaces but not yet written, and clearing the
     * tables (or detaching the interfaces) underneath them would discard the very packets
     * this exists to send.
     */
    fun tearDownLinksForShutdown() {
        var closed = 0
        for (link in activeLinks.toList() + pendingLinks.toList()) {
            try {
                link.teardown(network.reticulum.link.LinkConstants.TEARDOWN_REASON_DESTINATION_CLOSED)
                closed++
            } catch (e: Exception) {
                log("Could not tear down link before shutdown: ${e.message}")
            }
        }
        if (closed > 0) {
            try {
                Thread.sleep(TransportConstants.LINK_TEARDOWN_DRAIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @network.reticulum.RnsTestSeam
    fun forceCullForTest() {
        val savedStart = startTime
        startTime = 0L
        try {
            cullTables()
            // The reference's cull pass and its announce-retransmit pass live in the same
            // jobs() body (Transport.py:765-830), so a harness that forces a cull also
            // drives any due rebroadcast. Running it here keeps that coupling.
            announceRetransmitJob(System.currentTimeMillis())
        } finally {
            startTime = savedStart
        }
    }

    /** Read the per-destination announce-rate timestamps, or null if absent. */
    /**
     * Whether a path request for [destHash] is currently outstanding. Lets the
     * ingress-limit exemption test assert its own precondition: `requestPath` only
     * records the request if the packet actually went out.
     */
    @network.reticulum.RnsTestSeam
    fun hasPendingPathRequestForTest(destHash: ByteArray): Boolean {
        val key = destHash.toKey()
        return pathRequests.containsKey(key) || discoveryPathRequests.containsKey(key)
    }

    /** Plant a discovery path request as processPathRequest would after fanning out. */
    @network.reticulum.RnsTestSeam
    fun addDiscoveryPathRequestForTest(destHash: ByteArray, requestingInterface: InterfaceRef) {
        discoveryPathRequests[destHash.toKey()] =
            DiscoveryPathRequest(
                destinationHash = destHash.copyOf(),
                timeout = System.currentTimeMillis() + TransportConstants.PATH_REQUEST_TIMEOUT,
                requestingInterface = requestingInterface,
                engaged = true,
            )
    }

    fun announceRateTimestampsForTest(destHash: ByteArray): List<Long>? =
        announceRateTable[destHash.toKey()]?.timestamps?.toList()

    /** Snapshot the live tunnel table. */
    fun tunnelInfosForTest(): List<TunnelInfo> = tunnels.values.toList()

    /** Size of the active packet hashlist (excludes the rotated-out prev set). */
    fun packetHashlistSizeForTest(): Int = packetHashlist.size

    fun localClientInterfaceCountForTest(): Int = localClientInterfaces.size

    /** Whether a packet hash is currently remembered (active or prev set). */
    fun packetHashlistContainsForTest(hash: ByteArray): Boolean {
        val key = hash.toKey()
        return packetHashlist.contains(key) || packetHashlistPrev.contains(key)
    }

    /** Run the real duplicate/replay filter gate on a packet (no side effects). */
    @network.reticulum.RnsTestSeam
    fun packetFilterForTest(packet: Packet, receivingInterface: InterfaceRef): Boolean =
        packetFilter(packet, receivingInterface)

    /** Record a packet hash so a subsequent identical packet is filtered. */
    @network.reticulum.RnsTestSeam
    fun addPacketHashForTest(hash: ByteArray) = addPacketHash(hash)

    /** Drive the real outbound transmit (applies IFAC masking) on an interface. */
    @network.reticulum.RnsTestSeam
    fun transmitForTest(interfaceRef: InterfaceRef, raw: ByteArray) =
        transmit(interfaceRef, raw)

    /**
     * Conformance seam: return the genuine IFAC-masked frame for [raw] on this
     * interface WITHOUT transmitting it (the reference captures Transport.transmit's
     * process_outgoing output, wire_tcp.py:1827-1852). Exposes the private
     * applyIfacMasking so the wire bridge can mask a frame for injection. No port
     * logic — just surfaces the existing masker.
     */
    @network.reticulum.RnsTestSeam
    fun applyIfacMaskingForTest(raw: ByteArray, interfaceRef: InterfaceRef): ByteArray =
        applyIfacMasking(raw, interfaceRef)

    /** Replace path_table[dest]'s timestamp (epoch millis), copying the entry. */
    @network.reticulum.RnsTestSeam
    fun setPathTimestampForTest(destHash: ByteArray, timestampMs: Long): Boolean {
        val key = destHash.toKey()
        val entry = pathTable[key] ?: return false
        pathTable[key] = entry.copy(timestamp = timestampMs)
        return true
    }

    /** Replace path_table[dest]'s expires (epoch millis), copying the entry. */
    @network.reticulum.RnsTestSeam
    fun setPathExpiresForTest(destHash: ByteArray, expiresMs: Long): Boolean {
        val key = destHash.toKey()
        val entry = pathTable[key] ?: return false
        pathTable[key] = entry.copy(expires = expiresMs)
        return true
    }

    /** Resolve a registered interface by its hash (table-entry decomposition). */
    /** Resolve a registered interface by hash, for callers inside the library. */
    internal fun interfaceForHash(hash: ByteArray): InterfaceRef? = findInterfaceByHash(hash)

    fun findInterfaceByHashForTest(hash: ByteArray): InterfaceRef? =
        findInterfaceByHash(hash)

    // ===== Blackhole API (port of RNS/Transport.py:3406-3538) =====

    /**
     * Blackhole an identity (python Transport.blackhole_identity:3407-3428).
     * @return true if newly added, null if already present, false on error.
     * [until] is an epoch-millis expiry (null = permanent).
     */
    fun blackholeIdentity(identityHash: ByteArray, until: Long? = null, reason: String? = null): Boolean? {
        return try {
            val key = identityHash.toKey()
            if (!blackholedIdentities.containsKey(key)) {
                blackholedIdentities[key] = BlackholeEntry(
                    source = identity?.hash ?: ByteArray(0), until = until, reason = reason)
                persistBlackhole()
                removeBlackholedPaths()
                true
            } else null
        } catch (e: Exception) {
            log("Error while blackholing identity: ${e.message}")
            false
        }
    }

    /** Lift a blackhole (python unblackhole_identity:3432-3443). */
    fun unblackholeIdentity(identityHash: ByteArray): Boolean? {
        return try {
            val key = identityHash.toKey()
            if (blackholedIdentities.containsKey(key)) {
                blackholedIdentities.remove(key)
                persistBlackhole()
                true
            } else null
        } catch (e: Exception) {
            log("Error while unblackholing identity: ${e.message}")
            false
        }
    }

    /** Whether an identity hash is currently blackholed. */
    fun isBlackholed(identityHash: ByteArray): Boolean =
        blackholedIdentities.containsKey(identityHash.toKey())

    /** The /list response generator (python blackhole_list_handler:3514). */
    fun blackholeListHandler(): Map<ByteArrayKey, BlackholeEntry> = blackholedIdentities

    /**
     * Reload blackhole entries from the storage blackhole dir (python
     * reload_blackhole:3453-3490): 'local' is own identity, other files are
     * hex source-identity hashes that must be a trusted source; expired
     * (until < now) entries are skipped; a locally-sourced entry is never
     * overwritten. Then drops blackhole-associated paths.
     */
    fun reloadBlackhole() {
        val now = System.currentTimeMillis()
        val destLen = (RnsConstants.TRUNCATED_HASH_BYTES) * 2
        val dir = java.io.File(blackholePath)
        if (dir.isDirectory) {
            for (file in dir.listFiles() ?: emptyArray()) {
                try {
                    val filename = file.name
                    val sourceIdentityHash: ByteArray = if (filename == "local") {
                        identity?.hash ?: continue
                    } else {
                        if (filename.length != destLen) {
                            throw IllegalArgumentException("Invalid blackhole source filename length: $filename")
                        }
                        val src = filename.hexToBytesOrNull() ?: continue
                        if (blackholeSources.none { it.contentEquals(src) }) continue
                        src
                    }
                    val sourceList = unpackBlackholeFile(file.readBytes())
                    for ((idHash, se) in sourceList) {
                        if (idHash.size != RnsConstants.TRUNCATED_HASH_BYTES) continue
                        val key = idHash.toKey()
                        val existing = blackholedIdentities[key]
                        if (existing != null && identity != null && existing.source.contentEquals(identity!!.hash)) {
                            continue // never overwrite a locally-sourced entry
                        }
                        val until = se.until
                        if (until == null || now < until) {
                            blackholedIdentities[key] = BlackholeEntry(sourceIdentityHash, until, se.reason)
                        }
                    }
                } catch (e: Exception) {
                    log("Could not load blackholed identities from ${file.name}: ${e.message}")
                }
            }
        }
        removeBlackholedPaths()
    }

    /** Drop path-table entries whose recalled identity is blackholed
     * (python remove_blackholed_paths:3492-3512). */
    fun removeBlackholedPaths() {
        if (blackholedIdentities.isEmpty()) return
        val drop = mutableListOf<ByteArrayKey>()
        for (destKey in pathTable.keys.toList()) {
            try {
                val id = Identity.recall(destKey.bytes, noUse = true) // housekeeping, not use
                if (id != null && blackholedIdentities.containsKey(id.hash.toKey())) {
                    drop.add(destKey)
                }
            } catch (e: Exception) {
                log("Error enumerating blackhole-associated destinations: ${e.message}")
            }
        }
        for (k in drop) {
            pathTable.remove(k)
            pathAlternates.remove(k)
        }
        if (drop.isNotEmpty()) {
            log("Removed ${drop.size} destination(s) associated with blackholed identities from path table")
        }

    }

    /** Persist the locally-sourced blackhole entries to <storage>/blackhole/local
     * atomically (python persist_blackhole:3523-3538). */
    fun persistBlackhole() {
        try {
            val ownHash = identity?.hash ?: return
            val dir = java.io.File(blackholePath).apply { mkdirs() }
            val local = blackholedIdentities.filterValues { it.source.contentEquals(ownHash) }
            val packed = packBlackholeEntries(local)
            val localFile = java.io.File(dir, "local")
            val tmp = java.io.File(dir, "local.tmp")
            tmp.writeBytes(packed)
            // python uses os.replace (atomic overwrite). File.renameTo returns false
            // when the target exists on some platforms and its result was discarded,
            // so the list was never updated after the first write. Use an atomic
            // replacing move, degrade to a plain replace where the filesystem cannot
            // do it atomically, and surface a failure instead of ignoring it.
            val src = tmp.toPath()
            val dst = localFile.toPath()
            try {
                java.nio.file.Files.move(
                    src, dst,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log("Error while persisting blackhole list: ${e.message}")
        }
    }

    /** Clear in-memory blackhole state (own + reloaded). */
    fun clearBlackholeTable() = blackholedIdentities.clear()

    /** The blackhole storage directory (conformance file-ops seam). */
    fun blackholeStorageDirForTest(): String = blackholePath

    /** Expire blackhole entries whose `until` has passed; called from runJobs. */
    private fun expireBlackholeEntries(now: Long) {
        if (now <= blackholeLastChecked + blackholeCheckIntervalMs) return
        blackholeLastChecked = now
        val stale = blackholedIdentities.filter { (_, e) -> e.until != null && now > e.until }.keys
        for (k in stale) blackholedIdentities.remove(k)
    }

    /** Force the blackhole-expiry pass synchronously (conformance seam). */
    fun expireBlackholeNow() {
        blackholeLastChecked = 0
        expireBlackholeEntries(System.currentTimeMillis())
    }

    private fun packBlackholeEntries(entries: Map<ByteArrayKey, BlackholeEntry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val packer = org.msgpack.core.MessagePack.newDefaultPacker(out)
        packer.packMapHeader(entries.size)
        for ((key, e) in entries) {
            packer.packBinaryHeader(key.bytes.size); packer.writePayload(key.bytes)
            packer.packMapHeader(3)
            packer.packString("source"); packer.packBinaryHeader(e.source.size); packer.writePayload(e.source)
            packer.packString("until"); if (e.until == null) packer.packNil() else packer.packLong(e.until)
            packer.packString("reason"); if (e.reason == null) packer.packNil() else packer.packString(e.reason)
        }
        packer.close()
        return out.toByteArray()
    }

    private fun unpackBlackholeFile(data: ByteArray): Map<ByteArray, BlackholeEntry> {
        val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)
        val n = unpacker.unpackMapHeader()
        val out = LinkedHashMap<ByteArray, BlackholeEntry>(n)
        repeat(n) {
            val keyLen = unpacker.unpackBinaryHeader()
            val key = unpacker.readPayload(keyLen)
            val fields = unpacker.unpackMapHeader()
            var source = ByteArray(0); var until: Long? = null; var reason: String? = null
            repeat(fields) {
                when (unpacker.unpackString()) {
                    "source" -> {
                        val l = unpacker.unpackBinaryHeader(); source = unpacker.readPayload(l)
                    }
                    "until" -> if (unpacker.nextFormat.valueType == org.msgpack.value.ValueType.NIL) unpacker.unpackNil() else until = unpacker.unpackLong()
                    "reason" -> if (unpacker.nextFormat.valueType == org.msgpack.value.ValueType.NIL) unpacker.unpackNil() else reason = unpacker.unpackString()
                    else -> unpacker.skipValue()
                }
            }
            out[key] = BlackholeEntry(source, until, reason)
        }
        unpacker.close()
        return out
    }

    private fun String.hexToBytesOrNull(): ByteArray? = try {
        check(length % 2 == 0); chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (e: Exception) { null }

    private fun cullTables() {
        val now = System.currentTimeMillis()
        val withinStartupGrace = now - startTime < TransportConstants.STARTUP_GRACE_PERIOD

        // Remove expired path entries and entries whose interface no longer exists
        // (matches Python Transport.py:707-720)
        pathStore?.removeExpiredBefore(now)
        pathTable.entries.removeIf { entry ->
            val pathEntry = entry.value
            if (pathEntry.isExpired()) {
                pathStore?.removePath(entry.key.bytes)
                log("Path to ${entry.key} timed out and was removed")
                true
            } else if (!withinStartupGrace && findInterfaceByHash(pathEntry.receivingInterfaceHash) == null) {
                // Skip interface-based culling during startup grace period to allow
                // async interface registration (e.g., BLE, TCP) to complete.
                // Python registers interfaces synchronously before first cull.
                pathStore?.removePath(entry.key.bytes)
                log("Path to ${entry.key} was removed since the attached interface no longer exists")
                true
            } else {
                false
            }
        }

        // Evict stale, pathless known destinations to bound the identity caches
        // (python Identity.clean_known_destinations). Runs after the path
        // table cull so `hasPath` reflects the freshly-culled paths.
        network.reticulum.identity.Identity.cleanKnownDestinations({ hasPath(it) }, now)
        network.reticulum.identity.Identity.flushUsedMarkers()

        // Remove expired reverse entries
        reverseTable.entries.removeIf { it.value.isExpired() }

        // Remove unvalidated link entries that have timed out
        // Validated entries are kept longer (until general expiry)
        linkTable.entries.removeIf { entry ->
            val linkEntry = entry.value
            if (!linkEntry.validated && now > linkEntry.proofTimeout) {
                handleStaleTransportedLink(linkEntry, now)
                log("Removing unvalidated link entry: ${entry.key}")
                true
            } else if (linkEntry.validated &&
                now - linkEntry.timestamp > TransportConstants.LINK_TIMEOUT
            ) {
                // Also clean up old validated entries
                true
            } else {
                false
            }
        }

        // Remove expired discovery path requests
        discoveryPathRequests.entries.removeIf { now > it.value.timeout }

        // python Transport.py:993-998, 1108-1110 — forget in-flight path requests older than
        // the gate timeout, so a request that was never answered stops batching new ones.
        inflightPathRequests.entries.removeIf { now > it.value + TransportConstants.PATH_REQUEST_GATE_TIMEOUT }

        // python Transport.py:983-989, 1100-1103 — our own outstanding path requests age out
        // on the same gate timeout. This table had no steady-state cull at all: every
        // destination a transport node ever forwarded a request for stayed in it, and every
        // such destination was permanently exempt from announce ingress holding.
        pathRequests.entries.removeIf { now > it.value + TransportConstants.PATH_REQUEST_GATE_TIMEOUT }

        // python Transport.py:825-826 pops announce_table entries once their
        // retransmission has completed (retries > PATHFINDER_R, reached after
        // PATHFINDER_G + PATHFINDER_RW seconds). Kotlin retransmits through the
        // per-interface announce queues, so an entry's job is done once that window
        // has passed; without this cull the table (one raw announce per unique
        // destination, attacker-supplied) only ever grew.
        // python retransmits at the random window and once more PATHFINDER_G + PATHFINDER_RW
        // later, then completes the entry (Transport.py:771-782). A time cull at one window
        // removed the entry as the retry came due, so the retry never fired; the
        // completion rule is retries, the time bound is a safety net two windows out.
        val announceTtl = ((TransportConstants.PATHFINDER_G + TransportConstants.PATHFINDER_RW) * 1000).toLong()
        announceTable.entries.removeIf { entry ->
            val a = entry.value
            a.retransmits > TransportConstants.PATHFINDER_R || now - a.timestamp > 2 * announceTtl + 1000
        }
        heldAnnounceEntries.entries.removeIf { now - it.value.timestamp > announceTtl }

        // Drop rate entries whose newest arrival has aged well past any block they could
        // still be serving, so a stream of fresh identities cannot grow the map without
        // bound. The window covers the longest block a rate entry can impose.
        val rateCutoff = now - TransportConstants.ANNOUNCE_RATE_ENTRY_TTL
        announceRateTable.entries.removeIf { entry ->
            val newest = entry.value.timestamps.lastOrNull()
            (newest == null || newest < rateCutoff) && now > entry.value.blockedUntil
        }

        // Remove expired tunnels
        cleanExpiredTunnels()

        // Alternates follow the selected row's rules: gone when the destination goes, when
        // their interface deregisters, or when they expire. Last in the cull, so the
        // removals above — expiry and a dangling interface — have already happened and the
        // rows naming those destinations go with them.
        //
        // This was first written inside removeBlackholedPaths, which was wrong twice over:
        // that method returns immediately when nothing is blackholed, so the prune almost
        // never ran, and it is not called from the cull at all. The rows leaked silently,
        // which is exactly the failure that holding two structures in step invites.
        // Blackholing prunes on its own path; see there.
        pruneAlternates()
    }

    // ===== Tunnel Management =====

    /**
     * Check all interfaces for those wanting tunnel synthesis and synthesize them.
     * Called periodically from the job loop.
     */
    private fun synthesizeTunnelsForWaitingInterfaces() {
        for (interface_ in interfaces) {
            if (interface_.wantsTunnel && interface_.online) {
                log("Interface ${interface_.name} wants tunnel, synthesizing...")
                val tunnelId = synthesizeTunnel(interface_)
                if (tunnelId != null) {
                    log("Tunnel synthesized: ${tunnelId.toHexString().take(16)}")
                }
                // wantsTunnel is cleared by synthesizeTunnel on success
            }
        }
    }

    /**
     * Synthesize a virtual tunnel through an interface.
     *
     * Creates a tunnel by:
     * 1. Calculating tunnel ID: SHA256(public_key + interface_hash)
     * 2. Creating signed data: (public_key + interface_hash + random_hash)
     * 3. Sending broadcast packet to "rnstransport.tunnel.synthesize"
     *
     * The receiver validates the signature and establishes the tunnel.
     *
     * @param interface_ The interface to create the tunnel on
     * @return Tunnel ID, or null if synthesis failed
     */
    fun synthesizeTunnel(interface_: InterfaceRef): ByteArray? {
        val transportIdentity =
            identity ?: run {
                log("Cannot synthesize tunnel: no transport identity")
                return null
            }

        // Get interface hash (32 bytes). python uses iface.get_hash() — the
        // SAME hash the interface is registered under — for the tunnel_id
        // derivation (Transport.py:2283). kotlin's `hash` is that value
        // (fullHash of toString()); getInterfaceHash() (fullHash of name) is a
        // divergent second definition that made the emitted tunnel_id
        // inconsistent with the registered interface hash.
        val interfaceHash = interface_.hash

        // Get public key (64 bytes: 32 X25519 + 32 Ed25519)
        val publicKey = transportIdentity.getPublicKey()

        // Calculate tunnel ID: SHA256(public_key + interface_hash)
        val tunnelIdData = publicKey + interfaceHash
        val tunnelId = Hashes.fullHash(tunnelIdData)

        // Create random hash for replay protection (16 bytes, truncated)
        val randomHash = Hashes.getRandomHash()

        // Create signed data: tunnel_id_data + random_hash
        val signedData = tunnelIdData + randomHash

        // Sign the data
        val signature = transportIdentity.sign(signedData)

        // Packet data: public_key(64) + interface_hash(32) + random_hash(16) + signature(64) = 176 bytes
        val packetData = publicKey + interfaceHash + randomHash + signature

        // Create destination hash for "rnstransport.tunnel.synthesize" (PLAIN destination)
        val destHash = Destination.computeHash("rnstransport", listOf("tunnel", "synthesize"), null)

        // Broadcast packet pinned to this one interface. python attaches the interface
        // to the Packet and calls packet.send() (Transport.py:2780-2781), so the frame
        // goes through _outbound -> transmit and gets IFAC-masked like every other
        // frame. Handing packet.pack() straight to interface_.send() skipped
        // transmit()'s applyIfacMasking: on an IFAC'd interface the very first frame
        // left in plaintext, flag clear, and an IFAC'd python TCPServerInterface
        // silently dropped it (Transport.py:1442-1473), so the tunnel was never
        // established and reconnecting clients lost path restoration.
        val packet =
            Packet.createRaw(
                destinationHash = destHash,
                data = packetData,
                packetType = PacketType.DATA,
                destinationType = DestinationType.PLAIN,
                transportType = TransportType.BROADCAST,
                headerType = HeaderType.HEADER_1,
            )
        packet.attachedInterface = interface_

        // outbound() -> processOutbound() -> transmit(): masks, releases jobsLock
        // around the write, and records tx bytes once.
        val sent =
            try {
                outbound(packet)
            } catch (e: Exception) {
                log("Failed to send tunnel synthesis packet: ${e.message}")
                false
            }
        if (!sent) {
            log("Failed to send tunnel synthesis packet on ${interface_.name}")
            return null
        }

        // Mark interface as no longer wanting a tunnel
        interface_.wantsTunnel = false

        log("Synthesized tunnel ${tunnelId.toHexString()} on ${interface_.name}")

        return tunnelId
    }

    /**
     * Handle incoming tunnel synthesis requests.
     *
     * Validates the tunnel synthesis packet:
     * 1. Extracts public_key, interface_hash, random_hash, signature (176 bytes total)
     * 2. Reconstructs tunnel ID: SHA256(public_key + interface_hash)
     * 3. Validates Ed25519 signature over (public_key + interface_hash + random_hash)
     * 4. Creates tunnel entry on successful validation
     *
     * @param data Packet data: public_key(64) + interface_hash(32) + random_hash(16) + signature(64)
     * @param packet The packet containing the request
     * @param receivingInterface The interface that received the packet
     * @return true if tunnel was created successfully
     */
    fun tunnelSynthesizeHandler(
        data: ByteArray,
        @Suppress("UNUSED_PARAMETER") packet: Packet,
        receivingInterface: InterfaceRef,
    ): Boolean {
        // Expected: public_key(64) + interface_hash(32) + random_hash(16) + signature(64) = 176 bytes
        val expectedLength = 64 + 32 + 16 + 64
        if (data.size != expectedLength) {
            log("Invalid tunnel synthesis: expected $expectedLength bytes, got ${data.size}")
            return false
        }

        try {
            // Parse packet components
            val publicKey = data.copyOfRange(0, 64)
            val interfaceHash = data.copyOfRange(64, 96)
            val randomHash = data.copyOfRange(96, 112)
            val signature = data.copyOfRange(112, 176)

            // Calculate tunnel ID: SHA256(public_key + interface_hash)
            val tunnelIdData = publicKey + interfaceHash
            val tunnelId = Hashes.fullHash(tunnelIdData)

            // Reconstruct signed data
            val signedData = tunnelIdData + randomHash

            // Create identity from public key and validate signature
            val remoteIdentity = Identity.fromPublicKey(publicKey)
            if (!remoteIdentity.validate(signature, signedData)) {
                log("Invalid tunnel synthesis signature from ${remoteIdentity.hash.toHexString()}")
                return false
            }

            // Valid signature - handle the tunnel
            handleTunnelEstablishment(tunnelId, receivingInterface)
            return true
        } catch (e: Exception) {
            log("Error validating tunnel synthesis: ${e.message}")
            return false
        }
    }

    /**
     * Handle tunnel establishment after signature validation.
     *
     * If the tunnel already exists (reconnection), restores paths.
     * If new, creates an empty tunnel entry.
     */
    private fun handleTunnelEstablishment(
        tunnelId: ByteArray,
        interface_: InterfaceRef,
    ) {
        val key = ByteArrayKey(tunnelId)
        val now = System.currentTimeMillis()
        // python Transport.py:2821: TUNNEL_TIMEOUT (8 h), not the path table's week.
        val expires = now + TransportConstants.TUNNEL_TIMEOUT

        val existingTunnel = tunnels[key]
        if (existingTunnel == null) {
            // New tunnel - create with empty paths
            if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
                cleanExpiredTunnels()
                if (tunnels.size >= TransportConstants.MAX_TUNNELS) {
                    log("Cannot create tunnel, max tunnels reached")
                    return
                }
            }

            log("Tunnel endpoint ${tunnelId.toHexString()} established")
            val tunnel =
                TunnelInfo(
                    tunnelId = tunnelId,
                    interface_ = interface_,
                    expires = expires,
                )
            interface_.tunnelId = tunnelId
            tunnels[key] = tunnel
            tunnelInterfaces[key] = interface_
            tunnelStore?.upsertTunnel(tunnelId, interface_.hash, tunnel.expires)
        } else {
            // Existing tunnel reappeared - restore paths
            log("Tunnel endpoint ${tunnelId.toHexString()} reappeared. Restoring paths...")
            existingTunnel.interface_ = interface_
            existingTunnel.expires = expires
            existingTunnel.lastActivity = now
            interface_.tunnelId = tunnelId
            tunnelInterfaces[key] = interface_

            // Restore valid paths to main path table
            restoreTunnelPaths(existingTunnel, interface_)
        }
    }

    /**
     * Restore paths from a tunnel to the main path table.
     * Only restores paths that are still valid and better than existing paths.
     */
    private fun restoreTunnelPaths(
        tunnel: TunnelInfo,
        interface_: InterfaceRef,
    ) {
        val now = System.currentTimeMillis()
        val deprecatedPaths = mutableListOf<ByteArrayKey>()

        for ((destKey, pathEntry) in tunnel.paths) {
            // Check if path has expired
            if (now > pathEntry.expires) {
                log("Not restoring expired path to $destKey")
                deprecatedPaths.add(destKey)
                continue
            }

            // Check if we have a better existing path
            val existingPath = pathTable[destKey]
            if (existingPath != null) {
                if (pathEntry.hops > existingPath.hops && now < existingPath.expires) {
                    log("Not restoring path to $destKey: better path exists")
                    continue
                }
            }

            // Restore path to path table
            val restoredPath =
                PathEntry(
                    timestamp = now,
                    nextHop = pathEntry.receivedFrom,
                    hops = pathEntry.hops,
                    expires = pathEntry.expires,
                    randomBlobs = pathEntry.randomBlobs.toMutableList(),
                    receivingInterfaceHash = interface_.hash,
                    announcePacketHash = pathEntry.packetHash,
                )
            pathTable[destKey] = restoredPath
            pathStore?.upsertPath(destKey.bytes, restoredPath)
            log("Restored path to $destKey (${pathEntry.hops} hops) via tunnel ${tunnel.tunnelId.toHexString()}")
        }

        // Remove expired paths from tunnel
        deprecatedPaths.forEach { tunnel.paths.remove(it) }
    }

    /**
     * Add an announce path to a tunnel for persistence.
     *
     * If the receiving interface has an associated tunnel, the path is stored
     * in the tunnel so it can be restored if the tunnel reconnects.
     *
     * @param destHash Destination hash for the path
     * @param pathEntry The path entry from the path table
     * @param packet The announce packet
     * @param interface_ The receiving interface
     */
    private fun addPathToTunnel(
        destHash: ByteArray,
        pathEntry: PathEntry,
        packet: Packet,
        interface_: InterfaceRef,
    ) {
        val tunnelId = interface_.tunnelId ?: return
        val tunnel = tunnels[ByteArrayKey(tunnelId)] ?: return

        // Create tunnel path entry
        val tunnelPath =
            TunnelPathEntry(
                timestamp = pathEntry.timestamp,
                receivedFrom = pathEntry.nextHop.copyOf(),
                hops = pathEntry.hops,
                expires = pathEntry.expires,
                randomBlobs = pathEntry.randomBlobs.toMutableList(),
                packetHash = packet.packetHash.copyOf(),
            )

        tunnel.paths[destHash.toKey()] = tunnelPath
        tunnel.expires = System.currentTimeMillis() + TransportConstants.TUNNEL_TIMEOUT
        tunnel.lastActivity = System.currentTimeMillis()
        tunnelStore?.upsertTunnelPath(tunnelId, destHash, tunnelPath)

        log("Path to ${destHash.toHexString()} associated with tunnel ${tunnelId.toHexString()}")

        // Cache the announce packet for later restoration
        cacheAnnouncePacket(packet, interface_)
    }

    /**
     * Cache an announce packet for tunnel path restoration.
     *
     * When caching packets to storage, they are written exactly as they arrived
     * over their interface. This means they have not had their hop count increased yet!
     * Take note of this when reading from the packet cache.
     *
     * @param packet The announce packet to cache
     * @param interface_ The receiving interface
     */
    private fun cacheAnnouncePacket(
        packet: Packet,
        interface_: InterfaceRef,
    ) {
        // Use store if available (Room on Android)
        announceStore?.let { store ->
            val raw = packet.raw ?: return
            store.cacheAnnounce(packet.packetHash, raw, interface_.name)
            logDebug { "Cached announce packet ${packet.packetHash.toHexString().take(12)}" }
            return
        }

        try {
            // Create announces cache directory if needed
            val announcesDir = File("$cachePath/announces")
            if (!announcesDir.exists()) {
                announcesDir.mkdirs()
            }

            // Get packet hash as hex for filename
            val packetHash = packet.packetHash
            val packetHashHex = packetHash.toHexString()
            val cacheFile = File(announcesDir, packetHashHex)

            // Pack: [raw_bytes, interface_name]
            val output = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(output)
            packer.packArrayHeader(2)

            // Pack raw packet data
            val raw = packet.raw ?: return
            packer.packBinaryHeader(raw.size)
            packer.writePayload(raw)

            // Pack interface name (used to find interface on restore)
            packer.packString(interface_.name)

            packer.close()

            // Write to file
            cacheFile.writeBytes(output.toByteArray())

            logDebug { "Cached announce packet ${packetHashHex.take(12)}" }
        } catch (e: Exception) {
            log("Error caching announce packet: ${e.message}")
        }
    }

    /**
     * Retrieve a cached announce packet by hash.
     *
     * @param packetHash The packet hash to look up
     * @return The raw packet bytes and interface name, or null if not found
     */
    fun getCachedAnnouncePacket(packetHash: ByteArray): Pair<ByteArray, String?>? {
        // Use store if available (Room on Android)
        announceStore?.let { return it.getAnnounce(packetHash) }

        try {
            val packetHashHex = packetHash.toHexString()
            val cacheFile = File("$cachePath/announces", packetHashHex)

            if (!cacheFile.exists()) {
                return null
            }

            val data = cacheFile.readBytes()
            val unpacker = MessagePack.newDefaultUnpacker(data)

            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize != 2) {
                log("Invalid cached announce format")
                return null
            }

            // Unpack raw packet data
            val rawLen = unpacker.unpackBinaryHeader()
            val raw = ByteArray(rawLen)
            unpacker.readPayload(raw)

            // Unpack interface name
            val interfaceName = unpacker.unpackString()

            unpacker.close()

            return Pair(raw, interfaceName)
        } catch (e: Exception) {
            log("Error reading cached announce packet: ${e.message}")
            return null
        }
    }

    /**
     * Clean up expired announce packets from the cache.
     * Removes cached announces that are no longer referenced by path table or tunnels.
     */
    private fun cleanAnnounceCache() {
        // Use store if available (Room on Android)
        announceStore?.let { store ->
            val activeHashes = mutableSetOf<ByteArrayKey>()
            for ((_, pathEntry) in pathTable) {
                activeHashes.add(pathEntry.announcePacketHash.toKey())
            }
            for ((_, tunnel) in tunnels) {
                for ((_, tunnelPath) in tunnel.paths) {
                    activeHashes.add(tunnelPath.packetHash.toKey())
                }
            }
            store.removeAllExcept(activeHashes)
            return
        }

        try {
            val announcesDir = File("$cachePath/announces")
            if (!announcesDir.exists()) return

            // Collect active packet hashes from path table
            val activeHashes = mutableSetOf<String>()
            for ((_, pathEntry) in pathTable) {
                activeHashes.add(pathEntry.announcePacketHash.toHexString())
            }

            // Collect packet hashes from tunnel paths
            for ((_, tunnel) in tunnels) {
                for ((_, tunnelPath) in tunnel.paths) {
                    activeHashes.add(tunnelPath.packetHash.toHexString())
                }
            }

            // Remove files not in active set
            var removed = 0
            announcesDir.listFiles()?.forEach { file ->
                if (!activeHashes.contains(file.name)) {
                    if (file.delete()) {
                        removed++
                    }
                }
            }

            if (removed > 0) {
                log("Removed $removed expired cached announces")
            }
        } catch (e: Exception) {
            log("Error cleaning announce cache: ${e.message}")
        }
    }

    // ===== Tunnel Table Persistence =====

    /** Maximum random blobs to persist per path — reuses TransportConstants value */

    /**
     * Save tunnel table to persistent storage.
     * Format: Array of [tunnel_id, interface_hash, paths_array, expires]
     * Each path: [dest_hash, timestamp, received_from, hops, expires, random_blobs, interface_hash, packet_hash]
     */
    fun saveTunnelTable() {
        // When Room store is active, write-through handles persistence
        if (tunnelStore != null) return

        try {
            val startTime = System.currentTimeMillis()
            log("Saving tunnel table to storage...")

            val output = ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(output)

            // Pack array of tunnels
            packer.packArrayHeader(tunnels.size)

            for ((_, tunnel) in tunnels) {
                // Pack tunnel: [tunnel_id, interface_hash, paths, expires]
                packer.packArrayHeader(4)

                // tunnel_id
                packer.packBinaryHeader(tunnel.tunnelId.size)
                packer.writePayload(tunnel.tunnelId)

                // interface_hash (or nil if no interface) — use the registered
                // interface hash, consistent with synthesizeTunnel and python.
                val interfaceHash = tunnel.interface_?.hash
                if (interfaceHash != null) {
                    packer.packBinaryHeader(interfaceHash.size)
                    packer.writePayload(interfaceHash)
                } else {
                    packer.packNil()
                }

                // paths array
                packer.packArrayHeader(tunnel.paths.size)
                for ((destKey, pathEntry) in tunnel.paths) {
                    // Path: [dest_hash, timestamp, received_from, hops, expires, random_blobs, interface_hash, packet_hash]
                    packer.packArrayHeader(8)

                    // dest_hash
                    packer.packBinaryHeader(destKey.bytes.size)
                    packer.writePayload(destKey.bytes)

                    // timestamp
                    packer.packLong(pathEntry.timestamp)

                    // received_from
                    packer.packBinaryHeader(pathEntry.receivedFrom.size)
                    packer.writePayload(pathEntry.receivedFrom)

                    // hops
                    packer.packInt(pathEntry.hops)

                    // expires
                    packer.packLong(pathEntry.expires)

                    // random_blobs (limit to last PERSIST_RANDOM_BLOBS)
                    val blobsToSave = pathEntry.randomBlobs.takeLast(TransportConstants.PERSIST_RANDOM_BLOBS)
                    packer.packArrayHeader(blobsToSave.size)
                    for (blob in blobsToSave) {
                        packer.packBinaryHeader(blob.size)
                        packer.writePayload(blob)
                    }

                    // interface_hash (same as tunnel's interface)
                    if (interfaceHash != null) {
                        packer.packBinaryHeader(interfaceHash.size)
                        packer.writePayload(interfaceHash)
                    } else {
                        packer.packNil()
                    }

                    // packet_hash
                    packer.packBinaryHeader(pathEntry.packetHash.size)
                    packer.writePayload(pathEntry.packetHash)
                }

                // expires
                packer.packLong(tunnel.expires)
            }

            packer.close()

            // Write to file
            val tunnelsFile = File("$storagePath/tunnels")
            tunnelsFile.parentFile?.mkdirs()
            tunnelsFile.writeBytes(output.toByteArray())

            val saveTime = System.currentTimeMillis() - startTime
            log("Saved ${tunnels.size} tunnel table entries in ${saveTime}ms")
        } catch (e: Exception) {
            log("Error saving tunnel table: ${e.message}")
        }
    }

    /**
     * Load tunnel table from persistent storage.
     */
    fun loadTunnelTable() {
        // When Room store is active, load from database
        tunnelStore?.let { store ->
            val loaded = store.loadAllTunnels()
            tunnels.putAll(loaded)
            log("Loaded ${loaded.size} tunnels from store")
            return
        }

        try {
            val tunnelsFile = File("$storagePath/tunnels")
            if (!tunnelsFile.exists()) {
                log("No tunnel table found in storage")
                return
            }

            val data = tunnelsFile.readBytes()
            val unpacker = MessagePack.newDefaultUnpacker(data)

            val tunnelCount = unpacker.unpackArrayHeader()

            for (i in 0 until tunnelCount) {
                val tunnelSize = unpacker.unpackArrayHeader()
                if (tunnelSize != 4) {
                    log("Invalid tunnel entry size: $tunnelSize")
                    continue
                }

                // tunnel_id
                val tunnelIdLen = unpacker.unpackBinaryHeader()
                val tunnelId = ByteArray(tunnelIdLen)
                unpacker.readPayload(tunnelId)

                // interface_hash (may be nil)
                val interfaceHash: ByteArray? =
                    if (unpacker.tryUnpackNil()) {
                        null
                    } else {
                        val hashLen = unpacker.unpackBinaryHeader()
                        ByteArray(hashLen).also { unpacker.readPayload(it) }
                    }

                // paths array
                val pathCount = unpacker.unpackArrayHeader()
                val paths = mutableMapOf<ByteArrayKey, TunnelPathEntry>()

                for (j in 0 until pathCount) {
                    val pathSize = unpacker.unpackArrayHeader()
                    if (pathSize != 8) {
                        log("Invalid path entry size: $pathSize")
                        continue
                    }

                    // dest_hash
                    val destHashLen = unpacker.unpackBinaryHeader()
                    val destHash = ByteArray(destHashLen)
                    unpacker.readPayload(destHash)

                    // timestamp
                    val timestamp = unpacker.unpackLong()

                    // received_from
                    val receivedFromLen = unpacker.unpackBinaryHeader()
                    val receivedFrom = ByteArray(receivedFromLen)
                    unpacker.readPayload(receivedFrom)

                    // hops
                    val hops = unpacker.unpackInt()

                    // expires
                    val expires = unpacker.unpackLong()

                    // random_blobs
                    val blobCount = unpacker.unpackArrayHeader()
                    val randomBlobs = mutableListOf<ByteArray>()
                    for (k in 0 until blobCount) {
                        val blobLen = unpacker.unpackBinaryHeader()
                        val blob = ByteArray(blobLen)
                        unpacker.readPayload(blob)
                        randomBlobs.add(blob)
                    }

                    // interface_hash (skip, same as tunnel's)
                    if (unpacker.tryUnpackNil()) {
                        // nil, skip
                    } else {
                        val skipLen = unpacker.unpackBinaryHeader()
                        val skipBytes = ByteArray(skipLen)
                        unpacker.readPayload(skipBytes) // Skip the bytes
                    }

                    // packet_hash
                    val packetHashLen = unpacker.unpackBinaryHeader()
                    val packetHash = ByteArray(packetHashLen)
                    unpacker.readPayload(packetHash)

                    // Check if cached announce packet exists
                    val cachedAnnounce = getCachedAnnouncePacket(packetHash)
                    if (cachedAnnounce != null && !isPathExpired(expires)) {
                        val pathEntry =
                            TunnelPathEntry(
                                timestamp = timestamp,
                                receivedFrom = receivedFrom,
                                hops = hops,
                                expires = expires,
                                randomBlobs = randomBlobs,
                                packetHash = packetHash,
                            )
                        paths[ByteArrayKey(destHash)] = pathEntry
                    }
                }

                // expires
                val tunnelExpires = unpacker.unpackLong()

                // Only add tunnel if it has valid paths
                if (paths.isNotEmpty()) {
                    val tunnel =
                        TunnelInfo(
                            tunnelId = tunnelId,
                            interface_ = null, // Will be reconnected when interface comes back
                            expires = tunnelExpires,
                        )
                    tunnel.paths.putAll(paths)
                    tunnels[ByteArrayKey(tunnelId)] = tunnel
                    log("Loaded tunnel ${tunnelId.toHexString().take(12)} with ${paths.size} paths")
                }
            }

            unpacker.close()

            log("Loaded ${tunnels.size} tunnels from storage")
        } catch (e: Exception) {
            log("Error loading tunnel table: ${e.message}")
        }
    }

    /**
     * Check if a path has expired.
     */
    private fun isPathExpired(expires: Long): Boolean = System.currentTimeMillis() > expires

    /**
     * Persist all transport data.
     * Called during shutdown.
     */
    fun persistData() {
        saveTunnelTable()
    }

    /**
     * Save path table and packet hashlist to storage directory.
     * Uses filenames matching Python reference: "destination_table" and "packet_hashlist".
     */
    private fun persistDataToStorage() {
        // When Room stores are active, write-through handles persistence
        if (pathStore != null) {
            // Batch-persist packet hashlist (not write-through)
            packetHashStore?.let { store ->
                store.saveAll(packetHashlist.toSet(), 0)
                store.saveAll(packetHashlistPrev.toSet(), 1)
            }
            return
        }
        if (storagePath.isBlank()) return
        val dir = java.io.File(storagePath)
        dir.mkdirs()
        savePathTable(java.io.File(dir, "destination_table"))
        savePacketHashlist(java.io.File(dir, "packet_hashlist"))
    }

    /**
     * Load path table and packet hashlist from storage directory.
     * Uses filenames matching Python reference: "destination_table" and "packet_hashlist".
     */
    private fun loadPersistedDataFromStorage() {
        // When Room stores are active, load from database
        pathStore?.let { store ->
            val paths = store.loadAllPaths()
            pathTable.putAll(paths)
            log("Loaded ${paths.size} path entries from store")

            packetHashStore?.let { hashStore ->
                val (current, prev) = hashStore.loadAll()
                packetHashlist.addAll(current)
                packetHashlistPrev.addAll(prev)
                log("Loaded ${current.size + prev.size} packet hashes from store")
            }
            return
        }
        if (storagePath.isBlank()) return
        val dir = java.io.File(storagePath)
        if (!dir.exists()) return
        loadPathTable(java.io.File(dir, "destination_table"))
        loadPacketHashlist(java.io.File(dir, "packet_hashlist"))
    }

    /**
     * Remove a tunnel and its associated interface.
     *
     * @param tunnelId The tunnel ID to void
     * @return true if tunnel was removed
     */
    fun voidTunnelInterface(tunnelId: ByteArray): Boolean {
        val key = ByteArrayKey(tunnelId)

        val removed = tunnels.remove(key)
        tunnelInterfaces.remove(key)
        tunnelStore?.removeTunnel(tunnelId)

        if (removed != null) {
            log("Voided tunnel ${tunnelId.toHexString()}")
            return true
        }

        return false
    }

    /**
     * Handle packet routing through a tunnel.
     *
     * @param tunnelId The tunnel to route through
     * @param packet The packet data to transmit
     * @return true if transmitted successfully
     */
    fun handleTunnel(
        tunnelId: ByteArray,
        packet: ByteArray,
    ): Boolean {
        val key = ByteArrayKey(tunnelId)
        val tunnel = tunnels[key] ?: return false

        // Update tunnel activity
        tunnel.lastActivity = System.currentTimeMillis()
        tunnel.rxBytes += packet.size

        // Get the interface for this tunnel
        val interface_ = tunnelInterfaces[key] ?: return false

        // Transmit through tunnel interface
        return try {
            transmit(interface_, packet)
            tunnel.txBytes += packet.size
            true
        } catch (e: Exception) {
            log("Failed to transmit through tunnel: ${e.message}")
            false
        }
    }

    /**
     * Get tunnel info by ID.
     *
     * @param tunnelId The tunnel ID
     * @return TunnelInfo or null if not found
     */
    fun getTunnel(tunnelId: ByteArray): TunnelInfo? = tunnels[ByteArrayKey(tunnelId)]

    /**
     * Check if a tunnel exists.
     *
     * @param tunnelId The tunnel ID to check
     * @return true if tunnel exists
     */
    fun hasTunnel(tunnelId: ByteArray): Boolean = tunnels.containsKey(ByteArrayKey(tunnelId))

    /**
     * Get all active tunnels.
     *
     * @return Map of tunnel IDs to TunnelInfo
     */
    fun getTunnels(): Map<ByteArrayKey, TunnelInfo> = tunnels.toMap()

    /**
     * Get interface for a tunnel.
     *
     * @param tunnelId The tunnel ID
     * @return InterfaceRef or null if not found
     */
    fun getTunnelInterface(tunnelId: ByteArray): InterfaceRef? = tunnelInterfaces[ByteArrayKey(tunnelId)]

    /**
     * Remove expired tunnels.
     */
    fun cleanExpiredTunnels() {
        val now = System.currentTimeMillis()
        // python Transport.py:1019-1027: an entry expiring more than two lifetimes out is
        // corrupt and goes too; :1036-1042: a tunnel path older than TUNNEL_PATH_TIMEOUT
        // is dropped from the tunnel.
        val expired = tunnels.filter { it.value.isExpired() || it.value.expires > now + 2 * TransportConstants.TUNNEL_TIMEOUT }
        for ((key, tunnel) in expired) {
            tunnels.remove(key)
            tunnelInterfaces.remove(key)
            tunnelStore?.removeTunnel(tunnel.tunnelId)
            log("Cleaned expired tunnel ${tunnel.tunnelId.toHexString()}")
        }
        for (tunnel in tunnels.values) {
            val stale = tunnel.paths.filter { now > it.value.timestamp + TransportConstants.TUNNEL_PATH_TIMEOUT }.keys
            for (destKey in stale) {
                tunnel.paths.remove(destKey)
                logDebug { "Tunnel path to $destKey timed out and was removed" }
            }
        }
    }

    /** Establish (or restore) a tunnel endpoint as an inbound synthesis would (tunnel expiry tests). */
    @network.reticulum.RnsTestSeam
    fun establishTunnelForTest(
        tunnelId: ByteArray,
        interfaceRef: InterfaceRef,
    ) = handleTunnelEstablishment(tunnelId, interfaceRef)

    // ===== Helpers =====

    private fun findInterfaceByHash(hash: ByteArray): InterfaceRef? =
        // contentEquals: the previous form allocated a ByteArrayKey per interface per lookup
        // (path-routed outbound, forward, proof, cull). Still O(#interfaces), zero allocations.
        interfaces.find { it.hash.contentEquals(hash) }
            ?: localClientInterfaces.find { it.hash.contentEquals(hash) }

    private fun log(message: String) {
        RnsLog.log(RnsLog.INFO, "Transport", message)
    }

    /** Lazy DEBUG-level log for per-packet hot paths — message built only when enabled. */
    private inline fun logDebug(message: () -> String) {
        RnsLog.log(RnsLog.DEBUG, "Transport", message)
    }
}

/**
 * Reference to an interface for transport routing.
 *
 * This abstraction allows Transport to work with interfaces without
 * depending on the full Interface class from rns-interfaces.
 */
interface InterfaceRef {
    val name: String
    val hash: ByteArray
    val canSend: Boolean
    val canReceive: Boolean
    val online: Boolean

    /** Traffic counters. */
    val rxBytes: Long get() = 0
    val txBytes: Long get() = 0

    /** Interface operational mode. */
    val mode: InterfaceMode
        get() = InterfaceMode.FULL

    /** Bitrate in bits per second (0 if unknown). */
    val bitrate: Int
        get() = 0

    /** Announce bandwidth cap as fraction of bitrate (default 2%). */
    val announceCap: Double
        get() = TransportConstants.ANNOUNCE_CAP

    /**
     * Minimum seconds between announces from one destination before this interface starts
     * counting violations, or null to apply no rate limiting at all (python
     * `announce_rate_target`). The two knobs below only have meaning when this is set.
     */
    val announceRateTarget: Int?
        get() = null

    /** Violations tolerated before a destination is blocked (python `announce_rate_grace`). */
    val announceRateGrace: Int
        get() = 0

    /** Seconds added on top of the target when blocking (python `announce_rate_penalty`). */
    val announceRatePenalty: Int
        get() = 0

    /** Whether announces learned on an INTERNAL interface may leave via this one. */
    val announcesFromInternal: Boolean
        get() = true

    /** Whether announces arriving here may be carried onto an INTERNAL interface. */
    val announcesToInternal: Boolean?
        get() = null

    /** Hardware MTU in bytes. */
    val hwMtu: Int
        get() = RnsConstants.MTU

    /** Whether this interface supports link MTU discovery (Python: AUTOCONFIGURE_MTU or FIXED_MTU). */
    val supportsLinkMtuDiscovery: Boolean
        get() = false

    // IFAC (Interface Access Code) properties
    /** IFAC size in bytes. 0 means IFAC is disabled. */
    val ifacSize: Int
        get() = 0

    /** IFAC key derived from network name/key. */
    val ifacKey: ByteArray?
        get() = null

    /** IFAC identity for signing packets. */
    val ifacIdentity: network.reticulum.identity.Identity?
        get() = null

    // Tunnel properties
    /** Tunnel ID associated with this interface, if any. */
    var tunnelId: ByteArray?

    /** Whether this interface wants to establish a tunnel. */
    var wantsTunnel: Boolean

    // Shared instance properties (Python RNS compatibility)
    /** Whether this is a local shared instance server (Python: is_local_shared_instance). */
    val isLocalSharedInstance: Boolean
        get() = false

    /** Whether this is a client connected to a shared instance (Python: is_connected_to_shared_instance). */
    val isConnectedToSharedInstance: Boolean
        get() = false

    /** Parent interface for spawned interfaces (Python: parent_interface). */
    val parentInterface: InterfaceRef?
        get() = null

    /** Physical layer stats from the most recently received packet. */
    val rStatRssi: Int?
        get() = null
    val rStatSnr: Float?
        get() = null
    val rStatQ: Float?
        get() = null

    // Discovery properties
    /** Whether this interface type supports discovery at all. */
    val supportsDiscovery: Boolean get() = false

    /** Whether discovery is enabled for this particular instance. */
    val discoverable: Boolean get() = false

    /** Last time a discovery announce was sent (epoch seconds). */
    var lastDiscoveryAnnounce: Long
        get() = 0L
        set(_) {}

    /** Interval between discovery announces (seconds). */
    val discoveryAnnounceInterval: Long
        get() = network.reticulum.discovery.DiscoveryConstants.DEFAULT_ANNOUNCE_INTERVAL

    /** Human-readable name for discovery announces. */
    val discoveryName: String? get() = null

    /** Whether to encrypt discovery announce payloads. */
    val discoveryEncrypt: Boolean get() = false

    /** Required stamp value for this interface's announces (null = use default). */
    val discoveryStampValue: Int? get() = null

    /** Whether to include IFAC credentials in discovery announces. */
    val discoveryPublishIfac: Boolean get() = false

    /** IFAC network name (for publishing in discovery). */
    val ifacNetname: String? get() = null

    /** IFAC network key (for publishing in discovery). */
    val ifacNetkey: String? get() = null

    /** Geographic coordinates for discovery. */
    val discoveryLatitude: Double? get() = null
    val discoveryLongitude: Double? get() = null
    val discoveryHeight: Double? get() = null

    /** The interface type name as it appears in discovery announces. */
    val discoveryInterfaceType: String get() = "Interface"

    /** Whether this interface uses KISS framing (python: interface.kiss_framing,
     * read by the discovery announce builder's TCPClient/KISS rules). */
    val kissFraming: Boolean get() = false

    /** Python-style qualified name: "TCPClientInterface[homelab]". Used for interface type detection. */
    val qualifiedName: String get() = "$discoveryInterfaceType[$name]"

    /** Type-specific discovery data (TCP: reachable_on/port, RNode: freq/bw/sf/cr, etc.). */
    fun getDiscoveryData(): Map<Int, Any>? = null

    /**
     * Get a hash uniquely identifying this interface.
     * Used for tunnel ID calculation: hash(public_key + interface_hash).
     */
    fun getInterfaceHash(): ByteArray = Hashes.fullHash(name.toByteArray(Charsets.UTF_8))

    // Ingress control methods — implemented by Interface (via InterfaceAdapter),
    // no-ops by default for test/stub interfaces.

    /** Check if incoming announces should be rate-limited (burst detection). */
    fun shouldIngressLimit(): Boolean = false

    /** Record that an announce was received on this interface (for frequency tracking). */
    fun recordIncomingAnnounce() {}

    /** Whether inbound path requests should be rate-limited (python should_ingress_limit_pr). */
    fun shouldIngressLimitPr(): Boolean = false

    /** python `should_egress_limit_pr` (Interface.py:240-248); false unless `egress_control` is on. */
    fun shouldEgressLimitPr(): Boolean = false

    /** python `sent_path_request` (Interface.py:320-324): one sample in the outgoing path-request deque. */
    fun recordOutgoingPathRequest() {}

    /** python `protocol_violation` (Interface.py:326-329): count it and log at DEBUG. */
    fun protocolViolation(description: String) {}

    /** python `Interface.protocol_violations`, reported by `rnstatus`. */
    val protocolViolations: Long get() = 0
    /** Record that a path request was received on this interface (python received_path_request). */
    fun recordIncomingPathRequest() {}

    // Held announce methods — per-interface storage for ingress-controlled announces.
    // Implemented by Interface (via InterfaceAdapter), no-ops for test/stub interfaces.

    /** Hold an announce for later release when burst subsides. */
    fun holdAnnounce(
        destinationHash: ByteArray,
        raw: ByteArray,
        hops: Int,
        receivingInterface: InterfaceRef,
    ) {}

    /** Process held announces: release one (min-hops) if burst has subsided. */
    fun processHeldAnnounces() {}

    /** Number of announces currently held on this interface. */
    fun heldAnnounceCount(): Int = 0

    fun send(data: ByteArray)

    /** Detach the underlying interface (close connections, release resources). */
    fun detach() {}
}

/**
 * A raw announce held for later re-injection when ingress burst subsides.
 * Stored per-interface, keyed by destination hash.
 */
data class HeldAnnounce(
    val destinationHash: ByteArray,
    val raw: ByteArray,
    val hops: Int,
    val receivingInterface: InterfaceRef,
)
