# Phase 8: Propagated Delivery - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

LXMF messages delivered via PROPAGATED method through a Python propagation node. This covers store-and-forward messaging where Kotlin submits messages to an intermediary node, and recipients retrieve them via sync requests. Kotlin acts only as client; propagation node server role is out of scope.

</domain>

<decisions>
## Implementation Decisions

### Node Interaction Pattern
- Configured address: Test explicitly passes the propagation node's destination hash (no announce-based discovery)
- Fresh node per test: Each test starts a clean propagation node instance for isolation
- Link-based connection: Establish Link to propagation node, consistent with Direct delivery patterns
- Client role only: Kotlin submits and retrieves messages; propagation node server functionality is Python-only

### Message Submission Flow
- Wait for explicit ack: Block until propagation node confirms message accepted
- Verify rejection callback: When node rejects (e.g., insufficient stamp), test that Kotlin receives rejection reason
- No retry tests: Focus on happy path and rejection; retry logic is implementation detail
- Include fields: At least one test with attachments/custom fields for end-to-end field preservation

### Message Retrieval Behavior
- Explicit sync request: Client sends sync request to propagation node, node returns pending messages
- Single message only: Keep tests simple with one message submitted, one retrieved
- Test empty response: Verify Kotlin handles "no messages" gracefully
- Verify deletion: Second sync after retrieval should return empty, confirming message consumed

### Stamp Requirements
- Low difficulty: Configure propagation node to accept low difficulty stamps (e.g., 8-bit) for fast tests
- Trust LXMRouter: Let router handle stamp generation/attachment; test end result
- Test rejection: Include test for insufficient stamp (submit with difficulty N-1 when node requires N)
- Generate insufficient stamp: Create valid but low-difficulty stamp rather than corrupting bytes

### Claude's Discretion
- Exact timing for sync request after submission
- Propagation node configuration parameters beyond stamp difficulty
- Test timeout values
- Error message string matching specifics

</decisions>

<specifics>
## Specific Ideas

- Pattern mirrors Phase 6 (Direct Delivery) infrastructure: fresh Reticulum instances, Link establishment, callback verification
- Stamp difficulty mismatch test creates a "just below threshold" stamp, not corrupted bytes
- Deletion verification requires two sync operations: one to retrieve, one to confirm empty

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 08-propagated-delivery*
*Context gathered: 2026-01-24*
