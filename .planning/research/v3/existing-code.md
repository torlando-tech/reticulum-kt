# Existing Code Inventory: BLE Interface Port

**Researched:** 2026-02-06
**Confidence:** HIGH (direct source file reads, no web-only claims)

---

## 1. Python Code Inventory (ble-reticulum)

### Source Files (`~/repos/public/ble-reticulum/src/ble_reticulum/`)

| File | Lines | Primary Classes/Functions | Purpose | Dependencies |
|------|-------|--------------------------|---------|--------------|
| `BLEInterface.py` | 2,515 | `BLEInterface(Interface)`, `BLEPeerInterface(Interface)`, `DiscoveredPeer` | Main interface: dual-mode BLE, peer management, fragmentation orchestration, identity-based tracking, MAC rotation handling, peer scoring, spawned interface lifecycle | `RNS.Interfaces.Interface`, `BLEFragmentation`, `BLEGATTServer`, `bluetooth_driver`, `linux_bluetooth_driver` |
| `BLEFragmentation.py` | 535 | `BLEFragmenter`, `BLEReassembler`, `HDLCFramer` | Packet fragmentation (5-byte header: type+seq+total), reassembly with per-sender buffers, timeout/cleanup, HDLC byte-stuffing (alternative framer) | `struct`, `time` |
| `BLEGATTServer.py` | 684 | `BLEGATTServer` | Peripheral mode GATT server using bluezero: advertise service, RX/TX/Identity characteristics, central connection tracking, MTU tracking, notification sending | `bluezero`, `BLEAgent` |
| `BLEAgent.py` | 284 | `BLEAgent(dbus.service.Object)`, `register_agent()`, `unregister_agent()` | BlueZ D-Bus agent for automatic "Just Works" pairing without user interaction | `dbus`, `dbus.service` |
| `bluetooth_driver.py` | 217 | `BLEDriverInterface(ABC)`, `BLEDevice`, `DriverState` | Abstract driver interface: platform-agnostic contract for BLE operations (scan, advertise, connect, send, GATT ops) | None (pure abstract) |
| `linux_bluetooth_driver.py` | 2,501 | `LinuxBluetoothDriver(BLEDriverInterface)` | Linux-specific implementation using bleak (central) + bluezero (peripheral) + D-Bus workarounds | `bleak`, `bluezero`, `dbus_fast`, `bluetooth_driver` |
| `__init__.py` | 0 | - | Empty package init | - |

**Total Python source:** 6,736 lines

### Test Files (`~/repos/public/ble-reticulum/tests/`)

| File | Lines | What It Tests |
|------|-------|--------------|
| `conftest.py` | 333 | Test fixtures, mock setup |
| `mock_ble_driver.py` | 411 | Mock BLE driver implementing `BLEDriverInterface` |
| `test_fragmentation.py` | 305 | `BLEFragmenter`, `BLEReassembler` unit tests |
| `test_ble_peer_interface.py` | 306 | `BLEPeerInterface` lifecycle |
| `test_v2_2_identity_handshake.py` | 597 | Protocol v2.2 identity handshake flow |
| `test_v2_2_mac_sorting.py` | 526 | MAC address comparison for connection direction |
| `test_v2_2_race_conditions.py` | 416 | Race conditions in identity/MTU/connect sequence |
| `test_identity_cache.py` | 535 | Identity caching across disconnects |
| `test_identity_hash.py` | 129 | Identity hash computation |
| `test_identity_mapping_cleanup.py` | 310 | Cleanup of identity-to-address mappings |
| `test_zombie_connection_detection.py` | 700 | Zombie connection detection and replacement |
| `test_mac_rotation_blacklist_bug.py` | 839 | MAC rotation + blacklist interaction |
| `test_peer_address_mac_rotation.py` | 374 | Address update during MAC rotation |
| `test_ble_duplicate_identity_stale.py` | 366 | Stale duplicate identity detection |
| `test_peripheral_disconnect_cleanup.py` | 558 | Peripheral disconnect cleanup |
| `test_interface_cleanup.py` | 705 | Interface cleanup and orphan detection |
| `test_error_recovery.py` | 367 | Error recovery paths |
| `test_multi_device_simulation.py` | 492 | Multi-peer simulation |
| `test_prioritization.py` | 473 | Peer scoring algorithm |
| `test_gatt_server.py` | 333 | GATT server callbacks |
| `test_gatt_server_readiness.py` | 372 | GATT server startup sequence |
| `test_hci_error_fixes.py` | 791 | HCI error handling and recovery |
| `test_scanner_connection_coordination.py` | 310 | Scanner/connection timing coordination |
| `test_stale_connection_polling.py` | 328 | Stale connection detection via polling |
| `test_integration.py` | 147 | Integration test skeleton |
| `test_bleak_threading_hang.py` | 236 | Bleak threading hang prevention |
| `test_bleak_with_exec_loading.py` | 85 | Bleak compatibility with exec loading |
| `test_bluez_state_cleanup.py` | 267 | BlueZ state cleanup |
| `test_breddr_fallback_prevention.py` | 310 | BR/EDR fallback prevention |
| `test_config_directory.py` | 145 | Config directory resolution |
| `test_dbus_disconnect_monitoring.py` | 355 | D-Bus disconnect monitoring |

**Total Python tests:** 12,421 lines across 30 test files

### Example/Tool Files

| File | Lines | Purpose |
|------|-------|---------|
| `examples/ble_minimal_test.py` | - | Minimal BLE test example |
| `examples/two_device_simulator.py` | - | Two-device simulation |
| `tools/ble_log_shipper.py` | - | Log shipping for monitoring |
| `tools/ble_metrics_exporter.py` | - | Prometheus metrics exporter |

---

## 2. Columba Kotlin BLE Code Inventory

### Source Files (`~/repos/public/columba/reticulum/src/main/java/.../ble/`)

| File | Lines | Primary Class | Purpose | Android Dependencies |
|------|-------|--------------|---------|---------------------|
| `bridge/KotlinBLEBridge.kt` | 2,383 | `KotlinBLEBridge` | **Main entry point** for Python: singleton, orchestrates scanner/client/server/advertiser, Bluetooth state monitoring, connection tracking, deduplication, MAC rotation handling, data routing | `BluetoothManager`, `BluetoothAdapter`, Chaquopy `PyObject` |
| `client/BleGattClient.kt` | 1,225 | `BleGattClient` | **Central mode**: GATT connect/disconnect, service discovery, MTU negotiation (up to 517), CCCD enable, characteristic read/write, identity handshake (4-step), keepalive jobs, GATT error 133 retry | `BluetoothGatt`, `BluetoothGattCallback` |
| `server/BleGattServer.kt` | 1,088 | `BleGattServer` | **Peripheral mode**: GATT server with RX/TX/Identity characteristics, central tracking, identity handshake detection, MTU tracking, notification sending, keepalive | `BluetoothGattServer`, `BluetoothGattServerCallback` |
| `client/BleScanner.kt` | 446 | `BleScanner` | **Discovery**: adaptive scan intervals (5s active, 30s idle), service UUID filtering, RSSI tracking, device cache with StateFlow | `BluetoothLeScanner`, `ScanCallback` |
| `server/BleAdvertiser.kt` | 465 | `BleAdvertiser` | **Advertising**: service UUID + identity in device name, retry with backoff, proactive 60s refresh (Android silently stops advertising in background) | `BluetoothLeAdvertiser`, `AdvertiseCallback` |
| `service/BleConnectionManager.kt` | 984 | `BleConnectionManager` | **Coordinator**: dual-mode orchestration, connection pool, MAC sorting, identity tracking, data routing; alternative to KotlinBLEBridge (no Python dependency) | `BluetoothManager` |
| `util/BleOperationQueue.kt` | 498 | `BleOperationQueue` | **Serial GATT ops**: Android BLE does NOT queue operations internally; this ensures serial execution with completion tracking | `BluetoothGatt` |
| `util/BlePairingHandler.kt` | 322 | `BlePairingHandler` | **Auto-pairing**: BroadcastReceiver for `ACTION_PAIRING_REQUEST`, auto-confirms pairing (Android equivalent of Python's BLEAgent) | `BroadcastReceiver` |
| `util/BlePermissionManager.kt` | 131 | `BlePermissionManager` | **Permission checks**: runtime permissions for BLE (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, FINE_LOCATION) | Android permissions API |
| `model/BleConstants.kt` | 225 | `BleConstants` | **Constants**: Service UUID, char UUIDs, CCCD UUID, connection limits, timeouts, fragment header size, GATT error codes | None |
| `model/BleDevice.kt` | 101 | `BleDevice` | **Data model**: discovered peer with address, name, RSSI, identity, connection stats, priority scoring | `Parcelable` |
| `model/BleConnectionState.kt` | 59 | `BleConnectionState` (sealed class) | **State machine**: Idle, Scanning, Connecting, Connected, Error, BluetoothDisabled, PermissionDenied | None |

**Total Columba Kotlin BLE source:** 7,927 lines across 12 files

### Columba BLE Test Files

| File | Lines | What It Tests |
|------|-------|--------------|
| `bridge/KotlinBLEBridgeTest.kt` | 996 | Bridge lifecycle, start/stop, callback routing |
| `bridge/KotlinBLEBridgeDeduplicationTest.kt` | 517 | Dual connection deduplication |
| `bridge/KotlinBLEBridgeMacRotationTest.kt` | 495 | MAC rotation handling |
| `bridge/KotlinBLEBridgeSendRaceConditionTest.kt` | 396 | Send race conditions |
| `bridge/KotlinBLEBridgeDuplicateIdentityCallbackTest.kt` | 381 | Duplicate identity detection |
| `bridge/KotlinBLEBridgeAddressChangeTest.kt` | 313 | Address change notifications |
| `bridge/KotlinBLEBridgeNullAdapterTest.kt` | 307 | Graceful handling of missing Bluetooth hardware |
| `bridge/KotlinBLEBridgeMacSortingTest.kt` | 233 | MAC address comparison/sorting |
| `server/BleGattServerKeepaliveTest.kt` | 318 | Server keepalive mechanism |
| `client/BleGattClientKeepaliveTest.kt` | 280 | Client keepalive mechanism |
| `util/BleOperationQueueTimeoutTest.kt` | 250 | Operation queue timeout behavior |
| `client/BleScannerRemoveDeviceTest.kt` | 211 | Scanner device removal |
| `util/BlePairingHandlerThreadSafetyTest.kt` | 142 | Pairing handler thread safety |

**Total Columba Kotlin BLE tests:** 4,839 lines across 13 files

### Columba App-Layer BLE Files (outside reticulum module)

| File | Purpose |
|------|---------|
| `app/.../service/manager/BleCoordinator.kt` | App-level BLE lifecycle coordinator |
| `app/.../data/model/BleConnectionInfo.kt` | UI data model for BLE connections |
| `app/.../data/repository/BleStatusRepository.kt` | Repository pattern for BLE status |
| `app/.../viewmodel/BleConnectionsViewModel.kt` | ViewModel for BLE UI |
| `app/.../ui/screens/BleConnectionStatusScreen.kt` | BLE status screen UI |
| `app/.../ui/components/BlePermissionBottomSheet.kt` | Permission request UI |

---

## 3. Documentation Inventory

### ble-reticulum Documentation

| File | Lines | Content |
|------|-------|---------|
| `BLE_PROTOCOL_v2.2.md` | ~500+ | **Authoritative protocol spec**: GATT service structure, connection direction (MAC sorting), identity handshake protocol, identity-based keying, fragmentation format, connection flow diagrams, error handling, configuration reference, platform workarounds, lifecycle sequence diagrams |
| `BLE_PROTOCOL_v0.3.0.md` | - | Earlier protocol version (historical reference) |
| `REFACTORING_GUIDE.md` | - | Guide for refactoring the codebase |
| `PERIPHERAL_DISCONNECT_FIX_SUMMARY.md` | - | Fix summary for peripheral disconnect bug |
| `DBUS_MONITORING_FIX.md` | - | D-Bus monitoring fix documentation |
| `TESTING.md` | - | Testing guide |
| `README.md` | - | Project overview |

### Columba BLE Documentation

| File | Content |
|------|---------|
| `docs/ble-architecture.md` | Full architecture overview with Mermaid diagrams: Python layer -> Kotlin layer -> Android BLE stack; GATT service structure; layer responsibilities |
| `.claude/skills/columba-ble-networking/docs/BLE_ARCHITECTURE_OVERVIEW.md` | GATT protocol basics, central vs peripheral modes, dual-mode operation, component architecture, data flow, state management |

### Key Protocol Details from BLE_PROTOCOL_v2.2.md

**Service UUID:** `37145b00-442d-4a94-917f-8f42c5da28e3`

**GATT Characteristics:**
- **RX** (central writes): `37145b00-442d-4a94-917f-8f42c5da28e5` -- WRITE, WRITE_WITHOUT_RESPONSE
- **TX** (peripheral notifies): `37145b00-442d-4a94-917f-8f42c5da28e4` -- READ, NOTIFY
- **Identity** (protocol v2+): `37145b00-442d-4a94-917f-8f42c5da28e6` -- READ (16-byte identity hash)

**Fragment Header Format (5 bytes):**
```
[Type: 1 byte][Sequence: 2 bytes (big-endian)][Total: 2 bytes (big-endian)][Data: variable]
Type: 0x01=START, 0x02=CONTINUE, 0x03=END
```

**Identity Handshake (Protocol v2.2):**
1. Central reads peripheral's Identity characteristic (16 bytes)
2. Central sends its own 16-byte identity as first RX write
3. Both sides now know each other's identity, surviving MAC rotation

**MAC Sorting (Connection Direction):**
- Compare local and remote MAC addresses as strings (case-insensitive)
- Lower MAC acts as central (initiates connection)
- Higher MAC acts as peripheral (accepts connection)
- Prevents both sides from trying to connect simultaneously

---

## 4. Class Hierarchy and Architecture

### Python Class Hierarchy

```
RNS.Interfaces.Interface (base class from Reticulum)
    |
    +-- BLEInterface
    |       Dual-mode parent interface
    |       Manages discovery, connections, fragmentation, spawned interfaces
    |       NOT registered with Transport directly (spawned interfaces are)
    |       Configuration via Reticulum config file
    |
    +-- BLEPeerInterface
            Per-peer spawned interface
            Registered with Transport for routing
            process_incoming() -> Transport.inbound()
            process_outgoing() -> fragment -> driver.send()
            Copies HW_MTU, bitrate from parent
            Tracks peer_identity for stable identification

BLEDriverInterface (ABC) -- abstract platform contract
    |
    +-- LinuxBluetoothDriver
            Uses bleak + bluezero + D-Bus
            Own async event loop thread
            Callbacks: on_device_discovered, on_device_connected, etc.

BLEGATTServer -- bluezero peripheral wrapper
    Callbacks: on_data_received, on_central_connected, on_central_disconnected

BLEFragmenter -- fragments packets for BLE MTU
BLEReassembler -- reassembles fragments with per-sender buffers
DiscoveredPeer -- peer tracking with RSSI, connection stats, scoring
```

### Columba Kotlin Architecture (Android)

```
KotlinBLEBridge (singleton entry point, called from Python via Chaquopy)
    |
    +-- BleScanner            -- discovery with adaptive intervals
    +-- BleGattClient         -- central mode GATT operations
    +-- BleGattServer         -- peripheral mode GATT server
    +-- BleAdvertiser         -- BLE advertising with identity
    +-- BleOperationQueue     -- serial GATT operation execution

BleConnectionManager (alternative coordinator, no Python dependency)
    |
    +-- BleScanner
    +-- BleGattClient
    +-- BleGattServer
    +-- BleAdvertiser
    +-- BleOperationQueue
    +-- BlePairingHandler

Data Models:
    BleDevice           -- discovered peer (Parcelable)
    BleConnectionState  -- sealed class state machine
    BleConstants        -- UUIDs, timeouts, limits
```

### Existing reticulum-kt Code

```
Interface (abstract base)
    |
    +-- RNodeInterface (LoRa hardware via InputStream/OutputStream)

BluetoothLeConnection -- GATT client for RNode NUS service
    Uses: NUS_SERVICE_UUID (Nordic UART)
    Provides: InputStream/OutputStream via PipedStream bridge
    Pattern: scan -> connect GATT -> MTU -> discover services -> enable notifications

InterfaceManager -- hot-reload lifecycle management for all interface types
    Uses: BluetoothLeConnection for RNode BLE connections
```

---

## 5. Kotlin Reuse Assessment

### BluetoothLeConnection.kt (435 lines) -- PARTIAL REUSE

**What it does now:** GATT client connecting to RNode via Nordic UART Service (NUS). Provides InputStream/OutputStream bridge for RNodeInterface.

**Reuse potential for BLE Interface:**

| Component | Reusable? | Notes |
|-----------|-----------|-------|
| GATT connection flow | **YES** | connect() -> requestMtu() -> discoverServices() -> enableNotifications() is identical pattern |
| MTU negotiation | **YES** | Same approach: requestMtu(512) with fallback timer |
| Write synchronization (latch pattern) | **YES** | Critical for Android BLE: one write at a time via CountDownLatch |
| PipedInputStream/OutputStream bridge | **NO** | BLE Interface uses fragment-based data, not stream-based |
| NUS service UUIDs | **NO** | BLE Interface uses custom Reticulum service UUID |
| Single-device scanning | **PARTIAL** | BLE Interface needs multi-device discovery with adaptive intervals |
| Connection retry loop | **YES** | Same pattern: retry with backoff for GATT error 133 |

**Verdict:** The GATT client connection mechanics (MTU, write sync, retry) are directly portable. The stream abstraction (PipedStream) is NOT applicable -- BLE Interface works with fragment-level data, not byte streams.

### InterfaceManager.kt -- MODERATE REUSE

**What it does now:** Manages interface lifecycle via Flow observation of StoredInterfaceConfig. Handles start/stop of all interface types including BLE connections.

**Reuse potential:**

| Component | Reusable? | Notes |
|-----------|-----------|-------|
| Interface lifecycle (syncInterfaces) | **YES** | Add BLE Interface type alongside TCP/RNode/Auto |
| BluetoothLeConnection tracking | **EXTEND** | Already tracks `bleConnections` by config ID |
| Network state observation | **YES** | BLE should also react to network changes |
| Hot-reload pattern | **YES** | BLE Interface config changes should trigger restart |

**New fields needed in StoredInterfaceConfig:**

| Field | Type | Default | Python Config Equivalent |
|-------|------|---------|--------------------------|
| `enableCentral` | Boolean? | true | `enable_central` |
| `enablePeripheral` | Boolean? | true | `enable_peripheral` |
| `maxConnections` | Int? | 7 | `max_connections` |
| `discoveryInterval` | Double? | 5.0 | `discovery_interval` |
| `minRssi` | Int? | -85 | `min_rssi` |
| `powerMode` | String? | "balanced" | `power_mode` |
| `deviceName` | String? | null | `device_name` |
| `serviceUuid` | String? | null | `service_uuid` (for custom networks) |

### RNodeInterface.kt (500+ lines) -- PATTERN REUSE

**What it does now:** LoRa radio interface using KISS framing over InputStream/OutputStream.

**Reuse potential:**

| Pattern | Applicable? | Notes |
|---------|-------------|-------|
| CoroutineScope management | **YES** | Same pattern: parentScope with SupervisorJob + Dispatchers.IO |
| Read loop in coroutine | **PARTIAL** | BLE Interface uses callbacks, not a blocking read loop |
| Interface.name, hwMtu, bitrate | **YES** | Same base class fields to set |
| processOutgoing pattern | **YES** | Same: check online -> send data |
| Online/offline lifecycle | **YES** | Same pattern of detecting hardware and going online |

### Interface.kt (base class) -- DIRECT INHERITANCE

**BLEInterface must extend Interface**, same as RNodeInterface. Key fields to set:

| Field | BLE Interface Value | Source |
|-------|-------------------|--------|
| `name` | Config name or "BLEInterface" | Config |
| `hwMtu` | 500 (Reticulum standard) | Constant |
| `bitrate` | 700,000 (BLE throughput estimate) | Constant |
| `mode` | InterfaceMode.FULL | Constant |
| `canReceive` / `canSend` | true / true | Constant |
| `online` | set after driver starts | Lifecycle |

### Columba BleGattClient.kt vs our BluetoothLeConnection.kt

| Feature | BluetoothLeConnection (ours) | BleGattClient (Columba) |
|---------|------------------------------|------------------------|
| Connection | Single device by address | Multi-device pool |
| MTU negotiation | requestMtu(512) | requestMtu(517) with operation queue |
| Write sync | CountDownLatch | Coroutine suspendCancellable + operation queue |
| Notifications | Direct GATT callback -> PipedStream | Callback -> data handler |
| Identity handshake | N/A (NUS service) | 4-step: connect -> MTU -> read identity -> send identity |
| Keepalive | N/A | 15-second keepalive to prevent supervision timeout |
| Error handling | Basic retry x3 | GATT error 133 specific handling, exponential backoff |
| Concurrency | Thread.sleep polling | Full coroutine/Mutex |

**Recommendation:** The Columba `BleGattClient` is the more complete reference. Our `BluetoothLeConnection` is simpler but lacks critical features (identity handshake, keepalive, operation queue, multi-connection). For the BLE Interface port, we should follow Columba's patterns but adapt them to our coroutine/Interface model.

---

## 6. Configuration Parameters

### Python BLE Interface Config Parameters

From `BLEInterface.__init__()` parsing:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | str | "BLEInterface" | Interface display name |
| `service_uuid` | str | `37145b00-442d-4a94-917f-8f42c5da28e3` | Custom Reticulum service UUID |
| `device_name` | str | None | BLE advertising name (keep short, max 8 chars) |
| `discovery_interval` | float | 5.0 | Seconds between discovery scans |
| `max_connections` | int | 7 | Max simultaneous BLE connections |
| `min_rssi` | int | -85 | Minimum signal strength (dBm) |
| `connection_timeout` | float | 30.0 | Seconds before connection times out |
| `service_discovery_delay` | float | 1.5 | Delay after connect before service discovery |
| `power_mode` | str | "balanced" | "aggressive", "balanced", "saver" |
| `enable_central` | bool | true | Enable scanning and connecting |
| `enable_peripheral` | bool | true | Enable GATT server and advertising |
| `enable_local_announce_forwarding` | bool | false | Workaround for Transport local announce bug |
| `max_discovered_peers` | int | 100 | Max entries in discovery cache |
| `connection_rotation_interval` | float | 600.0 | Seconds between connection pool rotations |
| `connection_retry_backoff` | float | 60.0 | Seconds between connection retries |
| `max_connection_failures` | int | 3 | Failures before blacklisting |

### Mapping to StoredInterfaceConfig

Current `StoredInterfaceConfig` needs these BLE-specific fields:

```kotlin
// BLE Interface parameters
val enableCentral: Boolean? = null,     // enable_central
val enablePeripheral: Boolean? = null,  // enable_peripheral
val maxConnections: Int? = null,        // max_connections
val discoveryInterval: Double? = null,  // discovery_interval (seconds)
val minRssi: Int? = null,              // min_rssi (dBm)
val powerMode: String? = null,         // power_mode: "aggressive"/"balanced"/"saver"
val bleDeviceName: String? = null,     // device_name for advertising
val bleServiceUuid: String? = null,    // custom service_uuid (rare)
```

---

## 7. Test Infrastructure Analysis

### Python Test Patterns (ble-reticulum)

**Key test infrastructure:**

1. **`mock_ble_driver.py` (411 lines)** -- Mock implementation of `BLEDriverInterface`:
   - Simulates device discovery, connection, data transfer
   - Tracks all method calls for verification
   - Supports simulating errors, disconnects, MTU changes
   - Identity handshake simulation

2. **`conftest.py` (333 lines)** -- pytest fixtures:
   - `mock_driver_class` -- provides mock driver class
   - `ble_interface` -- creates BLEInterface with mock driver
   - `connected_interface` -- BLEInterface with pre-connected peer
   - Handles RNS module mocking

**Test patterns to replicate in Kotlin:**

| Pattern | Python Implementation | Kotlin Approach |
|---------|----------------------|-----------------|
| Mock BLE driver | `MockBLEDriver(BLEDriverInterface)` | Create mock implementing driver interface |
| Simulated discovery | `mock.simulate_discovery(device)` | Direct callback invocation |
| Simulated connection | `mock.simulate_connection(addr, identity)` | Direct callback invocation |
| Identity handshake | `mock.simulate_data(addr, 16_byte_identity)` | Direct data callback |
| Fragmentation tests | Direct `BLEFragmenter`/`BLEReassembler` unit tests | Port fragmentation classes, test directly |
| MAC rotation | Disconnect old addr, connect new addr with same identity | Same pattern via callbacks |
| Zombie detection | Simulate idle connection, verify replacement | Time-based with test clock |
| Multi-peer | Create multiple mock connections, verify routing | Same |

### Columba Test Patterns

**Key test files:**

1. **`KotlinBLEBridgeTest.kt` (996 lines)** -- Comprehensive bridge tests:
   - Uses Mockito/MockK for Android BLE mocking
   - Tests start/stop lifecycle
   - Tests callback routing between components
   - Tests deduplication, MAC rotation, address changes

2. **`BleGattClientKeepaliveTest.kt` (280 lines)** -- Tests keepalive mechanism

3. **`BleOperationQueueTimeoutTest.kt` (250 lines)** -- Tests operation timeout

**Columba test dependencies:** Mockito, MockK, Robolectric (for Android context), coroutine test library.

---

## 8. Component Mapping: What to Build vs What Exists

### Components Needed for BLE Interface Port

| Component | Python Source | Columba Kotlin Reference | Existing reticulum-kt Code | Build Effort |
|-----------|-------------|--------------------------|---------------------------|-------------|
| **BLEInterface** (parent) | `BLEInterface` (2515 lines) | `KotlinBLEBridge` / `BleConnectionManager` | None | HIGH -- core orchestrator |
| **BLEPeerInterface** (spawned) | `BLEPeerInterface` (135 lines) | N/A (Columba uses Python's) | None | LOW -- thin wrapper |
| **BLEFragmenter** | `BLEFragmenter` (175 lines) | N/A (Python handles fragmentation) | None | LOW -- straightforward port |
| **BLEReassembler** | `BLEReassembler` (270 lines) | N/A (Python handles) | None | MEDIUM -- state management |
| **DiscoveredPeer** | `DiscoveredPeer` (82 lines) | `BleDevice` (101 lines) | None | LOW -- data class |
| **BLE GATT Client** | N/A (driver handles) | `BleGattClient` (1225 lines) | `BluetoothLeConnection` (435 lines) | MEDIUM -- extend existing |
| **BLE GATT Server** | N/A (driver handles) | `BleGattServer` (1088 lines) | None | HIGH -- new Android code |
| **BLE Scanner** | N/A (driver handles) | `BleScanner` (446 lines) | Partial in `BluetoothLeConnection` | MEDIUM -- extract and extend |
| **BLE Advertiser** | N/A (driver handles) | `BleAdvertiser` (465 lines) | None | MEDIUM -- new Android code |
| **Operation Queue** | N/A | `BleOperationQueue` (498 lines) | CountDownLatch in `BluetoothLeConnection` | MEDIUM -- coroutine-based |
| **Pairing Handler** | `BLEAgent` (Linux-specific) | `BlePairingHandler` (322 lines) | None | LOW -- BroadcastReceiver |
| **Constants** | Scattered in `BLEInterface` | `BleConstants` (225 lines) | None | LOW -- port constants |
| **State Model** | Implicit in `BLEInterface` | `BleConnectionState` (59 lines) | None | LOW -- sealed class |

### Key Architecture Decision: Where Does Fragmentation Live?

**Python approach:** Fragmentation is in `BLEInterface` (Python layer), Kotlin is pure transport.

**For reticulum-kt:** Since we are NOT using Python, fragmentation MUST be in Kotlin. Two options:

1. **In rns-interfaces module** (platform-agnostic, testable on JVM):
   - `BLEFragmenter.kt` and `BLEReassembler.kt`
   - Can be unit-tested without Android
   - BLEInterface orchestrates fragmentation

2. **In BLE driver/bridge layer** (Android-specific):
   - Fragment before sending via GATT
   - Reassemble after receiving from GATT
   - Tied to Android BLE stack

**Recommendation:** Option 1 -- put fragmentation in `rns-interfaces` alongside `BLEInterface`. This follows the Python pattern where fragmentation is at the interface level, not the driver level. It also enables JVM-level unit testing.

### Key Architecture Decision: Driver Abstraction?

**Python has:** `BLEDriverInterface` (ABC) -> `LinuxBluetoothDriver` (concrete)

**For reticulum-kt on Android:**

- We only target Android, so a driver abstraction provides less benefit
- However, it cleanly separates `rns-interfaces` (pure JVM) from `rns-sample-app` (Android)
- The BLE Android code (GATT client/server/scanner/advertiser) should live in the app or an Android library module
- BLEInterface in `rns-interfaces` should define a driver/callback contract

**Recommendation:** Define a `BLEDriver` interface in `rns-interfaces` (pure JVM). Implement `AndroidBLEDriver` in the Android app. This mirrors the `InputStream`/`OutputStream` abstraction used by RNodeInterface.

---

## 9. Critical Differences: Python vs Kotlin Port

### Things That Change

| Aspect | Python (ble-reticulum) | Kotlin (reticulum-kt) |
|--------|----------------------|----------------------|
| Threading | Python threads + asyncio event loop | Coroutines (Dispatchers.IO, Default) |
| Locks | `threading.Lock`, `threading.RLock` | `Mutex` (coroutine), `synchronized` (JVM) |
| Timers | `threading.Timer` for periodic cleanup | `CoroutineScope.launch { delay(); ... }` |
| GATT operations | bleak (central) + bluezero (peripheral) | Android BLE API directly |
| D-Bus agent | BlueZ D-Bus agent for pairing | BroadcastReceiver for ACTION_PAIRING_REQUEST |
| BLE advertising | bluezero peripheral | BluetoothLeAdvertiser API |
| Configuration | Reticulum config file (.ini) | StoredInterfaceConfig (DataStore) |
| Interface registration | RNS.Transport.interfaces list | Transport.registerInterface() / InterfaceAdapter |
| Stream vs Fragment | Fragment-level data passing | Same (fragment-level, not stream-level) |

### Things That Stay the Same

| Aspect | Details |
|--------|---------|
| Protocol v2.2 | Service UUID, char UUIDs, identity handshake, MAC sorting |
| Fragmentation format | 5-byte header: type(1) + seq(2) + total(2), big-endian |
| Fragment types | 0x01=START, 0x02=CONTINUE, 0x03=END |
| Identity-based keying | Fragmenters/reassemblers keyed by identity hash, not MAC |
| Peer scoring algorithm | RSSI (60%) + History (30%) + Recency (10%) = 0-145 |
| Connection limits | Default 7 max peers |
| Keepalive | 15-second interval for Android supervision timeout |
| BLEPeerInterface pattern | Spawned per-peer interface registered with Transport |
| MAC rotation handling | Identity cache, pending detach with grace period |

---

## 10. Summary: Recommended Port Strategy

### What to copy from Columba directly (adapt, not wholesale copy):
1. **BleConstants.kt** -- UUIDs, timeouts, limits (nearly identical)
2. **BleDevice.kt** -- data model with scoring (adapt to non-Parcelable if JVM)
3. **BleConnectionState.kt** -- sealed class state machine
4. **BleOperationQueue.kt** -- serial GATT operations (critical for Android)
5. **BlePairingHandler.kt** -- auto-pairing BroadcastReceiver

### What to port from Python (protocol logic):
1. **BLEFragmenter** / **BLEReassembler** -- from `BLEFragmentation.py` (pure logic, easy port)
2. **BLEPeerInterface** -- from `BLEInterface.py` line 2380-2515 (thin wrapper)
3. **Peer scoring algorithm** -- from `BLEInterface._score_peer()` lines 1534-1599
4. **Identity handshake protocol** -- from `BLEInterface._handle_identity_handshake()` lines 1174-1279
5. **MAC sorting logic** -- from BLE_PROTOCOL_v2.2.md (simple string comparison)
6. **Connection lifecycle** -- discovery -> connect -> identity exchange -> spawn interface -> fragment/reassemble -> route

### What to build new:
1. **BLEInterface.kt** in `rns-interfaces` -- orchestrator extending `Interface`
2. **BLEDriver interface** in `rns-interfaces` -- abstract callback contract
3. **AndroidBLEDriver** in `rns-sample-app` -- implements BLEDriver using Android BLE APIs
4. **BLE GATT Server** -- new for Android (follow Columba's `BleGattServer` pattern)
5. **BLE Advertiser** -- new for Android (follow Columba's `BleAdvertiser` pattern)
6. **Integration into InterfaceManager** -- add BLE Interface type

### Estimated total new/ported code:
- `rns-interfaces` module: ~1,500-2,000 lines (BLEInterface, BLEPeerInterface, fragmentation, driver contract)
- `rns-sample-app` module: ~3,000-4,000 lines (AndroidBLEDriver, GATT client/server, scanner, advertiser, operation queue, pairing)
- Tests: ~2,000-3,000 lines (fragmentation unit tests, interface lifecycle tests, mock driver tests)
