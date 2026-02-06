---
phase: 20
plan: 02
subsystem: ble-driver
tags: [android, ble, gatt, driver, facade, internal-visibility]
depends_on:
  requires: [18-01, 18-02, 19-01, 19-02, 20-01]
  provides: [AndroidBLEDriver-facade, BLEDriver-implementation, internal-encapsulation]
  affects: [21-BLEInterface-orchestration]
tech-stack:
  added: []
  patterns: [facade-pattern, event-aggregation, inner-class-delegation]
key-files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ble/AndroidBLEDriver.kt
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleGattServer.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleAdvertiser.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleScanner.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleGattClient.kt
decisions:
  - runBlocking for MTU getter (quick Mutex-protected map lookup, acceptable)
  - inner class for AndroidBLEPeerConnection (access to enclosing driver components)
  - shared CoroutineScope passed to all BLE components for coordinated lifecycle
metrics:
  duration: 2min
  completed: 2026-02-06
---

# Phase 20 Plan 02: AndroidBLEDriver Summary

**AndroidBLEDriver facade implementing BLEDriver interface with event aggregation, peer connection lifecycle, and internal visibility encapsulation**

## Accomplishments

### Task 1: Implement AndroidBLEDriver.kt
Created the concrete Android implementation of the BLEDriver interface. AndroidBLEDriver is the single public entry point that aggregates all four BLE components (BleGattServer, BleAdvertiser, BleScanner, BleGattClient) behind the pure-JVM BLEDriver contract.

Key implementation details:
- **Facade pattern**: Delegates all BLEDriver methods to the appropriate internal component
- **Event aggregation**: 6 coroutine collectors in `init` block route component events to BLEDriver flows
- **AndroidBLEPeerConnection**: Private inner class implementing BLEPeerConnection, wrapping both outgoing (central/gattClient) and incoming (peripheral/gattServer) connections
- **Peer connection lifecycle**: Mutex-protected map tracks active connections; connect/disconnect/close properly manage entries
- **Phase 21 API**: setTransportIdentity(), getPeerConnection(), restartAdvertisingIfNeeded() exposed for BLEInterface orchestration

### Task 2: Mark BLE components as internal visibility
Applied `internal` visibility modifier to all four BLE component classes and their public enums:
- `BleGattServer` -> `internal class`
- `BleAdvertiser` -> `internal class`, `AdvertiseMode` -> `internal enum`
- `BleScanner` -> `internal class`, `ScanMode` -> `internal enum`
- `BleGattClient` -> `internal class`

Only AndroidBLEDriver remains public from the `network.reticulum.android.ble` package.

## Task Commits

| Task | Name | Commit | Key Change |
|------|------|--------|------------|
| 1 | Implement AndroidBLEDriver.kt | 8c9fee4 | New facade implementing BLEDriver interface |
| 2 | Mark BLE components as internal | 3f9c22e | 4 classes + 2 enums marked internal |

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| `runBlocking` for MTU property getter | Quick Mutex-protected map lookup; won't block. Alternative (var updated via collectors) adds complexity for no benefit |
| Private inner class for AndroidBLEPeerConnection | Needs access to enclosing driver's gattClient/gattServer; inner class is clean Kotlin pattern |
| Shared CoroutineScope for all components | Single scope enables coordinated lifecycle; shutdown() cancels everything cleanly |
| `@Suppress("MissingPermission")` for localAddress | SecurityException is caught; address is often unavailable on Android due to privacy anyway |

## Deviations from Plan

None -- plan executed exactly as written.

## Issues Encountered

None. Both builds succeeded on first attempt.

## Verification Results

1. `rns-android:compileDebugKotlin` -- BUILD SUCCESSFUL
2. `rns-sample-app:compileDebugKotlin` -- BUILD SUCCESSFUL (internal visibility doesn't break consumers)
3. All BLEDriver interface methods implemented
4. All 4 BLE components marked internal
5. AndroidBLEDriver remains public

## Next Phase Readiness

Phase 20 (GATT Client and Scanner) is now complete. All BLE transport components are implemented:
- Phase 18: Fragmentation/reassembly + driver contract (rns-interfaces)
- Phase 19: GATT server + advertising (rns-android)
- Phase 20: Scanner + GATT client + AndroidBLEDriver facade (rns-android)

Phase 21 (BLEInterface Orchestration) can now:
- Accept an `AndroidBLEDriver` instance typed as `BLEDriver`
- Use `BLEPeerConnection` for all peer interactions
- Perform identity handshake via readIdentity/writeIdentity
- Send/receive fragments via sendFragment/receivedFragments
- React to discoveredPeers, incomingConnections, connectionLost flows
- Set transport identity via setTransportIdentity (AndroidBLEDriver-specific)
