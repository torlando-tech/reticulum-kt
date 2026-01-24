# Phase 6: Direct Delivery - Research

**Researched:** 2026-01-24
**Domain:** LXMF Direct Message Delivery over Reticulum Links
**Confidence:** HIGH

## Summary

Phase 6 tests LXMF DIRECT delivery - sending messages over established Reticulum Links between Kotlin and Python clients. The Kotlin implementation already has substantial direct delivery support in LXMRouter, including link establishment, packet/resource sending, and delivery confirmations. The primary work is building test infrastructure that runs live Reticulum instances in both Kotlin and Python, connected via a transport mechanism, and verifying bidirectional message delivery.

The key insight from research: the existing PythonBridge is designed for cryptographic function interop testing (no live Reticulum), but Phase 6 requires actual Reticulum network connections. This requires either extending the bridge to manage Reticulum instances OR using TCP/Unix socket interfaces for Kotlin-Python connectivity.

**Primary recommendation:** Use TCP interfaces to connect Kotlin and Python Reticulum instances, with Python acting as a server and Kotlin connecting as a client. Extend PythonBridge to spawn and control a Python LXMF router for message receipt verification.

## Standard Stack

### Core Implementation (Already Exists)

| Class | Location | Purpose | Status |
|-------|----------|---------|--------|
| `LXMRouter` | `lxmf-core/.../LXMRouter.kt` | LXMF message routing | Implemented |
| `LXMessage` | `lxmf-core/.../LXMessage.kt` | Message pack/unpack | Implemented |
| `Link` | `rns-core/.../link/Link.kt` | Encrypted link handling | Implemented |
| `Resource` | `rns-core/.../resource/Resource.kt` | Large data transfer | Implemented |
| `TCPClientInterface` | `rns-interfaces/.../tcp/TCPClientInterface.kt` | TCP connectivity | Implemented |

### Test Infrastructure (Needs Extension)

| Component | Purpose | Required Changes |
|-----------|---------|------------------|
| `PythonBridge` | Command bridge to Python | Add LXMF router commands |
| `InteropTestBase` | Test scaffolding | Add Reticulum lifecycle management |
| Python bridge_server.py | Command handler | Add LXMF message receive/send commands |

### Transport for Testing

| Option | Pros | Cons | Recommendation |
|--------|------|------|----------------|
| TCP Interface | Simple, cross-platform, well-tested | Requires port allocation | **PRIMARY** |
| Unix Socket | Low latency, no port conflicts | Linux/Mac only | Secondary |
| Shared Instance | Uses existing daemon | Complex setup | Avoid for testing |

**Selected approach:** TCP interface with dynamic port allocation for test isolation.

## Architecture Patterns

### Test Architecture: Python Server + Kotlin Client

```
Test Setup:
+---------------------------------------------------------+
|  JUnit Test Process                                     |
|  +----------------+    +-----------------------------+  |
|  | Kotlin         |    | Python (subprocess)         |  |
|  |                |    |                             |  |
|  | Reticulum      |    | Reticulum                   |  |
|  |   +- Transport |<-->|   +- Transport              |  |
|  |   +- TCPClient |TCP |   +- TCPServer              |  |
|  |                |    |                             |  |
|  | LXMRouter      |    | LXMRouter                   |  |
|  |   +- Identity  |    |   +- Identity               |  |
|  |   +- Dest      |    |   +- Dest                   |  |
|  |                |    |                             |  |
|  +----------------+    +-----------------------------+  |
|           |                         |                   |
|           |    JSON Bridge (stdin/stdout)               |
|           +-------------------------+                   |
+---------------------------------------------------------+
```

### Message Flow: DIRECT Delivery (Kotlin -> Python)

```
1. Kotlin creates LXMessage with DIRECT method
2. Kotlin's LXMRouter establishes Link to Python destination
3. Link handshake completes (ECDH + proof exchange)
4. Kotlin sends packed LXMF data over Link
   - Small: Link.send() as packet
   - Large: Resource transfer
5. Python receives packet/resource on delivery destination
6. Python unpacks and validates LXMF message
7. Python sends proof back (delivery confirmation)
8. Kotlin receives proof, triggers delivery callback
```

### Recommended Test Structure

```
lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/
+-- DirectDeliveryTestBase.kt      # Base class with Reticulum lifecycle
+-- KotlinToPythonDirectTest.kt    # Kotlin -> Python delivery tests
+-- PythonToKotlinDirectTest.kt    # Python -> Kotlin delivery tests
+-- BidirectionalDirectTest.kt     # Both directions
+-- DeliveryReceiptTest.kt         # Receipt/confirmation tests
+-- DirectDeliveryFailureTest.kt   # Failure scenario tests
```

### Pattern: Coroutine-based Waiting with Timeout

```kotlin
// Source: Kotlin coroutines standard pattern
suspend fun waitForLinkEstablished(link: Link, timeout: Duration = 30.seconds): Boolean {
    return withTimeoutOrNull(timeout) {
        while (link.status != LinkConstants.ACTIVE) {
            if (link.status == LinkConstants.CLOSED) {
                return@withTimeoutOrNull false
            }
            delay(100.milliseconds)
        }
        true
    } ?: false
}
```

### Anti-Patterns to Avoid

- **Thread.sleep() in tests:** Use coroutine-based waiting instead
- **Fixed port allocation:** Use dynamic ports to avoid test conflicts
- **Shared test state:** Each test should have isolated Reticulum instances
- **Forgetting to teardown:** Always stop Reticulum and Python process

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Link encryption | Custom crypto | `Link.send()` | Already implements ECDH+AES |
| Message packing | Manual bytes | `LXMessage.pack()` | Complex msgpack format |
| Proof generation | Manual signing | `Packet.prove()` | Ed25519 over correct data |
| Resource transfer | Chunked sends | `Resource.create()` | Handles segmentation |
| Port allocation | Random ports | `ServerSocket(0)` | OS assigns free port |

**Key insight:** The Kotlin implementation already has full Link and LXMessage support. Testing should verify existing code works, not reimplement it.

## Common Pitfalls

### Pitfall 1: Link Establishment Race Condition

**What goes wrong:** Test sends message before link is fully established
**Why it happens:** Link.create() returns immediately, but link is PENDING until handshake completes
**How to avoid:** Always wait for `link.status == LinkConstants.ACTIVE` before sending
**Warning signs:** Messages silently dropped, sporadic test failures

### Pitfall 2: Python Reticulum Path Discovery

**What goes wrong:** Kotlin can't find path to Python destination
**Why it happens:** Announces haven't propagated, or Transport doesn't know the route
**How to avoid:**
1. Wait for Python to announce after link establishes
2. Use `Transport.has_path(destHash)` to verify path exists
3. Consider direct path injection for tests: `Transport.registerLinkPath()`
**Warning signs:** "No path known" errors, delivery attempts time out

### Pitfall 3: Message Data Format Mismatch

**What goes wrong:** Python fails to unpack Kotlin-sent messages (or vice versa)
**Why it happens:** LXMF wire format has subtle requirements:
- DIRECT delivery includes full packed message (dest_hash + source_hash + signature + payload)
- Strings must be packed as msgpack binary, not string type
- Timestamps are float64 (Python time.time() format)
**How to avoid:**
1. Use existing LXMessage.pack() which handles format correctly
2. Log hex dumps of sent/received data for debugging
3. Compare against Python LXMF's pack() output byte-by-byte
**Warning signs:** "Could not unpack LXMF message" errors

### Pitfall 4: Delivery Receipt Not Firing

**What goes wrong:** Sender's delivery callback never fires
**Why it happens:**
1. Receiver didn't call `packet.prove()`
2. Proof packet didn't route back to sender
3. Receipt timed out before proof arrived
**How to avoid:**
1. Verify receiver calls prove() in packet callback
2. Check link is bidirectional
3. Use reasonable timeouts (30+ seconds for test networks)
**Warning signs:** Messages delivered but sender thinks they failed

### Pitfall 5: Test Process Cleanup

**What goes wrong:** Tests hang or fail on subsequent runs
**Why it happens:** Python subprocess not terminated, ports still bound
**How to avoid:**
```kotlin
@AfterAll
fun cleanup() {
    reticulum?.stop()        // Stop Kotlin Reticulum
    pythonBridge?.close()    // Kill Python process
    // Ports auto-released when process exits
}
```
**Warning signs:** "Address already in use" errors, zombie Python processes

## Code Examples

### Example 1: Starting Reticulum for Tests

```kotlin
// Source: Existing TunnelTestMain.kt pattern
private fun startKotlinReticulum(tcpPort: Int): Pair<Reticulum, TCPClientInterface> {
    val configDir = Files.createTempDirectory("reticulum-test-").toFile()

    Reticulum.start(
        configDir = configDir.absolutePath,
        enableTransport = true
    )

    val tcpClient = TCPClientInterface(
        name = "Test Client",
        targetHost = "127.0.0.1",
        targetPort = tcpPort
    )

    val clientRef = tcpClient.toRef()
    Transport.registerInterface(clientRef)

    tcpClient.onPacketReceived = { data, iface ->
        Transport.inbound(data, iface.toRef())
    }

    tcpClient.start()

    return Pair(Reticulum.getInstance(), tcpClient)
}
```

### Example 2: Python Bridge LXMF Commands (to be added)

```python
# Source: Extension to bridge_server.py
def cmd_lxmf_start_router(params):
    """Start an LXMF router with a delivery destination."""
    global lxmf_router, delivery_identity, delivery_destination

    import RNS
    import LXMF

    # Get or create identity
    identity_hex = params.get('identity_hex')
    if identity_hex:
        identity = RNS.Identity.from_bytes(bytes.fromhex(identity_hex))
    else:
        identity = RNS.Identity()

    # Create router
    storage_path = params.get('storage_path', '/tmp/lxmf_test')
    lxmf_router = LXMF.LXMRouter(identity=identity, storagepath=storage_path)

    # Register delivery destination
    delivery_destination = lxmf_router.register_delivery_identity(identity)
    delivery_identity = identity

    # Track received messages
    received_messages = []
    def on_message(msg):
        received_messages.append(msg)
    lxmf_router.register_delivery_callback(on_message)

    return {
        'identity_hash': identity.hash.hex(),
        'destination_hash': delivery_destination.hash.hex()
    }

def cmd_lxmf_get_received_messages(params):
    """Get list of received messages."""
    return {
        'messages': [
            {
                'hash': msg.hash.hex() if msg.hash else None,
                'source_hash': msg.source_hash.hex(),
                'content': msg.content.decode('utf-8') if isinstance(msg.content, bytes) else msg.content,
                'title': msg.title.decode('utf-8') if isinstance(msg.title, bytes) else msg.title,
            }
            for msg in received_messages
        ]
    }
```

### Example 3: Waiting for Delivery

```kotlin
// Source: Kotlin coroutines pattern
suspend fun waitForDelivery(message: LXMessage, timeout: Duration = 30.seconds): Boolean {
    return withTimeoutOrNull(timeout) {
        while (message.state != MessageState.DELIVERED) {
            if (message.state == MessageState.FAILED ||
                message.state == MessageState.REJECTED) {
                return@withTimeoutOrNull false
            }
            delay(100.milliseconds)
        }
        true
    } ?: false
}
```

### Example 4: Test Fixture Setup

```kotlin
// Source: LXMFInteropTestBase pattern extended for live testing
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DirectDeliveryTestBase {
    protected lateinit var bridge: PythonBridge
    protected lateinit var kotlinRouter: LXMRouter
    protected lateinit var kotlinIdentity: Identity
    protected lateinit var kotlinDestination: Destination

    protected var pythonDestHash: ByteArray? = null

    @BeforeAll
    fun setup() {
        // Start Python bridge with Reticulum
        bridge = PythonBridge.startWithReticulum(getTcpPort())

        // Start Kotlin Reticulum
        startKotlinReticulum(getTcpPort())

        // Create Kotlin LXMF router
        kotlinIdentity = Identity.create()
        kotlinRouter = LXMRouter(identity = kotlinIdentity)
        kotlinDestination = kotlinRouter.registerDeliveryIdentity(kotlinIdentity)
        kotlinRouter.start()

        // Start Python LXMF router
        val result = python("lxmf_start_router")
        pythonDestHash = result.getBytes("destination_hash")

        // Wait for path discovery
        waitForPath(pythonDestHash!!)
    }

    @AfterAll
    fun teardown() {
        kotlinRouter.stop()
        Reticulum.stop()
        bridge.close()
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Mocked interop tests | Live Reticulum instances | This phase | Real E2E verification |
| PythonBridge crypto-only | PythonBridge + Reticulum | This phase | Tests actual networking |
| Thread.sleep() waits | Coroutine-based waits | Current | More reliable tests |

**Current implementation status:**
- LXMRouter DIRECT delivery: Implemented, needs testing
- Link establishment: Implemented, tested for crypto
- Resource transfer: Implemented, needs LXMF testing
- Delivery receipts: Implemented, needs E2E testing

## Open Questions

1. **TCP Port Management in Parallel Tests**
   - What we know: Tests can use ServerSocket(0) for dynamic port allocation
   - What's unclear: How to communicate port to Python subprocess before it starts
   - Recommendation: Pass port as command-line argument to Python script, or use named pipe

2. **Python Reticulum Initialization Timing**
   - What we know: Python Reticulum takes time to initialize interfaces
   - What's unclear: Exact timing needed before Kotlin can connect
   - Recommendation: Have Python print "READY" after TCPServerInterface starts

3. **Link MTU for Large Messages**
   - What we know: Messages > MDU need Resource transfer
   - What's unclear: Whether Kotlin Resource implementation is tested for LXMF
   - Recommendation: Include a large message test (> 500 bytes packed)

## Sources

### Primary (HIGH confidence)

- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt` - Kotlin LXMRouter implementation
- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt` - Kotlin LXMessage implementation
- `./rns-core/src/main/kotlin/network/reticulum/link/Link.kt` - Kotlin Link implementation
- `~/repos/LXMF/LXMF/LXMRouter.py` - Python LXMF reference (lxmf_delivery, direct_links)
- `~/repos/LXMF/LXMF/LXMessage.py` - Python LXMF reference (send, __as_packet)
- `~/repos/Reticulum/RNS/Link.py` - Python Link reference

### Secondary (MEDIUM confidence)

- `./rns-test/src/test/kotlin/network/reticulum/test/TunnelTestMain.kt` - TCP interface usage pattern
- `./python-bridge/bridge_server.py` - Existing bridge command pattern

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Verified against existing codebase
- Architecture: HIGH - Based on existing patterns in codebase
- Pitfalls: HIGH - Derived from code analysis and Python reference

**Research date:** 2026-01-24
**Valid until:** 30 days (stable domain, established patterns)
