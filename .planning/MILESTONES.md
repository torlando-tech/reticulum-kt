# Project Milestones: Reticulum-KT

## v2 Android Production Readiness (Shipped: 2026-02-06)

**Delivered:** Production-ready Android background connectivity for TCP/UDP interfaces — always-connected foreground service that survives Doze mode, network transitions, and battery optimization with intelligent reconnection and user-facing status.

**Phases completed:** 10-15 (22 plans total)
**Phases deferred:** 16 (OEM Compatibility), 17 (Memory Optimization) — deprioritized in favor of BLE interface work

**Key accomplishments:**

- Foreground service with connectedDevice type and persistent notification
- Doze/network/battery state observers with StateFlow-based reactive APIs
- Lifecycle-aware coroutine scope injection (service → interfaces)
- Doze-aware connection management with exponential backoff
- WorkManager integration for Doze-surviving periodic maintenance
- Service notification UX with quick actions (reconnect, pause/resume)
- Battery optimization UX with usage statistics, chart, and exemption flow
- Boot receiver for auto-restart across reboots
- RNode/LoRa interface via BLE (added during milestone)

**Stats:**

- 6 phases, 22 plans
- Average plan duration: 2.4 minutes
- Total execution time: ~58 minutes

**Git range:** `docs(10)` → `fix: disable flow control and serialize TX for BLE RNode connections`

**What's next:** BLE mesh interface (v3)

---

## v1 LXMF Interoperability (Shipped: 2026-01-24)

**Delivered:** Perfect byte-level interoperability with Python LXMF — Kotlin clients can send and receive LXMF messages (including files and images) with any Python LXMF client, and use Python-hosted propagation nodes for message relay.

**Phases completed:** 1-9 + 8.1 (25 plans total)

**Key accomplishments:**

- Python bridge infrastructure with 6 LXMF commands for byte-level testing
- Bidirectional message serialization (LXMessage pack/unpack) verified K↔P
- Field support: attachments, images, custom fields survive round-trip
- Cryptographic interop: hash and signature validation across implementations
- Stamp generation for proof-of-work with propagation nodes
- All 3 delivery methods: DIRECT, OPPORTUNISTIC, PROPAGATED
- Resource transfer for large messages (>319 bytes) with BZ2 compression

**Stats:**

- 116 files created/modified
- 31,126 lines of Kotlin/Python
- 10 phases, 25 plans, 112+ tests
- 2 days from start to ship (2026-01-23 → 2026-01-24)

**Git range:** `docs(phase-01)` → `docs(09)`

**What's next:** Android production readiness (v2)

---

*Created: 2026-01-24*
*Updated: 2026-02-06 — v2 shipped, v3 BLE milestone started*
