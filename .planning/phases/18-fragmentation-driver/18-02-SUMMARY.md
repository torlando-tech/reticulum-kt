---
phase: 18
plan: 02
subsystem: ble-fragmentation
tags: [ble, fragmentation, reassembly, wire-format, tdd]
depends_on:
  requires: [18-01]
  provides: [BLEFragmenter, BLEReassembler, ReassemblyStatistics]
  affects: [19, 20, 21]
tech-stack:
  added: []
  patterns: [ByteBuffer big-endian packing, synchronized reassembly buffers, TDD red-green-refactor]
key-files:
  created:
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEFragmentation.kt
    - rns-interfaces/src/test/kotlin/network/reticulum/interfaces/ble/BLEFragmentationTest.kt
  modified: []
decisions:
  - id: 18-02-01
    decision: "Constants delegated to BLEConstants companion object"
    rationale: "Single source of truth; BLEFragmenter.TYPE_START etc. are public API aliases"
  - id: 18-02-02
    decision: "Throw on MTU < 6 instead of clamping to 20 like Python"
    rationale: "Kotlin enforces minimum at construction time; invalid MTU is a programming error"
  - id: 18-02-03
    decision: "Synchronized methods instead of coroutine-based locking"
    rationale: "Reassembler may be called from BLE GATT callbacks which aren't coroutines; @Synchronized is simpler"
metrics:
  duration: 4min
  completed: 2026-02-06
  tests: 44
  lines-production: 314
  lines-test: 754
---

# Phase 18 Plan 02: BLE Fragmentation and Reassembly Summary

**BLEFragmenter splits packets into fragments with 5-byte big-endian headers; BLEReassembler handles ordered, out-of-order, and concurrent multi-sender reassembly with timeout cleanup -- wire-format byte-identical to Python BLEFragmentation.py.**

## What Was Built

### BLEFragmenter
- `fragment(packet: ByteArray): List<ByteArray>` -- splits packet into BLE-sized fragments
- Each fragment: `[type:1][seq:2][total:2][payload:variable]`, all big-endian
- Fragment types: START (0x01), CONTINUE (0x02), END (0x03)
- Single-fragment packets use START with seq=0, total=1
- `fragmentOverhead()` returns fragment count, overhead bytes, and overhead percentage
- Throws on empty packet, MTU < 6, or packet requiring >65535 fragments

### BLEReassembler
- `receiveFragment(fragment, senderId)` -- returns complete packet when all fragments received
- Handles out-of-order arrival via sequence-indexed buffer
- Multiple concurrent senders with separate buffers per sender ID
- Duplicate detection: benign (same data) silently ignored, conflicting (different data) throws and discards buffer
- Total consistency validation across fragments from same sender
- `cleanupStale()` removes timed-out incomplete buffers
- `statistics` property provides ReassemblyStatistics snapshot
- Thread-safe via `@Synchronized` on all public methods

### Wire Format Verification
Header encoding verified byte-identical to Python `struct.pack("!BHH", type, seq, total)`:
| Input | Expected bytes |
|-------|---------------|
| START, seq=0, total=1 | `01 00 00 00 01` |
| START, seq=0, total=3 | `01 00 00 00 03` |
| CONTINUE, seq=1, total=3 | `02 00 01 00 03` |
| END, seq=2, total=3 | `03 00 02 00 03` |

## Test Coverage (44 tests)

- **Fragmenter** (14 tests): single/multi fragment, headers, sequence numbers, total count, overhead, edge cases, wire format
- **Reassembler** (14 tests): single/multi fragment, out-of-order, multiple senders, timeout, statistics, error handling, duplicates
- **Round-trip** (8 tests): various MTU sizes (20-512), large packets, binary data, reverse order delivery
- **Wire format** (6 tests): byte-level header verification against Python reference
- **Edge cases** (2 tests): minimum MTU, default sender ID

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] MTU validation logic**
- **Found during:** GREEN phase
- **Issue:** Initial implementation used `maxOf(mtu, 6)` clamping (ported from Python's `max(mtu, 20)`), which prevented MTU=5 from throwing
- **Fix:** Changed to `require(mtu >= HEADER_SIZE + 1)` to throw on invalid MTU rather than silently clamping
- **Files modified:** BLEFragmentation.kt
- **Commit:** f4db8b2

## Decisions Made

1. **Constants delegation** -- BLEFragmenter.companion delegates to BLEConstants (from plan 18-01) as single source of truth. Public API is `BLEFragmenter.TYPE_START` etc. for test readability.

2. **Strict MTU validation** -- Throws IllegalArgumentException on MTU < 6 instead of Python's approach of clamping to 20. Kotlin convention: invalid construction parameters are programming errors.

3. **@Synchronized over coroutine locks** -- BLE GATT callbacks run on binder threads, not coroutines. @Synchronized is simpler and avoids requiring CoroutineScope.

## Next Phase Readiness

Phase 19 (GATT Server) can now use BLEFragmenter in the TX path (fragment outgoing packets) and BLEReassembler in the RX path (reassemble incoming fragments). The fragment protocol is fully tested and wire-compatible with Python peers.
