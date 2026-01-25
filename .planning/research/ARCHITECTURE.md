# Architecture Research: Android Background Integration

**Project:** Reticulum-KT Android Production Readiness
**Researched:** 2026-01-24
**Confidence:** HIGH

## Executive Summary

Android imposes strict lifecycle and power management constraints on background networking. Reticulum-KT currently runs on a foreground service with `dataSync` type, but the architecture needs enhancement to handle:

1. **Doze mode network suspension** - Android completely blocks network access during deep idle
2. **Foreground service time limits** - Android 15+ limits `dataSync` services to 6 hours
3. **Lifecycle-aware coroutine management** - Current `CoroutineScope(SupervisorJob() + Dispatchers.IO)` needs lifecycle integration
4. **JVM compatibility** - Must maintain testability on JVM while adding Android-specific features

The existing architecture (Transport, Interface, LXMRouter with coroutines) is fundamentally sound, but requires **Android-aware wrapper layers** and **lifecycle-managed scopes**.

## Integration Points with Existing Architecture

### 1. Transport Layer Integration

**Current state:**
```kotlin
object Transport {
    private val crypto: CryptoProvider by lazy { defaultCryptoProvider() }
    // Uses standalone coroutine job loop
    // No lifecycle awareness
}
```

**Integration needs:**

**Lifecycle-managed scope injection**
- Transport currently creates its own coroutine scope
- Needs to accept external scope tied to Android Service lifecycle
- Already has `configureCoroutineJobLoop(scope, intervalMs, ...)` - this is the right pattern
- Foreground service should inject `lifecycleScope` from LifecycleService

**Doze mode handling**
- Transport job loop should detect Doze state and adjust behavior
- During Doze: reduce expensive operations (path table culling, announce processing)
- During maintenance windows: catch up on deferred work
- Battery-adjusted intervals already implemented via `ReticulumConfig.getEffectiveTablesCullInterval()`

**WorkManager coordination for Transport nodes**
- Transport nodes (routing enabled) need periodic maintenance during Doze
- WorkManager survives Doze with constraints
- Already started in `ReticulumService.initializeReticulum()` line 162-164
- Worker runs every 15 minutes, can trigger path table cleanup and announce processing

**Integration point:**
```kotlin
// In ReticulumService.initializeReticulum()
network.reticulum.transport.Transport.configureCoroutineJobLoop(
    scope = serviceScope,  // ✓ Already uses service scope
    intervalMs = 250,      // Main loop runs frequently
    tablesCullIntervalMs = config.getEffectiveTablesCullInterval(),  // Battery-adjusted
    announcesCheckIntervalMs = config.getEffectiveAnnouncesCheckInterval()
)
```

**Status:** Mostly implemented. Main gap is Doze detection in job loop.

---

### 2. Interface Layer Integration

**Current state:**
```kotlin
abstract class Interface(val name: String) {
    val online = AtomicBoolean(false)
    val detached = AtomicBoolean(false)
    var onPacketReceived: ((data: ByteArray, fromInterface: Interface) -> Unit)? = null
}
```

**TCPClientInterface coroutine usage:**
```kotlin
class TCPClientInterface {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var connectJob: Job? = null

    override fun start() {
        connectJob = ioScope.launch { /* ... */ }
    }

    override fun detach() {
        ioScope.cancel()  // Cancels all jobs
    }
}
```

**Integration needs:**

**External scope injection**
- Interfaces currently create their own scope (`SupervisorJob() + Dispatchers.IO`)
- Should accept external scope from service
- This allows Android to control cancellation when service stops

**Network capability monitoring**
- Interfaces should receive network state changes (WiFi lost, cellular switched)
- ConnectivityManager.NetworkCallback integration
- Pause reconnection attempts when no network available

**Doze-aware reconnection**
- TCP reconnection intervals should increase during Doze
- Avoid aggressive reconnection during battery saver mode
- Respect `keepAlive` parameter (already exists, default true)

**Integration point:**
```kotlin
// In InterfaceManager (already exists)
class InterfaceManager(
    private val scope: CoroutineScope,  // ✓ Accepts external scope
    // ...
) {
    private fun startInterface(config: StoredInterfaceConfig): Interface? {
        val iface = TCPClientInterface(
            // ...
            keepAlive = true  // Could make battery-mode dependent
        )
        iface.start()  // ✓ Uses its own ioScope
    }
}
```

**Gap:** Interfaces still create their own scope. Should inject service scope or use child scope.

**Recommendation:**
```kotlin
// Change Interface creation to accept scope
class TCPClientInterface(
    // ... existing params ...
    private val parentScope: CoroutineScope? = null  // Inject from service
) {
    private val ioScope = parentScope?.let {
        CoroutineScope(it.coroutineContext + SupervisorJob() + Dispatchers.IO)
    } ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
```

---

### 3. LXMRouter Layer Integration

**Current state:**
```kotlin
class LXMRouter(
    private val identity: Identity? = null,
    private val storagePath: String? = null,
    private val autopeer: Boolean = true
) {
    private val pendingInbound = mutableListOf<LXMessage>()
    private val pendingOutbound = mutableListOf<LXMessage>()
    // Internal coroutine launches for processing loop
}
```

**Integration needs:**

**Lifecycle-aware message processing**
- LXMRouter likely has internal processing loop (not shown in 100-line excerpt)
- Should accept external scope for processing jobs
- Message queues should persist across service restarts (already uses `storagePath`)

**WorkManager for message delivery retries**
- Opportunistic delivery should retry during Doze maintenance windows
- WorkManager with ExistingWorkPolicy.REPLACE for retry scheduling
- Propagation node sync should use WorkManager for periodic checks

**Storage lifecycle**
- `storagePath` should point to Android app-specific storage
- Handle service restart without message loss
- Already configured in `ReticulumService` line 126: `File(filesDir, "reticulum")`

**Integration point:**
```kotlin
// LXMRouter initialization in app (not in service currently - gap)
val router = LXMRouter(
    identity = myIdentity,
    storagePath = File(context.filesDir, "lxmf").absolutePath,
    autopeer = true
)
```

**Gap:** Sample app doesn't show LXMRouter integration with service. Likely needs to be added.

---

## New Components Needed

### 1. Android Service Layer (Already Partially Implemented)

**ReticulumService** (exists at `rns-android/src/main/kotlin/network/reticulum/android/ReticulumService.kt`)

**Current implementation:**
```kotlin
class ReticulumService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(...): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC  // ⚠️ 6-hour limit on Android 15+
            )
        }
        lifecycleScope.launch { initializeReticulum() }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        // ✓ Already handles memory pressure
    }
}
```

**Gaps to address:**

**Service type limitations**
- `FOREGROUND_SERVICE_TYPE_DATA_SYNC` limited to 6 hours on Android 15+
- Should evaluate `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` instead
- Requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission in manifest
- Runtime prerequisite: declare `CHANGE_NETWORK_STATE` permission

**Doze detection and handling**
```kotlin
// Add to ReticulumService
private fun isInDozeMode(): Boolean {
    val powerManager = getSystemService(PowerManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isDeviceIdleMode == true
    } else {
        false
    }
}

// Monitor Doze state changes
private fun observeDozeMode() {
    val filter = IntentFilter().apply {
        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
    }
    registerReceiver(dozeReceiver, filter)
}
```

**Network state monitoring**
```kotlin
// Add to ReticulumService
private fun observeNetworkState() {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Resume interface connections
        }
        override fun onLost(network: Network) {
            // Pause interface reconnection attempts
        }
    }
    connectivityManager?.registerDefaultNetworkCallback(networkCallback)
}
```

**Scope hierarchy**
```kotlin
// Current: serviceScope is independent
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// Should use: lifecycleScope from LifecycleService
// Automatically cancelled when service destroyed
// Already available - just need to use it more
```

---

### 2. ReticulumWorker (Already Implemented)

**Purpose:** Periodic maintenance during Doze mode

**Current implementation:** Referenced in `ReticulumService` line 163
```kotlin
if (config.enableTransport) {
    ReticulumWorker.schedule(this@ReticulumService, intervalMinutes = 15)
}
```

**Needs verification:** Does `ReticulumWorker` class exist? Not shown in file list.

**Expected implementation:**
```kotlin
class ReticulumWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (Reticulum.isStarted()) {
                // Perform maintenance
                Transport.trimMemory()
                Transport.processAnnouncementQueue()
                // Could trigger LXMRouter opportunistic delivery attempts
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<ReticulumWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "reticulum_maintenance",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
```

**Gap:** Worker class not found in codebase. Needs implementation.

---

### 3. Lifecycle Management Components

**Component: DozeStateObserver**

**Purpose:** Centralize Doze mode state tracking

```kotlin
class DozeStateObserver(private val context: Context) {
    private val _dozeState = MutableStateFlow(false)
    val dozeState: StateFlow<Boolean> = _dozeState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(PowerManager::class.java)
                _dozeState.value = powerManager?.isDeviceIdleMode == true
            }
        }
    }

    fun start() {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        context.registerReceiver(receiver, filter)

        // Initial state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            _dozeState.value = powerManager?.isDeviceIdleMode == true
        }
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }
}
```

**Component: NetworkStateObserver**

**Purpose:** Monitor network availability for interfaces

```kotlin
class NetworkStateObserver(private val context: Context) {
    private val _networkAvailable = MutableStateFlow(true)
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkAvailable.value = true
        }

        override fun onLost(network: Network) {
            _networkAvailable.value = false
        }
    }

    fun start() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        connectivityManager?.registerDefaultNetworkCallback(callback)

        // Initial state
        val activeNetwork = connectivityManager?.activeNetwork
        _networkAvailable.value = activeNetwork != null
    }

    fun stop() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        connectivityManager?.unregisterNetworkCallback(callback)
    }
}
```

**Component: BatteryOptimizationChecker**

**Purpose:** Warn users about battery restrictions

```kotlin
object BatteryOptimizationChecker {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true  // No restrictions on older versions
        }
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
```

---

## Code Organization

### JVM vs Android-Specific Separation

**Current module structure (correct):**

```
reticulum-kt/
├── rns-core/           # JVM-only, no Android dependencies
│   ├── Transport.kt    # ✓ Pure Kotlin, platform-agnostic
│   └── ...
├── rns-interfaces/     # JVM-only, network interfaces
│   ├── Interface.kt    # ✓ Pure Kotlin, uses java.net
│   └── tcp/
│       └── TCPClientInterface.kt  # ✓ Uses Socket, not Android-specific
├── lxmf-core/          # JVM-only, LXMF protocol
│   └── LXMRouter.kt    # ✓ Pure Kotlin
├── rns-android/        # Android library (API wrapper)
│   ├── ReticulumService.kt     # Android foreground service
│   ├── ReticulumConfig.kt      # Android-specific config
│   ├── ReticulumWorker.kt      # ⚠️ Missing - needs implementation
│   └── lifecycle/              # ⚠️ Missing - needs new package
│       ├── DozeStateObserver.kt
│       ├── NetworkStateObserver.kt
│       └── BatteryOptimizationChecker.kt
└── rns-sample-app/     # Android app using rns-android
    ├── MainActivity.kt
    ├── ReticulumViewModel.kt   # ✓ Good - uses viewModelScope
    └── service/
        └── InterfaceManager.kt  # ✓ Good - lifecycle-aware
```

**Dependency rules:**

1. **rns-core, rns-interfaces, lxmf-core:** Pure Kotlin, JVM target 21, no Android deps
2. **rns-android:** Android library, depends on core modules, wraps with Android lifecycle
3. **rns-sample-app:** Android app, depends on rns-android, demonstrates usage

**Critical principle:** Core networking logic stays in JVM modules for testability. Android modules only add lifecycle awareness and power management.

---

### Scope Management Pattern

**Recommended scope hierarchy:**

```
Application Scope (app-wide, survives activities)
  └── Service Scope (ReticulumService.lifecycleScope)
       ├── Transport Job Loop (injected serviceScope)
       ├── InterfaceManager Scope (passed from viewModel/service)
       │    └── Interface I/O Scopes (child scopes)
       └── LXMRouter Processing (if integrated)

ViewModel Scope (UI-bound, survives config changes)
  └── UI refresh jobs (status polling, etc.)
```

**Pattern in code:**

```kotlin
// ReticulumService
class ReticulumService : LifecycleService() {
    // Don't create independent scope - use lifecycleScope
    // private val serviceScope = CoroutineScope(...) ❌

    override fun onStartCommand(...): Int {
        lifecycleScope.launch {  // ✓ Tied to service lifecycle
            initializeReticulum()

            // Inject lifecycleScope into Transport
            Transport.configureCoroutineJobLoop(
                scope = this,  // lifecycleScope
                // ...
            )

            // Inject lifecycleScope into InterfaceManager
            val interfaceManager = InterfaceManager(
                scope = this,  // lifecycleScope
                // ...
            )
        }
    }
}

// TCPClientInterface (JVM module - can't depend on Android)
class TCPClientInterface(
    // Accept optional parent scope for Android
    private val parentScope: CoroutineScope? = null
) {
    private val ioScope = parentScope?.let {
        // Create child scope under parent
        CoroutineScope(it.coroutineContext + SupervisorJob() + Dispatchers.IO)
    } ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)  // Standalone for JVM
}

// ViewModel (UI layer)
class ReticulumViewModel : AndroidViewModel() {
    fun refreshMonitor() {
        viewModelScope.launch {  // ✓ UI-bound scope
            updateMonitorState()
        }
    }
}
```

**Key insight:** JVM modules can accept optional `CoroutineScope` parameter without depending on Android. Android modules inject lifecycle-aware scopes.

---

## Build Order (Suggested Implementation Sequence)

### Phase 1: Foundation (Core Lifecycle Integration)

**Goal:** Make existing components lifecycle-aware without breaking JVM compatibility

**Tasks:**
1. Add `DozeStateObserver` to `rns-android/src/main/kotlin/network/reticulum/android/lifecycle/`
2. Add `NetworkStateObserver` to `rns-android/src/main/kotlin/network/reticulum/android/lifecycle/`
3. Integrate observers into `ReticulumService.onCreate()`
4. Update `ReticulumService` to use `lifecycleScope` instead of `serviceScope`
5. Verify JVM tests still pass (no Android deps leaked)

**Deliverable:** Service properly cancels work on destroy, observes Doze state

---

### Phase 2: Scope Injection

**Goal:** Allow core modules to use injected scopes while maintaining JVM independence

**Tasks:**
1. Add optional `parentScope: CoroutineScope? = null` parameter to `TCPClientInterface`
2. Add optional `parentScope: CoroutineScope? = null` parameter to other interfaces
3. Update `InterfaceManager.startInterface()` to pass service scope to interfaces
4. Verify JVM standalone usage still works (scope defaults to independent)
5. Add tests for scope cancellation propagation

**Deliverable:** Interface coroutines cancel when service stops

---

### Phase 3: Doze Handling

**Goal:** Adapt behavior during Doze mode

**Tasks:**
1. Add Doze state flow to `Transport.configureCoroutineJobLoop()`
2. Adjust job loop intervals during Doze (reduce expensive operations)
3. Update `TCPClientInterface` reconnection logic to detect Doze (via state flow or callback)
4. Add network availability check before reconnection attempts
5. Test with `adb shell dumpsys deviceidle force-idle`

**Deliverable:** Reduced battery drain during Doze, faster recovery during maintenance windows

---

### Phase 4: WorkManager Integration

**Goal:** Survive deep Doze with periodic maintenance

**Tasks:**
1. Create `ReticulumWorker` class in `rns-android/`
2. Implement `doWork()` with Transport maintenance tasks
3. Schedule worker in `ReticulumService.initializeReticulum()` (already done, verify worker exists)
4. Add worker for LXMRouter message retry (if LXMRouter integrated)
5. Test worker runs during Doze mode

**Deliverable:** Path table cleanup and announce processing continue during Doze

---

### Phase 5: Service Type Evaluation

**Goal:** Determine if connectedDevice type is better than dataSync

**Tasks:**
1. Research Android 15+ time limits for dataSync (6 hours confirmed)
2. Evaluate connectedDevice requirements (CHANGE_NETWORK_STATE permission)
3. Add runtime check for choosing service type based on use case
4. Update manifest permissions if switching to connectedDevice
5. Test long-running connections (> 6 hours)

**Deliverable:** Service type decision documented, manifest updated if needed

---

### Phase 6: LXMRouter Integration (If Needed)

**Goal:** Add LXMF message routing to service

**Tasks:**
1. Initialize `LXMRouter` in `ReticulumService.initializeReticulum()`
2. Pass storage path from Android app-specific directory
3. Add WorkManager job for message delivery retries
4. Expose LXMRouter via service binder for app access
5. Add UI in sample app for sending/receiving messages

**Deliverable:** End-to-end LXMF messaging working in Android app

---

### Phase 7: Battery Optimization UX

**Goal:** Help users understand battery restrictions

**Tasks:**
1. Add `BatteryOptimizationChecker` to check exemption status
2. Add UI in sample app settings to request battery exemption
3. Show warning when battery saver active
4. Add diagnostics screen showing Doze state, network state, battery state
5. Already implemented in `ReticulumViewModel.updateMonitorState()` - verify completeness

**Deliverable:** Users can grant exemption if needed, understand when Doze is active

---

### Phase 8: Memory Optimization

**Goal:** Minimize memory footprint for long-running service

**Tasks:**
1. Already implemented: `ReticulumService.onTrimMemory()` calls `Transport.trimMemory()`
2. Add periodic memory trim to WorkManager job
3. Monitor ByteArrayPool usage (mentioned in `MonitorState.byteArrayPoolMb`)
4. Add memory stats API to `Transport` for diagnostics
5. Test with Android memory profiler

**Deliverable:** Service survives memory pressure without restart

---

## Architecture Patterns

### Pattern 1: Lifecycle-Aware Scope Injection

**Problem:** JVM modules need coroutines, but can't depend on Android lifecycle

**Solution:** Optional scope injection with sensible defaults

```kotlin
// In JVM module (no Android deps)
class NetworkComponent(
    private val parentScope: CoroutineScope? = null  // Optional injection
) {
    private val componentScope = parentScope?.let {
        CoroutineScope(it.coroutineContext + SupervisorJob())
    } ?: CoroutineScope(SupervisorJob())  // Standalone if not provided

    fun start() {
        componentScope.launch { /* work */ }
    }
}

// In Android module
class AndroidService : LifecycleService() {
    override fun onCreate() {
        val component = NetworkComponent(
            parentScope = lifecycleScope  // Inject lifecycle scope
        )
    }
}

// In JVM test
fun testComponent() {
    val component = NetworkComponent()  // Uses independent scope
}
```

---

### Pattern 2: State Flow for Cross-Module Communication

**Problem:** Core modules need to react to Android state without Android deps

**Solution:** State flow observation with callback injection

```kotlin
// In JVM module
class NetworkInterface {
    var onNetworkUnavailable: (() -> Unit)? = null

    fun handleConnectionLost() {
        onNetworkUnavailable?.invoke()
    }
}

// In Android module
class NetworkObserver(context: Context) {
    private val _networkState = MutableStateFlow(true)
    val networkState: StateFlow<Boolean> = _networkState.asStateFlow()
}

// In Service
val networkObserver = NetworkObserver(this)
lifecycleScope.launch {
    networkObserver.networkState.collect { available ->
        if (!available) {
            // Pause interface reconnection
            interfaceManager.pauseReconnection()
        }
    }
}
```

---

### Pattern 3: WorkManager for Background Persistence

**Problem:** Doze mode suspends foreground service work

**Solution:** WorkManager with network constraints

```kotlin
class MaintenanceWorker(context: Context, params: WorkerParameters)
    : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // This runs even during Doze (in maintenance windows)
            Transport.performMaintenance()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// Schedule with constraints
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES)
    .setConstraints(constraints)
    .build()
```

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Leaking Android Dependencies to Core

**What:** Importing Android classes into rns-core, rns-interfaces, lxmf-core

**Why bad:** Breaks JVM testability, prevents CLI usage, tight coupling

**Instead:** Use dependency injection and callbacks for Android-specific features

```kotlin
// ❌ Wrong - Android class in core module
import android.content.Context
class Transport(private val context: Context) { }

// ✓ Right - Optional callback in core, implemented in Android
class Transport {
    var onBatteryLow: (() -> Unit)? = null
}
```

---

### Anti-Pattern 2: GlobalScope for Long-Running Work

**What:** Using `GlobalScope.launch` for interface I/O or Transport jobs

**Why bad:** Work continues after service destroyed, can't be cancelled, memory leaks

**Instead:** Use injected lifecycle-aware scope

```kotlin
// ❌ Wrong
GlobalScope.launch {
    while (true) { readSocket() }
}

// ✓ Right
lifecycleScope.launch {
    while (isActive) { readSocket() }
}
```

---

### Anti-Pattern 3: Aggressive Reconnection Without Doze Detection

**What:** TCP interface retries connection every 5 seconds regardless of Doze state

**Why bad:** Burns battery during Doze, wasted work when network suspended

**Instead:** Backoff during Doze, resume on maintenance window

```kotlin
// ❌ Wrong
while (!connected) {
    delay(5000)
    tryConnect()
}

// ✓ Right
while (!connected) {
    val delayMs = if (isInDoze()) 60_000L else 5_000L
    delay(delayMs)
    if (networkAvailable) tryConnect()
}
```

---

### Anti-Pattern 4: Ignoring onTrimMemory

**What:** Not releasing caches when system asks for memory

**Why bad:** Service gets killed by system instead of trimming gracefully

**Instead:** Implement trim strategy (already done in ReticulumService)

```kotlin
// ✓ Already implemented
override fun onTrimMemory(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_CRITICAL -> Transport.aggressiveTrimMemory()
        TRIM_MEMORY_RUNNING_LOW -> Transport.trimMemory()
    }
}
```

---

## Platform-Specific Considerations

### Android API Level Differences

| Feature | Min API | Notes |
|---------|---------|-------|
| Doze mode | 23 (6.0) | Check with `Build.VERSION.SDK_INT >= M` |
| Extended Doze | 24 (7.0) | Doze while device moving |
| Foreground service restrictions | 26 (8.0) | Must call `startForeground()` within 5 seconds |
| Background start restrictions | 29 (10.0) | Can't start FGS from background |
| Foreground service types | 29 (10.0) | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| Type declaration mandatory | 34 (14.0) | Must declare type in manifest |
| dataSync time limit | 35 (15.0) | 6-hour maximum runtime |
| Job quota for FGS workers | 36 (16.0) | Background jobs count toward quota |

**Min SDK:** Currently set to 26 (Android 8.0) - appropriate

---

### Battery Optimization Exemptions

**When to request:**
- Instant messaging apps (Reticulum qualifies)
- Apps requiring persistent connections
- Transport nodes providing routing

**How to request:**
```kotlin
// Check current status
val exempt = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false

// Request exemption (shows system dialog)
if (!exempt) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}
```

**User experience:** Show educational dialog explaining why exemption needed before requesting

---

### Testing Doze Mode

**Force Doze mode:**
```bash
adb shell dumpsys deviceidle force-idle
```

**Exit Doze:**
```bash
adb shell dumpsys deviceidle unforce
```

**Trigger maintenance window:**
```bash
adb shell dumpsys deviceidle step
```

**Check current state:**
```bash
adb shell dumpsys deviceidle
```

---

## Summary: Integration Strategy

**The existing architecture is fundamentally sound.** Transport, Interface, and LXMRouter are well-designed for JVM, using coroutines appropriately.

**Key integration pattern:** Inject Android lifecycle awareness through optional scope parameters and callbacks, without modifying core logic.

**Critical gaps to address:**
1. Implement `ReticulumWorker` for Doze maintenance
2. Add lifecycle observers (DozeStateObserver, NetworkStateObserver)
3. Inject `lifecycleScope` into Transport and Interfaces
4. Evaluate service type (dataSync vs connectedDevice)
5. Add LXMRouter to service (if needed for LXMF features)

**Build order priority:**
1. Foundation (lifecycle observers) - enables Doze detection
2. Scope injection - enables proper cancellation
3. Doze handling - reduces battery drain
4. WorkManager - survives deep Doze
5. Service type evaluation - avoids 6-hour limit
6. LXMRouter integration - enables LXMF features
7. Battery UX - helps users understand restrictions
8. Memory optimization - improves long-term stability

**Confidence:** HIGH - Android documentation is authoritative (updated 2026-01-19), existing code structure is solid, integration points are clear.

---

## Sources

**Official Android Documentation (HIGH confidence):**
- [Foreground services overview](https://developer.android.com/develop/background-work/services/fgs) - Authoritative guide to FGS requirements
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) - connectedDevice type requirements
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) - Network restrictions, exemptions
- [Background tasks overview](https://developer.android.com/develop/background-work/background-tasks) - WorkManager vs FGS tradeoffs
- [Best practices for coroutines in Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) - Scope management patterns

**Community Resources (MEDIUM confidence, verified with official sources):**
- [Foreground Service vs WorkManager in Android](https://medium.com/@amar90aqi/foreground-service-vs-workmanager-in-android-choosing-the-right-tool-for-background-tasks-32c1242f9898) - Decision matrix
- [Building Resilient Android Apps: Surviving Doze](https://medium.com/softaai-blogs/building-resilient-android-apps-surviving-doze-app-standby-and-resource-restrictions-ea7ac07a185d) - Real-world patterns

**Kotlin Coroutines (HIGH confidence):**
- [Use Kotlin coroutines with lifecycle-aware components](https://developer.android.com/topic/libraries/architecture/coroutines) - Official lifecycle integration guide
