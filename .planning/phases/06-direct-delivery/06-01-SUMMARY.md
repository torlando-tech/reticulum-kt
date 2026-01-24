---
phase: 06-direct-delivery
plan: 01
subsystem: lxmf-interop
tags: [lxmf, reticulum, tcp, interop, testing]

dependency-graph:
  requires:
    - phase-05 (stamp interop for message validation)
    - python-bridge (existing infrastructure)
  provides:
    - live Reticulum networking between Kotlin and Python
    - LXMF router lifecycle management
    - DirectDeliveryTestBase for end-to-end tests
  affects:
    - plan-06-02 (Kotlin-to-Python message delivery)
    - plan-06-03 (Python-to-Kotlin message delivery)

tech-stack:
  added:
    - rns-interfaces dependency in lxmf-core tests
  patterns:
    - TCP client/server for RNS interop testing
    - Module reimport for clean LXMF initialization

key-files:
  created:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliveryTestBase.kt
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliverySmokeTest.kt
  modified:
    - python-bridge/bridge_server.py
    - lxmf-core/build.gradle.kts

decisions:
  - decision: "Suppress RNS logging (LOG_CRITICAL) to avoid polluting JSON protocol"
    rationale: "RNS logs to stdout by default, breaking bridge JSON parsing"
  - decision: "Patch multiprocessing.set_start_method to handle LXMF reimport"
    rationale: "LXMF.LXStamper calls set_start_method without force=True, fails on reimport"
  - decision: "Cache RNS module and clear LXMF on reimport"
    rationale: "Ensures LXMF uses same RNS instance for isinstance checks"
  - decision: "Add 10 second timeout for TCP connection wait"
    rationale: "Async connection needs time; 500ms was insufficient"

metrics:
  duration: 11 min
  completed: 2026-01-24
---

# Phase 06 Plan 01: Direct Delivery Test Infrastructure Summary

Live Reticulum/LXMF networking infrastructure for end-to-end direct delivery testing between Kotlin and Python.

## What Was Built

### Python Bridge Commands (6 new)
Extended bridge_server.py with live Reticulum networking:

1. **rns_start** - Start Reticulum with TCP server interface
   - Creates temp config directory
   - Suppresses logging to avoid JSON pollution
   - Returns transport identity hash

2. **rns_stop** - Clean Reticulum shutdown

3. **lxmf_start_router** - Start LXMF router with delivery destination
   - Creates/restores identity
   - Registers delivery callback
   - Stores received messages globally

4. **lxmf_get_messages** - Retrieve received LXMF messages

5. **lxmf_clear_messages** - Clear received messages list

6. **lxmf_send_direct** - Send LXMF via DIRECT delivery

### DirectDeliveryTestBase (228 lines)
Test base class managing Kotlin+Python Reticulum lifecycle:

- Starts Python RNS with TCP server via bridge
- Starts Python LXMF router with delivery destination
- Starts Kotlin Reticulum with TCP client
- Creates Kotlin LXMF router with delivery identity
- Connection wait loop with 10s timeout
- Utility methods: getPythonMessages(), clearPythonMessages(), waitForPythonMessages()

### Smoke Test (3 tests)
Validates infrastructure functionality:
- Infrastructure starts successfully (both sides have destinations)
- TCP connection is active (client connected)
- Python messages list starts empty

## Technical Challenges Solved

### 1. RNS Logging Polluting JSON
RNS outputs logs to stdout by default, breaking JSON protocol parsing.
**Solution:** Set `RNS.loglevel = RNS.LOG_CRITICAL` before starting.

### 2. Multiprocessing Context Already Set
LXMF.LXStamper calls `multiprocessing.set_start_method("fork")` at import time
without `force=True`, failing on reimport in Python 3.14+.
**Solution:** Patch `set_start_method` to silently ignore if already set.

### 3. LXMF Using Different RNS Module
When reimporting RNS after bridge startup, LXMF retains reference to old RNS,
causing `isinstance(identity, RNS.Identity)` to fail.
**Solution:** Clear both RNS and LXMF modules, reimport cleanly.

### 4. Async TCP Connection Timing
TCPClientInterface.start() launches connection in coroutine; 500ms wait insufficient.
**Solution:** Add polling loop with 10s timeout waiting for `online.get()`.

## Files Changed

| File | Change |
|------|--------|
| python-bridge/bridge_server.py | +276 lines (6 commands, multiprocessing fix, logging fix) |
| lxmf-core/build.gradle.kts | +1 line (rns-interfaces test dependency) |
| DirectDeliveryTestBase.kt | New file (228 lines) |
| DirectDeliverySmokeTest.kt | New file (36 lines) |

## Commits

| Hash | Message |
|------|---------|
| d188975 | feat(06-01): add Reticulum/LXMF networking commands to bridge |
| b5a3fdf | feat(06-01): create DirectDeliveryTestBase with Reticulum lifecycle |
| b17dcc1 | test(06-01): add DirectDeliverySmokeTest verifying infrastructure |

## Verification

All verification criteria met:
- [x] Python bridge loads without import errors
- [x] 6 new bridge commands respond to requests
- [x] DirectDeliveryTestBase compiles (228 lines)
- [x] Smoke test passes: infrastructure starts, TCP connects, routers initialize

## Next Phase Readiness

Infrastructure ready for:
- Plan 06-02: Kotlin-to-Python message delivery tests
- Plan 06-03: Python-to-Kotlin message delivery tests

No blockers. TCP connection established, both LXMF routers running.
