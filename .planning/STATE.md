# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-23)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** Phase 4 - LXMF Cryptographic Interop (message hash verification complete)

## Current Position

Phase: 4 of 9 (LXMF Cryptographic Interop)
Plan: 1 of 2 in current phase
Status: In progress
Last activity: 2026-01-24 - Completed 04-01-PLAN.md (Message Hash Interop)

Progress: [████▒░░░░░] ~39%

## Performance Metrics

**Velocity:**
- Total plans completed: 7
- Average duration: 5.7 min
- Total execution time: 36 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-python-bridge-extension | 1 | 8 min | 8 min |
| 02-lxmf-message-round-trip | 2 | 15 min | 7.5 min |
| 03-lxmf-field-interop | 3 | 10 min | 3.3 min |
| 04-lxmf-cryptographic-interop | 1 | 3 min | 3 min |

**Recent Trend:**
- Last 5 plans: 3, 6, 2, 2, 3 min
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

### Pending Todos

None yet.

### Blockers/Concerns

None - Plan 04-01 complete. Ready for 04-02 (signature interop).

## Session Continuity

Last session: 2026-01-24
Stopped at: Completed 04-01-PLAN.md (Message Hash Interop)
Resume file: None
