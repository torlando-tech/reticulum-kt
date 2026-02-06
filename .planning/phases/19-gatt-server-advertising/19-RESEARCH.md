# Phase 19: GATT Server and Advertising - Research

**Researched:** 2026-02-06
**Domain:** Android BLE GATT Server, BLE Advertising, Android Library Module Migration
**Confidence:** HIGH

## Summary

Phase 19 implements the peripheral (GATT server) side of the BLE mesh interface: hosting the Reticulum service, accepting incoming connections from centrals, and advertising for peer discovery. The Android `BluetoothGattServer` and `BluetoothLeAdvertiser` APIs are well-documented and stable since API 21 (Android 5.0). Two working reference implementations exist -- the Python `BLEGATTServer.py` (bluezero-based) and Columba's `BleGattServer.kt` + `BleAdvertiser.kt` (Android-native). The Columba implementation is especially valuable as it already targets Android and uses the same UUIDs/protocol.

A critical architectural decision was made to add Android BLE dependencies directly to `rns-interfaces`, which is currently a pure Kotlin/JVM module. This requires converting it to an Android library module while preserving existing pure-JVM unit tests for `BLEFragmentation`, `BLEConstants`, etc. The pattern already exists in this project -- `rns-android` is an Android library module that depends on `rns-interfaces`. The conversion is straightforward.

The GATT server callback model is entirely Android-specific. Key patterns: always call `sendResponse()` when `responseNeeded=true`, serialize notifications via `onNotificationSent` callback, track CCCD subscription state and MTU per device, and handle the API 33 method signature changes for `notifyCharacteristicChanged`. The `BleOperationQueue` from Columba provides a proven serialization pattern.

**Primary recommendation:** Follow Columba's architecture closely (BleGattServer + BleAdvertiser + BleOperationQueue as separate classes in `network.reticulum.interfaces.ble`), converting `rns-interfaces` from `kotlin("jvm")` to `com.android.library` + `kotlin("android")`. Use the existing `rns-android` build.gradle.kts as the template.

## Standard Stack

### Core

| Library/API | Version | Purpose | Why Standard |
|-------------|---------|---------|--------------|
| `BluetoothGattServer` | Android API 21+ | GATT server hosting | Only Android API for peripheral mode |
| `BluetoothGattServerCallback` | Android API 21+ | Connection/write/read/MTU events | Standard callback mechanism |
| `BluetoothLeAdvertiser` | Android API 21+ | BLE advertising | Only Android API for advertising |
| `AdvertiseSettings` / `AdvertiseData` | Android API 21+ | Configure advertising params | Standard builder pattern |
| `kotlinx-coroutines-android` | Match project version | Coroutine dispatchers | Already used in rns-android |
| `kotlinx-coroutines-core` | Match project version | Mutex, Channel, SharedFlow | Already in rns-interfaces |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.core:core-ktx` | 1.12.0 | ContextCompat for permission checks | Permission checking on API 31+ |
| `org.robolectric:robolectric` | 4.11.1 | Android unit tests without device | Testing GATT server logic |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Raw BluetoothGattServer | Nordic BLE Library (no-nordicsemi) | Adds dependency, less control, but handles many edge cases automatically |
| Custom BleOperationQueue | Nordic BLE Library's internal queue | Nordic handles serialization internally, but it's a heavy dependency for just queue functionality |
| Manual CCCD tracking | - | No alternative; Android requires manual tracking on server side |

**Dependencies to add to rns-interfaces (after converting to Android library):**
```kotlin
// Android BLE APIs (provided by Android SDK, no additional library needed)
// Only need AndroidX for permission checking
implementation("androidx.core:core-ktx:1.12.0")
```

No additional BLE libraries are needed. The Android SDK provides all required BLE APIs.

## Architecture Patterns

### Recommended Project Structure

```
rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/
├── BLEConstants.kt              # (exists) Protocol UUIDs, fragment constants
├── BLEDriver.kt                 # (exists) Interface contract
├── BLEFragmentation.kt          # (exists) Pure-JVM fragmenter/reassembler
├── DiscoveredPeer.kt            # (exists) Peer scoring data class
├── BleGattServer.kt             # NEW: Android GATT server
├── BleAdvertiser.kt             # NEW: Android advertising manager
└── BleOperationQueue.kt         # NEW: GATT operation serializer
```

### Pattern 1: GATT Server Callback Dispatch

**What:** All `BluetoothGattServerCallback` methods dispatch to suspend functions via `scope.launch`.
**When to use:** Every callback handler in the GATT server.
**Why:** GATT callbacks arrive on a binder thread. Dispatching to a coroutine scope allows use of Mutex, SharedFlow, and suspend functions without blocking the BLE stack.

```kotlin
// Source: Columba BleGattServer.kt (verified pattern)
private val gattServerCallback = object : BluetoothGattServerCallback() {
    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice, requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray
    ) {
        scope.launch {
            handleCharacteristicWriteRequest(
                device, requestId, characteristic,
                preparedWrite, responseNeeded, offset, value
            )
        }
    }
}
```

### Pattern 2: Per-Device State Tracking with Mutex

**What:** Use `MutableMap<String, T>` + `Mutex` for per-device state (MTU, CCCD subscription, identity).
**When to use:** Tracking any per-connection state on the server side.
**Why:** Multiple GATT callbacks can arrive concurrently from different devices. Mutex prevents race conditions.

```kotlin
// Source: Columba BleGattServer.kt (verified pattern)
private val centralMtus = mutableMapOf<String, Int>()
private val mtuMutex = Mutex()

// Per-device CCCD tracking (notifications enabled/disabled)
private val notificationSubscriptions = mutableMapOf<String, Boolean>()
private val subscriptionMutex = Mutex()

private suspend fun handleMtuChanged(device: BluetoothDevice, mtu: Int) {
    val usableMtu = mtu - 3  // ATT header overhead
    mtuMutex.withLock {
        centralMtus[device.address] = usableMtu
    }
    onMtuChanged?.invoke(device.address, usableMtu)
}
```

### Pattern 3: Notification Serialization via onNotificationSent

**What:** Wait for `onNotificationSent` callback before sending next notification to same device.
**When to use:** Every `notifyCharacteristicChanged` call.
**Why:** Android BLE stack has no internal queue. Calling notify before previous notify completes silently drops data.

```kotlin
// Source: Derived from Columba pattern + android-ble.md research
private val notificationLatch = mutableMapOf<String, CompletableDeferred<Int>>()

suspend fun sendNotification(device: BluetoothDevice, data: ByteArray): Boolean {
    val latch = CompletableDeferred<Int>()
    notificationLatch[device.address] = latch

    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gattServer.notifyCharacteristicChanged(device, txCharacteristic, false, data)
    } else {
        @Suppress("DEPRECATION")
        txCharacteristic.value = data
        @Suppress("DEPRECATION")
        gattServer.notifyCharacteristicChanged(device, txCharacteristic, false)
    }

    if (success == BluetoothGatt.GATT_SUCCESS || success == true) {
        // Wait for onNotificationSent callback
        withTimeoutOrNull(BLEConstants.OPERATION_TIMEOUT_MS) { latch.await() }
    }
    notificationLatch.remove(device.address)
    return success == BluetoothGatt.GATT_SUCCESS || success == true
}

// In gattServerCallback:
override fun onNotificationSent(device: BluetoothDevice, status: Int) {
    notificationLatch[device.address]?.complete(status)
}
```

### Pattern 4: Advertising Restart After Connection

**What:** Monitor `onConnectionStateChange(STATE_CONNECTED)` and restart advertising if it stopped.
**When to use:** After every incoming connection.
**Why:** Some Android chipsets stop advertising when a connection is established. The user decision says to stop advertising at max connections and restart when a slot opens.

```kotlin
// Source: android-ble.md research + user CONTEXT.md decision
private suspend fun handleConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
            centralsMutex.withLock { connectedCentrals[device.address] = device }
            // Check if at max connections
            val count = centralsMutex.withLock { connectedCentrals.size }
            if (count >= maxConnections) {
                advertiser.stopAdvertising()
            }
            // Otherwise, ensure advertising is still running
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
            centralsMutex.withLock { connectedCentrals.remove(device.address) }
            // Slot opened, restart advertising if not already running
            if (!advertiser.isAdvertising.value) {
                advertiser.startAdvertising()
            }
        }
    }
}
```

### Pattern 5: API 33 Compatibility for notifyCharacteristicChanged

**What:** Use `Build.VERSION.SDK_INT` check to call the correct method signature.
**When to use:** Every notification send.
**Why:** API 33 added `notifyCharacteristicChanged(device, char, confirm, byte[])` returning `int`, deprecating the old 3-param version returning `boolean`.

```kotlin
// Source: Android API diff 33, verified via official docs
private fun sendNotificationCompat(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    data: ByteArray
): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+: new signature with byte[] param, returns int
        server.notifyCharacteristicChanged(device, characteristic, false, data)
    } else {
        // Pre-API 33: set value on characteristic, returns boolean
        @Suppress("DEPRECATION")
        characteristic.value = data
        @Suppress("DEPRECATION")
        val success = server.notifyCharacteristicChanged(device, characteristic, false)
        if (success) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
    }
}
```

### Pattern 6: Converting rns-interfaces to Android Library Module

**What:** Change the Gradle plugin from `kotlin("jvm")` to `com.android.library` + `kotlin("android")`.
**When to use:** Required for this phase -- GATT server needs Android APIs.
**Why:** BLE APIs are only available in the Android SDK. Existing pure-JVM code (`BLEFragmentation`, `BLEConstants`) continues to work under `src/main/kotlin` in an Android library module.

```kotlin
// rns-interfaces/build.gradle.kts BEFORE:
plugins {
    kotlin("jvm")
}

// rns-interfaces/build.gradle.kts AFTER (modeled on rns-android):
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "network.reticulum.interfaces"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    api(project(":rns-core"))  // Changed from implementation to api

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    // Android BLE (no additional dependency -- part of Android SDK)
    implementation("androidx.core:core-ktx:1.12.0")

    // Testing -- pure JVM tests still run via Robolectric or as local unit tests
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
```

**Impact on rns-core:** If `rns-core` is currently a pure JVM module and `rns-interfaces` changes to Android, `rns-core` either stays pure JVM (with `api(project(":rns-core"))` in rns-interfaces) or also converts. Since `rns-core` has no Android dependencies, it should stay pure JVM. The Android library module can depend on a JVM module, but not vice versa.

**Impact on existing unit tests:** The existing `BLEFragmentationTest.kt` uses pure JVM APIs (`ByteBuffer`, `ByteArray`). In an Android library module, `src/test/` runs as local JVM unit tests by default. Pure JVM tests will continue to pass without modification. The test runner changes from JUnit Platform to JUnit 4 (Android's standard), so `@Test` annotations from `org.junit.Test` instead of `org.junit.jupiter.api.Test`.

**WARNING:** The root `build.gradle.kts` applies JVM 21 target to non-Android modules. After converting rns-interfaces to Android, the `afterEvaluate` block will skip it (it checks for `com.android.library` plugin), which is correct behavior. The Android block sets Java 17 explicitly.

### Anti-Patterns to Avoid

- **Performing heavy work in GATT callbacks directly:** Always dispatch to a coroutine scope. GATT callbacks run on a binder thread; blocking it blocks the entire BLE stack.
- **Sending notifications without checking CCCD subscription:** `notifyCharacteristicChanged` may return true even if the client hasn't subscribed. Track CCCD writes per-device.
- **Using a single global MTU for all clients:** Each client negotiates its own MTU. Fragment size must use the per-device MTU, not a global value.
- **Starting/stopping advertising rapidly:** Android throttles scan starts (5 per 30s), and advertising has similar chipset-level limits. Start once, keep running.
- **Calling notifyCharacteristicChanged before onNotificationSent returns:** Silently drops the notification. Always serialize.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| GATT operation serialization | Custom locking primitives | BleOperationQueue with Channel + Mutex | Columba's proven pattern handles timeouts, cancellation, and callback completion correctly |
| API 33 compatibility | Custom reflection | `Build.VERSION.SDK_INT` check with `@Suppress("DEPRECATION")` | Standard Android pattern, well-documented |
| CCCD descriptor creation | Manual UUID bytes | `BluetoothGattDescriptor(BLEConstants.CCCD_UUID, PERMISSION_READ or PERMISSION_WRITE)` | Standard constant, always the same |
| Advertising data builder | Manual byte packing | `AdvertiseData.Builder().addServiceUuid()` | Android SDK handles 31-byte constraint validation |
| Permission checking | Manual string comparison | `ContextCompat.checkSelfPermission()` | Handles API level differences automatically |

**Key insight:** The Android BLE API surface is large but the patterns are well-established. The Columba implementation has already solved all the Android-specific problems. Porting its patterns (adapted to rns-interfaces' architecture) avoids all known pitfalls.

## Common Pitfalls

### Pitfall 1: Missing sendResponse() Causes GATT Error 133

**What goes wrong:** Client sends a write-with-response to RX characteristic. Server processes data but forgets to call `sendResponse()`. Client hangs, times out with GATT error 133, disconnects.
**Why it happens:** `responseNeeded` is only `true` for WRITE (not WRITE_WITHOUT_RESPONSE). Easy to miss in the callback.
**How to avoid:** Always check `responseNeeded` and call `sendResponse()` FIRST, before processing data. Put it at the top of the handler.
**Warning signs:** Client disconnects ~30 seconds after writing to server. Status code 133.

### Pitfall 2: Notification Sent Before CCCD Subscription

**What goes wrong:** Server calls `notifyCharacteristicChanged()` before client writes to CCCD. The notification is silently dropped or returns false.
**Why it happens:** There's a race between server being "ready to send" and client completing CCCD subscription.
**How to avoid:** Track per-device CCCD subscription state in `onDescriptorWriteRequest`. Only send notifications to devices that have `ENABLE_NOTIFICATION_VALUE` written to their CCCD.
**Warning signs:** Notifications appear to succeed (no exception) but client never receives data.

### Pitfall 3: Per-Device MTU Not Tracked

**What goes wrong:** Server uses default MTU (23 or 185) for all devices. Sends notifications larger than a client's actual MTU. Client receives truncated or dropped data.
**Why it happens:** `onMtuChanged` is per-device, but code uses a single MTU variable.
**How to avoid:** `Map<String, Int>` keyed by device address. Look up per-device MTU when sending notifications. Default to `BLEConstants.MIN_MTU` (23) for unknown devices until `onMtuChanged` fires.
**Warning signs:** Data corruption or missing fragments with specific peer devices.

### Pitfall 4: Advertising Stops Silently on Some OEMs

**What goes wrong:** Advertising stops after a connection is established or after the device enters Doze mode. New peers can't discover this device.
**Why it happens:** Some Android chipsets (particularly older Samsung Exynos, some Mediatek) auto-stop advertising on connection. Android Doze may interfere with chipset-level advertising timers.
**How to avoid:** Implement a periodic advertising health check (60-second timer per user decision). If `isAdvertising` is expected but no scan responses are being received, restart advertising. Use the foreground service to keep the process alive.
**Warning signs:** Peers stop discovering this device after the first connection or after ~15 minutes in background.

### Pitfall 5: Service Added Callback Ignored

**What goes wrong:** `gattServer.addService()` returns `true` but the service isn't actually registered yet. Subsequent operations fail silently.
**Why it happens:** `addService()` is asynchronous on Android. The service is only ready after `onServiceAdded` callback fires with `GATT_SUCCESS`.
**How to avoid:** Use `CompletableDeferred<Result<Unit>>` to bridge the callback to a suspend function. Wait for `onServiceAdded` before proceeding. Columba does this with a 5-second timeout.
**Warning signs:** GATT server appears open, but centrals can't discover the Reticulum service.

### Pitfall 6: Concurrent Notification Sends

**What goes wrong:** Server sends fragment 1 to device A, then immediately sends fragment 1 to device B. One of them is silently dropped.
**Why it happens:** `notifyCharacteristicChanged()` is a global operation on the BLE stack. Must wait for `onNotificationSent` before any further notifications (even to different devices).
**How to avoid:** Global notification lock/queue. Per-device queuing is insufficient -- the BLE stack is globally serialized.
**Warning signs:** Random data loss that correlates with multiple simultaneous peers.

### Pitfall 7: JUnit 5 to JUnit 4 Migration on Module Conversion

**What goes wrong:** Existing tests use `@Test` from `org.junit.jupiter.api.Test` and Kotest. After converting to Android library, `useJUnitPlatform()` is not available for Android modules.
**Why it happens:** Android Gradle Plugin's test runner only supports JUnit 4 for local unit tests. JUnit 5 requires a special JUnit 5 Android plugin.
**How to avoid:** Either (a) add the `android-junit5` Gradle plugin to maintain JUnit 5 compatibility, or (b) migrate existing tests to JUnit 4 assertions. Kotest can work with JUnit 4 runner via `kotest-runner-junit4`.
**Warning signs:** Tests fail to compile or run after module conversion.

## Code Examples

### Complete GATT Service Creation

```kotlin
// Source: Columba BleGattServer.kt createReticulumService() (verified)
private fun createReticulumService(): BluetoothGattService {
    val service = BluetoothGattService(
        BLEConstants.SERVICE_UUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    )

    // RX: Centrals write data here (fragments, identity handshake, keepalive)
    val rxChar = BluetoothGattCharacteristic(
        BLEConstants.RX_CHAR_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    // TX: We notify centrals here (fragments, keepalive)
    val txChar = BluetoothGattCharacteristic(
        BLEConstants.TX_CHAR_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    // CCCD descriptor on TX for notification subscription
    val cccd = BluetoothGattDescriptor(
        BLEConstants.CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or
            BluetoothGattDescriptor.PERMISSION_WRITE,
    )
    txChar.addDescriptor(cccd)

    // Identity: Read-only 16-byte Transport identity hash
    val identityChar = BluetoothGattCharacteristic(
        BLEConstants.IDENTITY_CHAR_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    service.addCharacteristic(rxChar)
    service.addCharacteristic(txChar)
    service.addCharacteristic(identityChar)

    return service
}
```

### Advertising Setup (Minimal Payload)

```kotlin
// Source: Columba BleAdvertiser.kt (verified, adapted per CONTEXT.md decisions)
fun buildAdvertiseSettings(
    mode: Int = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
    interval: Int? = null  // User-configurable per CONTEXT.md
): AdvertiseSettings {
    return AdvertiseSettings.Builder()
        .setAdvertiseMode(mode)
        .setConnectable(true)  // MUST be true for GATT server
        .setTimeout(0)          // Indefinite advertising
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .build()
}

fun buildAdvertiseData(): AdvertiseData {
    // Minimal payload per CONTEXT.md: service UUID only, no device name
    return AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
        .build()
}
```

### Write Request Handler with sendResponse

```kotlin
// Source: Columba BleGattServer.kt (verified), adapted
private suspend fun handleCharacteristicWriteRequest(
    device: BluetoothDevice, requestId: Int,
    characteristic: BluetoothGattCharacteristic,
    preparedWrite: Boolean, responseNeeded: Boolean,
    offset: Int, value: ByteArray
) = withContext(Dispatchers.Main) {
    when (characteristic.uuid) {
        BLEConstants.RX_CHAR_UUID -> {
            // ALWAYS send response first if needed (GATT-05)
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }

            // Validate data (silent drop on malformed per CONTEXT.md)
            if (value.isEmpty()) return@withContext

            // Pass to upper layer via callback/SharedFlow
            onDataReceived?.invoke(device.address, value)
        }
        else -> {
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_FAILURE, offset, null
                )
            }
        }
    }
}
```

### CCCD Descriptor Write Handler

```kotlin
// Source: Columba BleGattServer.kt (verified)
private suspend fun handleDescriptorWriteRequest(
    device: BluetoothDevice, requestId: Int,
    descriptor: BluetoothGattDescriptor,
    preparedWrite: Boolean, responseNeeded: Boolean,
    offset: Int, value: ByteArray
) = withContext(Dispatchers.Main) {
    when (descriptor.uuid) {
        BLEConstants.CCCD_UUID -> {
            val enabled = value.contentEquals(
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            // Track per-device subscription state
            subscriptionMutex.withLock {
                notificationSubscriptions[device.address] = enabled
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
        else -> {
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_FAILURE, offset, null
                )
            }
        }
    }
}
```

### BleOperationQueue Core Pattern

```kotlin
// Source: Columba BleOperationQueue.kt (verified pattern)
// The queue ensures only one GATT operation runs at a time.
// Operations are suspend functions that block until their callback fires.

class BleOperationQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val operationChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (operation in operationChannel) {
                try {
                    operation()
                } catch (e: Exception) {
                    // Log and continue processing queue
                }
            }
        }
    }

    suspend fun <T> enqueue(
        timeoutMs: Long = BLEConstants.OPERATION_TIMEOUT_MS,
        block: suspend () -> T
    ): T = suspendCancellableCoroutine { cont ->
        scope.launch {
            operationChannel.send {
                try {
                    val result = withTimeout(timeoutMs) { block() }
                    cont.resume(result) {}
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `characteristic.value = data` then `notifyCharacteristicChanged(device, char, false)` | `notifyCharacteristicChanged(device, char, false, data)` | API 33 (Android 13) | Must use compat check; old API deprecated |
| `BluetoothLeAdvertiser.startAdvertising()` only | `AdvertisingSet` API for advanced control | API 26 (Android 8.0) | Optional; legacy API still works fine for basic advertising |
| No automatic MTU | Android 14 auto-negotiates MTU 517 | API 34 (Android 14) | All Android 14+ devices get max MTU automatically |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` permissions | `BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_SCAN` | API 31 (Android 12) | Must handle both old and new permission models |

**Deprecated/outdated:**
- `BluetoothGattCharacteristic.setValue()` (deprecated API 33, use new method signatures)
- `notifyCharacteristicChanged(device, char, boolean)` (deprecated API 33, use 4-param version)
- `BluetoothGattDescriptor.setValue()` (deprecated API 33)

## Open Questions

1. **BleOperationQueue scope: standalone vs built-in**
   - What we know: Columba uses a standalone `BleOperationQueue` class. Phase 20 (GATT client/scanning) will also need GATT operation serialization.
   - What's unclear: Whether a single shared queue should serialize BOTH client and server operations, or separate queues per role.
   - Recommendation: Build as standalone class now. Phase 20 can reuse or extend it. GATT server operations (notifications) and GATT client operations (writes) use different `BluetoothGattServer`/`BluetoothGatt` objects and don't need cross-serialization. Use one queue per role, but build the class generically so it's reusable. **Confidence: MEDIUM** -- the Android BLE stack serializes internally at the radio level, but the API-level serialization is per-object.

2. **BLEDriver/BLEPeerConnection fate**
   - What we know: These interfaces were created in Phase 18 as abstractions. The user left this to Claude's discretion.
   - What's unclear: Whether `BleGattServer` should implement `BLEDriver.incomingConnections` directly or whether a higher-level orchestrator bridges them.
   - Recommendation: Keep `BLEDriver` as the public interface. Create a concrete `AndroidBLEDriver` class (in rns-interfaces since it's now Android) that owns `BleGattServer` + `BleAdvertiser` + `BleScanner` and implements `BLEDriver`. This preserves the clean interface for testing. **Confidence: HIGH** -- standard adapter pattern.

3. **Advertising interval user configuration**
   - What we know: CONTEXT.md says "advertising interval is user-configurable -- expose as a parameter."
   - What's unclear: Whether this maps to `AdvertiseSettings.ADVERTISE_MODE_*` constants or needs the `AdvertisingSet` API for fine-grained control.
   - Recommendation: Expose as `AdvertiseMode` enum mapping to the three constants (`LOW_POWER`, `BALANCED`, `LOW_LATENCY`). Don't use `AdvertisingSet` API -- it requires API 26+ and adds complexity for minimal benefit. The three modes cover the practical range. **Confidence: HIGH** -- this matches all reference implementations.

4. **Test strategy for Android BLE code**
   - What we know: Pure-JVM tests work for BLEFragmentation/BLEConstants. Android GATT APIs are not available in local unit tests.
   - What's unclear: How much can be tested with Robolectric vs requiring device tests.
   - Recommendation: Extract testable logic into pure-JVM classes where possible (the operation queue's channel/mutex logic, state tracking maps). For GATT server and advertiser, write integration tests that run on a real device (androidTest). Local unit tests verify callback dispatch logic using mocked BluetoothGattServer. **Confidence: MEDIUM** -- Robolectric's BLE support is limited.

5. **rns-core dependency chain after conversion**
   - What we know: `rns-core` is a pure JVM module. `rns-interfaces` depends on it. `rns-test` and `rns-cli` depend on `rns-interfaces`.
   - What's unclear: Whether `rns-test` and `rns-cli` (pure JVM) can still depend on `rns-interfaces` after it becomes an Android library.
   - Recommendation: **This is a real problem.** An Android library module cannot be consumed by a pure JVM module. Options: (a) Keep `rns-interfaces` as pure JVM, put Android BLE code in `rns-android` instead. (b) Extract BLE Android code into a new `rns-ble-android` module. (c) Use Kotlin Multiplatform to have common + Android source sets. Option (b) is simplest -- a new thin `rns-ble-android` module in `network.reticulum.interfaces.ble.android` that implements BleGattServer/BleAdvertiser/BleOperationQueue and depends on rns-interfaces for BLEConstants/BLEFragmentation. **Confidence: HIGH** -- this is a fundamental Gradle constraint.

## Revised Architecture Recommendation (Based on Open Question 5)

**DO NOT convert rns-interfaces to Android.** Instead:

```
rns-interfaces (stays pure JVM)
├── ble/BLEConstants.kt         (pure JVM, exists)
├── ble/BLEDriver.kt            (pure JVM interface, exists)
├── ble/BLEFragmentation.kt     (pure JVM, exists)
├── ble/DiscoveredPeer.kt        (pure JVM, exists)
└── ble/BleOperationQueue.kt    (pure JVM -- uses only coroutines, no Android APIs)

rns-android (Android library, exists)
├── depends on rns-interfaces
├── ble/BleGattServer.kt        (Android, NEW)
├── ble/BleAdvertiser.kt        (Android, NEW)
└── ble/AndroidBLEDriver.kt     (Android, implements BLEDriver, NEW)
```

This preserves the dependency chain:
- `rns-core` (JVM) <-- `rns-interfaces` (JVM) <-- `rns-android` (Android)
- `rns-core` (JVM) <-- `rns-interfaces` (JVM) <-- `rns-test` (JVM) -- still works
- `rns-core` (JVM) <-- `rns-interfaces` (JVM) <-- `rns-cli` (JVM) -- still works

The BleOperationQueue can live in `rns-interfaces` if it only uses `kotlinx.coroutines` (no Android APIs). The GATT server and advertiser must be in `rns-android` since they import `android.bluetooth.*`.

**HOWEVER:** The user's CONTEXT.md explicitly decided "Android dependencies in rns-interfaces" and "BleGattServer, BleAdvertiser, BleOperationQueue all in rns-interfaces under network.reticulum.interfaces.ble". This conflicts with the Gradle constraint. The planner should surface this conflict to the user and recommend the rns-android placement instead. If the user insists on rns-interfaces, the module conversion is technically possible but breaks rns-test and rns-cli.

## Sources

### Primary (HIGH confidence)
- Columba `BleGattServer.kt` -- `~/repos/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/server/BleGattServer.kt` -- Full Android GATT server implementation, verified working
- Columba `BleAdvertiser.kt` -- `~/repos/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/server/BleAdvertiser.kt` -- Full advertising implementation
- Columba `BleOperationQueue.kt` -- `~/repos/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/util/BleOperationQueue.kt` -- Operation serialization
- Python `BLEGATTServer.py` -- `~/repos/ble-reticulum/src/ble_reticulum/BLEGATTServer.py` -- Reference protocol implementation
- Existing v3 research -- `.planning/research/v3/android-ble.md` -- GATT server, advertising, MTU, pitfalls
- Existing protocol research -- `.planning/research/v3/protocol.md` -- Wire format, UUIDs, characteristic layout
- [Android API diff 33: BluetoothGattServer changes](https://developer.android.com/sdk/api_diff/33/changes/android.bluetooth.BluetoothGattServer) -- notifyCharacteristicChanged signature change
- [BluetoothGattServer API reference](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer) -- Official method signatures

### Secondary (MEDIUM confidence)
- [Punch Through: Android BLE Guide](https://punchthrough.com/android-ble-guide/) -- Comprehensive BLE patterns
- [AOSP BLE Advertising documentation](https://source.android.com/docs/core/connect/bluetooth/ble_advertising) -- Official advertising specs
- [Nordic DevZone: CCCD and GATT Server](https://devzone.nordicsemi.com/f/nordic-q-a/89706/questions-on-cccd-and-write-property-for-gatt-server) -- CCCD tracking best practices
- [Android Background BLE Communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background) -- Foreground service + BLE persistence

### Tertiary (LOW confidence)
- WebSearch results on OEM advertising persistence -- No authoritative source found; OEM-specific quirks are anecdotal from developer forums. The 60-second refresh timer approach is a pragmatic workaround without verified evidence of specific OEMs that silently stop advertising.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- Android BLE APIs are stable, well-documented, and the Columba reference provides proven patterns
- Architecture: HIGH -- Columba's architecture is directly portable, and the module placement issue is clearly identified
- Pitfalls: HIGH -- All pitfalls are documented in the v3 android-ble.md research and confirmed by Columba's implementation patterns
- Module migration: HIGH -- The Gradle dependency constraint is well-understood; the recommended workaround is clearly identified

**Research date:** 2026-02-06
**Valid until:** 2026-03-08 (30 days -- stable platform APIs)
