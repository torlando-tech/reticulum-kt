# reticulum-kt

A Kotlin/JVM implementation of the [Reticulum Network Stack](https://reticulum.network/) for building resilient, delay-tolerant mesh networks on Android and JVM.

## Implementation Status

Comparison with [Python RNS](https://github.com/markqvist/Reticulum) reference implementation. Interoperability is validated by automated tests against the Python reference.

### Core Protocol

| Component | Status | Notes |
|-----------|--------|-------|
| Identity | Complete | X25519/Ed25519, ratchets, known destinations, persistent storage |
| Destination | Complete | All types (SINGLE, GROUP, PLAIN, LINK), request handlers, proof strategies |
| Packet | Complete | Full wire format, HEADER_1/HEADER_2, receipts, proofs |
| Transport | ~95% | Routing, path management, tunnels, announces, announce caching, link management, IFAC, mode-based filtering |
| Link | Complete | Establishment, encryption, channels, resources, request/response, MTU discovery |
| Channel | ~90% | Windowed flow control, ordered delivery, retransmission, message type registry |
| Buffer | Complete | Stream I/O over channels |
| Resource | ~90% | Chunked transfer, BZ2 compression, progress tracking, metadata |
| Crypto | Complete | BouncyCastle: X25519, Ed25519, HKDF, AES-256-CBC, SHA-256/512 |

### Interfaces

| Interface | Status | Notes |
|-----------|--------|-------|
| TCP Server/Client | Complete | HDLC framing, exponential backoff reconnect |
| UDP | Complete | Unicast, broadcast, multicast |
| Local (Shared Instance) | Complete | Server/client IPC for sharing Reticulum across apps |
| RNode (LoRa) | Complete | Full KISS protocol, firmware checking, BLE + serial transport |
| BLE Mesh | Complete | Dual-role GATT, identity handshake, fragmentation, Android driver |
| Auto (Discovery) | Complete | IPv6 multicast peer discovery, per-peer UDP connections |
| KISS Framing | Complete | Used by TCP and RNode interfaces |
| HDLC Framing | Complete | Used by TCP interfaces |
| I2P | Not implemented | Stub in config factory |
| Serial | Not implemented | RNode covers most serial use cases |

### Android

| Component | Status | Notes |
|-----------|--------|-------|
| Foreground Service | Complete | Persistent connection with Doze/battery awareness |
| BLE Driver | Complete | GATT server/client, advertising, scanning (API 26+) |
| Power Management | Complete | Doze handler, battery monitor, WorkManager integration |
| Sample App | Moved | See [carina](https://github.com/torlando/carina) for the Compose UI sample app |
| LXMF | Moved | See [lxmf-kt](https://github.com/torlando/lxmf-kt) for the LXMF messaging protocol |

### Remaining Work

Features that exist in the Python reference but are not yet implemented:

| Feature | Priority | Description |
|---------|----------|-------------|
| PHY stats on packets | Medium | Expose RSSI/SNR/Q from RNode through the Packet API |
| Interface discovery | Medium | `InterfaceAnnouncer`/`InterfaceMonitor` for mDNS-based interface discovery |
| Blackhole system | Medium | Identity blacklisting to block bad actors |
| Remote management | Low | Control destinations for remote `/path` and `/status` queries |
| RPC server | Low | Multi-process sharing of a single Reticulum instance |
| CLI utilities | Low | `rnstatus`, `rnpath`, `rnprobe` equivalents |
| SerialInterface | Low | Direct serial port (RNode covers most use cases) |
| I2PInterface | Low | I2P anonymity network integration |

### Utilities/CLI

| Tool | Status | Notes |
|------|--------|-------|
| `rnsd-kt` | Complete | Daemon matching Python `rnsd` behavior |
| `rnstatus` | Not started | Network status |
| `rnpath` | Not started | Path discovery |
| `rnprobe` | Not started | Ping/latency |

---

## Requirements

- JDK 21+
- Python 3.8+ with [RNS](https://github.com/markqvist/Reticulum) installed (for interop tests)
- Android API 26+ (for Android deployment)

## Project Structure

```
rns-core/        # Core protocol (Identity, Destination, Transport, Link, Channel, Resource)
rns-interfaces/  # Network interfaces (TCP, UDP, Local, RNode, BLE, Auto)
rns-android/     # Android-specific code (BLE driver, foreground service, power management)
rns-cli/         # CLI utilities (rnsd-kt daemon)
rns-test/        # Integration and interop tests
python-bridge/   # Python bridge server for interop testing (120+ commands)
```

## Building

```bash
./gradlew build
```

## Running Tests

Run all tests:
```bash
./gradlew test
```

Run only interop tests (requires Python RNS):
```bash
./gradlew test --tests "*InteropTest*"
```

Run a specific test class:
```bash
./gradlew test --tests "network.reticulum.interop.identity.IdentityInteropTest"
```

## Running rnsd-kt

Build the fat JAR:
```bash
./gradlew :rns-cli:shadowJar
```

Run the daemon:
```bash
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

The test suite validates byte-perfect compatibility with Python RNS. The Python bridge server (`python-bridge/bridge_server.py`) provides 120+ commands for cross-implementation verification covering crypto, packet formats, link encryption, channel messaging, resource transfer, and LXMF message exchange.

Tests are started automatically - no manual setup required.

## License

[MPL-2.0](LICENSE)
