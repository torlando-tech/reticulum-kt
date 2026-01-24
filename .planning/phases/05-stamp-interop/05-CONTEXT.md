# Phase 5: Stamp Interop - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

LXMF proof-of-work stamp generation and validation across Kotlin and Python implementations. Stamps generated in Kotlin must validate in Python and vice versa. Includes difficulty calculation interop verification.

</domain>

<decisions>
## Implementation Decisions

### Difficulty levels
- Test difficulty range: 0, 1, 4, 8, 12, and 16 bits
- Include difficulty 0 (edge case where stamps are trivially valid)
- Include difficulty 16 (high difficulty test, even if slow)
- Verify difficulty calculation formula matches between implementations for same message/destination

### Test timing
- Use longer timeouts AND tag slow tests for flexibility
- Sync stamp generation in Python bridge is acceptable (no async needed)
- Create low-difficulty subset (1-4 bits) for fast development iteration
- High-difficulty tests (16 bits) should be tagged as slow/skippable

### Validation behavior
- Test comprehensive invalid stamp scenarios:
  - Wrong difficulty, corrupted bytes, wrong message hash
  - Truncated stamps, empty stamps, wrong length
  - Replay attacks (stamp for wrong message)
- Validation error detail: match Python behavior exactly
- Same invalid stamp must be rejected by both implementations (bidirectional rejection testing)

### Stamp format
- Test stamps both standalone AND within LXMF message packing (unit + integration)
- Use shared test fixtures (pre-computed valid/invalid stamps) + fresh generation tests
- Fixture tests for known-good values, generation tests for algorithm verification

### Claude's Discretion
- Exact byte representation matching (semantic vs byte-exact)
- Whether to test over-qualified stamps (8-bit stamp when 4 required)
- Nonce/counter iteration behavior testing
- Appropriate timeout values for high-difficulty tests

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches based on Python reference implementation.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 05-stamp-interop*
*Context gathered: 2026-01-24*
