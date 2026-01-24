---
phase: 07-opportunistic-delivery
plan: 02
subsystem: testing
tags: [lxmf, opportunistic, broadcast, interop, junit, kotest]

# Dependency graph
requires:
  - phase: 06-direct-delivery
    provides: DirectDeliveryTestBase infrastructure
  - phase: 07-01
    provides: Opportunistic delivery constants aligned with Python
provides:
  - OpportunisticDeliveryTestBase for testing announce-controlled scenarios
  - KotlinToPythonOpportunisticTest validating broadcast delivery
  - Pattern for testing opportunistic delivery without pre-announce
affects: [07-03, integration-testing]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Override setup to control announce timing for opportunistic tests
    - Test immediate broadcast delivery when identity known
    - Validate path optimization after announce

key-files:
  created:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/OpportunisticDeliveryTestBase.kt
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/KotlinToPythonOpportunisticTest.kt
  modified:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliveryTestBase.kt

key-decisions:
  - "Added open modifier to DirectDeliveryTestBase.setupDirectDelivery() to enable override"
  - "Opportunistic delivery sends immediately via broadcast when identity known (no queueing)"
  - "Announce provides path optimization, not delivery enablement for opportunistic"

patterns-established:
  - "OpportunisticDeliveryTestBase: skip step 9 (announce) in setup for controlled testing"
  - "triggerPythonAnnounce() controls when path becomes available"
  - "waitForKotlinToReceiveAnnounce() verifies path propagation"

# Metrics
duration: 7min
completed: 2026-01-24
---

# Phase 7 Plan 02: Opportunistic Delivery Test Infrastructure Summary

**Test base and K->P tests validating LXMF opportunistic broadcast delivery - immediate send when identity known**

## Performance

- **Duration:** 7 min
- **Started:** 2026-01-24T18:48:38Z
- **Completed:** 2026-01-24T18:55:36Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Created OpportunisticDeliveryTestBase extending DirectDeliveryTestBase with announce control
- Validated opportunistic delivery sends immediately when destination identity is known
- Verified path optimization after announce (routing efficiency)
- Confirmed multiple messages delivered and callbacks fire

## Task Commits

Each task was committed atomically:

1. **Task 1: Create OpportunisticDeliveryTestBase** - `f06349a` (feat)
2. **Task 2: Create KotlinToPythonOpportunisticTest** - `bbfd18b` (test)

## Files Created/Modified
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/OpportunisticDeliveryTestBase.kt` - Test base with setup override skipping announce, plus announce control methods
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/KotlinToPythonOpportunisticTest.kt` - 4 tests for opportunistic K->P delivery
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliveryTestBase.kt` - Added `open` modifier to setupDirectDelivery()

## Decisions Made

1. **Added `open` to DirectDeliveryTestBase.setupDirectDelivery()** - Required for OpportunisticDeliveryTestBase to override. Minimal change (1 word) that doesn't affect existing behavior. Applied as Rule 3 (Blocking) deviation.

2. **Revised test approach for opportunistic delivery** - Original plan assumed messages queue when path unknown. Investigation revealed opportunistic delivery sends immediately via broadcast when identity is known (which it is after setup step 8). Tests updated to verify actual opportunistic behavior.

3. **Tests verify broadcast delivery, not queuing** - Opportunistic messages are sent as encrypted broadcast packets immediately. The "announce triggers delivery" pattern applies to DIRECT delivery (link-based), not opportunistic.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added open modifier to enable override**
- **Found during:** Task 1 (OpportunisticDeliveryTestBase creation)
- **Issue:** setupDirectDelivery() in DirectDeliveryTestBase was final, preventing override
- **Fix:** Added `open` modifier to the method
- **Files modified:** DirectDeliveryTestBase.kt (1 word change)
- **Verification:** Compilation succeeds, Phase 6 tests still pass
- **Committed in:** f06349a (Task 1 commit)

**2. [Rule 1 - Bug] Fixed JUnit test discovery issue**
- **Found during:** Task 2 (running tests)
- **Issue:** Tests returning String instead of Unit due to runBlocking returning last expression
- **Fix:** Added `Unit` at end of test functions
- **Files modified:** KotlinToPythonOpportunisticTest.kt
- **Verification:** Tests are discovered and run
- **Committed in:** bbfd18b (Task 2 commit)

**3. [Rule 1 - Bug] Revised test assertions to match actual behavior**
- **Found during:** Task 2 (first test failure)
- **Issue:** Test expected message to queue when path unknown, but opportunistic delivery sends immediately via broadcast
- **Fix:** Updated tests to verify actual opportunistic behavior (immediate send when identity known)
- **Files modified:** KotlinToPythonOpportunisticTest.kt
- **Verification:** All 4 tests pass
- **Committed in:** bbfd18b (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking)
**Impact on plan:** Tests now accurately verify opportunistic delivery behavior. No scope creep - tests still validate K->P opportunistic delivery as intended.

## Issues Encountered

- Initial test assumption was incorrect about queueing behavior. Investigation of Python reference implementation revealed opportunistic delivery sends immediately via broadcast. Tests revised to match actual protocol behavior.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- OpportunisticDeliveryTestBase ready for Python-to-Kotlin tests in 07-03
- Pattern established: override setup, control announce timing, verify delivery
- All Phase 6 tests verified still working with DirectDeliveryTestBase change

---
*Phase: 07-opportunistic-delivery*
*Completed: 2026-01-24*
