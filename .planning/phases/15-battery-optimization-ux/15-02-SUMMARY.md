---
phase: 15-battery-optimization-ux
plan: 02
subsystem: android-battery
tags: [service-events, kill-detection, shared-preferences, state-flow]
depends_on:
  requires: [10-04, 12-01]
  provides: [service-kill-counting, warning-dismissal-state, battery-stats-wiring]
  affects: [15-03]
tech_stack:
  added: []
  patterns: [flag-based-kill-detection, daily-reset-counters, dismissable-warning-state]
key_files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ServiceEventTracker.kt
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/ReticulumService.kt
decisions:
  - id: "15-02-01"
    decision: "Flag-pair kill detection (service_was_running + user_initiated_stop)"
    reason: "No Android API for detecting system kills; flag pair on start/stop distinguishes user vs system"
  - id: "15-02-02"
    decision: "Daily reset via date string comparison"
    reason: "Simple YYYY-MM-DD comparison avoids timezone edge cases and stale counter accumulation"
  - id: "15-02-03"
    decision: "Warning dismissed at count, not boolean"
    reason: "If user dismisses at count=2 and count rises to 3, warning resurfaces -- provides progressive urgency"
metrics:
  duration: "2min"
  completed: "2026-02-05"
---

# Phase 15 Plan 02: Service Event Tracking Summary

**One-liner:** Kill-count tracker with flag-based detection, daily reset, and dismissable warning state via SharedPreferences and StateFlow

## What Was Done

### Task 1: Created ServiceEventTracker (244 lines)
- **Kill detection:** Flag pair (`service_was_running`, `user_initiated_stop`) detects system kills on next service start
- **Daily reset:** Compares stored date (YYYY-MM-DD) with today; resets counters on new day
- **Warning dismissal:** Records kill count at dismissal; warning resurfaces only when count exceeds dismissed-at value
- **Persistence:** SharedPreferences file `reticulum_battery` (shared with BatteryStatsTracker)
- **Reactive state:** `ServiceEvents` data class exposed via `StateFlow` with computed `hasActiveWarning` property
- **Methods:** `recordServiceStart()`, `recordUserStop()`, `recordServiceKill()`, `dismissWarning()`, `shouldShowWarning(isExempt)`

### Task 2: Wired into ReticulumService lifecycle
- `eventTracker` initialized in `onCreate()` with immediate `recordServiceStart()` call
- `BatteryStatsTracker` wired: `start(lifecycleScope)` after batteryMonitor, `stop()` in onDestroy
- Companion `stop()` calls `recordUserStop()` before stopping service
- Accessor methods `getEventTracker()` and `getBatteryStatsTracker()` for downstream UI consumption

## Kill Detection Flow

```
User stops service:
  stop() -> recordUserStop() -> service_was_running=false, user_initiated_stop=true
  Next start: was_running=false -> NOT a kill

System kills service:
  (process killed, no callback)
  Flags remain: service_was_running=true, user_initiated_stop=false
  Next start: was_running=true AND user_stop=false -> KILL detected, counter++

Normal restart after user stop:
  Flags: service_was_running=false, user_initiated_stop=true
  Next start: was_running=false -> NOT a kill
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Wired BatteryStatsTracker into ReticulumService**
- **Found during:** Task 2
- **Issue:** Plan noted BatteryStatsTracker might not exist yet (parallel wave), but it already exists from Plan 01
- **Fix:** Wired it directly instead of leaving TODO comment -- `start(lifecycleScope)` in onCreate, `stop()` in onDestroy, accessor method added
- **Files modified:** ReticulumService.kt
- **Commit:** 779be66

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 26e3138 | feat | ServiceEventTracker with kill counting, daily reset, dismissable warnings |
| 779be66 | feat | Wire ServiceEventTracker and BatteryStatsTracker into ReticulumService |

## Next Phase Readiness

Plan 15-03 (Monitor Screen) can now:
- Access `service.getEventTracker().events` for kill count display
- Access `service.getBatteryStatsTracker().stats` for drain rate charts
- Call `shouldShowWarning(isExempt)` to decide when to show optimization banner
- Call `dismissWarning()` when user taps dismiss
