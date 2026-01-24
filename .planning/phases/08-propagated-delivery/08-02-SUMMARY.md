---
phase: 08-propagated-delivery
plan: 02
subsystem: lxmf
tags: [propagation, lxmf, interop, testing]

# Dependency graph
requires:
  - phase: 08-01
    provides: PropagatedDeliveryTestBase and initial propagation node setup
provides:
  - LXMRouter.addPropagationNode() method for direct node registration
  - Reliable propagation node setup in test infrastructure
affects: [08-03, propagated delivery tests]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Direct propagation node registration bypassing announce flow"]

key-files:
  created: []
  modified:
    - "lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt"
    - "lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/PropagatedDeliveryTestBase.kt"

key-decisions:
  - "Add addPropagationNode() to bypass announce parsing for test scenarios"
  - "Call addPropagationNode() before setActivePropagationNode() for reliable setup"

patterns-established:
  - "Direct node registration: Use addPropagationNode() when node details are known out-of-band"

# Metrics
duration: 4min
completed: 2026-01-24
---

# Phase 8 Plan 02: Gap Closure - Propagation Node Registration Summary

**LXMRouter addPropagationNode() method enables direct propagation node registration without announce parsing, fixing test setup failures**

## Performance

- **Duration:** 4 min
- **Started:** 2026-01-24T14:45:00Z
- **Completed:** 2026-01-24T14:49:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `addPropagationNode()` public method to LXMRouter
- Fixed PropagatedDeliveryTestBase to reliably set active propagation node
- All 4 propagated delivery tests pass with proper node setup

## Task Commits

Each task was committed atomically:

1. **Task 1: Add addPropagationNode method to LXMRouter** - `4759a23` (feat)
2. **Task 2: Update PropagatedDeliveryTestBase to use addPropagationNode** - `a09da58` (feat)

## Files Created/Modified
- `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt` - Added addPropagationNode() method
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/PropagatedDeliveryTestBase.kt` - Updated setup to use addPropagationNode before setActivePropagationNode

## Decisions Made
- Added `addPropagationNode()` method instead of modifying announce parsing - simpler solution for test scenarios where node details are known through bridge protocol
- Removed try/catch around setActivePropagationNode since it now succeeds reliably after addPropagationNode

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - the gap was clearly identified in verification, and the fix was straightforward.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Propagation node registration is now reliable in test infrastructure
- All 4 propagated delivery tests pass:
  - Kotlin can submit message to Python propagation node
  - Kotlin can retrieve message from Python propagation node
  - Propagation node rejects message with insufficient stamp
  - Message with fields survives propagated delivery round-trip
- Ready for plan 03: Extended propagation tests (message retrieval, sync flows)

---
*Phase: 08-propagated-delivery*
*Completed: 2026-01-24*
