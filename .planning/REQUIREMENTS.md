# Requirements: Reticulum-KT v3 BLE Interface

**Defined:** 2026-02-06
**Core Value:** BLE mesh networking — peer-to-peer Reticulum over Bluetooth Low Energy

## v3 Requirements

Requirements for BLE mesh interface. Each maps to roadmap phases.

### Fragmentation and Wire Format

- [x] **FRAG-01**: Fragmenter splits packets using 5-byte header [type:1][sequence:2][total:2] big-endian
- [x] **FRAG-02**: Fragment types match protocol: START(0x01), CONTINUE(0x02), END(0x03)
- [x] **FRAG-03**: Fragment payload size = MTU - 5 bytes (respects negotiated MTU)
- [x] **FRAG-04**: Single-fragment packets still include header (START type, seq=0, total=1)
- [x] **FRAG-05**: Reassembler reconstructs original packet from ordered fragments
- [x] **FRAG-06**: Reassembler handles out-of-order fragment arrival
- [x] **FRAG-07**: Reassembler times out incomplete packets after 30 seconds
- [x] **FRAG-08**: Wire format byte-identical to Python ble-reticulum BLEFragmentation.py

### Driver Abstraction

- [x] **DRV-01**: BLEDriver interface defined in rns-interfaces (pure JVM, no Android imports)
- [x] **DRV-02**: BLEDriver exposes message-based API (send/receive fragments, not streams)
- [x] **DRV-03**: AndroidBLEDriver implements BLEDriver using Android BLE APIs
- [x] **DRV-04**: Protocol logic unit-testable on JVM with mock BLEDriver (no Android required)

### GATT Server (Peripheral Role)

- [x] **GATT-01**: GATT server hosts custom service (UUID: 37145b00-442d-4a94-917f-8f42c5da28e3)
- [x] **GATT-02**: RX characteristic accepts writes and write-without-response
- [x] **GATT-03**: TX characteristic supports notifications (CCCD descriptor)
- [x] **GATT-04**: Identity characteristic serves 16-byte Transport identity hash (read-only)
- [x] **GATT-05**: Server always calls sendResponse() when responseNeeded=true
- [x] **GATT-06**: Notifications serialized (wait for onNotificationSent before next)
- [x] **GATT-07**: Per-device MTU tracked from onMtuChanged callbacks

### BLE Advertising

- [x] **ADV-01**: Advertises custom service UUID for peer discovery
- [x] **ADV-02**: Advertising restarts after connection (handles Android auto-stop)
- [x] **ADV-03**: Advertising refresh every 60 seconds (background persistence)

### GATT Client (Central Role)

- [x] **CLI-01**: Scanner discovers peers by service UUID filter (single long-running scan)
- [x] **CLI-02**: GATT client connects, discovers services, enables TX notifications
- [x] **CLI-03**: MTU negotiation requests 517 bytes, accepts any negotiated value
- [x] **CLI-04**: All GATT operations serialized via operation queue (prevents pipelining)
- [x] **CLI-05**: GATT error 133 triggers retry with exponential backoff

### Identity Handshake

- [x] **ID-01**: Central reads peripheral's Identity characteristic (16 bytes)
- [x] **ID-02**: Central writes own 16-byte identity to RX as first packet (WRITE_WITH_RESPONSE)
- [x] **ID-03**: Identity exchange completes before any data transfer
- [x] **ID-04**: Peer keyed by identity hash (survives MAC rotation)
- [x] **ID-05**: Identity handshake times out after 30 seconds

### Connection Management

- [x] **CONN-01**: Identity-based sorting determines connection dedup (replaces MAC sorting — Android can't provide local MAC)
- [x] **CONN-02**: Dual-role operation — simultaneous scanning, advertising, and connections
- [x] **CONN-03**: BLEInterface spawns BLEPeerInterface per connected peer
- [x] **CONN-04**: Spawned BLEPeerInterface registered with Transport for routing
- [x] **CONN-05**: Keepalive byte (0x00) sent every 15 seconds per connection
- [x] **CONN-06**: Disconnected peers cleaned up (Transport deregistered, state freed)
- [x] **CONN-07**: Connection limit respected (max 5 simultaneous peers, configurable)

### Deduplication

- [ ] **DEDUP-01**: Duplicate identity connections detected and resolved (keep newest)
- [ ] **DEDUP-02**: MAC rotation does not create duplicate connections for same identity

### Hardening

- [ ] **HARD-01**: Zombie connection detection (peer unresponsive despite connected state)
- [ ] **HARD-02**: Failed peer blacklisting with exponential backoff
- [ ] **HARD-03**: Graceful degradation at connection limit (prioritize by peer score)
- [ ] **HARD-04**: Peer scoring: RSSI (60%) + history (30%) + recency (10%)

### App Integration

- [x] **APP-01**: InterfaceManager creates BLEInterface with AndroidBLEDriver injection
- [ ] **APP-02**: BLE interface toggle in UI (start/stop discovery and connections)
- [x] **APP-03**: BLE permissions declared in manifest (SCAN, CONNECT, ADVERTISE)

## Out of Scope

| Feature | Reason |
|---------|--------|
| BLE Classic (BR/EDR) | Protocol uses BLE only |
| iOS BLE interface | Android-first; iOS has different BLE APIs |
| HDLC framing over BLE | BLE uses fragment protocol, not HDLC byte-stuffing |
| Multi-hop BLE relay | Transport layer handles routing; BLE is link-layer only |
| v0.3.0 capability flags | Deferred — all Android devices support both central and peripheral |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| FRAG-01..08 | Phase 18 | Complete |
| DRV-01..02,04 | Phase 18 | Complete |
| DRV-03 | Phase 20 | Complete |
| GATT-01..07 | Phase 19 | Complete |
| ADV-01..03 | Phase 19 | Complete |
| CLI-01..05 | Phase 20 | Complete |
| ID-01..05 | Phase 21 | Complete |
| CONN-01..07 | Phase 21 | Complete |
| DEDUP-01..02 | Phase 22 | Pending |
| HARD-01..04 | Phase 22 | Pending |
| APP-01,03 | Phase 21 | Complete |
| APP-02 | Phase 22+ | Pending |

**Coverage:**
- v3 requirements: 42 total
- Mapped to phases: 42
- Unmapped: 0

---
*Requirements defined: 2026-02-06*
*Traceability updated: 2026-02-06*
