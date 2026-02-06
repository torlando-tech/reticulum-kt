# Phase 19: GATT Server and Advertising - Context

**Gathered:** 2026-02-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement the BLE peripheral role: host a GATT service with Reticulum characteristics (RX, TX, Identity), advertise the service UUID so nearby centrals can discover this device, and manage per-connection state (MTU, CCCD). This phase delivers the server half of BLE communication. Client/scanner (Phase 20), orchestration/identity handshake (Phase 21), and hardening (Phase 22) are separate phases.

</domain>

<decisions>
## Implementation Decisions

### Connection Lifecycle
- Gate data exchange on identity handshake — GATT server buffers or drops RX writes from unauthenticated centrals until identity exchange completes
- No connection limit enforcement at server level — accept all connections; Phase 21's orchestration layer handles limiting and dropping lowest-scored peers
- Event-driven disconnects — server emits disconnect events via SharedFlow so upper layers (BLEInterface/Transport) can deregister the peer
- Per-device state reset policy: Claude's discretion (decide based on BLE spec behavior and Python reference)

### Advertising Strategy
- Stop advertising when max connections reached; restart when a slot opens
- Advertising resilience (OEM silent kills, 60s refresh): Claude's discretion on implementation approach
- Minimal advertising payload — service UUID only, no device name or extra data
- Advertising interval is **user-configurable** — expose as a parameter rather than hardcoding; provide sensible default

### Error and Edge-Case Responses
- Silent drop on malformed RX writes — do not disconnect the offending central; let sender's reassembly timeout handle it
- Retry once on notification send failure — one retry attempt, then skip the fragment
- Minimal logging — only log errors and disconnects; quiet by default
- Report and stop on GATT server init failure — emit error event, let InterfaceManager/BLEInterface decide retry policy

### Module Placement
- **Android dependencies in rns-interfaces** — add Android BLE APIs to the rns-interfaces module. This is a purpose-built Android implementation; pure JVM compatibility for BLE code is not a goal
- BleGattServer, BleAdvertiser, and BleOperationQueue all live in `rns-interfaces` under the existing `network.reticulum.interfaces.ble` package
- RNode BLE code (BluetoothLeConnection.kt, NUS-related code) stays in rns-sample-app — different BLE use case
- BLEInterface appears in the sample app like any other Reticulum interface (TCP, Auto, RNode) — the peripheral role is an invisible implementation detail, no special UI
- Fate of BLEDriver interface/BLEPeerConnection from Phase 18: Claude's discretion on whether to keep, simplify, or merge
- Gradle dependency isolation strategy: Claude's discretion on source sets vs mixed deps

### Claude's Discretion
- Per-device state caching on reconnect (fresh vs brief cache)
- BLEDriver/BLEPeerConnection fate (keep abstraction or fold into concrete classes)
- BleOperationQueue as standalone vs built-in (consider Phase 20 reuse)
- Gradle source set isolation for Android BLE deps
- Advertising resilience mechanism (periodic restart vs monitor+restart)
- Testing strategy (mock Android APIs vs device-only tests)

</decisions>

<specifics>
## Specific Ideas

- "The BLE peripheral role is just an invisible part of the BLEInterface; the BLEInterface should show up in the sample app like any other Reticulum interface"
- "This is meant to be a purpose-built Android implementation of the BLE interface — no advantage to making anything JVM compatible"
- Advertising interval should be configurable by the user, not a hardcoded constant

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 19-gatt-server-advertising*
*Context gathered: 2026-02-06*
