# Phase 6: Direct Delivery - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

LXMF messages delivered via DIRECT method over established Reticulum Links. Tests bidirectional message delivery between Kotlin and Python clients. Creating Links, sending messages, receiving confirmations, and handling failures.

</domain>

<decisions>
## Implementation Decisions

### Test Infrastructure
- Extend existing PythonBridge with Link/delivery commands (same model as phases 1-5)
- Run real Reticulum instance in Python bridge (not mocked/simulated)
- Reuse existing test identity fixtures (testSourceIdentity, testDestinationIdentity)

### Link Establishment
- Test both directions: Kotlin-initiated Links AND Python-initiated Links
- Use callback-based waiting for Link establishment (onLinkEstablished pattern)
- Test both single-use Links (one message, teardown) and persistent Links (multiple messages)
- Configurable timeout for Link establishment (from test config or environment)

### Delivery Confirmation
- Require both: delivery callback fires AND content verified on receiver
- Test LXMF delivery receipts (acknowledgment back to sender)
- Progressive field verification: base fields test, then all fields (attachments, images) test

### Failure Scenarios
- Test Link drops mid-delivery (simulate teardown during send)
- Test unreachable destination (timeout handling)
- Test corruption handling (invalid signature, bad format rejected over Link)
- Match Python behavior for failure callbacks/exceptions

### Claude's Discretion
- Transport choice for connecting Kotlin and Python Reticulum instances
- Async handling approach (coroutines vs blocking primitives)
- Specific timeout default values
- Test organization and naming

</decisions>

<specifics>
## Specific Ideas

- Real Reticulum instances on both sides — not mocked. Tests actual protocol behavior.
- Leverage all the existing interop infrastructure from phases 1-5 (bridge, fixtures, patterns)
- Failure behavior should match Python LXMF exactly — interop means same semantics

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 06-direct-delivery*
*Context gathered: 2026-01-24*
