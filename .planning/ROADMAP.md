# Roadmap: Reticulum-KT v3

## Milestones

- v1.0 LXMF Interoperability - Phases 1-9 (shipped 2026-01-24)
- v2.0 Android Production Readiness - Phases 10-15 (shipped 2026-02-06, Phases 16-17 deferred)
- v3.0 BLE Interface - Phases 18-22 (in progress)

## Overview

This milestone delivers a complete BLE mesh interface for peer-to-peer Reticulum communication between Android devices. Starting with the fragment protocol and driver abstraction, we progressively build the GATT server (peripheral), GATT client (central), orchestration layer (MAC sorting, identity handshake, dual-role), and production hardening (zombie detection, blacklisting, deduplication). Each phase builds on the previous to create a reliable BLE mesh that interoperates with the Python ble-reticulum reference implementation.

## Phases

<details>
<summary>v1.0 LXMF Interoperability (Phases 1-9) - SHIPPED 2026-01-24</summary>

See `.planning/milestones/v1-ROADMAP.md` for archived roadmap.

**Summary:** 25 plans across 10 phases delivered complete LXMF interoperability with 120+ tests verifying byte-level compatibility with Python LXMF.

</details>

<details>
<summary>v2.0 Android Production Readiness (Phases 10-15) - SHIPPED 2026-02-06</summary>

See `.planning/milestones/v2-ROADMAP.md` for archived roadmap.

**Summary:** 22 plans across 6 phases delivered production-ready Android background connectivity: foreground service, Doze-aware connections, WorkManager, notification UX, battery optimization. Phases 16-17 deferred.

</details>

### v3.0 BLE Interface (In Progress)

**Milestone Goal:** BLE mesh networking — peer-to-peer Reticulum over Bluetooth Low Energy, wire-compatible with Python ble-reticulum

**Phase Numbering:** Continues from v2 (phases 18-22)

- [x] **Phase 18: Fragmentation and Driver Contract** - Wire format, reassembly, BLEDriver interface
- [ ] **Phase 19: GATT Server and Advertising** - Peripheral role, characteristic hosting, notifications
- [ ] **Phase 20: GATT Client and Scanner** - Central role, service discovery, MTU negotiation
- [ ] **Phase 21: BLEInterface Orchestration** - MAC sorting, identity handshake, dual-role, Transport integration
- [ ] **Phase 22: Hardening and Edge Cases** - Zombie detection, blacklisting, deduplication, peer scoring

## Phase Details

### Phase 18: Fragmentation and Driver Contract

**Goal**: Establish the wire format and module boundary that all subsequent phases build against
**Depends on**: Nothing (first v3 phase)
**Requirements**: FRAG-01..08, DRV-01..04
**Research flags**: Standard patterns, skip phase research. Protocol completely specified in BLE_PROTOCOL_v2.2.md and BLEFragmentation.py.
**Success Criteria** (what must be TRUE):
  1. BLEFragmenter splits packets into fragments with 5-byte header [type:1][seq:2][total:2] big-endian
  2. Fragment types: START(0x01), CONTINUE(0x02), END(0x03) — single-fragment packets use START
  3. BLEReassembler reconstructs packets from fragments, including out-of-order
  4. Reassembler times out incomplete packets after 30 seconds
  5. BLEDriver interface in rns-interfaces defines message-based contract (no Android imports)
  6. DiscoveredPeer data class with identity, address, RSSI, scoring
  7. Wire format byte-identical to Python BLEFragmentation.py (verified by unit tests)
  8. All tests run on JVM without Android dependencies

**Deliverables:** `BLEFragmentation.kt`, `BLEDriver.kt`, `BLEConstants.kt`, `DiscoveredPeer.kt` in `rns-interfaces/ble/`
**Plans:** 2 plans

Plans:
- [x] 18-01-PLAN.md -- Constants, BLEDriver interface, and DiscoveredPeer data class
- [x] 18-02-PLAN.md -- BLE fragmentation and reassembly (TDD)

### Phase 19: GATT Server and Advertising

**Goal**: Be discoverable and accept incoming BLE connections as peripheral
**Depends on**: Phase 18 (uses BLEDriver contract, BLEConstants)
**Requirements**: GATT-01..07, ADV-01..03
**Research flags**: Needs phase research for advertising persistence across OEMs and CCCD tracking patterns.
**Success Criteria** (what must be TRUE):
  1. GATT server hosts service UUID 37145b00-442d-4a94-917f-8f42c5da28e3
  2. RX characteristic accepts WRITE and WRITE_WITHOUT_RESPONSE
  3. TX characteristic supports NOTIFY with CCCD descriptor
  4. Identity characteristic serves 16-byte Transport.identity.hash (read-only)
  5. sendResponse() always called when responseNeeded=true
  6. Notifications serialized via onNotificationSent callback
  7. Per-device MTU tracked from onMtuChanged
  8. BLE advertising with service UUID, restarts after connection, 60s refresh
  9. BleOperationQueue ensures serial GATT operations

**Deliverables:** `BleGattServer.kt`, `BleAdvertiser.kt` in `rns-android/ble/`; `BleOperationQueue.kt` in `rns-interfaces/ble/`
**Plans:** 2 plans

Plans:
- [ ] 19-01-PLAN.md -- BleGattServer and BLE permissions
- [ ] 19-02-PLAN.md -- BleAdvertiser, BleOperationQueue, and tests

### Phase 20: GATT Client and Scanner

**Goal**: Discover nearby peers and connect as central
**Depends on**: Phase 18 (uses BLEDriver contract), Phase 19 (loopback testing against server)
**Requirements**: CLI-01..05
**Research flags**: Standard patterns, skip phase research. Well-trodden GATT client path, existing BluetoothLeConnection.kt to adapt.
**Success Criteria** (what must be TRUE):
  1. Scanner discovers peers by service UUID filter (single long-running scan, no cycling)
  2. GATT client connects, discovers services, enables TX notifications via CCCD
  3. MTU negotiation requests 517, accepts negotiated value, reports to BLEDriver
  4. All GATT operations serialized via BleOperationQueue (shared with server)
  5. GATT error 133 triggers disconnect + exponential backoff retry
  6. AndroidBLEDriver complete: connects Phase 19 server + Phase 20 client to Phase 18 protocol

**Deliverables:** `BleGattClient.kt`, `BleScanner.kt`, `AndroidBLEDriver.kt` in `rns-sample-app/service/ble/`

### Phase 21: BLEInterface Orchestration

**Goal**: Wire discovery, connection direction, identity exchange, and peer lifecycle into a working mesh
**Depends on**: Phase 18, Phase 19, Phase 20 (requires all BLE components)
**Requirements**: ID-01..05, CONN-01..07, APP-01..03
**Research flags**: Needs research on practical connection limits under dual-role operation.
**Success Criteria** (what must be TRUE):
  1. MAC sorting: lower MAC initiates as central, higher waits as peripheral
  2. Identity handshake: central reads peripheral Identity char, writes own identity to RX
  3. Identity exchange completes before data transfer, times out at 30 seconds
  4. BLEInterface spawns BLEPeerInterface per connected peer
  5. Spawned BLEPeerInterface registered with Transport, deregistered on disconnect
  6. Keepalive byte (0x00) sent every 15 seconds per connection
  7. Dual-role: simultaneous scanning + advertising + server + client connections
  8. InterfaceManager creates BLEInterface with AndroidBLEDriver injection
  9. BLE permissions gate (SCAN, CONNECT, ADVERTISE) before starting
  10. Two devices can exchange Reticulum packets over BLE (end-to-end verified)

**Deliverables:** `BLEInterface.kt`, `BLEPeerInterface.kt` in `rns-interfaces/ble/`; InterfaceManager integration in `rns-sample-app`

### Phase 22: Hardening and Edge Cases

**Goal**: Production-quality resilience under adverse conditions
**Depends on**: Phase 21 (requires working mesh to harden)
**Requirements**: DEDUP-01..02, HARD-01..04
**Research flags**: Needs research on Android 14 GATT 133 persistent state and MAC rotation timing across OEMs.
**Success Criteria** (what must be TRUE):
  1. Zombie detection: peer unresponsive despite connected state triggers disconnect
  2. Failed peer blacklisting with exponential backoff (prevents reconnect storms)
  3. Duplicate identity connections detected and resolved (keep newest)
  4. MAC rotation does not create duplicate connections for same identity
  5. Graceful degradation at connection limit (drop lowest-scored peer)
  6. Peer scoring: RSSI (60%) + connection history (30%) + recency (10%)
  7. On-device testing confirms 2+ peers mesh correctly for >1 hour

**Deliverables:** Hardening logic in `BLEInterface.kt` and `BLEPeerInterface.kt`; on-device test scripts

## Progress

**Execution Order:** Phases 18 through 22 in sequence. Phase 19 and 20 are potentially parallelizable but server-first de-risks the harder component.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 18. Fragmentation and Driver Contract | 2/2 | Complete | 2026-02-06 |
| 19. GATT Server and Advertising | 0/2 | Planned | - |
| 20. GATT Client and Scanner | 0/TBD | Not started | - |
| 21. BLEInterface Orchestration | 0/TBD | Not started | - |
| 22. Hardening and Edge Cases | 0/TBD | Not started | - |

---

*Created: 2026-02-06*
*Milestone: v3 BLE Interface*
