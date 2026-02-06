---
phase: 14-service-notification-ux
plan: 02
subsystem: android-notification
tags: [notification, quick-actions, broadcast-receiver, pause-resume, reconnect]
dependency_graph:
  requires: [14-01]
  provides: [NotificationActionReceiver, pause/resume/reconnect API, action constants]
  affects: [14-03]
tech_stack:
  added: []
  patterns: [BroadcastReceiver-dispatch, static-instance-access, PendingIntent-actions]
key_files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/NotificationActionReceiver.kt
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/ReticulumService.kt
    - rns-android/src/main/AndroidManifest.xml
decisions:
  - id: 14-02-01
    description: "Static instance pattern for BroadcastReceiver-to-Service communication"
    rationale: "BroadcastReceiver needs synchronous access to service; binding is async and heavyweight for quick actions"
  - id: 14-02-02
    description: "Long.MAX_VALUE for pause instead of stopping coroutine job loop"
    rationale: "Transport.stopCoroutineJobLoop is private; setting interval to max effectively freezes without API changes"
  - id: 14-02-03
    description: "Reconnect hidden when paused"
    rationale: "Reconnecting while Transport is frozen would be contradictory; user must resume first"
  - id: 14-02-04
    description: "Callback-based onReconnectRequested instead of direct interface manipulation"
    rationale: "Service doesn't own InterfaceManager; ViewModel wires callback to onNetworkChanged()"
metrics:
  duration: 2min
  completed: 2026-02-05
---

# Phase 14 Plan 02: Notification Quick Actions Summary

**One-liner:** BroadcastReceiver + service pause/resume/reconnect API with dynamic notification action buttons

## What Was Done

### Task 1: Add pause/resume/reconnect methods to ReticulumService
- Added `_isPaused` state tracking with public `isPaused` read access
- Added `sessionStartTimeMs` for uptime tracking in notifications
- Added `pause()`: sets isPaused, freezes Transport via `customJobIntervalMs = Long.MAX_VALUE`, cancels WorkManager
- Added `resume()`: restores policy-based interval, re-schedules WorkManager, triggers reconnection
- Added `reconnectInterfaces()`: logs interface count, invokes `onReconnectRequested` callback
- Added `onPauseStateChanged` and `onReconnectRequested` callback fields
- Added `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_RECONNECT` constants in companion
- Added static `getInstance()` / `instance` for BroadcastReceiver access (set in onCreate/onDestroy)
- Set `sessionStartTime` in onStartCommand before initialization

### Task 2: Create NotificationActionReceiver and register in manifest
- Created `NotificationActionReceiver` BroadcastReceiver dispatching three action intents
- `onReceive()` matches ACTION_PAUSE/RESUME/RECONNECT and calls corresponding service methods
- Logs warning if service instance is null (service not running)
- `buildActions()` companion function produces dynamic button list:
  - Running state: Reconnect + Pause buttons
  - Paused state: Resume button only
- Uses distinct PendingIntent request codes (0 for reconnect, 1 for pause/resume)
- All PendingIntents use FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT
- Registered in AndroidManifest as `android:exported="false"`

## Deviations from Plan

None - plan executed exactly as written.

## Commits

| # | Hash | Description |
|---|------|-------------|
| 1 | f4511a6 | feat(14-02): add pause/resume/reconnect methods to ReticulumService |
| 2 | 2186f34 | feat(14-02): create NotificationActionReceiver and register in manifest |

## Verification

- `./gradlew :rns-android:compileDebugKotlin` passes (BUILD SUCCESSFUL)
- ReticulumService.pause() sets isPaused=true and freezes Transport
- ReticulumService.resume() restores Transport interval and reconnects
- NotificationActionReceiver dispatches actions to service instance
- AndroidManifest includes receiver registration (non-exported)
- buildActions() returns Reconnect + Pause when running, Resume only when paused

## Next Phase Readiness

Plan 14-03 can now:
- Wire `onPauseStateChanged` callback to trigger notification refresh with new action buttons
- Use `buildActions(context, isPaused)` when building notifications
- Access `sessionStartTimeMs` and `isPaused` from service instance for ConnectionSnapshot
- Build the full notification update loop that ties everything together
