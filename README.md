# reticulum-kt

This repo is an experimental kotlin port of reticulum. It's almost completely AI generated. You probably shouldn't use this.

When I first set out to create this port, development of reticulum had slowed significantly, and was even declared to be mostly done. Since then, development pace has picked up considerably, and as a result there is no longer a long-static version to aim at for parity. I simply haven't had the time I thought I would to close the parity gaps that existed against older versions of RNS, much less to implement the newer features. I will still update this when I can, especially to close known discrepancies against the reference implementation, but it will not be in my top 3 focuses for some considerable time. 

If you'd like to contribute fixes to this repo, please also see the reticulum-conformance suite linked in the action badge below; I have been capturing as much as I can in that suite so that it may someday act as a comprehensive, language-agnostic test suite for any one else foolhardy enough to attempt vibe coding a reticulum port in their language of choice. 

Thanks for reading. The rest of this page is written by the LLM and is probably full of inaccuracies. 

\- Torlando

[![Conformance](https://github.com/torlando-tech/reticulum-kt/actions/workflows/conformance.yml/badge.svg)](https://github.com/torlando-tech/reticulum-kt/actions/workflows/conformance.yml)

A Kotlin/JVM implementation of the [Reticulum Network Stack](https://reticulum.network/) for building resilient, delay-tolerant mesh networks on Android and JVM.

The KISS and radio support, the security hardening, the hot-path work and the
reference-parity work are described under [Additions](#additions). The stack is
driven in production from an Android foreground service talking to KISS TNCs over
BLE, Bluetooth Classic and USB serial.

## Additions

The five themes:

**KISS / TNC radio stack** — `KissInterface` over any byte stream, AX.25 framing
(`kiss/Ax25.kt`), `BleKissInterface` over Nordic UART with automatic detection of
the BLE KISS TNC Service profile, beacon transmit and TNC configuration commands,
and Android backends for GATT (`AndroidNusLink`), Bluetooth Classic RFCOMM and
USB serial with per-chip drivers (FTDI, CP210x, CH34x, CDC, plus a CH34x-as-CDC
override for the TNC4). Verified end to end on an Android emulator with virtual
Bluetooth against an emulated MeshCore TNC.

**Reference parity (RNS 1.5.2)** — asynchronous inbound processing with
per-traffic-class queues and a single drainer, announce and path-request
ingress limiting with per-interface `ic_*` tuning, path-request egress control,
protocol-violation counters, the announce-retransmit machinery and announce-rate
limiter, `MODE_INTERNAL` and the `announces_from/to_internal` knobs, waiting
discovery path requests answered on announce arrival, management and probe
destinations that answer remote `/path` and `/status` queries and announce every
two hours, known-destination use tracking with batched persistence, multi-segment
resources joined on the receive side, link requests and responses carrying any
msgpack value, receipt-free link sends, and a `SerialInterface`. Every timer in
Packet, Link, Resource, Channel and Transport was inventoried against the reference
with the state it checks when it fires; the seven divergences that inventory found
(initiator establishment, the Resource watchdog's
four branches, tunnel and link-table lifetimes, the proof timeout's interface term,
the request budget's start, pending requests at link close) are fixed. A slow-link
interop rig (`rns-test`, RTT and bitrate shaped) and a conformance case for a
response that outlives its request budget guard the timing class of defect. The
`conformance-bridge` module runs the language-agnostic
[reticulum-conformance](https://github.com/torlando-tech/reticulum-conformance)
suite against this port; the full suite passes against RNS 1.5.2.

**Security hardening** — three source-level reviews of `rns-core`,
`rns-interfaces` and `rns-cli` with Python RNS as the oracle. The first
resolved 40-plus findings including five High: `Link.validate()` discarding the
Ed25519 result, link DATA replay through a missing destination-type gate,
HEADER_1 DATA relayed between interfaces without a transport-enabled gate,
`Resource.validateProof` spinning on payloads over 1 MiB, and IFAC silently
disabled on `BackboneInterface`. The second
closed an unbounded receive-side segment accumulator, a never-culled
path-request table, HDLC buffers that never shrank, the keepalive-reply race,
and the ingress-limiter parity gaps, and audited the dependency set and the
publication surface. The third, on the parity work itself, replaced every
recursive msgpack decode on a remote-reachable path with span-based slicing.
Also msgpack allocation bounds, decompression-bomb teardown, bounded deframers,
ratchet persistence and forward secrecy, atomic file replace, and catch-alls in
every stream read loop.

**Correctness fixes** — announces replayed to late-joining local clients,
shared-instance startup race, sender-side `Resource` recovery and in-flight
failure on link teardown, `InterfaceAdapter` cache leak, IN-only destination
registration, SOCKS5 support on `TCPClientInterface`, and an SPP socket leak on
a failed connect or accept.

**Performance** — a leveled `RnsLog` with hot-path log gating, announce-path
allocation down about 66%, an O(1) local-destination index, one decrypt call per
packet, a byte-ring `Channel`/`Buffer`, and `Packet.getHashablePart` in a single
allocation.

Every intentional departure from Python RNS is recorded in
[`port-deviations.md`](port-deviations.md) (the authoritative list) and summarised
in [`PYTHON_DEVIATIONS.md`](PYTHON_DEVIATIONS.md). What is still open against the
reference is listed in [`TODO.md`](TODO.md); the per-component status is in
[`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md).

## Implementation Status

Comparison with [Python RNS](https://github.com/markqvist/Reticulum).
Interoperability is validated by automated tests against the Python reference.

### Core Protocol

| Component | Status | Notes |
|-----------|--------|-------|
| Identity | Complete | X25519/Ed25519, ratchets (one per destination, persisted), known destinations with use tracking and eviction, atomic-replace storage |
| Destination | Complete | All types (SINGLE, GROUP, PLAIN, LINK), request handlers, proof strategies, persisted peer ratchets consulted on encrypt |
| Packet | Complete | Full wire format, HEADER_1/HEADER_2, receipts, proofs, zero-length data rejected |
| Transport | Complete | Routing, path management with alternate paths retained per destination, tunnels, announces, announce caching and retransmit, link management, IFAC, mode-based filtering, blackhole, traffic-class inbound queues, ingress/egress limiting, management and probe destinations |
| Link | Complete | Establishment, encryption, channels, resources, request/response with any msgpack value, MTU discovery, peer-supplied RTT/MTU clamped |
| Channel | Complete | Windowed flow control, ordered delivery, retransmission, message type registry |
| Buffer | Complete | Stream I/O over channels, partial writes re-driven |
| Resource | Complete | Chunked transfer, BZ2 compression with a bomb ceiling, progress on both ends, segmented above 1 MiB and joined on receive, metadata |
| Crypto | Complete | BouncyCastle: X25519, Ed25519, HKDF, AES-256-CBC, SHA-256/512 |
| Blackhole | Complete | Identity blacklisting with expiry, trusted remote sources, persistence, path removal |
| Interface Discovery | Complete | Announcer with PoW stamps, self-filtering handler, persistence and auto-connect |

### Interfaces

| Interface | Status | Notes |
|-----------|--------|-------|
| TCP Server/Client | Complete | HDLC framing, fixed five-second reconnect matching Python, spawned server children registered with Transport so a server can route to a connected client, keepalive on server children, optional SOCKS5 proxy |
| Backbone | Complete | NIO selector listener, IFAC with configurable tag size on the parent and spawned clients, 1024-client cap, bounded HDLC deframer, coalescing transmit buffer |
| UDP | Complete | Unicast, broadcast, multicast |
| Local (Shared Instance) | Complete | Server/client IPC for sharing Reticulum across apps, bounded child deframer, accept loop survives transient errors |
| RNode (LoRa) | Complete | Full KISS protocol, firmware checking, BLE + serial transport, bitrate-derived frame-duration ceiling |
| KISS | Complete | Fork addition — KISS TNC over any byte stream, frame-duration ceiling, flow control, beacon, TNC configuration commands, IFAC |
| AX.25 over KISS | Complete | Fork addition — callsign/SSID validation, 16-byte header, prepend on transmit and strip on receive |
| BLE KISS | Complete | Fork addition — Nordic UART Service and BLE KISS TNC Service profiles by auto-detection, bounded fragment reassembly |
| BLE Mesh | Complete | Dual-role GATT, identity handshake, fragmentation, Android driver — Kotlin-only |
| Bluetooth SPP | Complete | Bluetooth Classic RFCOMM with HDLC framing, client + server, decoupled transmit, 8-byte serial IFAC tag |
| Pipe | Complete | HDLC over arbitrary byte streams (subprocess pipes, FIFOs, in-process testing) — Python-parity |
| Auto (Discovery) | Complete | IPv6 multicast peer discovery, per-peer UDP connections, working multi-interface dedup |
| I2P | Complete | SAM API tunnels with HDLC-framed TCP, server tunnel + client tunnels, spawned peers registered with Transport (inbound peers deregistered on disconnect, outbound kept so a reconnect keeps its route) |
| Nearby Connections | Present, scheduled for removal | Google Nearby Connections (WiFi Direct + BLE) — Kotlin-only. The Android driver pulls in `play-services` for a transport no consumer instantiates; the driver and dependency are slated to go, the abstract seam stays |
| KISS Framing | Complete | Used by TCP, RNode and the KISS interfaces |
| HDLC Framing | Complete | Bounded deframer, used by TCP, Backbone, SPP, Pipe and I2P |
| PHY Stats | Complete | RSSI/SNR exposed via `Interface.rStatRssi` / `rStatSnr` |
| Serial | Complete | Fork addition — Python `SerialInterface` (HDLC over a serial stream) on any `KissSerialPort`; `jSerialComm` backend in the CLI |

### Android

| Component | Status | Notes |
|-----------|--------|-------|
| Foreground Service | Complete | Persistent connection with Doze/battery awareness |
| BLE Driver | Complete | GATT server/client, advertising, scanning (API 26+) |
| NUS Client | Complete | `AndroidNusLink` — GATT Nordic UART client behind the `NusLink` seam, emulator-verified |
| Bluetooth Classic KISS | Complete | `SppKissSerialPort` — RFCOMM behind the `KissSerialPort` seam, unit-tested over pipes |
| USB Serial KISS | Complete | `UsbKissSerialPort` over `usb-serial-for-android`, per-chip drivers |
| KISS Wiring | Complete | `AndroidKissInterfaces` — construct backend and interface, hook receive into Transport, register |
| Power Management | Complete | Doze handler, battery monitor, WorkManager integration |
| Sample App | Moved | See [carina](https://github.com/torlando-tech/carina) for the Compose UI sample app |
| LXMF | Moved | See [LXMF-kt](https://github.com/torlando-tech/LXMF-kt) for the LXMF messaging protocol |

### Remaining Work

| Feature | Priority | Description |
|---------|----------|-------------|
| Shared-medium hints, stream `Resource` init, auto-MTU tuning | Medium | Three reference behaviours not yet ported; see [`TODO.md`](TODO.md) |
| Bounded resource worker | Medium | Resource assembly and request handling run on the ingest thread; Python uses daemon threads |
| `WeaveInterface`, `RNodeMultiInterface` | Low | Hardware drivers (Weave switch protocol, multi-radio RNode firmware); not started, no device to verify against |
| `rnstatus` / `rnpath` / `rnprobe` | Low | CLI equivalents; the RPC server answers the requests they would make |
| CLI interface construction | Low | `InterfaceConfigFactory` builds TCP client/server, Backbone, Auto, Serial, KISS and AX.25 KISS. It warns and returns null for RNode, I2P, UDP and BLE — desktop has no backend for BLE, the rest are simply unwired |
| TCP server client cap | Low | The 64-slot cap is a constructor default with no config key |

### Utilities/CLI

| Tool | Status | Notes |
|------|--------|-------|
| `rnsd-kt` | Complete | Daemon matching Python `rnsd` behavior; config parser (ConfigObj quoting and inline comments) and an interface factory covering TCP client/server, Backbone, Auto, Serial, KISS and AX.25 KISS, with IFAC, mode, announce-cap, announce-rate and ingress/egress knobs applied from config |
| RPC server | Complete | `rns-core/rpc/RpcServer.kt` — Python `multiprocessing.connection` handshake, msgpack payloads, the full RNS 1.5.2 request set (status, path and rate tables, blackhole, drops, identity and destination data), bound to localhost, key derived from the transport identity as in Python |
| `conformance-bridge` | Complete | In-process peer for the reticulum-conformance suite |
| `rnstatus` | Not started | Network status |
| `rnpath` | Not started | Path discovery |
| `rnprobe` | Not started | Ping/latency |

---

## Requirements

- JDK 21+ (Android modules target 17)
- Python 3.8+ with [RNS](https://github.com/markqvist/Reticulum) installed, for the interop tests
- Android API 26+ for Android deployment

## Project Structure

```
rns-core/            # Core protocol (Identity, Destination, Transport, Link, Channel, Resource, discovery, storage)
rns-interfaces/      # Network interfaces (TCP, Backbone, UDP, Local, RNode, KISS, BLE KISS, BLE, SPP, Auto, I2P, Pipe) + framing
rns-android/         # Android code (BLE and NUS drivers, KISS backends, foreground service, power management)
rns-cli/             # CLI utilities (rnsd-kt daemon, config parser, interface factory, serial port)
rns-test/            # Integration and interop tests
python-bridge/       # Python bridge server for interop testing (150+ commands)
conformance-bridge/  # In-process Python conformance sessions
```

## Building

A standard Gradle build with JDK 21 and, for the Android module, an Android SDK
on `ANDROID_HOME`:

```bash
./gradlew build
```

`gradlew` is committed with LF endings. If a Windows checkout has converted it
to CRLF, the wrapper JAR runs the same build without the script:

```bash
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build
```

A consumer that includes this project as a composite build (`includeBuild`)
holds this tree's Gradle lock while its own build runs.

To consume a specific commit from another project, publish it to the local Maven
repository with `VERSION=<short commit SHA> ./gradlew publishToMavenLocal`; the
modules declare `maven-publish` and read the version from `VERSION`, and a
commit-SHA version keeps a consumer's pin honest.

## Running Tests

```bash
./gradlew test                                    # everything
./gradlew test --tests "*InteropTest*"            # interop only (requires Python RNS)
./gradlew :rns-interfaces:test                    # pure-JVM interface tests
./gradlew :rns-android:compileDebugKotlin         # Android compile check
```

## Running rnsd-kt

```bash
./gradlew :rns-cli:shadowJar
java -jar rns-cli/build/libs/rnsd-kt.jar
```

CLI options (matching Python `rnsd`):

```
Options:
  --config PATH     Path to config directory (default: ~/.reticulum)
  -v, --verbose     Increase verbosity (repeatable)
  -q, --quiet       Decrease verbosity (repeatable)
  -s, --service     Run as service (log to file)
  --exampleconfig   Print example config and exit
  --version         Show version and exit
  -h, --help        Show this message and exit
```

## Usage

```kotlin
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.destination.Destination
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType

// Initialize Reticulum
val rns = Reticulum.start()

// Create an identity
val identity = Identity.create()

// Create a destination
val destination = Destination.create(
    identity = identity,
    direction = DestinationDirection.IN,
    type = DestinationType.SINGLE,
    appName = "myapp",
    "example"
)

// Register and announce
rns.registerDestination(destination)
destination.announce()

// Cleanup
Reticulum.stop()
```

## Interop Testing

The test suite validates byte-perfect compatibility with Python RNS. The Python
bridge server (`python-bridge/bridge_server.py`) provides 150+ commands for
cross-implementation verification covering crypto, packet formats, link
encryption, channel messaging, resource transfer and propagation-node exchanges.
Tests start it automatically — no manual setup. The `conformance-bridge` module
plays the same role for the reticulum-conformance suite.

The Python reference is the oracle for every parity decision here. Where
Kotlin behaviour differs on purpose, the divergence is recorded in
[`port-deviations.md`](port-deviations.md) with the Python file and line it
departs from.

## License

[MPL-2.0](LICENSE). `rns-core` and `rns-interfaces` are a port of
[Reticulum](https://github.com/markqvist/Reticulum); its license and copyright
notice are reproduced in [`NOTICE`](NOTICE).
