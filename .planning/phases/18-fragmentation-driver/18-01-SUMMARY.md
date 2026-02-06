---
phase: 18-fragmentation-driver
plan: 01
subsystem: ble
tags: [ble, gatt, fragmentation, driver-abstraction, sharedflow, coroutines]

# Dependency graph
requires:
  - phase: rns-interfaces module
    provides: Interface base class, coroutines dependency, build infrastructure
provides:
  - BLEConstants with all protocol UUIDs, fragment header, MTU, keepalive, timeout values
  - BLEDriver interface defining message-based BLE abstraction with SharedFlow events
  - BLEPeerConnection interface for fragment-level send/receive per connection
  - DiscoveredPeer data class with weighted RSSI/history/recency scoring
affects: [18-02-fragmentation, 19-gatt-server-advertising, 20-gatt-client-scanner, 21-ble-orchestration, 22-hardening-edge-cases]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "BLEDriver abstraction: pure-JVM interface in rns-interfaces, platform impl in rns-sample-app"
    - "SharedFlow for BLE events: discoveredPeers, incomingConnections, connectionLost, receivedFragments"
    - "Message-based BLE I/O: no InputStream/OutputStream, fragment-level send/receive"
    - "DiscoveredPeer scoring: RSSI 60% + history 30% + recency 10% weighted combination"

key-files:
  created:
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEConstants.kt
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEDriver.kt
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/DiscoveredPeer.kt
  modified: []

key-decisions:
  - "Normalized scoring [0,1] instead of Python's absolute [0,145] -- cleaner interface, same relative ordering"
  - "Exponential recency decay (60s half-life) instead of Python's linear decay -- smoother degradation"
  - "New peers get 0.5 history rate (benefit of the doubt) matching Python's 25/50 ratio"
  - "equals/hashCode on address only -- peers identified by MAC before identity handshake"

patterns-established:
  - "BLE package: network.reticulum.interfaces.ble in rns-interfaces module"
  - "BLEDriver contract: suspend lifecycle + SharedFlow events + state properties"
  - "BLEPeerConnection contract: suspend fragment ops + SharedFlow received + identity handshake"
  - "BLEConstants singleton: all protocol constants with doc comments referencing Python source"

# Metrics
duration: 3min
completed: 2026-02-06
---

# Phase 18 Plan 01: BLE Driver Contract Summary

**BLEDriver/BLEPeerConnection interfaces with SharedFlow events, BLEConstants matching Python protocol v2.2, and DiscoveredPeer with weighted scoring -- pure JVM, zero Android deps**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-06T18:26:24Z
- **Completed:** 2026-02-06T18:29:27Z
- **Tasks:** 1
- **Files created:** 3

## Accomplishments

- Established the module boundary between pure-JVM protocol logic (rns-interfaces) and Android BLE implementation (rns-sample-app) via BLEDriver/BLEPeerConnection interfaces
- Defined all protocol constants (UUIDs, fragment types, MTU, keepalive, timeouts) matching Python ble-reticulum and Columba references exactly
- Implemented DiscoveredPeer with weighted scoring algorithm (RSSI 60% + history 30% + recency 10%) for peer selection priority
- Verified compilation on JVM with zero Android dependencies

## Task Commits

Each task was committed atomically:

1. **Task 1: Create BLEConstants, BLEDriver interface, and DiscoveredPeer** - `4d45804` (feat)

## Files Created/Modified

- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEConstants.kt` - Protocol constants (UUIDs, fragment header, MTU, keepalive, timeouts, identity size, connection limits)
- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEDriver.kt` - BLEDriver interface (lifecycle, event flows, state) and BLEPeerConnection interface (fragment ops, identity handshake)
- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/DiscoveredPeer.kt` - Data class with address, RSSI, identity, connection stats, and weighted scoring algorithm

## Decisions Made

1. **Normalized scoring range [0.0, 1.0]** -- The Python reference uses absolute points (0-145). Normalized range is cleaner for the interface contract. Same relative ordering is preserved (weights are identical ratios).

2. **Exponential recency decay** -- Python uses linear decay (full score < 5s, linear to 0 at 30s). Exponential decay with 60s half-life provides smoother degradation and is more robust to varying scan intervals.

3. **New peer benefit of doubt = 0.5** -- Python gives new peers 25/50 points on history. 0.5 rate preserves the same ratio (50%) in normalized space.

4. **Added CCCD_UUID to constants** -- Not in the plan but needed by GATT server (Phase 19) for notification subscription. Added proactively since it's a standard BLE UUID.

5. **Added MAX_MTU and CONNECTION_TIMEOUT_MS** -- Additional constants from Python/Columba reference that will be needed by later phases. Avoids needing to modify BLEConstants in every subsequent plan.

## Deviations from Plan

None -- plan executed exactly as written. Minor additions to BLEConstants (CCCD_UUID, MAX_MTU, CONNECTION_TIMEOUT_MS, OPERATION_TIMEOUT_MS, MAX_CONNECTIONS, MIN_RSSI_DBM) are forward-looking constants from the reference implementations that cost nothing to include now and prevent modifications later.

## Issues Encountered

None.

## User Setup Required

None -- no external service configuration required.

## Next Phase Readiness

- BLEConstants, BLEDriver, BLEPeerConnection, and DiscoveredPeer are ready for Phase 18 Plan 02 (fragmentation/reassembly)
- BLEDriver interface is ready for Phase 19 (GATT server implementation) and Phase 20 (GATT client implementation)
- DiscoveredPeer is ready for Phase 21 (orchestration peer selection)
- No blockers or concerns

---
*Phase: 18-fragmentation-driver*
*Completed: 2026-02-06*
