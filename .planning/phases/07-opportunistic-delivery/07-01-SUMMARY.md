---
phase: 07-opportunistic-delivery
plan: 01
subsystem: messaging
tags: [lxmf, opportunistic-delivery, path-discovery, transport]

# Dependency graph
requires:
  - phase: 06-direct-delivery
    provides: Direct delivery implementation with working LXMRouter
provides:
  - Aligned opportunistic delivery constants matching Python LXMF
  - Path request logic for opportunistic delivery
  - Path rediscovery mechanism for failed deliveries
affects: [07-02, 07-03, propagation-delivery]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Path request after MAX_PATHLESS_TRIES attempts"
    - "Path rediscovery at MAX_PATHLESS_TRIES+1 with delay"

key-files:
  created: []
  modified:
    - lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt

key-decisions:
  - "Use Transport.expirePath() for path removal (equivalent to Python's drop_path)"
  - "Match Python's 0.5s delay before requesting new path during rediscovery"

patterns-established:
  - "Opportunistic path request: deliveryAttempts >= MAX_PATHLESS_TRIES && !hasPath"
  - "Opportunistic path rediscovery: deliveryAttempts == MAX_PATHLESS_TRIES+1 && hasPath"

# Metrics
duration: 5min
completed: 2026-01-24
---

# Phase 7 Plan 1: Opportunistic Delivery Constants Summary

**Aligned Kotlin opportunistic delivery constants and logic with Python LXMF - MAX_PATHLESS_TRIES=1, path request after pathless attempts, path rediscovery on continued failure**

## Performance

- **Duration:** 5 min
- **Started:** 2026-01-24T00:00:00Z
- **Completed:** 2026-01-24T00:05:00Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments
- Renamed PATHLESS_DELIVERY_ATTEMPTS (value 3) to MAX_PATHLESS_TRIES (value 1) matching Python
- Enhanced processOpportunisticDelivery with path request logic matching Python lines 2554-2581
- Implemented path rediscovery with Transport.expirePath + delayed Transport.requestPath

## Task Commits

Each task was committed atomically:

1. **Task 1: Align constants with Python LXMF reference** - `65d7138` (feat)
2. **Task 2: Enhance processOpportunisticDelivery with Python path logic** - `59f6a29` (feat)

## Files Created/Modified
- `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt` - Updated constants and processOpportunisticDelivery function

## Decisions Made
- Used Transport.expirePath() for path removal (equivalent to Python's Reticulum.drop_path)
- Matched Python's 0.5s delay before requesting new path during rediscovery phase
- Check delivery attempts BEFORE incrementing (matching Python's <= check structure)

## Deviations from Plan
None - plan executed exactly as written.

## Issues Encountered
- Type mismatch with nextDeliveryAttempt being Long? (nullable) - fixed by using safe call with default value

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Constants aligned with Python LXMF reference
- processOpportunisticDelivery has proper path request logic
- Ready for 07-02 (LXMessage method selection) and 07-03 (integration tests)

---
*Phase: 07-opportunistic-delivery*
*Completed: 2026-01-24*
