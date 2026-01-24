# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-23)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** Phase 4 complete - LXMF Cryptographic Interop

## Current Position

Phase: 4 of 9 (LXMF Cryptographic Interop)
Plan: 2 of 2 in current phase
Status: Phase complete
Last activity: 2026-01-24 - Completed 04-02-PLAN.md (Message Signature Interop)

Progress: [████▒░░░░░] ~44%

## Performance Metrics

**Velocity:**
- Total plans completed: 8
- Average duration: 5.4 min
- Total execution time: 40 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-python-bridge-extension | 1 | 8 min | 8 min |
| 02-lxmf-message-round-trip | 2 | 15 min | 7.5 min |
| 03-lxmf-field-interop | 3 | 10 min | 3.3 min |
| 04-lxmf-cryptographic-interop | 2 | 7 min | 3.5 min |

**Recent Trend:**
- Last 5 plans: 6, 2, 2, 3, 4 min
- Trend: Stable/Fast

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

### Pending Todos

None yet.

### Blockers/Concerns

None - Phase 4 complete. Ready for Phase 5 (LXMF Stamp Verification).

## Session Continuity

Last session: 2026-01-24
Stopped at: Completed 04-02-PLAN.md (Message Signature Interop)
Resume file: None
