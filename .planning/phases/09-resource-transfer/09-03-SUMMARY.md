---
phase: "09"
plan: "03"
title: "Resource Protocol Fix"
type: gap-closure
completed: "2026-01-24"
duration: ~10m

subsystem: resource-transfer
tags: [resource, proof-routing, protocol-fix, interop]

dependency-graph:
  requires: ["09-01", "09-02"]
  provides: ["working-resource-protocol", "bidirectional-large-message-delivery"]
  affects: ["lxmf-core", "rns-core"]

tech-stack:
  patterns:
    - "RESOURCE_PRF proof routing to active links"
    - "64-byte proof format (32 hash + 32 proof)"

key-files:
  modified:
    - "rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt"
    - "rns-core/src/main/kotlin/network/reticulum/link/Link.kt"
    - "rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt"
    - "lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/ResourceDeliveryTest.kt"
  created:
    - "test-scripts/debug_resource_flow.py"

decisions:
  - id: "proof-routing"
    description: "RESOURCE_PRF proofs routed through activeLinks in Transport"
    rationale: "Proofs use link ID as destination and must be delivered to Link for processing"
  - id: "proof-format"
    description: "Proof format is 64 bytes: [resource_hash (32 bytes)][proof (32 bytes)]"
    rationale: "Python sends full hash, not truncated; matches Python validate_proof expectations"

metrics:
  tasks-completed: 3
  tests-added: 0
  tests-modified: 1
  bugs-fixed: 2
---

# Phase 09 Plan 03: Resource Protocol Fix Summary

## One-liner
Fixed RESOURCE_PRF proof routing to activeLinks and corrected proof format from 48 to 64 bytes, enabling bidirectional large message delivery.

## What Was Done

### Task 1: Created diagnostic Python script
Created `test-scripts/debug_resource_flow.py` that traces the entire Resource protocol:
- Logs RESOURCE_ADV advertisement parsing
- Traces RESOURCE_REQ requests with hash details
- Monitors individual part receipts
- Captures proof sending
- Enables RNS extreme logging

**Commit:** `f438606` - feat(09-03): add Resource protocol debugging script

### Task 2: Fixed Resource protocol bugs

**Bug 1: RESOURCE_PRF proof not delivered to links**
- Transport.processProof() only checked pendingLinks for LRPROOF context
- RESOURCE_PRF proofs use link ID as destination but weren't being routed to activeLinks
- Fix: Added RESOURCE_PRF handling that checks activeLinks and calls Link.receive()

**Bug 2: Proof format mismatch**
- Kotlin expected 48 bytes: [hash (16)][proof (32)]
- Python sends 64 bytes: [hash (32)][proof (32)]
- Fix: Updated Link.processResourceProof() and Resource.validateProof() to expect 64 bytes

**Commit:** `1b3b639` - fix(09-03): fix Resource protocol proof routing and format

### Task 3: Tightened test assertions
- K->P test now requires DELIVERED state (not just progress)
- P->K test requires message receipt
- Bidirectional test requires both directions to succeed
- Removed comments about "known timing issues"
- All 5 ResourceDeliveryTest tests pass with strict assertions

**Commit:** `d482e5b` - test(09-03): tighten Resource delivery test assertions

## Deviations from Plan

None - plan executed exactly as written.

## Technical Details

### Proof Routing Fix (Transport.kt)
```kotlin
// Handle RESOURCE_PRF (Resource Proof) - deliver to active link
if (packet.context == PacketContext.RESOURCE_PRF) {
    val activeLink = activeLinks[packet.destinationHash.toKey()]
    if (activeLink != null) {
        log("Delivering RESOURCE_PRF to active link ${packet.destinationHash.toHexString()}")
        val receiveMethod = activeLink::class.java.getMethod("receive", Packet::class.java)
        receiveMethod.invoke(activeLink, packet)
        return
    }
}
```

### Proof Format Fix (Resource.kt)
```kotlin
// Proof format: [resource_hash (32 bytes)][proof (32 bytes)]
// Python sends full hash (32 bytes), not truncated (16 bytes)
if (proofData.size != RnsConstants.FULL_HASH_BYTES * 2) {
    log("Invalid proof length: ${proofData.size}")
    return false
}
```

## Verification Results

All verification criteria met:
- Resource advertisement sent by Kotlin is received and parsed by Python
- Resource request sent by Python is received and parsed by Kotlin
- Resource parts sent by Kotlin are received and assembled by Python
- Resource proof sent by Python is received and validated by Kotlin
- Large LXMF message (500 bytes) reaches DELIVERED state within 60 seconds

Test results:
```
ResourceDeliveryTest - 5/5 passed
ResourceCompressionTest - 4/4 passed
ResourceProgressTest - 2/2 passed
LiveDeliveryTest - 3/3 passed (no regressions)
```

## Files Modified

| File | Changes |
|------|---------|
| Transport.kt | Added RESOURCE_PRF handling in processProof() |
| Link.kt | Fixed proof format expectation (64 bytes) |
| Resource.kt | Fixed validateProof() proof extraction offset |
| ResourceDeliveryTest.kt | Strict assertions for DELIVERED state |
| debug_resource_flow.py | New diagnostic script |

## Phase 9 Completion Status

With this gap closure plan complete, Phase 9 (Resource Transfer) is now fully verified:
- Threshold detection: Working (319/320 boundary)
- BZ2 compression: Working bidirectionally
- Progress tracking: Working
- Large message delivery: Working K<->P with DELIVERED state

Phase 9 COMPLETE.
