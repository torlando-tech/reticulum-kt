# Architecture Research: BLE Interface Port to Kotlin

**Domain:** BLE mesh networking interface for Reticulum-KT
**Researched:** 2026-02-06
**Overall confidence:** HIGH (based on direct codebase analysis of Kotlin codebase, Python BLE reference, and Columba prior art)

---

## 1. Module Boundary Problem

### The Core Tension

The BLE Interface needs two fundamentally different kinds of code:

1. **Protocol logic** -- fragmentation, reassembly, peer scoring, handshake state machines, connection management. This is pure computation, highly testable, has no platform dependencies.

2. **Android BLE driver** -- `BluetoothGatt`, `BluetoothLeScanner`, `BluetoothLeAdvertiser`, `BluetoothGattServer`, GATT callbacks, permissions. This requires `android.bluetooth.*` APIs and a `Context`.

The existing module structure enforces this split:
- `rns-interfaces` uses `kotlin("jvm")` -- no Android dependencies allowed
- `rns-sample-app` uses `kotlin("android")` -- has `Context`, BLE APIs
- `rns-android` uses `com.android.library` -- has `Context`, but is for service/lifecycle code

### How the Python Reference Splits It

The Python BLE implementation (`~/repos/ble/src/RNS/Interfaces/`) uses three files:

| File | Lines | Responsibility |
|------|-------|---------------|
| `BLEInterface.py` | ~1230 | Main interface class, discovery loop, peer management, spawned interfaces |
| `BLEFragmentation.py` | ~427 | Fragmenter + Reassembler (pure logic) |
| `BLEGATTServer.py` | ~535 | GATT server (peripheral mode) using `bless` library |

Python does NOT cleanly separate protocol from driver. `BLEInterface.py` directly imports `bleak` (the BLE client library) and mixes discovery logic, connection management, and `BleakClient` calls in the same class. The GATT server is separate only because it uses a different library (`bless` vs `bleak`).

This is fine for Python (dynamic imports, conditional `HAS_BLEAK` checks), but is a non-option for Kotlin's module dependency graph.

### How the Existing RNode Pattern Works

The existing RNode interface in this codebase already solved a similar problem:

```
rns-interfaces/rnode/RNodeInterface.kt     -- pure JVM, takes InputStream/OutputStream
rns-sample-app/BluetoothLeConnection.kt    -- Android BLE driver, provides streams
rns-sample-app/InterfaceManager.kt         -- Glue: creates BLE connection, passes streams to RNodeInterface
```

**Key insight from `RNodeInterface.kt` (line 37-48):**
```kotlin
class RNodeInterface(
    name: String,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    // ... radio parameters
) : Interface(name) {
```

The interface is stream-agnostic. It does not know or care whether the underlying transport is BLE, serial, or TCP. The Android-specific BLE connection code lives in `BluetoothLeConnection.kt` and produces `InputStream`/`OutputStream` via piped streams. `InterfaceManager` wires them together.

### Evaluation of Options

#### Option A: All in rns-sample-app

Put everything in the sample app. Simplest.

- **Pro:** No module changes, fast to implement
- **Con:** Not reusable by other Android apps consuming the library
- **Con:** Cannot unit test protocol logic without Android test framework
- **Con:** Violates the existing pattern where `Interface` subclasses live in `rns-interfaces`
- **Verdict:** Reject. Too much regression from existing architecture quality.

#### Option B: Protocol in rns-interfaces, driver in rns-sample-app

Split along the JVM/Android boundary. Fragmentation, reassembly, peer scoring, and the abstract interface class go in `rns-interfaces`. Android BLE GATT code goes in `rns-sample-app`.

- **Pro:** Protocol logic is pure JVM, fully unit-testable
- **Pro:** Follows the RNode pattern already established
- **Pro:** No new modules needed
- **Con:** BLE Interface is more complex than RNode -- it is not stream-based. It manages multiple concurrent peers, each with their own GATT connection, fragmentation state, and send queue. The RNode stream abstraction does not apply here.
- **Con:** The "driver" for BLE is not just `InputStream`/`OutputStream` -- it is a richer abstraction (scan, connect, disconnect, send fragment, receive fragment, MTU negotiation)
- **Verdict:** The right idea, but the abstraction boundary is NOT streams.

#### Option C: New `rns-ble` Android library module

Create a new `rns-ble` module with `com.android.library` that contains both protocol and driver code.

- **Pro:** Self-contained, reusable as a library
- **Con:** Puts testable protocol logic behind Android dependency wall
- **Con:** More modules to maintain
- **Con:** `rns-android` already exists as the Android library layer
- **Verdict:** Reject. Unnecessary module proliferation. Could revisit if BLE code grows beyond ~3K lines.

#### Option D: Driver interface in rns-interfaces, Android implementation injected (RECOMMENDED)

Define a platform abstraction in `rns-interfaces` that the BLE Interface depends on. The Android app provides the implementation.

```
rns-interfaces/
  ble/BLEInterface.kt           -- Extends Interface, pure JVM
  ble/BLEPeerInterface.kt       -- Spawned per-peer interface, pure JVM
  ble/BLEFragmentation.kt       -- Fragmenter + Reassembler, pure JVM
  ble/BLEDriver.kt              -- Interface (abstraction) for BLE operations

rns-sample-app/ (or rns-android/)
  ble/AndroidBLEDriver.kt       -- Implements BLEDriver using Android APIs
  ble/BleScanner.kt             -- Scanning via BluetoothLeScanner
  ble/BleGattClient.kt          -- Central mode GATT
  ble/BleGattServer.kt          -- Peripheral mode GATT
  ble/BleOperationQueue.kt      -- Serial GATT execution
```

- **Pro:** All protocol logic is pure JVM, fully unit-testable with mock drivers
- **Pro:** Follows dependency inversion -- high-level module defines the abstraction
- **Pro:** Future-proof: could implement `DesktopBLEDriver` using `bleak4j` or similar
- **Pro:** Matches how Columba structured it (BleConnectionManager as orchestration layer)
- **Con:** More upfront design work for the driver interface
- **Verdict:** RECOMMENDED. The extra design work pays for itself in testability and separation.

### Recommended Driver Interface Shape

Based on analysis of what `BLEInterface.py` actually needs from the BLE stack:

```kotlin
// In rns-interfaces/ble/BLEDriver.kt (pure JVM)

/**
 * Platform abstraction for BLE operations.
 * Implementations provide actual BLE scanning, advertising, and GATT I/O.
 */
interface BLEDriver {
    /** Start scanning for peers advertising the Reticulum service UUID. */
    fun startScanning(callback: ScanCallback)
    fun stopScanning()

    /** Start advertising as a peripheral. */
    fun startAdvertising(deviceName: String, callback: AdvertiseCallback)
    fun stopAdvertising()

    /** Connect to a peer as central. Returns negotiated MTU. */
    suspend fun connect(address: String, timeout: Long): BLEPeerConnection

    /** Accept incoming connection as peripheral. */
    // Handled via AdvertiseCallback.onPeerConnected

    /** Clean up all resources. */
    fun shutdown()
}

interface BLEPeerConnection {
    val address: String
    val mtu: Int
    val isConnected: Boolean

    /** Send a fragment to this peer. */
    suspend fun sendFragment(data: ByteArray)

    /** Register callback for incoming fragments. */
    fun onFragmentReceived(callback: (ByteArray) -> Unit)

    /** Register callback for disconnection. */
    fun onDisconnected(callback: () -> Unit)

    /** Disconnect this peer. */
    fun disconnect()
}

interface ScanCallback {
    fun onPeerDiscovered(address: String, name: String?, rssi: Int)
}

interface AdvertiseCallback {
    fun onAdvertisingStarted()
    fun onPeerConnected(connection: BLEPeerConnection)
    fun onPeerDisconnected(address: String)
}
```

This interface captures exactly what the BLE Interface protocol layer needs without exposing any Android-specific types.

---

## 2. Interface Integration with Transport

### How Registration Works (from codebase analysis)

The codebase uses a two-layer design:

1. **`Interface`** (abstract class in `rns-interfaces`) -- the actual networking code
2. **`InterfaceRef`** (interface in `rns-core`) -- a lightweight reference that Transport uses
3. **`InterfaceAdapter`** bridges the two, with a cache to prevent duplicates

**Registration flow (from `InterfaceManager.kt` lines 231-238):**
```kotlin
iface.onPacketReceived = { data, fromInterface ->
    Transport.inbound(data, InterfaceAdapter.getOrCreate(fromInterface))
}
iface.start()
Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))
```

### Server-Spawned Interface Pattern

`TCPServerInterface` is the exact model for BLE. Key observations from the code:

**TCPServerInterface (lines 97-121):**
- Server creates `TCPServerClientInterface` for each connected client
- Sets `parentInterface = this` on each client
- Sets `onPacketReceived` callback that forwards to server's callback AND rebroadcasts to other clients
- Adds to `spawnedInterfaces` list
- Client calls `parentServer.clientDisconnected(this)` on teardown

**How this maps to BLE:**

| TCP Server Pattern | BLE Equivalent |
|---|---|
| `TCPServerInterface` | `BLEInterface` (the main interface) |
| `TCPServerClientInterface` | `BLEPeerInterface` (one per connected peer) |
| `acceptLoop()` spawning clients | Discovery loop connecting to peers |
| `clientDisconnected()` | Peer disconnect handler |
| Server's `processOutgoing()` broadcasts to all clients | BLE's `processOutgoing()` broadcasts to all peers |

**Critical difference:** TCP server only receives connections (accept loop). BLE does BOTH:
- Central mode: actively scans and connects to peers (like a client)
- Peripheral mode: advertises and accepts connections (like a server)

Both result in spawned `BLEPeerInterface` instances. The BLE Interface is simultaneously a client and a server.

### Transport Registration for Spawned Interfaces

From the Python BLE code (`BLEInterface.py` line 808):
```python
RNS.Transport.interfaces.append(peer_if)
```

Each spawned peer interface is registered directly with Transport. This matches how `TCPServerInterface` does it in Kotlin -- the server's `onPacketReceived` callback forwards packets with the spawned interface as the source, and Transport sees each peer as a separate interface.

**Recommendation:** `BLEInterface` itself should NOT be registered with Transport for data routing. Only the spawned `BLEPeerInterface` instances should be registered. `BLEInterface` acts as the orchestrator (like `TCPServerInterface`), and its `processOutgoing()` broadcasts to all connected peers.

**Concrete integration pattern:**

```kotlin
class BLEInterface(...) : Interface(name) {
    private val peers = ConcurrentHashMap<String, BLEPeerInterface>()

    init {
        spawnedInterfaces = mutableListOf()
    }

    fun spawnPeer(address: String, name: String, connection: BLEPeerConnection) {
        val peerInterface = BLEPeerInterface(
            parent = this,
            peerAddress = address,
            peerName = name,
            connection = connection
        )
        peerInterface.parentInterface = this
        peerInterface.onPacketReceived = { data, iface ->
            // Forward to our callback (which goes to Transport)
            onPacketReceived?.invoke(data, iface)
        }

        peers[address] = peerInterface
        spawnedInterfaces?.add(peerInterface)

        // Register spawned interface with Transport
        try {
            Transport.registerInterface(peerInterface.toRef())
        } catch (_: Exception) {
            // Transport may not be running in tests
        }

        peerInterface.start()
    }

    fun peerDisconnected(address: String) {
        val peer = peers.remove(address) ?: return
        spawnedInterfaces?.remove(peer)
        try {
            Transport.deregisterInterface(peer.toRef())
        } catch (_: Exception) {}
        peer.detach()
    }

    override fun processOutgoing(data: ByteArray) {
        // Broadcast to all connected peers
        for (peer in peers.values) {
            if (peer.online.get()) {
                try { peer.processOutgoing(data) } catch (_: Exception) {}
            }
        }
    }
}
```

---

## 3. Concurrency Model

### What Needs Concurrent Handling

Each BLE peer has independent state that changes asynchronously:

| State | Lifecycle | Concurrency Need |
|-------|-----------|-----------------|
| GATT connection | Created/destroyed by scan/connect/disconnect | One at a time per peer |
| Fragmentation (TX) | Per-packet, per-peer | Serialize sends per peer |
| Reassembly (RX) | Per-packet, per-peer | Fragments arrive on GATT callback thread |
| Handshake | Per-peer, during connection setup | Sequential |
| Discovery/scoring | Periodic, modifies peer list | Must not race with connect/disconnect |

### Existing Patterns in the Codebase

**TCPServerInterface:** Uses `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per interface. Each client gets its own `readJob` coroutine. Write serialization uses `AtomicBoolean(writing)` with busy-wait.

**TCPClientInterface:** Same pattern. Adds `parentScope` support for Android lifecycle integration. Creates child scope when parent provided: `CoroutineScope(parent.coroutineContext + SupervisorJob(parentJob) + Dispatchers.IO)`.

**RNodeInterface:** Same pattern. Single `readJob` for its byte-level state machine. Write serialization via `synchronized(txLock)`.

**BluetoothLeConnection:** Uses `CountDownLatch` for BLE write synchronization (Android requires waiting for `onCharacteristicWrite` callback before next write). This is the correct pattern for Android BLE -- you cannot pipeline GATT writes.

### Recommended Concurrency Model

**One CoroutineScope per BLEInterface (not per peer).** Use structured concurrency with SupervisorJob so one peer's failure does not cancel others.

```kotlin
class BLEInterface(
    name: String,
    private val driver: BLEDriver,
    private val parentScope: CoroutineScope? = null,
) : Interface(name) {

    private val ioScope: CoroutineScope = if (parentScope != null) {
        CoroutineScope(
            parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            Dispatchers.IO
        )
    } else {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private var discoveryJob: Job? = null

    override fun start() {
        discoveryJob = ioScope.launch {
            discoveryLoop()
        }
        driver.startAdvertising(deviceName, peripheralCallback)
        online.set(true)
    }
}
```

**Per-peer send serialization:** Each `BLEPeerInterface` needs a send mutex or channel, because BLE fragment sends must be sequential (wait for write callback). Use Kotlin `Mutex` rather than busy-wait `AtomicBoolean`:

```kotlin
class BLEPeerInterface(
    private val parent: BLEInterface,
    val peerAddress: String,
    val peerName: String,
    private val connection: BLEPeerConnection,
) : Interface("$peerName/$peerAddress") {

    private val sendMutex = Mutex()
    private val fragmenter: BLEFragmenter  // initialized with connection.mtu
    private val reassembler: BLEReassembler

    override fun processOutgoing(data: ByteArray) {
        if (!online.get()) return

        // Launch send in parent scope (non-blocking from Transport's perspective)
        parent.ioScope.launch {
            sendMutex.withLock {
                val fragments = fragmenter.fragment(data)
                for (fragment in fragments) {
                    connection.sendFragment(fragment)  // suspend, waits for BLE callback
                }
                txBytes.addAndGet(data.size.toLong())
            }
        }
    }
}
```

**Discovery loop:** Single coroutine, periodic. Uses `delay()` between cycles (matches Python's `asyncio.sleep()`).

**Fragment reception:** GATT callbacks dispatch to coroutine immediately (never block the Binder thread). Reassembly is per-peer, so no cross-peer locking needed.

### Why NOT Actor Pattern

The actor pattern (Channel-based message processing) would add unnecessary complexity here. The BLE interface's concurrency needs are straightforward:
- Discovery is a single loop
- Per-peer sends need serialization (Mutex is sufficient)
- Per-peer receives are callback-driven (no shared state between peers)

Actors would be appropriate if peers needed to coordinate with each other, but they do not. Each peer is independent.

---

## 4. Existing Pattern Analysis

### Interface Spawning Pattern (TCP Server)

From `TCPServerInterface.kt`:

```
Server starts -> acceptLoop coroutine
  -> accept() returns Socket
  -> Create TCPServerClientInterface(socket, parentServer=this)
  -> Set parentInterface, onPacketReceived
  -> Add to clients list and spawnedInterfaces
  -> client.start() launches readLoop coroutine

Client disconnects:
  -> readLoop exits (EOF or IOException)
  -> client.detach() called
  -> parentServer.clientDisconnected(client) removes from lists
```

**Direct mapping for BLE:**

```
BLEInterface starts -> discoveryLoop coroutine + advertising
  -> Scanner discovers peer -> score and prioritize
  -> driver.connect(address) returns BLEPeerConnection
  -> Create BLEPeerInterface(connection, parent=this)
  -> Set parentInterface, onPacketReceived, onFragmentReceived
  -> Add to peers map and spawnedInterfaces
  -> Register with Transport

Peer disconnects:
  -> onDisconnected callback fires
  -> peerDisconnected(address) removes from maps
  -> Deregister from Transport
  -> Clean up fragmenter/reassembler state
```

### Stream-Based I/O (RNode)

`RNodeInterface` uses `InputStream`/`OutputStream` because RNode is fundamentally a serial port with a byte-stream protocol. BLE is NOT stream-based -- it is message-based (write characteristics, receive notifications). Each BLE operation is a discrete message, not a continuous stream.

**Do NOT use the piped-stream pattern for BLE mesh.** It worked for RNode because:
1. RNode is point-to-point (single device)
2. RNode uses KISS framing over a byte stream
3. RNode has no concept of multiple peer connections

BLE mesh is:
1. Multi-peer (up to 7 connections)
2. Message-based (characteristic writes/notifications)
3. Each peer has independent MTU and connection state

The piped-stream approach from `BluetoothLeConnection.kt` was correct for RNode-over-BLE, but would be incorrect for BLE-native mesh networking.

### State Machine Patterns

**RNodeInterface read loop state machine** (lines 301-538): Byte-by-byte processing with `inFrame`, `escape`, `command` state variables. Complex but mechanical -- each KISS command has known payload size.

**BLE does not need this.** BLE messages arrive as complete characteristic values (bounded by MTU). The fragmentation protocol has a simple 5-byte header that is parsed once per fragment. There is no byte-stuffing or escape sequence. The state machine for BLE is at the peer-connection level, not the byte level:

```
DISCONNECTED -> CONNECTING -> CONNECTED -> DISCONNECTING -> DISCONNECTED
```

### Error Recovery and Reconnection

**TCPClientInterface reconnection** (lines 237-274): Uses `ExponentialBackoff` with configurable max attempts. Network change events reset backoff via `onNetworkChanged()`. Clean pattern.

**BLE equivalent needs per-peer reconnection AND discovery-level reconnection:**

1. **Per-peer reconnection:** When a connected peer disconnects unexpectedly, should we retry? Generally NO for BLE mesh -- the peer will be rediscovered by the scan loop and scored against other candidates. Explicit reconnection creates affinity that undermines mesh dynamics.

2. **Discovery-level resilience:** The scan loop itself should be resilient (restart after BLE stack errors, Status 133, etc.). Use adaptive scan intervals (Columba pattern: 2s active, 30s idle).

3. **Blacklisting:** Peers that consistently fail to connect should be temporarily blacklisted with exponential backoff (Python does this: 60s, 120s, 240s... capped at 480s).

**Recommendation:** Do NOT reuse `ExponentialBackoff` for per-peer reconnection. Instead, use the discovery loop with peer scoring -- failed peers get lower scores naturally (via `connection_attempts` vs `successful_connections` tracking) and eventually get blacklisted.

---

## 5. Testing Strategy

### Protocol-Level Unit Tests (Pure JVM)

Because all protocol code lives in `rns-interfaces` (pure JVM), standard JUnit/Kotest works:

**BLEFragmentation tests:**
```kotlin
class BLEFragmenterTest {
    @Test fun `fragments 500-byte packet at MTU 185`() { ... }
    @Test fun `single fragment for small packet`() { ... }
    @Test fun `rejects empty packet`() { ... }
}

class BLEReassemblerTest {
    @Test fun `reassembles 3 fragments in order`() { ... }
    @Test fun `reassembles fragments out of order`() { ... }
    @Test fun `times out incomplete packet`() { ... }
    @Test fun `handles duplicate fragments`() { ... }
}
```

These are the easiest and highest-value tests. The Python fragmentation module (`BLEFragmentation.py`) has a well-defined binary protocol (5-byte header, START/CONTINUE/END types) that is simple to test exhaustively.

**Peer scoring tests:**
```kotlin
class PeerScoringTest {
    @Test fun `strong RSSI outscores weak RSSI`() { ... }
    @Test fun `high success rate outscores low`() { ... }
    @Test fun `recently seen peer gets bonus`() { ... }
    @Test fun `blacklisted peer is excluded`() { ... }
}
```

### Integration Tests with Mock Driver

The `BLEDriver` interface enables testing the full `BLEInterface` without BLE hardware:

```kotlin
class MockBLEDriver : BLEDriver {
    val scannedPeers = mutableListOf<MockPeer>()
    val connections = mutableMapOf<String, MockBLEPeerConnection>()

    override fun startScanning(callback: ScanCallback) {
        // Emit pre-configured peers
        scannedPeers.forEach { callback.onPeerDiscovered(it.address, it.name, it.rssi) }
    }

    override suspend fun connect(address: String, timeout: Long): BLEPeerConnection {
        return connections.getOrPut(address) { MockBLEPeerConnection(address) }
    }
    // ...
}

class BLEInterfaceTest {
    @Test fun `discovers and connects to peers`() {
        val driver = MockBLEDriver()
        driver.scannedPeers.add(MockPeer("AA:BB:CC:DD:EE:01", "Peer1", -50))

        val bleInterface = BLEInterface("test", driver)
        bleInterface.start()

        // Verify peer interface spawned
        advanceUntilIdle()
        assertEquals(1, bleInterface.connectedPeerCount())
    }

    @Test fun `fragments and sends large packet to peer`() {
        val driver = MockBLEDriver()
        val mockConn = MockBLEPeerConnection("AA:BB:CC:DD:EE:01", mtu = 185)
        driver.connections["AA:BB:CC:DD:EE:01"] = mockConn

        val bleInterface = BLEInterface("test", driver)
        // ... connect, then send 500-byte packet
        bleInterface.processOutgoing(ByteArray(500))

        // Verify 3 fragments sent
        assertEquals(3, mockConn.sentFragments.size)
    }
}
```

### Loopback Testing

Create two `BLEInterface` instances with a `LoopbackBLEDriver` that connects them:

```kotlin
class LoopbackBLEDriver(private val peerDriver: LoopbackBLEDriver?) : BLEDriver {
    // When one side connects, create a paired connection to the other
    // Fragments sent by one side arrive as received fragments on the other
}
```

This tests the full protocol path: fragment -> send -> receive -> reassemble -> deliver to Transport.

### Android BLE Tests (On-Device)

These test the `AndroidBLEDriver` implementation and require two physical devices:

1. **Connection test:** Device A scans, finds Device B, connects, exchanges identity
2. **Data transfer test:** Send known packet, verify reassembly
3. **Multi-peer test:** Device A connects to B and C simultaneously
4. **Disconnection test:** Kill one device, verify cleanup

These are expensive and should be done manually or with a CI farm. The mock-based tests above cover the vast majority of correctness concerns.

### Python Interop Tests

Not immediately needed. The BLE protocol is simple (custom GATT service + fragmentation). If interop testing is desired later, run the Python BLE Interface on a Linux machine and have the Android device connect to it. The fragment header format (5 bytes: type + sequence + total) is the critical interop surface.

---

## 6. Recommended Architecture (Complete Picture)

### Module Layout

```
rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/
    BLEInterface.kt              -- Main interface (extends Interface), discovery orchestration
    BLEPeerInterface.kt          -- Spawned per-peer interface (extends Interface)
    BLEFragmentation.kt          -- BLEFragmenter + BLEReassembler (port of Python)
    BLEDriver.kt                 -- Platform abstraction interfaces
    BLEConstants.kt              -- Service UUID, characteristic UUIDs, timeouts
    DiscoveredPeer.kt            -- Peer tracking and scoring (port of Python)

rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/ble/
    AndroidBLEDriver.kt          -- Implements BLEDriver using Android APIs
    BleScanner.kt                -- BluetoothLeScanner wrapper
    BleGattClient.kt             -- Central mode GATT client
    BleGattServer.kt             -- Peripheral mode GATT server
    BleOperationQueue.kt         -- Serial GATT operation queue (critical for Android)
    BlePermissionHelper.kt       -- Runtime permission handling

rns-sample-app/.../InterfaceManager.kt
    -- Add BLE case alongside existing RNODE case
```

### Dependency Graph

```
rns-core (pure JVM)
    |
    v
rns-interfaces (pure JVM)
    |-- Interface, InterfaceAdapter
    |-- ble/BLEInterface, BLEPeerInterface, BLEFragmentation, BLEDriver
    |
    v
rns-android (Android library)
    |-- api(rns-core), api(rns-interfaces)
    |
    v
rns-sample-app (Android application)
    |-- implementation(rns-android)
    |-- service/ble/AndroidBLEDriver  (implements ble/BLEDriver)
    |-- service/InterfaceManager      (creates BLEInterface with AndroidBLEDriver)
```

### Data Flow (Complete Path)

**Outgoing (Reticulum -> BLE):**
```
Transport.outbound(data, interfaceRef)
  -> InterfaceAdapter.send(data)
    -> BLEInterface.processOutgoing(data)
      -> for each peer in peers:
        -> BLEPeerInterface.processOutgoing(data)
          -> fragmenter.fragment(data) -> [frag1, frag2, frag3]
          -> sendMutex.withLock:
            -> connection.sendFragment(frag1)  // suspend, waits for BLE callback
            -> connection.sendFragment(frag2)
            -> connection.sendFragment(frag3)
```

**Incoming (BLE -> Reticulum):**
```
[Android GATT callback thread]
onCharacteristicChanged(characteristic, value)
  -> scope.launch {  // dispatch immediately
    -> AndroidBLEPeerConnection.onFragmentReceived(value)
  }

[Coroutine on IO dispatcher]
  -> BLEPeerInterface.handleFragment(value)
    -> reassembler.receiveFragment(value, peerAddress)
    -> if complete packet:
      -> processIncoming(completePacket)  // calls onPacketReceived
        -> Transport.inbound(data, peerInterfaceRef)
```

**Discovery Flow:**
```
discoveryLoop (coroutine, periodic):
  -> driver.startScanning(callback)
  -> callback.onPeerDiscovered(addr, name, rssi)
    -> discoveredPeers[addr].updateRssi(rssi)
  -> selectPeersToConnect()  // score all candidates, pick top N
  -> for each selected:
    -> driver.connect(addr) -> BLEPeerConnection
    -> spawnPeer(addr, name, connection)
      -> create BLEPeerInterface
      -> register with Transport
  -> delay(scanInterval)
  -> repeat
```

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Module boundary | Driver interface in rns-interfaces | Testability. Protocol logic must be pure JVM. |
| Concurrency model | Single scope + per-peer Mutex | Simple, matches existing patterns. No need for actors. |
| Transport registration | Register spawned peers, not main interface | Matches TCPServer pattern. Transport needs per-peer routing. |
| Reconnection strategy | Discovery loop with scoring, not explicit reconnect | BLE mesh should not create peer affinity. Let scoring handle it. |
| Fragment protocol | Port Python's 5-byte header exactly | Interop with ble-reticulum Python implementation. |
| Dual-mode (central+peripheral) | Implement both from the start | Required for symmetric mesh. Central-only would be half-functional. |
| Identity exchange | GATT Identity characteristic (UUID 00000004) | Solves Android MAC rotation. Essential for mesh reliability. |
| Stream abstraction | Do NOT use InputStream/OutputStream | BLE is message-based, not stream-based. Piped streams add latency and complexity for multi-peer. |

### Component Sizing Estimates

| Component | Estimated Lines | Complexity |
|-----------|----------------|------------|
| `BLEFragmentation.kt` | ~250 | Low (direct port of Python) |
| `BLEDriver.kt` | ~80 | Low (interface definitions) |
| `BLEConstants.kt` | ~30 | Trivial |
| `DiscoveredPeer.kt` | ~120 | Low (data class + scoring) |
| `BLEInterface.kt` | ~350 | Medium (discovery, peer management) |
| `BLEPeerInterface.kt` | ~200 | Medium (fragment send/receive, lifecycle) |
| `AndroidBLEDriver.kt` | ~150 | Medium (delegation to sub-components) |
| `BleScanner.kt` | ~150 | Medium (Android BLE scanning) |
| `BleGattClient.kt` | ~300 | High (GATT callbacks, MTU, notifications) |
| `BleGattServer.kt` | ~300 | High (GATT server, advertising, notifications) |
| `BleOperationQueue.kt` | ~150 | Medium (serial GATT operation queue) |
| **Total** | **~2,100** | |

---

## 7. Anti-Patterns to Avoid

### Anti-Pattern 1: Blocking GATT Callbacks

**What goes wrong:** GATT callbacks run on Android's Binder thread. Blocking them (with locks, network calls, or heavy computation) causes BLE stack instability, dropped connections, and ANRs.

**Prevention:** Always dispatch from callbacks to a coroutine immediately. The callback should do nothing except post to a channel or launch a coroutine:

```kotlin
// WRONG
override fun onCharacteristicChanged(..., value: ByteArray) {
    mutex.withLock { reassembler.process(value) }  // Blocks Binder thread!
}

// RIGHT
override fun onCharacteristicChanged(..., value: ByteArray) {
    scope.launch { reassembler.process(value) }  // Returns immediately
}
```

### Anti-Pattern 2: Pipelining GATT Writes

**What goes wrong:** Android's BLE stack does NOT support concurrent GATT operations. If you call `writeCharacteristic()` before the previous write's callback fires, the second write is silently dropped.

**Prevention:** Use a serial operation queue (Columba's `BleOperationQueue` pattern) or a Mutex/Channel that ensures one GATT operation at a time:

```kotlin
private val operationQueue = Channel<BleOperation>(Channel.UNLIMITED)

init {
    scope.launch {
        for (op in operationQueue) {
            op.execute()
            op.awaitCompletion()  // Wait for GATT callback
        }
    }
}
```

### Anti-Pattern 3: Using MAC Address as Stable Identifier

**What goes wrong:** Android uses Resolvable Private Addresses (RPA) that rotate. The same device appears with different MAC addresses, breaking peer tracking, blacklisting, and scoring.

**Prevention:** Use the Identity Characteristic (GATT UUID `00000004-...`) to exchange Reticulum identity hashes on connection. Use identity hash, not MAC address, as the peer key in all maps.

### Anti-Pattern 4: Mixing Protocol and Driver in One Class

**What goes wrong:** Untestable code. The Python `BLEInterface.py` mixes discovery logic, scoring algorithms, and `BleakClient` calls in one 1230-line class. Cannot unit test scoring without BLE stack.

**Prevention:** The recommended architecture separates these cleanly. `BLEInterface` depends on `BLEDriver` interface, not Android classes.

### Anti-Pattern 5: Reconnecting to Specific Peers

**What goes wrong:** In a mesh network, reconnecting to specific peers creates affinity that reduces mesh resilience. If peer A moves away but keeps getting reconnected to, we miss nearby peer B.

**Prevention:** Let the discovery loop handle peer selection. Failed peers get lower scores naturally. Blacklisting handles persistent failures. The mesh should be topology-agnostic.

---

## 8. Scalability Considerations

| Concern | 1-2 peers | 5-7 peers | Post-7 (mesh relay) |
|---------|-----------|-----------|---------------------|
| Memory | ~50KB per peer (frag buffers) | ~350KB total | N/A (BLE limit is 7) |
| CPU | Negligible | Moderate (concurrent sends) | N/A |
| Battery | 1-2% per hour | 4-6% per hour | N/A |
| Scan interval | 5s (aggressive) | 10-30s (back off) | 30s+ (already connected) |
| Fragment throughput | ~100 kbps per peer | ~15-50 kbps per peer (shared radio) | N/A |

The BLE hardware limit of ~7 concurrent connections is the natural scalability cap. Beyond that, Reticulum's transport-level routing handles multi-hop relay through connected peers.

---

## Sources

All findings based on direct analysis of:
- `./rns-interfaces/src/main/kotlin/network/reticulum/interfaces/Interface.kt`
- `./rns-interfaces/src/main/kotlin/network/reticulum/interfaces/InterfaceAdapter.kt`
- `./rns-interfaces/src/main/kotlin/network/reticulum/interfaces/tcp/TCPServerInterface.kt`
- `./rns-interfaces/src/main/kotlin/network/reticulum/interfaces/tcp/TCPClientInterface.kt`
- `./rns-interfaces/src/main/kotlin/network/reticulum/interfaces/rnode/RNodeInterface.kt`
- `./rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/BluetoothLeConnection.kt`
- `./rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/InterfaceManager.kt`
- `~/repos/ble/src/RNS/Interfaces/BLEInterface.py`
- `~/repos/ble/src/RNS/Interfaces/BLEFragmentation.py`
- `~/repos/ble/src/RNS/Interfaces/BLEGATTServer.py`
- `~/repos/columba/BLE_ARCHITECTURE.md`
- `./rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt`
- All `build.gradle.kts` files and `settings.gradle.kts`
