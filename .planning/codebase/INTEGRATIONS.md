# External Integrations

**Analysis Date:** 2026-01-23

## APIs & External Services

**Python Interop:**
- Python RNS Reference Implementation (~/repos/Reticulum)
  - Test bridge: `python-bridge/bridge_server.py` - Provides 70+ commands for cross-implementation verification
  - Test environment variable: `PYTHON_RNS_PATH` - Path to Python RNS installation
  - Used for: Byte-perfect compatibility testing via integration tests
  - Protocol: TCP socket communication with JSON/pickle exchange

**Reticulum Network Stack:**
- Mesh network protocol implementation - Re-implements the open-source Reticulum protocol
  - No external API dependencies - Self-contained protocol implementation
  - Network communication via TCP, UDP, Serial, and other interfaces

## Data Storage

**Databases:**
- No SQL database integration detected

**File Storage:**
- Local filesystem only
  - Configuration: `~/.reticulum/config` (INI/ConfigObj format)
  - Identity storage: File-based, location configurable via config
  - Daemon state: In-process, no persistent store by default

**Caching:**
- In-process memory caching
  - Hash tables and queues in Transport layer
  - ByteArray pooling in `network.reticulum.common.ByteArrayPool`
  - No external cache service

## Authentication & Identity

**Auth Provider:**
- Custom implementation
  - Location: `rns-core/src/main/kotlin/network/reticulum/identity/`
  - Approach: Elliptic curve cryptography (X25519, Ed25519)
  - Key derivation: HKDF with SHA256/SHA512
  - Encryption: AES-256-CBC
  - No third-party auth services (OAuth, LDAP, etc.)

**Identity Management:**
- File-based identity storage
  - Default location: `~/.reticulum/identities/`
  - Format: Custom binary format with pickle compatibility
  - Private key encryption: Uses AES with user-provided password

## Monitoring & Observability

**Error Tracking:**
- No third-party error tracking integration (Sentry, Rollbar, etc.)

**Logs:**
- Kotlin Logging with SLF4J Simple backend
  - Output: Console (stdout) or file when run as service
  - Configuration: Verbosity levels via CLI flags (`-v`, `-q`)
  - Locations:
    - JVM daemon: stdout/stderr or log file via `-s` service flag
    - Android: Logcat via `Log` API (standard Android logging)
  - No external log aggregation

## CI/CD & Deployment

**Hosting:**
- No cloud hosting integration
- Deployed as:
  - JVM: Fat JAR (`rnsd-kt.jar`, `lxmf-node.jar`)
  - Android: Library module for integration into Android apps

**CI Pipeline:**
- No external CI system detected
- Build command: `./gradlew build`
- Test command: `./gradlew test`
- Shadow JAR build: `./gradlew :rns-cli:shadowJar`

## Environment Configuration

**Required env vars:**
- `PYTHON_RNS_PATH` - Optional, for integration tests
  - Default search: `$HOME/repos/Reticulum`, `$PROJECT_DIR/../../../Reticulum`
  - Used by: `rns-test/build.gradle.kts`

**Secrets location:**
- None detected - No API keys or secrets in codebase
- Identity private keys: Stored in `~/.reticulum/identities/` with optional user password encryption
- Configuration: INI file format at `~/.reticulum/config`

## Webhooks & Callbacks

**Incoming:**
- Not applicable - Reticulum is a mesh network stack, not an HTTP API

**Outgoing:**
- Not applicable for core protocol

**Message Callbacks (RNS-specific):**
- Channel message handlers: Application-level callbacks via `ChannelListener`
- Link establishment: `LinkListener` interface for lifecycle callbacks
- Destination message handlers: Application-level message processing
- No external webhook integrations

## Network Interfaces

**Implemented:**
- TCP Server Interface (`rns-interfaces/src/main/kotlin/network/reticulum/interfaces/tcp/TCPServerInterface.kt`)
  - Binding: Configurable IP and port
  - Protocol: Reticulum packet framing via KISS or HDLC
- TCP Client Interface (`rns-interfaces/src/main/kotlin/network/reticulum/interfaces/tcp/TCPClientInterface.kt`)
  - Connection: Target host and port (remote node discovery)
- UDP Interface (`rns-interfaces/src/main/kotlin/network/reticulum/interfaces/udp/UDPInterface.kt`)
  - Modes: Unicast, broadcast, multicast
  - Configuration: Bind/forward IPs and ports
- KISS Framing (`rns-interfaces/src/main/kotlin/network/reticulum/interfaces/framing/KISS.kt`)
  - Protocol: KISS (Keep It Simple Stupid) serial protocol framing
- HDLC Framing (`rns-interfaces/src/main/kotlin/network/reticulum/interfaces/framing/HDLC.kt`)
  - Protocol: High-level Data Link Control framing

**Not Implemented:**
- Serial Interface - Not started
- RNode Interface (LoRa radios) - Not started
- BLE Interface (Bluetooth Low Energy) - Not started
- I2P Interface - Not started
- Auto Interface (mDNS discovery) - Not started
- Local Interface (IPC/Unix sockets) - Not started

## RPC Protocol

**Python Compatibility:**
- Location: `rns-cli/src/main/kotlin/network/reticulum/cli/daemon/RpcServer.kt`
- Protocol: Python `multiprocessing.connection` protocol (port 37429 default)
- Authentication: HMAC-SHA256 challenge-response with shared authkey
- Message Format: Length-prefixed pickle data (4-byte big-endian length + pickle bytes)
- Purpose: Allows Python RNS clients to query interface statistics and daemon info
- No external service - Implemented as part of rnsd-kt daemon

---

*Integration audit: 2026-01-23*
