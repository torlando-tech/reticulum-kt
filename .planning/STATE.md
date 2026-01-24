# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-23)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** Phase 8 - Propagated Delivery (Plan 02 complete)

## Current Position

Phase: 8 of 9 (Propagated Delivery)
Plan: 2 of 3 in current phase
Status: In progress
Last activity: 2026-01-24 - Completed 08-02-PLAN.md (Gap Closure - Propagation Node Registration)

Progress: [████████░░] ~83%

## Performance Metrics

**Velocity:**
- Total plans completed: 18
- Average duration: 5.5 min
- Total execution time: 110 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-python-bridge-extension | 1 | 8 min | 8 min |
| 02-lxmf-message-round-trip | 2 | 15 min | 7.5 min |
| 03-lxmf-field-interop | 3 | 10 min | 3.3 min |
| 04-lxmf-cryptographic-interop | 2 | 7 min | 3.5 min |
| 05-stamp-interop | 2 | 8 min | 4 min |
| 06-direct-delivery | 3 | 36 min | 12 min |
| 07-opportunistic-delivery | 3 | 16 min | 5.3 min |
| 08-propagated-delivery | 2 | 10 min | 5 min |

**Recent Trend:**
- Last 5 plans: 5, 7, 4, 6, 4 min
- Trend: Consistent fast execution

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Work with raw hashes instead of Destination objects for LXMF bridge (avoids RNS initialization complexity)
- Import only LXStamper module, not LXMessage class
- Move InteropTestBase and PythonBridge to main source for cross-module reuse
- Use api() dependencies for test infrastructure exposed to other modules
- Use Identity.sigPrv (32-byte Ed25519 seed) for Python ed25519_sign, not getPrivateKey()
- Add Map serialization to PythonBridge for fields parameter support
- Use fields_hex instead of fields in lxmf_unpack_with_fields response (avoids JSON serialization issues)
- Kotlin strings serialized as msgpack binary (bytes) to match Python LXMF behavior
- Use testSourceIdentity.getPrivateKey() for Python identity_sign (64-byte full key)
- Reconstruct signed_part by extracting payload from packed message
- Use 25 expand rounds for stamp tests (fast while meaningful)
- Custom shouldBeAtLeast infix for readable >= assertions
- Test 8-bit stamp validated at 4-bit shows over-qualification acceptance
- Difficulty 0 accepts any stamp (target = 2^256 > any hash)
- Suppress RNS logging (LOG_CRITICAL) to avoid polluting JSON protocol
- Patch multiprocessing.set_start_method to handle LXMF reimport
- Cache RNS module and clear LXMF on reimport for consistent isinstance checks
- Add 10 second timeout for TCP connection wait (async connection needs time)
- Use InteropTestBase for format tests instead of DirectDeliveryTestBase (simpler)
- Python-to-Kotlin message construction requires lxmf_pack + identity_sign + manual assembly
- Use AtomicBoolean for callback tracking in async test scenarios
- Accept SENT or DELIVERED as valid final states (delivery confirmation timing varies)
- Parse fields from Python bridge with hex decoding for binary values
- Use Transport.expirePath() for path removal (equivalent to Python's drop_path)
- Match Python's 0.5s delay before requesting new path during rediscovery
- Added open modifier to DirectDeliveryTestBase.setupDirectDelivery() for test inheritance
- Opportunistic delivery sends immediately via broadcast when identity known (no queueing)
- Announce provides path optimization, not delivery enablement for opportunistic
- Use registerDeliveryCallback for incoming message handling in LXMRouter
- Use stamp_cost=8 for fast tests (lower than production default of 16)
- PropagatedDeliveryTestBase extends DirectDeliveryTestBase for code reuse
- Use WORKBLOCK_EXPAND_ROUNDS_PN (1000) for propagation stamps
- Add addPropagationNode() to bypass announce parsing for test scenarios
- Call addPropagationNode() before setActivePropagationNode() for reliable setup

### Pending Todos

None yet.

### Blockers/Concerns

- TCP interface compatibility issue between Kotlin and Python RNS - connections drop after packet transmission. Does not block message format verification but affects live delivery testing.

## Session Continuity

Last session: 2026-01-24
Stopped at: Completed 08-02-PLAN.md - ready for 08-03-PLAN.md (Extended Propagation Tests)
Resume file: None
