---
phase: 04-lxmf-cryptographic-interop
plan: 01
subsystem: lxmf-interop
tags: [hash, sha256, interop, testing]

requires:
  - 03-lxmf-field-interop (LXMFInteropTestBase, PythonBridge)

provides:
  - MessageHashInteropTest for hash verification
  - Verification that Kotlin and Python compute identical message hashes

affects:
  - 04-02 (signature verification builds on hash verification)

tech-stack:
  added: []
  patterns:
    - Nested test classes for test organization
    - verifyInPythonWithFields for fields with bytes

key-files:
  created:
    - lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/MessageHashInteropTest.kt
  modified: []

decisions:
  - Use lxmf_unpack_with_fields for messages with fields to handle bytes serialization

metrics:
  duration: 3 min
  completed: 2026-01-24
---

# Phase 04 Plan 01: Message Hash Interoperability Summary

Verify LXMF message hash computation is identical between Kotlin and Python implementations.

## One-liner

MessageHashInteropTest with 8 test cases verifying hash computation matches Python for all edge cases.

## What Was Built

### MessageHashInteropTest.kt (273 lines)

Comprehensive test class with 3 nested classes and 8 tests:

**BasicHashComputation:**
- `message hash matches Python for simple message` - Basic text content hash verification
- `message hash matches Python for message with title` - Title + content hash verification

**HashIdempotency:**
- `hash is idempotent after pack-unpack round-trip` - Same hash across multiple pack() calls
- `Kotlin hash computation matches Python lxmf_hash command` - Independent hash computation verification

**EdgeCases:**
- `empty content produces valid hash` - Empty string content
- `empty title and content produces valid hash` - Both empty
- `Unicode content produces matching hash` - Emoji and special chars
- `message with fields produces matching hash` - Fields don't break hash

## Key Verification Points

1. **Hash algorithm**: SHA256(destination_hash + source_hash + packed_payload)
2. **Packed payload**: msgpack([timestamp, title_bytes, content_bytes, fields])
3. **Title/content encoding**: UTF-8 as binary (not string) in msgpack
4. **Fields impact**: Fields are part of payload, affect hash

## Technical Notes

- Used `lxmf_unpack_with_fields` for messages with fields to handle bytes serialization to JSON
- The `lxmf_hash` Python command allows verifying hash from components independently
- Hash is computed during `pack()` and cached

## Deviations from Plan

None - plan executed exactly as written. Task 2 (lxmf_hash verification) was incorporated into Task 1 as part of HashIdempotency nested class.

## Next Phase Readiness

- Hash verification complete, foundation for signature tests
- Same test patterns can be applied to signature verification
- All 8 tests passing
