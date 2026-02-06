---
phase: 19-gatt-server-advertising
verified: 2026-02-06T21:26:39Z
status: passed
score: 16/16 must-haves verified
---

# Phase 19: GATT Server and Advertising Verification Report

**Phase Goal:** Be discoverable and accept incoming BLE connections as peripheral
**Verified:** 2026-02-06T21:26:39Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | GATT server hosts service UUID 37145b00-442d-4a94-917f-8f42c5da28e3 with RX, TX, and Identity characteristics | ✓ VERIFIED | BleGattServer.kt lines 680-721: createReticulumService() creates service with all 3 characteristics using BLEConstants UUIDs |
| 2 | RX characteristic accepts WRITE and WRITE_WITHOUT_RESPONSE from centrals | ✓ VERIFIED | BleGattServer.kt lines 687-692: RX char has PROPERTY_WRITE \| PROPERTY_WRITE_NO_RESPONSE with PERMISSION_WRITE |
| 3 | TX characteristic supports NOTIFY with CCCD descriptor for notification subscription | ✓ VERIFIED | BleGattServer.kt lines 694-708: TX char has PROPERTY_NOTIFY + CCCD descriptor with read/write permissions |
| 4 | Identity characteristic serves 16-byte Transport identity hash (read-only) | ✓ VERIFIED | BleGattServer.kt lines 710-715: Identity char has PROPERTY_READ only; lines 505-520: read handler serves transportIdentityHash |
| 5 | sendResponse() always called when responseNeeded=true in write/read callbacks | ✓ VERIFIED | BleGattServer.kt: All read requests (lines 486-533) and write requests (lines 535-578) call sendResponse when responseNeeded=true or for all reads |
| 6 | Notifications serialized via onNotificationSent callback (CompletableDeferred) | ✓ VERIFIED | BleGattServer.kt lines 341-372: sendNotification uses notificationMutex + CompletableDeferred + onNotificationSent callback (line 201) |
| 7 | Per-device MTU tracked from onMtuChanged callbacks with Map<String, Int> | ✓ VERIFIED | BleGattServer.kt line 85: centralMtus map; lines 664-673: handleMtuChanged stores usable MTU (mtu - 3) per device address |
| 8 | Per-device CCCD subscription state tracked -- notifications only sent to subscribed devices | ✓ VERIFIED | BleGattServer.kt line 88: notificationSubscriptions map; lines 346-350: sendNotification checks subscription before sending |
| 9 | API 33 compatibility for notifyCharacteristicChanged (new 4-param vs deprecated 3-param) | ✓ VERIFIED | BleGattServer.kt lines 724-748: sendNotificationCompat uses Build.VERSION_CODES.TIRAMISU check for API 33+ vs pre-API 33 |
| 10 | BLE advertising with service UUID 37145b00-442d-4a94-917f-8f42c5da28e3 for peer discovery | ✓ VERIFIED | BleAdvertiser.kt line 242: addServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID)) |
| 11 | Advertising is connectable with indefinite timeout | ✓ VERIFIED | BleAdvertiser.kt lines 233-234: setConnectable(true) + setTimeout(0) |
| 12 | Advertising restarts after connection if stopped (OEM auto-stop resilience) | ✓ VERIFIED | BleAdvertiser.kt lines 194-207: restartIfNeeded() checks shouldBeAdvertising vs actual state and restarts |
| 13 | 60-second periodic advertising refresh for background persistence | ✓ VERIFIED | BleAdvertiser.kt line 48: REFRESH_INTERVAL_MS = 60_000L; lines 273-291: startRefreshTimer() loops every 60s to stop/restart advertising |
| 14 | Advertising mode is user-configurable (LOW_POWER, BALANCED, LOW_LATENCY) | ✓ VERIFIED | BleAdvertiser.kt lines 55-64: AdvertiseMode enum with 3 modes; line 129: startAdvertising(mode) parameter |
| 15 | BleOperationQueue serializes GATT operations via Channel (one at a time) | ✓ VERIFIED | BleOperationQueue.kt line 33: Channel<Operation<*>>; lines 36-42: single consumer loop processes operations serially |
| 16 | BleOperationQueue is pure JVM (coroutines-only, no Android imports) | ✓ VERIFIED | BleOperationQueue.kt has zero Android imports; only kotlinx.coroutines imports; test passes on JVM without Android |

**Score:** 16/16 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `rns-android/src/main/kotlin/network/reticulum/android/ble/BleGattServer.kt` | GATT server hosting Reticulum service, callback handling, notification serialization, per-device state | ✓ VERIFIED | 763 lines, substantive implementation with all required features |
| `rns-android/src/main/kotlin/network/reticulum/android/ble/BleAdvertiser.kt` | BLE advertising lifecycle, configurable mode, restart logic, 60s refresh | ✓ VERIFIED | 315 lines, complete advertising management with OEM resilience |
| `rns-android/src/main/AndroidManifest.xml` | BLE permissions (BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN) | ✓ VERIFIED | Lines 4-16: All 6 BLE permissions (3 for API 31+, 3 for API 30-) + hardware feature declaration |
| `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BleOperationQueue.kt` | Generic GATT operation serializer using Channel + suspend functions | ✓ VERIFIED | 94 lines, pure JVM coroutines-based queue with timeout support |
| `rns-interfaces/src/test/kotlin/network/reticulum/interfaces/ble/BleOperationQueueTest.kt` | Unit tests for operation queue serialization behavior | ✓ VERIFIED | 170 lines, 8 tests covering serial execution, timeout, exceptions, concurrent access |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| BleGattServer.kt | BLEConstants.kt | imports SERVICE_UUID, RX_CHAR_UUID, TX_CHAR_UUID, IDENTITY_CHAR_UUID, CCCD_UUID | ✓ WIRED | Line 31: `import network.reticulum.interfaces.ble.BLEConstants`; all UUIDs used from BLEConstants |
| BleAdvertiser.kt | BLEConstants.kt | imports SERVICE_UUID for advertising payload | ✓ WIRED | Line 27: import statement; line 242: `BLEConstants.SERVICE_UUID` used in advertising data |
| BleOperationQueue.kt | kotlinx.coroutines | uses Channel, Mutex, withTimeout for operation serialization | ✓ WIRED | Lines 9-11: imports; line 33: Channel usage; line 46: withTimeout usage |
| rns-android | rns-interfaces | BleGattServer/BleAdvertiser import BLEConstants from rns-interfaces | ✓ WIRED | Cross-module dependency works; compilation succeeds |

### Requirements Coverage

No explicit REQUIREMENTS.md mapping for Phase 19 in current project structure. Success criteria from ROADMAP.md all verified above.

### Anti-Patterns Found

**No blocking anti-patterns found.** Code quality is high with proper patterns throughout.

Minor observations (not blockers):
- **ℹ️ Info:** BleGattServer uses minimal logging (only errors/disconnects) per CONTEXT.md — intentional design decision
- **ℹ️ Info:** BleAdvertiser retry logic capped at 3 attempts with linear backoff — reasonable for production resilience

### Human Verification Required

The following cannot be verified programmatically and require on-device testing:

#### 1. GATT Server Accepts Connections

**Test:** Using a second Android device or BLE scanner app, scan for devices advertising service UUID `37145b00-442d-4a94-917f-8f42c5da28e3`. Connect to the peripheral.
**Expected:** Connection succeeds. GATT service discovery shows RX, TX, and Identity characteristics with correct UUIDs and properties. Reading Identity characteristic returns 16 bytes (or GATT_FAILURE if not yet set).
**Why human:** Requires BLE stack interaction between two devices; cannot verify without actual hardware.

#### 2. RX Characteristic Accepts Writes

**Test:** After connecting as central, write test data to RX characteristic (UUID ending in 28e5) using both WRITE and WRITE_NO_RESPONSE.
**Expected:** Both write types succeed. BleGattServer's dataReceived SharedFlow emits the written data with correct device address.
**Why human:** Requires central device to initiate GATT write operations.

#### 3. TX Characteristic Notifications Work

**Test:** Subscribe to TX characteristic notifications via CCCD. Have peripheral send notification via `sendNotification()`. 
**Expected:** Central receives notification with correct data. Peripheral's `sendNotification()` returns true. Sending to non-subscribed device returns false.
**Why human:** Requires BLE notification delivery verification between devices.

#### 4. MTU Negotiation Updates Per-Device MTU

**Test:** Connect as central and request MTU change to 517. Check `getMtu()` on peripheral side.
**Expected:** Peripheral's centralMtus map reflects usable MTU (514 = 517 - 3). mtuChanged flow emits event.
**Why human:** Requires central-initiated MTU negotiation.

#### 5. Advertising is Discoverable

**Test:** Start advertising on peripheral. Scan from another device filtering by service UUID `37145b00-442d-4a94-917f-8f42c5da28e3`.
**Expected:** Peripheral appears in scan results within 5 seconds. Advertising persists for multiple minutes without manual restart.
**Why human:** Requires BLE scanning and actual radio environment.

#### 6. Advertising Restarts After Connection

**Test:** Start advertising, connect as central, observe advertising state. Some OEMs auto-stop advertising on connection.
**Expected:** If advertising stops, restartIfNeeded() called by connection handler successfully restarts it. isAdvertising StateFlow reflects current state.
**Why human:** OEM-specific behavior that varies by chipset.

#### 7. 60-Second Advertising Refresh Persists

**Test:** Start advertising, background the app, wait 2+ minutes.
**Expected:** Advertising remains active (verified via scanner on second device). No silent stops due to background restrictions.
**Why human:** Requires background execution and time-based behavior observation.

#### 8. BleOperationQueue Serializes Operations

**Test:** Unit tests pass (verified programmatically), but real GATT operations would queue via BleOperationQueue in Phase 20.
**Expected:** Unit tests pass (VERIFIED: `./gradlew :rns-interfaces:test` succeeds). Phase 20 will verify with real GATT operations.
**Why human:** Full integration testing happens in Phase 20; unit tests provide high confidence.

---

## Verification Summary

**All automated checks passed.**

✓ All 16 observable truths verified through code inspection
✓ All 5 required artifacts exist and are substantive (not stubs)
✓ All 4 key links wired correctly with proper imports and usage
✓ No blocker anti-patterns found
✓ Code compiles successfully (`./gradlew :rns-android:compileDebugKotlin` passes)
✓ Unit tests pass (`./gradlew :rns-interfaces:test` passes)

**Human verification items identified for on-device testing:**
8 integration tests requiring actual BLE hardware interaction. These are appropriate for Phase 21 end-to-end testing or manual QA, not blocking for phase completion.

**Phase 19 goal achieved:** The codebase now supports peripheral mode BLE with GATT server hosting the Reticulum service, BLE advertising for discoverability, and operation serialization infrastructure. All success criteria from ROADMAP.md verified.

---

_Verified: 2026-02-06T21:26:39Z_
_Verifier: Claude Code (gsd-verifier)_
