---
phase: 21-ble-orchestration
plan: 01
subsystem: ble-interface
tags: [ble, mesh, interface, orchestration, identity-handshake, fragmentation, transport]
dependency_graph:
  requires: [18-01, 18-02, 19-01, 19-02, 20-01, 20-02]
  provides: [BLEInterface, BLEPeerInterface, ble-mesh-orchestration]
  affects: [22-ble-hardening]
tech_stack:
  added: []
  patterns: [server-style-parent, spawned-child-interface, identity-handshake, dual-role-ble]
key_files:
  created:
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEInterface.kt
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEPeerInterface.kt
  modified: []
decisions:
  - id: "21-01-01"
    decision: "No downcast to AndroidBLEDriver -- caller sets identity on driver before construction"
    rationale: "BLEInterface is pure JVM in rns-interfaces, cannot import AndroidBLEDriver from rns-android"
  - id: "21-01-02"
    decision: "No restartAdvertisingIfNeeded() call -- BleAdvertiser 60s refresh is sufficient"
    rationale: "Keeps BLEDriver interface minimal; advertising resilience handled at driver level"
  - id: "21-01-03"
    decision: "Optimistic connection then sort -- connect to all discovered peers, deduplicate after identity handshake"
    rationale: "Cannot know peer identity before connecting; identity hash sorting used for dedup after handshake"
  - id: "21-01-04"
    decision: "runBlocking(Dispatchers.IO) bridge for processOutgoing sync-to-async"
    rationale: "Transport calls processOutgoing synchronously; BLE send is suspend; same pattern as AndroidBLEDriver MTU getter"
  - id: "21-01-05"
    decision: "shouldInitiateConnection reserved for Phase 22 dedup enhancements"
    rationale: "Method implemented but suppressed unused warning; initial approach uses optimistic connect + dedup"
metrics:
  duration: "~3min"
  completed: "2026-02-06"
---

# Phase 21 Plan 01: BLEInterface and BLEPeerInterface Summary

**Implemented BLEInterface (server-style parent) and BLEPeerInterface (per-peer child) orchestrating BLE mesh lifecycle with dual-role discovery, identity handshake, fragmentation, keepalive, and Transport integration.**

## What Was Done

### Task 1: BLEInterface.kt
Server-style parent interface (following LocalServerInterface pattern) that orchestrates the entire BLE mesh:

- **Dual-role startup**: advertise first (peripheral), then scan after 100ms delay (central)
- **Discovery collection**: connects to discovered peers, skipping blacklisted/backoff/at-capacity
- **Identity handshake (central)**: reads Identity characteristic, writes own identity to RX
- **Identity handshake (peripheral)**: waits for 16-byte write on RX from central
- **Handshake timeout**: 30 seconds, followed by disconnect and 60s blacklist
- **Duplicate identity detection**: same identity at different address tears down old connection
- **Peer spawning**: creates BLEPeerInterface, registers with Transport via toRef()
- **Disconnection handling**: tears down peer, sets 7s reconnection backoff
- **processOutgoing()**: no-op (Transport calls spawned peers directly)
- **Periodic cleanup**: every 30s clears expired blacklist/backoff entries

### Task 2: BLEPeerInterface.kt
Per-peer child interface (following LocalClientInterface pattern) for a single connected BLE peer:

- **receiveLoop**: collects fragments from BLEPeerConnection, filters keepalives (0x00) and identity data (16 bytes), reassembles via BLEReassembler, delivers to Transport
- **processOutgoing**: fragments data via BLEFragmenter, sends via runBlocking bridge
- **keepaliveLoop**: sends 0x00 byte every 15s with grace period on failure (retry once, then disconnect)
- **updateConnection**: swaps underlying BLE connection for MAC rotation (cancel old loops, reset fragmenter/reassembler, restart)
- **Pure JVM**: no Android imports, depends only on BLEPeerConnection interface

## Decisions Made

1. **No downcast to AndroidBLEDriver** -- BLEInterface does not call setTransportIdentity. The caller (InterfaceManager) sets identity on the driver before construction. This maintains pure JVM boundary.

2. **No restartAdvertisingIfNeeded()** -- BleAdvertiser's 60-second refresh cycle handles advertising resilience. Removes need for this method on BLEDriver interface.

3. **Optimistic connect then sort** -- Cannot determine identity-based connection direction before connecting. Connect to all discovered peers, perform handshake, then deduplicate by identity.

4. **runBlocking bridge for processOutgoing** -- Transport calls processOutgoing synchronously; BLE send is suspend. runBlocking(Dispatchers.IO) bridges the gap, matching prior pattern in AndroidBLEDriver.

5. **shouldInitiateConnection reserved** -- Method exists with identity hash comparison logic but marked @Suppress("unused"). Phase 22 can enable it for smarter dedup.

## Deviations from Plan

None -- plan executed exactly as written.

## Verification

- `./gradlew :rns-interfaces:compileKotlin` -- BUILD SUCCESSFUL
- BLEInterface extends Interface with processOutgoing() as no-op
- No Android imports in either file (verified via grep)
- Both files in `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/`

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | 11e4c95 | feat(21-01): implement BLEInterface server-style parent |
| 2 | eb819c8 | feat(21-01): implement BLEPeerInterface per-peer child |

## Next Phase Readiness

Phase 22 (Hardening and Edge Cases) can proceed:
- BLEInterface and BLEPeerInterface provide the orchestration layer
- Zombie detection, enhanced blacklisting, dedup refinement build on this foundation
- shouldInitiateConnection() is ready to enable for smarter connection direction
