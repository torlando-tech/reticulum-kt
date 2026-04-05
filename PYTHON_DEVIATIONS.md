# Python Reference Implementation Deviations

This document tracks intentional behavioral differences between the
Kotlin (reticulum-kt) implementation and the Python reference (RNS).
Each entry explains **what** differs, **why**, and the **risk**.

Python is always the ground truth. Deviations documented here are
temporary workarounds or conscious trade-offs, not design choices.

---

## Transport: Ingress-Limited Announces Processed Locally

**Python behavior** (`Transport.py:328-331`):
When `should_ingress_limit()` is true for an unknown destination,
Python calls `interface.hold_announce(packet)` and returns immediately.
The announce is not processed locally. Later, `process_held_announces()`
re-injects held announces when the burst subsides.

**Kotlin behavior** (`Transport.kt:processAnnounce`):
When ingress-limited, Kotlin processes the announce locally (learns
the path, stores identity, notifies handlers) but skips retransmission
to other interfaces. The announce is also held for later re-injection,
but `processHeldAnnounces()` is a no-op stub.

**Why**: Without a working `processHeldAnnounces`, the Python approach
causes paths to never be learned for new destinations discovered during
high-traffic periods. This breaks link establishment, NomadNet browsing,
and any feature that depends on `Identity.recall()` or `Transport.hasPath()`.

**Risk**: Low. The local processing is strictly additive — we learn
a path that Python would eventually learn when held announces are
re-injected. The only difference is timing (immediate vs deferred)
and that we don't retransmit the announce to other interfaces.

**Resolution**: Implement `processHeldAnnounces()` to match Python,
then revert to Python's hold-and-return behavior.

---

## Transport: Stale Path Entry Removal on Outbound

**Python behavior** (`Transport.py:load_path_table`):
When loading persisted paths on startup, Python resolves the stored
interface hash to a live interface object. If `find_interface_from_hash()`
returns `None`, the path entry is simply not loaded (line 105:
"The interface is no longer available").

**Kotlin behavior** (`Transport.kt:processOutbound`):
Kotlin loads all persisted paths regardless of interface availability.
During outbound routing, if `findInterfaceByHash()` returns null for
a path entry, Kotlin removes the stale entry from the path table at
that point and falls through to broadcast.

**Why**: Kotlin's path persistence loads paths before all interfaces
are registered (AutoInterface/discovered interfaces register later).
Filtering at load time would discard valid paths for interfaces that
haven't connected yet.

**Risk**: Low. The end result is the same — stale paths are eventually
removed. The timing differs (Python: at load; Kotlin: at first use).

**Resolution**: Add a deferred path validation pass after all interfaces
are registered, matching Python's load-time filtering.

---

## Transport: processHeldAnnounces Not Implemented

**Python behavior**: `Interface.process_held_announces()` is called
periodically by the Transport jobs loop. It re-injects held announces
when the ingress rate drops below the threshold.

**Kotlin behavior**: `processHeldAnnounces()` is a no-op stub.
Held announces are never re-injected.

**Why**: The ingress-limited-local-processing deviation above mitigates
the impact for local functionality. Full implementation requires porting
the interface-level held announce queue and the periodic job timer.

**Risk**: Medium. Announces that should be retransmitted to other
interfaces after the burst subsides are lost. This affects announce
propagation across the network but not local path/identity learning.

**Resolution**: Port `Interface.process_held_announces()` from Python.

---

## LXMRouter: Ratchet Self-Storage Removed from generateAnnounceData

**Python behavior** (`Destination.py:announce()`):
Python does NOT store the ratchet public key under the local
destination's hash during announce generation. The ratchet public
key is only stored by REMOTE nodes that receive the announce.

**Kotlin behavior (before fix)**:
`generateAnnounceData()` called `setRatchetForDestination(hash, ratchetPub)`
and `Identity.rememberRatchet(hash, ratchetPub)`, storing the ratchet
public key under the local destination hash. This confused the
`Destination.encrypt()` path because `Identity.get_ratchet(destHash)`
would find a public key for a LOCAL destination and use it instead
of the identity's base key.

**Kotlin behavior (after fix)**: Matches Python — removed self-storage.

**Risk**: None. This was a bug fix, not a deviation.

---

## Conformance Test Status

78 of 79 conformance tests pass (only `bz2_compress` differs due to
library version producing different but equally valid compressed output).

All 4 ratchet lifecycle tests pass, including:
- Announce with ratchet pack/unpack
- Ratchet extraction from announce
- Full lifecycle encrypt/decrypt
- Cross-implementation encrypt/decrypt
