---
phase: 21-ble-orchestration
plan: 02
subsystem: ble
tags: [android, ble, interface-manager, bluetooth, permissions]

# Dependency graph
requires:
  - phase: 21-01
    provides: BLEInterface and BLEPeerInterface orchestration classes
  - phase: 20-02
    provides: AndroidBLEDriver with setTransportIdentity(), getPeerConnection()
provides:
  - InterfaceManager BLE case wiring AndroidBLEDriver + BLEInterface
  - Complete BLE permission set in sample app manifest (SCAN, CONNECT, ADVERTISE)
  - End-to-end BLE mesh available through sample app configuration
affects: [22-hardening]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Async interface startup on Dispatchers.IO with null return (established by RNODE, extended to BLE)"
    - "Fully-qualified type names for single-use Android/BLE types to minimize import clutter"

key-files:
  created: []
  modified:
    - rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/InterfaceManager.kt
    - rns-sample-app/src/main/AndroidManifest.xml

key-decisions:
  - "Fully-qualified AndroidBLEDriver and BLEInterface in BLE case instead of imports (used only in one place)"
  - "Explicit BLUETOOTH_ADVERTISE in sample app manifest despite rns-android manifest merger (visibility and reliability)"
  - "No additional stopInterface() case needed -- existing else -> detach() fallback covers BLEInterface"

patterns-established:
  - "BLE case follows same async launch + null return pattern as RNODE case in InterfaceManager"

# Metrics
duration: 2min
completed: 2026-02-06
---

# Phase 21 Plan 02: InterfaceManager BLE Integration Summary

**InterfaceManager wired to create AndroidBLEDriver + BLEInterface on Dispatchers.IO with BLUETOOTH_ADVERTISE permission completing the BLE permission set**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-06T23:27:36Z
- **Completed:** 2026-02-06T23:29:36Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- InterfaceManager.startInterface() BLE case creates AndroidBLEDriver with Context + BluetoothManager, sets transport identity, constructs BLEInterface, registers with Transport
- Async launch on Dispatchers.IO follows same proven pattern as RNODE case
- BLUETOOTH_ADVERTISE permission added to sample app manifest, completing the 3 BLE permissions needed for Android 12+

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire BLE case in InterfaceManager.startInterface()** - `fc1acf5` (feat)
2. **Task 2: Add BLUETOOTH_ADVERTISE permission to sample app manifest** - `632fc79` (feat)

## Files Created/Modified
- `rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/InterfaceManager.kt` - BLE case in startInterface() creating AndroidBLEDriver + BLEInterface with async lifecycle
- `rns-sample-app/src/main/AndroidManifest.xml` - Added BLUETOOTH_ADVERTISE permission

## Decisions Made
- Used fully-qualified type names (android.bluetooth.BluetoothManager, network.reticulum.android.ble.AndroidBLEDriver, network.reticulum.interfaces.ble.BLEInterface) instead of imports since they are only used in the BLE case block
- Added BLUETOOTH_ADVERTISE explicitly to sample app manifest even though rns-android module declares it for manifest merger -- ensures visibility and reliability
- No stopInterface() changes needed -- the existing `else -> iface.detach()` fallback already handles BLEInterface correctly

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 21 complete: BLEInterface orchestration + app integration fully wired
- Ready for Phase 22: Hardening and Edge Cases (zombie detection, blacklisting, dedup)
- All BLE components from Phases 18-21 are connected end-to-end through InterfaceManager

---
*Phase: 21-ble-orchestration*
*Completed: 2026-02-06*
