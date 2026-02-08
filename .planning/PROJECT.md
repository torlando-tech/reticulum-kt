# Reticulum-KT: BLE Interface

## What This Is

A Kotlin implementation of the Reticulum network stack and LXMF messaging protocol, optimized for Android. The goal is to provide a production-ready alternative to Python Reticulum for mobile LXMF applications, starting with eventual integration into Columba (an existing LXMF messaging app currently using Python Reticulum).

## Core Value

**BLE mesh networking** — Kotlin implementation of the BLE Interface protocol for peer-to-peer Reticulum over Bluetooth Low Energy, enabling off-grid mesh communication between Android devices without internet connectivity.

## Current Milestone: v3 BLE Interface

**Goal:** Port the BLE Interface protocol from Python (ble-reticulum + columba repos) to Kotlin, implementing the full peer-to-peer BLE mesh stack including identity handshake, MAC sorting, fragmentation/reassembly, MTU negotiation, and deduplication.

**Source repos:**
- `~/repos/public/ble-reticulum` — Python BLE interface implementation (BLEInterface.py, peer interface, driver)
- `~/repos/public/columba*` — Extended BLE interface with documentation and Android integration

**Target features:**
- BLE peer discovery and connection management
- Identity handshake protocol (cryptographic peer verification)
- MAC address sorting for deterministic master/slave role assignment
- MTU negotiation between BLE peers
- Packet fragmentation (large Reticulum packets → BLE-sized chunks)
- Packet reassembly (BLE chunks → complete Reticulum packets)
- Deduplication layer (toggleable — prevents duplicate packet processing)
- Android BLE driver abstraction (GATT client/server, advertising)

**Existing code to reuse:**
- `BluetoothLeConnection.kt` — GATT client, NUS service, MTU negotiation (from RNode work)
- `BleDevicePicker.kt` — BLE scanning UI with permissions
- `InterfaceManager.kt` — BLE lifecycle management patterns

## Previous Milestones

### v2 Android Production Readiness (Shipped 2026-02-06)

v2 delivered production-ready Android background connectivity:
- Foreground service with Doze/network/battery observers
- Lifecycle-aware coroutine scope injection
- Doze-aware connection management with exponential backoff
- WorkManager for periodic maintenance
- Service notification UX with quick actions
- Battery optimization UX with stats and exemption flow
- RNode/LoRa BLE interface (bonus)
- 6 phases, 22 plans completed; Phases 16-17 deferred

### v1 LXMF Interoperability (Shipped 2026-01-24)

v1 delivered complete LXMF interoperability:
- 120+ interop tests verify byte-level compatibility with Python LXMF
- All 3 delivery methods: DIRECT, OPPORTUNISTIC, PROPAGATED
- Large message support via Resource transfer with BZ2 compression
- Stamp generation for propagation node submission
- 31,126 LOC (Kotlin + Python bridge)

See `.planning/MILESTONES.md` for full details.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

**v2 Android Production Readiness (2026-02-06):**
- ✓ Foreground service with persistent connection — v2
- ✓ Doze mode handling with state observers — v2
- ✓ Battery optimization exemption flow — v2
- ✓ WorkManager for background scheduling — v2
- ✓ Lifecycle-aware coroutine management — v2
- ✓ Service notification UX — v2

**v1 LXMF Interoperability (2026-01-24):**
- ✓ LXMF message round-trip (Kotlin↔Python) — v1
- ✓ LXMF file attachment interop — v1
- ✓ LXMF image attachment interop — v1
- ✓ LXMF stamp generation and validation — v1
- ✓ End-to-end DIRECT delivery — v1
- ✓ End-to-end OPPORTUNISTIC delivery — v1
- ✓ End-to-end PROPAGATED delivery — v1
- ✓ Large message Resource transfer — v1
- ✓ Message hash computed identically — v1
- ✓ Message signature validates across implementations — v1

**Pre-v1 (RNS Foundation):**
- ✓ X25519/Ed25519 cryptography — verified via Python bridge
- ✓ HDLC/KISS framing — verified via Python bridge
- ✓ TCP client/server interfaces — working
- ✓ UDP interface — working
- ✓ Transport routing and path management — working
- ✓ RNode/LoRa interface via BLE — working

### Active

<!-- Current scope. Building toward these for v3. -->

*To be defined after research phase.*

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Hosting LXMF propagation nodes — User only needs client functionality for Columba
- I2P interface — Not needed for Columba use case
- iOS BLE interface — Android-first; iOS has different BLE APIs
- BLE Classic (BR/EDR) — BLE only; Classic Bluetooth not used by protocol

**Deferred from v2 (not blocked, lower priority):**
- OEM compatibility (Samsung, Xiaomi, Huawei) — Phase 16
- Memory optimization (<50MB peak) — Phase 17

## Context

**Reference Implementations:**
- Python Reticulum: `~/repos/Reticulum`
- Python LXMF: `~/repos/LXMF`
- Python BLE Interface: `~/repos/public/ble-reticulum`
- Columba BLE Interface (extended): `~/repos/public/columba*`

**Existing BLE Code (Kotlin):**
- `rns-sample-app/.../service/BluetoothLeConnection.kt` — GATT client for NUS (from RNode)
- `rns-sample-app/.../ui/screens/wizard/BleDevicePicker.kt` — BLE scanning UI
- `rns-sample-app/.../service/InterfaceManager.kt` — BLE interface lifecycle

**Testing Infrastructure:**
- Python bridge server: `python-bridge/bridge_server.py` (70+ commands)
- Interop test suite: `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/`

**Codebase Analysis:**
- See `.planning/codebase/` for architecture documentation
- Key module: `rns-interfaces` for new BLE interface code

**Target Integration:**
- Columba: Existing LXMF messaging app using Python Reticulum
- Goal: Replace Python BLE backend with Kotlin implementation

**Milestone History:**
- See `.planning/MILESTONES.md` for shipped versions
- See `.planning/milestones/` for archived roadmaps and requirements

## Constraints

- **Protocol compatibility**: Must produce byte-identical wire format to Python BLE Interface reference
- **No Python at runtime**: Final Android app should have zero Python dependencies
- **JDK**: JDK 21 for development/testing, Android API 26+ for deployment
- **BLE limitations**: ~512 byte MTU max (negotiated), ~20 byte default, requires fragmentation for Reticulum packets
- **Android BLE stack quirks**: Serialize writes, handle 133 errors, MTU negotiation timing

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Interop before Android optimization | Prove correctness first, then optimize | ✓ Good — v1 shipped with full interop |
| Skip propagation node hosting | Client-only scope for Columba | ✓ Good — client-only verified |
| Use existing Python bridge pattern | Proven effective for RNS interop | ✓ Excellent — 120+ tests verify interop |
| flowControl=false for BLE RNode | RNode firmware doesn't send CMD_READY over BLE | ✓ Good — fixed TX deadlock |
| txLock serialization for BLE TX | Android BLE drops concurrent writeCharacteristic | ✓ Good — fixed write failures |
| Defer v2 Phases 16-17 | BLE interface higher priority than OEM/memory polish | Pending |
| Reuse BluetoothLeConnection patterns | Proven GATT client code from RNode work | Pending |

---
*Last updated: 2026-02-06 after starting v3 BLE Interface milestone*
