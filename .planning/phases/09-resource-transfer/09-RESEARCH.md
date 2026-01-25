# Phase 9: Resource Transfer - Research

**Researched:** 2026-01-24
**Domain:** LXMF Resource Transfer / Large Message Delivery
**Confidence:** HIGH

## Summary

This phase focuses on ensuring large LXMF messages (exceeding 319 bytes of content) transfer correctly as Resources between Kotlin and Python implementations. The Kotlin implementation already has a comprehensive Resource class (`Resource.kt`) that handles chunking, compression, hashmap management, and proof verification. The LXMF layer (`LXMRouter.kt`) already integrates with Resources for both outbound and inbound delivery.

The key work is **interoperability verification** and **threshold testing**, not new implementation. The existing Resource subsystem is functionally complete, but live cross-implementation testing with large messages has not been performed. The critical threshold is 319 bytes (LINK_PACKET_MAX_CONTENT = Link.MDU - LXMF_OVERHEAD = 431 - 112 = 319).

**Primary recommendation:** Create live cross-implementation tests sending messages of 320+ bytes to verify Resource transfer works bidirectionally between Kotlin and Python over TCP.

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Apache Commons Compress | 1.24.0 | BZ2 compression/decompression | Standard library, matches Python bz2 module |
| MessagePack | 0.9.x | Binary serialization for advertisements | Already used throughout codebase |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Kotest | 5.x | Assertions and matchers | Test assertions |
| JUnit 5 | 5.10.x | Test framework | Integration tests |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Apache Commons BZ2 | jbzip2 | Apache Commons is more widely tested and maintained |
| Manual chunking | StreamingResource | Streaming would add complexity, not needed for <10KB tests |

## Architecture Patterns

### Existing Project Structure

```
rns-core/src/main/kotlin/network/reticulum/
├── resource/
│   ├── Resource.kt              # Full Resource implementation (1300+ LOC)
│   ├── ResourceAdvertisement.kt # Advertisement pack/unpack
│   └── ResourceConstants.kt     # Protocol constants
└── link/
    └── Link.kt                  # Resource integration via callbacks

lxmf-core/src/main/kotlin/network/reticulum/lxmf/
├── LXMRouter.kt                 # Outbound/inbound Resource handling
├── LXMessage.kt                 # Representation detection (PACKET vs RESOURCE)
└── LXMFConstants.kt             # LINK_PACKET_MAX_CONTENT = 319

lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/
├── DirectDeliveryTestBase.kt    # TCP infrastructure for live tests
├── LiveDeliveryTest.kt          # K<->P delivery tests (small messages)
└── PropagatedDeliveryTest.kt    # Propagated delivery tests
```

### Pattern 1: Message Size-Based Representation Selection

**What:** LXMF automatically selects PACKET or RESOURCE representation based on packed message size.
**When to use:** Every outbound LXMF message.
**Source:** `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt:213-229`

```kotlin
// In LXMessage.determineDeliveryMethod()
DeliveryMethod.DIRECT -> {
    method = DeliveryMethod.DIRECT
    representation = if (contentSize <= LXMFConstants.LINK_PACKET_MAX_CONTENT) {
        MessageRepresentation.PACKET
    } else {
        MessageRepresentation.RESOURCE  // Triggers Resource transfer
    }
}
```

### Pattern 2: Resource Transfer via Link Callbacks

**What:** LXMRouter uses Link's resource callbacks for inbound/outbound Resource handling.
**When to use:** Any message requiring Resource transfer.
**Source:** `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt:735-751`

```kotlin
// Register for incoming Resources on Link
link.setResourceStrategy(Link.ACCEPT_APP)
link.setResourceCallback { _: ResourceAdvertisement ->
    // Accept LXMF resources
}
link.setResourceConcludedCallback { resource: Any ->
    handleResourceConcluded(resource, link)
}
```

### Pattern 3: BZ2 Compression (Python Compatible)

**What:** Resource uses BZ2 compression matching Python's bz2 module.
**When to use:** Automatic for data <= 64MB.
**Source:** `./rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt:1246-1268`

```kotlin
// Compression using Apache Commons BZ2
private fun compress(data: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    BZip2CompressorOutputStream(output).use { bz2 ->
        bz2.write(data)
    }
    return output.toByteArray()
}
```

### Anti-Patterns to Avoid

- **Assuming 500-byte threshold:** The actual threshold is 319 bytes (LINK_PACKET_MAX_CONTENT), not 500.
- **Testing only one direction:** Resource transfer must work K->P AND P->K.
- **Ignoring compression flag:** Resources may or may not be compressed based on whether compression reduces size.

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| BZ2 compression | Custom bz2 wrapper | Apache Commons Compress | Edge cases with block sizes, headers |
| Part hashmap | Manual hash concatenation | Resource.buildHashmap() | Collision detection, segment handling |
| Proof calculation | Manual SHA256 | Hashes.fullHash() | Must match Python's RNS.Identity.full_hash() |
| Resource chunking | Custom SDU splitting | Resource.initializeForSending() | Encryption overhead, random prefix handling |

**Key insight:** The Resource subsystem is already fully implemented in Kotlin. This phase is about verification, not implementation.

## Common Pitfalls

### Pitfall 1: Content Size vs Packed Size

**What goes wrong:** Testing with 319-byte content but message actually exceeds threshold due to LXMF overhead.
**Why it happens:** LXMF adds 112 bytes overhead (dest + source + signature + timestamp + structure).
**How to avoid:** Test with calculated packed size, not just content size. Use `message.pack().size` for actual size.
**Warning signs:** Message uses PACKET when expecting RESOURCE, or vice versa.

### Pitfall 2: Threshold Off-By-One

**What goes wrong:** Message exactly at threshold (319 bytes) behaves unexpectedly.
**Why it happens:** Boundary condition confusion between < and <=.
**How to avoid:** Test 318, 319, and 320 bytes content explicitly. Verify Python uses same comparison.
**Warning signs:** Inconsistent behavior at exact threshold.

### Pitfall 3: Compression Determinism

**What goes wrong:** Kotlin compressed bytes don't match Python compressed bytes.
**Why it happens:** BZ2 compression can vary by implementation (block size, flush behavior).
**How to avoid:** Verify decompression works bidirectionally; don't require byte-identical compressed output.
**Warning signs:** Python can't decompress Kotlin BZ2, or vice versa.

### Pitfall 4: Resource Proof Format

**What goes wrong:** Proof validation fails even though data transferred correctly.
**Why it happens:** Proof = SHA256(uncompressed_data_with_metadata + resource_hash)[:16], must use data BEFORE metadata stripping.
**How to avoid:** Follow exact Python proof calculation order from Resource.py line 744.
**Warning signs:** "Proof validation failed: mismatch" errors.

### Pitfall 5: Progress Callback Threading

**What goes wrong:** Progress callbacks block the transfer or cause race conditions.
**Why it happens:** Callbacks may fire on network thread.
**How to avoid:** Keep progress callbacks lightweight, copy data needed before callback.
**Warning signs:** Transfer hangs during progress updates, ConcurrentModificationException.

## Code Examples

### Verified Large Message Creation

```kotlin
// Create message that will use Resource transfer
// Content must exceed 319 bytes after LXMF overhead consideration
// 320 bytes content + 112 bytes overhead = 432 bytes packed > 431 (Link.MDU)
val largeContent = "X".repeat(400)  // Well over threshold

val message = LXMessage.create(
    destination = pythonDest,
    source = kotlinDestination,
    content = largeContent,
    title = "Large Message Test",
    desiredMethod = DeliveryMethod.DIRECT
)

// Pack to trigger representation detection
message.pack()

// Verify Resource representation selected
assertEquals(MessageRepresentation.RESOURCE, message.representation)
```

### Sending Resource via LXMRouter

```kotlin
// Already implemented in LXMRouter.sendDirectMessage()
// Source: ./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt:968

val resource = Resource.create(
    data = packed,
    link = deliveryLink,
    callback = { completedResource ->
        pendingResources.remove(messageHashHex)
        message.state = MessageState.DELIVERED
        message.deliveryCallback?.invoke(message)
    },
    progressCallback = { progressResource ->
        message.progress = progressResource.progress.toDouble()
    }
)
```

### Threshold Boundary Test

```kotlin
// Test exact threshold boundary
// LINK_PACKET_MAX_CONTENT = 319, LXMF_OVERHEAD = 112
// Total packed threshold = 319 + 112 = 431 (Link.MDU)

val thresholdContent = "X".repeat(319)  // Exactly at threshold
val overThresholdContent = "X".repeat(320)  // One byte over

// At threshold: PACKET
val atThreshold = LXMessage.create(dest, source, thresholdContent).apply { pack() }
assertEquals(MessageRepresentation.PACKET, atThreshold.representation)

// Over threshold: RESOURCE
val overThreshold = LXMessage.create(dest, source, overThresholdContent).apply { pack() }
assertEquals(MessageRepresentation.RESOURCE, overThreshold.representation)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual Resource testing | Python bridge interop tests | Phase 4+ | Automated verification |
| Packet-only delivery | Full Resource support in LXMRouter | Phase 6 | Large message capability |

**Deprecated/outdated:**
- None identified; Resource protocol is stable in Reticulum.

## Open Questions

### 1. Compression Byte-Identity

**What we know:** Both Kotlin (Apache Commons) and Python (bz2 module) use BZ2 compression.
**What's unclear:** Whether compressed bytes are identical for same input. BZ2 has multiple compression levels.
**Recommendation:** Test decompression interoperability rather than byte-identity. If Python can decompress Kotlin BZ2, that's sufficient.

### 2. Progress Callback Frequency

**What we know:** Python fires progress callbacks per-segment (window of parts).
**What's unclear:** Exact callback timing guarantees and whether Kotlin matches Python exactly.
**Recommendation:** Verify callbacks fire in order and cover full transfer, but don't assert exact timing.

### 3. Resource Transfer Timeout in Propagation

**What we know:** Phase 8.1 identified that propagated messages progress to SENDING but may timeout.
**What's unclear:** Whether this affects Resource transfers specifically or is a higher-layer LXMF issue.
**Recommendation:** Focus on DIRECT delivery Resource tests first; propagated Resource testing deferred to later phase.

## Sources

### Primary (HIGH confidence)

- `~/repos/Reticulum/RNS/Resource.py` - Python reference implementation (1200+ lines)
- `~/repos/LXMF/LXMF/LXMessage.py` - LXMF message representation logic (lines 83-89, 421-432)
- `./rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt` - Kotlin implementation
- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMRouter.kt` - LXMF Resource integration

### Secondary (MEDIUM confidence)

- `./rns-test/src/test/kotlin/network/reticulum/interop/resource/ResourceInteropTest.kt` - Existing interop tests
- `./rns-test/src/test/kotlin/network/reticulum/interop/resource/ResourcePartInteropTest.kt` - Part/hashmap interop tests

### Tertiary (LOW confidence)

- Phase 8.1 findings about propagation timeout (may or may not apply to Resource layer)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - BZ2 compression is well-established, already implemented
- Architecture: HIGH - Existing Resource subsystem is comprehensive and matches Python
- Pitfalls: HIGH - Based on code analysis and Python reference

**Research date:** 2026-01-24
**Valid until:** 60 days (Reticulum Resource protocol is stable)

---

## Key Findings Summary

1. **Threshold is 319 bytes**, not 500 bytes. LINK_PACKET_MAX_CONTENT = Link.MDU (431) - LXMF_OVERHEAD (112) = 319.

2. **Resource subsystem is already implemented** in Kotlin. This phase is about live interop verification, not new code.

3. **Critical test cases:**
   - 318 bytes content (PACKET)
   - 319 bytes content (PACKET, at threshold)
   - 320 bytes content (RESOURCE, over threshold)
   - ~2KB content (multi-part Resource)
   - ~10KB content (multi-segment Resource)

4. **Bidirectional testing required:** K->P and P->K Resource transfers must both work.

5. **BZ2 decompression interop > byte-identity:** Focus on verifying either side can decompress the other's BZ2 output.

6. **Progress callbacks matter:** CONTEXT.md specified per-segment callbacks; verify they fire in correct order.

7. **Existing test infrastructure:** DirectDeliveryTestBase provides TCP K<->P connection; extend LiveDeliveryTest pattern for Resource tests.
