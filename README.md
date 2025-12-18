# reticulum-kt

A Kotlin/JVM implementation of the [Reticulum Network Stack](https://reticulum.network/) for building resilient, delay-tolerant mesh networks.

## Implementation Status

Comparison with [Python RNS](https://github.com/markqvist/Reticulum) reference implementation:

| Component | Status | Notes |
|-----------|--------|-------|
| **Core Protocol** | ~90% | Identity, Destination, Packet, Transport, Link, Channel, Resource |
| **Interfaces** | ~15% | TCP complete; UDP, Serial, RNode, BLE not started |
| **Utilities/CLI** | ~15% | rnsd-kt daemon implemented |
| **Crypto** | 100% | Full BouncyCastle implementation |

### Core Protocol

| Class | Status | Notes |
|-------|--------|-------|
| Identity | Complete | Encryption, signing, ratchets, storage |
| Destination | Complete | All types (SINGLE, GROUP, PLAIN, LINK) |
| Packet | Complete | Full wire format, HEADER_1/HEADER_2 |
| Transport | ~95% | Routing, path tables, persistence, speed monitoring |
| Link | ~95% | Establishment, encryption, keepalive |
| Channel | ~80% | Core messaging; advanced flow control partial |
| Resource | ~85% | Transfers work; pause/resume partial |
| Buffer | Complete | Stream I/O over channels |

### Interfaces

**Implemented:**
- TCP Server/Client Interface
- KISS Framing
- HDLC Framing

**Not Implemented:**
- UDP Interface
- Serial Interface
- RNode Interface (LoRa radios)
- BLE Interface (Bluetooth Low Energy)
- I2P Interface
- Auto Interface (discovery)
- Local Interface (IPC)

### Utilities/CLI

| Tool | Status | Notes |
|------|--------|-------|
| `rnsd-kt` | Complete | Daemon matching Python `rnsd` behavior |
| `rnid` | Not started | Identity management |
| `rnstatus` | Not started | Network status |
| `rnpath` | Not started | Path discovery |
| `rncp` | Not started | File transfer |
| `rnprobe` | Not started | Ping/latency |

---

## Requirements

- JDK 21+
- Python 3.8+ with [RNS](https://github.com/markqvist/Reticulum) installed (for interop tests)

## Project Structure

```
rns-core/        # Core protocol implementation (Identity, Destination, Link, Channel, etc.)
rns-interfaces/  # Network interface implementations
rns-cli/         # CLI utilities (rnsd-kt daemon)
rns-test/        # Test suite including Python interop tests
python-bridge/   # Python bridge server for interop testing
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

The daemon reads `~/.reticulum/config` (same ConfigObj/INI format as Python RNS) and supports TCPClientInterface and TCPServerInterface.

## Usage

```kotlin
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.destination.Destination

// Initialize Reticulum
val rns = Reticulum.start()

// Create an identity
val identity = Identity.create()

// Create a destination
val destination = Destination.create(
    identity = identity,
    direction = Destination.Direction.IN,
    type = Destination.Type.SINGLE,
    appName = "myapp",
    aspects = listOf("example")
)

// Register and announce
rns.registerDestination(destination)
destination.announce()

// Cleanup
Reticulum.stop()
```

## Interop Testing

The test suite validates byte-perfect compatibility with Python RNS. The Python bridge server (`python-bridge/bridge_server.py`) provides 70+ commands for cross-implementation verification.

Tests are started automatically - no manual setup required.

## License

MIT
