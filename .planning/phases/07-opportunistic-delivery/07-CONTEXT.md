# Phase 7: Opportunistic Delivery - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

LXMF messages delivered via OPPORTUNISTIC method when destination path is unknown. Sender queues messages, then delivers automatically when destination announces itself on the network. This tests the core "store-and-forward" behavior that makes LXMF resilient to intermittent connectivity.

</domain>

<decisions>
## Implementation Decisions

### Queue Behavior
- Match Python behavior: process all queued messages in list order when announce arrives
- No queue size limit (unlimited, same as Python reference)
- Use Python's exact retry constants: 5 max attempts, 10s retry wait, 7s path request wait, 1 pathless try

### Announce Handling
- Use real RNS announces in tests (not simulated/mocked)
- Match Python retry logic: 10s between attempts, max 5 tries
- Test both single message and batch delivery scenarios (multiple messages to same destination)
- Verify announced stamp cost propagates to Kotlin's stamp requirement tracking

### Timeout Semantics
- Test with full real timeouts (50+ seconds total for failure scenarios)
- Verify full error info on failure: callback fires, message state reflects failure, error reason available
- Test path discovery logic: Kotlin should request path after first pathless attempt
- Test both offline destination (clean timeout) AND intermittent connection scenarios

### Test Scenarios
- Reuse Phase 6 DirectDeliveryTestBase infrastructure with opportunistic extensions
- Test delayed announce: sender starts first, queues message, then receiver starts and announces
- Test path invalidation: both running, invalidate path, queue message, re-announce
- Full content verification: title, content, fields, timestamps, source/dest hashes
- Bidirectional testing: both Kotlin→Python and Python→Kotlin opportunistic delivery

### Claude's Discretion
- Exact test timing tolerances for async operations
- Helper method organization within test base
- Debug logging verbosity

</decisions>

<specifics>
## Specific Ideas

- Python uses `LXMFDeliveryAnnounceHandler` that sets `next_delivery_attempt = time.time()` for all matching queued messages when announce received
- Announce handler triggers `process_outbound()` via daemon thread
- Python constants from `LXMRouter`: `MAX_DELIVERY_ATTEMPTS=5`, `MAX_PATHLESS_TRIES=1`, `DELIVERY_RETRY_WAIT=10`, `PATH_REQUEST_WAIT=7`

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 07-opportunistic-delivery*
*Context gathered: 2026-01-24*
