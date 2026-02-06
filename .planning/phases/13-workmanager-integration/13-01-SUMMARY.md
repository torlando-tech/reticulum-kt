---
phase: 13-workmanager-integration
plan: 01
subsystem: android
tags: [workmanager, doze, battery, recovery, periodic-worker]

# Dependency graph
requires:
  - phase: 10-android-service-foundation
    provides: ReticulumService foreground service, BatteryMonitor, ConnectionPolicy
  - phase: 12-doze-aware-connection-management
    provides: ConnectionPolicyProvider, battery throttle thresholds
provides:
  - Recovery-focused ReticulumWorker with interface health, Transport maintenance, and service restart
  - Battery-aware task execution (skips non-critical work in low battery)
  - Diagnostic output data for worker monitoring
affects: [13-02 service wiring, 13-03 boot receiver, 15-ui-settings]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "WorkManager inputData for passing config to workers (avoids DataStore dependency)"
    - "BatteryManager point-in-time read in worker (no long-lived monitor needed)"
    - "No network constraint on WorkRequest for mesh routing support"

key-files:
  created: []
  modified:
    - rns-android/src/main/kotlin/network/reticulum/android/ReticulumWorker.kt

key-decisions:
  - "inputData for auto-restart flag keeps library module independent of app DataStore"
  - "BatteryManager direct read in worker instead of BatteryMonitor instance (worker is short-lived)"
  - "Interface health check is logging-only; reconnection handled by TCPClientInterface reconnect loop"

patterns-established:
  - "Worker recovery pattern: health check, maintenance, service restart (in priority order)"
  - "Battery-aware worker: skip non-critical, always run critical tasks"

# Metrics
duration: 2min
completed: 2026-02-05
---

# Phase 13 Plan 01: ReticulumWorker Enhancement Summary

**Recovery-focused periodic worker with interface health logging, Transport maintenance, and conditional service restart with battery awareness**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-06T00:58:27Z
- **Completed:** 2026-02-06T01:01:00Z
- **Tasks:** 2 (combined into 1 commit - overlapping scope)
- **Files modified:** 1

## Accomplishments
- Enhanced ReticulumWorker from skeleton (just Transport.runMaintenanceJobs()) to full recovery worker
- Three recovery tasks in priority order: interface health check, Transport maintenance, service restart
- Battery-aware execution using ConnectionPolicy.BATTERY_THROTTLE_THRESHOLD (15%)
- Diagnostic output data on Result.success() for monitoring (interface counts, service status, timing)
- Auto-restart via WorkManager inputData (avoids DataStore/SharedPreferences dependency)

## Task Commits

Each task was committed atomically:

1. **Task 1+2: Enhance ReticulumWorker with recovery-focused doWork() and diagnostics** - `f19608e` (feat)

Tasks 1 and 2 were combined into a single commit because they both modified the same file with heavily overlapping concerns (doWork implementation, diagnostic output, logging, and constraint verification were naturally intertwined).

## Files Created/Modified
- `rns-android/src/main/kotlin/network/reticulum/android/ReticulumWorker.kt` - Recovery-focused periodic worker with 3 recovery tasks, battery awareness, and diagnostic output

## Decisions Made
- **inputData over DataStore:** Passing auto-restart flag via WorkManager inputData keeps the rns-android library module independent of any app-specific DataStore or SharedPreferences setup
- **BatteryManager direct read:** Worker uses BatteryManager system service directly for a point-in-time battery reading instead of instantiating a BatteryMonitor (worker is short-lived, no need for continuous monitoring)
- **Logging-only health check:** Interface health check only logs status; reconnection is already handled by TCPClientInterface's built-in reconnect loop from Phase 12-04
- **Tasks combined:** Tasks 1 and 2 were naturally combined since diagnostic output (Task 2) was integral to the doWork() implementation (Task 1)

## Deviations from Plan

None - plan executed exactly as written. Tasks 1 and 2 were combined into a single commit due to overlapping scope but all specified functionality was implemented.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- ReticulumWorker ready for service wiring in Plan 13-02
- schedule() accepts autoRestart parameter for service lifecycle integration
- cancel() available for stop-means-stop behavior

---
*Phase: 13-workmanager-integration*
*Completed: 2026-02-05*
