# Phase 2: LXMF Message Round-Trip - Research

**Researched:** 2026-01-23
**Domain:** LXMF message serialization, Python-Kotlin interoperability testing
**Confidence:** HIGH

## Summary

This research investigates the LXMF message format and testing patterns needed for bidirectional round-trip verification between Kotlin and Python implementations. The Python reference implementation (LXMessage.py) defines the wire format as: destination hash (16 bytes) + source hash (16 bytes) + Ed25519 signature (64 bytes) + msgpack payload. The msgpack payload contains a 4-5 element list: [timestamp (float64), title (bytes), content (bytes), fields (dict), stamp (optional bytes)].

The existing codebase already has a robust Python bridge (`bridge_server.py`) with dedicated LXMF commands (`lxmf_pack`, `lxmf_unpack`, `lxmf_hash`) that work with raw hashes instead of Destination objects. The Kotlin `LXMessage` class follows the same wire format and has existing interop tests using static test vectors. For Phase 2, we need to extend this to bidirectional round-trip testing: Kotlin creates message -> Python unpacks and validates -> compare all fields.

**Primary recommendation:** Use the existing `InteropTestBase` pattern with `PythonBridge`, organize tests as `KotlinToPythonTest` and `PythonToKotlinTest` classes, leverage Kotest's `assertSoftly` for collecting all failures before reporting.

## Standard Stack

The established libraries/tools for this domain:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| org.msgpack:msgpack-core | (project) | MessagePack serialization | Direct API control for byte-perfect packing |
| Kotest | 5.8.0 | Assertions and soft assertions | Already in project, supports `assertSoftly` |
| JUnit 5 | (project) | Test runner | Standard, integrates with Kotest |
| PythonBridge | (project) | Python interop | Existing infrastructure, JSON over stdin/stdout |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-serialization-json | (project) | JSON parsing for bridge responses | Bridge communication |
| kotlin.test | stdlib | Basic assertions | Simple equality checks |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Manual JSON parsing | Full JSON library | Current approach works, minimal overhead |
| External test vectors | Live bridge calls | Bridge is more flexible, tests real Python |

## Architecture Patterns

### Recommended Test Structure
```
lxmf-core/src/test/kotlin/network/reticulum/lxmf/
├── interop/
│   ├── LXMFInteropTestBase.kt        # Shared fixtures, bridge setup
│   ├── KotlinToPythonMessageTest.kt  # Kotlin-created messages verified in Python
│   └── PythonToKotlinMessageTest.kt  # Python-created messages verified in Kotlin
```

### Pattern 1: InteropTestBase Extension
**What:** Base class providing bridge connection and utility methods for LXMF testing
**When to use:** All LXMF round-trip tests
**Example:**
```kotlin
// Source: rns-test/src/test/kotlin/network/reticulum/interop/InteropTestBase.kt
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LXMFInteropTestBase : InteropTestBase() {

    protected lateinit var testSourceIdentity: Identity
    protected lateinit var testDestIdentity: Identity
    protected lateinit var sourceDestination: Destination
    protected lateinit var destDestination: Destination

    @BeforeAll
    fun setupIdentities() {
        testSourceIdentity = Identity.create()
        testDestIdentity = Identity.create()
        sourceDestination = Destination.create(
            identity = testSourceIdentity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        destDestination = Destination.create(
            identity = testDestIdentity,
            direction = DestinationDirection.OUT,
            type = DestinationType.SINGLE,
            appName = "lxmf",
            "delivery"
        )
        // Remember source for signature validation
        Identity.remember(
            packetHash = ByteArray(32),
            destHash = sourceDestination.hash,
            publicKey = testSourceIdentity.getPublicKey()
        )
    }
}
```

### Pattern 2: Soft Assertions with Field-by-Field Comparison
**What:** Collect all comparison failures before reporting
**When to use:** Round-trip validation where multiple fields need checking
**Example:**
```kotlin
// Source: https://kotest.io/docs/assertions/soft-assertions.html
assertSoftly {
    pythonTimestamp shouldBe kotlinMessage.timestamp
    pythonTitle shouldBe kotlinMessage.title
    pythonContent shouldBe kotlinMessage.content
    pythonDestHash shouldBe kotlinMessage.destinationHash.toHex()
    pythonSourceHash shouldBe kotlinMessage.sourceHash.toHex()
    pythonMessageHash shouldBe kotlinMessage.hash!!.toHex()
}
```

### Pattern 3: Bridge Command with Timing
**What:** Log serialization/deserialization timing for performance insight
**When to use:** All bridge operations per context requirements
**Example:**
```kotlin
fun verifyInPython(lxmfBytes: ByteArray, expectedFields: MessageFields): PythonVerificationResult {
    val startTime = System.currentTimeMillis()
    val result = python(
        "lxmf_unpack",
        "lxmf_bytes" to lxmfBytes.toHex()
    )
    val elapsed = System.currentTimeMillis() - startTime
    println("Python unpack took ${elapsed}ms for ${lxmfBytes.size} bytes")
    return PythonVerificationResult(result, elapsed)
}
```

### Anti-Patterns to Avoid
- **Testing without signature validation:** Always ensure source identity is remembered so signatures validate
- **Fixed timestamps:** Per context, use current time; don't hardcode test timestamps
- **Skipping byte equality:** Field comparison is for diagnostics; final truth is byte-perfect equality
- **Multiple test variations per scenario:** Per context, one representative case per scenario

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Hex encoding | Custom conversion | `toHex()` / `hexToByteArray()` from PythonBridge.kt | Existing, tested utilities |
| JSON parsing | Manual string manipulation | kotlinx-serialization-json | Already used by bridge |
| Byte comparison diff | Simple != check | `assertBytesEqual` from InteropTestBase | Shows first 10 differences |
| Bridge lifecycle | Manual process management | `InteropTestBase` with `@BeforeAll/@AfterAll` | Handles startup, cleanup |

**Key insight:** The existing interop infrastructure handles all bridge communication, byte encoding, and error reporting. Focus on LXMF-specific test logic, not infrastructure.

## Common Pitfalls

### Pitfall 1: Timestamp Precision Mismatch
**What goes wrong:** Python `time.time()` returns Float with ~15 significant digits; msgpack stores as IEEE 754 float64
**Why it happens:** Float comparison uses exact equality but representation may differ by ULP
**How to avoid:** Both implementations store as float64 in msgpack; compare with tolerance or use byte equality of packed form
**Warning signs:** Timestamps differ by tiny amount (1e-15) when comparing unpacked values

### Pitfall 2: Title/Content String vs Bytes Confusion
**What goes wrong:** LXMF stores title/content as bytes (UTF-8 encoded), not strings
**Why it happens:** Python unpacker returns bytes, Kotlin converts to String immediately
**How to avoid:** Compare UTF-8 byte representation when checking wire format equality
**Warning signs:** Unicode content round-trips correctly but byte comparison fails

### Pitfall 3: Fields Map Integer Keys as Strings
**What goes wrong:** JSON only supports string keys; Python bridge returns `{"3": value}` not `{3: value}`
**Why it happens:** JSON serialization limitation in bridge protocol
**How to avoid:** Convert string keys to int when parsing bridge response: `{int(k): v for k, v in fields.items()}`
**Warning signs:** Field lookup fails or returns null for known field IDs

### Pitfall 4: Signature Validation Without Remembered Identity
**What goes wrong:** `signatureValidated` returns false even for valid messages
**Why it happens:** `Identity.recall(sourceHash)` returns null if identity not remembered
**How to avoid:** Always call `Identity.remember()` with source identity before unpacking
**Warning signs:** `unverifiedReason = SOURCE_UNKNOWN` on correctly signed messages

### Pitfall 5: Message Hash Includes Stamp
**What goes wrong:** Hash computed with stamp differs from hash computed without stamp
**Why it happens:** Python extracts stamp from payload before computing hash; must do same in Kotlin
**How to avoid:** Both implementations already handle this correctly; verify by checking existing code
**Warning signs:** Message hash mismatch even when other fields match

## Code Examples

Verified patterns from the codebase:

### LXMF Wire Format (from LXMessage.py)
```python
# Source: ~/repos/LXMF/LXMF/LXMessage.py lines 386-392
self.packed      = b""
self.packed     += self.__destination.hash
self.packed     += self.__source.hash
self.packed     += self.signature
self.packed     += packed_payload
```

### Bridge LXMF Unpack Command
```python
# Source: python-bridge/bridge_server.py cmd_lxmf_unpack
def cmd_lxmf_unpack(params):
    lxmf_bytes = hex_to_bytes(params['lxmf_bytes'])
    DEST_LEN = 16
    SIG_LEN = 64

    destination_hash = lxmf_bytes[:DEST_LEN]
    source_hash = lxmf_bytes[DEST_LEN:2*DEST_LEN]
    signature = lxmf_bytes[2*DEST_LEN:2*DEST_LEN+SIG_LEN]
    packed_payload = lxmf_bytes[2*DEST_LEN+SIG_LEN:]

    unpacked_payload = umsgpack.unpackb(packed_payload)
    # ... extract stamp if present, recompute hash ...
```

### Kotest Soft Assertions
```kotlin
// Source: https://kotest.io/docs/assertions/soft-assertions.html
assertSoftly {
    kotlinMessage.destinationHash.toHex() shouldBe pythonResult.getString("destination_hash")
    kotlinMessage.sourceHash.toHex() shouldBe pythonResult.getString("source_hash")
    kotlinMessage.hash!!.toHex() shouldBe pythonResult.getString("message_hash")
    kotlinMessage.title shouldBe pythonResult.getString("title")
    kotlinMessage.content shouldBe pythonResult.getString("content")
}
```

### Creating Test Message in Kotlin
```kotlin
// Source: lxmf-core/src/test/kotlin/network/reticulum/lxmf/LXMessageTest.kt
val message = LXMessage.create(
    destination = destDestination,
    source = sourceDestination,
    content = "Hello, World!",
    title = "Test Message",
    fields = mutableMapOf(
        LXMFConstants.FIELD_RENDERER to LXMFConstants.RENDERER_PLAIN
    ),
    desiredMethod = DeliveryMethod.DIRECT
)
val packed = message.pack()
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Static test vectors (JSON file) | Live Python bridge | Phase 1 | Real-time validation, no stale vectors |
| Separate hash computation | Bridge `lxmf_hash` command | Phase 1 | Python-authoritative hash computation |

**Deprecated/outdated:**
- `lxmf_test_vectors.json` approach: Still works but bridge is more flexible and current

## Open Questions

Things requiring investigation during implementation:

1. **Timestamp comparison tolerance**
   - What we know: Both use IEEE 754 float64 via msgpack
   - What's unclear: Whether byte-perfect representation is guaranteed
   - Recommendation: Test with exact equality first; add tolerance only if needed

2. **Title field usage in LXMF**
   - What we know: Python LXMessage has `title` field, defaults to empty string
   - What's unclear: Whether title is commonly used or vestigial
   - Recommendation: Include title in tests with both empty and non-empty values

3. **Fields dictionary serialization order**
   - What we know: Msgpack dicts have deterministic order in Python 3.7+
   - What's unclear: Whether Kotlin msgpack guarantees same order
   - Recommendation: Compare fields as Map, not byte-for-byte in fields section

## Sources

### Primary (HIGH confidence)
- `~/repos/LXMF/LXMF/LXMessage.py` - Reference implementation, pack/unpack logic
- `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt` - Kotlin implementation
- `python-bridge/bridge_server.py` - LXMF bridge commands (cmd_lxmf_pack, cmd_lxmf_unpack)
- `rns-test/src/test/kotlin/network/reticulum/interop/InteropTestBase.kt` - Test patterns
- [Kotest Soft Assertions](https://kotest.io/docs/assertions/soft-assertions.html) - assertSoftly documentation

### Secondary (MEDIUM confidence)
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/LXMessageTest.kt` - Existing test patterns
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/LXMessageInteropTest.kt` - Static vector approach
- [MessagePack Spec](https://github.com/msgpack/msgpack/blob/master/spec.md) - float64 format details

### Tertiary (LOW confidence)
- Web search on msgpack timestamp precision (verified against spec)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - existing tools already in project
- Architecture: HIGH - follows established patterns from rns-test
- Pitfalls: HIGH - derived from code analysis and Python reference

**Research date:** 2026-01-23
**Valid until:** 60 days (LXMF format is stable, reference implementation unlikely to change)
