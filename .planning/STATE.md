# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-06)

**Core value:** BLE mesh networking — Kotlin implementation of the BLE Interface protocol for peer-to-peer Reticulum over Bluetooth Low Energy
**Current focus:** v3 BLE Interface

## Current Position

Phase: 21 of 22 (BLEInterface Orchestration)
Plan: 02 of 02 in phase
Status: Phase complete
Last activity: 2026-02-06 - Completed 21-02-PLAN.md (InterfaceManager BLE integration)

Progress: v3 [██████████░░] 80%

## Milestone Goals

**v3: BLE Interface**

5 phases (18-22) delivering BLE mesh networking:
- Phase 18: Fragmentation and Driver Contract (wire format, module boundary) -- COMPLETE (2/2 plans)
- Phase 19: GATT Server and Advertising (peripheral role) -- COMPLETE (2/2 plans)
- Phase 20: GATT Client and Scanner (central role) -- COMPLETE (2/2 plans)
- Phase 21: BLEInterface Orchestration (MAC sorting, identity, dual-role, Transport) -- COMPLETE (2/2 plans)
- Phase 22: Hardening and Edge Cases (zombie detection, blacklisting, dedup)

## Performance Metrics

**Velocity:**
- Total plans completed: 8 (v3)
- 18-01: 3min (BLE driver contract)
- 18-02: 4min (BLE fragmentation and reassembly)
- 19-01: 3min (GATT server implementation)
- 19-02: 3min (BLE advertising and operation queue)
- 20-01: 3min (BLE scanner and GATT client)
- 20-02: 2min (AndroidBLEDriver and internal visibility)
- 21-01: 3min (BLEInterface and BLEPeerInterface orchestration)
- 21-02: 2min (InterfaceManager BLE integration and manifest permissions)

**Historical (v2):**
- 22 plans in ~58 minutes
- Average duration: 2.4min

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [21-02]: Fully-qualified type names for AndroidBLEDriver/BLEInterface in BLE case (single-use types, no imports)
- [21-02]: Explicit BLUETOOTH_ADVERTISE in sample app manifest despite rns-android merger
- [21-02]: No stopInterface() change needed -- else -> detach() fallback covers BLEInterface
- [21-01]: No downcast to AndroidBLEDriver -- caller sets identity on driver before construction
- [21-01]: No restartAdvertisingIfNeeded() call -- BleAdvertiser 60s refresh is sufficient
- [21-01]: Optimistic connect then sort -- deduplicate by identity after handshake
- [21-01]: runBlocking(Dispatchers.IO) bridge for processOutgoing sync-to-async
- [21-01]: shouldInitiateConnection reserved for Phase 22 dedup enhancements
- [20-02]: runBlocking for MTU property getter (quick Mutex-protected map lookup, acceptable)
- [20-02]: Private inner class for AndroidBLEPeerConnection (access to enclosing driver components)
- [20-02]: Shared CoroutineScope passed to all BLE components for coordinated lifecycle
- [20-01]: Single long-running scan (no cycling) -- BLE chip handles power management via ScanMode
- [20-01]: Per-device 3s throttle for scan callbacks
- [20-01]: Error 133: gatt.close() only (no disconnect first) + exponential backoff retry
- [20-01]: Connection timeout via coroutine Job (not operationQueue)
- [19-02]: Channel-based BleOperationQueue (single consumer) over Mutex-based serialization
- [19-02]: No scan response data in advertising (CONTEXT.md: service UUID only)
- [19-02]: Linear backoff (2s, 4s, 6s) for advertising retry
- [19-01]: SharedFlow events over nullable callbacks for multi-collector support
- [19-01]: Single stateMutex for all per-device maps (connect/disconnect modify all maps together)
- [19-01]: Global notification Mutex (Android BLE stack serializes globally, not per-device)
- [19-01]: No keepalive/identity parsing in GATT server (delegates to Phase 21 BLEInterface)
- [19-01]: Per-device CCCD tracking in map (not descriptor.value like Columba)
- [18-02]: Constants delegated to BLEConstants; BLEFragmenter.TYPE_START etc. are public API aliases
- [18-02]: Throw on MTU < 6 instead of Python's clamping to 20 -- Kotlin convention for invalid construction
- [18-02]: @Synchronized over coroutine locks for GATT callback thread safety
- [18-01]: Normalized scoring [0,1] for DiscoveredPeer instead of Python's absolute [0,145]
- [18-01]: Exponential recency decay (60s half-life) instead of Python's linear decay
- [18-01]: Added forward-looking constants (CCCD_UUID, MAX_MTU, MAX_CONNECTIONS) to BLEConstants
- [v3]: Architecture: BLEDriver interface in rns-interfaces (pure JVM), AndroidBLEDriver in rns-sample-app
- [v3]: No streams: BLE is message-based, not stream-based (unlike RNode PipedInputStream pattern)
- [v3]: Server before client: GATT server is higher risk (entirely new), de-risk early
- [v2]: Deferred OEM Compatibility (Phase 16) and Memory Optimization (Phase 17)
- [v2]: flowControl=false for BLE RNode connections
- [v2]: txLock serialization for BLE TX

### From v2

Codebase ready for BLE interface work:
- Working RNode BLE connection (BluetoothLeConnection.kt) with NUS service
- InterfaceManager handles BLE lifecycle (connect, disconnect, cleanup)
- GATT callback infrastructure with MTU negotiation
- BLE device picker UI in RNode wizard

### From 18-01 (BLE Driver Contract)

BLE protocol abstractions established:
- `BLEConstants` -- all protocol UUIDs, fragment header, MTU, keepalive, timeouts
- `BLEDriver` -- message-based BLE abstraction with SharedFlow events
- `BLEPeerConnection` -- per-connection fragment send/receive and identity handshake
- `DiscoveredPeer` -- data class with weighted scoring (RSSI 60% + history 30% + recency 10%)
- Package: `network.reticulum.interfaces.ble` in rns-interfaces module

### From 18-02 (BLE Fragmentation and Reassembly)

Fragment protocol fully implemented and tested:
- `BLEFragmenter` -- splits packets into fragments with 5-byte big-endian headers
- `BLEReassembler` -- reconstructs from ordered/out-of-order fragments with timeout cleanup
- Wire format byte-identical to Python BLEFragmentation.py (verified by 44 unit tests)
- Thread-safe via @Synchronized; no Android dependencies
- Constants delegate to BLEConstants for single source of truth

### From 19-01 (GATT Server Implementation)

GATT server fully implemented in rns-android module:
- `BleGattServer` -- hosts Reticulum GATT service with RX/TX/Identity characteristics
- All 8 GATT callbacks dispatch to coroutine scope (avoids blocking binder thread)
- Notification serialization via global Mutex + CompletableDeferred
- Per-device MTU (mtu-3), CCCD subscription, and device tracking in Mutex-protected maps
- API 33 compat for notifyCharacteristicChanged (4-param vs deprecated 3-param)
- SharedFlow events: centralConnected, centralDisconnected, dataReceived, mtuChanged
- AndroidManifest.xml has all 6 BLE permissions + hardware feature declaration
- Package: `network.reticulum.android.ble` in rns-android module

### From 19-02 (BLE Advertising and Operation Queue)

BLE advertising and operation serialization:
- `BleAdvertiser` -- advertising lifecycle with service UUID, 60s refresh, OEM restart
- `BleOperationQueue` -- pure JVM generic suspend-based operation queue (Channel + single consumer)
- `BleOperationTimeoutException` -- timeout exception for queue operations
- AdvertiseMode enum: LOW_POWER, BALANCED, LOW_LATENCY (user-configurable)
- 8 unit tests for queue serialization, timeout, exception propagation, concurrent access

### From 20-01 (BleScanner and BleGattClient)

BLE central-role components implemented in rns-android module:
- `BleScanner` -- hardware UUID ScanFilter, single long-running scan, per-device 3s throttle
- `BleGattClient` -- connect/disconnect, service discovery, MTU 517, CCCD, data send/receive
- All GATT operations serialized via `BleOperationQueue` with `CompletableDeferred` bridge
- GATT error 133: full close + fresh `connectGatt` with exponential backoff (1s-16s), 5 retries
- Temporary 60-second blacklist after max retries
- API 33 compat for writeCharacteristic/writeDescriptor and pre-API 33 callback overloads
- SharedFlow events: discoveredPeers, connected, disconnected, connectionFailed, dataReceived, mtuChanged
- Package: `network.reticulum.android.ble` in rns-android module

### From 20-02 (AndroidBLEDriver and Internal Visibility)

AndroidBLEDriver facade completing the BLE transport layer:
- `AndroidBLEDriver` -- concrete BLEDriver implementation aggregating all BLE components
- `AndroidBLEPeerConnection` -- private inner class wrapping outgoing/incoming connections
- Event aggregation: 6 coroutine collectors route component events to BLEDriver flows
- All BLE components (BleGattServer, BleAdvertiser, BleScanner, BleGattClient) marked `internal`
- Only AndroidBLEDriver is public API from `network.reticulum.android.ble` package
- Phase 21 API: setTransportIdentity(), getPeerConnection(), restartAdvertisingIfNeeded()

### From 21-01 (BLEInterface Orchestration)

BLE mesh orchestration layer complete:
- `BLEInterface` -- server-style parent: dual-role startup, discovery, identity handshake, peer spawning
- `BLEPeerInterface` -- per-peer child: fragmentation, reassembly, keepalive, Transport integration
- Identity handshake: central reads Identity char, writes own identity to RX (30s timeout)
- Duplicate identity detection: keeps newest connection, tears down oldest
- Blacklist (60s) on handshake timeout, reconnection backoff (7s) on connection failure
- processOutgoing no-op on parent; runBlocking bridge on child for sync-to-async
- Pure JVM: no Android imports, depends only on BLEDriver/BLEPeerConnection interfaces
- Package: `network.reticulum.interfaces.ble` in rns-interfaces module

### From 21-02 (InterfaceManager BLE Integration)

App-level BLE wiring complete:
- InterfaceManager BLE case: creates AndroidBLEDriver + BLEInterface on Dispatchers.IO
- Transport identity set on driver BEFORE BLEInterface.start() (identity handshake prerequisite)
- Async launch + null return pattern (same as RNODE case)
- BLUETOOTH_ADVERTISE permission added to sample app manifest
- All 3 BLE runtime permissions declared: SCAN, CONNECT, ADVERTISE
- stopInterface() covered by existing else -> detach() fallback

### Research Completed

4 research documents produced (`.planning/research/v3/`):
- protocol.md — Full wire format, handshake, fragmentation, MAC sorting
- android-ble.md — Android BLE stack capabilities, GATT server, dual-role
- existing-code.md — Python/Kotlin code inventory, reuse assessment
- architecture.md — Module boundaries, concurrency model, testing strategy
- SUMMARY.md — Synthesized findings, scope estimates, phase recommendations

### Pending Todos

None yet.

### Blockers/Concerns

None yet.

## Session Continuity

Last session: 2026-02-06T23:29:00Z
Stopped at: Phase 21 complete — all 2 plans done (BLEInterface + InterfaceManager integration)
Resume file: None
