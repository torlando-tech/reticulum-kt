---
phase: 07-opportunistic-delivery
plan: 03
subsystem: lxmf-interop
tags: [opportunistic, interop, testing, python-bridge, bidirectional]
dependency-graph:
  requires: [07-02]
  provides: [complete-opportunistic-test-coverage, bidirectional-interop]
  affects: []
tech-stack:
  added: []
  patterns: [batch-delivery-testing, timeout-failure-testing, bidirectional-interop]
key-files:
  created:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/PythonToKotlinOpportunisticTest.kt
  modified:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/KotlinToPythonOpportunisticTest.kt
    - python-bridge/bridge_server.py
decisions:
  - key: batch-test-approach
    choice: "Test batch delivery with identity-known scenario"
    rationale: "Opportunistic sends immediately when identity known from setup step 8"
  - key: callback-method
    choice: "registerDeliveryCallback instead of setMessageCallback"
    rationale: "Correct API name in LXMRouter for incoming message handling"
metrics:
  duration: 3m 35s
  completed: 2026-01-24
---

# Phase 7 Plan 3: Extended Opportunistic Test Coverage Summary

Extended opportunistic delivery tests with batch delivery, timeout failure, and bidirectional Python-to-Kotlin interoperability.

## Tasks Completed

| Task | Description | Commit | Files |
|------|-------------|--------|-------|
| 1 | Add batch delivery and timeout tests to KotlinToPythonOpportunisticTest | f5111fb | KotlinToPythonOpportunisticTest.kt |
| 2 | Add lxmf_send_opportunistic command to Python bridge | a7e9ee0 | bridge_server.py |
| 3 | Create PythonToKotlinOpportunisticTest | a2babb1 | PythonToKotlinOpportunisticTest.kt |

## What Was Built

### Extended Kotlin-to-Python Tests
Added two new tests to `KotlinToPythonOpportunisticTest`:
- **Batch delivery test**: Verifies multiple messages to the same destination are all delivered. Since identity is known from setup, messages send immediately via broadcast.
- **Timeout failure test**: Verifies message to unreachable destination fails after max attempts with appropriate state/callback.

### Python Bridge Extension
Added `lxmf_send_opportunistic` command to the Python bridge server:
- Takes destination_hash, content, title, and optional fields
- Recalls identity from destination hash
- Creates OPPORTUNISTIC method LXMessage
- Calls handle_outbound to send

### Python-to-Kotlin Opportunistic Tests
Created new test class `PythonToKotlinOpportunisticTest` with:
- Basic opportunistic message reception after Kotlin announces
- Message content and title preservation verification
- Fields preservation through opportunistic delivery

## Key Patterns

### Opportunistic Delivery Behavior
- Messages send immediately via broadcast when identity is known
- No path/announce required for delivery (identity recall sufficient)
- Announce provides path optimization, not delivery enablement

### Bidirectional Testing
- K->P tests extend OpportunisticDeliveryTestBase
- P->K tests also extend OpportunisticDeliveryTestBase
- Both use same infrastructure (TCP connection, router setup)

## Deviations from Plan

None - plan executed exactly as written.

## Verification Results

All opportunistic tests pass:
- `KotlinToPythonOpportunisticTest` (6 tests including new batch and timeout)
- `PythonToKotlinOpportunisticTest` (3 tests)

## Next Steps

Phase 7 complete. Opportunistic delivery is fully tested with:
- K->P basic delivery
- K->P batch delivery
- K->P timeout failure
- P->K basic delivery
- P->K field preservation
