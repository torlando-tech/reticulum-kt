# Phase 20: GATT Client and Scanner - Context

**Gathered:** 2026-02-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement the BLE central role: scan for nearby Reticulum BLE peripherals by service UUID, connect as GATT client, discover services, negotiate MTU, enable TX notifications via CCCD, and serialize all GATT operations through BleOperationQueue. Also implement AndroidBLEDriver as the bridge between the pure-JVM BLEDriver interface (Phase 18) and the Android BLE stack (Phase 19 server + Phase 20 client). Identity handshake, MAC sorting, and connection orchestration are Phase 21. Hardening (zombie detection, blacklisting refinement, dedup) is Phase 22.

</domain>

<decisions>
## Implementation Decisions

### Scan Lifecycle
- Scan mode is **user-configurable** — expose as a parameter (similar to BleAdvertiser's AdvertiseMode), not hardcoded
- **Hardware-level service UUID filter** via ScanFilter — most efficient, offloaded to BLE chip. Note: some OEMs have buggy filter implementations; a software filter fallback may be needed in Phase 22
- **Explicit stop only** — scanning runs until told to stop, even at max connections. Phase 22's peer scoring needs continuous discovery for replacement decisions
- **Emit all results with throttle** — every scan callback emitted but throttled to max once per device per N seconds, balancing RSSI scoring data with noise reduction

### Connection Error Handling
- **Exponential backoff** on GATT error 133 (1s, 2s, 4s, 8s...)
- **5 retry attempts** before giving up on a connection
- **Temporary blacklist** after max retries — don't attempt reconnection for a cooldown period. Phase 22 refines the blacklist with peer scoring
- **Close + fresh connectGatt()** on error 133 — full teardown to avoid stale GATT state. No graceful disconnect attempt first

### Module Placement
- **All Android BLE code in rns-android** — BleGattClient, BleScanner, AndroidBLEDriver go in `rns-android` alongside Phase 19's BleGattServer/BleAdvertiser
- **AndroidBLEDriver in rns-android** — bridges pure-JVM BLEDriver interface with Android implementations
- **Internal + facade** visibility — BLE classes (BleGattClient, BleScanner, BleGattServer, BleAdvertiser) are `internal`. Only AndroidBLEDriver is public API
- **Keep RNode BLE separate** — existing BluetoothLeConnection.kt in rns-sample-app stays separate (different BLE service: NUS vs Reticulum)

### AndroidBLEDriver Scope
- **Delegate to managers** — AndroidBLEDriver delegates to separate manager classes, not direct ownership of all components
- **Aggregate and transform events** — AndroidBLEDriver combines events from BleScanner/BleGattClient/BleGattServer into unified BLEDriver SharedFlows
- **BLEInterface handles identity handshake** (Phase 21) — AndroidBLEDriver just connects; identity exchange is a higher-level concern
- BLEPeerConnection creation: Claude's discretion on whether driver or Phase 21 creates them

### Claude's Discretion
- BLEPeerConnection creation ownership (driver vs Phase 21)
- Exact scan throttle interval for RSSI updates
- Default scan mode if user doesn't specify
- Manager class decomposition within AndroidBLEDriver
- Connection parameter negotiation (connection interval, slave latency)

</decisions>

<specifics>
## Specific Ideas

- Hardware UUID scan filter is preferred for efficiency, but note OEM quirks may require software fallback in Phase 22
- The internal + facade pattern means rns-sample-app only sees AndroidBLEDriver — all BLE complexity is encapsulated in the rns-android library
- Scan should keep running even at max connections so Phase 22 can score and potentially replace weaker peers

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 20-gatt-client-scanner*
*Context gathered: 2026-02-06*
