---
phase: 14-service-notification-ux
plan: 03
subsystem: android-notification
tags: [notification, service-wiring, debounce, pause-resume, viewmodel, android]
dependency_graph:
  requires: [14-01, 14-02]
  provides: [debounced-notification-updates, tap-to-open-intent, viewmodel-pause-state, reconnect-callback-wiring]
  affects: [phase-15]
tech_stack:
  added: []
  patterns: [debounced-notification-update, handler-based-debounce, connection-snapshot-loop, callback-wiring]
key_files:
  created: []
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/ReticulumService.kt
    - rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/viewmodel/ReticulumViewModel.kt
    - rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/service/InterfaceManager.kt
    - rns-android/build.gradle.kts
decisions:
  - id: "14-03-01"
    description: "Handler-based debounce for notification updates (500ms minimum interval)"
  - id: "14-03-02"
    description: "Name-based heuristic for interface categorization with optional provider override"
  - id: "14-03-03"
    description: "Periodic 5-second update loop with 3-second initial delay for initialization"
  - id: "14-03-04"
    description: "promote lifecycle-service to api() scope for transitive dependency access"
metrics:
  duration: "3.25min"
  completed: "2026-02-05"
---

# Phase 14 Plan 03: Service Notification Wiring Summary

Debounced notification update loop with ConnectionSnapshot from Transport interfaces, tap-to-open intent via package manager, ViewModel pause/resume state wiring through InterfaceManager reconnectAll

## What Was Done

### Task 1: Wire debounced notification updates into ReticulumService

Replaced the old string-based `createNotification(status)` / `updateNotification(status)` pattern with the rich notification system built in Plans 01 and 02:

- **NotificationContentBuilder** initialized in `onCreate()` and used for all notification builds
- **`buildConnectionSnapshot()`** collects interface state from `Transport.getInterfaces()`, maps each `InterfaceRef` to an `InterfaceSnapshot` using `categorizeInterface()`, and computes the `ServiceConnectionState`
- **`categorizeInterface()`** uses name-based heuristics (TCP, UDP, Auto, BLE, RNode, Local) with an optional `interfaceTypeProvider` callback for ViewModel override
- **`updateNotificationDebounced()`** implements 500ms debounce using `Handler(Looper.getMainLooper())` -- if called within 500ms of the last update, it coalesces and posts a delayed runnable
- **`postNotificationUpdate()`** builds snapshot, content intent, dynamic actions, and posts the notification
- **`buildContentIntent()`** uses `packageManager.getLaunchIntentForPackage()` for tap-to-open
- **Periodic loop**: coroutine in `lifecycleScope` runs `updateNotificationDebounced()` every 5 seconds after a 3-second initialization delay
- **Pause callback wired**: `onPauseStateChanged` triggers immediate debounced notification update
- **Initial notification**: uses `ConnectionSnapshot(CONNECTING, ...)` for the `startForeground()` call

### Task 2: Wire ViewModel to service pause state and reconnect callback

- Added `_isPaused: MutableStateFlow<Boolean>` and `isPaused: StateFlow<Boolean>` to ViewModel
- In `startService()`: wired `onReconnectRequested` to `interfaceManager?.reconnectAll()` and `onPauseStateChanged` to sync `_isPaused` from service
- Added `reconnectAll()` public method to InterfaceManager (delegates to `notifyNetworkChange()`)
- In `stopService()`: reset `_isPaused`, clear both service callbacks
- In `updateServiceStatus()`: sync `_isPaused` from service instance each refresh cycle
- Added `pauseService()` and `resumeService()` methods for future UI controls

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed lifecycle-service transitive dependency**

- **Found during:** Task 2 compilation
- **Issue:** `rns-android` declared `implementation("androidx.lifecycle:lifecycle-service:2.7.0")` which made `LifecycleService` supertype of `ReticulumService` invisible to the `rns-sample-app` module
- **Fix:** Changed to `api("androidx.lifecycle:lifecycle-service:2.7.0")` so the type is transitively available
- **Files modified:** `rns-android/build.gradle.kts`
- **Commit:** 686db2b

## Decisions Made

1. **Handler-based debounce** over coroutine-based delay: Handler posts on main looper which is natural for notification updates and avoids coroutine scheduling overhead for a simple timer
2. **Name-based interface categorization** with optional provider callback: Since `InterfaceRef` doesn't expose the underlying class type, we inspect the name for keywords (TCP, UDP, Auto, etc.) as a reasonable heuristic with escape hatch for ViewModel override
3. **5-second periodic update** with 3-second initial delay: Balances notification freshness with battery impact; 3-second delay ensures Reticulum has time to initialize
4. **api() scope for lifecycle-service**: The sample app (and any consumer of rns-android) needs to resolve the supertype of `ReticulumService`, so the lifecycle-service dependency must be transitively exposed

## Commits

| Hash | Message |
|------|---------|
| 3da03e9 | feat(14-03): wire debounced notification updates into ReticulumService |
| 686db2b | feat(14-03): wire ViewModel to service pause state and reconnect callback |

## Verification

- Both `rns-android` and `rns-sample-app` modules compile successfully
- `buildConnectionSnapshot` present in ReticulumService
- `buildActions.*isPaused` wiring confirmed in notification update
- `isPaused` StateFlow present in ViewModel
- `onReconnectRequested` and `onPauseStateChanged` wired in startService, cleared in stopService
- Debounce implemented at 500ms with Handler-based coalescing

## Next Phase Readiness

Phase 14 (Service Notification UX) is now complete:
- Plan 01: Connection state model and notification builder
- Plan 02: Notification quick actions (pause/resume/reconnect)
- Plan 03: Service notification wiring (this plan)

The notification system is fully functional: live-updating, debounced, with dynamic actions and tap-to-open. Ready for Phase 15 (battery optimization guidance UX).
