# Milestone v1: LXMF Interoperability

**Status:** ✅ SHIPPED 2026-01-24
**Phases:** 1-9 (+ 8.1 inserted)
**Total Plans:** 25

## Overview

This roadmap delivered byte-level LXMF interoperability between Kotlin and Python implementations. Starting with Python bridge extension, we progressively verified message serialization, field encoding, cryptographic operations, stamp generation, and all three delivery modes (DIRECT, OPPORTUNISTIC, PROPAGATED), culminating in large message Resource transfer. Each phase produces tests that run against the Python reference implementation.

## Phases

### Phase 1: Python Bridge Extension

**Goal**: Python bridge supports LXMF operations for cross-implementation testing
**Depends on**: Nothing (first phase)
**Requirements**: None (infrastructure enabling all LXMF requirements)
**Plans**: 1 plan

Plans:
- [x] 01-01-PLAN.md - Add LXMF commands to Python bridge

**Details:**
Added 6 LXMF bridge commands: lxmf_pack, lxmf_unpack, lxmf_hash, lxmf_stamp_workblock, lxmf_stamp_valid, lxmf_stamp_generate. Foundation for all interoperability testing.

### Phase 2: LXMF Message Round-Trip

**Goal**: Kotlin-packed LXMessage unpacks correctly in Python and vice versa
**Depends on**: Phase 1
**Requirements**: LXMF-01, LXMF-02
**Plans**: 2 plans

Plans:
- [x] 02-01-PLAN.md - Kotlin-to-Python message round-trip tests (shared fixtures + K2P tests)
- [x] 02-02-PLAN.md - Python-to-Kotlin message round-trip tests (P2K tests + full suite verification)

**Details:**
Verified bidirectional message serialization with 11 tests covering basic fields, empty messages, unicode content, timestamp precision.

### Phase 3: LXMF Field Interop

**Goal**: All LXMF field types (attachments, images, custom) survive Kotlin-Python round-trip
**Depends on**: Phase 2
**Requirements**: LXMF-03, LXMF-04, LXMF-05
**Plans**: 3 plans

Plans:
- [x] 03-01-PLAN.md - File attachment interop tests (bridge extension + AttachmentFieldInteropTest)
- [x] 03-02-PLAN.md - Image field interop tests (ImageFieldInteropTest with WebP)
- [x] 03-03-PLAN.md - Custom field interop tests (renderer, thread, arbitrary fields)

**Details:**
15 tests verifying FIELD_FILE_ATTACHMENTS, FIELD_IMAGE, FIELD_RENDERER, FIELD_THREAD, and arbitrary custom fields survive round-trip with binary content integrity (SHA-256 verified).

### Phase 4: LXMF Cryptographic Interop

**Goal**: LXMF message hash and signature computed identically across implementations
**Depends on**: Phase 2
**Requirements**: LXMF-06, LXMF-07
**Plans**: 2 plans

Plans:
- [x] 04-01-PLAN.md - Message hash interop tests (hash matches Python, idempotency, edge cases)
- [x] 04-02-PLAN.md - Message signature interop tests (bidirectional validation, error handling)

**Details:**
20 tests covering hash computation, signature generation/validation, invalid signature rejection. Verified cryptographic operations match byte-for-byte.

### Phase 5: Stamp Interop

**Goal**: LXMF stamps generated in Kotlin are accepted by Python and vice versa
**Depends on**: Phase 4
**Requirements**: STAMP-01, STAMP-02, STAMP-03
**Plans**: 2 plans

Plans:
- [x] 05-01-PLAN.md - Core stamp interop (workblock, K->P, P->K, difficulty levels)
- [x] 05-02-PLAN.md - Edge cases and invalid stamp rejection

**Details:**
25 tests covering workblock generation, stamp generation at various difficulties (0-16 bits), validation, over-qualified stamps, invalid stamp rejection. LXStamper implementation verified against Python LXStamper.

### Phase 6: Direct Delivery

**Goal**: LXMF messages delivered via DIRECT method over established Links
**Depends on**: Phase 4
**Requirements**: E2E-01, E2E-02
**Plans**: 3 plans

Plans:
- [x] 06-01-PLAN.md - Test infrastructure with live Reticulum instances
- [x] 06-02-PLAN.md - Bidirectional direct delivery tests
- [x] 06-03-PLAN.md - Gap closure: delivery callbacks and field preservation (gap_closure)

**Details:**
6 tests verifying Kotlin↔Python direct delivery over TCP Links. Includes delivery callbacks, MessageState transitions, and field preservation. Established DirectDeliveryTestBase infrastructure for subsequent phases.

### Phase 7: Opportunistic Delivery

**Goal**: LXMF messages delivered via OPPORTUNISTIC method when path available
**Depends on**: Phase 6
**Requirements**: E2E-03
**Plans**: 3 plans

Plans:
- [x] 07-01-PLAN.md — Align constants and enhance opportunistic delivery logic
- [x] 07-02-PLAN.md — Test infrastructure and basic K->P opportunistic tests
- [x] 07-03-PLAN.md — Advanced scenarios: batch, timeout, bidirectional P->K

**Details:**
9 tests covering opportunistic delivery with announce-based path discovery. Verified path request/rediscovery logic, timeout callbacks, immediate send when identity known.

### Phase 8: Propagated Delivery

**Goal**: LXMF messages delivered via PROPAGATED method through Python propagation node
**Depends on**: Phase 5, Phase 6
**Requirements**: E2E-04
**Plans**: 2 plans

Plans:
- [x] 08-01-PLAN.md - Bridge commands + PropagatedDeliveryTestBase + E2E tests
- [x] 08-02-PLAN.md - Gap closure: addPropagationNode method for proper node registration

**Details:**
4 tests verifying propagation node interaction with stamp validation. Added addPropagationNode() method to LXMRouter for reliable test setup.

### Phase 8.1: TCP Interface Interop (INSERTED)

**Goal**: Fix TCP transport between Kotlin TCPClientInterface and Python RNS TCPServerInterface so E2E delivery tests don't need flexible assertions
**Depends on**: Phase 8
**Requirements**: Enables proper verification of E2E-01, E2E-02, E2E-03, E2E-04
**Plans**: 4 plans

Plans:
- [x] 08.1-01-PLAN.md - Diagnostic logging + minimal Python TCP interop test
- [x] 08.1-02-PLAN.md - Socket options alignment and write safeguards
- [x] 08.1-03-PLAN.md - Tighten E2E test assertions (strict DELIVERED state)
- [x] 08.1-04-PLAN.md - Gap closure: LXMF propagation link callback fix

**Details:**
4 tests for isolated TCP layer verification. Fixed socket options (keepAlive, SO_LINGER), write safeguards, and link callback parameter for propagation delivery vs retrieval.

### Phase 9: Resource Transfer

**Goal**: Large LXMF messages (>319 bytes content) transfer correctly as Resources between Kotlin and Python
**Depends on**: Phase 6
**Requirements**: RES-01, RES-02, RES-03
**Plans**: 3 plans

Plans:
- [x] 09-01-PLAN.md - Large message Resource transfer tests (threshold boundary + bidirectional delivery)
- [x] 09-02-PLAN.md - Resource compression interop tests (BZ2 interop + progress callbacks)
- [x] 09-03-PLAN.md - Gap closure: Fix Resource protocol transfer (proof routing + format)

**Details:**
11 tests covering threshold detection (319/320 bytes), BZ2 compression interop, progress callbacks, and large message delivery. Fixed Resource proof routing to activeLinks and 64-byte proof format.

---

## Milestone Summary

**Decimal Phases:**
- Phase 8.1: TCP Interface Interop (inserted after Phase 8 for urgent TCP layer fixes)

**Key Decisions:**
- Work with raw hashes instead of Destination objects for LXMF bridge (avoids RNS initialization)
- Use Identity.sigPrv (32-byte Ed25519 seed) for Python ed25519_sign
- Kotlin strings serialized as msgpack binary (bytes) to match Python LXMF behavior
- Use forRetrieval parameter in establishPropagationLink to differentiate delivery vs retrieval callbacks
- RESOURCE_PRF proofs routed through activeLinks (not reverse_table)
- Resource proof format is 64 bytes: [hash (32)][proof (32)]

**Issues Resolved:**
- TCP connection drops after packet transmission (socket options alignment)
- LXMF propagation link callback not triggering message processing (forRetrieval parameter)
- Resource proof not delivered to active links (routing fix in Transport.processProof())
- Resource proof format mismatch 48 vs 64 bytes (format correction)

**Issues Deferred:**
- None (all v1 issues resolved)

**Technical Debt Incurred:**
- None (clean implementation)

---

_Archived: 2026-01-24 as part of v1 milestone completion_
_For current project status, see .planning/ROADMAP.md (created for next milestone)_
