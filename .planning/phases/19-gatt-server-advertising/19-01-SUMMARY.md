---
phase: 19
plan: 01
subsystem: ble-gatt-server
tags: [ble, gatt-server, peripheral, android, notifications, cccd, mtu]
depends_on:
  requires: [18-01]
  provides: [BleGattServer]
  affects: [19-02, 20, 21]
tech-stack:
  added: []
  patterns: [SharedFlow event dispatch, CompletableDeferred notification serialization, Mutex-protected per-device state, API version compat pattern]
key-files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleGattServer.kt
  modified:
    - rns-android/src/main/AndroidManifest.xml
decisions:
  - id: 19-01-01
    decision: "SharedFlow events instead of nullable function callbacks"
    rationale: "Multiple collectors (BLEInterface + logging), replay=0 with extraBufferCapacity=16 for backpressure"
  - id: 19-01-02
    decision: "Single stateMutex for all per-device maps"
    rationale: "connectedCentrals, centralMtus, and notificationSubscriptions are always modified together; single lock avoids deadlock"
  - id: 19-01-03
    decision: "Global notification Mutex (not per-device)"
    rationale: "Android BLE stack serializes notifications globally at radio level; per-device queueing is insufficient"
  - id: 19-01-04
    decision: "No keepalive logic in GATT server"
    rationale: "Keepalive is a BLEInterface orchestration concern (Phase 21), not a GATT server concern"
  - id: 19-01-05
    decision: "No identity handshake interpretation in GATT server"
    rationale: "Server emits raw RX data via SharedFlow; identity handshake parsing is Phase 21 BLEInterface concern"
metrics:
  duration: 3min
  completed: 2026-02-06
  lines-production: 754
---

# Phase 19 Plan 01: GATT Server Implementation Summary

**BleGattServer hosts the Reticulum GATT service with RX/TX/Identity characteristics, handles all GATT callbacks via coroutine dispatch, serializes notifications via global Mutex+CompletableDeferred, and tracks per-device MTU and CCCD subscription state.**

## What Was Built

### AndroidManifest.xml BLE Permissions
- 3 permissions for Android 12+ (API 31+): BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN
- 3 permissions for Android 11 and below (API 30-): BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION
- `neverForLocation` flag on BLUETOOTH_SCAN avoids location permission on API 31+
- `maxSdkVersion="30"` on legacy permissions limits scope to older API levels
- BLE hardware feature declaration (not required; allows graceful degradation)

### BleGattServer.kt

**GATT Service (3 characteristics):**
| Characteristic | UUID | Properties | Permissions | Purpose |
|---|---|---|---|---|
| RX | `..28e5` | WRITE, WRITE_NO_RESPONSE | WRITE | Centrals write data to peripheral |
| TX | `..28e4` | READ, NOTIFY + CCCD | READ | Peripheral notifies centrals with data |
| Identity | `..28e6` | READ | READ | 16-byte Transport identity hash |

**8 GATT Callback Handlers (all dispatch via scope.launch):**
1. `onConnectionStateChange` -- track centrals, emit connect/disconnect events
2. `onCharacteristicReadRequest` -- serve TX (empty), Identity (16 bytes), or GATT_FAILURE
3. `onCharacteristicWriteRequest` -- sendResponse FIRST if needed, then emit data via SharedFlow
4. `onDescriptorReadRequest` -- return per-device CCCD subscription state
5. `onDescriptorWriteRequest` -- track CCCD subscription, sendResponse if needed
6. `onMtuChanged` -- store usable MTU (mtu - 3) per device
7. `onNotificationSent` -- complete CompletableDeferred for notification serialization
8. `onServiceAdded` -- complete CompletableDeferred for async service registration

**Notification Serialization:**
- Global `notificationMutex` ensures only one notification in-flight at a time
- `CompletableDeferred<Int>` bridges `onNotificationSent` callback to suspend function
- `withTimeoutOrNull(OPERATION_TIMEOUT_MS)` prevents indefinite blocking
- CCCD subscription check prevents sends to unsubscribed devices

**API 33 Compatibility:**
- `sendNotificationCompat()` uses `Build.VERSION.SDK_INT >= TIRAMISU` check
- API 33+: `notifyCharacteristicChanged(device, char, false, data)` returns Int
- Pre-API 33: `characteristic.value = data` then `notifyCharacteristicChanged(device, char, false)` returns Boolean

**Event Flows (SharedFlow, replay=0, extraBufferCapacity=16):**
- `centralConnected: SharedFlow<String>` -- device address
- `centralDisconnected: SharedFlow<String>` -- device address
- `dataReceived: SharedFlow<Pair<String, ByteArray>>` -- (address, data)
- `mtuChanged: SharedFlow<Pair<String, Int>>` -- (address, usableMtu)

**Public API:**
- `open()` / `close()` -- GATT server lifecycle
- `setTransportIdentity(ByteArray)` -- set 16-byte identity
- `sendNotification(device, data)` / `sendNotificationToAddress(address, data)`
- `getConnectedCentrals()` / `getMtu(address)` / `isSubscribed(address)`
- `disconnectCentral(address)` / `shutdown()`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] XML comment double-hyphen in AndroidManifest.xml**
- **Found during:** Task 2 build verification
- **Issue:** Comment `<!-- not required -- graceful degradation -->` contains `--` which is invalid XML
- **Fix:** Changed to `<!-- not required; graceful degradation -->`
- **Files modified:** AndroidManifest.xml
- **Commit:** e6e2814

## Decisions Made

1. **SharedFlow events over callbacks** -- Columba uses nullable function callbacks (`var onCentralConnected`). SharedFlow allows multiple collectors (BLEInterface orchestrator + logging/monitoring) and is more idiomatic Kotlin coroutines.

2. **Single state mutex** -- Instead of Columba's separate mutexes (centralsMutex, mtuMutex), using a single stateMutex for all per-device maps. These maps are always modified together (connect adds to all, disconnect removes from all), so separate locks add deadlock risk without concurrency benefit.

3. **Global notification serialization** -- Android BLE stack serializes notifications globally (not per-device). Using a global Mutex ensures correct ordering. Columba is missing this serialization, which can cause silent notification drops with multiple simultaneous peers.

4. **No keepalive, no identity parsing** -- Columba includes keepalive and identity handshake in the GATT server. This implementation emits raw events and delegates keepalive/identity to Phase 21's BLEInterface orchestrator, maintaining cleaner separation of concerns.

5. **Per-device CCCD tracking** -- Columba relies on descriptor.value for CCCD state. This implementation tracks CCCD subscription per device address in a map, which is more reliable across Android versions and avoids shared mutable state on the descriptor object.

## Next Phase Readiness

Phase 19 Plan 02 (BLE Advertising) can build on this GATT server. The server is fully functional for accepting connections and can be paired with a BleAdvertiser for device discovery. Phase 20 (GATT Client) will connect to this server as a central. Phase 21 (BLEInterface) will consume the SharedFlow events to orchestrate identity handshake, keepalive, and data routing.
