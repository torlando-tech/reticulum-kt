---
phase: 12
plan: 01
subsystem: android-power-management
tags: [android, coroutines, stateflow, power-management, battery]

dependency_graph:
  requires: [10-01, 10-02, 10-03, 10-04]
  provides: [ConnectionPolicy, ConnectionPolicyProvider]
  affects: [12-02, 12-03, 15-transport]

tech_stack:
  added: []
  patterns:
    - StateFlow combine for reactive state merging
    - Flow-based battery polling (30s interval)
    - Hysteresis pattern for battery threshold management

key_files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ConnectionPolicy.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ConnectionPolicyProvider.kt
    - rns-android/src/test/kotlin/network/reticulum/android/ConnectionPolicyTest.kt
  modified:
    - rns-android/src/test/kotlin/network/reticulum/android/ReticulumConfigTest.kt

decisions:
  - id: 12-01-hysteresis
    choice: "3% hysteresis (15% throttle, 18% resume)"
    rationale: "Prevents flip-flopping when battery hovers near threshold"
  - id: 12-01-battery-poll
    choice: "30-second polling for battery state"
    rationale: "Battery changes slowly; simpler than callbackFlow conversion"
  - id: 12-01-charging-override
    choice: "Charging overrides battery throttling, not Doze"
    rationale: "User expects full speed when plugged in, but Doze is system-level"

metrics:
  duration: 3min
  completed: 2026-01-25
  tests_added: 10
  lines_added: 462
---

# Phase 12 Plan 01: ConnectionPolicy and ConnectionPolicyProvider Summary

Unified connection policy combining Doze, network, and battery states into a single reactive StateFlow.

## What Was Built

### ConnectionPolicy Data Class
- `throttleMultiplier: Float` - 1.0 for normal, 5.0 for throttled
- `shouldThrottle: Boolean` - convenience flag
- `networkAvailable: Boolean` - from NetworkState
- `reason: String` - human-readable explanation
- Constants: `NORMAL_MULTIPLIER`, `DOZE_MULTIPLIER` (5.0), `BATTERY_THROTTLE_THRESHOLD` (15), `BATTERY_RESUME_THRESHOLD` (18)
- Factory method with hysteresis tracking returns `Pair<ConnectionPolicy, Boolean>`

### ConnectionPolicyProvider
- Combines `DozeStateObserver`, `NetworkStateObserver`, `BatteryMonitor` via `kotlinx.coroutines.flow.combine()`
- Battery info polled every 30 seconds (battery changes slowly)
- Tracks hysteresis state internally (`wasThrottledForBattery`)
- `start()`/`stop()` lifecycle management
- Exposes `policy: StateFlow<ConnectionPolicy>` and `currentPolicy` for synchronous access

### Priority Logic
1. **Charging** overrides battery throttling (but not Doze)
2. **Doze mode** applies 5x throttle
3. **Low battery** (<15%) applies 5x throttle with hysteresis

## Decisions Made

| Decision | Choice | Why |
|----------|--------|-----|
| Hysteresis window | 15% -> 18% (3%) | Prevents rapid on/off when battery hovers near threshold |
| Battery polling | 30-second interval | Simpler than callbackFlow; battery changes slowly |
| Charging behavior | Overrides battery, not Doze | User expectation: plugged in = full speed; Doze is system-level |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed pre-existing broken ReticulumConfigTest**

- **Found during:** Task 3 test compilation
- **Issue:** Tests referenced removed `getEffectiveJobInterval()` method
- **Fix:** Updated 3 tests to use `getEffectiveTablesCullInterval()` instead
- **Files modified:** `rns-android/src/test/kotlin/network/reticulum/android/ReticulumConfigTest.kt`
- **Commit:** e538b7f (included with Task 3)

## Test Coverage

10 unit tests covering:
- Charging override behavior
- Doze throttling
- Low battery throttling
- Hysteresis (16% stays throttled, 18% resumes)
- Edge cases (exact thresholds, Doze while charging, network unavailable)

## Code Examples

### Using ConnectionPolicy Factory
```kotlin
val (policy, newWasThrottled) = ConnectionPolicy.create(
    doze = DozeState.Active,
    network = NetworkState(NetworkType.WiFi),
    batteryLevel = 14,
    isCharging = false,
    wasThrottledForBattery = false
)
// policy.throttleMultiplier == 5.0 (low battery)
// newWasThrottled == true (now tracking hysteresis)
```

### Using ConnectionPolicyProvider
```kotlin
val provider = ConnectionPolicyProvider(dozeObserver, networkObserver, batteryMonitor, scope)
provider.start()

provider.policy.collect { policy ->
    if (policy.shouldThrottle) {
        adjustPollingInterval(baseInterval * policy.throttleMultiplier.toLong())
    }
}
```

## Next Phase Readiness

Ready for Plan 02 (Adaptive Polling):
- ConnectionPolicy provides `throttleMultiplier` for interval adjustment
- ConnectionPolicyProvider exposes reactive `StateFlow<ConnectionPolicy>`
- Transport can observe policy changes and adjust behavior

## Files Changed

| File | Lines | Purpose |
|------|-------|---------|
| ConnectionPolicy.kt | 128 | Data class, constants, factory with hysteresis |
| ConnectionPolicyProvider.kt | 167 | Combines state sources into single StateFlow |
| ConnectionPolicyTest.kt | 157 | 10 unit tests for throttle logic |
| ReticulumConfigTest.kt | +6/-11 | Fix broken tests (deviation) |
