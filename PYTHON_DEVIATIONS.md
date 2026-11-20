# Python Reference Implementation Deviations

This document tracks intentional behavioral differences between the
Kotlin (reticulum-kt) implementation and the Python reference (RNS).
Each entry explains **what** differs, **why**, and the **risk**.

Python is always the ground truth. Deviations documented here are
temporary workarounds or conscious trade-offs, not design choices.

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

## AutoInterface: Adaptive Announce Interval

**Python behavior** (`AutoInterface.py:announce_handler`):
Sends multicast discovery announcements every 1.6 seconds continuously
with no adaptation. This interval is hardcoded and never changes.

**Kotlin behavior** (`AutoInterface.kt:startAnnouncementLoop`):
The announce interval adapts based on peer activity and device state:

| State | Announce Interval | vs Python (1.6s) |
|-------|------------------|------------------|
| Startup / peer change | 1.6s (+ immediate reply) | Same |
| Stable, 30s | ~60s | 37x less |
| Stable, 60s+ | 2 minutes | 75x less |
| Doze, stable | 10 minutes | 375x less |
| New peer during Doze | Immediate reply → 1.6s | Same responsiveness |

The interval linearly ramps from 1.6s to `maxAnnounceIntervalMs *
throttleMultiplier` over 60 seconds after the last peer topology
change. Any peer add/remove resets to fast mode AND triggers an
immediate announce so the new peer discovers us within ~1 second.

Doze awareness: `NativeReticulumProtocol` observes `DozeStateObserver`
and sets `AutoInterface.throttleMultiplier` to 5.0 during Doze,
scaling the max interval from 2 minutes to 10 minutes.

**Why**: Continuous 1.6s multicast is the single biggest battery drain
on the native stack. Receiving other nodes' multicast is free (blocking
`socket.receive()`), but sending wakes the WiFi radio every 1.6s
indefinitely. Since peer discovery is symmetric, other nodes sending
at their rate still discover us via receiving — we only need to send
often enough that they discover us within a reasonable time.

**Multicast socket cleanup**: Kotlin explicitly calls `leaveGroup()`
before closing multicast sockets on detach, preventing OS-level
multicast group membership from lingering after the interface is
stopped. Python relies on `socket.close()` for implicit cleanup.

**Risk**: Low. A node running Kotlin at 2-minute intervals next to a
node running Python at 1.6s will still discover each other: the Python
node's multicast arrives within 1.6s, and the Kotlin node's immediate
reply fires within ~1 second. After discovery, unicast data flow is
unaffected.

**Resolution**: This is an intentional improvement, not a workaround.
`throttleMultiplier` can be set to 1.0 and `maxAnnounceIntervalMs`
can be set to `ANNOUNCE_INTERVAL_MS` to match exact Python behavior
if needed.

---

## Python Bridge: Ratchet Lookup Without a Reticulum Instance

**Python behavior**:
`Identity.get_ratchet()` is cache-first at Reticulum 1.5.5
(`RNS/Identity.py:486/504`): it serves `Identity.known_ratchets` and,
on a cache miss, returns `None` when no ratchet file exists. At 1.6.0
the cache-first lookup is unchanged (`:619`), but the store-miss branch
(`:636`) calls
`RNS.Reticulum.get_instance().is_connected_to_shared_instance`, which
raises `AttributeError: 'NoneType' object has no attribute
'is_connected_to_shared_instance'` when no `Reticulum()` instance has
been constructed.

**Bridge behavior** (`python-bridge/bridge_server.py:_safe_get_ratchet`):
The interop bridge delegates to `Identity.get_ratchet()` unchanged, so
remembered ratchets are still served without an instance (the reference
cache is consulted first and never touches `get_instance()`). Only the
`AttributeError` from the missing-instance branch is translated into
`None`, and only while `RNS.Reticulum.get_instance()` is still `None`;
any other `AttributeError` is re-raised, so a defect inside a running
stack still surfaces instead of being reported as "no ratchet known".

**Why**: The bridge legitimately runs crypto/interop commands
(`destination_encrypt_debug`, `check_known_ratchet`,
`check_ratchet_for_dest`, `propagation_encrypt_for_recipient`) before any
`Reticulum()` exists, and Reticulum 1.6.0 moved the reference behavior
underneath them. Reporting "no ratchet known" keeps the identity-key
path the conformance/interop tests pin. The bridge deliberately does not
read the private `Identity.known_ratchets` cache itself, so 1.6.0's
reshaped store semantics stay upstream-owned.

**Risk**: Low, and scoped to the bridge. On Reticulum 1.5.5 the branch
is unreachable (a cache miss returns `None`). On 1.6.0 the bridge reports
"unknown" where upstream would raise; once a `Reticulum()` instance
exists, the delegation is byte-for-byte upstream. The Kotlin
implementation is unaffected — this is not a stack deviation.

---

## Transport: Platform Hashlist Budget and Load-Time Trim

**Python behavior** (`Transport.py:247`, `Transport.py:831-834`,
`Transport.py:339-348`):
`hashlist_maxsize` is a flat 1,000,000 on every platform. The job loop rotates
`packet_hashlist` into `packet_hashlist_prev` once
`len(packet_hashlist) > hashlist_maxsize // 2`, so two windows of at most
500,001 entries (about 1,000,001 live) are retained. A persisted
`packet_hashlist.raw` is loaded verbatim, with no size cap.

**Kotlin behavior** (`Transport.kt:addPacketHash`, `Transport.kt:trimMemory`,
`Transport.kt:loadPacketHashlist`, `Transport.kt:addCapped`,
`PacketHashStore.loadCapped`):
The budget is read as a TOTAL, exactly like the reference: rotation runs at
`budget // 2`, so both windows together stay near one budget. The budget source
differs — `Platform.recommendedHashlistSize` is 1,000,000 on the JVM but 50,000
on Android, so an Android device retains at most ~25,001 entries per window
instead of 500,001. Loading a persisted hashlist retains at most `budget / 2`
entries per generation while loading: the file branch streams lines and stops at
one window, and the `PacketHashStore` branch asks for a bounded load
(`loadCapped`, a SQL `LIMIT` on Android) and copies at most one window out of
what the store returns. A hashlist written by a pre-fix build against the flat
constant can therefore not keep a multi-million-entry set alive, nor even be
materialised in full.

**Why**: columba#1222 was a `ConcurrentHashMap$KeySetView.addAll` OOM inside
`addPacketHash` on a 201 MB Android heap; a 500k-entry dedup window plus the
rotation's copy filled it well before the flat 1M budget would have. Android
needs the smaller budget to keep dedup working at all. The load-time cap closes
the same hole for data persisted by the earlier build.

**Risk**: Low. Dedup only needs to cover the packet lifetime of in-flight
traffic; a smaller window can miss a duplicate that arrives more than a rotation
later, which the reference also accepts at its own window boundary. The load cap
drops arbitrary entries (`ConcurrentHashMap` iteration order for the store path,
file order for the streaming path), so a duplicate from before a restart may be
reprocessed — the same outcome as losing the persisted hashlist entirely, which
`PacketHashStore`'s contract already accepts.

**Resolution**: None planned. The Android budget is intentional; revisit only if
a larger window becomes safe on modern Android heaps.

---

## Conformance Test Status

78 of 79 conformance tests pass (only `bz2_compress` differs due to
library version producing different but equally valid compressed output).

All 4 ratchet lifecycle tests pass, including:
- Announce with ratchet pack/unpack
- Ratchet extraction from announce
- Full lifecycle encrypt/decrypt
- Cross-implementation encrypt/decrypt
