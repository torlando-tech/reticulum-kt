---
phase: 14-service-notification-ux
plan: 01
subsystem: android-notification
tags: [notification, foreground-service, connection-state, android]
dependency_graph:
  requires: [phase-10]
  provides: [ServiceConnectionState, NotificationContentBuilder, ConnectionSnapshot]
  affects: [14-02, 14-03]
tech_stack:
  added: []
  patterns: [enum-state-model, snapshot-pattern, BigTextStyle-notification]
key_files:
  created:
    - rns-android/src/main/kotlin/network/reticulum/android/ServiceConnectionState.kt
    - rns-android/src/main/kotlin/network/reticulum/android/NotificationContentBuilder.kt
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/NotificationHelper.kt
decisions:
  - id: 14-01-01
    description: "em dash separator in notification content text for clean visual"
  - id: 14-01-02
    description: "Interface detail in parentheses in expanded view for readability"
  - id: 14-01-03
    description: "Sort interface breakdown by count descending (most common type first)"
metrics:
  duration: 1.5min
  completed: 2026-02-05
---

# Phase 14 Plan 01: Connection State Model and Notification Builder Summary

**One-liner:** Five-state connection model with rich BigTextStyle notification builder showing color-coded status, interface breakdown, and uptime

## What Was Built

### ServiceConnectionState Enum
Five connection states derived from interface data:
- **CONNECTED** -- all configured interfaces online
- **PARTIAL** -- some but not all interfaces online
- **CONNECTING** -- service started, no interfaces online yet
- **DISCONNECTED** -- service running, all interfaces offline
- **PAUSED** -- user explicitly paused via notification action

### Data Model
- `InterfaceSnapshot` -- point-in-time interface status (name, type, online, detail)
- `ConnectionSnapshot` -- complete state bundle (state, interfaces, session start, transport mode, paused flag)

### State Computation Functions
- `computeConnectionState(interfaces, isPaused)` -- derives state from interface list
- `formatInterfaceBreakdown(interfaces)` -- "2 TCP, 1 UDP" style grouping of online interfaces
- `formatUptime(sessionStartTime)` -- "2h 34m", "45m", "< 1m" duration formatting

### NotificationContentBuilder
Rich notification builder producing:
- Title: "Reticulum Transport" or "Reticulum Client"
- Collapsed view: "Connected -- 2 TCP, 1 UDP" / "Partial -- 1 TCP online" / "Connecting..." / etc.
- Expanded BigTextStyle with uptime line and per-interface status lines
- Color-coded accent: green (connected), orange (partial), yellow (connecting), red (disconnected), gray (paused)
- Silent, ongoing, no-badge, FOREGROUND_SERVICE_IMMEDIATE

### NotificationHelper Enhancements
Two new overloads accepting `ConnectionSnapshot`:
- `createServiceNotification(snapshot, contentIntent?, actions?)` -- delegates to NotificationContentBuilder
- `updateNotification(notificationId, snapshot, contentIntent?, actions?)` -- updates in-place

All existing methods preserved for backward compatibility.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | 39c155e | ServiceConnectionState enum and computation logic |
| 2 | b545a83 | NotificationContentBuilder and NotificationHelper enhancements |

## Decisions Made

| ID | Decision | Rationale |
|----|----------|-----------|
| 14-01-01 | Em dash separator in content text | Clean visual separation between state label and breakdown |
| 14-01-02 | Interface detail in parentheses in expanded view | "TCP testnet (10.0.0.1:4242) -- Online" readable format |
| 14-01-03 | Sort breakdown by count descending | Most common interface type appears first |

## Deviations from Plan

None -- plan executed exactly as written.

## Verification

- `./gradlew :rns-android:compileDebugKotlin` -- BUILD SUCCESSFUL
- ServiceConnectionState.kt has 5 enum values
- NotificationContentBuilder produces Notification objects with BigTextStyle
- NotificationHelper has both old and new overloads
- No existing code broken

## Next Phase Readiness

Plan 14-02 (Quick Actions) can wire `NotificationCompat.Action` objects into the `actions` parameter already accepted by both `NotificationContentBuilder.buildNotification()` and `NotificationHelper.createServiceNotification(snapshot)`.

Plan 14-03 (Service Integration) can construct `ConnectionSnapshot` from live interface data and pass it through the new notification builder pipeline.
