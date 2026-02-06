# Phase 21: BLEInterface Orchestration - Context

**Gathered:** 2026-02-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire discovery, connection direction, identity exchange, and peer lifecycle into a working BLE mesh. This phase creates BLEInterface (server-style parent) and BLEPeerInterface (per-peer child) that integrate with Transport, using the BLEDriver components from Phases 18-20. MAC sorting determines connection direction, identity handshake gates data transfer, and dual-role operation enables simultaneous scanning + advertising.

</domain>

<decisions>
## Implementation Decisions

### Connection Formation
- MAC sorting: lower MAC initiates as central, higher waits as peripheral
- Equal MACs (extremely rare): Claude's discretion — pick simplest approach
- Race condition (both sides connect simultaneously): accept both connections, deduplicate by identity after handshake reveals same peer
- After connection drop: backoff delay (5-10s) before reconnecting to avoid rapid connect/disconnect loops
- No orchestration-level retry limit: Phase 20's GATT error 133 handling + 60s blacklist is sufficient. Orchestration retries on next scan appearance after backoff.

### Identity Handshake Flow
- Central reads peripheral's Identity characteristic, then writes own identity to RX
- 30-second handshake timeout: disconnect and blacklist temporarily (60s) on timeout
- Require Transport identity before starting: don't advertise or scan until Transport.identityHash is available
- Duplicate identity (same identity, different address — MAC rotation): keep newest connection, tear down oldest
- Data transfer blocked until identity handshake completes: no Reticulum packets until both sides have exchanged identity

### Peer Lifecycle & Transport Integration
- BLEPeerInterface extends Interface base class (same as LocalClientInterface pattern) — full Interface lifecycle with processIncoming/processOutgoing, toRef(), Transport registration
- BLEInterface is server-style parent (same as LocalServerInterface pattern): spawns BLEPeerInterface children per peer, registers children with Transport, parent processOutgoing() broadcasts to all children
- Python reference confirms this pattern: BLEInterface(Interface) parent spawns BLEPeerInterface(Interface) children, children registered directly with Transport.interfaces
- Keepalive failure: grace period — miss keepalive → send one more → wait another interval → then tear down
- BLEInterface lives in rns-interfaces (pure JVM) — depends only on BLEDriver interface, AndroidBLEDriver injected at runtime

### Dual-Role Operation
- Startup order: advertise first (be discoverable), then start scanning after brief delay (~100ms)
- Hard connection cap at 7 (Android BLE limit)
- At capacity: keep scanning, replace worst-scored peer if a significantly better peer appears
- Minimal user-facing config: scan mode (low power vs balanced) and max connections in interface configuration

### Claude's Discretion
- Equal MAC tie-break strategy (extremely rare edge case)
- Exact backoff timing for reconnection delay (5-10s range given)
- "Significantly better peer" threshold for peer replacement at capacity
- Keepalive interval timing (Python reference uses 15s — match or adjust)
- Exact startup delay between advertising and scanning start
- BLEPeerInterface naming convention and logging format

</decisions>

<specifics>
## Specific Ideas

- Python BLEInterface.py is the reference: server-style parent that spawns BLEPeerInterface children, children registered with Transport.interfaces
- Follow existing Kotlin patterns: LocalServerInterface spawns LocalClientInterface children, Transport.registerInterface() / deregisterInterface() for lifecycle
- InterfaceAdapter wraps Interface into InterfaceRef for Transport — use interface.toRef()
- Server processOutgoing() is no-op by design — Transport calls each spawned interface's processOutgoing() directly

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. Hardening features (zombie detection, blacklisting, deduplication refinement) are explicitly Phase 22.

</deferred>

---

*Phase: 21-ble-orchestration*
*Context gathered: 2026-02-06*
