---
phase: 15-battery-optimization-ux
plan: 03
subsystem: android-battery-ux
tags: [compose-ui, canvas-chart, bottom-sheet, viewmodel, stateflow]
depends_on:
  requires: [15-01, 15-02]
  provides: [monitor-battery-card, exemption-sheet, optimization-warning]
  affects: []
tech_stack:
  added: []
  patterns: [compose-canvas-chart, modal-bottom-sheet, stateflow-collectAsState]
key_files:
  created: []
  modified:
    - rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/viewmodel/ReticulumViewModel.kt
    - rns-sample-app/src/main/kotlin/tech/torlando/reticulumkt/ui/screens/MonitorScreen.kt
decisions:
  - id: "15-03-01"
    decision: "Compose Canvas for line chart (no external charting library)"
    reason: "Simple line chart only needs Canvas + Path; avoids dependency for one chart"
  - id: "15-03-02"
    decision: "ModalBottomSheet for exemption flow (not dialog or new screen)"
    reason: "Bottom sheet is the standard Android pattern for contextual actions that don't navigate away"
  - id: "15-03-03"
    decision: "ViewModel drives exemption sheet visibility via StateFlow"
    reason: "Keeps UI state in ViewModel for testability; Compose just observes"
metrics:
  duration: "2min"
  completed: "2026-02-05"
---

# Phase 15 Plan 03: Monitor Screen Battery UX Summary

**One-liner:** Enhanced Monitor battery card with drain rate, Canvas chart, inline optimization warning, and exemption bottom sheet

## What Was Done

### Task 1: Wired battery stats and events into ViewModel
- Added `batteryStats`, `serviceEvents`, `showExemptionSheet` StateFlows
- Collection from service trackers in `startService()` flow
- First-start exemption prompt via `BatteryExemptionHelper.shouldPrompt()`
- Action methods: `dismissExemptionSheet()`, `showExemptionSheet()`, `dismissOptimizationWarning()`, `requestBatteryExemption(): Intent`

### Task 2: Enhanced Monitor screen with battery UX
- **Battery card:** Level, drain rate (%/hr), session drain rate, power save status
- **Canvas chart:** Line chart of battery level history (last 4 hours), gridlines at 25/50/75%, filled area below line, "Collecting data..." placeholder when < 2 samples
- **Optimization warning:** Inline card with kill count, warning icon, "Fix" and "Dismiss" buttons; only shows when `hasActiveWarning && !batteryExempt`
- **Exemption sheet:** ModalBottomSheet explaining impacts (dropped connections, missed messages, delayed routing), one-tap "Exempt from Battery Optimization" button launching system dialog, "Not Now" dismissal

### Task 3: Checkpoint (human-verify)
- Built and installed on physical device (SM-G998U1) and emulator
- User verified battery card, chart area, notification actions
- During verification, discovered and fixed two bugs:
  1. Notification action broadcasts were implicit (silently dropped on Android 8+)
  2. Service pause only set job interval (didn't actually pause Transport)

## Deviations from Plan

### Bug Fixes Discovered During Verification

**1. [Bug] Notification action intents were implicit broadcasts**
- **Found during:** Checkpoint verification
- **Issue:** `Intent(ACTION).setPackage(pkg)` is still implicit on Android 8+; manifest-registered receivers silently ignore them
- **Fix:** Changed to explicit `Intent(context, NotificationActionReceiver::class.java)` with action set
- **Commit:** fb0fd28

**2. [Bug] Service pause didn't actually pause Transport**
- **Found during:** Checkpoint verification (path counter kept incrementing)
- **Issue:** `customJobIntervalMs = Long.MAX_VALUE` had no effect — coroutine captured interval once at startup; interfaces still fed packets to Transport
- **Fix:** Added `Transport.paused` AtomicBoolean; gates `inbound()`, `outbound()`, and `runJobs()`; both job loops now re-read interval each iteration
- **Commit:** 56a2c4f

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 8c22473 | feat | Wire battery stats and events into ViewModel |
| 1900177 | feat | Enhance Monitor battery card with stats, chart, warning, and exemption sheet |
| fb0fd28 | fix | Use explicit intents for notification action broadcasts |
| 56a2c4f | fix | Implement Transport-level pause for service pause/resume |

## Next Phase Readiness

Phase 16 (OEM Compatibility) can now:
- Build on the exemption flow pattern for OEM-specific battery settings
- Use ServiceEventTracker kill counts as evidence of OEM restrictions
- Extend the Monitor screen with manufacturer-specific guidance cards
