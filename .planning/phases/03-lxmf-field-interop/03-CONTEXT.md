# Phase 3: LXMF Field Interop - Context

**Gathered:** 2026-01-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Verify that all LXMF field types (file attachments, images, custom fields) survive Kotlin-Python round-trip with byte-level accuracy. Testing infrastructure for protocol interoperability — actual message delivery is in later phases.

</domain>

<decisions>
## Implementation Decisions

### Test fixture design
- Use 3-5 realistic files with real extensions (.txt, .pdf, .png) and meaningful content sizes (1KB-100KB)
- Python generates fixtures, Kotlin consumes — Python is the reference implementation
- Include mixed text and binary file types to exercise both handling paths
- Test 2-3 attachments together in a single message to verify ordering preservation

### Field edge cases
- Match Python behavior exactly for empty/null fields — discover via testing what Python does
- Include one unicode filename test case to verify encoding (not exhaustive unicode testing)
- Test both documented LXMF fields (renderer, thread_id) AND arbitrary key-value pairs for custom fields

### Binary data strategy
- Use real image files (WebP format specifically requested)
- Byte-by-byte comparison for small data, checksum comparison for larger files
- Both methods used for verification

### Error handling
- Match Python's tolerance exactly — test what Python rejects/accepts and ensure Kotlin matches
- Match Python exactly for unknown/unexpected field handling (forward compatibility behavior)
- Follow Python's attachment count limits — discover and match whatever limits Python enforces

### Claude's Discretion
- Appropriate file sizes for tests (within reason, Phase 9 handles size limits)
- Image file sourcing method (generated minimal images vs pre-made test images)
- Whether type mismatch testing is valuable for interop

</decisions>

<specifics>
## Specific Ideas

- WebP format for image testing (user specifically requested this over PNG/JPEG)
- Python reference implementation drives all behavior decisions — if in doubt, match Python

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-lxmf-field-interop*
*Context gathered: 2026-01-23*
