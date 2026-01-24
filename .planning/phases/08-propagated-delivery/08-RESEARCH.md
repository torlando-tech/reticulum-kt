# Phase 8: Propagated Delivery - Research

**Researched:** 2026-01-24
**Domain:** LXMF PROPAGATED delivery via Python propagation node
**Confidence:** HIGH

## Summary

Phase 8 implements PROPAGATED delivery - the store-and-forward messaging method where Kotlin clients submit messages to a Python propagation node for later retrieval by recipients. This is fundamentally different from DIRECT (link-based) and OPPORTUNISTIC (single-packet broadcast) delivery because it involves an intermediary server that stores messages until recipients request them.

The key components are:
1. **Message submission**: Kotlin creates a propagation-formatted message with a propagation stamp, establishes a link to the propagation node, and sends the message
2. **Propagation stamp validation**: The node validates the proof-of-work stamp meets its cost requirements before accepting the message
3. **Message retrieval**: Recipients connect to the node, request pending messages via the `/get` request path, and receive stored messages

The Kotlin `LXMRouter` already has substantial PROPAGATED delivery scaffolding (lines 807-889, 1277-1688), including `processPropagatedDelivery()`, `sendViaPropagation()`, propagation node tracking, and message retrieval state machine. The Python bridge needs extension to run a propagation node server for testing.

**Primary recommendation:** Create `PropagatedDeliveryTestBase` extending `DirectDeliveryTestBase` with Python propagation node management. Add bridge commands `propagation_node_start`, `propagation_node_get_messages`, and `propagation_node_get_hash`. Test submission with varying stamp difficulties, retrieval via sync, and rejection for insufficient stamps.

## Standard Stack

### Core Implementation (Already Exists)

| Class | Location | Purpose | Status |
|-------|----------|---------|--------|
| `LXMRouter` | `lxmf-core/.../LXMRouter.kt` | Propagation node interaction | Partially implemented |
| `LXMessage` | `lxmf-core/.../LXMessage.kt` | `propagationPacked` field support | Implemented |
| `LXStamper` | `lxmf-core/.../LXStamper.kt` | Propagation stamp generation | Implemented |
| `Link` | `rns-core/.../link/Link.kt` | Link to propagation node | Implemented |
| `Resource` | `rns-core/.../resource/Resource.kt` | Large message transfer | Implemented |

### Test Infrastructure (Needs Extension)

| Component | Purpose | Required Changes |
|-----------|---------|------------------|
| `PythonBridge` | Command bridge to Python | Add propagation node commands |
| `bridge_server.py` | Python command handler | Add `propagation_node_*` commands |
| `PropagatedDeliveryTestBase.kt` | Test scaffolding | New file - propagation node lifecycle |

### Python Reference Implementation

| File | Purpose | Key Functions |
|------|---------|---------------|
| `LXMF/LXMRouter.py` | Propagation node server | `enable_propagation()`, `propagation_packet()`, `message_get_request()` |
| `LXMF/LXMPeer.py` | Peer protocol | `OFFER_REQUEST_PATH="/offer"`, `MESSAGE_GET_PATH="/get"` |
| `LXMF/LXMessage.py` | Message packing | `propagation_packed`, `transient_id`, `get_propagation_stamp()` |

## Architecture Patterns

### Propagation Message Format

For PROPAGATED delivery, Python packs messages differently than DIRECT:

```python
# From LXMessage.py pack() for PROPAGATED method (lines 431-449):
# 1. Encrypt packed message for destination
self.__pn_encrypted_data = self.__destination.encrypt(self.packed[DESTINATION_LENGTH:])

# 2. Create propagation format: dest_hash + encrypted_data
lxmf_data = self.packed[:DESTINATION_LENGTH] + self.__pn_encrypted_data

# 3. Calculate transient_id from this
self.transient_id = RNS.Identity.full_hash(lxmf_data)

# 4. Append propagation stamp if present
if self.propagation_stamp != None:
    lxmf_data += self.propagation_stamp

# 5. Wrap in propagation transfer format: [timebase, [message_list]]
self.propagation_packed = msgpack.packb([time.time(), [lxmf_data]])
```

**Key insight:** Propagation format encrypts the message content for the final recipient (not the propagation node), allowing the node to store without reading content. The 32-byte transient_id is computed from the destination hash + encrypted data, and is used to track duplicate messages.

### Propagation Stamp Generation

Propagation stamps use different expand rounds than regular stamps:

```python
# From LXMRouter.py (lines 349-358):
# Regular stamp: 3000 expand rounds (default)
# Propagation node stamp: 1000 expand rounds
generated_stamp, value = LXStamper.generate_stamp(
    self.transient_id,  # NOT message_id - uses transient_id
    target_cost,
    expand_rounds=LXStamper.WORKBLOCK_EXPAND_ROUNDS_PN  # 1000 rounds
)
```

**Critical:** Propagation stamps hash the `transient_id` (hash of propagation-formatted data), not the `message_id` (hash of the original message). This prevents replay attacks where valid stamps for one propagation node could be reused at another.

### Submission Protocol

Client submits to propagation node via link packet/resource:

```
1. Client establishes Link to propagation node destination
2. Client calls link.identify(identity) to reveal sender
3. Client sends propagation_packed data as packet (small) or Resource (large)
4. Node validates stamp via propagation_packet() callback
5. Node stores message if stamp valid, rejects otherwise
6. Client receives proof (accepted) or error code (rejected)
```

### Retrieval Protocol

The `/get` request path handles both listing and fetching:

```python
# From LXMRouter.py message_get_request() (lines 1410-1490):

# Phase 1: Get message list
# Request: [None, None] - returns list of available transient_ids

# Phase 2: Get specific messages
# Request: [wants, haves, limit]
#   wants: list of transient_ids to download
#   haves: list of transient_ids already have (optional)
#   limit: max KB to transfer
# Response: list of lxmf_data for requested transient_ids
```

### Test Architecture: Python Propagation Node Server

```
Test Setup:
+------------------------------------------------------------------+
|  JUnit Test Process                                               |
|  +--------------------+    +--------------------------------+     |
|  | Kotlin             |    | Python (subprocess)            |     |
|  |                    |    |                                |     |
|  | Reticulum          |    | Reticulum                      |     |
|  |   +- TCPClient     |<-->|   +- TCPServer                 |     |
|  |                    |TCP |                                |     |
|  | LXMRouter          |    | LXMRouter                      |     |
|  |   +- Identity      |    |   +- Identity                  |     |
|  |   +- PropNode ref  |    |   +- enable_propagation()      |     |
|  |                    |    |   +- propagation_destination   |     |
|  +--------------------+    +--------------------------------+     |
|           |                         |                             |
|           |    JSON Bridge (stdin/stdout)                         |
|           +-------------------------+                             |
+------------------------------------------------------------------+
```

### Recommended Test Structure

```
lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/
+-- PropagatedDeliveryTestBase.kt   # Base with propagation node lifecycle
+-- KotlinToPropagationTest.kt      # Kotlin submits, verify node stores
+-- PropagationToKotlinTest.kt      # Kotlin retrieves from node
+-- PropagationStampRejectionTest.kt # Insufficient stamp rejection
```

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Propagation format | Manual packing | `LXMessage.packPropagation()` | Complex encryption/format |
| Transient ID calc | Manual hash | Existing `transient_id` logic | Must match Python exactly |
| Stamp for propagation | Regular stamp | `get_propagation_stamp()` | Different expand rounds |
| Message list parsing | Manual msgpack | Existing LXMRouter methods | Error handling complexity |
| Node stamp validation | Custom check | Python node's validation | Reference behavior |

**Key insight:** The Kotlin `LXMRouter` already has propagation support. Testing should verify it works with a real Python propagation node, not reimplement the protocol.

## Common Pitfalls

### Pitfall 1: Using message_id Instead of transient_id for Propagation Stamp

**What goes wrong:** Stamp generated for message_id fails validation at propagation node
**Why it happens:** Propagation stamps hash `transient_id = SHA256(dest_hash + encrypted_data)`, not the original message hash
**How to avoid:**
- Ensure `pack()` is called before stamp generation to compute transient_id
- Use `get_propagation_stamp()` which handles this correctly
**Warning signs:** "Invalid propagation stamp" errors from node

### Pitfall 2: Wrong Expand Rounds for Propagation Stamps

**What goes wrong:** Workblock size mismatch, stamp validation fails
**Why it happens:** Propagation nodes use 1000 expand rounds, not 3000
**How to avoid:** Always use `WORKBLOCK_EXPAND_ROUNDS_PN` (1000) for propagation stamps
**Warning signs:** Stamps that work in unit tests fail against real propagation node

### Pitfall 3: Not Waiting for Link Establishment Before Sending

**What goes wrong:** Propagation packet dropped silently
**Why it happens:** Link status is PENDING during handshake
**How to avoid:** Wait for `link.status == LinkConstants.ACTIVE` before sending
**Warning signs:** Messages submitted but never appear in node storage

### Pitfall 4: Missing Identity on Propagation Link

**What goes wrong:** Node returns ERROR_NO_IDENTITY (0xf0)
**Why it happens:** Propagation node requires sender to identify themselves via `link.identify()`
**How to avoid:** Always call `link.identify(identity)` after link establishment
**Warning signs:** Submission fails with error code 240 (0xf0)

### Pitfall 5: Stamp Cost Configuration Mismatch

**What goes wrong:** Stamps rejected as insufficient even at required difficulty
**Why it happens:** Node accepts `cost - flexibility` as minimum, tests may not account for this
**How to avoid:**
- Use low difficulty for tests (8-bit) to make generation fast
- Configure node with matching or lower cost
- Test explicitly generates "just below threshold" stamps
**Warning signs:** Valid stamps rejected, inconsistent test results

### Pitfall 6: Propagation Packed Format Confusion

**What goes wrong:** Node fails to parse submitted message
**Why it happens:** Propagation transfer format is `[timebase, [message_list]]`, not raw lxmf_data
**How to avoid:** Use `propagation_packed` field from LXMessage, not `packed`
**Warning signs:** Parse errors at propagation node, malformed data logs

## Code Examples

### Example 1: Python Bridge - Start Propagation Node

```python
# Source: Pattern from existing bridge_server.py + LXMRouter.enable_propagation()
def cmd_propagation_node_start(params):
    """Start a propagation node with configurable stamp cost."""
    global lxmf_router, propagation_node_hash

    if lxmf_router is None:
        return {'error': 'LXMF router not started'}

    stamp_cost = int(params.get('stamp_cost', 8))  # Default low for fast tests
    stamp_flexibility = int(params.get('stamp_flexibility', 0))

    # Configure stamp cost before enabling propagation
    lxmf_router.propagation_stamp_cost = stamp_cost
    lxmf_router.propagation_stamp_cost_flexibility = stamp_flexibility

    # Enable propagation node functionality
    lxmf_router.enable_propagation()

    # Get the propagation destination hash
    propagation_node_hash = lxmf_router.propagation_destination.hash

    return {
        'propagation_hash': bytes_to_hex(propagation_node_hash),
        'stamp_cost': stamp_cost,
        'stamp_flexibility': stamp_flexibility,
        'identity_hash': bytes_to_hex(lxmf_router.identity.hash),
        'identity_public_key': bytes_to_hex(lxmf_router.identity.get_public_key())
    }
```

### Example 2: Python Bridge - Get Stored Messages

```python
# Source: Pattern from LXMRouter.propagation_entries
def cmd_propagation_node_get_messages(params):
    """Get list of messages stored on propagation node."""
    global lxmf_router

    if lxmf_router is None:
        return {'error': 'LXMF router not started'}

    if not lxmf_router.propagation_node:
        return {'error': 'Propagation not enabled'}

    messages = []
    for transient_id, entry in lxmf_router.propagation_entries.items():
        # entry = [dest_hash, filepath, received_time, size, handled_peers, unhandled_peers, stamp_value]
        messages.append({
            'transient_id': bytes_to_hex(transient_id),
            'destination_hash': bytes_to_hex(entry[0]),
            'received_time': entry[2],
            'size': entry[3],
            'stamp_value': entry[6]
        })

    return {'messages': messages, 'count': len(messages)}
```

### Example 3: Kotlin Test - Submit Message to Propagation Node

```kotlin
// Source: Pattern from existing DirectDeliveryTestBase
@Test
fun `Kotlin can submit message to Python propagation node`() = runBlocking {
    // 1. Get propagation node hash and identity from bridge
    val nodeResult = python("propagation_node_start", "stamp_cost" to 8)
    val nodeHash = nodeResult.getBytes("propagation_hash")
    val nodeIdentityPubKey = nodeResult.getBytes("identity_public_key")

    // 2. Register propagation node identity for path establishment
    Identity.remember(
        packetHash = nodeHash,
        destHash = nodeHash,
        publicKey = nodeIdentityPubKey,
        appData = null
    )

    // 3. Wait for path to propagation node
    val announced = python("propagation_node_announce")
    assertTrue(waitForPath(nodeHash, timeoutMs = 5000), "Path to propagation node not found")

    // 4. Set active propagation node in Kotlin router
    kotlinRouter.setActivePropagationNode(nodeHash.toHexString())

    // 5. Create message with PROPAGATED delivery method
    val message = LXMessage.create(
        destination = createDestinationForRecipient(),
        source = kotlinDestination,
        content = "Test propagated message",
        title = "Propagation Test",
        desiredMethod = DeliveryMethod.PROPAGATED
    )

    // 6. Generate propagation stamp
    val stampCost = 8
    message.generatePropagationStamp(stampCost, expandRounds = 1000)

    // 7. Submit via router
    kotlinRouter.handleOutbound(message)

    // 8. Wait for submission (SENT state for propagated = accepted by node)
    val delivered = waitForMessageState(message, MessageState.SENT, timeoutMs = 30000)
    assertTrue(delivered, "Message not accepted by propagation node")

    // 9. Verify message appears in node storage
    val storedMessages = python("propagation_node_get_messages")
    val count = storedMessages.getInt("count")
    assertTrue(count >= 1, "Message not found in node storage")
}
```

### Example 4: Kotlin Test - Retrieve Message from Propagation Node

```kotlin
// Source: Pattern from LXMRouter.requestMessagesFromPropagationNode()
@Test
fun `Kotlin can retrieve message from Python propagation node`() = runBlocking {
    // Setup: Python submits a message to its own propagation node
    // (addressed to Kotlin's identity)
    val submitResult = python("propagation_node_submit_for_recipient",
        "recipient_hash" to kotlinDestination.hash,
        "content" to "Message for Kotlin"
    )
    assertTrue(submitResult.getBoolean("submitted"), "Python failed to store test message")

    // 1. Request messages from propagation node
    kotlinRouter.requestMessagesFromPropagationNode()

    // 2. Wait for transfer to complete
    val deadline = System.currentTimeMillis() + 30000
    while (System.currentTimeMillis() < deadline) {
        when (kotlinRouter.propagationTransferState) {
            PropagationTransferState.COMPLETE -> break
            PropagationTransferState.FAILED -> fail("Propagation transfer failed")
            else -> delay(100)
        }
    }

    assertEquals(PropagationTransferState.COMPLETE, kotlinRouter.propagationTransferState)
    assertTrue(kotlinRouter.propagationTransferLastResult >= 1, "No messages retrieved")

    // 3. Verify message was delivered via callback
    assertTrue(receivedMessages.isNotEmpty(), "No message delivered to callback")
    assertEquals("Message for Kotlin", receivedMessages.first().contentAsString)
}
```

### Example 5: Test - Insufficient Stamp Rejection

```kotlin
// Source: Context decision for stamp rejection testing
@Test
fun `Propagation node rejects message with insufficient stamp`() = runBlocking {
    // 1. Configure node with high stamp cost
    val nodeResult = python("propagation_node_start",
        "stamp_cost" to 12,  // Require 12-bit stamp
        "stamp_flexibility" to 0  // No flexibility
    )

    // 2. Create message with insufficient stamp (8-bit, need 12-bit)
    val message = LXMessage.create(
        destination = createDestinationForRecipient(),
        source = kotlinDestination,
        content = "Test insufficient stamp",
        desiredMethod = DeliveryMethod.PROPAGATED
    )

    // Generate valid but insufficient stamp
    message.generatePropagationStamp(stampCost = 8, expandRounds = 1000)  // 8 < 12

    // 3. Submit via router
    kotlinRouter.handleOutbound(message)

    // 4. Wait for rejection
    val rejected = waitForMessageState(message, MessageState.REJECTED, timeoutMs = 30000)
    assertTrue(rejected, "Message should have been rejected")

    // 5. Verify node did not store the message
    val storedMessages = python("propagation_node_get_messages")
    assertEquals(0, storedMessages.getInt("count"), "Rejected message should not be stored")
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No propagation support | Propagation scaffolding in LXMRouter | Current implementation | Ready for testing |
| Only message format tests | Live network tests | Phase 6-7 | E2E verification |
| Mocked propagation node | Real Python propagation node | This phase | True interoperability |

**Current implementation status:**
- `LXMRouter.processPropagatedDelivery()`: Implemented, untested against real node
- `LXMRouter.sendViaPropagation()`: Implemented, untested
- `LXMessage.propagationPacked`: Present but may need verification
- `LXStamper` propagation support: 1000 expand rounds supported
- Message retrieval: State machine implemented, needs E2E testing

## Open Questions

1. **Propagation Stamp in Kotlin LXMessage**
   - What we know: Python's `get_propagation_stamp()` uses transient_id and 1000 expand rounds
   - What's unclear: Whether Kotlin's `LXMessage` has equivalent method or needs addition
   - Recommendation: Verify `LXMessage.generatePropagationStamp()` exists, add if missing

2. **Message Rejection Signaling**
   - What we know: Python sends ERROR_INVALID_STAMP (0xf5) packet on rejection
   - What's unclear: Whether Kotlin LXMRouter handles this in `propagationTransferSignallingPacket()`
   - Recommendation: Test rejection scenario and verify error propagates to message state

3. **Sync Deletion Verification**
   - What we know: Context requires "second sync returns empty, confirming message consumed"
   - What's unclear: Exact behavior - does Python delete after sending, or mark as handled?
   - Recommendation: Test shows messages removed from `propagation_entries` after client retrieval

4. **TCP Interface Stability**
   - What we know: STATE.md notes "TCP interface compatibility issue - connections drop after packet transmission"
   - What's unclear: Whether this affects propagation link stability
   - Recommendation: Monitor for connection drops during Resource transfers, implement reconnection if needed

## Sources

### Primary (HIGH confidence)

- `~/repos/LXMF/LXMF/LXMRouter.py` - Python propagation node implementation
  - `enable_propagation()` (lines 533-667): Node setup and message indexing
  - `propagation_packet()` (lines 2098-2127): Incoming message handling with stamp validation
  - `message_get_request()` (lines 1410-1490): Message retrieval protocol
  - `lxmf_propagation()` (lines 2298-2353): Message storage logic
  - Lines 483-524: `request_messages_from_propagation_node()` - client retrieval
  - Lines 2665-2718: Propagated delivery processing

- `~/repos/LXMF/LXMF/LXMessage.py` - Propagation message format
  - Lines 431-449: Propagation packing with encryption
  - Lines 334-358: `get_propagation_stamp()` with PN expand rounds

- `~/repos/LXMF/LXMF/LXMPeer.py` - Peer protocol constants
  - Line 14-15: `OFFER_REQUEST_PATH`, `MESSAGE_GET_PATH`
  - Lines 24-29: Error codes including `ERROR_INVALID_STAMP`

- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt` - Kotlin implementation
  - Lines 807-889: `processPropagatedDelivery()`, `sendViaPropagation()`
  - Lines 1277-1688: Propagation node methods

- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMFConstants.kt` - Protocol constants
  - Lines 262-305: Propagation constants matching Python

### Secondary (MEDIUM confidence)

- `./lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliveryTestBase.kt` - Test pattern
- `./.planning/phases/08-propagated-delivery/08-CONTEXT.md` - User decisions

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - verified against existing Kotlin codebase and Python reference
- Architecture: HIGH - based on Python reference implementation analysis
- Pitfalls: HIGH - derived from protocol analysis and common propagation issues

**Research date:** 2026-01-24
**Valid until:** 30 days (stable protocol, established patterns)
