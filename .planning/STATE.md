# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-06)

**Core value:** BLE mesh networking — Kotlin implementation of the BLE Interface protocol for peer-to-peer Reticulum over Bluetooth Low Energy
**Current focus:** v3 BLE Interface

## Current Position

Phase: 18 of 22 (Fragmentation and Driver Contract)
Plan: 01 of 02 in phase
Status: In progress
Last activity: 2026-02-06 - Completed 18-01-PLAN.md (BLE Driver Contract)

Progress: v3 [██░░░░░░░░░░] 10%

## Milestone Goals

**v3: BLE Interface**

5 phases (18-22) delivering BLE mesh networking:
- Phase 18: Fragmentation and Driver Contract (wire format, module boundary) -- plan 01/02 complete
- Phase 19: GATT Server and Advertising (peripheral role)
- Phase 20: GATT Client and Scanner (central role)
- Phase 21: BLEInterface Orchestration (MAC sorting, identity, dual-role, Transport)
- Phase 22: Hardening and Edge Cases (zombie detection, blacklisting, dedup)

## Performance Metrics

**Velocity:**
- Total plans completed: 1 (v3)
- 18-01: 3min (BLE driver contract)

**Historical (v2):**
- 22 plans in ~58 minutes
- Average duration: 2.4min

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

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

Last session: 2026-02-06T18:29:27Z
Stopped at: Completed 18-01-PLAN.md (BLE Driver Contract)
Resume file: None
