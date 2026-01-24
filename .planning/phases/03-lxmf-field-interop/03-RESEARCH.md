# Phase 3: LXMF Field Interop - Research

**Researched:** 2026-01-23
**Domain:** LXMF field types (attachments, images, custom fields), msgpack nested structures, Python-Kotlin interoperability
**Confidence:** HIGH

## Summary

This research investigates the LXMF field type formats and testing patterns needed for verifying that file attachments, images, and custom fields survive Kotlin-Python round-trips with byte-level accuracy. The Python reference implementation (LXMF.py and Sideband) defines specific structures for these fields:

- **FIELD_FILE_ATTACHMENTS (0x05)**: List of tuples `[(filename, binary_data), ...]`
- **FIELD_IMAGE (0x06)**: Tuple `(extension, binary_data)` e.g., `("webp", b'\x52\x49...')`
- **FIELD_RENDERER (0x0F)**: Integer value (0x00=PLAIN, 0x01=MICRON, 0x02=MARKDOWN, 0x03=BBCODE)
- **FIELD_THREAD (0x08)**: Thread ID for message threading

The existing Phase 2 infrastructure (LXMFInteropTestBase, PythonBridge, lxmf_pack/lxmf_unpack commands) already handles basic fields. Phase 3 extends this to verify complex nested structures with binary data survive round-trips. The Kotlin `LXMessage.packValue()` and `unpackValue()` methods already support lists, maps, and byte arrays recursively.

**Primary recommendation:** Extend Python bridge to generate test fixtures with attachments/images, then verify Kotlin correctly unpacks and repacks these structures with byte-level equivalence. Use WebP images (user requirement), realistic file sizes (1KB-100KB), and 2-3 attachments per message.

## Standard Stack

The established libraries/tools for this domain:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| org.msgpack:msgpack-core | (project) | MessagePack serialization with nested list/map support | Already handles recursive packing/unpacking |
| Kotest | 5.8.0 | Soft assertions for multi-field comparison | Already in project |
| PythonBridge | (project) | Python interop with hex-encoded binary transport | Proven in Phase 2 |
| LXMFInteropTestBase | (project) | Shared test fixtures for LXMF testing | Established pattern |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| java.security.MessageDigest | JDK | SHA-256 checksums for large file verification | Files > 1KB |
| kotlinx-serialization-json | (project) | JSON parsing for bridge responses | Bridge communication |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hex-encoded binary in JSON | Base64 encoding | Hex is simpler, already used in bridge |
| Generated test images | Pre-made image files | Generated gives more control over exact bytes |

## Architecture Patterns

### Recommended Test Structure
```
lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/
├── LXMFInteropTestBase.kt           # Shared fixtures (existing)
├── KotlinToPythonMessageTest.kt     # Basic messages (Phase 2)
├── PythonToKotlinMessageTest.kt     # Basic messages (Phase 2)
├── AttachmentFieldInteropTest.kt    # FIELD_FILE_ATTACHMENTS tests (Phase 3)
├── ImageFieldInteropTest.kt         # FIELD_IMAGE tests (Phase 3)
└── CustomFieldInteropTest.kt        # Custom fields + edge cases (Phase 3)
```

### Pattern 1: Python Fixture Generation
**What:** Python generates messages with complex fields, Kotlin unpacks and verifies
**When to use:** Testing Kotlin can correctly parse Python-generated field structures
**Example:**
```kotlin
// Generate attachment message in Python
val packedBytes = createAttachmentMessageInPython(
    attachments = listOf(
        AttachmentFixture("test.txt", "Hello, World!".toByteArray()),
        AttachmentFixture("data.bin", ByteArray(1024) { it.toByte() })
    )
)

// Unpack in Kotlin
val message = LXMessage.unpackFromBytes(packedBytes)

// Verify attachments field structure
val attachments = message.fields[LXMFConstants.FIELD_FILE_ATTACHMENTS] as List<*>
assertSoftly {
    attachments.size shouldBe 2
    (attachments[0] as List<*>)[0] shouldBe "test.txt".toByteArray()
    // ... verify byte content
}
```

### Pattern 2: Kotlin-to-Python Round-Trip Verification
**What:** Kotlin creates message with fields, Python unpacks, compare field values
**When to use:** Proving Kotlin-generated messages are compatible with Python
**Example:**
```kotlin
// Create message with image field in Kotlin
val imageBytes = generateMinimalWebP()
val message = createTestMessage(
    content = "Image test",
    fields = mutableMapOf(
        LXMFConstants.FIELD_IMAGE to listOf("webp".toByteArray(), imageBytes)
    )
)
val packed = message.pack()

// Verify in Python
val pythonResult = verifyInPython(packed)
val pythonImage = extractImageField(pythonResult)

assertSoftly {
    pythonImage.extension shouldBe "webp"
    pythonImage.data.toHex() shouldBe imageBytes.toHex()
}
```

### Pattern 3: Field Structure as Msgpack List (Not Tuple)
**What:** LXMF fields use msgpack arrays (lists) since msgpack has no tuple type
**When to use:** All field structures - attachments, images, etc.
**Example:**
```kotlin
// FIELD_IMAGE structure: [extension_bytes, image_bytes]
val imageField = listOf(
    "webp".toByteArray(Charsets.UTF_8),  // Extension as bytes
    imageBytes                            // Binary image data
)
fields[LXMFConstants.FIELD_IMAGE] = imageField

// FIELD_FILE_ATTACHMENTS structure: [[filename_bytes, content_bytes], ...]
val attachmentsField = listOf(
    listOf("file1.txt".toByteArray(), content1Bytes),
    listOf("file2.pdf".toByteArray(), content2Bytes)
)
fields[LXMFConstants.FIELD_FILE_ATTACHMENTS] = attachmentsField
```

### Anti-Patterns to Avoid
- **Assuming tuple vs list distinction:** Msgpack only has arrays; Python tuples become lists
- **String keys in fields dict:** Field keys must be integers, not strings
- **Storing filenames as strings:** LXMF stores filenames as UTF-8 byte arrays
- **Large test files:** Keep test files under 100KB to avoid slow tests

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Minimal WebP generation | Image manipulation library | Static 1x1 WebP bytes | WebP header is complex; use known-good bytes |
| Checksum comparison | Manual byte loop | `MessageDigest.getInstance("SHA-256")` | Proven, efficient |
| Hex encoding | Custom conversion | `ByteArray.toHex()` from PythonBridge.kt | Already tested |
| Bridge field serialization | Manual JSON | `Map<String, Any>` serialization in PythonBridge | Already handles nested structures |

**Key insight:** The Python bridge already serializes nested maps/lists to JSON. For binary data in fields, hex-encode the bytes and have Python decode them.

## Common Pitfalls

### Pitfall 1: Msgpack List vs Python Tuple
**What goes wrong:** Expecting tuple type preservation across Python-Kotlin boundary
**Why it happens:** Msgpack has no tuple type; Python tuples become msgpack arrays, which Kotlin deserializes as lists
**How to avoid:** Always treat field structures as lists in Kotlin; don't expect tuple semantics
**Warning signs:** Type cast errors when accessing field values

### Pitfall 2: Filename Encoding (String vs Bytes)
**What goes wrong:** Storing filenames as strings instead of UTF-8 byte arrays
**Why it happens:** Natural to use String type in Kotlin; LXMF expects bytes
**How to avoid:** Always encode filenames: `"file.txt".toByteArray(Charsets.UTF_8)`
**Warning signs:** Python sees different bytes or decoding errors

### Pitfall 3: Binary Data in JSON Bridge
**What goes wrong:** Raw bytes can't be JSON-serialized directly
**Why it happens:** JSON is text-based; binary data needs encoding
**How to avoid:** Hex-encode all binary values before sending to bridge; decode on Python side
**Warning signs:** JSON parse errors or "invalid escape" errors

### Pitfall 4: Field Value Type After Unpacking
**What goes wrong:** Assuming Int when msgpack returns Long
**Why it happens:** Msgpack deserializes integers as Long by default
**How to avoid:** Use `when` expression to handle both: `when (value) { is Int -> ..., is Long -> ... }`
**Warning signs:** ClassCastException on field access

### Pitfall 5: Empty Fields Dictionary Handling
**What goes wrong:** Null vs empty map confusion
**Why it happens:** Python `{}` vs `None` for fields parameter
**How to avoid:** Test both explicitly empty `{}` and Python None; ensure Kotlin handles both
**Warning signs:** NullPointerException or unexpected empty fields

### Pitfall 6: Attachment Order Preservation
**What goes wrong:** Attachments reordered between pack/unpack
**Why it happens:** Using non-ordered collections
**How to avoid:** Use List (ordered) not Set; verify order in tests
**Warning signs:** Attachments present but in wrong order

## Code Examples

Verified patterns from the codebase and Sideband:

### FIELD_IMAGE Structure (from Sideband analysis)
```python
# Source: Sideband ui/messages.py analysis
# Image field is a tuple/list: (extension, binary_data)
image_field = ("webp", image_bytes)
message.fields[LXMF.FIELD_IMAGE] = image_field
```

### FIELD_FILE_ATTACHMENTS Structure (from Sideband analysis)
```python
# Source: Sideband ui/messages.py analysis
# Attachments field is a list of tuples: [(filename, data), ...]
attachments = [
    ("document.pdf", pdf_bytes),
    ("image.jpg", jpg_bytes)
]
message.fields[LXMF.FIELD_FILE_ATTACHMENTS] = attachments
```

### Kotlin packValue for Nested Structures (existing)
```kotlin
// Source: lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt
private fun packValue(packer: MessagePacker, value: Any) {
    when (value) {
        is ByteArray -> {
            packer.packBinaryHeader(value.size)
            packer.writePayload(value)
        }
        is List<*> -> {
            packer.packArrayHeader(value.size)
            for (item in value) {
                if (item != null) packValue(packer, item)
                else packer.packNil()
            }
        }
        is Map<*, *> -> {
            packer.packMapHeader(value.size)
            for ((k, v) in value) {
                if (k != null) packValue(packer, k)
                if (v != null) packValue(packer, v)
            }
        }
        // ... other types
    }
}
```

### Minimal WebP Image (for testing)
```kotlin
// Minimal 1x1 pixel WebP image (smallest valid WebP)
// Source: WebP specification - RIFF container with VP8L chunk
val MINIMAL_WEBP = byteArrayOf(
    // RIFF header
    0x52, 0x49, 0x46, 0x46,  // "RIFF"
    0x1a, 0x00, 0x00, 0x00,  // File size - 8
    0x57, 0x45, 0x42, 0x50,  // "WEBP"
    // VP8L chunk (lossless)
    0x56, 0x50, 0x38, 0x4c,  // "VP8L"
    0x0d, 0x00, 0x00, 0x00,  // Chunk size
    0x2f, 0x00, 0x00, 0x00,  // Signature
    0x00, 0x00, 0x00, 0x00,  // 1x1 image data
    0x00, 0x00, 0x00, 0x00,
    0x00
).also { require(it.size == 34) }
```

### Bridge Extension for Field Verification
```python
# To add to bridge_server.py for field testing
def cmd_lxmf_unpack_with_fields(params):
    """Unpack LXMF message and deeply inspect fields."""
    result = cmd_lxmf_unpack(params)

    # Convert field values to hex for binary data
    fields = result.get('fields', {})
    hex_fields = {}
    for key, value in fields.items():
        hex_fields[str(key)] = serialize_field_value(value)

    result['fields_hex'] = hex_fields
    return result

def serialize_field_value(value):
    if isinstance(value, bytes):
        return {'type': 'bytes', 'hex': value.hex()}
    elif isinstance(value, (list, tuple)):
        return {'type': 'list', 'items': [serialize_field_value(v) for v in value]}
    elif isinstance(value, dict):
        return {'type': 'dict', 'items': {str(k): serialize_field_value(v) for k, v in value.items()}}
    else:
        return {'type': type(value).__name__, 'value': value}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Static test vectors | Live Python bridge | Phase 1-2 | Real-time validation |
| String field keys in JSON | Integer keys with conversion | Phase 2 | Proper LXMF compliance |

**Deprecated/outdated:**
- None for this phase; builds on Phase 2 patterns

## Open Questions

Things that couldn't be fully resolved:

1. **MIME type in attachments**
   - What we know: Sideband analysis shows `(filename, data)` format
   - What's unclear: Whether MIME type is sometimes included as third element
   - Recommendation: Test with 2-element tuples first; add MIME type test if Python includes it

2. **Maximum attachment count**
   - What we know: User decision says "follow Python's limits"
   - What's unclear: What limits Python actually enforces
   - Recommendation: Test with 1, 2, and 3 attachments; discover any errors empirically

3. **Empty attachment list handling**
   - What we know: Should match Python behavior
   - What's unclear: Whether Python accepts `[]` vs omitting field entirely
   - Recommendation: Test both explicitly; document Python's behavior

4. **Unicode filenames edge cases**
   - What we know: User wants one unicode filename test
   - What's unclear: Which unicode characters Python handles
   - Recommendation: Test with common emoji + CJK characters; not exhaustive

## Sources

### Primary (HIGH confidence)
- `~/repos/LXMF/LXMF/LXMF.py` - Field constant definitions (0x05, 0x06, etc.)
- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMFConstants.kt` - Kotlin field constants
- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt` - packValue/unpackValue logic
- `./lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/` - Phase 2 test patterns
- `./python-bridge/bridge_server.py` - lxmf_pack/lxmf_unpack commands

### Secondary (MEDIUM confidence)
- Sideband GitHub repository - Field structure analysis via WebFetch
- [LXMF README](https://github.com/markqvist/LXMF/blob/master/README.md) - Protocol overview
- WebP specification - Minimal image format

### Tertiary (LOW confidence)
- Web search on LXMF field formats (confirmed with primary sources)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - all tools already in project and proven in Phase 2
- Architecture: HIGH - extends established Phase 2 patterns
- Field formats: HIGH - verified against Python reference and Sideband
- Pitfalls: HIGH - derived from Phase 2 experience and code analysis

**Research date:** 2026-01-23
**Valid until:** 60 days (LXMF format is stable)
