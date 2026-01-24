# Phase 5: Stamp Interop - Research

**Researched:** 2026-01-24
**Domain:** LXMF proof-of-work stamp generation, workblock derivation, cross-implementation validation
**Confidence:** HIGH

## Summary

This research investigates LXMF stamp generation and validation to ensure interoperability between Kotlin and Python implementations. LXMF stamps are proof-of-work tokens that require computational effort to generate, used for spam prevention in propagation networks.

The stamp algorithm:
1. **Workblock generation**: HKDF expansion of message ID using 3000 rounds (or 1000 for propagation, 25 for peering), producing ~750KB of derived key material
2. **Stamp search**: Find 32 random bytes where `SHA256(workblock + stamp)` has enough leading zeros to meet the difficulty target
3. **Validation**: Check that `SHA256(workblock + stamp) <= 2^(256-cost)`

The existing Kotlin implementation (`LXStamper.kt`) already matches the Python algorithm, including:
- Workblock generation using HKDF with msgpack-serialized round numbers as salt
- Parallel stamp search using coroutines
- Validation using BigInteger comparison

The Python bridge already has commands for stamp operations: `lxmf_stamp_workblock`, `lxmf_stamp_valid`, `lxmf_stamp_generate`. These enable direct cross-implementation verification.

**Primary recommendation:** Create `StampInteropTest.kt` extending `LXMFInteropTestBase` with bidirectional stamp validation tests. Use low-difficulty stamps (0-8 bits) for fast tests, tag high-difficulty tests (12-16 bits) as slow. Verify workblock generation matches byte-for-byte, then test cross-implementation stamp validity.

## Standard Stack

The established libraries/tools for this domain:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| LXStamper.kt | (project) | Stamp generation and validation | Already implemented, matches Python algorithm |
| PythonBridge | (project) | Cross-language verification | Existing infrastructure with stamp commands |
| Kotest | 5.8.0 | Soft assertions, test annotations | Established in project |
| JUnit 5 | (project) | Test runner with @Tag support | Standard, supports slow test tagging |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-coroutines-test | (project) | Testing suspend functions | Stamp generation is suspend fun |
| java.security.SecureRandom | JDK | Random stamp candidate generation | Non-deterministic stamp search |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Live Python stamp generation | Pre-computed fixtures | Fixtures are static; live tests verify algorithm correctness |
| Full workblock tests | Small expand_rounds | Faster tests but less production-realistic |

## Architecture Patterns

### Recommended Test Structure
```
lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/
├── StampInteropTest.kt           # Main stamp interop tests
```

Tests should be organized into nested classes:
- `WorkblockGeneration` - Verify workblock bytes match between implementations
- `KotlinGeneratesPythonValidates` - Kotlin stamps accepted by Python
- `PythonGeneratesKotlinValidates` - Python stamps accepted by Kotlin
- `DifficultyCalculation` - Stamp value/cost computation matches
- `InvalidStampRejection` - Both implementations reject same invalid stamps
- `IntegrationWithMessage` - Stamps within packed LXMF messages

### Pattern 1: Workblock Byte-Exact Verification
**What:** Verify workblock generation produces identical bytes
**When to use:** Before testing stamps - establishes foundation
**Example:**
```kotlin
// Source: Verified pattern from existing interop tests
@Test
fun `workblock matches Python for same message ID`() {
    val messageId = crypto.randomBytes(32)
    val expandRounds = 2  // Small for fast test

    // Generate in Kotlin
    val kotlinWorkblock = LXStamper.generateWorkblock(messageId, expandRounds)

    // Generate in Python via bridge
    val pythonResult = python(
        "lxmf_stamp_workblock",
        "message_id" to messageId,
        "expand_rounds" to expandRounds
    )
    val pythonWorkblock = pythonResult.getBytes("workblock")

    assertBytesEqual(pythonWorkblock, kotlinWorkblock, "Workblock")
}
```

### Pattern 2: Cross-Implementation Stamp Validation
**What:** Generate stamp in one implementation, validate in the other
**When to use:** All stamp validity tests
**Example:**
```kotlin
// Source: Context decision for bidirectional testing
@Test
fun `Kotlin generated stamp validates in Python`() {
    val messageId = crypto.randomBytes(32)
    val stampCost = 8
    val expandRounds = 25  // Fast for testing

    val workblock = LXStamper.generateWorkblock(messageId, expandRounds)
    val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }

    assertNotNull(result.stamp)
    assertTrue(result.value >= stampCost)

    // Validate in Python
    val pythonResult = python(
        "lxmf_stamp_valid",
        "stamp" to result.stamp!!,
        "target_cost" to stampCost,
        "workblock" to workblock
    )

    pythonResult.getBoolean("valid") shouldBe true
}
```

### Pattern 3: Difficulty Level Testing with Tags
**What:** Test multiple difficulty levels, tag slow tests
**When to use:** Comprehensive difficulty coverage
**Example:**
```kotlin
// Source: Context decision for difficulty levels and tagging
@Nested
@DisplayName("DifficultyLevels")
inner class DifficultyLevels {

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 4, 8])
    fun `low difficulty stamp validates in Python`(cost: Int) {
        // Fast tests for development iteration
        testStampAtDifficulty(cost, expandRounds = 25)
    }

    @Test
    @Tag("slow")
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `high difficulty 12-bit stamp validates`() {
        testStampAtDifficulty(12, expandRounds = 100)
    }

    @Test
    @Tag("slow")
    @Timeout(300, unit = TimeUnit.SECONDS)
    fun `high difficulty 16-bit stamp validates`() {
        testStampAtDifficulty(16, expandRounds = 100)
    }
}
```

### Pattern 4: Invalid Stamp Rejection Testing
**What:** Verify both implementations reject the same invalid stamps
**When to use:** Security testing, edge cases
**Example:**
```kotlin
// Source: Context decision for invalid stamp scenarios
@Nested
@DisplayName("InvalidStampRejection")
inner class InvalidStampRejection {

    @Test
    fun `wrong difficulty rejected by both`() {
        // Generate 4-bit stamp, try to validate as 8-bit
        val workblock = LXStamper.generateWorkblock(messageId, 25)
        val stamp = runBlocking { LXStamper.generateStamp(workblock, 4) }.stamp!!

        // Should fail validation at higher cost
        assertFalse(LXStamper.isStampValid(stamp, 8, workblock))

        val pythonResult = python(
            "lxmf_stamp_valid",
            "stamp" to stamp,
            "target_cost" to 8,
            "workblock" to workblock
        )
        pythonResult.getBoolean("valid") shouldBe false
    }

    @Test
    fun `corrupted stamp rejected by both`() {
        // Generate valid stamp, then corrupt it
        val stamp = validStamp.copyOf()
        stamp[0] = (stamp[0].toInt() xor 0xFF).toByte()

        assertFalse(LXStamper.isStampValid(stamp, stampCost, workblock))
        // Also verify Python rejects
    }

    @Test
    fun `wrong message hash rejected by both`() {
        // Stamp for message A, validate against message B
        val workblockA = LXStamper.generateWorkblock(messageIdA, 25)
        val workblockB = LXStamper.generateWorkblock(messageIdB, 25)
        val stampForA = runBlocking { LXStamper.generateStamp(workblockA, 4) }.stamp!!

        // Should fail against different workblock
        assertFalse(LXStamper.isStampValid(stampForA, 4, workblockB))
    }

    @Test
    fun `truncated stamp rejected by both`() {
        val truncatedStamp = ByteArray(16)  // Should be 32
        assertFalse(LXStamper.validateStamp(truncatedStamp, messageId, 4))
    }

    @Test
    fun `empty stamp rejected by both`() {
        val emptyStamp = ByteArray(0)
        assertFalse(LXStamper.validateStamp(emptyStamp, messageId, 4))
    }
}
```

### Anti-Patterns to Avoid
- **Full 3000-round workblocks in all tests:** Use small expand_rounds (25-100) for speed; 3000 only for integration tests
- **Testing only happy path:** Invalid stamp rejection is critical for security
- **Synchronous stamp generation in UI thread:** Generation is CPU-bound; use coroutines
- **Hardcoded timeouts without @Tag:** Tag slow tests so CI can skip/extend them
- **Testing stamp replay without workblock regeneration:** Workblock is deterministic for same message_id; replay test must use different message

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Workblock generation | Custom HKDF loop | `LXStamper.generateWorkblock()` | Already matches Python exactly |
| Stamp validation | BigInteger comparison | `LXStamper.isStampValid()` | Handles edge cases |
| Leading zeros count | Bit manipulation | `LXStamper.stampValue()` | Already tested against Python |
| HKDF expansion | Custom HMAC loop | `LXStamper.hkdfExpand()` | RFC 5869 compliant |
| Msgpack int encoding | Manual bytes | `LXStamper.packInt()` | Matches Python msgpack.packb() |

**Key insight:** The Kotlin LXStamper implementation is complete and passes unit tests. Phase 5 focuses on cross-implementation verification, not implementing new crypto.

## Common Pitfalls

### Pitfall 1: Workblock Expand Rounds Mismatch
**What goes wrong:** Kotlin uses 3000 rounds, test expects 1000 (or vice versa)
**Why it happens:** Different expand_rounds for different stamp types (LXMF=3000, PN=1000, peering=25)
**How to avoid:** Always explicitly specify expand_rounds in both Kotlin and Python calls
**Warning signs:** Workblock size mismatch, valid stamp fails cross-validation

### Pitfall 2: Stamp Cost vs Stamp Value Confusion
**What goes wrong:** Testing that stamp.value == cost instead of stamp.value >= cost
**Why it happens:** PoW usually finds first valid stamp, which typically exceeds minimum
**How to avoid:** Assert `result.value >= stampCost` not `result.value == stampCost`
**Warning signs:** Sporadic test failures when stamp happens to have exact cost

### Pitfall 3: Test Timeout Without Proper Tagging
**What goes wrong:** High-difficulty tests fail in CI due to timeout
**Why it happens:** 16-bit stamp can take minutes to generate
**How to avoid:** Use `@Tag("slow")` and `@Timeout` annotations; separate slow tests in CI
**Warning signs:** Tests pass locally but fail in CI; intermittent CI failures

### Pitfall 4: Parallel Stamp Generation Race Conditions
**What goes wrong:** Multiple workers find stamps, only one should "win"
**Why it happens:** Kotlin uses AtomicReference for thread-safe result sharing
**How to avoid:** Use existing `generateStamp()` which handles this correctly
**Warning signs:** Multiple stamps returned, null stamps despite valid workblock

### Pitfall 5: HKDF Salt Construction Error
**What goes wrong:** Workblock bytes differ between implementations
**Why it happens:** Salt is `SHA256(material + msgpack(n))`; msgpack encoding must match
**How to avoid:** Use `LXStamper.packInt()` which matches Python's `msgpack.packb()`
**Warning signs:** First workblock bytes correct, later rounds diverge

### Pitfall 6: BigInteger Sign Handling
**What goes wrong:** Negative BigInteger from 256-bit hash bytes
**Why it happens:** Java BigInteger(byte[]) interprets first bit as sign
**How to avoid:** Use `BigInteger(1, hash)` to force positive interpretation
**Warning signs:** Some stamps fail validation despite having enough leading zeros

## Code Examples

Verified patterns from the codebase:

### Python Stamp Workblock Generation (Reference)
```python
# Source: ~/repos/LXMF/LXMF/LXStamper.py lines 20-31
def stamp_workblock(material, expand_rounds=WORKBLOCK_EXPAND_ROUNDS):
    workblock = b""
    for n in range(expand_rounds):
        workblock += RNS.Cryptography.hkdf(
            length=256,
            derive_from=material,
            salt=RNS.Identity.full_hash(material+msgpack.packb(n)),
            context=None
        )
    return workblock
```

### Kotlin Stamp Workblock Generation (Verified Match)
```kotlin
// Source: lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXStamper.kt lines 98-118
fun generateWorkblock(material: ByteArray, expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS): ByteArray {
    val output = ByteArrayOutputStream(expandRounds * HKDF_OUTPUT_LENGTH)

    for (n in 0 until expandRounds) {
        // salt = SHA256(material + msgpack(n))
        val msgpackN = packInt(n)
        val saltInput = material + msgpackN
        val salt = sha256(saltInput)

        // HKDF expand
        val expanded = hkdfExpand(
            ikm = material,
            salt = salt,
            info = ByteArray(0), // context=None
            length = HKDF_OUTPUT_LENGTH
        )
        output.write(expanded)
    }

    return output.toByteArray()
}
```

### Python Stamp Validation (Reference)
```python
# Source: ~/repos/LXMF/LXMF/LXStamper.py lines 44-48
def stamp_valid(stamp, target_cost, workblock):
    target = 0b1 << 256-target_cost
    result = RNS.Identity.full_hash(workblock+stamp)
    if int.from_bytes(result, byteorder="big") > target: return False
    else: return True
```

### Kotlin Stamp Validation (Verified Match)
```kotlin
// Source: lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXStamper.kt lines 232-237
fun isStampValid(stamp: ByteArray, targetCost: Int, workblock: ByteArray): Boolean {
    val target = BigInteger.ONE.shiftLeft(256 - targetCost)
    val hash = sha256(workblock + stamp)
    val result = BigInteger(1, hash) // Positive BigInteger from bytes
    return result <= target
}
```

### Python Bridge Stamp Commands (Available)
```python
# Source: python-bridge/bridge_server.py lines 2787-2837
def cmd_lxmf_stamp_workblock(params):
    """Generate stamp workblock from message ID."""
    message_id = hex_to_bytes(params['message_id'])
    expand_rounds = int(params.get('expand_rounds', 3000))
    workblock = LXStamper.stamp_workblock(message_id, expand_rounds=expand_rounds)
    return {'workblock': bytes_to_hex(workblock), 'size': len(workblock)}

def cmd_lxmf_stamp_valid(params):
    """Validate stamp against target cost and workblock."""
    stamp = hex_to_bytes(params['stamp'])
    target_cost = int(params['target_cost'])
    workblock = hex_to_bytes(params['workblock'])
    valid = LXStamper.stamp_valid(stamp, target_cost, workblock)
    value = LXStamper.stamp_value(workblock, stamp) if valid else 0
    return {'valid': valid, 'value': value}

def cmd_lxmf_stamp_generate(params):
    """Generate stamp meeting target cost."""
    message_id = hex_to_bytes(params['message_id'])
    stamp_cost = int(params['stamp_cost'])
    expand_rounds = int(params.get('expand_rounds', 3000))
    stamp, value = LXStamper.generate_stamp(message_id, stamp_cost, expand_rounds=expand_rounds)
    return {'stamp': bytes_to_hex(stamp) if stamp else None, 'value': value}
```

### Test Pattern: Cross-Implementation Validation
```kotlin
// Source: Established pattern from existing interop tests
@Test
fun `Kotlin stamp validates in Python`() {
    val messageId = crypto.randomBytes(32)
    val stampCost = 4
    val expandRounds = 25

    // Generate workblock (same in both)
    val workblock = LXStamper.generateWorkblock(messageId, expandRounds)

    // Generate stamp in Kotlin
    val result = runBlocking { LXStamper.generateStamp(workblock, stampCost) }
    assertNotNull(result.stamp, "Stamp should not be null")
    assertTrue(result.value >= stampCost, "Stamp value should meet cost")

    // Validate in Python
    val pyResult = python(
        "lxmf_stamp_valid",
        "stamp" to result.stamp!!,
        "target_cost" to stampCost,
        "workblock" to workblock
    )

    pyResult.getBoolean("valid") shouldBe true
    pyResult.getInt("value") shouldBe result.value
}
```

### Test Pattern: Shared Fixtures
```kotlin
// Source: Context decision for fixture-based testing
companion object {
    // Pre-computed valid stamps for deterministic tests
    val FIXTURE_MESSAGE_ID = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef".hexToByteArray()
    val FIXTURE_WORKBLOCK_2_ROUNDS = "6b4e93e1358f5b18...".hexToByteArray()  // First 512 bytes
    val FIXTURE_VALID_STAMP_8_BIT = "52c8508b7f8dfdd984e110d489e3c5535c0583005f1ebb08f63ca7c36c6c5882".hexToByteArray()
    // This stamp has value 11 (11 leading zeros), valid for cost <= 11
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No stamp tests | Unit tests only | LXStamper.kt created | Basic algorithm verification |
| Fixed test vectors | Live Python bridge | Phase 1 | Real cross-implementation testing |
| Single-threaded | Parallel generation | LXStamper implementation | Faster stamp generation |

**Deprecated/outdated:**
- None - LXMF stamp algorithm is stable

## Open Questions

Things that need validation during implementation:

1. **Over-qualified stamps acceptance**
   - What we know: 8-bit stamp should validate at 4-bit cost
   - What's unclear: Whether Python has edge cases with over-qualified stamps
   - Recommendation: Add explicit test per Context decision (Claude's discretion)

2. **Stamp within message packing**
   - What we know: Stamp is appended to payload in `pack()`
   - What's unclear: Exact byte layout when stamp is included
   - Recommendation: Add integration test with `lxmf_unpack` verifying stamp extraction

3. **Nonce/counter iteration behavior**
   - What we know: Kotlin uses SecureRandom for each attempt
   - What's unclear: Whether Python's os.urandom() behavior needs matching
   - Recommendation: Per Context (Claude's discretion) - not critical for interop, both use random search

4. **Timeout values for high difficulty**
   - What we know: 16-bit can take minutes
   - What's unclear: Appropriate CI timeout for 16-bit tests
   - Recommendation: Start with 5 minutes for 16-bit, tag as slow, adjust based on CI behavior

## Sources

### Primary (HIGH confidence)
- `~/repos/LXMF/LXMF/LXStamper.py` - Complete Python reference implementation
- `lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXStamper.kt` - Kotlin implementation
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/LXStamperTest.kt` - Existing unit tests
- `python-bridge/bridge_server.py` lines 2787-2837 - Bridge commands for stamp operations

### Secondary (MEDIUM confidence)
- `.planning/phases/05-stamp-interop/05-CONTEXT.md` - User decisions on difficulty levels and test tagging
- `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/LXMFInteropTestBase.kt` - Base class pattern

### Tertiary (LOW confidence)
- RFC 5869 (HKDF specification) - For understanding key derivation algorithm

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - existing implementation verified, bridge commands available
- Architecture: HIGH - follows established interop test patterns
- Pitfalls: HIGH - derived from code analysis and unit test edge cases

**Research date:** 2026-01-24
**Valid until:** 90 days (stamp algorithm is stable, unlikely to change)
