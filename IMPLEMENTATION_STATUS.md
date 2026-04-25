# Reticulum-KT Implementation Status

**Last Updated**: 2026-04-24
**Version**: 0.1.0-SNAPSHOT

## Executive Summary

The Kotlin implementation of Reticulum is **feature-complete** for the core protocol and fully interoperable with the Python reference implementation. Every interface type Python ships is implemented (TCP, UDP, Local, RNode, Auto, I2P, Pipe) except the three legacy serial-TNC paths (`SerialInterface`, `KISSInterface`, `AX25KISSInterface`) — all covered for typical use by `RNodeInterface` — plus four mobile/JVM-specific additions (BLE Mesh, Nearby Connections, Bluetooth SPP, Pipe). The Android module (`rns-android/`) provides foreground service, BLE driver, and power management components.

LXMF has been extracted to a separate repository: [LXMF-kt](https://github.com/torlando-tech/LXMF-kt).

---

## Core Protocol Completeness

### Fully Implemented (100%)

#### Transport Layer
- **Path management**: State machine (ACTIVE → UNRESPONSIVE → STALE)
- **Receipt management**: Timeout tracking, MAX_RECEIPTS culling
- **Announce queuing**: Per-interface queues with bandwidth limiting
- **Tunnel support**: Full synthesis, persistence, path restoration
- **Packet routing**: Forwarding, deduplication, hashlist management
- **Link table**: Active link routing for transport nodes
- **IFAC**: Interface Authentication Code — masking, signature validation, per-interface keys

#### Cryptography
- **X25519**: Key exchange and encryption
- **Ed25519**: Signing and verification
- **HKDF**: Key derivation
- **AES-128/256**: Packet and link encryption
- **Ratchet**: Forward secrecy implementation

#### Higher-Level Features
- **Link establishment**: Full handshake (initiator and receiver)
- **Resource transfers**: Chunked transfers with compression
- **Channel messaging**: Reliable ordered delivery with windowed flow control
- **Buffer**: Stream I/O over channels

#### Interfaces
- **TCP**: Client and server with HDLC framing, exponential backoff reconnect
- **UDP**: Unicast, broadcast, multicast
- **Local**: Server/client IPC for sharing Reticulum across apps
- **RNode (LoRa)**: Full KISS protocol, firmware checking, BLE + serial transport
- **BLE Mesh**: Dual-role GATT, identity handshake, fragmentation, Android driver
- **Nearby Connections**: Kotlin-only — Google Nearby Connections (WiFi Direct + BLE), dual-role advertise/discover
- **Auto**: IPv6 multicast peer discovery, per-peer UDP connections
- **I2P**: SAM API tunnels with HDLC-framed TCP through localhost, server tunnel + client tunnels to configured peers
- **Bluetooth SPP**: Bluetooth Classic RFCOMM with HDLC framing, client + server modes, automatic reconnect
- **Pipe**: HDLC over arbitrary byte streams (subprocess pipes, FIFOs, in-process testing) — Python-parity port

#### Interface Discovery
- **InterfaceAnnouncer**: Periodic discovery announces with PoW stamps
- **InterfaceAnnounceHandler**: Incoming discovery processing with self-filtering
- **InterfaceDiscovery**: Persistence, status tracking (available/unknown/stale), auto-connect

#### Android (`rns-android/`)
- **Foreground Service**: `ReticulumService` with lifecycle management and notification
- **BLE Driver**: GATT server/client, advertising, scanning (API 26+)
- **Power Management**: Doze handler, battery monitor/stats/exemption, network monitor

#### Testing
- **660+ test methods** across 86 test files, 100% passing with Python implementation
- **15 in-repo conformance test files** in `python-bridge/conformance/` (65 test methods; Kotlin ↔ Python over pipe interfaces)
- **22 cross-implementation test files** in [`torlando-tech/reticulum-conformance`](https://github.com/torlando-tech/reticulum-conformance) — wire-level and behavioral parity tests parametrized across `(sender, transport, receiver)` impl triples covering byte-level identity, transport routing, link multi-hop, resource transfer, IFAC interop, path discovery, and announce semantics
- **Integration tests**: Links, resources, channels, tunnels, IFAC verified
- **Python interop**: Kotlin ↔ Python communication tested across all protocol features

#### CLI
- **rnsd-kt**: Complete daemon matching Python `rnsd` behavior

### Not Yet Implemented

| Feature | Priority | Description |
|---------|----------|-------------|
| Blackhole system | Medium | Identity blacklisting to block bad actors |
| Remote management | Low | Control destinations for remote `/path` and `/status` queries |
| RPC server | Low | Multi-process sharing of a single Reticulum instance |
| CLI utilities (rnstatus, rnpath, rnprobe) | Low | Network diagnostic tools |
| SerialInterface | Low | Direct serial port (RNode covers most use cases) |
| KISSInterface | Low | Legacy serial TNC path (RNode covers most use cases) |
| AX25KISSInterface | Low | AX.25 over KISS — specialized amateur-radio path |

---

## Android Battery & Performance Notes

The core protocol was originally designed as a JVM library. Running as a background Android service introduces battery and performance considerations:

### With Transport Routing Enabled
- Transport job loop wakes every 250ms — significant battery impact
- Per-link watchdog threads add overhead with multiple active links
- Blocking I/O threads on TCP/UDP interfaces

### Recommended: Client-Only Mode

```kotlin
Reticulum.start(
    configDir = configPath,
    enableTransport = false  // Client-only mode
)
```

Client-only mode disables routing/forwarding and eliminates the job loop, reducing battery impact by 70-80% while retaining all messaging, link, resource, and channel capabilities.

### Production Optimization Opportunities
- Migrate job loop to WorkManager (15min+ intervals) or event-driven architecture
- Consolidate per-link watchdogs to a single shared timer
- Migrate blocking I/O to NIO channels or coroutines
- Reduce `HASHLIST_MAXSIZE` for mobile (currently 1,000,000)
- Use Android Cipher API for AES hardware acceleration

---

## Feature Comparison: Python vs Kotlin

| Feature | Python | Kotlin | Notes |
|---------|--------|--------|-------|
| Core Transport | ✅ | ✅ | 100% compatible |
| Path Management | ✅ | ✅ | State machine complete |
| Tunnels | ✅ | ✅ | Full persistence |
| Links | ✅ | ✅ | Both directions |
| Resources | ✅ | ✅ | With compression |
| Channels | ✅ | ✅ | Reliable delivery |
| Ratchets | ✅ | ✅ | Forward secrecy |
| IFAC | ✅ | ✅ | Interface authentication |
| Interface Discovery | ✅ | ✅ | Announcer, handler, persistence |
| TCP Interface | ✅ | ✅ | Client and server |
| UDP Interface | ✅ | ✅ | Unicast, broadcast, multicast |
| Local Interface | ✅ | ✅ | Shared instance IPC |
| RNode Interface | ✅ | ✅ | KISS protocol, BLE + serial |
| BLE Mesh | ❌ | ✅ | Kotlin-only, dual-role GATT |
| Nearby Connections | ❌ | ✅ | Kotlin-only, Google Nearby Connections (WiFi Direct + BLE) |
| Auto Interface | ✅ | ✅ | IPv6 multicast discovery |
| I2P Interface | ✅ | ✅ | SAM API tunnels, server + client |
| Bluetooth SPP | ❌ | ✅ | Kotlin-only, Bluetooth Classic RFCOMM with HDLC |
| Pipe Interface | ✅ | ✅ | Python-parity port; HDLC over arbitrary byte streams (subprocess pipes, FIFOs, in-process testing) |
| Blackhole | ✅ | ❌ | Identity blacklisting |
| Remote Mgmt | ✅ | ❌ | Status/path endpoints |
| Serial Interface | ✅ | ❌ | Legacy direct-serial path; RNode covers most use cases |
| KISS Interface | ✅ | ❌ | Legacy serial TNC path; RNode covers most use cases |
| AX.25 KISS Interface | ✅ | ❌ | Legacy AX.25 over KISS; specialized amateur-radio path |
| CLI Utilities | ✅ | Partial | rnsd-kt complete; rnstatus/rnpath/rnprobe not started |

**Result**: Kotlin achieves 100% core protocol compatibility and implements every interface type Python ships except the three legacy serial-TNC paths (`SerialInterface`, `KISSInterface`, `AX25KISSInterface`) — all covered for typical use by `RNodeInterface` — plus four mobile/JVM-specific additions (BLE Mesh, Nearby Connections, Bluetooth SPP, Pipe). Remaining gaps are optional features (blackhole, remote management) and a few CLI diagnostic utilities.

---

## File Reference

### Core Implementation
- `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt` — Main transport layer
- `rns-core/src/main/kotlin/network/reticulum/transport/Tables.kt` — Data structures
- `rns-core/src/main/kotlin/network/reticulum/link/Link.kt` — Link management
- `rns-core/src/main/kotlin/network/reticulum/packet/` — Packet handling
- `rns-core/src/main/kotlin/network/reticulum/crypto/` — Cryptography
- `rns-core/src/main/kotlin/network/reticulum/discovery/` — Interface discovery

### Interfaces
- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/` — All interface types

### Android
- `rns-android/src/main/kotlin/network/reticulum/android/` — Service, BLE driver, power management

### CLI
- `rns-cli/src/main/kotlin/network/reticulum/cli/` — rnsd-kt daemon
