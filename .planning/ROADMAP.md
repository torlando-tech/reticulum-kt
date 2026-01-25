# Roadmap: Reticulum-KT LXMF Interoperability

## Overview

This roadmap delivers byte-level LXMF interoperability between Kotlin and Python implementations. Starting with Python bridge extension, we progressively verify message serialization, field encoding, cryptographic operations, stamp generation, and all three delivery modes (DIRECT, OPPORTUNISTIC, PROPAGATED), culminating in large message Resource transfer. Each phase produces tests that run against the Python reference implementation.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: Python Bridge Extension** - Add LXMF commands to existing Python bridge
- [x] **Phase 2: LXMF Message Round-Trip** - Core message pack/unpack across implementations
- [x] **Phase 3: LXMF Field Interop** - Attachments and custom fields survive round-trip
- [x] **Phase 4: LXMF Cryptographic Interop** - Hash and signature validation across implementations
- [x] **Phase 5: Stamp Interop** - Proof-of-work stamp generation and validation
- [x] **Phase 6: Direct Delivery** - LXMF messages over established Links
- [x] **Phase 7: Opportunistic Delivery** - LXMF messages when path available
- [x] **Phase 8: Propagated Delivery** - LXMF messages via propagation node
- [x] **Phase 8.1: TCP Interface Interop** - Fix TCP transport between Kotlin and Python RNS (INSERTED)
- [x] **Phase 9: Resource Transfer** - Large LXMF messages as Resources

## Phase Details

### Phase 1: Python Bridge Extension
**Goal**: Python bridge supports LXMF operations for cross-implementation testing
**Depends on**: Nothing (first phase)
**Requirements**: None (infrastructure enabling all LXMF requirements)
**Success Criteria** (what must be TRUE):
  1. Bridge server can create LXMessage from provided parameters
  2. Bridge server can serialize LXMessage to bytes and return them
  3. Bridge server can deserialize LXMessage bytes and return fields
  4. Bridge server can compute LXMF message hash from bytes
  5. Bridge server can generate stamps with configurable difficulty
**Plans**: 1 plan

Plans:
- [x] 01-01-PLAN.md - Add LXMF commands to Python bridge

### Phase 2: LXMF Message Round-Trip
**Goal**: Kotlin-packed LXMessage unpacks correctly in Python and vice versa
**Depends on**: Phase 1
**Requirements**: LXMF-01, LXMF-02
**Success Criteria** (what must be TRUE):
  1. LXMessage created in Kotlin unpacks in Python with all base fields preserved
  2. LXMessage created in Python unpacks in Kotlin with all base fields preserved
  3. Message source/destination hashes match across implementations
  4. Timestamp and content fields survive round-trip intact
**Plans**: 2 plans

Plans:
- [x] 02-01-PLAN.md - Kotlin-to-Python message round-trip tests (shared fixtures + K2P tests)
- [x] 02-02-PLAN.md - Python-to-Kotlin message round-trip tests (P2K tests + full suite verification)

### Phase 3: LXMF Field Interop
**Goal**: All LXMF field types (attachments, images, custom) survive Kotlin-Python round-trip
**Depends on**: Phase 2
**Requirements**: LXMF-03, LXMF-04, LXMF-05
**Success Criteria** (what must be TRUE):
  1. FIELD_FILE_ATTACHMENTS with multiple files survives round-trip with correct names and content
  2. FIELD_IMAGE with binary image data survives round-trip with correct bytes
  3. Custom fields (renderer, thread ID) survive round-trip with correct values
  4. Empty/null field handling matches between implementations
**Plans**: 3 plans

Plans:
- [x] 03-01-PLAN.md - File attachment interop tests (bridge extension + AttachmentFieldInteropTest)
- [x] 03-02-PLAN.md - Image field interop tests (ImageFieldInteropTest with WebP)
- [x] 03-03-PLAN.md - Custom field interop tests (renderer, thread, arbitrary fields)

### Phase 4: LXMF Cryptographic Interop
**Goal**: LXMF message hash and signature computed identically across implementations
**Depends on**: Phase 2
**Requirements**: LXMF-06, LXMF-07
**Success Criteria** (what must be TRUE):
  1. Message hash computed in Kotlin matches hash computed in Python for same message bytes
  2. Signature generated in Kotlin validates in Python
  3. Signature generated in Python validates in Kotlin
  4. Invalid signatures are rejected by both implementations
**Plans**: 2 plans

Plans:
- [x] 04-01-PLAN.md - Message hash interop tests (hash matches Python, idempotency, edge cases)
- [x] 04-02-PLAN.md - Message signature interop tests (bidirectional validation, error handling)

### Phase 5: Stamp Interop
**Goal**: LXMF stamps generated in Kotlin are accepted by Python and vice versa
**Depends on**: Phase 4
**Requirements**: STAMP-01, STAMP-02, STAMP-03
**Success Criteria** (what must be TRUE):
  1. Stamp generated in Kotlin with difficulty N validates in Python
  2. Stamp generated in Python validates in Kotlin
  3. Difficulty calculation matches between implementations for same message
  4. Invalid stamps (wrong difficulty, corrupted) rejected by both implementations
**Plans**: 2 plans

Plans:
- [x] 05-01-PLAN.md - Core stamp interop (workblock, K->P, P->K, difficulty levels)
- [x] 05-02-PLAN.md - Edge cases and invalid stamp rejection

### Phase 6: Direct Delivery
**Goal**: LXMF messages delivered via DIRECT method over established Links
**Depends on**: Phase 4
**Requirements**: E2E-01, E2E-02
**Success Criteria** (what must be TRUE):
  1. Kotlin client can send LXMessage to Python client over Link
  2. Python client can send LXMessage to Kotlin client over Link
  3. Message content and fields preserved end-to-end
  4. Delivery receipts/confirmations work bidirectionally
**Plans**: 3 plans

Plans:
- [x] 06-01-PLAN.md - Test infrastructure with live Reticulum instances
- [x] 06-02-PLAN.md - Bidirectional direct delivery tests
- [x] 06-03-PLAN.md - Gap closure: delivery callbacks and field preservation (gap_closure)

### Phase 7: Opportunistic Delivery
**Goal**: LXMF messages delivered via OPPORTUNISTIC method when path available
**Depends on**: Phase 6
**Requirements**: E2E-03
**Success Criteria** (what must be TRUE):
  1. Kotlin client queues message when destination path unknown
  2. Message sends automatically when announce received
  3. Message content preserved after opportunistic delivery
  4. Failed delivery after timeout triggers appropriate callback
**Plans**: 3 plans

Plans:
- [x] 07-01-PLAN.md — Align constants and enhance opportunistic delivery logic
- [x] 07-02-PLAN.md — Test infrastructure and basic K->P opportunistic tests
- [x] 07-03-PLAN.md — Advanced scenarios: batch, timeout, bidirectional P->K

### Phase 8: Propagated Delivery
**Goal**: LXMF messages delivered via PROPAGATED method through Python propagation node
**Depends on**: Phase 5, Phase 6
**Requirements**: E2E-04
**Success Criteria** (what must be TRUE):
  1. Kotlin client can submit message to Python propagation node
  2. Python propagation node accepts Kotlin-generated stamp
  3. Message retrieved by recipient from propagation node
  4. End-to-end content integrity verified
**Plans**: 2 plans

Plans:
- [x] 08-01-PLAN.md - Bridge commands + PropagatedDeliveryTestBase + E2E tests
- [x] 08-02-PLAN.md - Gap closure: addPropagationNode method for proper node registration

### Phase 8.1: TCP Interface Interop (INSERTED)
**Goal**: Fix TCP transport between Kotlin TCPClientInterface and Python RNS TCPServerInterface so E2E delivery tests don't need flexible assertions
**Depends on**: Phase 8
**Requirements**: Enables proper verification of E2E-01, E2E-02, E2E-03, E2E-04
**Success Criteria** (what must be TRUE):
  1. Kotlin TCPClientInterface maintains stable connection to Python RNS TCPServerInterface
  2. Packets sent from Kotlin are received correctly by Python (no drops after transmission)
  3. Packets sent from Python are received correctly by Kotlin
  4. Direct delivery tests pass with strict assertions (DELIVERED state, not just OUTBOUND)
  5. Propagated delivery tests can verify actual message submission/retrieval
**Plans**: 4 plans

Plans:
- [x] 08.1-01-PLAN.md - Diagnostic logging + minimal Python TCP interop test
- [x] 08.1-02-PLAN.md - Socket options alignment and write safeguards
- [x] 08.1-03-PLAN.md - Tighten E2E test assertions (strict DELIVERED state)
- [x] 08.1-04-PLAN.md - Gap closure: LXMF propagation link callback fix

### Phase 9: Resource Transfer
**Goal**: Large LXMF messages (>319 bytes content) transfer correctly as Resources between Kotlin and Python
**Depends on**: Phase 6
**Requirements**: RES-01, RES-02, RES-03
**Success Criteria** (what must be TRUE):
  1. LXMF message over 319 bytes content automatically uses RESOURCE representation
  2. BZ2 compression interoperates bidirectionally (K->P and P->K decompression works)
  3. Large messages transfer with content intact via Resource
  4. Transfer progress callbacks fire appropriately
**Plans**: 3 plans

Plans:
- [x] 09-01-PLAN.md - Large message Resource transfer tests (threshold boundary + bidirectional delivery)
- [x] 09-02-PLAN.md - Resource compression interop tests (BZ2 interop + progress callbacks)
- [x] 09-03-PLAN.md - Gap closure: Fix Resource protocol transfer (proof routing + format)

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 8.1 -> 9

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Python Bridge Extension | 1/1 | ✓ Complete | 2026-01-23 |
| 2. LXMF Message Round-Trip | 2/2 | ✓ Complete | 2026-01-24 |
| 3. LXMF Field Interop | 3/3 | ✓ Complete | 2026-01-24 |
| 4. LXMF Cryptographic Interop | 2/2 | ✓ Complete | 2026-01-24 |
| 5. Stamp Interop | 2/2 | ✓ Complete | 2026-01-24 |
| 6. Direct Delivery | 3/3 | ✓ Complete | 2026-01-24 |
| 7. Opportunistic Delivery | 3/3 | ✓ Complete | 2026-01-24 |
| 8. Propagated Delivery | 2/2 | ✓ Complete | 2026-01-24 |
| 8.1 TCP Interface Interop | 4/4 | ✓ Complete | 2026-01-24 |
| 9. Resource Transfer | 3/3 | ✓ Complete | 2026-01-24 |

---
*Roadmap created: 2026-01-23*
