# Reticulum-KT: LXMF Interoperability

## What This Is

A Kotlin implementation of the Reticulum network stack and LXMF messaging protocol, optimized for Android. The goal is to provide a production-ready alternative to Python Reticulum for mobile LXMF applications, starting with eventual integration into Columba (an existing LXMF messaging app currently using Python Reticulum).

## Core Value

**Perfect byte-level interoperability with Python LXMF** — Kotlin clients can send and receive LXMF messages (including files and images) with any Python LXMF client, and use Python-hosted propagation nodes for message relay.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. Inferred from existing codebase. -->

- ✓ X25519/Ed25519 cryptography — verified via Python bridge (70+ commands)
- ✓ HKDF key derivation — verified via Python bridge
- ✓ AES-256-CBC encryption — verified via Python bridge
- ✓ Identity creation and management — verified via Python bridge
- ✓ Destination hashing and routing — verified via Python bridge
- ✓ Packet wire format encoding/decoding — verified via Python bridge
- ✓ Link establishment and encryption — verified via Python bridge
- ✓ Ratchet encryption — verified via Python bridge
- ✓ Announce pack/unpack — verified via Python bridge
- ✓ Channel envelope/stream messaging — verified via Python bridge
- ✓ Resource advertisement serialization — verified via Python bridge
- ✓ HDLC/KISS framing — verified via Python bridge
- ✓ TCP client/server interfaces — working
- ✓ UDP interface (unicast, broadcast, multicast) — working
- ✓ Local/shared instance interface — working
- ✓ Transport routing and path management — working (basic)
- ✓ LXMessage encoding/decoding — Python→Kotlin verified via test vectors

### Active

<!-- Current scope. Building toward these for this milestone. -->

- [ ] LXMF message round-trip (Kotlin→Python→Kotlin) — prove Kotlin-packed messages unpack in Python
- [ ] LXMF file attachment interop — FIELD_FILE_ATTACHMENTS survives round-trip
- [ ] LXMF image attachment interop — FIELD_IMAGE survives round-trip
- [ ] LXMF stamp generation and validation — stamps accepted by Python propagation nodes
- [ ] End-to-end DIRECT delivery — Kotlin client ↔ Python client via Link
- [ ] End-to-end OPPORTUNISTIC delivery — Kotlin sends when path available
- [ ] End-to-end PROPAGATED delivery — Kotlin → Python propagation node → Python recipient
- [ ] Large message Resource transfer — messages >500 bytes transfer correctly

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Hosting LXMF propagation nodes — User only needs client functionality for Columba
- Android battery optimization — Deferred until interop verified; no point optimizing broken code
- Serial interface — Not needed for Columba use case
- RNode/LoRa interface — Not needed for Columba use case
- BLE interface — Not needed for Columba use case
- I2P interface — Not needed for Columba use case
- Auto Interface (mDNS) — Not needed for Columba use case (connects to known nodes)
- IFAC (Interface Authentication) — Nice-to-have, not blocking

## Context

**Reference Implementations:**
- Python Reticulum: `~/repos/Reticulum`
- Python LXMF: `~/repos/LXMF`

**Testing Infrastructure:**
- Python bridge server: `python-bridge/bridge_server.py` (70+ commands for cross-implementation verification)
- Interop test suite: `rns-test/src/test/kotlin/network/reticulum/interop/`
- LXMF test vectors: `lxmf-core/src/test/resources/lxmf_test_vectors.json`

**Codebase Analysis:**
- See `.planning/codebase/` for detailed architecture, stack, and concerns documentation
- Key concern: Android battery/Doze issues flagged but deferred

**Target Integration:**
- Columba: Existing LXMF messaging app using Python Reticulum
- Goal: Replace Python backend with Kotlin implementation

## Constraints

- **Protocol compatibility**: Must produce byte-identical wire format to Python reference
- **No Python at runtime**: Final Android app should have zero Python dependencies
- **JDK**: JDK 21 for development/testing, Android API 26+ for deployment
- **Testing**: All new LXMF interop must use Python bridge for verification

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Interop before Android optimization | Prove correctness first, then optimize | — Pending |
| Skip propagation node hosting | Client-only scope for Columba | — Pending |
| Skip Serial/RNode/BLE interfaces | Not needed for TCP/UDP mesh connectivity | — Pending |
| Use existing Python bridge pattern | Proven effective for RNS interop (70+ commands) | ✓ Good |

---
*Last updated: 2026-01-23 after initialization*
