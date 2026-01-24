# Codebase Structure

**Analysis Date:** 2026-01-23

## Directory Layout

```
reticulum-kt/
├── rns-core/                          # Core network stack implementation
│   ├── src/main/kotlin/network/reticulum/
│   │   ├── Reticulum.kt               # Main entry point singleton
│   │   ├── common/                    # Constants, enums, utilities
│   │   ├── crypto/                    # Cryptographic providers and hashes
│   │   ├── identity/                  # Identity management (X25519/Ed25519)
│   │   ├── destination/               # Destination abstraction
│   │   ├── packet/                    # Packet wire format encoding/decoding
│   │   ├── link/                      # Encrypted link establishment
│   │   ├── transport/                 # Routing and packet forwarding
│   │   ├── resource/                  # Large data transfer
│   │   └── channel/                   # Structured messaging
│   └── src/test/kotlin/               # Unit and integration tests
│
├── rns-interfaces/                    # Physical/logical interface implementations
│   └── src/main/kotlin/network/reticulum/interfaces/
│       ├── Interface.kt               # Abstract base class
│       ├── tcp/                       # TCP client/server interfaces
│       ├── udp/                       # UDP interface
│       ├── auto/                      # Automatic peer discovery (broadcast)
│       ├── local/                     # Shared instance (IPC via TCP)
│       └── framing/                   # HDLC and KISS framing protocols
│
├── lxmf-core/                         # Higher-level messaging protocol
│   └── src/main/kotlin/network/reticulum/lxmf/
│       ├── LXMessage.kt               # Message encoding/decoding
│       ├── LXMRouter.kt               # Message routing and propagation
│       ├── LXStamper.kt               # Proof-of-work stamping
│       └── LXMFConstants.kt
│
├── rns-android/                       # Android-specific optimizations
│   └── src/main/kotlin/               # Battery-optimized coroutine handling
│
├── rns-cli/                           # Command-line tool (example usage)
├── rns-sample-app/                    # Sample mobile application
├── rns-test/                          # Test utilities and fixtures
│
├── test-scripts/                      # Python interop and manual testing
│   ├── link_test_*.py                 # Link establishment tests
│   ├── lxmf_*.py                      # LXMF message tests
│   ├── python_*.py                    # Python reference tests
│   └── *.kt                           # Manual Kotlin test files
│
├── python-bridge/                     # Python reference implementation bridge
│   └── bridge_server.py               # TCP server for Python interop
│
├── .planning/                         # Analysis documents (generated)
├── build.gradle.kts                   # Root Gradle configuration
├── settings.gradle.kts                # Gradle module configuration
├── gradle.properties                  # Gradle version constants
└── README.md
```

## Directory Purposes

**rns-core:**
- Purpose: Core Reticulum Network Stack protocol implementation
- Contains: All packet types, routing, encryption, link management
- Key files: `Reticulum.kt` (entry point), `transport/Transport.kt` (routing engine)
- Depends on: BouncyCastle (crypto), MessagePack (serialization), Coroutines
- Exports: All public classes and enums in `network.reticulum.*` packages

**rns-interfaces:**
- Purpose: Translate between network media and Reticulum packets
- Contains: TCP, UDP, Auto (broadcast), Local (shared instance) implementations
- Key files: `Interface.kt` (abstract base), framing protocols (HDLC, KISS)
- Depends on: rns-core
- Exports: Interface subclasses; registered with Transport at runtime

**lxmf-core:**
- Purpose: Layered messaging protocol on top of Reticulum
- Contains: Message serialization, routing, proof-of-work stamping
- Key files: `LXMessage.kt`, `LXMRouter.kt`
- Depends on: rns-core
- Exports: LXMessage class, LXMRouter singleton

**rns-android:**
- Purpose: Platform-specific optimizations for Android
- Contains: Coroutine-based job loop, battery-efficient background processing
- Depends on: rns-core, AndroidX libraries
- Note: Uses `Transport.useCoroutineJobLoop = true` and custom intervals

**rns-cli, rns-sample-app:**
- Purpose: Example applications demonstrating usage
- Contains: Destination setup, packet sending, link establishment examples
- Depends on: rns-core, rns-interfaces, lxmf-core

**test-scripts:**
- Purpose: Interoperability testing with Python reference implementation
- Contains: Python scripts for link tests, LXMF tests, manual verification
- Usage: Run Python and Kotlin sides simultaneously to verify protocol compatibility

**python-bridge:**
- Purpose: TCP bridge for Python-Kotlin communication during testing
- Contains: Simple socket server that relays messages between languages

## Key File Locations

**Entry Points:**
- `rns-core/src/main/kotlin/network/reticulum/Reticulum.kt`: Application initialization, must call `Reticulum.start()` first

**Configuration:**
- `gradle.properties`: Dependency versions (Kotlin, Coroutines, BouncyCastle, etc.)
- `settings.gradle.kts`: Module inclusion and project setup
- `rns-core/build.gradle.kts`: Core module dependencies

**Core Logic:**
- `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt`: Routing engine, packet deduplication, announce propagation
- `rns-core/src/main/kotlin/network/reticulum/identity/Identity.kt`: Cryptographic identity (X25519/Ed25519)
- `rns-core/src/main/kotlin/network/reticulum/destination/Destination.kt`: Named endpoints with type and direction
- `rns-core/src/main/kotlin/network/reticulum/packet/Packet.kt`: Wire format encoding/decoding
- `rns-core/src/main/kotlin/network/reticulum/link/Link.kt`: Encrypted peer connections with handshake
- `rns-core/src/main/kotlin/network/reticulum/crypto/`: Hashing, signing, encryption
- `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/Interface.kt`: Abstract base for network bindings

**Testing:**
- `rns-core/src/test/kotlin/`: Unit tests (announce validation, packet parsing, crypto)
- `rns-interfaces/src/test/kotlin/`: Interface tests (TCP, Auto, IFAC)
- `lxmf-core/src/test/kotlin/`: Message encoding tests, interop tests
- `test-scripts/`: Manual interoperability tests with Python reference

## Naming Conventions

**Files:**
- Class files: PascalCase, one class per file (e.g., `Destination.kt`, `Transport.kt`)
- Constants: `*Constants.kt` (e.g., `RnsConstants.kt`, `LinkConstants.kt`)
- Enum/sealed class: PascalCase in `Enums.kt` or `*Types.kt`
- Test files: `*Test.kt` (e.g., `AnnounceValidationTest.kt`, `LXMessageTest.kt`)

**Directories:**
- Package structure: lowercase with hyphens (e.g., `network/reticulum/transport/`)
- Functional grouping: single responsibility (crypto, identity, link, transport, etc.)
- Module directories: lowercase with hyphen prefix (e.g., `rns-core`, `rns-interfaces`)

**Classes/Interfaces:**
- PascalCase: `Transport`, `Destination`, `Link`, `Packet`, `Resource`
- Data classes for immutable values: `Packet`, `Identity`, `PathEntry`
- Singleton objects: `Transport`, `Reticulum` (companion object)
- Interface suffix: `Interface` (base), specific names for implementations (e.g., `TCPServerInterface`)
- Callback suffix: `Callback` (e.g., `ProofCallback`) or `Handler` (e.g., `AnnounceHandler`)

**Functions:**
- camelCase: `start()`, `getInstance()`, `sendPacket()`, `ingressPacket()`
- getter patterns: `getInterfaces()`, `getPathTable()` (sometimes property syntax preferred)
- factory methods: `create()` (e.g., `Identity.create()`, `Destination.create()`)
- callback setters: `setPacketCallback()` or property assignment (e.g., `destination.packetCallback = { ... }`)

**Variables:**
- camelCase: `destinationHash`, `transportId`, `packetHash`
- private backing fields: underscore prefix for fields with public properties (not used; Kotlin properties preferred)
- loop counters: `i`, `j` (minimal scope)
- mutable collections: explicit type (e.g., `val linkTable = ConcurrentHashMap<ByteArrayKey, LinkEntry>()`)

**Types:**
- Enum variants: UPPER_CASE (e.g., `SINGLE`, `GROUP`, `ANNOUNCE`, `LINKREQUEST`)
- Type aliases: PascalCase starting with capital (e.g., `MessageCallback`)
- Sealed class hierarchy: PascalCase base, subclass names specific (e.g., `DestinationType`, variants `SINGLE`, `GROUP`)

## Where to Add New Code

**New Destination Type or Transport Enhancement:**
- Primary code: `rns-core/src/main/kotlin/network/reticulum/destination/` or `rns-core/src/main/kotlin/network/reticulum/transport/`
- Constants: Add to relevant `*Constants.kt` file
- Tests: `rns-core/src/test/kotlin/network/reticulum/destination/` or `rns-core/src/test/kotlin/network/reticulum/transport/`
- Example: New destination type (TUNNEL, MESH) goes in `Destination.kt` with enum variant in `Enums.kt`

**New Interface Implementation:**
- Implementation: `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/{type}/`
- Extend: `Interface` abstract class from `Interface.kt`
- Register: Call `Transport.registerInterface(interface.toRef())` where toRef() creates `InterfaceRef`
- Tests: `rns-interfaces/src/test/kotlin/`
- Example: New serial interface at `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/serial/SerialInterface.kt`

**New Message Feature (LXMF):**
- Implementation: `lxmf-core/src/main/kotlin/network/reticulum/lxmf/`
- Extend: `LXMessage` for new fields, or `LXMRouter` for new routing behaviors
- Serialization: Update msgpack format in `pack()`/`unpack()` methods
- Tests: `lxmf-core/src/test/kotlin/` with Python interop tests in `test-scripts/lxmf_*.py`

**New Test Utilities:**
- Shared test helpers: `rns-test/src/main/kotlin/`
- Module-specific tests: `{module}/src/test/kotlin/`
- Python interop tests: `test-scripts/` directory
- Example: Helper for creating test destinations goes in `rns-test/`

**Utilities and Helpers:**
- Shared extensions: `rns-core/src/main/kotlin/network/reticulum/common/` (e.g., `ByteUtils.kt`, `ByteArrayPool.kt`)
- Crypto helpers: `rns-core/src/main/kotlin/network/reticulum/crypto/` (e.g., new hash functions)
- Serialization: MessagePack usage localized to relevant layer (Identity, Destination, Packet, etc.)

## Special Directories

**build/:**
- Purpose: Gradle build output and compiled classes
- Generated: Yes (by Gradle during `./gradlew build`)
- Committed: No (.gitignore)

 .gradle/:
- Purpose: Gradle caches and metadata
- Generated: Yes
- Committed: No

**test-scripts/:**
- Purpose: Manual testing and Python reference interoperability
- Generated: No (hand-written)
- Committed: Yes (Python scripts checked in)
- Usage: Run alongside Kotlin tests to verify protocol compatibility

**.planning/codebase/:**
- Purpose: Architecture and structure documentation (this file and related docs)
- Generated: Yes (by analysis agents)
- Committed: Yes (reference for future implementations)

**gradle/wrapper/:**
- Purpose: Gradle wrapper for build reproducibility
- Generated: No (checked in for reproducibility)
- Committed: Yes

## Module Dependencies

```
rns-core (no deps within project)
  ↓ depends on
  └─ BouncyCastle, MessagePack, Coroutines, Kotlin stdlib

rns-interfaces
  ↓ depends on
  ├─ rns-core
  └─ Java NIO/networking libraries

lxmf-core
  ↓ depends on
  ├─ rns-core
  └─ MessagePack

rns-android
  ↓ depends on
  ├─ rns-core
  ├─ rns-interfaces
  ├─ AndroidX libraries
  └─ Android Framework

rns-cli, rns-sample-app (example apps)
  ↓ depend on
  ├─ rns-core
  ├─ rns-interfaces
  └─ lxmf-core (optional)

test modules (rns-test, lxmf-core/test, etc.)
  ↓ depend on
  ├─ Core module under test
  ├─ JUnit, Kotest
  └─ Mockito or similar (if used)
```

## Package Structure Rules

1. **One-to-one file to class:** Each class (except enums, constants, data classes) gets its own file
2. **Grouping:** Related classes grouped by function (e.g., all transport classes in `transport/` package)
3. **Internal classes:** Nested or package-private where state is tightly coupled (e.g., `PathEntry` inside Transport)
4. **Constants:** Separate `*Constants.kt` file per domain (not in `Companion object`)
5. **Enums:** All wire protocol enums in `common/Enums.kt`
6. **Exceptions:** Custom exceptions in `common/Exceptions.kt`

## Adding a New Module

1. Create directory: `{module-name}/` at project root
2. Create structure:
   ```
   {module-name}/
   ├── build.gradle.kts    # Copy from rns-core, adjust dependencies
   ├── src/main/kotlin/network/reticulum/{domain}/
   └── src/test/kotlin/network/reticulum/{domain}/
   ```
3. Add to `settings.gradle.kts`: `include(":module-name")`
4. Add dependency in consuming modules' `build.gradle.kts`: `implementation(project(":module-name"))`
5. Update `.planning/codebase/STRUCTURE.md` with new module location and purpose

