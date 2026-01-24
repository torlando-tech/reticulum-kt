# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-23)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** Phase 6 - Direct Delivery infrastructure complete, ready for message tests.

## Current Position

Phase: 6 of 9 (Direct Delivery)
Plan: 1 of 3 in current phase
Status: In progress
Last activity: 2026-01-24 - Completed 06-01-PLAN.md (Direct Delivery Test Infrastructure)

Progress: [██████░░░░] ~60%

## Performance Metrics

**Velocity:**
- Total plans completed: 11
- Average duration: 5.0 min
- Total execution time: 59 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-python-bridge-extension | 1 | 8 min | 8 min |
| 02-lxmf-message-round-trip | 2 | 15 min | 7.5 min |
| 03-lxmf-field-interop | 3 | 10 min | 3.3 min |
| 04-lxmf-cryptographic-interop | 2 | 7 min | 3.5 min |
| 05-stamp-interop | 2 | 8 min | 4 min |
| 06-direct-delivery | 1 | 11 min | 11 min |

**Recent Trend:**
- Last 5 plans: 3, 4, 5, 3, 11 min
- Trend: Infrastructure plan took longer (expected)

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

### Pending Todos

None yet.

### Blockers/Concerns

None - Plan 06-01 complete. Direct delivery infrastructure ready for message tests.

## Session Continuity

Last session: 2026-01-24
Stopped at: Completed 06-01-PLAN.md (Direct Delivery Test Infrastructure)
Resume file: None
