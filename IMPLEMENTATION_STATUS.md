# Reticulum-KT Implementation Status

**Last Updated**: 2026-09-23
**Reference**: Python RNS 1.5.2

## Executive Summary

The Kotlin implementation of Reticulum is feature-complete for the core protocol and
interoperable with the Python reference: the full reticulum-conformance suite passes
against RNS 1.5.2 with the Kotlin bridge in every arm. Every interface type Python ships
is implemented except `WeaveInterface` and `RNodeMultiInterface` (hardware drivers with
no device to verify against), plus four mobile/JVM-specific additions (BLE Mesh, Nearby
Connections, Bluetooth SPP, Pipe) and three Android backends for KISS TNCs. The Android
module (`rns-android/`) provides the foreground service, BLE driver, KISS transports and
power management.

LXMF lives in a separate repository: [LXMF-kt](https://github.com/torlando-tech/LXMF-kt).

---

## Core Protocol Completeness

### Fully Implemented

#### Transport Layer
- **Inbound processing**: asynchronous, with per-traffic-class queues (data, announce,
  path request, ingress-limited) and a single drainer, as RNS 1.5.2
- **Ingress and egress control**: announce and path-request ingress limiting with
  per-interface `ic_*` tuning, held-announce release, path-request egress control,
  protocol-violation counters
- **Path management**: state machine (ACTIVE → UNRESPONSIVE → STALE), expiry by interface
  mode, path-request gates and discovery timeouts, waiting discovery requests answered on
  announce arrival, `MODE_INTERNAL` with the `announces_from/to_internal` knobs
- **Alternate paths**: the rows the reference discards when a newer announce replaces a path
  are kept per destination, so a destination heard over several interfaces has more than one
  recorded way through. A path that fails is marked rather than dropped when another way is
  held, and dropped outright when it is the only one, which is the reference's behaviour.
  Alternates are pruned with the destination they belong to. See `port-deviations.md`
- **Announces**: rebroadcast with the PATHFINDER retransmit machinery, announce cap and
  announce-rate limiter, replay to late-joining local clients
- **Receipt management**: timeout tracking, MAX_RECEIPTS culling
- **Tunnel support**: synthesis, persistence, path restoration, eight-hour lifetime
- **Packet routing**: forwarding, deduplication, hashlist management, link-MTU signalling
  clamped per hop
- **Link table**: active link routing for transport nodes, proof timeout with the
  outbound interface's term
- **IFAC**: masking, signature validation, per-interface keys, on every interface type
  the CLI constructs
- **Management**: probe and remote-management destinations answering `/path` and
  `/status`, announced every two hours; blackhole table with expiry and persistence

#### Cryptography
- **X25519**, **Ed25519**, **HKDF**, **AES-256-CBC** (BouncyCastle)
- **Ratchets**: one per destination, persisted, forward secrecy; peer ratchets consulted
  on encrypt
- **Crypto warm-up** at start so a fresh JVM's first handshake does not skew its RTT

#### Higher-Level Features
- **Link establishment**: full handshake both ways; the initiator waits
  first-hop timeout + 6 s per hop, the responder 6 s per hop + keepalive
- **Link requests and responses**: any msgpack value as request data or response,
  span-based decoding (no value tree is built from remote bytes), request budget with
  the reference's DELIVERED/RECEIVING semantics, pending requests failed at link close
- **Resource transfers**: chunked, BZ2 with a decompression ceiling, segmented above
  1 MiB and joined on the receive side with bounded, link-scoped accumulators, the
  reference's four-branch watchdog (advertise retry, rate-derived part timeout, sender
  wait, proof re-query), metadata, REJECTED distinct from FAILED
- **Channel messaging**: reliable ordered delivery with windowed flow control, RTT-based
  retry timing, five tries then teardown
- **Buffer**: stream I/O over channels, partial writes re-driven

#### Interfaces
- **TCP**: client and server, HDLC framing, fixed five-second reconnect, keepalive on
  server children, optional SOCKS5 proxy, IFAC
- **Backbone**: NIO selector listener, IFAC with configurable tag size, 1024-client cap,
  bounded HDLC deframer, coalescing transmit buffer
- **UDP**: unicast, broadcast, multicast
- **Local**: shared-instance server and client IPC, bounded deframer, accept loop that
  survives transient errors
- **RNode (LoRa)**: KISS protocol, firmware checking, BLE and serial transport,
  bitrate-derived frame-duration ceiling
- **KISS** and **AX.25 over KISS**: KISS TNC over any byte stream, frame-duration ceiling,
  flow control, beacon and TNC configuration commands, IFAC; AX.25 callsign/SSID header
- **Serial**: HDLC over a serial stream (`SerialInterface`), any `KissSerialPort`
- **BLE KISS**: Nordic UART Service and BLE KISS TNC Service profiles by auto-detection,
  bounded fragment reassembly
- **BLE Mesh**: dual-role GATT, identity handshake, fragmentation, Android driver
- **Bluetooth SPP**: Bluetooth Classic RFCOMM with HDLC framing, client and server
- **Auto**: IPv6 multicast peer discovery, per-peer UDP connections, adaptive announce
  interval (see `PYTHON_DEVIATIONS.md`)
- **I2P**: SAM API tunnels with HDLC-framed TCP, server tunnel and client tunnels
- **Pipe**: HDLC over arbitrary byte streams
- **Nearby Connections**: Kotlin-only; present, scheduled for removal

#### Interface Discovery
- **InterfaceAnnouncer**: periodic discovery announces with PoW stamps
- **InterfaceAnnounceHandler**: incoming discovery processing with self-filtering
- **InterfaceDiscovery**: persistence, status tracking, auto-connect

#### Android (`rns-android/`)
- **Foreground Service**: `ReticulumService` with lifecycle management and notification
- **BLE Driver**: GATT server/client, advertising, scanning (API 26+)
- **KISS backends**: `SppKissSerialPort` (RFCOMM), `UsbKissSerialPort` (usb-serial-for-android,
  per-chip drivers), `AndroidNusLink` (GATT Nordic UART), wired by `AndroidKissInterfaces`
- **Storage**: Room-backed identity, path, announce, tunnel and discovery stores with
  transient-lock retry on every write-through path
- **Power Management**: Doze handler, battery monitor/stats/exemption, network monitor

#### CLI
- **rnsd-kt**: daemon matching Python `rnsd`; config parser with ConfigObj quoting and
  inline comments; interface factory for TCP client/server, Backbone, Auto, Serial,
  KISS and AX.25 KISS with IFAC, mode, announce-cap, announce-rate and
  ingress/egress knobs applied from config
- **RPC server**: in `rns-core`, the Python `multiprocessing.connection` handshake,
  msgpack payloads, the full RNS 1.5.2 request set, bound to localhost, key derived
  from the transport identity

#### Testing
- **Unit suites**: rns-core, rns-interfaces, rns-cli, rns-android
- **Interop**: `rns-test` against the Python bridge (`python-bridge/bridge_server.py`,
  150+ commands), including a slow-link rig that shapes RTT and bitrate so timing
  defects surface on loopback
- **Conformance**: `conformance-bridge` runs the
  [reticulum-conformance](https://github.com/torlando-tech/reticulum-conformance)
  suite; full run 1308 passed, 0 failed, 12 skipped, 1 expected failure against
  RNS 1.5.2

### Not Yet Implemented

| Feature | Priority | Description |
|---------|----------|-------------|
| `WeaveInterface` | Low | WDCL discovery and handshake to a Weave switch over USB serial; no device to verify against |
| `RNodeMultiInterface` | Low | Multi-radio RNode firmware with sub-interfaces; no device to verify against |
| RNode, UDP, I2P, BLE from CLI config | Low | `InterfaceConfigFactory` warns and returns null for these; desktop has no BLE backend, the others are unwired |
| Bounded resource worker | Medium | Resource assembly and request handling run on the ingest thread; Python uses daemon threads |
| Shared-medium interface hints, stream `Resource` init, auto-MTU tuning | Medium | Three reference behaviours not yet ported |
| TCP server client cap | Low | The 64-slot cap is a constructor default with no config key |
| CLI utilities (rnstatus, rnpath, rnprobe) | Low | Diagnostic tools; the RPC server answers the requests they would make |

---

## Android Battery & Performance Notes

The core protocol was designed as a JVM library. Running as a background Android
service introduces battery and performance considerations:

### With Transport Routing Enabled
- Transport job loop wakes every 250 ms
- Per-link watchdog threads add overhead with multiple active links
- Blocking I/O threads on TCP/UDP interfaces

### Recommended: Client-Only Mode

```kotlin
Reticulum.start(
    configDir = configPath,
    enableTransport = false  // Client-only mode
)
```

Client-only mode disables routing and forwarding and eliminates the job loop while
retaining all messaging, link, resource and channel capabilities.

### Optimizations in place
- Leveled `RnsLog` with lazy message construction on hot paths
- Announce-path allocations reduced by about two thirds; an O(1) local-destination index
- One decrypt per packet; `Packet.getHashablePart` in a single allocation
- Byte-ring `Channel`/`Buffer`
- Adaptive AutoInterface announce interval with Doze awareness

### Remaining opportunities
- Migrate the job loop to WorkManager or an event-driven design
- Consolidate per-link watchdogs to a shared timer
- Migrate blocking I/O to NIO channels or coroutines
- Use the Android Cipher API for AES hardware acceleration

---

## Feature Comparison: Python vs Kotlin

| Feature | Python | Kotlin | Notes |
|---------|--------|--------|-------|
| Core Transport | ✅ | ✅ | async inbound with traffic classes, ingress/egress control |
| Path Management | ✅ | ✅ | state machine, mode-based expiry, discovery answers |
| Tunnels | ✅ | ✅ | persistence, eight-hour lifetime |
| Links | ✅ | ✅ | both directions, request values, request budget semantics |
| Resources | ✅ | ✅ | compression, segmentation, four-branch watchdog |
| Channels | ✅ | ✅ | reliable delivery |
| Ratchets | ✅ | ✅ | forward secrecy, persisted |
| IFAC | ✅ | ✅ | every CLI-constructed interface |
| Interface Discovery | ✅ | ✅ | announcer, handler, persistence |
| Blackhole | ✅ | ✅ | expiry, trusted sources, persistence |
| Remote management | ✅ | ✅ | `/path` and `/status`, announced |
| RPC server | ✅ | ✅ | msgpack, full 1.5.2 request set |
| TCP Interface | ✅ | ✅ | client and server, SOCKS5 |
| Backbone Interface | ✅ | ✅ | IFAC, bounded deframer |
| UDP Interface | ✅ | ✅ | unicast, broadcast, multicast |
| Local Interface | ✅ | ✅ | shared instance IPC |
| RNode Interface | ✅ | ✅ | KISS protocol, BLE + serial |
| KISS Interface | ✅ | ✅ | any byte stream, IFAC |
| AX.25 KISS Interface | ✅ | ✅ | callsign/SSID header |
| Serial Interface | ✅ | ✅ | HDLC over serial |
| Auto Interface | ✅ | ✅ | IPv6 multicast discovery |
| I2P Interface | ✅ | ✅ | SAM API tunnels |
| Pipe Interface | ✅ | ✅ | HDLC over byte streams |
| BLE Mesh | ❌ | ✅ | Kotlin-only, dual-role GATT |
| BLE KISS | ❌ | ✅ | Kotlin-only, NUS and BLE KISS TNC Service |
| Bluetooth SPP | ❌ | ✅ | Kotlin-only, RFCOMM with HDLC |
| Nearby Connections | ❌ | ✅ | Kotlin-only, scheduled for removal |
| Weave Interface | ✅ | ❌ | hardware driver, not started |
| RNodeMulti Interface | ✅ | ❌ | hardware driver, not started |
| CLI Utilities | ✅ | Partial | rnsd-kt complete; rnstatus/rnpath/rnprobe not started |

---

## File Reference

### Core Implementation
- `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt` — transport layer
- `rns-core/src/main/kotlin/network/reticulum/transport/InboundQueues.kt` — traffic-class queues
- `rns-core/src/main/kotlin/network/reticulum/transport/Tables.kt` — data structures
- `rns-core/src/main/kotlin/network/reticulum/link/Link.kt` — link management
- `rns-core/src/main/kotlin/network/reticulum/link/RequestWire.kt` — request/response values
- `rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt` — resource transfers
- `rns-core/src/main/kotlin/network/reticulum/packet/` — packet handling
- `rns-core/src/main/kotlin/network/reticulum/crypto/` — cryptography
- `rns-core/src/main/kotlin/network/reticulum/discovery/` — interface discovery
- `rns-core/src/main/kotlin/network/reticulum/rpc/RpcServer.kt` — RPC server

### Interfaces
- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/` — all interface types

### Android
- `rns-android/src/main/kotlin/network/reticulum/android/` — service, BLE driver, KISS backends, storage, power management

### CLI
- `rns-cli/src/main/kotlin/network/reticulum/cli/` — rnsd-kt daemon, config, interface factory, serial port
