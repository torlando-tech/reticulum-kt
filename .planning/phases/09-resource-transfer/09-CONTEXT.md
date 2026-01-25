# Phase 9: Resource Transfer - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Large LXMF messages (>500 bytes packed size) transfer correctly as Resources between Kotlin and Python implementations. This includes automatic threshold detection, compression, chunked transfer, progress callbacks, and failure handling. Does NOT include new delivery modes or protocol extensions.

</domain>

<decisions>
## Implementation Decisions

### Size thresholds
- Focus on boundary testing around 500-byte threshold (499, 500, 501, 510 bytes)
- Verify both paths: messages under threshold use packet delivery, over threshold use Resource
- Maximum test size: ~10KB (covers practical use cases, keeps tests fast)
- Calculate packed size with full overhead (msgpack container, all fields, signatures)
- Assert threshold constant matches Python's LXMF constant value
- Test bidirectionally: Kotlin→Python AND Python→Kotlin
- Include edge case: fields with attachments affect size calculation

### Compression verification
- Byte-identical output required: Kotlin compression must equal Python compression for same input
- Verify from Python reference source which algorithm LXMF uses (don't assume bz2)
- Don't assert size reduction, just correctness
- Test incompressible data explicitly (random bytes, already-compressed content)

### Progress callbacks
- Per-segment granularity: callback fires for each chunk transferred
- Exact signature match: Kotlin callbacks provide same parameters as Python
- Strict sequence verification: started → segment1 → segment2 → ... → completed
- Test both sender and receiver sides for progress tracking
- Verify callback threading behavior (which thread callbacks fire on)

### Failure scenarios
- Test all failure modes: link drop mid-transfer, timeouts, corruption
- Failure callback must match Python exactly (same reason/code format)
- Verify cleanup after failure: no leaked resources, partial data cleaned up
- Test retry logic: automatic retry behavior and eventual success/give-up

### Claude's Discretion
- Specific test fixture content (as long as sizes are correct)
- Test class organization and naming
- Order of test execution within categories
- Helper method design for size calculation

</decisions>

<specifics>
## Specific Ideas

- Full packed message size matters, not just content size — need to account for msgpack overhead, fields, signatures when determining threshold crossing
- Per-segment callbacks are important for real-world progress UX (not just start/complete)
- Byte-identical compression is strictest interop guarantee — if compressed bytes match, decompression definitely works

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 09-resource-transfer*
*Context gathered: 2026-01-24*
