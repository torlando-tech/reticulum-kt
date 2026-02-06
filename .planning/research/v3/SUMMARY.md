# BLE Mesh Interface Research Summary

**Project:** Reticulum-KT BLE Mesh Interface (v3)
**Domain:** BLE peer-to-peer mesh networking on Android
**Researched:** 2026-02-06
**Confidence:** HIGH

## Executive Summary

The BLE mesh interface for Reticulum-KT is a dual-role (central + peripheral) BLE networking layer that enables phone-to-phone communication without any infrastructure. The protocol is well-specified: a custom GATT service with three characteristics (RX, TX, Identity), a 16-byte identity handshake, 5-byte fragment headers, MAC-address-based connection direction sorting, and 15-second keepalives. Two complete reference implementations exist -- Python (`ble-reticulum`, 6,736 lines) and Kotlin/Android (`columba`, 7,927 lines) -- providing high confidence in both the protocol and Android-specific implementation patterns.

The recommended approach is a clean two-layer architecture: protocol logic (fragmentation, reassembly, peer scoring, handshake state machine) in the pure-JVM `rns-interfaces` module behind a `BLEDriver` abstraction interface, with the Android BLE implementation (`AndroidBLEDriver`) in `rns-sample-app`. This mirrors the existing RNode pattern but replaces stream-based I/O with message-based fragment passing. The Columba codebase provides directly adaptable Android BLE patterns (GATT server, advertiser, operation queue, pairing handler), while the Python codebase provides the authoritative protocol specification.

The primary risks are Android BLE stack unreliability (GATT error 133, scan throttling, per-device behavioral differences) and the inherent complexity of dual-role operation (simultaneous scanning, advertising, GATT client, and GATT server). These are well-documented and mitigable: serial GATT operation queues, identity-based peer keying (survives MAC rotation), exponential backoff for failed peers, and a single long-running scan instead of cycling. The total estimated scope is ~2,100 lines of production code plus ~2,500 lines of tests.

## Key Findings

### Protocol Specification

- **GATT Service UUID:** `37145b00-442d-4a94-917f-8f42c5da28e3` with three characteristics: RX (write), TX (notify), Identity (read-only, 16 bytes)
- **Connection direction:** Lower MAC address acts as central (initiates), higher MAC waits as peripheral. v0.3.0 adds capability flags for peripheral-only devices
- **Identity handshake:** Central reads peripheral's Identity characteristic, then writes its own 16-byte identity to RX as the first packet. Both sides now have stable identity, immune to MAC rotation
- **Fragment header:** 5 bytes big-endian -- `[type:1][sequence:2][total:2]` where type is START(0x01)/CONTINUE(0x02)/END(0x03). Always present, even on single-fragment packets
- **Keepalive:** Single byte `0x00` every 15 seconds. Required on Android to prevent BLE supervision timeout (status 8)
- **Message disambiguation:** 1 byte + 0x00 = keepalive; 16 bytes + no stored identity = handshake; 5+ bytes with valid type = fragment
- **MTU:** Request 517, accept negotiated value. Fragment payload = MTU - 5 bytes. Default fallback: 185

### Architecture Recommendation

- **Module split:** Protocol logic in `rns-interfaces/ble/` (pure JVM), Android BLE in `rns-sample-app/service/ble/`
- **Driver abstraction:** `BLEDriver` interface in rns-interfaces; `AndroidBLEDriver` implementation injected by InterfaceManager
- **Transport integration:** `BLEInterface` acts as orchestrator (like TCPServerInterface). Only spawned `BLEPeerInterface` instances register with Transport
- **Concurrency:** Single `CoroutineScope(SupervisorJob + IO)` per BLEInterface. Per-peer `Mutex` for send serialization. GATT callbacks dispatch immediately to coroutines
- **No streams:** BLE is message-based, not stream-based. Do NOT use the PipedInputStream/OutputStream pattern from RNode

### Reuse Assessment

**Directly portable from Columba (adapt, not copy):**
- `BleConstants.kt` -- UUIDs, timeouts, limits (nearly identical values)
- `BleOperationQueue.kt` -- serial GATT operation execution (critical for Android reliability)
- `BlePairingHandler.kt` -- auto-pairing BroadcastReceiver
- `BleConnectionState.kt` -- sealed class state machine
- `BleDevice.kt` -- data model with scoring

**Port from Python (protocol logic):**
- `BLEFragmenter` / `BLEReassembler` from `BLEFragmentation.py` (pure computation, straightforward port)
- Identity handshake protocol from `BLEInterface._handle_identity_handshake()`
- MAC sorting from `BLEInterface.py:1718-1738`
- Peer scoring algorithm: RSSI (60%) + history (30%) + recency (10%)

**Reuse from existing reticulum-kt:**
- `BluetoothLeConnection.kt` -- GATT connection flow, MTU negotiation, write latch pattern (partial reuse; need to drop stream bridge, add multi-peer support)
- `InterfaceManager.kt` -- add BLE case alongside existing RNode/TCP types
- `Interface.kt` base class -- direct inheritance as with all interfaces
- CoroutineScope patterns from `TCPServerInterface` / `RNodeInterface`

**Build new:**
- `BLEInterface.kt` -- orchestrator (discovery loop, peer management, spawned interface lifecycle)
- `BLEPeerInterface.kt` -- per-peer interface with fragment send/receive
- `AndroidBLEDriver.kt` -- implements BLEDriver using Android APIs
- `BleGattServer.kt` -- peripheral mode GATT server (entirely new for this codebase)
- `BleAdvertiser.kt` -- BLE advertising with identity and capability flags
- `BleScanner.kt` -- multi-device discovery with adaptive intervals

### Critical Pitfalls

1. **GATT error 133 (Android catch-all):** Most common BLE failure. Caused by connection limit exceeded, pipelined operations, or stack corruption. **Prevention:** Serial GATT operations via operation queue, retry with exponential backoff, 100-200ms delay between close() and reconnect
2. **Scan throttling (5 starts per 30 seconds):** 6th `startScan()` silently returns zero results with no error. **Prevention:** Start one long-running scan with ScanFilter for service UUID. Never cycle scans for discovery
3. **Missing sendResponse() on GATT server:** If `responseNeeded=true` and server does not call `sendResponse()`, client hangs and disconnects with GATT 133. **Prevention:** Always check `responseNeeded` in every write/read/descriptor callback
4. **Pipelined GATT writes (silent data loss):** Android BLE has no internal queue. Writing without waiting for callback silently drops the second write. **Prevention:** Kotlin Mutex or Channel-based serial execution for all GATT operations
5. **MAC address as peer key (breaks on rotation):** Android rotates BLE MAC every ~15 minutes. **Prevention:** All data structures keyed by 16-byte Reticulum identity hash, not MAC address. Identity learned via GATT characteristic + handshake

## Estimated Scope

### Production Code (~2,100 lines)

| Component | Module | Lines | Complexity |
|-----------|--------|-------|------------|
| `BLEFragmentation.kt` | rns-interfaces | ~250 | Low (direct Python port) |
| `BLEDriver.kt` | rns-interfaces | ~80 | Low (interface definitions) |
| `BLEConstants.kt` | rns-interfaces | ~30 | Trivial |
| `DiscoveredPeer.kt` | rns-interfaces | ~120 | Low (data class + scoring) |
| `BLEInterface.kt` | rns-interfaces | ~350 | Medium (discovery, peer mgmt) |
| `BLEPeerInterface.kt` | rns-interfaces | ~200 | Medium (fragment lifecycle) |
| `AndroidBLEDriver.kt` | rns-sample-app | ~150 | Medium (delegation) |
| `BleScanner.kt` | rns-sample-app | ~150 | Medium |
| `BleGattClient.kt` | rns-sample-app | ~300 | High (GATT callbacks, MTU) |
| `BleGattServer.kt` | rns-sample-app | ~300 | High (server + notifications) |
| `BleOperationQueue.kt` | rns-sample-app | ~150 | Medium |

### Test Code (~2,500 lines)

- Fragmentation unit tests (pure JVM): ~400 lines
- Reassembly unit tests (pure JVM, including timeout/corruption): ~500 lines
- BLEInterface integration tests with MockBLEDriver (pure JVM): ~800 lines
- MAC sorting and peer scoring tests (pure JVM): ~300 lines
- Loopback integration test (two BLEInterfaces connected): ~500 lines

## Roadmap Implications

### Phase 1: Fragmentation and Driver Contract
**Rationale:** The fragment protocol is the foundation. Everything else depends on being able to split, send, and reassemble packets. The driver interface defines the module boundary that all subsequent phases build against.
**Delivers:** `BLEFragmentation.kt`, `BLEDriver.kt`, `BLEConstants.kt`, `DiscoveredPeer.kt`, full JVM unit tests
**Addresses:** Wire-format compatibility with Python ble-reticulum
**Avoids:** Premature coupling to Android APIs
**Estimated effort:** Small (the fragment protocol is well-specified, pure computation)

### Phase 2: GATT Server and Advertising (Peripheral Role)
**Rationale:** The GATT server is entirely new code with no existing equivalent in this codebase. It has the most Android-specific pitfalls (sendResponse, notification serialization, CCCD tracking, per-device MTU). Building it second allows the driver contract from Phase 1 to inform its design.
**Delivers:** `BleGattServer.kt`, `BleAdvertiser.kt`, `BleOperationQueue.kt`, Identity characteristic hosting
**Addresses:** Peripheral mode -- being discoverable and accepting incoming connections
**Avoids:** GATT error 133 via operation queue, missing sendResponse via callback discipline

### Phase 3: GATT Client and Scanner (Central Role)
**Rationale:** Adapts the existing `BluetoothLeConnection.kt` patterns. The scanner and GATT client have partial implementations already. Building after the server means we can loopback-test central against the Phase 2 peripheral.
**Delivers:** `BleGattClient.kt`, `BleScanner.kt`, `AndroidBLEDriver.kt` (complete), MTU negotiation, identity handshake (central side)
**Addresses:** Central mode -- discovering and connecting to nearby peers
**Avoids:** Scan throttling via single long-running scan, write serialization via operation queue

### Phase 4: BLEInterface Orchestration and Transport Integration
**Rationale:** This is the integration phase. The orchestrator wires discovery, connection direction (MAC sorting), identity exchange, peer lifecycle, and spawned interface registration into a coherent whole. Requires all prior phases.
**Delivers:** `BLEInterface.kt`, `BLEPeerInterface.kt`, InterfaceManager integration, dual-role orchestration
**Addresses:** Full mesh: scan + advertise + connect + accept + route packets
**Avoids:** Peer affinity via discovery-loop-with-scoring (not explicit reconnect), MAC rotation via identity-based keying

### Phase 5: Hardening and Edge Cases
**Rationale:** The "last 20% that takes 80% of the time." MAC rotation, zombie connection detection, dual-connection deduplication, blacklisting with exponential backoff, connection pool rotation, graceful degradation at connection limit.
**Delivers:** Production-quality resilience, Android-specific workarounds, on-device testing
**Addresses:** Real-world reliability under adverse conditions
**Avoids:** All remaining pitfalls from research

### Phase Ordering Rationale

- Phases 1-3 are dependency-ordered: fragmentation is foundational, server and client are independent but both need the driver contract, and the orchestrator needs both roles
- Phase 2 before Phase 3 because the GATT server is entirely new (higher risk, more unknowns) while the GATT client adapts existing code (lower risk). Getting the harder piece done earlier de-risks the project
- Phase 4 is a natural integration point where on-device testing becomes meaningful for the first time
- Phase 5 is a hardening pass that should happen after basic functionality is proven

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 2 (GATT Server):** Advertising continuation after connection varies by chipset. Must test on real devices (Pixel, Samsung, budget). The 60-second advertising refresh workaround from Columba may or may not be needed
- **Phase 4 (Orchestration):** Practical connection limit under dual-role load is unknown. The 7-connection AOSP limit may effectively halve when running both server and client. Needs empirical testing
- **Phase 5 (Hardening):** Android 14 GATT 133 persistent state issue on tablets. MAC rotation timing across different OEMs

Phases with standard patterns (skip deep research):
- **Phase 1 (Fragmentation):** Completely specified binary protocol, direct port from Python, zero ambiguity
- **Phase 3 (GATT Client):** Well-trodden path, existing code to adapt, Columba provides patterns

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Protocol spec | HIGH | Derived from reading actual source code of two implementations |
| Android BLE APIs | HIGH | Official documentation, well-tested patterns in Columba |
| Architecture | HIGH | Follows established patterns already in this codebase (TCPServer, RNode) |
| Pitfalls | HIGH | Well-documented across community, confirmed in Columba and ble-reticulum |
| Scope estimate | MEDIUM | Component sizing based on reference implementations, but integration work often surprises |
| Device compatibility | MEDIUM | Known to work on Pixel/Samsung (Columba), untested on budget devices |

**Overall confidence:** HIGH

### Gaps to Address

- **Advertising persistence across OEMs:** Some devices stop advertising after connection. Must test and implement restart-on-connect workaround if needed. Handle during Phase 2
- **Practical multi-peer throughput:** With 4-5 peers connected, per-peer throughput is unknown. BLE radio time-sharing degrades with connection count. Measure during Phase 4
- **v0.3.0 capability flags on Android:** The `addManufacturerData()` API excludes the Company ID from the byte array. Must verify 2-byte format works for peer detection. Handle during Phase 2
- **Connection limit under dual-role:** Whether server + client connections share the 7-connection budget or have independent budgets is chipset-dependent. Test during Phase 4

## Sources

### Primary (HIGH confidence)
- `~/repos/public/ble-reticulum/` -- Python BLE mesh reference implementation (6,736 lines + 12,421 test lines)
- `~/repos/public/columba/reticulum/src/main/java/.../ble/` -- Kotlin Android BLE implementation (7,927 lines + 4,839 test lines)
- `BLE_PROTOCOL_v2.2.md` -- Authoritative protocol specification
- Android AOSP BLE documentation -- GATT server, advertiser, scanner APIs
- This codebase (`rns-interfaces/`, `rns-sample-app/`) -- existing Interface patterns, TCPServer spawned interface model

### Secondary (MEDIUM confidence)
- Nordic Semiconductor DevZone -- Android 14 GATT 133 issues, connection limits
- Punch Through BLE guides -- Write patterns, scan throttling, throughput optimization
- Google Issue Tracker -- MTU 517 auto-negotiation on Android 14+

---
*Research completed: 2026-02-06*
*Ready for roadmap: yes*
