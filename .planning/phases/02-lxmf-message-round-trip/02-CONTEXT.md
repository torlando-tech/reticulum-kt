# Phase 2: LXMF Message Round-Trip - Context

**Gathered:** 2026-01-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Verify that LXMessage objects serialize and deserialize identically between Kotlin and Python implementations. This is the foundational interoperability test — messages created in one implementation must unpack correctly in the other with all base fields preserved. Attachments, custom fields, and cryptographic signatures are separate phases.

</domain>

<decisions>
## Implementation Decisions

### Test message content
- Comprehensive coverage: all base fields populated with varied content lengths
- Use real RNS identities (generate actual Identity objects with valid keys in both implementations)
- Use current time (time.time() / System.currentTimeMillis()) for timestamps — no fixed values

### Round-trip verification
- Use both approaches: field-by-field comparison for diagnostics, byte equality as final truth
- Python is authoritative: Python reference implementation defines correct serialization; Kotlin must match
- Include message hash checks: compute hash before and after round-trip, must match

### Test case structure
- Separate test classes: KotlinToPythonTest and PythonToKotlinTest as distinct test suites
- One representative case per scenario (not multiple variations)
- Shared fixtures: use @BeforeAll/@BeforeEach to share bridge connection and test identities

### Failure handling
- Basic assertion messages (standard test framework output)
- Soft assertions: collect all failures and report together at end
- Bridge unavailability is a hard failure (ensures bridge is always running)
- Log timing information for serialization/deserialization operations

### Claude's Discretion
- Unicode and special character coverage in test content
- Timestamp precision tolerance (investigate LXMF format and determine appropriate comparison)
- Title field inclusion (determine based on LXMF field usage patterns)
- Test class internal organization

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-lxmf-message-round-trip*
*Context gathered: 2026-01-23*
