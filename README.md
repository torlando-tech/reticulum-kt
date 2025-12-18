# reticulum-kt

A Kotlin/JVM implementation of the [Reticulum Network Stack](https://reticulum.network/) for building resilient, delay-tolerant mesh networks.

## Requirements

- JDK 17+
- Python 3.8+ with [RNS](https://github.com/markqvist/Reticulum) installed (for interop tests)

## Project Structure

```
rns-core/        # Core protocol implementation (Identity, Destination, Link, Channel, etc.)
rns-interfaces/  # Network interface implementations
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
