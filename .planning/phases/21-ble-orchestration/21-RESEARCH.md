# Phase 21: BLEInterface Orchestration - Research

**Researched:** 2026-02-06
**Domain:** BLE mesh orchestration, coroutine-based dual-role BLE, Interface lifecycle management
**Confidence:** HIGH (codebase-driven, existing patterns well-established)

## Summary

Phase 21 wires together the BLE components from Phases 18-20 (BLEDriver, AndroidBLEDriver, BleGattServer, BleGattClient, BleScanner, BleAdvertiser) into a working mesh through two new classes: `BLEInterface` (server-style parent, pure JVM, lives in `rns-interfaces`) and `BLEPeerInterface` (per-peer child, also pure JVM). The orchestration layer handles discovery, MAC-sorting-based connection direction, identity handshake, keepalive, and Transport registration.

The standard approach follows the existing `LocalServerInterface` / `LocalClientInterface` pattern exactly: parent interface spawns children, children are registered with Transport via `toRef()`, parent's `processOutgoing()` is a no-op, and children deregister on disconnect. BLE-specific concerns (identity handshake gating, MAC sorting, dual-role coordination, reconnection backoff) are layered on top of this established pattern.

The key technical challenge is that Android cannot reliably provide the local BLE MAC address (returns `02:00:00:00:00:00` on most devices), which means MAC-based connection direction sorting must use the identity hash as a fallback. The identity hash is always available (it is the 16-byte truncated hash of Transport's public key) and is the primary peer identifier in the Reticulum BLE protocol anyway.

**Primary recommendation:** Build BLEInterface in `rns-interfaces/ble/` as a pure-JVM class that depends only on the `BLEDriver` interface. Use identity hash comparison (not MAC) as the primary connection direction tiebreaker, falling back to MAC only when both are available. Follow the LocalServerInterface spawn/register/deregister pattern exactly.

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.7+ | Async orchestration, SharedFlow collection, timeouts | Already used throughout codebase |
| Network.reticulum.interfaces.Interface | n/a (codebase) | Base class for BLEInterface and BLEPeerInterface | Established pattern for all interface types |
| Network.reticulum.interfaces.ble.BLEDriver | n/a (codebase) | Platform abstraction for BLE operations | Phase 18 contract, AndroidBLEDriver implements it |
| Network.reticulum.transport.Transport | n/a (codebase) | Interface registration, packet routing | Standard integration point |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| java.util.concurrent.CopyOnWriteArrayList | JVM stdlib | Thread-safe peer list | Same as LocalServerInterface uses for clients |
| java.util.concurrent.ConcurrentHashMap | JVM stdlib | Identity-to-peer mapping | O(1) lookup for identity dedup |
| kotlinx.coroutines.sync.Mutex | 1.7+ | Peer state mutation | Coroutine-safe locking for peer map |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| CopyOnWriteArrayList | Mutex + MutableList | COWAL is simpler for read-heavy, small lists; Mutex better for write-heavy |
| SupervisorJob | Regular Job | SupervisorJob prevents one peer's failure from cascading; matches existing pattern |
| SharedFlow for events | Channel | SharedFlow allows multiple collectors (logging + orchestration); Channel is 1:1 |

## Architecture Patterns

### Recommended Project Structure

```
rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/
├── BLEConstants.kt          # (exists) Protocol constants
├── BLEDriver.kt             # (exists) Platform abstraction interface
├── BLEPeerConnection.kt     # (exists, in BLEDriver.kt) Per-connection abstraction
├── BLEFragmentation.kt      # (exists) Fragment/reassemble
├── BleOperationQueue.kt     # (exists) GATT operation serializer
├── DiscoveredPeer.kt        # (exists) Peer scoring model
├── BLEInterface.kt          # NEW: Server-style parent, orchestration
└── BLEPeerInterface.kt      # NEW: Per-peer child, Transport-registered

rns-android/src/main/kotlin/network/reticulum/android/ble/
├── AndroidBLEDriver.kt      # (exists) Concrete BLEDriver implementation
├── BleGattServer.kt         # (exists) GATT server
├── BleGattClient.kt         # (exists) GATT client
├── BleScanner.kt            # (exists) BLE scanner
└── BleAdvertiser.kt         # (exists) BLE advertiser

rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/
└── InterfaceManager.kt      # (exists) Add BLE case to startInterface()
```

### Pattern 1: Server-Style Parent Interface (LocalServerInterface)

**What:** BLEInterface follows the exact same pattern as LocalServerInterface: it spawns child interfaces per peer, registers them with Transport, and its own `processOutgoing()` is a no-op.

**When to use:** Always. This is the established pattern for multi-peer parent interfaces.

**Example (from existing LocalServerInterface):**

```kotlin
// Source: rns-interfaces/.../local/LocalServerInterface.kt lines 325-348
private fun handleNewClient(socket: Socket) {
    val clientInterface = LocalClientInterface(
        name = clientName,
        connectedSocket = socket,
        parentServer = this
    )
    clients.add(clientInterface)
    spawnedInterfaces?.add(clientInterface)

    clientInterface.onPacketReceived = { data, iface ->
        onPacketReceived?.invoke(data, iface)
    }
    clientInterface.start()

    try {
        Transport.registerInterface(clientInterface.toRef())
    } catch (e: Exception) {
        log("Could not register spawned interface with Transport: ${e.message}")
    }
}

// Parent processOutgoing is a no-op
override fun processOutgoing(data: ByteArray) {
    // No-op: Transport calls each spawned child directly
}
```

### Pattern 2: Per-Peer Child Interface (LocalClientInterface / TCPServerClientInterface)

**What:** BLEPeerInterface extends Interface, handles send/receive for a single peer, and notifies the parent on disconnect for Transport deregistration.

**When to use:** One per connected BLE peer.

**Example (from existing TCPServerClientInterface):**

```kotlin
// Source: rns-interfaces/.../tcp/TCPServerInterface.kt lines 212-342
class TCPServerClientInterface internal constructor(
    name: String,
    private val socket: Socket,
    private val parentServer: TCPServerInterface,
) : Interface(name) {
    init {
        parentInterface = parentServer  // Link to parent
    }

    override fun processOutgoing(data: ByteArray) {
        // Frame and send data to this specific peer
        val framedData = HDLC.frame(data)
        socket.getOutputStream().write(framedData)
        txBytes.addAndGet(framedData.size.toLong())
        parentInterface?.txBytes?.addAndGet(framedData.size.toLong())
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)
        readJob?.cancel()
        socket.close()
        parentServer.clientDisconnected(this)  // Notify parent
    }
}
```

### Pattern 3: InterfaceAdapter / toRef() for Transport Registration

**What:** Every Interface must be wrapped in InterfaceRef via `toRef()` before registration with Transport. The adapter caches instances and sets up the `onPacketReceived` callback to route to `Transport.inbound()`.

**When to use:** Every time a BLEPeerInterface is created or destroyed.

**Critical detail:** InterfaceAdapter only sets `onPacketReceived` if it is null. Set callbacks BEFORE calling `toRef()`.

```kotlin
// Source: rns-interfaces/.../InterfaceAdapter.kt
init {
    // Only set up the callback if one isn't already set
    if (iface.onPacketReceived == null) {
        iface.onPacketReceived = { data, fromInterface ->
            val sourceRef = if (fromInterface === iface) this
                           else fromInterface.toRef()
            Transport.inbound(data, sourceRef)
        }
    }
}
```

### Pattern 4: Coroutine-Based Event Processing

**What:** BLEInterface collects from BLEDriver's SharedFlows (discoveredPeers, incomingConnections, connectionLost) in a CoroutineScope, processing events sequentially within each flow.

**When to use:** Main orchestration loop.

```kotlin
// BLEInterface lifecycle pseudo-pattern:
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

override fun start() {
    online.set(true)
    scope.launch { collectDiscoveredPeers() }
    scope.launch { collectIncomingConnections() }
    scope.launch { collectConnectionLost() }
    scope.launch { keepaliveLoop() }
}
```

### Anti-Patterns to Avoid

- **Don't register BLEInterface itself with Transport.** Only its spawned BLEPeerInterface children should be registered. The parent is a coordinator, not a data path.
- **Don't use runBlocking in BLE callbacks.** Everything is already suspend/coroutine-based. The AndroidBLEDriver uses `runBlocking` in one place (MTU getter) which is a known compromise, not a pattern to follow.
- **Don't block the main coroutine scope with long operations.** Identity handshake has a 30-second timeout; always use `withTimeout` inside a launched coroutine, not on the main collection loop.
- **Don't rely on MAC addresses for peer identity.** Android rotates BLE MAC addresses every ~15 minutes. Always key on identity hash after handshake.
- **Don't send data before identity handshake completes.** BLEPeerInterface should buffer or reject `processOutgoing()` until handshake is done.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| GATT operation serialization | Custom queue | BleOperationQueue (exists) | Already handles timeout, cancellation, FIFO ordering |
| Packet fragmentation | Custom splitter | BLEFragmenter / BLEReassembler (exists) | Handles fragment types, sequence numbers, reassembly timeout |
| Peer scoring | Simple RSSI check | DiscoveredPeer.connectionScore() (exists) | Weighted RSSI (60%) + history (30%) + recency (10%) |
| Interface-to-Transport bridging | Direct Transport calls | InterfaceAdapter / toRef() (exists) | Handles caching, callback wiring, InterfaceRef contract |
| Reconnection backoff | Hardcoded delays | ExponentialBackoff (exists) | Configurable initial/max/multiplier/attempts |
| Advertising restart after connection | Manual restart | AndroidBLEDriver.restartAdvertisingIfNeeded() (exists) | Handles OEM chipset quirks |

**Key insight:** Almost all BLE infrastructure already exists from Phases 18-20. Phase 21 is pure orchestration logic -- connecting the dots, not building new primitives.

## Common Pitfalls

### Pitfall 1: Android Cannot Provide Local MAC Address

**What goes wrong:** `BluetoothAdapter.getAddress()` returns `02:00:00:00:00:00` on Android 6+ unless the app has the `LOCAL_MAC_ADDRESS` system permission (system apps only). This breaks MAC-based connection direction sorting.

**Why it happens:** Android privacy protections prevent apps from reading the real BLE MAC address. The address is randomized every ~15 minutes.

**How to avoid:** Use identity hash (16 bytes, always available from Transport) for connection direction sorting instead of MAC address. Both devices know their own identity hash before connecting. The identity hash is stable across MAC rotations. Compare identity hashes lexicographically: lower identity hash initiates as central.

**Warning signs:** `localAddress` in BLEDriver returns null on most Android devices.

**Confidence:** HIGH -- verified from AndroidBLEDriver.kt (lines 80-86) which wraps `bluetoothAdapter.address` in try/catch returning null.

### Pitfall 2: Race Condition -- Both Sides Connect Simultaneously

**What goes wrong:** If MAC/identity sorting doesn't prevent it (e.g., scan timing), both devices may simultaneously attempt to connect as central, creating two connections to the same peer.

**Why it happens:** Scan results arrive at different times on different devices. A device may connect as central before realizing the other device should have been the initiator.

**How to avoid:** Per the CONTEXT.md decision: accept both connections, then deduplicate by identity after the handshake reveals the same peer. Keep the newest connection, tear down the oldest. The identity hash comparison ensures both sides agree on which connection to keep.

**Warning signs:** Two BLEPeerInterface instances with the same identity hash.

**Confidence:** HIGH -- this is a locked decision from CONTEXT.md.

### Pitfall 3: Advertising Stops After Connection on Some Chipsets

**What goes wrong:** Some Android BLE chipsets silently stop advertising when a GATT connection is established. New peers can no longer discover this device.

**Why it happens:** OEM BLE stack implementation varies. The BLE spec allows a peripheral to continue advertising while connected, but not all implementations support it.

**How to avoid:** Call `AndroidBLEDriver.restartAdvertisingIfNeeded()` after each connection event (both incoming and outgoing). BleAdvertiser already implements periodic 60-second refresh as an additional safety net.

**Warning signs:** Device becomes undiscoverable after first connection.

**Confidence:** HIGH -- BleAdvertiser already has `restartIfNeeded()` for this, documented in Phase 19 context.

### Pitfall 4: Identity Handshake Timeout Creates Orphan Connections

**What goes wrong:** A connection completes at the GATT level but the 30-second identity handshake never finishes. The BLEPeerInterface is half-initialized, consuming a connection slot.

**Why it happens:** Remote device crashes, goes out of range, or has a bug in its identity exchange implementation.

**How to avoid:** Use `withTimeout(IDENTITY_HANDSHAKE_TIMEOUT_MS)` wrapping the entire handshake sequence. On timeout, disconnect the GATT connection and add a 60-second temporary blacklist entry. Do NOT create the BLEPeerInterface until the handshake succeeds.

**Warning signs:** Connection count at max but no peers completing handshake.

**Confidence:** HIGH -- timeout value and blacklist behavior are locked decisions from CONTEXT.md.

### Pitfall 5: InterfaceAdapter Callback Overwrite

**What goes wrong:** If `toRef()` is called before setting `onPacketReceived` on a BLEPeerInterface, the InterfaceAdapter constructor sets a default callback. Later attempts to set a custom callback may be ignored because the adapter caches the instance.

**Why it happens:** InterfaceAdapter only sets `onPacketReceived` if it is null (line 50-51 in InterfaceAdapter.kt).

**How to avoid:** Set `onPacketReceived` on BLEPeerInterface BEFORE calling `toRef()`. Or, rely on the default InterfaceAdapter callback which routes to `Transport.inbound()` -- this is actually the desired behavior for spawned interfaces.

**Warning signs:** Packets received on BLE peer but never reach Transport.

**Confidence:** HIGH -- verified from InterfaceAdapter.kt source code.

### Pitfall 6: Missing BLUETOOTH_ADVERTISE Permission in Sample App Manifest

**What goes wrong:** The rns-sample-app AndroidManifest.xml does not declare `BLUETOOTH_ADVERTISE`. When InterfaceManager creates a BLEInterface and the driver tries to advertise, it fails with SecurityException on Android 12+.

**Why it happens:** The rns-android module's manifest has the permission, but if manifest merger doesn't work as expected or if the sample app overrides permissions, the permission may be missing.

**How to avoid:** Verify that rns-sample-app's AndroidManifest.xml includes `BLUETOOTH_ADVERTISE` permission. The rns-android module declares it, and Android's manifest merger should merge it in. But the sample app should also request it at runtime (APP-03).

**Warning signs:** BLE advertising silently fails on API 31+.

**Confidence:** MEDIUM -- manifest merger should handle this, but runtime permission request is essential regardless.

### Pitfall 7: Keepalive Missed Due to Suspended Coroutine

**What goes wrong:** If the keepalive coroutine is suspended (e.g., waiting on a Mutex or during a long send operation), the 15-second keepalive interval may be missed, causing the supervision timeout to disconnect the peer.

**Why it happens:** BLE supervision timeout is ~20 seconds on most Android devices (5 seconds on Android 10+). If keepalive misses one interval, the connection may drop.

**How to avoid:** Run the keepalive loop in its own dedicated coroutine per peer, not blocked by send operations. Use a separate lightweight coroutine that only sends the single 0x00 byte. Consider sending keepalive via the BLEPeerConnection directly rather than through the BLEPeerInterface's processOutgoing path.

**Warning signs:** Peers disconnect after ~20 seconds of inactivity despite keepalive being "enabled."

**Confidence:** MEDIUM -- depends on Android device BLE supervision timeout configuration.

## Code Examples

### BLEInterface Skeleton (Pure JVM)

```kotlin
// Source: Pattern derived from LocalServerInterface.kt + BLEDriver.kt
class BLEInterface(
    name: String,
    private val driver: BLEDriver,
    private val transportIdentity: ByteArray, // 16-byte identity hash
    private val maxConnections: Int = BLEConstants.MAX_CONNECTIONS,
) : Interface(name) {

    override val bitrate: Int = 40_000 // ~40 kbps BLE practical throughput
    override val canReceive: Boolean = true
    override val canSend: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peers = ConcurrentHashMap<String, BLEPeerInterface>() // identity hex -> peer
    private val addressToIdentity = ConcurrentHashMap<String, String>() // MAC -> identity hex

    init {
        spawnedInterfaces = mutableListOf()
    }

    override fun start() {
        online.set(true)
        scope.launch { driver.startAdvertising() }
        scope.launch { delay(100); driver.startScanning() }
        scope.launch { collectDiscoveredPeers() }
        scope.launch { collectIncomingConnections() }
        scope.launch { collectDisconnections() }
    }

    // Parent processOutgoing is no-op -- Transport calls children directly
    override fun processOutgoing(data: ByteArray) { }

    override fun detach() {
        super.detach()
        scope.cancel()
        peers.values.forEach { it.detach() }
        peers.clear()
        driver.shutdown()
    }
}
```

### Identity-Based Connection Direction Sorting

```kotlin
// Source: Derived from CONTEXT.md decisions + Android MAC address limitations
private fun shouldInitiateConnection(peerIdentity: ByteArray): Boolean {
    // Compare identity hashes lexicographically
    // Lower identity hash acts as central (initiator)
    for (i in transportIdentity.indices) {
        val local = transportIdentity[i].toInt() and 0xFF
        val remote = peerIdentity[i].toInt() and 0xFF
        if (local < remote) return true   // We are lower -> we initiate
        if (local > remote) return false  // They are lower -> they initiate
    }
    // Equal identity hashes (astronomically unlikely)
    // Break tie: don't connect (both sides will wait, which is fine)
    return false
}
```

### Identity Handshake (Central Side)

```kotlin
// Source: Derived from BLEDriver/BLEPeerConnection API + CONTEXT.md
private suspend fun performHandshakeAsCentral(
    conn: BLEPeerConnection
): ByteArray {
    return withTimeout(BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS) {
        // Step 1: Read peripheral's identity
        val peerIdentity = conn.readIdentity()
        require(peerIdentity.size == BLEConstants.IDENTITY_SIZE) {
            "Invalid identity size: ${peerIdentity.size}"
        }

        // Step 2: Write our identity
        conn.writeIdentity(transportIdentity)

        peerIdentity
    }
}
```

### Identity Handshake (Peripheral Side)

```kotlin
// Source: Derived from BLEPeerConnection.receivedFragments + CONTEXT.md
private suspend fun performHandshakeAsPeripheral(
    conn: BLEPeerConnection
): ByteArray {
    return withTimeout(BLEConstants.IDENTITY_HANDSHAKE_TIMEOUT_MS) {
        // Peripheral waits for central to write its identity to RX
        // The first 16-byte write on RX is the identity
        val peerIdentity = conn.receivedFragments.first { fragment ->
            fragment.size == BLEConstants.IDENTITY_SIZE
        }
        peerIdentity
    }
}
```

### BLEPeerInterface Skeleton

```kotlin
// Source: Pattern derived from TCPServerClientInterface + BLEFragmentation
class BLEPeerInterface(
    name: String,
    private val connection: BLEPeerConnection,
    private val parentInterface: BLEInterface,
    private val peerIdentity: ByteArray,
) : Interface(name) {

    override val bitrate: Int = 40_000
    private val fragmenter = BLEFragmenter(connection.mtu)
    private val reassembler = BLEReassembler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        this.parentInterface = parentInterface
    }

    fun startReceiving() {
        online.set(true)
        scope.launch { receiveLoop() }
        scope.launch { keepaliveLoop() }
    }

    private suspend fun receiveLoop() {
        connection.receivedFragments.collect { fragment ->
            if (fragment.size == 1 && fragment[0] == BLEConstants.KEEPALIVE_BYTE) {
                // Keepalive -- update last-seen timestamp
                return@collect
            }
            val packet = reassembler.receiveFragment(fragment, connection.address)
            if (packet != null) {
                processIncoming(packet) // Delivers to Transport via onPacketReceived
            }
        }
    }

    override fun processOutgoing(data: ByteArray) {
        val fragments = fragmenter.fragment(data)
        // Send fragments -- must be blocking for Transport compatibility
        runBlocking {
            for (frag in fragments) {
                connection.sendFragment(frag)
            }
        }
        txBytes.addAndGet(data.size.toLong())
        parentInterface.txBytes.addAndGet(data.size.toLong())
    }

    private suspend fun keepaliveLoop() {
        while (online.get() && !detached.get()) {
            delay(BLEConstants.KEEPALIVE_INTERVAL_MS)
            try {
                connection.sendFragment(byteArrayOf(BLEConstants.KEEPALIVE_BYTE))
            } catch (e: Exception) {
                log("Keepalive failed: ${e.message}")
                detach()
            }
        }
    }

    override fun detach() {
        if (detached.getAndSet(true)) return
        online.set(false)
        scope.cancel()
        connection.close()
        parentInterface.peerDisconnected(this)
    }
}
```

### InterfaceManager BLE Integration

```kotlin
// Source: Derived from existing InterfaceManager.kt BLE case (lines 283-288)
InterfaceType.BLE -> {
    scope.launch(Dispatchers.IO) {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
            val driver = AndroidBLEDriver(context, bluetoothManager, scope)

            // Set transport identity on the GATT server
            val identityHash = Transport.identity?.hash
                ?: throw IllegalStateException("Transport identity not available")
            driver.setTransportIdentity(identityHash)

            val iface = BLEInterface(
                name = config.name,
                driver = driver,
                transportIdentity = identityHash,
            )
            iface.start()

            // Register the parent interface (it won't route packets, but
            // Transport needs to know about it for lifecycle management)
            Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))
            runningInterfaces[config.id] = iface

            Log.i(TAG, "Started BLE interface: ${config.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE interface ${config.name}: ${e.message}", e)
        }
    }
    null // Async registration
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MAC address for connection direction | Identity hash comparison | Android 6+ (2015) | MAC-based sorting unreliable on Android; identity hash is protocol-native |
| `BluetoothAdapter.getAddress()` | Returns `02:00:00:00:00:00` | Android 6+ | Must use alternative identifiers (identity hash) |
| Fixed supervision timeout (20s) | Reduced to 5s on some devices | Android 10 (2019) | Keepalive interval of 15s may be too long on some devices |
| Single-role BLE (central OR peripheral) | Dual-role (both simultaneously) | Android 5.0+ | Fully supported; connection count shared across roles |
| Manual GATT operation serialization | BleOperationQueue | Phase 19 | Already implemented, serializes all GATT operations |
| Android 14 auto-MTU (517) | Automatic on API 34+ | Android 14 (2023) | No MTU negotiation needed on modern devices |

**Deprecated/outdated:**
- `BluetoothGattCharacteristic.setValue()` -- deprecated API 33; use new method signatures with data parameter
- Legacy BLE permissions (`BLUETOOTH`, `BLUETOOTH_ADMIN`) -- replaced by granular permissions on API 31+

## Open Questions

1. **Android local MAC address availability**
   - What we know: `BluetoothAdapter.getAddress()` returns dummy address on most Android 6+ devices. BLEDriver.localAddress returns null.
   - What's unclear: Whether any reliable method exists to get the real MAC on non-rooted devices.
   - Recommendation: Use identity hash for connection direction sorting. MAC address is not needed if both devices have identity hashes. The scan result provides the remote device's MAC (possibly randomized), but we don't need our own MAC for the sorting decision.

2. **Supervision timeout on Android 10+ devices**
   - What we know: Some sources suggest Android 10 reduced supervision timeout from 20s to 5s. The keepalive interval is set at 15s.
   - What's unclear: Whether this applies to all Android 10+ devices or only specific chipsets.
   - Recommendation: Keep 15-second keepalive interval as specified. If disconnections are observed in testing, reduce to 4 seconds. The 15s interval matches the Python reference implementation and is well within the 20s timeout on most devices.

3. **Practical connection limits under dual-role operation**
   - What we know: Android BLE stack typically supports up to 7-8 total connections across all roles. The hard cap is set at 7 in BLEConstants.
   - What's unclear: Whether advertising + scanning + server + client connections all count against the same limit, and whether some devices have lower limits.
   - Recommendation: Start with max 5 connections (conservative), configurable up to 7. Monitor connection stability in testing. The connection limit is shared across all BLE roles on the device.

4. **processOutgoing blocking vs suspend**
   - What we know: Interface.processOutgoing() is a synchronous abstract method. BLE send is inherently async (suspend function on BLEPeerConnection.sendFragment).
   - What's unclear: Whether using `runBlocking` in processOutgoing is acceptable or if it will cause deadlocks with the coroutine-based BLE stack.
   - Recommendation: Use `runBlocking(Dispatchers.IO)` inside processOutgoing, matching the approach used in AndroidBLEDriver's MTU getter. The IO dispatcher prevents deadlock with the Default dispatcher used by AndroidBLEDriver. If issues arise, consider making processOutgoing enqueue to a Channel and send from a dedicated coroutine.

5. **Parent BLEInterface Transport registration**
   - What we know: LocalServerInterface registers its children with Transport but the parent itself is also known to Transport (it's in the interfaces list). TCPServerInterface does NOT explicitly register itself -- only children.
   - What's unclear: Whether BLEInterface (parent) should be registered with Transport. LocalServerInterface is, TCPServerInterface is not.
   - Recommendation: Follow the InterfaceManager pattern: register the parent to track lifecycle, but parent's processOutgoing is no-op so Transport won't route through it. The InterfaceManager needs a reference to the parent for stop/start lifecycle.

## Sources

### Primary (HIGH confidence)
- Codebase: `rns-interfaces/.../local/LocalServerInterface.kt` -- spawn/register/deregister pattern
- Codebase: `rns-interfaces/.../local/LocalClientInterface.kt` -- child interface lifecycle
- Codebase: `rns-interfaces/.../tcp/TCPServerInterface.kt` -- alternative spawn pattern
- Codebase: `rns-interfaces/.../InterfaceAdapter.kt` -- toRef() callback wiring
- Codebase: `rns-interfaces/.../Interface.kt` -- base class contract
- Codebase: `rns-interfaces/.../ble/BLEDriver.kt` -- platform abstraction interface
- Codebase: `rns-interfaces/.../ble/BLEConstants.kt` -- protocol constants
- Codebase: `rns-interfaces/.../ble/BLEFragmentation.kt` -- fragment/reassemble
- Codebase: `rns-interfaces/.../ble/DiscoveredPeer.kt` -- peer scoring
- Codebase: `rns-android/.../ble/AndroidBLEDriver.kt` -- concrete BLE implementation
- Codebase: `rns-sample-app/.../InterfaceManager.kt` -- interface lifecycle management
- Codebase: `rns-core/.../transport/Transport.kt` -- identity, registerInterface, deregisterInterface
- Python: `RNS/Interfaces/LocalInterface.py` -- Python spawn/register pattern (lines 408-452)

### Secondary (MEDIUM confidence)
- Android docs: BLE dual-role support confirmed via source.android.com
- Web research: Android BLE connection limits (7-8 practical maximum)
- Web research: Advertising stops on connection (OEM-specific, requires restart)

### Tertiary (LOW confidence)
- Web research: Android 10 supervision timeout reduction (5s vs 20s) -- conflicting sources
- Web research: Practical dual-role connection limits vary by device

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries are already in the codebase, no new dependencies
- Architecture: HIGH -- directly follows established LocalServerInterface/LocalClientInterface pattern with all code visible
- Pitfalls: HIGH for pitfalls 1-5 (verified from code), MEDIUM for pitfalls 6-7 (platform-dependent)

**Research date:** 2026-02-06
**Valid until:** 2026-03-08 (30 days -- stable domain, no external library changes expected)
