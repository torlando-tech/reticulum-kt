package network.reticulum

import network.reticulum.common.RnsConstants
import network.reticulum.common.hexToByteArray
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the Reticulum Network Stack.
 *
 * This class initializes and manages the Reticulum instance. You must create
 * exactly one instance of this class before performing any other RNS operations.
 *
 * Usage:
 * ```kotlin
 * // Initialize with defaults
 * val reticulum = Reticulum.start()
 *
 * // Or with custom config
 * val reticulum = Reticulum.start(
 *     configDir = "/path/to/config",
 *     enableTransport = true
 * )
 *
 * // Start as shared instance (other apps can connect)
 * val reticulum = Reticulum.start(
 *     shareInstance = true,
 *     sharedInstancePort = 37428
 * )
 *
 * // Connect to existing shared instance
 * val reticulum = Reticulum.start(
 *     connectToSharedInstance = true,
 *     sharedInstancePort = 37428
 * )
 *
 * // Create identity and destination
 * val identity = Identity.create()
 * val destination = Destination.create(identity, ...)
 *
 * // Shutdown when done
 * Reticulum.stop()
 * ```
 */
class Reticulum private constructor(
    val configDir: String,
    val enableTransport: Boolean,
    val shareInstance: Boolean,
    val sharedInstancePort: Int,
    val connectToSharedInstance: Boolean,
    /**
     * Optional in-memory transport identity. When non-null, [initialize] uses this
     * identity directly, skips the load-from-file / create-and-save-to-file flow,
     * and zero-overwrites + deletes any stale `$storagePath/transport_identity`
     * file left behind by a prior file-backed run — so the private key does not
     * need to touch disk as plaintext in the current session. The overwrite-and-
     * delete of the legacy file is best-effort; on wear-levelled or journalled
     * storage the underlying blocks may still be recoverable forensically.
     * Callers that manage their own identity persistence (e.g. encrypted-at-rest
     * in a Room database or Keystore-wrapped in a secure enclave) can pass an
     * Identity built via [Identity.fromBytes]. When null, Reticulum retains the
     * legacy `$storagePath/transport_identity` file behaviour.
     */
    private val transportIdentityOverride: Identity? = null,
    /**
     * Process-wide posture knobs. kotlin has no INI config layer / RNS
     * `__apply_config`, so the flags python resolves from config
     * (Reticulum.py:253-281, :497-558, :575-591) are threaded here as typed
     * constructor parameters with RNS-matching defaults. See port-deviations.md.
     */
    val respondToProbes: Boolean = false,            // python __allow_probes (default False, :257)
    val useImplicitProof: Boolean = true,            // python __use_implicit_proof (default True, :256)
    val enableRemoteManagement: Boolean = false,     // python __remote_management_enabled (default False, :255)
    private val remoteManagementAllowedHashes: List<ByteArray> = emptyList(),
    val panicOnInterfaceError: Boolean = false,      // python panic_on_interface_error (default False, :281)
    private val blackholeSourceHashes: List<ByteArray> = emptyList(),
    /** Trusted interface-discovery-source identity hashes (python __interface_sources). */
    val interfaceDiscoverySources: List<ByteArray> = emptyList(),
    /** Raw rpc_key hex string; parsed (with SHA-256(privkey) fallback) in initialize(). */
    private val rpcKeyHex: String? = null,
) {
    companion object {
        /**
         * MTU that Reticulum adheres to (500 bytes by default).
         * All peers must use the same MTU to communicate.
         */
        const val MTU = RnsConstants.MTU

        /**
         * Maximum Data Unit - maximum payload size in a single packet.
         */
        const val MDU = RnsConstants.MTU - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE

        /**
         * Whether link MTU discovery is enabled.
         */
        const val LINK_MTU_DISCOVERY = true

        /**
         * Maximum queued announces.
         */
        const val MAX_QUEUED_ANNOUNCES = 16384

        /**
         * How long a queued announce survives before being purged as stale,
         * in seconds (python: QUEUED_ANNOUNCE_LIFE = 60*60*24, Reticulum.py:111).
         */
        const val QUEUED_ANNOUNCE_LIFE = 60 * 60 * 24

        /**
         * Announce cap - maximum percentage of bandwidth for announces.
         */
        const val ANNOUNCE_CAP = 2

        /**
         * Minimum bitrate required for links (5 bps).
         */
        const val MINIMUM_BITRATE = 5

        /**
         * Default timeout per hop (6 seconds).
         */
        const val DEFAULT_PER_HOP_TIMEOUT = 6

        /**
         * Default TCP port for shared instance communication.
         */
        const val DEFAULT_SHARED_INSTANCE_PORT = 37428

        /**
         * Interface-discovery master gate (python __discover_interfaces,
         * Reticulum.py:259 — default OFF; config `discover_interfaces`).
         */
        @Volatile var discoverInterfaces: Boolean = false

        /**
         * Autoconnect-discovered-interfaces knob (python
         * __autoconnect_discovered_interfaces, Reticulum.py:260,592-595 —
         * default off; a configured value > 0 is the max autoconnect count).
         */
        @Volatile var autoconnectDiscoveredInterfaces: Int = 0

        /** python Reticulum.should_autoconnect_discovered_interfaces(). */
        fun shouldAutoconnectDiscoveredInterfaces(): Boolean = autoconnectDiscoveredInterfaces > 0

        /** python Reticulum.max_autoconnected_interfaces(). */
        fun maxAutoconnectedInterfaces(): Int = autoconnectDiscoveredInterfaces

        /**
         * Process-wide implicit-vs-explicit single-packet PROOF policy. RNS keeps
         * this as the class attribute `__use_implicit_proof` (python
         * Reticulum.py:256, default True; config parse :555-558), so it is static
         * here too. [initialize] seeds it from the started instance's configured
         * posture, mirroring python's `__init__` setting the class attribute.
         * Identity.prove branches on it (Identity.py:961-963): implicit emits the
         * signature only, explicit prepends the packet hash.
         */
        @Volatile
        private var implicitProofPolicy: Boolean = true

        /** python RNS.Reticulum.should_use_implicit_proof() (Reticulum.py:1699-1705). */
        fun shouldUseImplicitProof(): Boolean = implicitProofPolicy

        /**
         * Conformance seam: set the implicit-proof policy the prover reads (the
         * reference flips the same `__use_implicit_proof` class attribute,
         * python Reticulum.py:555-558 / the bridge's `_Reticulum__use_implicit_proof`,
         * wire_tcp.py:5871). No port logic — just exposes the static flag.
         */
        fun setUseImplicitProofForTest(enabled: Boolean) {
            implicitProofPolicy = enabled
        }

        /** Internal: [initialize] seeds the static policy from the instance posture. */
        internal fun seedImplicitProofPolicy(enabled: Boolean) {
            implicitProofPolicy = enabled
        }

        @Volatile
        private var instance: Reticulum? = null
        private val started = AtomicBoolean(false)

        // Pre-set factories (set before start, applied to instance during init)
        private var pendingLocalClientFactory: ((Int, String) -> Any)? = null
        private var pendingLocalServerFactory: ((Int) -> Any)? = null
        private var pendingInterfaceRegistrar: ((Any) -> Unit)? = null

        /**
         * Set the LocalClientInterface factory before calling start().
         * This allows apps to connect to an existing shared Reticulum instance.
         */
        fun setLocalClientFactory(factory: (port: Int, host: String) -> Any) {
            pendingLocalClientFactory = factory
        }

        /**
         * Set the LocalServerInterface factory before calling start().
         * This allows apps to share their Reticulum instance with others.
         */
        fun setLocalServerFactory(factory: (port: Int) -> Any) {
            pendingLocalServerFactory = factory
        }

        /**
         * Set an interface registrar that can adapt and register interfaces with Transport.
         * This bridges rns-core and rns-interfaces by allowing the app to provide
         * the InterfaceAdapter.toRef() + Transport.registerInterface() logic.
         */
        fun setInterfaceRegistrar(registrar: (Any) -> Unit) {
            pendingInterfaceRegistrar = registrar
        }

        /**
         * Get the current Reticulum instance.
         * @throws IllegalStateException if not started
         */
        fun getInstance(): Reticulum = instance ?: throw IllegalStateException("Reticulum not started")

        /**
         * Check if Reticulum is currently running.
         */
        fun isStarted(): Boolean = started.get()

        /**
         * Start Reticulum with the given configuration.
         *
         * @param configDir Directory for configuration and storage
         * @param enableTransport Whether to enable transport layer (routing)
         * @param shareInstance Whether to share this instance with other apps (starts local server)
         * @param sharedInstancePort TCP port for shared instance communication
         * @param connectToSharedInstance Whether to connect to an existing shared instance
         * @param transportIdentity Optional in-memory transport identity. When non-null, the
         *   private key is used directly, `$storagePath/transport_identity` is never read or
         *   written in the current session, and any stale file left by a prior file-backed
         *   run is zero-overwritten and deleted on a best-effort basis. True secure erasure
         *   is not achievable on wear-levelled or journalled storage; callers needing that
         *   guarantee must rely on device-level full-disk encryption. When null (default),
         *   the legacy file-backed flow runs.
         * @return The Reticulum instance
         */
        fun start(
            configDir: String? = null,
            enableTransport: Boolean = false,
            shareInstance: Boolean = false,
            sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
            connectToSharedInstance: Boolean = false,
            transportIdentity: Identity? = null,
            respondToProbes: Boolean = false,
            useImplicitProof: Boolean = true,
            enableRemoteManagement: Boolean = false,
            remoteManagementAllowed: List<ByteArray> = emptyList(),
            panicOnInterfaceError: Boolean = false,
            blackholeSources: List<ByteArray> = emptyList(),
            interfaceDiscoverySources: List<ByteArray> = emptyList(),
            rpcKey: String? = null,
        ): Reticulum {
            // Validate + dedup the identity-hash lists BEFORE claiming the
            // singleton (pure, no side effects) so a malformed/wrong-length hash
            // aborts the start cleanly with a ValueError-equivalent, exactly as
            // python's __apply_config raises (Reticulum.py:532-536,:578-588).
            val rmAllowed = validateAndDedupHashes(remoteManagementAllowed, "remote management ACL")
            val bhSources = validateAndDedupHashes(blackholeSources, "blackhole source")
            val idSources = validateAndDedupHashes(interfaceDiscoverySources, "interface discovery source")
            if (started.compareAndSet(false, true)) {
                val dir = configDir ?: getDefaultConfigDir()
                val rns =
                    Reticulum(
                        configDir = dir,
                        enableTransport = enableTransport,
                        shareInstance = shareInstance,
                        sharedInstancePort = sharedInstancePort,
                        connectToSharedInstance = connectToSharedInstance,
                        transportIdentityOverride = transportIdentity,
                        respondToProbes = respondToProbes,
                        useImplicitProof = useImplicitProof,
                        enableRemoteManagement = enableRemoteManagement,
                        remoteManagementAllowedHashes = rmAllowed,
                        panicOnInterfaceError = panicOnInterfaceError,
                        blackholeSourceHashes = bhSources,
                        interfaceDiscoverySources = idSources,
                        rpcKeyHex = rpcKey,
                    )
                instance = rns

                // Apply pre-set factories before initialize
                pendingLocalClientFactory?.let { rns.localClientInterfaceFactory = it }
                pendingLocalServerFactory?.let { rns.localServerInterfaceFactory = it }
                pendingInterfaceRegistrar?.let { rns.interfaceRegistrar = it }

                try {
                    rns.initialize()
                } catch (t: Throwable) {
                    // Roll back the started/instance state so the caller can retry
                    // (e.g. with a different identity or after fixing a filesystem
                    // issue) without hitting the "already started" guard on the
                    // next start() call.
                    //
                    // Order matters: null `instance` *first*, then flip `started`
                    // false. The opposite order would let a racing start() win the
                    // CAS, build and assign its own rns to `instance`, and then
                    // this catch block would overwrite that with null — silently
                    // corrupting the racing caller's successful init. With
                    // `instance` marked @Volatile, a racing caller whose CAS sees
                    // started=true and falls through to `return instance!!` can
                    // still NPE during the narrow window between these two writes;
                    // that's an acceptable degradation (loud failure) versus the
                    // silent-corruption alternative.
                    instance = null
                    started.set(false)
                    throw t
                }
                return rns
            }
            if (transportIdentity != null) {
                // Callers pass transportIdentity precisely because they rely on the plaintext
                // private key never touching disk. Silently handing back an already-running
                // instance — which may have been started with the file-backed flow — would
                // break that guarantee without the caller ever noticing. Fail loudly instead.
                throw IllegalStateException(
                    "Reticulum is already started; cannot apply transportIdentity. " +
                        "Call Reticulum.stop() before restarting with a new identity.",
                )
            }
            return instance!!
        }

        /**
         * Check if a shared instance is already running on the given port.
         *
         * @param port TCP port to check
         * @param host Host to check (default: localhost)
         * @return true if a shared instance is responding on that port
         */
        fun isSharedInstanceRunning(
            port: Int = DEFAULT_SHARED_INSTANCE_PORT,
            host: String = "127.0.0.1",
        ): Boolean =
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1000)
                    true
                }
            } catch (e: Exception) {
                false
            }

        /**
         * Stop Reticulum and clean up resources.
         */
        fun stop() {
            if (started.compareAndSet(true, false)) {
                instance?.shutdown()
                instance = null
            }
        }

        /**
         * Clear any pending local-client / local-server / interface-registrar
         * factories previously installed via the `set*Factory` / `setInterfaceRegistrar`
         * setters. Intended for test harnesses (e.g. the conformance bridge) that
         * may re-enter `start()` with different topology assumptions across
         * back-to-back invocations and need to avoid carrying a stale lambda
         * (and any objects it captured) into the next session.
         *
         * Production callers (`rns-android.ReticulumService`, `rns-cli.PipePeer`)
         * re-set their factories before every `start()`, so this method is a
         * no-op for them — but calling it is harmless and idempotent.
         *
         * Does not touch the live `instance`'s factory state; only resets the
         * pre-start static slots used to seed the next `start()`. Call after
         * `stop()` (or wherever a clean factory baseline is desired).
         */
        fun clearPendingFactories() {
            pendingLocalClientFactory = null
            pendingLocalServerFactory = null
            pendingInterfaceRegistrar = null
        }

        /**
         * Get the default configuration directory.
         */
        private fun getDefaultConfigDir(): String {
            val userHome = System.getProperty("user.home")
            return "$userHome/.reticulum"
        }

        /**
         * Check if transport routing is enabled.
         */
        fun transportEnabled(): Boolean = instance?.enableTransport ?: false

        /**
         * Whether probe responses are enabled (python Reticulum.probe_destination_enabled,
         * default False). A connected local CLIENT always reports False regardless of
         * the configured knob (python Reticulum.py:431, the attach-time override).
         */
        fun probeDestinationEnabled(): Boolean =
            instance?.let { if (it.isConnectedToSharedInstance) false else it.respondToProbes } ?: false

        /**
         * Whether remote management is enabled (python Reticulum.remote_management_enabled,
         * default False). A connected local CLIENT always reports False (python
         * Reticulum.py:430, the attach-time override).
         */
        fun remoteManagementEnabled(): Boolean =
            instance?.let { if (it.isConnectedToSharedInstance) false else it.enableRemoteManagement } ?: false

        /**
         * Whether an interface error should panic the process (python
         * Reticulum.panic_on_interface_error, default False).
         */
        fun panicOnInterfaceError(): Boolean = instance?.panicOnInterfaceError ?: false

        /**
         * Trusted interface-discovery-source identity hashes (python
         * Reticulum.interface_discovery_sources()).
         */
        fun interfaceDiscoverySources(): List<ByteArray> = instance?.interfaceDiscoverySources ?: emptyList()

        /**
         * Trusted remote blackhole-source identity hashes (python
         * Reticulum.blackhole_sources()); reads the live Transport list.
         */
        fun blackholeSources(): List<ByteArray> = Transport.blackholeSources.toList()

        /**
         * Validate and deduplicate a list of identity-hash entries (mirrors
         * python Reticulum.py:532-536, :578-588, :582 for remote_management_allowed
         * / blackhole_sources / interface_discovery_sources): each entry must be
         * exactly TRUNCATED_HASHLENGTH//8 == 16 bytes, else the start aborts with a
         * ValueError-equivalent; duplicates collapse to a single entry. Pure (no
         * side effects) so it can run before the singleton is claimed.
         */
        private fun validateAndDedupHashes(hashes: List<ByteArray>, label: String): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for (h in hashes) {
                require(h.size == RnsConstants.TRUNCATED_HASH_BYTES) {
                    "Identity hash length for $label ${h.toHexString()} is invalid, must be " +
                        "${RnsConstants.TRUNCATED_HASH_BYTES} bytes."
                }
                if (out.none { it.contentEquals(h) }) out.add(h)
            }
            return out
        }

        /**
         * Check if link MTU discovery is enabled.
         */
        fun linkMtuDiscovery(): Boolean = LINK_MTU_DISCOVERY

        private fun log(message: String) {
            val timestamp =
                java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                )
            println("[$timestamp] [Reticulum] $message")
        }
    }

    // Storage paths
    val storagePath: String = "$configDir/storage"
    val cachePath: String = "$configDir/cache"

    // State
    private val interfaces = mutableListOf<Any>()
    private val shutdownHooks = mutableListOf<() -> Unit>()

    /**
     * Derived RPC control-channel authkey (python Reticulum.rpc_key). With no
     * configured rpc_key it is full_hash(transport_identity.private_key) ==
     * SHA-256(private key) (Reticulum.py:347-348); a valid hex rpc_key is used
     * verbatim and a malformed one falls back to the default
     * (Reticulum.py:489-495). Set during [initialize].
     */
    lateinit var rpcKey: ByteArray
        private set

    /** Whether this instance is the shared instance (has the local server running). */
    var isSharedInstance: Boolean = false
        private set

    /** Whether this instance is connected to a shared instance. */
    var isConnectedToSharedInstance: Boolean = false
        private set

    /** The shared instance interface (LocalServerInterface or LocalClientInterface). */
    private var sharedInterface: Any? = null

    /** Callback to create LocalServerInterface (set by rns-interfaces module). */
    var localServerInterfaceFactory: ((Int) -> Any)? = null

    /** Callback to create LocalClientInterface (set by rns-interfaces module). */
    var localClientInterfaceFactory: ((Int, String) -> Any)? = null

    /** Callback to adapt an interface and register it with Transport. */
    var interfaceRegistrar: ((Any) -> Unit)? = null

    /**
     * Initialize the Reticulum instance.
     */
    private fun initialize() {
        log("Initializing Reticulum...")

        // Seed the process-wide implicit-proof policy from this instance's
        // configured posture, mirroring python __init__ setting the class
        // attribute __use_implicit_proof (Reticulum.py:256). shouldUseImplicitProof()
        // reads the static policy thereafter (the conformance bridge may then flip
        // it at runtime via setUseImplicitProofForTest).
        seedImplicitProofPolicy(useImplicitProof)

        // Directory layout is lazily created at each write site (Transport, Identity,
        // InterfaceDiscovery, Destination all mkdirs parents on demand) and on Android
        // everything routes through Room stores anyway, so there's no reason to
        // eagerly create empty subdirectories that may never be written to.

        // Use the caller-provided identity if given (so the plaintext private key
        // never has to touch disk), otherwise fall back to the legacy file-backed flow.
        val transportIdentity =
            if (transportIdentityOverride != null) {
                // A caller on a device that previously ran the file-backed flow may still
                // have a plaintext $storagePath/transport_identity on disk. Since the whole
                // point of supplying an override is to keep plaintext keys off disk, remove
                // the stale file on behalf of the caller.
                deleteLegacyTransportIdentityFile()
                transportIdentityOverride
            } else {
                loadOrCreateTransportIdentity()
            }

        // Derive the RPC control-channel authkey. A valid hex rpc_key is used
        // verbatim; a malformed one falls back to the SHA-256(private-key)
        // default, exactly as python catches bytes.fromhex failure
        // (Reticulum.py:489-495) and defaults at :347-348. full_hash == SHA-256.
        rpcKey = rpcKeyHex
            ?.let { runCatching { it.hexToByteArray() }.getOrNull()?.takeIf { it.isNotEmpty() } }
            ?: Hashes.fullHash(transportIdentity.getPrivateKey())

        // Configure Transport and Identity storage paths
        Transport.setCachePath(cachePath)
        Transport.setStoragePath(storagePath)
        Identity.setStoragePath(storagePath)
        Identity.loadKnownDestinations()

        // Check if we should connect to an existing shared instance
        if (connectToSharedInstance) {
            if (tryConnectToSharedInstance(transportIdentity)) {
                applyConfigToTransport()
                log("Connected to shared instance on port $sharedInstancePort")
                return
            } else {
                log("No shared instance found, starting standalone")
            }
        }

        // Start Transport with identity
        Transport.start(transportIdentity = transportIdentity, enableTransport = enableTransport)

        // Publish the config-derived ACL / source lists onto the live Transport
        // (validated + deduped in start()). Mirrors python __apply_config
        // appending into RNS.Transport.remote_management_allowed /
        // Reticulum.__blackhole_sources / __interface_sources.
        applyConfigToTransport()

        // If shareInstance is enabled, start the local server
        if (shareInstance) {
            startLocalServer()
        }

        log("Reticulum started (transport=${if (enableTransport) "enabled" else "disabled"}, shared=$isSharedInstance)")
    }

    /**
     * Publish the config-derived ACL / source lists onto the live Transport.
     * The lists are already validated (16-byte) and deduplicated in [start];
     * this copies them in, mirroring python __apply_config appending into
     * RNS.Transport.remote_management_allowed / Reticulum.__blackhole_sources /
     * __interface_sources (Reticulum.py:528-541, :575-591).
     */
    private fun applyConfigToTransport() {
        for (h in remoteManagementAllowedHashes) {
            if (Transport.remoteManagementAllowed.none { it.contentEquals(h) }) {
                Transport.remoteManagementAllowed.add(h)
            }
        }
        for (h in blackholeSourceHashes) {
            if (Transport.blackholeSources.none { it.contentEquals(h) }) {
                Transport.blackholeSources.add(h)
            }
        }
        for (h in interfaceDiscoverySources) {
            if (Transport.interfaceDiscoverySources.none { it.contentEquals(h) }) {
                Transport.interfaceDiscoverySources.add(h)
            }
        }
    }

    /**
     * Try to connect to an existing shared instance.
     *
     * @return true if connected successfully
     */
    private fun tryConnectToSharedInstance(transportIdentity: Identity): Boolean {
        val factory = localClientInterfaceFactory
        if (factory == null) {
            log("LocalClientInterface factory not set, cannot connect to shared instance")
            return false
        }

        if (!isSharedInstanceRunning(sharedInstancePort)) {
            return false
        }

        try {
            val clientInterface = factory(sharedInstancePort, "127.0.0.1")

            // Start Transport (without transport routing) so inbound() works
            Transport.start(transportIdentity = transportIdentity, enableTransport = false)

            // Start the interface
            clientInterface::class.java.getMethod("start").invoke(clientInterface)

            // Register with Transport so packets flow through
            val registrar = interfaceRegistrar
            if (registrar != null) {
                registrar(clientInterface)
            } else {
                log("WARNING: No interface registrar set, packets will not be processed")
            }

            // Set state only after all steps succeed (matches Python Reticulum.py:414-416)
            sharedInterface = clientInterface
            isConnectedToSharedInstance = true
            Transport.isConnectedToSharedInstance = true

            log("Connected to shared instance")
            return true
        } catch (e: Exception) {
            log("Failed to connect to shared instance: ${e.message}")
            isConnectedToSharedInstance = false
            Transport.isConnectedToSharedInstance = false
            return false
        }
    }

    /**
     * Start the local server interface for sharing this instance.
     */
    private fun startLocalServer() {
        val factory = localServerInterfaceFactory
        if (factory == null) {
            log("LocalServerInterface factory not set, cannot share instance")
            return
        }

        try {
            val serverInterface = factory(sharedInstancePort)
            sharedInterface = serverInterface
            isSharedInstance = true

            // Start the interface
            serverInterface::class.java.getMethod("start").invoke(serverInterface)

            // Register with Transport
            val registrar = interfaceRegistrar
            if (registrar != null) {
                registrar(serverInterface)
            } else {
                log("WARNING: No interface registrar set for server interface")
            }

            log("Started shared instance server on port $sharedInstancePort")
        } catch (e: Exception) {
            log("Failed to start shared instance server: ${e.message}")
        }
    }

    /**
     * Load transport identity from file or create a new one.
     * The identity is persisted to ensure consistent RPC keys across restarts.
     */
    private fun loadOrCreateTransportIdentity(): Identity {
        val identityFile = File("$storagePath/transport_identity")

        return if (identityFile.exists()) {
            val identity = Identity.fromFile(identityFile.absolutePath)
            if (identity != null) {
                log("Loaded transport identity")
                identity
            } else {
                log("Failed to load transport identity, creating new")
                createAndSaveTransportIdentity(identityFile)
            }
        } else {
            log("No transport identity found, creating new")
            createAndSaveTransportIdentity(identityFile)
        }
    }

    /**
     * Create a new transport identity and save it to file.
     */
    private fun createAndSaveTransportIdentity(file: File): Identity {
        val identity = Identity.create()
        identity.toFile(file.absolutePath)
        return identity
    }

    /**
     * Best-effort removal of `$storagePath/transport_identity` when a caller
     * supplies an in-memory transport identity override. Performs a zero-fill
     * overwrite before [File.delete] to reduce plaintext remnants in the
     * directory entry's previously-allocated blocks.
     *
     * Caveat: neither the overwrite nor the delete is a true secure erase on
     * wear-levelled (F2FS, eMMC controller-level) or journalled (ext4 data=
     * journal) storage. Copy-on-write and the FS journal may retain
     * previous block contents for an unbounded time before they're reused.
     * This is the best Android userspace can offer without vendor APIs for
     * secure discard; callers who need stronger guarantees must rely on
     * full-disk encryption being active on the device.
     *
     * Throws [IllegalStateException] if the file exists but cannot be
     * deleted. Callers using the override are relying on a security
     * guarantee that a leftover plaintext key actively breaks; a
     * warning-and-continue in that case would leave a forensic artifact
     * with the caller having no programmatic way to detect it.
     */
    private fun deleteLegacyTransportIdentityFile() {
        val identityFile = File("$storagePath/transport_identity")
        if (identityFile.exists()) {
            // Best-effort overwrite. Failure here is non-fatal — the delete
            // below still runs and is what the invariant actually depends on.
            try {
                val zeros = ByteArray(identityFile.length().toInt())
                identityFile.writeBytes(zeros)
            } catch (_: Exception) {
                // Overwrite is best-effort; fall through to delete.
            }
            if (!identityFile.delete()) {
                throw IllegalStateException(
                    "In-memory transport identity override requested but failed to delete " +
                        "legacy plaintext key file at ${identityFile.absolutePath}. " +
                        "Refusing to start to avoid a false sense of security.",
                )
            }
            log("Deleted legacy plaintext transport_identity file (in-memory override active)")
        }
    }

    /**
     * Add an interface to Reticulum.
     */
    fun addInterface(iface: Any) {
        interfaces.add(iface)
        // Interface will be registered with Transport separately
    }

    /**
     * Remove an interface from Reticulum.
     */
    fun removeInterface(iface: Any) {
        interfaces.remove(iface)
    }

    /**
     * Get all registered interfaces.
     */
    fun getInterfaces(): List<Any> = interfaces.toList()

    /**
     * Get the path table as a map of destination hash (hex) to hop count.
     */
    fun getPathTable(): Map<String, Int> =
        Transport.pathTable
            .mapKeys { it.key.toString() }
            .mapValues { it.value.hops }

    /**
     * Register a destination with Transport.
     */
    fun registerDestination(destination: Destination) {
        Transport.registerDestination(destination)
    }

    /**
     * Register a shutdown hook to be called when Reticulum stops.
     */
    fun addShutdownHook(hook: () -> Unit) {
        shutdownHooks.add(hook)
    }

    /**
     * Get the first hop timeout for a destination.
     */
    fun getFirstHopTimeout(destinationHash: ByteArray): Long {
        val hops = Transport.hopsTo(destinationHash) ?: 1
        return DEFAULT_PER_HOP_TIMEOUT * 1000L * maxOf(1, hops)
    }

    /**
     * Shutdown Reticulum.
     */
    private fun shutdown() {
        log("Shutting down Reticulum...")

        // Persist known destinations before shutdown so identities survive restart
        try {
            Identity.saveKnownDestinations()
        } catch (e: Exception) {
            log("Error saving known destinations: ${e.message}")
        }

        // Run shutdown hooks
        shutdownHooks.forEach { hook ->
            try {
                hook()
            } catch (e: Exception) {
                log("Error in shutdown hook: ${e.message}")
            }
        }
        shutdownHooks.clear()

        // Detach shared interface
        sharedInterface?.let { iface ->
            try {
                iface::class.java.getMethod("detach").invoke(iface)
            } catch (e: Exception) {
                log("Error detaching shared interface: ${e.message}")
            }
        }
        sharedInterface = null
        isSharedInstance = false
        isConnectedToSharedInstance = false
        Transport.isConnectedToSharedInstance = false

        // Detach interfaces
        interfaces.clear()

        // Stop Transport (only if we're not just a client)
        if (!connectToSharedInstance || !isConnectedToSharedInstance) {
            Transport.stop()
        }

        log("Reticulum stopped")
    }

    /**
     * Get the number of clients connected to this shared instance.
     *
     * @return number of connected clients, or 0 if not a shared instance
     */
    fun getSharedInstanceClientCount(): Int {
        val server = sharedInterface ?: return 0
        return try {
            server::class.java.getMethod("clientCount").invoke(server) as Int
        } catch (e: Exception) {
            0
        }
    }
}
