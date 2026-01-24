# Phase 7: Opportunistic Delivery - Research

**Researched:** 2026-01-24
**Domain:** LXMF OPPORTUNISTIC delivery method implementation
**Confidence:** HIGH

## Summary

This phase implements LXMF OPPORTUNISTIC delivery - single-packet encrypted messages sent when destination path becomes available. Unlike DIRECT delivery (which establishes a link), opportunistic delivery sends messages as single encrypted packets, queuing them when the path is unknown and delivering automatically when the destination announces.

The Python reference implementation uses `LXMFDeliveryAnnounceHandler` to detect when destinations announce, triggering immediate delivery attempts for all queued messages to that destination. The retry logic uses specific constants: MAX_DELIVERY_ATTEMPTS=5, MAX_PATHLESS_TRIES=1, DELIVERY_RETRY_WAIT=10s, PATH_REQUEST_WAIT=7s.

The existing Kotlin LXMRouter already has partial opportunistic delivery support, but the announce handler trigger mechanism and some constants need alignment with Python. The test infrastructure from Phase 6 (DirectDeliveryTestBase) provides the foundation for opportunistic delivery testing.

**Primary recommendation:** Align Kotlin's opportunistic delivery constants with Python (MAX_PATHLESS_TRIES=1, not 3), implement announce-triggered queue processing matching `LXMFDeliveryAnnounceHandler`, and extend DirectDeliveryTestBase for opportunistic scenarios.

## Standard Stack

The established libraries/tools for this domain:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin coroutines | 1.7+ | Async processing, queue management | Already in codebase, handles announce callbacks |
| JUnit 5 | 5.x | Test framework | Used by DirectDeliveryTestBase |
| Kotest matchers | 5.x | Assertions | Already in test codebase |
| AtomicBoolean/AtomicReference | JDK | Thread-safe callback tracking | Proven in Phase 6 tests |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| ConcurrentHashMap | JDK | Thread-safe message queues | For pending outbound tracking |
| CopyOnWriteArrayList | JDK | Thread-safe received message collection | For test callbacks |

### No New Dependencies Needed
The existing codebase has everything required. Phase 7 builds on Phase 6 infrastructure.

## Architecture Patterns

### Python Reference Architecture

```
LXMRouter
  |-- pending_outbound: List[LXMessage]        # Messages awaiting delivery
  |-- delivery_destinations: Dict[hash, Dest]  # Registered inbound destinations
  |-- outbound_stamp_costs: Dict[hash, cost]   # Cached stamp costs from announces
  |
  |-- LXMFDeliveryAnnounceHandler
  |     |-- aspect_filter = "lxmf.delivery"
  |     |-- received_announce(dest_hash, identity, app_data):
  |     |     - update_stamp_cost(dest_hash, stamp_cost)
  |     |     - for message in pending_outbound:
  |     |         if dest_hash == message.destination_hash:
  |     |             message.next_delivery_attempt = time.time()  # Immediate retry
  |     |     - threading.Thread(target=process_outbound).start()
```

### Opportunistic Delivery Flow (Python)

```python
# In handle_outbound():
if not RNS.Transport.has_path(destination_hash) and method == OPPORTUNISTIC:
    RNS.Transport.request_path(destination_hash)
    message.next_delivery_attempt = time.time() + PATH_REQUEST_WAIT  # 7s
    # Message queued, will send when announce arrives

# In process_outbound() for OPPORTUNISTIC:
if delivery_attempts <= MAX_DELIVERY_ATTEMPTS:
    if delivery_attempts >= MAX_PATHLESS_TRIES and not has_path:
        # Request path, wait PATH_REQUEST_WAIT (7s)
        delivery_attempts += 1
        request_path()
        next_delivery_attempt = time.time() + PATH_REQUEST_WAIT
    elif delivery_attempts == MAX_PATHLESS_TRIES+1 and has_path:
        # Rediscover path (drop and re-request)
        delivery_attempts += 1
        drop_path(); request_path()
        next_delivery_attempt = time.time() + PATH_REQUEST_WAIT
    else:
        if time.time() > next_delivery_attempt:
            delivery_attempts += 1
            next_delivery_attempt = time.time() + DELIVERY_RETRY_WAIT  # 10s
            message.send()
else:
    fail_message()  # After 5 attempts
```

### Kotlin Implementation Pattern

The existing Kotlin LXMRouter has `processOpportunisticDelivery()` but needs alignment:

```kotlin
// Current (WRONG):
const val PATHLESS_DELIVERY_ATTEMPTS = 3  // Should be 1 (MAX_PATHLESS_TRIES)

// Correct constants to match Python:
const val MAX_DELIVERY_ATTEMPTS = 5
const val MAX_PATHLESS_TRIES = 1  // Renamed from PATHLESS_DELIVERY_ATTEMPTS
const val DELIVERY_RETRY_WAIT = 10_000L  // 10 seconds
const val PATH_REQUEST_WAIT = 7_000L     // 7 seconds

// Announce handler trigger (needs implementation):
fun handleDeliveryAnnounce(destHash: ByteArray, appData: ByteArray?) {
    // 1. Update stamp cost cache
    // 2. For each pending message to this destination:
    //    message.nextDeliveryAttempt = System.currentTimeMillis()
    // 3. Launch processOutbound() on coroutine
}
```

### Test Infrastructure Pattern

Extend DirectDeliveryTestBase for opportunistic scenarios:

```kotlin
abstract class OpportunisticDeliveryTestBase : DirectDeliveryTestBase() {

    // Additional setup: don't have Python announce immediately
    // Let tests control when announces happen

    protected fun delayPythonAnnounce() {
        // Skip the announce in setupDirectDelivery
    }

    protected fun triggerPythonAnnounce() {
        python("lxmf_announce")
    }

    protected fun waitForDeliveryAfterAnnounce(timeoutMs: Long): Boolean {
        // Wait for message to be delivered after announce
    }
}
```

### Anti-Patterns to Avoid
- **Mocking announces:** User decision specifies real RNS announces, not simulated
- **Using direct links for opportunistic:** Opportunistic is single-packet, no link
- **Ignoring pathless logic:** Python has specific MAX_PATHLESS_TRIES=1 behavior
- **Wrong retry timing:** Must match Python's 10s retry, 7s path wait exactly

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Thread-safe message queue | Custom locking | ConcurrentHashMap + Mutex | Already proven in codebase |
| Test callback tracking | Manual booleans | AtomicBoolean | Used successfully in Phase 6 |
| Announce handler | Custom announce tracking | RNS Transport.registerAnnounceHandler | Already registered in LXMRouter |
| Async test waiting | Thread.sleep loops | withTimeoutOrNull + delay | Kotlin coroutines pattern from Phase 6 |

**Key insight:** The Kotlin codebase already has the building blocks. Phase 7 is about aligning behavior with Python, not building new infrastructure.

## Common Pitfalls

### Pitfall 1: Constant Mismatch
**What goes wrong:** Kotlin has PATHLESS_DELIVERY_ATTEMPTS=3, Python has MAX_PATHLESS_TRIES=1
**Why it happens:** Early implementation didn't match Python exactly
**How to avoid:** Update constant to 1, rename to MAX_PATHLESS_TRIES for clarity
**Warning signs:** Different retry timing behavior between Kotlin and Python

### Pitfall 2: Announce Handler Not Triggering Queue Processing
**What goes wrong:** Messages stay queued even after destination announces
**Why it happens:** handleDeliveryAnnounce doesn't set nextDeliveryAttempt to now
**How to avoid:** Match Python's LXMFDeliveryAnnounceHandler exactly:
```python
for lxmessage in self.lxmrouter.pending_outbound:
    if destination_hash == lxmessage.destination_hash:
        if lxmessage.method == DIRECT or lxmessage.method == OPPORTUNISTIC:
            lxmessage.next_delivery_attempt = time.time()  # Immediate!
```
**Warning signs:** Tests timeout waiting for delivery after announce

### Pitfall 3: Path Request vs Has Path Logic
**What goes wrong:** Messages fail when path exists but delivery still fails
**Why it happens:** Python has specific logic for "rediscovery" at MAX_PATHLESS_TRIES+1
**How to avoid:** Implement the exact Python flow:
1. Attempt 1 (pathless): Try send, will fail without path
2. Attempt 2+ (no path): Request path, wait 7s
3. Attempt MAX_PATHLESS_TRIES+1 (has path but failing): Drop path, re-request
**Warning signs:** Intermittent delivery failures with stale paths

### Pitfall 4: Testing with Pre-Announced Destinations
**What goes wrong:** Opportunistic tests pass trivially because Python already announced
**Why it happens:** DirectDeliveryTestBase announces in setup
**How to avoid:** Modify setup for opportunistic tests to skip initial announce, or use separate test base
**Warning signs:** Tests don't actually exercise queue-then-announce behavior

### Pitfall 5: Timeout Test Duration
**What goes wrong:** Failure tests take forever or timeout assertions fail
**Why it happens:** Full failure scenario = 5 attempts * (10s retry + 7s path wait) = 85s
**How to avoid:**
- Use shorter timeouts for success scenarios (10-15s)
- Accept 90+ second tests for full failure scenarios (per CONTEXT.md: 50+ seconds)
- Use @Timeout annotation appropriately
**Warning signs:** Tests pass locally but timeout in CI

### Pitfall 6: Callback Registration Timing
**What goes wrong:** Delivery callbacks not fired
**Why it happens:** Callback registered after message already in sending state
**How to avoid:** Register callbacks BEFORE calling handleOutbound
**Warning signs:** AtomicBoolean.get() returns false after delivery confirmed

## Code Examples

### Python Announce Handler (Reference)
```python
# Source: ~/repos/LXMF/LXMF/Handlers.py lines 9-33
class LXMFDeliveryAnnounceHandler:
    def __init__(self, lxmrouter):
        self.aspect_filter = APP_NAME+".delivery"
        self.receive_path_responses = True
        self.lxmrouter = lxmrouter

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            stamp_cost = stamp_cost_from_app_data(app_data)
            self.lxmrouter.update_stamp_cost(destination_hash, stamp_cost)
        except Exception as e:
            RNS.log(f"Error decoding stamp cost: {e}", RNS.LOG_ERROR)

        # CRITICAL: Trigger immediate delivery for all matching queued messages
        for lxmessage in self.lxmrouter.pending_outbound:
            if destination_hash == lxmessage.destination_hash:
                if lxmessage.method == LXMessage.DIRECT or lxmessage.method == LXMessage.OPPORTUNISTIC:
                    lxmessage.next_delivery_attempt = time.time()  # NOW

                    def outbound_trigger():
                        while self.lxmrouter.outbound_processing_lock.locked():
                            time.sleep(0.1)
                        self.lxmrouter.process_outbound()

                    threading.Thread(target=outbound_trigger, daemon=True).start()
```

### Python Opportunistic Process Outbound (Reference)
```python
# Source: ~/repos/LXMF/LXMF/LXMRouter.py lines 2554-2581
# Outbound handling for opportunistic messages
if lxmessage.method == LXMessage.OPPORTUNISTIC:
    if lxmessage.delivery_attempts <= LXMRouter.MAX_DELIVERY_ATTEMPTS:
        if lxmessage.delivery_attempts >= LXMRouter.MAX_PATHLESS_TRIES and not RNS.Transport.has_path(lxmessage.get_destination().hash):
            RNS.log(f"Requesting path after {lxmessage.delivery_attempts} pathless tries")
            lxmessage.delivery_attempts += 1
            RNS.Transport.request_path(lxmessage.get_destination().hash)
            lxmessage.next_delivery_attempt = time.time() + LXMRouter.PATH_REQUEST_WAIT
            lxmessage.progress = 0.01
        elif lxmessage.delivery_attempts == LXMRouter.MAX_PATHLESS_TRIES+1 and RNS.Transport.has_path(...):
            # Rediscover path
            lxmessage.delivery_attempts += 1
            RNS.Reticulum.get_instance().drop_path(...)
            RNS.Transport.request_path(...)
            lxmessage.next_delivery_attempt = time.time() + LXMRouter.PATH_REQUEST_WAIT
        else:
            if not hasattr(lxmessage, "next_delivery_attempt") or time.time() > lxmessage.next_delivery_attempt:
                lxmessage.delivery_attempts += 1
                lxmessage.next_delivery_attempt = time.time() + LXMRouter.DELIVERY_RETRY_WAIT
                lxmessage.send()
    else:
        self.fail_message(lxmessage)
```

### Kotlin Announce Handler (Existing, Needs Enhancement)
```kotlin
// Source: LXMRouter.kt lines 1116-1151
// Current handleDeliveryAnnounce - needs enhancement
fun handleDeliveryAnnounce(destHash: ByteArray, appData: ByteArray?) {
    // ... parse stamp cost ...

    // CRITICAL: Must match Python's immediate trigger behavior
    val destHashHex = destHash.toHexString()
    processingScope?.launch {
        pendingOutboundMutex.withLock {
            for (message in pendingOutbound) {
                if (message.destinationHash.toHexString() == destHashHex) {
                    // Python: message.next_delivery_attempt = time.time()
                    message.nextDeliveryAttempt = System.currentTimeMillis()  // NOW!
                }
            }
        }
        processOutbound()  // Trigger immediate processing
    }
}
```

### Test Pattern: Delayed Announce
```kotlin
// Pattern for testing queue-then-announce behavior
@Test
@Timeout(60, unit = TimeUnit.SECONDS)
fun `message queued then delivered after announce`() = runBlocking {
    // 1. Create message BEFORE destination is known
    val message = LXMessage.create(
        destination = pythonDest,  // Destination object exists but not announced
        source = kotlinDestination,
        content = "Opportunistic test",
        desiredMethod = DeliveryMethod.OPPORTUNISTIC
    )

    // 2. Track callbacks
    val deliveryFired = AtomicBoolean(false)
    message.deliveryCallback = { deliveryFired.set(true) }

    // 3. Queue message (will not send yet - no path)
    kotlinRouter.handleOutbound(message)
    delay(1000)  // Allow queue processing

    // 4. Message should be queued, not delivered
    message.state shouldNotBe MessageState.DELIVERED

    // 5. NOW announce the destination
    python("lxmf_announce")

    // 6. Wait for delivery (announce triggers immediate send)
    withTimeoutOrNull(15.seconds) {
        while (!deliveryFired.get()) { delay(100) }
    }

    deliveryFired.get() shouldBe true
    listOf(MessageState.SENT, MessageState.DELIVERED) shouldContain message.state
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| PATHLESS_DELIVERY_ATTEMPTS=3 | MAX_PATHLESS_TRIES=1 | Phase 7 | Matches Python behavior |
| Announce handler doesn't trigger queue | Announce sets nextDeliveryAttempt=now | Phase 7 | Enables opportunistic delivery |

**Constants to update in Kotlin LXMRouter:**
```kotlin
// Change from:
const val PATHLESS_DELIVERY_ATTEMPTS = 3

// To (matching Python exactly):
const val MAX_PATHLESS_TRIES = 1  // Python: MAX_PATHLESS_TRIES = 1
```

## Open Questions

Things that couldn't be fully resolved:

1. **TCP Interface Stability**
   - What we know: Phase 6 noted "TCP interface compatibility issue - connections drop after packet transmission"
   - What's unclear: Whether this affects opportunistic (single-packet) delivery
   - Recommendation: Opportunistic uses single packets (not links), may be less affected. Test early.

2. **Stamp Cost Propagation from Announces**
   - What we know: Python's announce handler extracts stamp cost and caches it
   - What's unclear: Whether current Kotlin handleDeliveryAnnounce does this correctly
   - Recommendation: Verify stamp cost parsing in tests, especially for interop

3. **Batch Delivery Ordering**
   - What we know: Python processes all queued messages in list order
   - What's unclear: Whether Kotlin's concurrent processing maintains order
   - Recommendation: Test batch scenario (multiple messages to same destination)

## Sources

### Primary (HIGH confidence)
- ~/repos/LXMF/LXMF/LXMRouter.py - process_outbound() lines 2504-2720
- ~/repos/LXMF/LXMF/Handlers.py - LXMFDeliveryAnnounceHandler lines 9-33
- ~/repos/LXMF/LXMF/LXMessage.py - Message states and methods
- ./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt - Current Kotlin implementation

### Secondary (MEDIUM confidence)
- ./lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/DirectDeliveryTestBase.kt - Test infrastructure
- ./lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/LiveDeliveryTest.kt - Delivery test patterns

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing codebase infrastructure
- Architecture: HIGH - Direct reference to Python implementation
- Pitfalls: HIGH - Identified from code analysis and Phase 6 learnings

**Research date:** 2026-01-24
**Valid until:** 60 days (Python LXMF implementation is stable)

---

## Implementation Checklist for Planner

The planner should create tasks covering:

1. **Constant Alignment**
   - [ ] Update PATHLESS_DELIVERY_ATTEMPTS=3 to MAX_PATHLESS_TRIES=1
   - [ ] Verify DELIVERY_RETRY_WAIT=10000 and PATH_REQUEST_WAIT=7000

2. **Announce Handler Enhancement**
   - [ ] Enhance handleDeliveryAnnounce to set nextDeliveryAttempt=now for matching messages
   - [ ] Ensure processOutbound() is triggered after announce

3. **Opportunistic Delivery Logic**
   - [ ] Align processOpportunisticDelivery with Python's path request logic
   - [ ] Implement path rediscovery at MAX_PATHLESS_TRIES+1

4. **Test Infrastructure**
   - [ ] Create OpportunisticDeliveryTestBase (or modify DirectDeliveryTestBase)
   - [ ] Add method to control Python announce timing

5. **Test Scenarios (from CONTEXT.md)**
   - [ ] Test delayed announce: sender queues, receiver announces later
   - [ ] Test path invalidation: both running, invalidate, re-announce
   - [ ] Test batch delivery: multiple messages to same destination
   - [ ] Test failure timeout: verify callback after max attempts
   - [ ] Bidirectional: Kotlin->Python and Python->Kotlin opportunistic
