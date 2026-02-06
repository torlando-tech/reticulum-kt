# Android BLE Stack Research: BLE Mesh Interface

**Project:** Reticulum-KT BLE Mesh Interface
**Researched:** 2026-02-06
**Overall Confidence:** MEDIUM-HIGH (well-documented platform APIs, verified across multiple authoritative sources)

---

## Executive Summary

Android's BLE stack fully supports the dual-role (central + peripheral) architecture needed for a BLE mesh interface. Since Android 5.0 (API 21), devices can simultaneously act as both scanner/central and advertiser/peripheral, which is the foundation for peer-to-peer BLE mesh. The existing `BluetoothLeConnection.kt` handles the GATT client (central) role well and can be largely reused. The major new work is implementing `BluetoothGattServer` (peripheral role), `BluetoothLeAdvertiser` (peer discovery), and a connection manager that orchestrates both roles simultaneously.

The practical constraints are well-understood: 7 simultaneous BLE connections (AOSP limit), 31 bytes per advertisement packet, 5 scan starts per 30-second window, and MTU-3 bytes per write payload. These constraints shape the mesh protocol design significantly -- a mesh of more than 5-6 peers per node is impractical over BLE alone, which aligns well with Reticulum's design as a low-bandwidth resilient network.

---

## 1. GATT Server (Peripheral Role)

**Confidence: HIGH** -- Official Android documentation and multiple working implementations confirm this approach.

### API Overview

The GATT server is created through `BluetoothManager.openGattServer()`, which returns a `BluetoothGattServer` instance. The server hosts services and characteristics that remote clients can discover, read, write, and subscribe to notifications on.

### Setup Pattern (Kotlin)

```kotlin
// 1. Open the GATT server
val gattServer: BluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)

// 2. Create the NUS-like service
val service = BluetoothGattService(
    MESH_SERVICE_UUID,
    BluetoothGattService.SERVICE_TYPE_PRIMARY
)

// TX characteristic: server notifies client (data FROM this device)
val txCharacteristic = BluetoothGattCharacteristic(
    MESH_TX_CHAR_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    BluetoothGattCharacteristic.PERMISSION_READ
)
val cccdDescriptor = BluetoothGattDescriptor(
    CCCD_UUID, // 00002902-0000-1000-8000-00805f9b34fb
    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
)
txCharacteristic.addDescriptor(cccdDescriptor)

// RX characteristic: client writes to server (data TO this device)
val rxCharacteristic = BluetoothGattCharacteristic(
    MESH_RX_CHAR_UUID,
    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
    BluetoothGattCharacteristic.PERMISSION_WRITE
)

service.addCharacteristic(txCharacteristic)
service.addCharacteristic(rxCharacteristic)
gattServer.addService(service)

// 3. Start advertising (see Section 2)
```

### Server Callback Handling

The `BluetoothGattServerCallback` handles all incoming operations:

| Callback | Purpose | Must Respond? |
|----------|---------|---------------|
| `onConnectionStateChange` | Track connected peers | No |
| `onCharacteristicWriteRequest` | Receive data from peer | YES if `responseNeeded` is true |
| `onCharacteristicReadRequest` | Peer reads characteristic | YES always |
| `onDescriptorWriteRequest` | Peer enables/disables notifications (CCCD) | YES if `responseNeeded` is true |
| `onMtuChanged` | Peer negotiated new MTU | No (but must track the value) |
| `onNotificationSent` | Confirmation that notification was sent | No (but must serialize notifications) |

**CRITICAL:** You MUST call `gattServer.sendResponse()` when `responseNeeded` is true. Failing to do so causes the client to receive GATT error 133 and disconnect. This is the single most common GATT server bug.

### Sending Notifications (Server to Client)

```kotlin
// API 33+ (preferred, memory-safe)
val result = gattServer.notifyCharacteristicChanged(
    device,
    txCharacteristic,
    false,  // false = notification, true = indication
    data    // byte array payload, max MTU-3 bytes
)

// Pre-API 33 (deprecated but needed for backward compat)
txCharacteristic.value = data
gattServer.notifyCharacteristicChanged(device, txCharacteristic, false)
```

**Notification serialization:** Must wait for `onNotificationSent` callback before sending the next notification. Same pattern as the existing latch-based write serialization in `BluetoothLeConnection.kt`.

### What Can Be Reused from Existing Code

| Component | Reusable? | Notes |
|-----------|-----------|-------|
| NUS UUIDs | Partially | May want custom mesh UUIDs, but NUS is a solid choice for compatibility |
| PipedInputStream/OutputStream bridge | YES | Same pattern works for server-side data flow |
| Latch-based write serialization | YES | Same pattern needed for `notifyCharacteristicChanged` |
| MTU negotiation handling | Partially | Client-side logic reusable; server gets MTU via `onMtuChanged` callback |
| Retry logic | YES | Same error-handling patterns apply |
| Scan logic | Partially | Need to add scan-for-mesh-peers (not just scan-for-specific-address) |

### Sources

- [BluetoothGattServer API Reference](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)
- [UART GATT Server on Android](https://thejeshgn.com/2016/12/11/uart-gatt-server-peripheral-on-android/)
- [Android Things GATT Server Sample](https://github.com/androidthings/sample-bluetooth-le-gattserver)

---

## 2. BLE Advertising

**Confidence: HIGH** -- Official Android documentation is thorough here.

### BluetoothLeAdvertiser API

Available since API 21 (Android 5.0). Obtained via `BluetoothAdapter.getBluetoothLeAdvertiser()`.

```kotlin
val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser

val settings = AdvertiseSettings.Builder()
    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)  // or BALANCED, LOW_POWER
    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    .setConnectable(true)   // MUST be true to accept incoming connections
    .setTimeout(0)          // 0 = advertise indefinitely (max 180000ms otherwise)
    .build()

// Primary advertisement data (31 bytes max)
val advertiseData = AdvertiseData.Builder()
    .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
    .setIncludeTxPowerLevel(false)  // Save space
    .setIncludeDeviceName(false)    // Save space -- names are long
    .build()

// Scan response data (additional 31 bytes, sent on active scan)
val scanResponse = AdvertiseData.Builder()
    .setIncludeDeviceName(true)     // Put name here instead
    .build()

advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
```

### Advertisement Data Budget (31 bytes)

The 31-byte limit is the most important constraint. Here is the byte budget:

| Field | Bytes | Notes |
|-------|-------|-------|
| Flags (mandatory) | 3 | Length(1) + Type(1) + Value(1) |
| 128-bit Service UUID | 18 | Length(1) + Type(1) + UUID(16) |
| **Remaining** | **10** | Available for service data, manufacturer data, etc. |

If you include the device name, TX power, or additional service data, you eat into those 10 remaining bytes fast. **Recommendation:** Put only the service UUID in the advertisement data, and use scan response for the device name and any additional metadata.

For extended advertising (API 26+, Bluetooth 5 hardware), connectable advertisements can carry up to 191 bytes. However, relying on this limits device compatibility.

### Advertise Modes

| Mode | Interval | Battery Impact | Use Case |
|------|----------|----------------|----------|
| `ADVERTISE_MODE_LOW_POWER` | ~1000ms | Lowest | Background/idle |
| `ADVERTISE_MODE_BALANCED` | ~250ms | Moderate | Default |
| `ADVERTISE_MODE_LOW_LATENCY` | ~100ms | Highest | Active peer discovery |

**Recommendation for mesh:** Start with `LOW_LATENCY` during active peer discovery, then switch to `BALANCED` or `LOW_POWER` once connected to conserve battery.

### AdvertisingSet API (API 26+)

The newer `AdvertisingSet` API provides more control:
- Modify advertising data without stopping/restarting
- Set advertising duration and maximum events
- Support for periodic advertising
- Better control over PHY selection

For a mesh use case, the `AdvertisingSet` API is preferred because you may need to update advertised data (e.g., peer count, capabilities) without cycling the advertiser.

### Advertising While Connected

**Critical mesh concern:** By default, most Android devices stop connectable advertising when a connection is established. To accept multiple incoming connections as a peripheral:

1. The device must continue advertising after a connection is established
2. Android supports this -- the `BluetoothLeAdvertiser` does not automatically stop when a GATT server connection is made
3. However, some chipsets may limit this behavior
4. **Tested pattern:** Call `startAdvertising()` once; it continues even after connections are made. If it stops, restart advertising in the `onConnectionStateChange` callback.

### Error Codes

| Error | Meaning | Action |
|-------|---------|--------|
| `ADVERTISE_FAILED_DATA_TOO_LARGE` | Exceeded 31 bytes | Remove fields from AdvertiseData |
| `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` | Hardware limit reached | Reduce advertising sets |
| `ADVERTISE_FAILED_ALREADY_STARTED` | Duplicate start call | Stop then restart |
| `ADVERTISE_FAILED_INTERNAL_ERROR` | Stack error | Retry after delay |
| `ADVERTISE_FAILED_FEATURE_UNSUPPORTED` | Hardware doesn't support peripheral | Cannot use this device as peripheral |

### Permissions (API 31+)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

### Sources

- [BluetoothLeAdvertiser API Reference](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser)
- [BLE Advertising in AOSP](https://source.android.com/docs/core/connect/bluetooth/ble_advertising)
- [AdvertisingSet API Reference](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSet)

---

## 3. Simultaneous Central/Peripheral (Dual Role)

**Confidence: HIGH** -- AOSP documentation explicitly states this is supported.

### Official Support

From AOSP documentation: "An Android device acting as both a peripheral and central device can communicate with other BLE peripheral devices while sending advertisements in peripheral mode."

This requires:
- **Bluetooth 4.1+** chipset (virtually all Android devices since ~2014)
- **Android 5.0+** (API 21) for peripheral mode advertising
- The `BluetoothAdapter.isMultipleAdvertisementSupported()` check for advertising capability

### What This Means in Practice

You can simultaneously:
- Run a `BluetoothLeScanner` to discover peers (central role)
- Run a `BluetoothLeAdvertiser` to be discoverable (peripheral role)
- Maintain `BluetoothGatt` connections to other peripherals (GATT client)
- Host a `BluetoothGattServer` accepting incoming connections (GATT server)

### Caveats and Limitations

1. **Radio time-sharing:** BLE, Bluetooth Classic, and Wi-Fi share the same radio. Heavy BLE activity can impact Wi-Fi performance and vice versa.

2. **Connection interval tuning:** When both scanning and advertising, the scan interval, scan window, advertising interval, and connection intervals must be tuned to avoid starvation. Android handles this automatically to some degree, but heavy loads may cause dropped advertisements or delayed scan results.

3. **Not all devices support peripheral role:** Some older or budget devices may not support `BluetoothLeAdvertiser`. Always check `bluetoothAdapter.bluetoothLeAdvertiser != null` and `bluetoothAdapter.isMultipleAdvertisementSupported`.

4. **Device-specific quirks:** Some manufacturers (notably older Huawei devices) have issues connecting while scanning. Recommendation: stop scanning briefly when initiating a new GATT connection, then resume.

### Capability Detection

```kotlin
fun canActAsPeripheral(): Boolean {
    val adapter = bluetoothAdapter ?: return false
    return adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null
}

fun canActAsCentral(): Boolean {
    val adapter = bluetoothAdapter ?: return false
    return adapter.bluetoothLeScanner != null
}

fun supportsDualRole(): Boolean = canActAsPeripheral() && canActAsCentral()
```

### Sources

- [BLE in AOSP](https://source.android.com/docs/core/connect/bluetooth/ble)
- [Nordic Semiconductor BLE Library Issue #202](https://github.com/NordicSemiconductor/Android-BLE-Library/issues/202)

---

## 4. Connection Limits

**Confidence: MEDIUM-HIGH** -- AOSP source code defines 7, but practical limits vary by device.

### AOSP Defined Limit

The Android Bluetooth stack defines `BTA_GATTC_CONN_MAX = 7` as the maximum number of simultaneous GATT connections. This applies to **all** BLE connections combined (both client and server connections).

### Practical Considerations

| Factor | Impact |
|--------|--------|
| Bluetooth Classic devices | Count toward the 7-connection budget |
| Wi-Fi coexistence | Reduces available radio time for BLE |
| Connection interval | Lower intervals consume more radio time per connection |
| Manufacturer variations | Some devices may support fewer than 7 |
| Server connections | Each incoming peer connection counts toward the limit |

### Impact on Mesh Design

For a BLE mesh node that acts as both central and peripheral:
- Each outgoing connection (this device as client) = 1 connection
- Each incoming connection (this device as server) = 1 connection
- Bluetooth headphones/watches = 1-2 connections consumed

**Practical mesh peer limit: 4-5 simultaneous peers** after accounting for the user's other Bluetooth devices. This is fine for Reticulum -- BLE mesh is inherently low-density, and the mesh protocol should handle peer rotation.

### Detecting the Limit

There is no API to query the current connection count or the maximum. You must track connections yourself and handle `GATT_ERROR` (133) on connection attempts when the limit is reached.

### Recommendation

Design the mesh connection manager with a configurable maximum peer count, defaulting to 4. Implement graceful degradation: when at capacity, prefer maintaining existing stable connections over accepting new ones.

### Sources

- [AOSP BLE Documentation](https://source.android.com/docs/core/connect/bluetooth/ble)
- [Nordic BLE Library Issue #189](https://github.com/NordicSemiconductor/Android-BLE-Library/issues/189)
- [Google Issue Tracker: BLE Max Connections](https://issuetracker.google.com/issues/36993728)

---

## 5. MTU Negotiation

**Confidence: HIGH** -- Well-documented with a significant Android 14 behavioral change.

### Overview

| Parameter | Value |
|-----------|-------|
| Default MTU | 23 bytes |
| Maximum MTU | 517 bytes |
| Usable payload | MTU - 3 bytes (ATT header overhead) |
| Default payload | 20 bytes |
| Maximum payload | 514 bytes |

### Client-Side (Existing Code)

The existing `BluetoothLeConnection.kt` already handles this correctly:
```kotlin
gatt.requestMtu(512)  // Line 246 in existing code
```

### Android 14+ Behavior Change (IMPORTANT)

Starting with Android 14 (API 34), Android **automatically negotiates MTU 517** regardless of the value passed to `requestMtu()`. This means:

1. On Android 14+, calling `requestMtu(512)` effectively becomes `requestMtu(517)` internally.
2. Subsequent `requestMtu()` calls are ignored.
3. This applies to ALL apps, even those not targeting API 34.

**Impact on mesh:** This is actually good news -- all Android 14+ devices will negotiate maximum MTU automatically. For older devices, continue to call `requestMtu(512)`.

### Server-Side MTU Handling

The GATT server does NOT initiate MTU negotiation -- the client does. The server receives the negotiated MTU via the callback:

```kotlin
override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
    // Track per-device MTU
    peerMtuMap[device.address] = mtu
    Log.d(TAG, "MTU changed for ${device.address}: $mtu")
}
```

**CRITICAL:** On the server side, you must track MTU per connected device, since different clients may negotiate different MTU values.

### Practical MTU Values Across Devices

| Device Category | Typical MTU | Notes |
|-----------------|-------------|-------|
| Android 14+ | 517 | Automatic max negotiation |
| Modern Android (8-13) | 247-517 | Depends on peripheral support |
| Older Android (5-7) | 23-247 | May not negotiate at all |
| iOS devices | 185-512 | If connecting to iOS peers |

### Optimal MTU Values for Throughput

Due to BLE link-layer packet sizes, certain MTU values align better with LL packet boundaries. The optimal values are: 23, 50, 77, 104, 131, 158, 185, 212, 239, 266, 293, 320, 347, 374, 401, 428, 455, 482, 509, 517.

**Recommendation:** Request MTU 517. Accept whatever is negotiated. Chunk data to `negotiatedMtu - 3` bytes per write/notification.

### Sources

- [Google Issue Tracker: Android 14 MTU 517](https://issuetracker.google.com/issues/288211591)
- [RxAndroidBle MTU Tutorial](https://github.com/dariuszseweryn/RxAndroidBle/wiki/Tutorial:-MTU-negotiation)
- [BluetoothGatt.requestMtu API Reference](https://learn.microsoft.com/en-us/dotnet/api/android.bluetooth.bluetoothgatt.requestmtu)

---

## 6. BLE Write Patterns

**Confidence: HIGH** -- Well-documented with clear throughput implications.

### Write With Response (Write Request) -- WRITE_TYPE_DEFAULT

- Client sends data, server MUST acknowledge
- `onCharacteristicWrite` callback fires only after server responds
- **Reliable:** Failed writes are reported
- **Slow:** Acknowledgment round-trip takes at least one connection interval (~7.5-15ms)
- Only 1 write per connection event (request + response consumes the event)

### Write Without Response (Write Command) -- WRITE_TYPE_NO_RESPONSE

- Client sends data, no acknowledgment from server
- `onCharacteristicWrite` callback fires when data is buffered in the Android BLE stack (not when received by peer)
- **Fast:** Multiple packets per connection event (up to 6 on Android)
- **Less reliable:** Silent drops possible under load
- Data integrity still guaranteed by BLE link layer CRC -- drops only happen due to buffer overflows

### Throughput Comparison

| Method | Packets/Event | Approx. Throughput (MTU=247, CI=7.5ms) |
|--------|---------------|----------------------------------------|
| Write with response | 1 | ~32 KB/s |
| Write without response | Up to 6 | ~195 KB/s |
| Notifications (server to client) | Up to 6 | ~195 KB/s |
| Indications (server to client) | 1 | ~32 KB/s |

### Recommendation for Mesh

**Use write without response for data transfer, write with response for control messages.**

Rationale:
- Reticulum packets are already integrity-checked at the application layer (HMAC, signatures)
- BLE link layer provides CRC error detection
- The throughput improvement from write-without-response is 3-6x
- For small control messages (connection setup, keepalive), write-with-response provides confirmation

### Existing Code Impact

The existing `BluetoothLeConnection.kt` uses write-with-response (latch pattern, line 405-406):
```kotlin
rxChar.value = chunk
if (!gatt.writeCharacteristic(rxChar)) { ... }
```

For the mesh interface, create a parallel write path that uses `WRITE_TYPE_NO_RESPONSE`:
```kotlin
rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
rxChar.value = chunk
gatt.writeCharacteristic(rxChar)
// Still wait for onCharacteristicWrite callback before next write
// (callback means "buffered in stack", not "received by peer")
```

**IMPORTANT:** Even with write-without-response, you MUST wait for the `onCharacteristicWrite` callback before issuing the next write. Android's BLE stack has no internal queue -- writing without waiting for callbacks silently drops data.

### Sources

- [Punch Through: Write Requests vs Commands](https://punchthrough.com/ble-write-requests-vs-write-commands/)
- [Understanding BLE Throughput on Android](https://xizzhu.me/post/2021-08-06-android-ble-throughput/)
- [Maximizing BLE Throughput (Punch Through)](https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/)

---

## 7. Background BLE

**Confidence: HIGH** -- Official Android documentation is comprehensive here.

### Context: Existing Foreground Service

The reticulum-kt app already has a foreground service (from v2 work with battery optimization). This is the best foundation for background BLE.

### Scanning in Background

| Approach | Background Support | Doze Mode | Battery |
|----------|-------------------|-----------|---------|
| `BluetoothLeScanner.startScan(callback)` | Stops after ~10min in background | Stops in Doze | High |
| `BluetoothLeScanner.startScan(PendingIntent)` | Works in background | Works in Doze | Low |
| Foreground service + `startScan(callback)` | Continuous | Immune to Doze | Moderate |

**Recommendation:** Use the existing foreground service for continuous scanning. The foreground service is immune to Doze mode restrictions. For a mesh network, continuous scanning is essential for peer discovery.

### Advertising in Background

BLE advertising through `BluetoothLeAdvertiser` continues to work in the background as long as:
1. The `BluetoothLeAdvertiser` reference is held
2. The app process is alive (foreground service ensures this)
3. Advertising was started before going to background

**No special handling needed** -- the existing foreground service keeps the process alive, and advertising is not subject to the same background restrictions as scanning.

### Foreground Service Type

For Android 12+ (API 31), the foreground service must declare an appropriate type. The `connectedDevice` type is appropriate for maintaining BLE connections:

```xml
<service
    android:name=".service.ReticulumService"
    android:foregroundServiceType="connectedDevice"
    ... />
```

### Doze Mode

Doze mode (Android 6.0+) suspends most background processing to save battery. Effects on BLE:

| Operation | In Doze | With Foreground Service |
|-----------|---------|------------------------|
| BLE scanning | Suspended | Continues |
| BLE advertising | Continues (chipset-level) | Continues |
| GATT connections | Maintained | Maintained |
| GATT operations | May be delayed | Normal operation |

### Sources

- [Android BLE Background Communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Background BLE Scan in Doze Mode](https://proandroiddev.com/background-ble-scan-in-doze-mode-on-android-devices-3c2ce1764570)

---

## 8. Known Pitfalls

**Confidence: HIGH** -- These are well-documented, repeatedly confirmed across community sources.

### Critical Pitfalls

#### Pitfall 1: GATT Error 133 (The BLE Catch-All)

**What it is:** Status code 133 (`GATT_ERROR`) is a generic error meaning "something went wrong." It is the most common BLE error on Android.

**Common causes:**
- Exceeded the 7-connection limit
- Rapid successive BLE operations without waiting for callbacks
- Connection lost during service discovery or bonding
- Device out of range during connection attempt
- Android BLE stack internal state corruption

**Prevention:**
- Always serialize BLE operations (the existing latch pattern is correct)
- Implement retry with exponential backoff (existing 3-retry logic is good)
- Stop scanning before connecting on problematic devices
- Add a 100-200ms delay between `close()` and reconnecting to the same device
- On Android 14, some tablets enter a persistent GATT 133 state requiring Bluetooth restart

**Detection:** Any callback with `status != GATT_SUCCESS` where `status == 133`.

#### Pitfall 2: Scan Throttling (5 in 30 Seconds)

**What it is:** Since Android 7.0 (API 24), the system limits each app to 5 scan start operations within any 30-second window.

**How it manifests:** The 6th `startScan()` call succeeds silently but returns zero results. No error is reported.

**Prevention:**
- Start scanning once and keep it running. Do NOT stop/restart scanning frequently.
- Use scan filters to reduce results rather than cycling scans.
- If you must restart, implement a scan-start counter and wait 30 seconds when approaching the limit.

**Impact on mesh:** For mesh peer discovery, start a single long-running scan with a `ScanFilter` for the mesh service UUID. Never cycle the scan for discovery purposes.

#### Pitfall 3: Missing sendResponse() on GATT Server

**What it is:** When a GATT client sends a write request (with response) or read request, the server MUST call `gattServer.sendResponse()`. If it doesn't, the client hangs waiting for a response, eventually times out with GATT error 133, and disconnects.

**Prevention:** In every `onCharacteristicWriteRequest` and `onDescriptorWriteRequest` callback, check `responseNeeded` and send a response:
```kotlin
if (responseNeeded) {
    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
}
```

#### Pitfall 4: Writing Without Waiting for Callback

**What it is:** Android's BLE stack has NO internal operation queue. If you call `writeCharacteristic()` twice without waiting for `onCharacteristicWrite` between them, the second write is silently dropped.

**Prevention:** The existing latch pattern in `BluetoothLeConnection.kt` is exactly the right approach. Apply the same pattern to:
- `notifyCharacteristicChanged()` on the server side (wait for `onNotificationSent`)
- `writeDescriptor()` (wait for `onDescriptorWrite`)
- Any GATT operation

### Moderate Pitfalls

#### Pitfall 5: Per-Device MTU Tracking on Server

**What it is:** Each connected client may negotiate a different MTU. If you use a single global `bleMtu` variable, you may send notifications larger than what a specific client supports, causing data loss.

**Prevention:** Maintain a `Map<String, Int>` of device address to MTU. When sending notifications, use the per-device MTU to determine chunk size.

#### Pitfall 6: Descriptor Write Before Notifications Work

**What it is:** A GATT client must write to the CCCD descriptor (0x2902) to enable notifications. Until this write completes, `notifyCharacteristicChanged()` returns `false` or silently fails.

**Prevention:** Track per-device notification subscription state. Only send notifications to devices that have enabled them via CCCD write.

#### Pitfall 7: Connection Interval Cannot Be Set on Android

**What it is:** Android does not expose an API to set the BLE connection interval. The OS negotiates it automatically. On Android, the minimum is 7.5ms, but the actual value depends on the OS power management decisions.

**Impact:** You cannot optimize throughput by requesting specific connection intervals. The OS may use longer intervals when the screen is off or the device is in low-power mode.

**Workaround:** Request high-priority connection via `BluetoothGatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`. This hints at a 7.5-15ms interval but is not guaranteed.

#### Pitfall 8: Advertising Stops After Connection on Some Devices

**What it is:** Some Android devices (particularly older ones) stop BLE advertising when a connection is established, preventing additional peers from discovering and connecting to this device.

**Prevention:** After each `onConnectionStateChange(STATE_CONNECTED)` on the server side, verify that advertising is still running. If not, restart it. Implement an advertising monitor that periodically checks and restarts advertising if needed.

### Minor Pitfalls

#### Pitfall 9: API 33 Deprecation of Characteristic.setValue()

**What it is:** Starting with API 33, `BluetoothGattCharacteristic.setValue()` and the version of `writeCharacteristic()` that reads from the characteristic are deprecated in favor of new method signatures that take `byte[]` directly.

**Prevention:** Use API-level checks:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    gatt.writeCharacteristic(characteristic, data, writeType)
} else {
    @Suppress("DEPRECATION")
    characteristic.value = data
    @Suppress("DEPRECATION")
    gatt.writeCharacteristic(characteristic)
}
```

The same pattern applies to `notifyCharacteristicChanged()` on the server side.

#### Pitfall 10: BLE Operations on Main Thread

**What it is:** GATT callbacks arrive on a binder thread, not the main thread. Performing UI operations or heavy computation in callbacks blocks the BLE stack.

**Prevention:** Keep callback handlers lightweight. Dispatch data to a separate processing thread/coroutine immediately.

### Sources

- [Nordic DevZone: Android 14 GATT 133 Issues](https://devzone.nordicsemi.com/f/nordic-q-a/111467/android-14-connection-issues---repeated-gatt-133-errors)
- [Punch Through: BLE Scan Returns No Results](https://punchthrough.com/ble-scan-returns-no-results-on-android/)
- [Punch Through: Android BLE Scan Errors](https://punchthrough.com/android-ble-scan-errors/)
- [Making Android BLE Work - Part 2 (Martijn van Welie)](https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07)
- [Google Issue Tracker: Android 13 BLE Reliability](https://issuetracker.google.com/issues/269646835)

---

## 9. Architecture Recommendation for BLE Mesh Interface

Based on this research, here is the recommended architecture:

### Component Structure

```
BleMeshManager (orchestrator)
  |
  +-- BleAdvertiser (wraps BluetoothLeAdvertiser)
  |     - Advertises mesh service UUID
  |     - Monitors advertising state, restarts if needed
  |
  +-- BleScanner (wraps BluetoothLeScanner)
  |     - Scans for mesh service UUID
  |     - Reports discovered peers
  |     - Single long-running scan (no cycling)
  |
  +-- BleGattServer (wraps BluetoothGattServer)
  |     - Hosts mesh service with RX/TX characteristics
  |     - Accepts incoming connections
  |     - Tracks per-device MTU and CCCD subscription state
  |     - Serializes notifications per-device
  |
  +-- BleGattClient (evolution of BluetoothLeConnection)
  |     - Connects to discovered peers as GATT client
  |     - Discovers mesh service, enables notifications
  |     - Writes data using write-without-response
  |
  +-- BlePeerRegistry
        - Tracks all connected peers (both inbound and outbound)
        - Enforces connection limit (default: 4)
        - Provides unified send/receive interface to Reticulum
```

### Data Flow

```
Outgoing packet (Reticulum -> BLE):
  Transport.processOutgoing()
    -> BleMeshInterface.processOutgoing(data)
      -> BlePeerRegistry.broadcastToAll(data)
        -> For each peer:
           If connected as client: BleGattClient.write(data)
           If connected as server: BleGattServer.notify(device, data)

Incoming packet (BLE -> Reticulum):
  BleGattServer.onCharacteristicWriteRequest(data)  -- or --
  BleGattClient.onCharacteristicChanged(data)
    -> BlePeerRegistry.onDataReceived(peer, data)
      -> BleMeshInterface.onPacketReceived(data)
        -> Transport.inbound(data, interfaceRef)
```

### Service UUID Decision

**Option A: Reuse NUS UUIDs** (6e400001-...) -- Compatible with existing RNode tools
**Option B: Custom mesh UUIDs** -- Avoid confusion with actual NUS devices

**Recommendation:** Use custom UUIDs. The mesh service has different semantics than NUS (bidirectional symmetric vs. unidirectional UART). Using NUS UUIDs would cause confusion when other NUS-aware tools discover the mesh service. Generate a unique base UUID for the Reticulum BLE mesh protocol.

### Permissions Required

```xml
<!-- API 31+ (Android 12+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Pre-API 31 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

---

## 10. Gaps and Open Questions

1. **Advertising continuation after connection -- device testing needed.** While the APIs support it, real-world behavior varies by chipset. Must test on Pixel, Samsung, and at least one budget device.

2. **Practical connection limit under dual-role load.** The 7-connection AOSP limit is documented, but it is unknown whether running both GATT server and client simultaneously effectively halves this (since each direction uses a connection). Testing needed.

3. **Throughput under multi-peer load.** With 4 peers connected, what is the practical throughput per peer? BLE radio time is shared, so throughput degrades with more connections. Need empirical measurements.

4. **BLE 5.0 Extended Advertising adoption.** How many current devices support extended advertising (191-byte connectable advertisements)? This would simplify peer metadata exchange but limits backward compatibility.

5. **Connection parameter request after API 33.** Android 13+ may handle connection parameter negotiation differently. Need to verify `requestConnectionPriority()` behavior on latest Android versions.
