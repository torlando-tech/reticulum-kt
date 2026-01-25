# Phase 12: Doze-Aware Connection Management - Research

**Researched:** 2026-01-25
**Domain:** Android power management, coroutine-based backoff, network resilience
**Confidence:** HIGH

## Summary

This phase implements connection resilience for Android's Doze mode and network transitions. The existing codebase (Phase 10/11) already provides `DozeStateObserver`, `NetworkStateObserver`, `BatteryMonitor`, and `BatteryOptimizationChecker` with StateFlow APIs. This phase consumes those state streams to throttle Transport job intervals, implement exponential backoff reconnection, and adapt to network/battery state changes.

The implementation follows the user's locked decisions from CONTEXT.md: two-tier Doze throttling (2x for Light, 5x for Deep), exponential backoff starting at 1s with 60s max and 10-attempt limit, 15% battery threshold with 3% hysteresis, and charging state override.

**Primary recommendation:** Use Kotlin coroutines `combine` to merge DozeState, NetworkState, and BatteryState flows into a single "ConnectionPolicy" StateFlow that all interfaces observe. Throttling multipliers apply to Transport.customJobIntervalMs. Reconnection uses a simple exponential backoff coroutine pattern (no external library needed).

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.8.1 | StateFlow combine, delay, structured concurrency | Already in project; native Kotlin async |
| Android PowerManager | API 26+ | isDeviceIdleMode(), isIgnoringBatteryOptimizations() | Official Android API |
| Android BatteryManager | API 26+ | Battery level, charging state | Official Android API |
| Android ConnectivityManager | API 26+ | Network state callbacks | Official Android API |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| N/A | - | - | No additional libraries needed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-rolled backoff | kotlin-retry or arrow-kt Schedule | Overkill for simple exponential; adds dependency |
| StateFlow combine | Channel-based solution | StateFlow already in use; combine is cleaner |

**Installation:** No new dependencies required.

## Architecture Patterns

### Recommended Project Structure

```
rns-android/src/main/kotlin/network/reticulum/android/
├── DozeStateObserver.kt       # [existing] Doze state StateFlow
├── NetworkStateObserver.kt    # [existing] Network state StateFlow
├── BatteryMonitor.kt          # [existing] Battery info + listener
├── BatteryOptimizationChecker.kt  # [existing] Battery optimization status
├── ConnectionPolicy.kt        # [NEW] Combines all state into policy
├── ConnectionPolicyProvider.kt # [NEW] Creates combined state flow
└── BackoffStrategy.kt         # [NEW] Exponential backoff implementation

rns-interfaces/src/main/kotlin/network/reticulum/interfaces/
├── tcp/TCPClientInterface.kt  # [modify] Add reconnect with backoff
└── Reconnectable.kt           # [NEW] Interface for reconnection support
```

### Pattern 1: Combined State Flow

**What:** Merge multiple state flows into a single policy that interfaces observe.

**When to use:** When multiple independent state sources affect a single behavior.

**Example:**
```kotlin
// Source: kotlinx.coroutines official docs
data class ConnectionPolicy(
    val throttleMultiplier: Float,  // 1.0 = normal, 2.0 = 2x slower, 5.0 = 5x slower
    val shouldThrottle: Boolean,
    val networkAvailable: Boolean,
    val reason: String
)

fun createConnectionPolicyFlow(
    dozeState: StateFlow<DozeState>,
    networkState: StateFlow<NetworkState>,
    batteryInfo: StateFlow<BatteryInfo>
): StateFlow<ConnectionPolicy> {
    return combine(dozeState, networkState, batteryInfo) { doze, network, battery ->
        when {
            // Charging overrides battery throttling
            battery.charging -> ConnectionPolicy(1.0f, false, network.isAvailable, "Charging")

            // Deep Doze or low battery = 5x throttle
            doze is DozeState.Dozing || battery.level < 15 ->
                ConnectionPolicy(5.0f, true, network.isAvailable, "Deep throttle")

            // Light Doze = 2x throttle (Note: Android doesn't expose Light Doze separately)
            // Using Doze as single tier since isDeviceIdleMode is binary

            // Low battery with hysteresis (resume at 18%)
            battery.level < 18 && !battery.charging ->
                ConnectionPolicy(5.0f, true, network.isAvailable, "Low battery")

            else -> ConnectionPolicy(1.0f, false, network.isAvailable, "Normal")
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionPolicy(1.0f, false, true, "Init"))
}
```

### Pattern 2: Exponential Backoff with Coroutines

**What:** Simple backoff without external library, using delay().

**When to use:** For reconnection attempts that should slow down on failure.

**Example:**
```kotlin
// Source: peerdh.com/kotlin coroutines patterns
class ExponentialBackoff(
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
    private val maxAttempts: Int = 10
) {
    private var currentDelay = initialDelayMs
    private var attempts = 0

    suspend fun nextDelay(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = currentDelay
        currentDelay = minOf((currentDelay * multiplier).toLong(), maxDelayMs)
        attempts++
        return delay
    }

    fun reset() {
        currentDelay = initialDelayMs
        attempts = 0
    }
}

// Usage in reconnect loop
private suspend fun reconnectWithBackoff() {
    val backoff = ExponentialBackoff()
    while (!online.get() && !detached.get()) {
        backoff.nextDelay()?.let { delayMs ->
            delay(delayMs)
            try {
                if (connect()) {
                    log("Reconnected successfully")
                    backoff.reset() // Reset on success
                    return
                }
            } catch (e: Exception) {
                log("Reconnection attempt failed: ${e.message}")
            }
        } ?: run {
            log("Max reconnection attempts (${backoff.maxAttempts}) reached")
            detach()
            return
        }
    }
}
```

### Pattern 3: Network Transition Handling

**What:** React to network changes by pausing/resuming connections.

**When to use:** When network type changes (WiFi to cellular, online to offline).

**Example:**
```kotlin
// Observe network state and react
scope.launch {
    networkState.collect { state ->
        when {
            !state.isAvailable -> {
                log("Network lost - pausing connections")
                pauseConnections()
            }
            state.isAvailable && previousState?.isAvailable == false -> {
                log("Network restored - resuming with fresh backoff")
                backoff.reset()  // Network change resets backoff
                resumeConnections()
            }
        }
        previousState = state
    }
}
```

### Anti-Patterns to Avoid

- **Polling network state:** Use StateFlow collection instead of periodic checks. The existing NetworkStateObserver with 500ms debounce is correct.

- **Ignoring charging state:** Per CONTEXT.md, charging overrides all throttling. Always check `isCharging` before applying battery-based throttling.

- **No hysteresis:** Without hysteresis, battery state would flip-flop at 15%. Use 15% down / 18% up threshold.

- **Fixed reconnect delays:** Simple fixed delays waste battery on flaky networks. Always use exponential backoff.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| StateFlow combining | Manual subscription tracking | `combine()` operator | Handles lifecycle, cancellation automatically |
| Exponential backoff | Complex retry library | Simple delay() + multiplier | Only 20 lines needed; no dependency |
| Network state | Polling ConnectivityManager | NetworkStateObserver (Phase 10) | Already debounced, StateFlow-based |
| Doze state | Polling PowerManager | DozeStateObserver (Phase 10) | Already StateFlow-based |
| Battery state | Polling BatteryManager | BatteryMonitor (Phase 10) | Already has listener pattern |

**Key insight:** The Phase 10 observers already solve the "observe system state" problem. This phase only needs to consume those flows and adjust behavior accordingly.

## Common Pitfalls

### Pitfall 1: Light Doze vs Deep Doze Detection

**What goes wrong:** Android's `isDeviceIdleMode()` only returns true for Deep Doze. Light Doze is not directly detectable via API.

**Why it happens:** Light Doze was introduced in Android 7.0 but has no public API to detect entry.

**How to avoid:** The CONTEXT.md specifies "Light Doze = 2x slower, Deep Doze = 5x slower". However, since we can only detect Deep Doze, recommend treating any Doze state as the 5x tier. If finer granularity is needed later, could potentially use ACTION_SCREEN_OFF timing heuristics, but this adds complexity.

**Warning signs:** Connections being too aggressive during Light Doze (when screen is off but device is moving).

### Pitfall 2: 9-Minute Alarm Limit

**What goes wrong:** Using `setAndAllowWhileIdle()` alarms for wake-ups, hitting the once-per-9-minutes limit.

**Why it happens:** Android enforces this to prevent battery drain during Doze.

**How to avoid:** Don't rely on alarms for connection maintenance. Instead, let maintenance windows wake the app naturally, and do work then. The foreground service (Phase 9) with network wakelocks handles high-priority cases.

**Warning signs:** Logs showing "alarm not firing" or unexpected delays in maintenance.

### Pitfall 3: Backoff Not Resetting on Network Change

**What goes wrong:** Backoff counter persists across network changes, causing long delays on fresh networks.

**Why it happens:** Developer forgets to call `backoff.reset()` when network type changes.

**How to avoid:** Per CONTEXT.md, network changes reset the backoff counter. Explicitly call `reset()` in network state change handler.

**Warning signs:** 60-second delays when switching from WiFi to cellular.

### Pitfall 4: Race Between Scope Cancellation and Reconnect

**What goes wrong:** Reconnection attempt starts after scope is cancelled, causing leaks.

**Why it happens:** `reconnecting` flag set before checking scope cancellation.

**How to avoid:** Check `isActive` before each reconnection attempt and at the start of the loop. Use structured concurrency.

**Warning signs:** Logs showing reconnection attempts after "Stopped" message.

### Pitfall 5: Hysteresis Flip-Flopping

**What goes wrong:** At 15-16% battery, state alternates between throttled and normal every few seconds.

**Why it happens:** Battery readings fluctuate, especially under load.

**How to avoid:** Use 3% hysteresis as specified: throttle at 15%, resume at 18%. Track whether currently throttled.

**Warning signs:** Rapid log spam of "throttle enabled" / "throttle disabled".

## Code Examples

### Throttling Transport Job Interval

```kotlin
// Apply throttle multiplier to Transport job interval
scope.launch {
    connectionPolicy.collect { policy ->
        val baseInterval = 250L  // Python default: 0.25s
        val throttledInterval = (baseInterval * policy.throttleMultiplier).toLong()

        Transport.customJobIntervalMs = throttledInterval
        log("Job interval set to ${throttledInterval}ms (${policy.reason})")
    }
}
```

### Battery State with Hysteresis

```kotlin
class BatteryThrottleTracker {
    private var isThrottled = false
    private val throttleThreshold = 15
    private val resumeThreshold = 18  // 3% hysteresis

    fun shouldThrottle(batteryLevel: Int, isCharging: Boolean): Boolean {
        if (isCharging) {
            isThrottled = false
            return false
        }

        if (!isThrottled && batteryLevel < throttleThreshold) {
            isThrottled = true
        } else if (isThrottled && batteryLevel >= resumeThreshold) {
            isThrottled = false
        }

        return isThrottled
    }
}
```

### Interface Reconnection with Policy Awareness

```kotlin
// Modified TCPClientInterface.reconnect()
private suspend fun reconnect() {
    if (reconnecting.getAndSet(true)) return

    val backoff = ExponentialBackoff(
        initialDelayMs = 1000L,
        maxDelayMs = 60_000L,
        maxAttempts = 10
    )

    while (!online.get() && !detached.get()) {
        // Get current policy for throttle awareness
        val policy = connectionPolicyProvider?.currentPolicy
        val multiplier = policy?.throttleMultiplier ?: 1.0f

        val baseDelay = backoff.nextDelay() ?: run {
            log("Max reconnection attempts reached")
            detach()
            break
        }

        // Apply throttle multiplier to backoff delay
        delay((baseDelay * multiplier).toLong())

        // Check if scope still active
        if (!ioScope.isActive) break

        try {
            if (connect()) {
                log("Reconnected successfully")
                break
            }
        } catch (e: CancellationException) {
            break
        } catch (e: Exception) {
            log("Reconnection attempt failed: ${e.message}")
        }
    }

    reconnecting.set(false)
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Thread.sleep() in loops | Coroutine delay() | Kotlin 1.3+ (2018) | Battery-efficient, cancellable |
| Polling for state | StateFlow collection | Kotlin 1.4+ (2020) | Reactive, lifecycle-aware |
| Fixed reconnect delays | Exponential backoff | Industry standard | Reduces battery drain on flaky networks |
| Ignoring Doze | Doze-aware throttling | Android 6.0+ (2015) | Required for background apps |

**Deprecated/outdated:**
- `AlarmManager.setRepeating()`: Cannot be used reliably due to Doze restrictions. Use WorkManager or foreground service instead.
- Raw Thread for background work: Use coroutines with proper scope management.

## Open Questions

1. **Light Doze Detection**
   - What we know: Only Deep Doze (`isDeviceIdleMode()`) is detectable via public API
   - What's unclear: Whether Light Doze can be inferred from screen-off timing
   - Recommendation: Treat all Doze as Deep Doze (5x throttle). If too aggressive, could add screen-off timer heuristic later.

2. **TCP Keep-Alive During Doze**
   - What we know: TCP keepalive packets may not fire during Doze maintenance windows
   - What's unclear: Whether Android kernel respects keepalive during Doze
   - Recommendation: Per CONTEXT.md, "Whether to proactively drop TCP connections on WiFi->cellular handoff or let TCP handle naturally" is Claude's discretion. Recommend letting TCP handle naturally for simplicity; reconnection logic handles failures.

3. **OEM Doze Variations**
   - What we know: OEM skins (Samsung, Xiaomi, etc.) have custom power management
   - What's unclear: Exact behavior varies per device
   - Recommendation: Research flag in phase description notes "Doze behavior varies by OEM". Test on multiple devices during Phase 12 verification. Document any device-specific workarounds discovered.

## Sources

### Primary (HIGH confidence)

- [kotlinx.coroutines official docs](https://github.com/kotlin/kotlinx.coroutines/blob/master/docs/topics/flow.md) - StateFlow combine, delay, cancellation
- [Android Developer: Doze and Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) - Doze restrictions, maintenance windows, 9-minute alarm limit
- [Android Developer: Battery Monitoring](https://developer.android.com/training/monitoring-device-state/battery-monitoring) - BatteryManager API, charging state detection
- Python Reticulum source `Transport.py` - job_interval = 0.250s, interface_jobs_interval = 5.0s

### Secondary (MEDIUM confidence)

- [Kotlin exponential backoff patterns](https://peerdh.com/blogs/programming-insights/implementing-exponential-backoff-in-kotlin-coroutines) - Pattern for delay-based backoff
- [Arrow-kt Schedule](https://arrow-kt.io/learn/resilience/retry-and-repeat/) - Reference for advanced backoff with jitter (not used but informative)

### Tertiary (LOW confidence)

- OEM Doze behavior variations - Community reports, needs device testing

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH - Using existing kotlinx.coroutines and Android APIs already in project
- Architecture: HIGH - Patterns verified against official documentation
- Pitfalls: MEDIUM - Some items (OEM variations) require device testing to fully validate

**Research date:** 2026-01-25
**Valid until:** 2026-02-25 (30 days - Android power management APIs are stable)
