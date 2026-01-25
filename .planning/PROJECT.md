# Reticulum-KT: LXMF Interoperability

## What This Is

A Kotlin implementation of the Reticulum network stack and LXMF messaging protocol, optimized for Android. The goal is to provide a production-ready alternative to Python Reticulum for mobile LXMF applications, starting with eventual integration into Columba (an existing LXMF messaging app currently using Python Reticulum).

## Core Value

**Perfect byte-level interoperability with Python LXMF** — Kotlin clients can send and receive LXMF messages (including files and images) with any Python LXMF client, and use Python-hosted propagation nodes for message relay.

## Current State (v1 Shipped)

**Shipped:** 2026-01-24

v1 delivers complete LXMF interoperability:
- 120+ interop tests verify byte-level compatibility with Python LXMF
- All 3 delivery methods: DIRECT, OPPORTUNISTIC, PROPAGATED
- Large message support via Resource transfer with BZ2 compression
- Stamp generation for propagation node submission
- 31,126 LOC (Kotlin + Python bridge)

Ready for Columba integration.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

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
- ✓ Transport routing and path management — working

### Active

<!-- Next milestone scope. To be defined in /gsd:new-milestone. -->

(No active requirements — start next milestone with `/gsd:new-milestone`)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Hosting LXMF propagation nodes — User only needs client functionality for Columba
- Serial interface — Not needed for Columba use case
- RNode/LoRa interface — Not needed for Columba use case
- BLE interface — Not needed for Columba use case
- I2P interface — Not needed for Columba use case

**Potential v2 scope (deferred, not blocked):**
- Android battery optimization (Doze mode, WorkManager)
- Auto Interface (mDNS peer discovery)
- IFAC (Interface Authentication)
- Hardware-accelerated crypto

## Context

**Reference Implementations:**
- Python Reticulum: `~/repos/Reticulum`
- Python LXMF: `~/repos/LXMF`

**Testing Infrastructure:**
- Python bridge server: `python-bridge/bridge_server.py` (70+ commands for cross-implementation verification)
- Interop test suite: `lxmf-core/src/test/kotlin/network/reticulum/lxmf/interop/` (120+ tests)
- LXMF test vectors: `lxmf-core/src/test/resources/lxmf_test_vectors.json`

**Codebase Analysis:**
- See `.planning/codebase/` for detailed architecture, stack, and concerns documentation
- Key concern: Android battery/Doze issues flagged but deferred to v2

**Target Integration:**
- Columba: Existing LXMF messaging app using Python Reticulum
- Goal: Replace Python backend with Kotlin implementation

**Milestone History:**
- See `.planning/MILESTONES.md` for shipped versions
- See `.planning/milestones/` for archived roadmaps and requirements

## Constraints

- **Protocol compatibility**: Must produce byte-identical wire format to Python reference
- **No Python at runtime**: Final Android app should have zero Python dependencies
- **JDK**: JDK 21 for development/testing, Android API 26+ for deployment
- **Testing**: All new LXMF interop must use Python bridge for verification

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Interop before Android optimization | Prove correctness first, then optimize | ✓ Good — v1 shipped with full interop |
| Skip propagation node hosting | Client-only scope for Columba | ✓ Good — client-only verified |
| Skip Serial/RNode/BLE interfaces | Not needed for TCP/UDP mesh connectivity | ✓ Good — TCP/UDP sufficient |
| Use existing Python bridge pattern | Proven effective for RNS interop | ✓ Excellent — 120+ tests verify interop |
| Raw hashes in LXMF bridge | Avoids RNS initialization complexity | ✓ Good — simplified testing |
| forRetrieval parameter in establishPropagationLink | Differentiate delivery vs retrieval callbacks | ✓ Good — fixed propagation delivery |
| 64-byte Resource proof format | Match Python protocol exactly | ✓ Good — fixed Resource transfer |
| RESOURCE_PRF routing to activeLinks | Not reverse_table (per Python reference) | ✓ Good — fixed proof delivery |

---
*Last updated: 2026-01-24 after v1 milestone*
