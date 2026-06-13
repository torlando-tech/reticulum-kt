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

### Pre-start factory setters (LocalClient / LocalServer / InterfaceRegistrar) — `rns-core/.../Reticulum.kt::setLocalClientFactory`, `setLocalServerFactory`, `setInterfaceRegistrar`, `clearPendingFactories`

**Python reference:** `RNS/Reticulum.py:373-444` (`__start_local_interface`). Python's `Reticulum.__init__` instantiates `LocalServerInterface` / `LocalClientInterface` directly — there's no factory setter, no registrar callback, no `clear*` method. The whole construct+register sequence lives inside one class because python has no module/package decoupling forcing a callback indirection.

**Category:** language/runtime forced.

**Date:** 2026-05-11.

**Tracking:** reticulum-kt#68 (production registrar wiring fix), reticulum-kt#70 (conformance-bridge auto_attach mode that exercises the same factory/registrar path in CI), greptile P2 thread on #70 ("Global factory/registrar not cleared after auto_attach setup").

**Description:** reticulum-kt is split into separate gradle modules — `rns-core` (which owns `Reticulum`, `Transport`) and `rns-interfaces` (which owns `LocalClientInterface`, `LocalServerInterface`, the `InterfaceAdapter` that wires `onPacketReceived` to `Transport.inbound`). To avoid a circular dependency, `rns-core` cannot directly reference the interface types it needs to instantiate inside `tryConnectToSharedInstance` / `startLocalServer`. Instead, downstream callers (`rns-android.ReticulumService`, `rns-cli.PipePeer`, the conformance bridge's auto_attach path) install pre-start factory lambdas (`pendingLocalClientFactory`, `pendingLocalServerFactory`, `pendingInterfaceRegistrar`) on the `Reticulum` companion; `start()` promotes them onto the constructed instance before `initialize()` runs, and the connect/share paths invoke them via `Any`-typed callbacks. Python has no analog because rns is a single python package with no comparable boundary.

`clearPendingFactories()` was added 2026-05-11 alongside the conformance-bridge auto_attach mode (PR #70). Because the setters store lambdas on the `Reticulum` companion (process-wide static state), and `Reticulum.stop()` only nulls the `instance` and runs `shutdown()` without touching the pre-start slots, a stale factory + registrar pair could survive across `stop()` / `start()` cycles — including capturing references to the prior session's `LocalClientInterface` instances inside closures, holding them alive in memory even after the conformance bridge thought it had reset. `clearPendingFactories()` lets test harnesses (and any future caller that wants a clean baseline) explicitly drop those slots. Not folded into `stop()` itself because the documented "set once before start" idiom is used by `ReticulumService.initializeReticulum` and `PipePeer.main`; clearing on every stop would silently break the next start unless those callers were also updated. An explicit clear method preserves backward compat while making the hygiene fix available where it matters (the conformance bridge's `resetWireState`).

**Re-evaluation:** if `rns-core` and `rns-interfaces` are ever consolidated into a single module — or if `rns-core` is taught to reference interface types directly via a stable abstract base — the factory setter / registrar machinery becomes unnecessary and `Reticulum.__init__`-equivalent direct construction (matching python exactly) becomes viable. Until then, every new caller that uses shared-instance mode needs the setter pair, and `clearPendingFactories()` should be called from any test scaffold that re-enters `Reticulum.start()` with different topology assumptions.

### H1→H2 master-side mutation removed — `rns-core/.../Transport.kt::processInbound`

**Python reference:** `RNS/Transport.py:1488-1489` only — python's master-side shared-instance handling sets `packet.transport_id` (the field) on inbound packets that target a local client (`if packet.transport_id == None and for_local_client`), and never mutates `packet.raw`. Python has no equivalent for the "packet FROM a local client TO a remote destination with no `transport_id`" case because python clients always pack `HEADER_2` outbound with the master's identity as `transport_id` (`RNS/Transport.py:1097-1108`), so the master always sees `packet.transport_id != None` and routes normally.

**Category:** N/A — this is the removal of a previous deviation, restoring python parity. Documented here for the historical record so future readers don't reintroduce it.

**Date:** 2026-05-10.

**Tracking:** reticulum-conformance shared-instance test trio.

**Description:** an earlier version of `processInbound` contained a kotlin-only compensation block that, on receiving a `HEADER_1` packet from a local client destined for a remote, mutated `packet.raw` in place to upgrade it to `HEADER_2` with the master's identity inserted at offset 2, and set `packet.transportId` to the same hash. This was a workaround for the bug documented in the previous entry (manually-registered `LocalClientInterface` not flipping `Transport.isConnectedToSharedInstance`, so kotlin clients sent `HEADER_1` outbound where python clients send `HEADER_2`). The compensation broke the `packet.raw ↔ packet.headerType` invariant relied on by `Packet.getHashablePart()` — `headerType` is `val`, set at unpack, and stayed `HEADER_1` after the raw mutation; `getHashablePart()` then sliced the new `HEADER_2` raw using the cached `HEADER_1` layout, accidentally including the just-inserted `transport_id` in the hash input, producing a `link_id` that diverged from what the originator and destination computed. The LRPROOF returning from the destination hashed to the originator's `link_id`, but the master's `link_table` was keyed under the bogus mutated-hashable hash, and the proof was silently dropped.

With the `registerInterface` widening above, kotlin clients now pack `HEADER_2` outbound on the same code path as python, so the master receives `packet.transportId != null` and no compensation is required. The compensation block is removed; the master's processing now mirrors python's exactly.

**Re-evaluation:** the removal is a strict re-convergence with the python reference — there's nothing to re-evaluate unless a future kotlin downstream consumer reintroduces a code path that bypasses both `Reticulum.tryConnectToSharedInstance` and `Transport.registerInterface`'s flag widening (which would be a separate bug to fix at the new bypass site, not here).

### Eager client-side path persistence with deferred (lazy) interface validation — `rns-core/.../Transport.kt::loadPersistedDataFromStorage`, `hasPath`/`hopsTo`/`nextHop`/`isDanglingPath`, `cullTables`

**Python reference:** `RNS/Transport.py:255-300` (`__init__` load of `destination_table` from `destination_table` cache). Python loads the persisted path table **only when `Reticulum.transport_enabled()`** is true (leaf clients never persist or restore paths), and validates each entry **at load time**: an entry is dropped if its `receiving_interface` no longer resolves to a registered interface ("The interface is no longer available") or if no cached announce backs it. After load, `has_path`/`hops_to`/`next_hop` can therefore trust that every table entry references a live interface.

**Category:** new feature (client-side path persistence) + language/runtime forced (lazy validation timing).

**Date:** 2026-06-09.

**Tracking:** columba#1004 (D5). See also `columba` memory `issue-1004-path-requests-direct-delivery`.

**Description:** Columba persists the path table on *plain clients* (phones) via a Room-backed [PathStore] — an intentional improvement over python, where only transport nodes persist paths, so that a freshly-opened app can reach recently-known destinations without waiting for a fresh announce ([path-persistence-improvement]). Two deviations follow from this:

1. **Eager load (feature).** `loadPersistedDataFromStorage` restores `pathTable` unconditionally, omitting python's `transport_enabled()` gate. This is the persistence feature itself and must not be "fixed" back to the python gate.

2. **Lazy interface validation (runtime-forced).** Python validates interfaces at load time because, in python, all configured interfaces are constructed and registered *before* `Transport` loads the table. On Android, interfaces (TCP, BLE, RNode) register **asynchronously after** `Transport.start()`, so a load-time validation would wrongly drop every restored entry during the startup window before its interface comes up. Instead, validation is deferred and split:
   - `hasPath`/`hopsTo`/`nextHop` apply a **non-destructive** `isDanglingPath` check: a restored entry whose `receivingInterfaceHash` resolves to no registered interface is treated as absent, *but only once at least one interface has registered* (`interfaces.isNotEmpty()` — the same guard `savePathTable` uses, mirroring `Transport.py:2905-2910`). This preserves python's invariant ("a usable path references a live interface") at the read APIs without racing async registration.
   - `cullTables` performs the **destructive** prune (removing the entry and calling `pathStore.removePath`) but only after `STARTUP_GRACE_PERIOD` past `startTime`, which is the kotlin equivalent of python's load-time "interface no longer available" drop (`Transport.py:284-298`), shifted later to accommodate async registration.

   Net effect: once interfaces are up, kotlin's read APIs and table contents converge on exactly what python would have after its load-time validation.

**Re-evaluation:** if Android interface registration is ever made synchronous-before-`Transport.start()` (matching python's construct-then-load order), the lazy split can collapse back into a single load-time validation in `loadPersistedDataFromStorage`, and the `interfaces.isNotEmpty()` guard plus the grace-period cull become unnecessary. The eager-load feature (item 1) is independent and stays regardless, as long as Columba wants client-side path persistence.

### Adaptive multicast announce interval + Doze throttle — `rns-interfaces/.../auto/AutoInterface.kt::startAnnouncementLoop`, `updateAnnounceInterval`, `resetAnnounceInterval`, `throttleMultiplier`

**Python reference:** `RNS/Interfaces/AutoInterface.py:62` (`ANNOUNCE_INTERVAL = 1.6`), `:472-475` (`announce_handler`: `while True: peer_announce(ifname); time.sleep(1.6)` — a fixed 1.6s multicast discovery transmission, per adopted interface, forever). Python constants that interact with this deviation: `:61` (`PEERING_TIMEOUT = 22.0`), `:371-381` (`peer_jobs` expires peers not heard within the timeout and tears down their spawned interfaces), `:614` (`AutoInterfacePeer.process_incoming` refreshes `last_heard` on inbound data, not just announces).

**Category:** new feature (mobile power optimization). **Rule caveat:** as written above, reason 2 requires that kotlin-only behavior "not change semantics of any code path that does exist in python" — this deviation *does* change the announce cadence of a python-existing path. It landed 2026-04-05 without an entry here; documented retroactively 2026-06-11. Owner should confirm the justification stands (process rule 3), especially given the hazard below.

**Date:** code 2026-04-05 (`9b0d21a` adaptive interval, `59db315` Doze throttle); entry 2026-06-11.

**Tracking:** columba `docs/battery-optimization-opportunities.md` item 1 (announce-loop wakeup mechanics) and its parity note.

**Description:** python transmits a multicast discovery announce every 1.6 seconds per adopted interface, unconditionally — ~54,000 multicast TX/day per interface, which on a phone keeps the WiFi radio out of power-save indefinitely. kotlin replaces the fixed cadence with an adaptive ramp (`AutoInterface.kt:57-70, 536-552`): announces start at the python-compatible 1.6s (`minAnnounceIntervalMs = ANNOUNCE_INTERVAL_MS`), and over the 60s following the last peer-topology change (`rampUpDurationMs`) the interval ramps linearly to `maxAnnounceIntervalMs = 120_000` (2 minutes). Any peer add/remove calls `resetAnnounceInterval()`, snapping back to 1.6s so new or changed peers are discovered at python speed. An additional `throttleMultiplier` (default 1.0) scales the effective max; rns-android's Doze plumbing raises it when the device idles. Receiving-side behavior is unchanged: like python, kotlin discovers announcing peers regardless of its own TX rate, and refreshes `lastHeard` on both announces (`addPeer`→`refreshPeer`, `AutoInterface.kt:700`) and inbound data (`AutoInterface.kt:465`), matching python's `:614`.

**Known hazard — steady-state interval exceeds PEERING_TIMEOUT (owner decision needed):** both implementations expire peers not heard for 22s (`PEERING_TIMEOUT`; kotlin `peerJobs`, `AutoInterface.kt:661-676`). Data traffic refreshes peers on both sides, so *active* links are unaffected. But for an **idle** pair, announces are the only refresh source, and the ramped announce gap (→120s, larger still under the Doze multiplier) blows through the 22s timeout: once the ramp produces a gap >22s (roughly 10–30s after the last peer change), the remote side expires us — and expiry is disruptive, not cosmetic: kotlin's `removePeer` deregisters the spawned peer interface from `Transport` (`AutoInterface.kt:751-762`); python tears down its spawned interface likewise (`:381+`). Expiry also fires `resetAnnounceInterval()`, so the pair re-peers within seconds and starts ramping again — an add→expire→re-add flap cycle on the order of once per minute at idle, with Transport interface churn each cycle, against both python and kotlin remotes. Two consequences: (1) the nominal "~75x fewer announces" steady state is never actually reached against a live peer — the effective announce rate oscillates between 1.6s and roughly the timeout; (2) inbound delivery via AutoInterface during an expired window can be dropped on the remote (no spawned interface exists for us until our next announce). Mitigation options: (a) cap `effectiveMax` at ~15–18s — safely inside `PEERING_TIMEOUT` with jitter margin, still ~10x fewer TX than python; (b) keep slow multicast announces but add a unicast keepalive to *established* peers at <22s cadence (python's `reverse_announce`, `:477-489`, is precedent; kotlin currently has no unicast announce path); (c) a negotiated/longer peering timeout — not viable against fixed python remotes. Until one of these lands, AutoInterface peer flapping in idle-device logs is this deviation, not a network bug. Related dead code: `AutoInterfaceConstants.kt:52` defines `ANDROID_PEERING_TIMEOUT = 27.5` but nothing references it.

**Re-evaluation:** if the announce cadence is capped ≤ `PEERING_TIMEOUT` (option a), the hazard paragraph retires and the deviation becomes a strict improvement; if upstream python ever adopts adaptive announces, converge on its constants instead. The columba battery-doc item 1 (replacing the announce loop's 1s flag-poll wakeup with a channel signal) is orthogonal wakeup mechanics and folds into this entry whenever it lands.

### remember() malformed-key gate raises IllegalArgumentException, not TypeError — `rns-core/.../identity/Identity.kt::remember`

**Python reference:** `RNS/Identity.py:100-101` (1.1.3) / `:102-103` (1.3.1) — `remember()` raises `TypeError` when `len(public_key) != Identity.KEYSIZE//8` (64 bytes), so a corrupt announce can never plant an unusable key.

**Category:** language/runtime forced (exception-type idiom only; the gate condition and placement mirror python exactly).

**Date:** 2026-06-12.

**Tracking:** conformance-suite `identity_remember` bridge command (reticulum-conformance `reference/bridge_server.py::cmd_identity_remember`).

**Description:** kotlin's `remember()` previously had NO length gate (silent divergence — any key size was stored). The gate is now added to match python. Python raises `TypeError`; kotlin throws `IllegalArgumentException`, the JVM-idiomatic equivalent for an invalid argument (kotlin reserves its `TypeCastException`-family for actual cast failures, so `TypeError` has no faithful counterpart). Callers that need python parity should treat `IllegalArgumentException` from `remember()` as python's `TypeError`.

**Re-evaluation:** none needed — permanent idiom mapping. NOTE (pre-existing, undocumented divergence spotted during this change, NOT introduced by it): python 1.3.1's `remember()` additionally takes `known_destinations_lock`, stores 5-element entries, and on an already-known destination UPDATES timestamp/packet_hash/public_key/app_data in place (`Identity.py:105-117`); kotlin unconditionally overwrites with a fresh `IdentityData` and has no 5th element. Functionally close but not identical (the 1.3.1 5th element survives updates). Needs its own entry or a port fix when the `remember-update-refreshes-existing-entry` conformance behavior gets exercised.

### optimiseMtu as companion function, not instance mutator — `rns-interfaces/.../Interface.kt::Companion.optimiseMtu`

**Python reference:** `RNS/Interfaces/Interface.py:140-163` (1.1.3) / `:198-221` (1.3.1) — `optimise_mtu(self)` mutates `self.HW_MTU` from a bitrate tier table, gated on `self.AUTOCONFIGURE_MTU`.

**Category:** language/runtime forced.

**Date:** 2026-06-12.

**Tracking:** conformance `interface_optimise_mtu` bridge command.

**Description:** kotlin's `Interface.hwMtu` is an immutable `open val` (subclass-declared), so python's in-place `self.HW_MTU = ...` mutation pattern cannot be expressed. The tier mapping is ported byte-for-byte (every threshold, comparator, and value identical, including the `>= 1 Gbps` top tier vs `>` elsewhere and the `None`/null bottom tier) as a pure companion function `optimiseMtu(bitrate): Int?`; callers apply python's `AUTOCONFIGURE_MTU` gate themselves and assign the result wherever their MTU state lives.

**Re-evaluation:** if `hwMtu` ever becomes mutable interface state, this can return to an instance method with the gate inside, matching python's shape exactly.

### WallClock pinning seam for protocol timestamps — `rns-core/.../common/WallClock.kt`, consulted by `Destination.generateAnnounceData`/`cachePathResponse`/`cleanStalePathResponses`

**Python reference:** none directly — the python *conformance bridge* pins `time.time()` by monkeypatching around single `announce()` calls (reticulum-conformance `reference/bridge_server.py::cmd_announce_build`, `cmd_destination_path_response_cache`); the library itself reads the real clock.

**Category:** new feature (test seam), forced by the JVM's inability to patch `System.currentTimeMillis()`.

**Date:** 2026-06-12.

**Tracking:** conformance kotlin-bridge command surface work (announce_build emission_ts, destination_path_response_cache, Phase-3 behavioral time control).

**Description:** protocol code paths whose wall-clock reads are observable behavior (announce random-hash timestamp embed, path-response PR_TAG_WINDOW bookkeeping; later transport-table ages) read `WallClock.nowMs()` instead of `System.currentTimeMillis()`. With `overrideMs == null` (always, in production) this is byte-for-byte `System.currentTimeMillis()`; the override exists solely so the conformance bridge can pin the clock the way the python bridge pins `time.time()`. No python-visible semantics change.

**Re-evaluation:** if a general clock-injection design ever lands (e.g. kotlinx-datetime Clock plumbed through constructors), fold this into it.

### Blanket: python TypeError/ValueError argument guards → kotlin IllegalArgumentException — port-wide

**Python reference:** recurring pattern — e.g. `Destination.py:128` (`hash`: "Invalid material supplied..."), `:365-366` (`set_proof_strategy`: "Unsupported proof strategy"), `Identity.py:101-102` (`remember`).

**Category:** language/runtime forced (exception-type idiom only).

**Date:** 2026-06-12.

**Tracking:** conformance kotlin-bridge surface work.

**Description:** python uses TypeError/ValueError interchangeably for invalid-argument guards; kotlin's idiomatic equivalent for both is IllegalArgumentException (via `require` or explicit throw). Wherever the port adds or corrects such a guard, the CONDITION and MESSAGE mirror python exactly and only the exception class maps to IAE. This blanket entry covers all such sites (each carries a code comment citing its python line); per-site entries are only written when more than the exception class differs.

**Re-evaluation:** none — permanent idiom mapping.

### Packet context as enum + contextRaw; createRaw pass-through pack() — `rns-core/.../packet/Packet.kt`

**Python reference:** `RNS/Packet.py:186-238` (pack: encrypt-at-pack branch table, HEADER_2 announce-only assembly, IOError on missing transport id), `:246-252` (unpack: context is a raw int — any wire byte parses and simply matches no dispatch branch).

**Category:** language/runtime forced (representation), plus one pre-existing kotlin-only construct documented retroactively.

**Date:** 2026-06-12.

**Tracking:** conformance packet_build/packet_build_raw_header2/packet_resend_observe arms; conformance tests test_packet*.py.

**Description:** (1) python's `Packet.context` is a bare int; kotlin's typed `PacketContext` enum cannot carry unnamed code points, so `contextRaw: Int` is the wire source of truth (pack writes it, unpack stores it) and `PacketContext.UNKNOWN(-1)` is the named view for unknown bytes — matching python's accept-and-match-nothing forward compatibility byte-for-byte. (2) python encrypts inside pack() (fresh ephemeral/IV per pack, which is what makes resend() re-encrypt); kotlin previously encrypted at Packet.create — now moved into pack() with python's exact unencrypted-class branch table and HEADER_2 announce-only rule (python error text preserved; IOError/AttributeError → IllegalStateException per the blanket idiom entry). (3) kotlin-only `Packet.createRaw` (no python counterpart — python transport splices raw bytes instead of re-packing) passes `data` through pack() untouched; that pass-through is its documented contract and is unreachable from python-mirrored code paths.

**Re-evaluation:** if PacketContext is ever refactored to a value-class over Int, UNKNOWN/contextRaw collapse into it.

### Discovery: polymorphic type-field sourcing; no executable reachable_on; injected source allowlist — `rns-core/.../discovery/{InterfaceAnnouncer,InterfaceAnnounceHandler,DiscoveryUtil}.kt`

**Python reference:** `RNS/Discovery.py:96-186` (builder: per-type fields AND rules centralized; the reachable_on-from-executable subprocess branch at :117-131), `:214-362` (received_announce), `:216` (sources read live from Reticulum config), `:769-790` (validators/san_map).

**Category:** mixed — (a) language/structure: per-type announce FIELDS are sourced polymorphically via `Interface.getDiscoveryData()` (kotlin's typed interfaces can't be duck-probed for arbitrary attributes), while every RULE (TCPClient-without-KISS abort, Backbone/TCPServer reachable_on validation+abort, KISS rewrite, IFAC publication, insertion order via LinkedHashMap) is centralized in the builder exactly as python's; (b) deliberate omission: the reachable_on-from-executable branch (python runs a user-configured executable and parses stdout) is NOT ported — running config-supplied executables is rejected on JVM/Android; an executable path therefore fails the IP/hostname validation and aborts the announce (python's failure mode for a broken script). (c) the receiver's source allowlist is constructor-injected instead of read live from Reticulum config (kotlin has no INI config layer yet); empty/null disables gating exactly like python's falsy check. Receiver validation core (hard type gates, whitelist, sanitize_name/san_map, config_entry strings, callback(None) on missing INTERFACE_TYPE) is a line-for-line port.

**Date:** 2026-06-12.

**Tracking:** conformance discovery_* bridge commands; tests/test_discovery_*.py.

**Re-evaluation:** when kotlin grows a config layer, switch the allowlist to a live read; revisit the executable branch only if a sandboxed design is approved by the owner.

### announce_rate_table is a timestamp list, not python's dict — `rns-core/.../transport/Transport.kt::announceRateTable`

**Python reference:** `RNS/Transport.py:1830-1860` — `announce_rate_table[dest]` is a dict `{"last", "rate_violations", "blocked_until", "timestamps":[...]}`.

**Category:** new feature divergence (simplified model) — flagged for follow-up.

**Date:** 2026-06-12.

**Tracking:** conformance `behavioral_read_announce_rate`; announce-rate ENFORCEMENT is LIMITS-class per CONFORMANCE_COMPLETENESS_V2 §, so only the timestamp history is observably pinned today.

**Description:** kotlin stores only the per-destination announce timestamp history (`Map<ByteArrayKey, MutableList<Long>>`), not python's full rate-limiter record with `last`/`rate_violations`/`blocked_until`. The conformance read surfaces `timestamps` faithfully and derives `last = max(timestamps)`; `rate_violations`/`blocked_until` are reported as 0 because kotlin does not yet implement the grace-counter/penalty-window enforcement. This is a genuine feature gap, not just a representation choice.

**Re-evaluation:** when kotlin implements per-destination announce-rate enforcement (target rate + grace + penalty window), port python's full record and remove this entry.

---

### Typed config-knob threading replaces the INI config layer (Phase 5g) — `rns-core/.../Reticulum.kt::start`/`initialize`, `rns-interfaces/.../tcp/{TCPServerInterface,TCPClientInterface}.kt`, `Interface.kt`

**Python reference:** `RNS/Reticulum.py:253-281` (posture defaults), `:489-495` (rpc_key parse/fallback), `:497-558` (`__apply_config` flag parse), `:575-591` (blackhole/discovery-source 16-byte validation + dedup), `:347-348` (rpc_key default = full_hash(transport private key)), `:719-723` (ifac_size bits->bytes floor), `:765-768` (bitrate MINIMUM_BITRATE floor); `RNS/Interfaces/Interface.py:89-94` (DEFAULT_AR_*, AUTOCONFIGURE_MTU/FIXED_MTU).

**Category:** language/architecture divergence — kotlin has no INI/ConfigObj parser and no `__apply_config`.

**Date:** 2026-06-12.

**Tracking:** conformance `wire_instance_posture`, `wire_transport_enabled`, `wire_rpc_authkey`, `wire_interface_bitrate`, `wire_interface_hw_mtu`, `wire_interface_transport_defaults`, `wire_first_hop_timeout`, `wire_discovery_autoconnect_gate` (tests/wire/test_reticulum_config_hooks.py, test_reticulum_config_v2.py, test_interface_defaults_v2.py, test_discovery_autoconnect_v2.py, test_link_protocol.py, test_link_completeness.py).

**Description:** RNS resolves these posture/interface knobs by parsing an INI config in `__apply_config`. reticulum-kt has no config layer, so the same flags are threaded as typed parameters: `Reticulum.start(respondToProbes, useImplicitProof, enableRemoteManagement, remoteManagementAllowed, panicOnInterfaceError, blackholeSources, interfaceDiscoverySources, rpcKey)` plus companion read-outs (`probeDestinationEnabled`/`shouldUseImplicitProof`/`remoteManagementEnabled`/`panicOnInterfaceError`/`interfaceDiscoverySources`); and TCP interface constructor params `bitrate`/`fixedMtuBytes`/`ifacSizeBits`. The 16-byte identity-hash validation + dedup that python does in `__apply_config` runs in `Reticulum.start()` (a wrong-length/invalid-hex hash aborts the start, matching python's ValueError). rpc_key parse-with-fallback and the SHA-256(private-key) default run in `initialize()`. The bitrate floor and ifac_size bits->bytes floor run in the interface constructors (python applies them post-init in `_synthesize_interface`); the spawned `TCPServerClientInterface` inherits the parent's MTU/bitrate posture so the receiver side of a fixed-MTU link negotiates the same value. `Interface.autoconfigureMtu`/`fixedMtu` are faithful ports of python's `AUTOCONFIGURE_MTU`/`FIXED_MTU` class attributes; `Interface.DEFAULT_AR_TARGET/_PENALTY/_GRACE` are faithful ports of the python constants. The resolved VALUES match python exactly — only the resolution mechanism (typed kwargs vs INI parse) differs.

**Re-evaluation:** if reticulum-kt ever gains an INI/`_synthesize_interface` config layer, route these knobs through it and keep the typed params as the programmatic surface.

---

### InterfaceDiscovery.autoconnect Yggdrasil 200::/7 guard added (Phase 5g divergence fix) — `rns-core/.../discovery/InterfaceDiscovery.kt::autoconnect`

**Python reference:** `RNS/Discovery.py:649-651` — `if is_ygg_ipv6(info["reachable_on"]): return` (skip auto-connecting a BackboneInterface on a Yggdrasil address).

**Category:** divergence FIX (the guard was missing; kotlin wrongly auto-connected ygg endpoints).

**Date:** 2026-06-12.

**Tracking:** conformance `test_autoconnect_rejects_unsupported_records` (the `yggdrasil` case); unit `InterfaceDiscoveryTest.autoconnect skips yggdrasil endpoint`.

**Description:** kotlin's `autoconnect` had no Yggdrasil guard, so a discovered `BackboneInterface` reachable on a 200::/7 address (which IS in `AUTOCONNECT_TYPES`) would invoke the connect factory. The guard `if (info.reachableOn != null && DiscoveryUtil.isYggIpv6(info.reachableOn)) return` is now applied after the type/limit/dedup checks and before the factory invoke, mirroring python. `endpointHashForTest`/`autoconnectForTest` are public test seams over the existing inline endpoint-hash computation and the private `autoconnect`.
