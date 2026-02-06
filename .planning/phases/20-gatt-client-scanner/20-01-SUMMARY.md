---
phase: 20
plan: 01
subsystem: ble-central
tags: [ble, scanner, gatt-client, android, coroutines]
depends_on:
  requires: [18-01, 18-02, 19-01, 19-02]
  provides: [BleScanner, BleGattClient]
  affects: [21-01, 21-02, 22-01]
tech-stack:
  added: []
  patterns: [CompletableDeferred-GATT-bridge, per-device-throttle, exponential-backoff-retry]
key-files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleScanner.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleGattClient.kt
  modified: []
decisions:
  - "Single long-running scan (no cycling) -- simpler than BleAdvertiser's 60s refresh, BLE chip handles power management via ScanMode"
  - "Per-device 3s throttle reduces scan callback noise while keeping RSSI data fresh"
  - "Pre-API 33 callback compat: added deprecated onCharacteristicChanged and onCharacteristicRead overloads"
  - "Connection timeout via coroutine Job (not operationQueue) since connection itself is not a GATT operation"
  - "Error 133 handler does gatt.close() only (no disconnect first) per CONTEXT.md recommendation"
metrics:
  duration: 3min
  completed: 2026-02-06
---

# Phase 20 Plan 01: BleScanner and BleGattClient Summary

**BLE central-role components: scanner with hardware UUID filter and per-device throttle, GATT client with operation queue serialization and error 133 exponential backoff**

## Accomplishments

### Task 1: BleScanner.kt
Created BLE scanner for peer discovery:
- Hardware `ScanFilter` with Reticulum `SERVICE_UUID` offloaded to BLE chip
- Single long-running scan until explicitly stopped (no cycling/restart pattern)
- Per-device throttle (3s interval) via `Mutex`-protected map of last emission times
- User-configurable `ScanMode` enum: LOW_POWER, BALANCED, LOW_LATENCY
- `SharedFlow<DiscoveredPeer>` with replay=0, extraBufferCapacity=64
- RSSI filtering below `MIN_RSSI_DBM` (-85 dBm)
- Permission checks: `BLUETOOTH_SCAN` on API 31+, `ACCESS_FINE_LOCATION` on API 30-

### Task 2: BleGattClient.kt
Created GATT client for central-mode connection management:
- Full connect/disconnect lifecycle with `BluetoothGatt` and per-connection `ConnectionData`
- Post-connect setup sequence: discoverServices -> requestMtu(517) -> setCharacteristicNotification -> writeDescriptor(CCCD)
- All GATT operations (service discovery, MTU request, characteristic write/read, descriptor write) serialized via `BleOperationQueue` with `CompletableDeferred` bridge pattern
- GATT error 133 handler: full `gatt.close()` (no disconnect) + fresh `connectGatt` with exponential backoff (1s, 2s, 4s, 8s, 16s)
- 5 retry attempts before 60-second temporary blacklist
- API 33 compat for `writeCharacteristic` and `writeDescriptor` (4-param vs deprecated 3-param)
- Pre-API 33 compat for `onCharacteristicChanged` and `onCharacteristicRead` callbacks
- Connection timeout via coroutine `Job` (CONNECTION_TIMEOUT_MS = 30s)
- SharedFlow events: connected, disconnected, connectionFailed, dataReceived, mtuChanged

## Task Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | BleScanner | d92aeb8 | BleScanner.kt (263 lines) |
| 2 | BleGattClient | 684be25 | BleGattClient.kt (746 lines) |

## Decisions Made

1. **Single long-running scan**: Unlike BleAdvertiser which uses 60-second refresh cycling, BleScanner runs a single continuous scan. The BLE chip handles power management via the ScanMode setting. This is simpler and matches CONTEXT.md's explicit decision.

2. **Per-device 3-second throttle**: Reduces scan callback noise (Android can fire callbacks many times per second for the same device) while keeping RSSI scoring data reasonably fresh.

3. **Pre-API 33 callback compatibility**: Added deprecated `onCharacteristicChanged(gatt, characteristic)` and `onCharacteristicRead(gatt, characteristic, status)` overloads since the new byte-array-parameter versions are API 33+ only.

4. **Connection timeout via Job**: The connection timeout is implemented as a separate coroutine Job rather than through operationQueue, since the initial BLE connection (`connectGatt`) is not a GATT operation and doesn't go through the queue.

5. **Error 133: close without disconnect**: Per CONTEXT.md, the error 133 handler calls `gatt.close()` directly without `gatt.disconnect()` first. The Android BLE stack is already in an error state, so disconnect is unreliable and may cause additional 133 errors.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed Kotlin expression-vs-statement `if` compilation errors**
- **Found during:** Task 2 initial build
- **Issue:** `withContext(Dispatchers.Main) { try { if (hasPermission()) { ... } } }` -- Kotlin treats the `if` as an expression return of the `try` block, requiring an `else` branch
- **Fix:** Moved `try/catch` outside `withContext` so the `if` is a statement, not an expression
- **Files modified:** BleGattClient.kt (2 locations: disconnect and setupConnection catch block)
- **Commit:** 684be25

**2. [Rule 2 - Missing Critical] Added pre-API 33 GATT callback overloads**
- **Found during:** Task 2 implementation review
- **Issue:** `onCharacteristicChanged(gatt, characteristic, value: ByteArray)` is API 33+ only. Devices running API 26-32 (our minSdk is 26) would silently not receive TX notifications.
- **Fix:** Added deprecated `onCharacteristicChanged(gatt, characteristic)` and `onCharacteristicRead(gatt, characteristic, status)` overloads that read value from characteristic object
- **Files modified:** BleGattClient.kt
- **Commit:** 684be25

## Issues Encountered

None -- both tasks completed without blocking issues.

## Next Phase Readiness

Phase 20 Plan 02 (BLE connection lifecycle) can proceed. BleScanner and BleGattClient provide the central-role transport layer that the BLEInterface (Phase 21) will orchestrate.

Available components for Phase 21:
- **Peripheral role:** BleGattServer, BleAdvertiser (Phase 19)
- **Central role:** BleScanner, BleGattClient (this plan)
- **Wire format:** BLEFragmenter, BLEReassembler (Phase 18)
- **Abstractions:** BLEConstants, BLEDriver, BLEPeerConnection, DiscoveredPeer, BleOperationQueue (Phases 18-19)
