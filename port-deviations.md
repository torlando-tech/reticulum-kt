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
