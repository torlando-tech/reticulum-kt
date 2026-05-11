# reticulum-kt — Documented Deviations from the Python Reference

This file is the **single source of truth** for every place where reticulum-kt's logic intentionally diverges from `markqvist/Reticulum`. Any divergence not listed here is a bug, not a deviation.

## Rule

> All logic in reticulum-kt MUST mirror the python reference identically. Deviations are allowed ONLY for one of two reasons, both of which MUST be documented here before the code lands.

**Allowed reason 1 — Language/runtime forced.** The python pattern cannot be expressed faithfully in kotlin or on the JVM. Examples: coroutines vs threads, `@Volatile` vs the GIL, `ReentrantLock` where python relies on GIL-implicit serialization, `kotlinx.coroutines.runBlocking` boundaries at JVM/non-coroutine seams.

**Allowed reason 2 — New feature not present in python.** Kotlin-only API surface added for downstream consumers (Android lifecycle adapters, mobile-specific entry points, etc.). The kotlin-only behavior must not change semantics of any code path that *does* exist in python.

## Process

1. Before changing a kotlin port file in a way that diverges from the python reference, read the corresponding python source.
2. If the divergence is unavoidable for one of the two reasons above, add a section below using the template, then implement the change.
3. If you're unsure whether a divergence is justified, ask the human owner before picking unilaterally. Ports drift one small "harmless" choice at a time.
4. Reviewers should reject any PR that introduces a kotlin/python semantics divergence not represented in this file.

## Entry template

```markdown
### <short title> — <kotlin-file-relative-path>:<line-or-symbol>

**Python reference:** `<path>:<line>` (e.g. `RNS/Resource.py:560-670`)

**Category:** language/runtime forced  |  new feature

**Date:** YYYY-MM-DD

**Tracking:** issue/PR link, if any.

**Description:** what the kotlin code does, why it differs from python, and (for category 1) why no kotlin idiom can express the python semantics directly.

**Re-evaluation:** if a future kotlin/JVM/library change would make the python pattern expressible, what to look for.
```

---

## Deviations

### Optimistic identity-CAS on pathTable/linkTable updates after `transmit()` — `rns-core/.../Transport.kt::processOutbound`, transport forwarding, link forwarding

**Python reference:** `RNS/Transport.py:134, 136` — python protects `path_table` and `link_table` with dedicated per-table locks (`path_table_lock`, `link_table_lock`) acquired only when the table is read or written, distinct from the main `jobs_lock` that wraps inbound/outbound entry points.

**Category:** language/runtime forced.

**Date:** 2026-04-29.

**Tracking:** reticulum-kt#64 greptile P1 carry-over ("stale pathEntry read-modify-write on pathTable/linkTable after lock re-acquisition in transmit's callers").

**Description:** kotlin's `Transport` uses a single process-wide `jobsLock` covering both inbound and outbound. After `transmit()` was changed to release `jobsLock` around blocking interface I/O (a separate deviation needed for perf — see #65), the post-transmit `pathTable[key] = pathEntry.touch()` and `linkTable[key] = linkEntry.copy(...)` patterns at `processOutbound` lines 2885-2887, 2972-2974, and 3118-3122 became read-modify-write hazards: another thread processing a fresher inbound announce on the same destination could replace the entry during the release window, and the post-transmit blind write would clobber it with a stale-derived value. Rather than introducing per-table locks (which would require restructuring all `pathTable`/`linkTable` access sites — a much larger change), kotlin uses optimistic identity-CAS at the three affected sites: re-read `pathTable[key]`/`linkTable[key]` after `transmit()` returns and only write back if the current entry is still `=== pathEntry` (or `=== linkEntry`). If a fresher entry has replaced ours during the release, we skip our touch and leave their newer state alone. Python's per-table locks achieve the same invariant via stricter exclusion; kotlin's optimistic check achieves it via "no overwrite of fresher state."

**Re-evaluation:** if `Transport`'s state model is ever refactored to per-table locks (matching python's structure exactly), this can be retired. Until then, every new post-transmit `pathTable`/`linkTable` write site needs the same identity-CAS check.

### Watchdog uses Thread + interrupt() instead of flag-check job ID — `rns-core/.../Resource.kt::startWatchdog`, `stopWatchdog`, `watchdogJob`

**Python reference:** `RNS/Resource.py:560-670` (`watchdog_job` / `__watchdog_job_id`). Python spawns a daemon thread that runs a `while self.status < Resource.ASSEMBLING and this_job_id == self.__watchdog_job_id` loop. Stopping a watchdog increments `__watchdog_job_id` so the next loop iteration's compare-and-exit fires; no thread interruption is involved.

**Category:** language/runtime forced.

**Date:** 2026-04-29.

**Tracking:** reticulum-kt#64 greptile P1 ("`cancel()` self-interrupts before `callbacks.failed`").

**Description:** kotlin's watchdog uses `kotlin.concurrent.thread { ... }` + `Thread.sleep` + `Thread.interrupt()` for prompt wake-up rather than the python flag-check-after-sleep pattern. Both achieve the same end (loop exits when active=false), but the kotlin `interrupt()` call introduces a self-targeting hazard not present in python: when `cancel()` is invoked from inside `watchdogJob` itself (the new retries-exhausted branch), `stopWatchdog()` would interrupt the current thread, leaving the interrupt flag set during the subsequent `callbacks.failed?.invoke` and silently aborting any I/O the failed callback performs that uses interruptible primitives (notably `ReentrantLock.lockInterruptibly()` on TCP writes — added for shutdown responsiveness in this same PR). `stopWatchdog` therefore checks `Thread.currentThread() === watchdogThread` and skips the self-interrupt; the loop's `if (!watchdogActive) break` already handles the cooperative exit, matching python's flag-check semantics for this case.

**Re-evaluation:** if kotlin coroutines replace the threaded watchdog (likely a future direction — `processingScope.launch { while (active) { delay(...); ... } }` would compose better with the rest of the rns-core async surface), the entire interrupt mechanism goes away and this deviation can be retired.

### Eager cleanup on `Resource.accept` exception — `rns-core/.../Resource.kt::accept`

**Python reference:** `RNS/Resource.py:223-244`. Python's `Resource.accept` calls `link.register_incoming_resource(resource)` and then `resource.hashmap_update(0, resource.hashmap_raw)` and finally `resource.watchdog_job()`. If `hashmap_update` throws, the outer `try/except` catches it, logs, and returns None — but the resource has already been registered via `register_incoming_resource`, and the watchdog has not yet been started, so the registration leaks for the lifetime of the link with no recovery path.

**Category:** new feature (defensive cleanup beyond the python reference).

**Date:** 2026-04-29.

**Tracking:** reticulum-kt#64 greptile P1 ("zombie blocks hash on requestNext failure").

**Description:** kotlin's `Resource.accept` adds a `resource?.cancel()` in its catch block so a thrown initialization or `requestNext()` cleans up the registration immediately. Without this, a thrown send would leave the advertisement hash blocked in the link's `incomingResources` until either (a) kotlin's watchdog times out — which kotlin's `initializeFromAdvertisement` does start before the throw site, so eventual recovery exists at ~16-20s, OR (b) the link tears down. The eager cleanup brings recovery from "eventually" to "immediately," which matters for the dedup guard's user-visible behavior: a sender retransmit after a brief network glitch is otherwise dropped silently. This is a strict improvement over python's behavior — python in the equivalent path never recovers.

**Re-evaluation:** if/when python adds equivalent cleanup upstream, this entry can be removed and the kotlin code labeled as "matches python" again.

### Explicit write serialization on TCP interfaces — `rns-interfaces/.../TCPClientInterface.kt::processOutgoing`, `TCPServerInterface.kt::processOutgoing`

**Python reference:** `RNS/Interfaces/TCPInterface.py:320-345` (`process_outgoing`). Python sets a `self.writing = True/False` flag but the actual serialization of concurrent writes is implicit: the GIL guarantees atomicity around `socket.sendall`, and the original `while self.writing: time.sleep(0.01)` busy-loop is commented out. Python effectively does not serialize concurrent calls to `process_outgoing`.

**Category:** language/runtime forced.

**Date:** 2026-04-29.

**Tracking:** reticulum-kt#64; symptom history in `TCPServerInterface.kt:365-375` comment ("the old check-then-set on `writing` was racy and interleaved socket writes, corrupting resource transfers (status=CORRUPT / 7)").

**Description:** kotlin/JVM has no GIL, so concurrent calls to `processOutgoing` from different threads (the read loop's reactive sends, link keepalives, resource ACK/request packets, etc.) can interleave bytes mid-frame on the same socket. The original kotlin port translated python's commented-out busy-spin into an active `Thread.sleep(10)` loop guarded by an `AtomicBoolean writing`, which was both racy (check-then-set is non-atomic) and slow (10ms latency floor on contention). The current code uses `ReentrantLock.lockInterruptibly()` (TCPClientInterface) or `synchronized(this)` (TCPServerInterface) to provide the mutual exclusion the GIL gives python for free. `lockInterruptibly()` is preferred over `lock()` because the original `Thread.sleep(10)` would throw `InterruptedException` on shutdown — preserving that interrupt-propagation lets clean teardown work even when a write is contended (greptile P2 finding on PR #64).

**Re-evaluation:** if a future kotlin-on-Loom virtual-thread or kotlinx-coroutines-Mutex pattern offers GIL-equivalent atomicity for socket sends with no per-call lock cost, revisit. As of JDK 21 there is no such mechanism — explicit serialization remains the only correct expression of python's effective serialization.

### `Transport.isConnectedToSharedInstance` flipped at `registerInterface` time — `rns-core/.../Transport.kt::registerInterface`, `deregisterInterface`

**Python reference:** `RNS/Reticulum.py:417` (`self.is_connected_to_shared_instance = True` set inside `__start_local_interface`'s `LocalClientInterface` fallback branch), `RNS/Reticulum.py:425-427` and `433-435` (cleared in failure / standalone paths).

**Category:** new feature.

**Date:** 2026-05-10.

**Tracking:** reticulum-conformance shared-instance test trio (`tests/wire/test_link_via_shared_master.py`).

**Description:** in python, `is_connected_to_shared_instance` lives on the Reticulum singleton and is set exactly once inside `__start_local_interface` after a `LocalClientInterface` is successfully constructed (or cleared on the exception / standalone paths). Python apps never construct a `LocalClientInterface` directly — the only entry point is `RNS.Reticulum(...)`, which routes through `__start_local_interface`, so pinning the flag there covers every reachable code path.

The kotlin port exposes a wider API surface: callers can construct a `LocalClientInterface` themselves and register it with `Transport.registerInterface(...)` (Carina's `ReticulumService`, Eridanus' shared-instance attach in `viewmodel/EridanusViewModel.kt`, and the conformance wire bridge's `wire_start_local_client` all do this). Those paths bypass `Reticulum.tryConnectToSharedInstance` — the kotlin analog of python's `__start_local_interface` — and so the global `Transport.isConnectedToSharedInstance` flag stayed `false` even though the process was, in fact, connected to a shared instance via the just-registered interface.

The downstream consequence was a transport-mode bug: with the flag false, the outbound branch at `Transport.kt::processOutbound` line ~3088 (`pathEntry.hops == 1 && isConnectedToSharedInstance && isHeader1 && !isLink`) never fired, so outbound LINKREQUESTs went onto the local-client interface as HEADER_1 instead of HEADER_2-with-master's-identity-as-`transport_id`. The master was then forced to compensate with an H1→H2 raw mutation that broke the `packet.raw ↔ packet.headerType` invariant (see the separate "H1→H2 master-side mutation removed" entry below), producing divergent `link_id` values and dropping every LRPROOF that came back. Conformance test that catches the symptom: `reticulum-conformance/tests/wire/test_link_via_shared_master.py`.

To restore the python invariant ("the flag is true iff we're a shared-instance client, regardless of how the interface was created"), the kotlin port flips the flag inside `registerInterface` when the registered interface reports `isConnectedToSharedInstance` (and clears it inside `deregisterInterface` when the last such interface goes away — symmetric for apps that toggle between hosting and consuming the shared instance, e.g. Carina). This widens the python setup point (singleton init) into a kotlin lifecycle invariant tied to interface registration; semantically equivalent, mechanically forced by the kotlin API surface allowing manual construction.

**Concurrency note (JVM memory model, category (a)):** because the kotlin port flips this flag at runtime from `registerInterface` / `deregisterInterface` — both of which can run on arbitrary threads, unlike python's single-threaded `Reticulum.__init__` setup — the flag-flip blocks are guarded by a private `sharedInstanceFlagLock` monitor, and the `var` itself is marked `@Volatile`. This closes a check-then-act TOCTOU on `interfaces.none { ... }` inside `deregisterInterface` (whose surrounding `interfaces.remove(...)` on a `CopyOnWriteArrayList` is individually atomic but compound-unsafe), and gives concurrent readers — e.g. `processOutbound`'s `hops == 1 && isConnectedToSharedInstance && isHeader1` branch — a happens-before guarantee on flag writes. Python doesn't need either mechanism (GIL + init-only setpoint); the kotlin additions are pure language/runtime adaptations, not behavioral divergences.

**Re-evaluation:** if the kotlin port were ever restricted to only setting up `LocalClientInterface` instances via `Reticulum.tryConnectToSharedInstance` (matching python's single entry point exactly), the `registerInterface` widening could be retired. Until then, every new manual `LocalClientInterface` construction site in downstream apps depends on this widening for the global flag to track reality.

### H1→H2 master-side mutation removed — `rns-core/.../Transport.kt::processInbound`

**Python reference:** `RNS/Transport.py:1488-1489` only — python's master-side shared-instance handling sets `packet.transport_id` (the field) on inbound packets that target a local client (`if packet.transport_id == None and for_local_client`), and never mutates `packet.raw`. Python has no equivalent for the "packet FROM a local client TO a remote destination with no `transport_id`" case because python clients always pack `HEADER_2` outbound with the master's identity as `transport_id` (`RNS/Transport.py:1097-1108`), so the master always sees `packet.transport_id != None` and routes normally.

**Category:** N/A — this is the removal of a previous deviation, restoring python parity. Documented here for the historical record so future readers don't reintroduce it.

**Date:** 2026-05-10.

**Tracking:** reticulum-conformance shared-instance test trio.

**Description:** an earlier version of `processInbound` contained a kotlin-only compensation block that, on receiving a `HEADER_1` packet from a local client destined for a remote, mutated `packet.raw` in place to upgrade it to `HEADER_2` with the master's identity inserted at offset 2, and set `packet.transportId` to the same hash. This was a workaround for the bug documented in the previous entry (manually-registered `LocalClientInterface` not flipping `Transport.isConnectedToSharedInstance`, so kotlin clients sent `HEADER_1` outbound where python clients send `HEADER_2`). The compensation broke the `packet.raw ↔ packet.headerType` invariant relied on by `Packet.getHashablePart()` — `headerType` is `val`, set at unpack, and stayed `HEADER_1` after the raw mutation; `getHashablePart()` then sliced the new `HEADER_2` raw using the cached `HEADER_1` layout, accidentally including the just-inserted `transport_id` in the hash input, producing a `link_id` that diverged from what the originator and destination computed. The LRPROOF returning from the destination hashed to the originator's `link_id`, but the master's `link_table` was keyed under the bogus mutated-hashable hash, and the proof was silently dropped.

With the `registerInterface` widening above, kotlin clients now pack `HEADER_2` outbound on the same code path as python, so the master receives `packet.transportId != null` and no compensation is required. The compensation block is removed; the master's processing now mirrors python's exactly.

**Re-evaluation:** the removal is a strict re-convergence with the python reference — there's nothing to re-evaluate unless a future kotlin downstream consumer reintroduces a code path that bypasses both `Reticulum.tryConnectToSharedInstance` and `Transport.registerInterface`'s flag widening (which would be a separate bug to fix at the new bypass site, not here).
