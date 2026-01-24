# Phase 4: LXMF Cryptographic Interop - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

LXMF message hash and signature computed identically across Kotlin and Python implementations. This enables messages signed by one implementation to be verified by the other, and ensures message identity (hash) is consistent regardless of which implementation processes the message.

</domain>

<decisions>
## Implementation Decisions

### Hash Computation Scope
- Test hashing at all message lifecycle states (freshly created, packed, unpacked round-trip)
- Verify hash idempotency: pack → unpack → repack should produce the same hash
- Test both raw crypto primitives (SHA-256 or underlying algorithm) AND LXMF message hash method — helps isolate bugs to correct layer

### Signature Test Coverage
- Use both freshly-generated signatures AND pre-computed fixture signatures for determinism
- Test both directions: Kotlin signs → Python verifies AND Python signs → Kotlin verifies
- Basic validity testing: valid signatures verify, invalid ones don't (no exhaustive tamper testing)
- Representative message only for signature tests — signature algorithm doesn't depend on content variations

### Error Handling Behavior
- Match Python behavior exactly for signature verification failures
- Match Python behavior for null/missing signature handling
- Match Python behavior for hash computation errors (unhashable states)
- Debug mode toggle: pass/fail normally, detailed diagnostic info when debug flag enabled

### Edge Case Handling
- Test with zero-length (empty) message content — valid in LXMF
- Test with Unicode content (emoji, CJK, RTL) — verify encoding doesn't affect hash
- Test with keys from all sources: fresh Kotlin keys, fresh Python keys, pre-shared fixture keys

### Claude's Discretion
- Hash field combination coverage — determine appropriate samples based on risk
- Malformed signature handling coverage — determine appropriate truncated/corrupted signature tests
- Specific debug info format and verbosity levels

</decisions>

<specifics>
## Specific Ideas

- "Match Python exactly" was the guiding principle for error handling — behavioral parity is the goal
- Hash idempotency testing catches serialization drift that could break message identity
- Testing both crypto layers (raw + LXMF) helps isolate whether bugs are in crypto primitives or message handling

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-lxmf-cryptographic-interop*
*Context gathered: 2026-01-24*
