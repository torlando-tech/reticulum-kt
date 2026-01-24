# Architecture

**Analysis Date:** 2026-01-23

## Pattern Overview

**Overall:** Layered network stack with separation between protocol implementation, transport/routing, and interface abstractions.

**Key Characteristics:**
- Byte-perfect interoperability with Python reference implementation (Reticulum and LXMF)
- Multi-layered packet handling (Interface → Transport → Destination/Link)
- Singleton-based Transport with concurrent hash tables for packet routing
- Event-driven callbacks for packet delivery and link establishment
- Support for both thread-based and coroutine-based async operations (Android compatibility)

## Layers

**Interface Layer:**
- Purpose: Translate between raw network data and Reticulum packets
- Location: `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/`
- Contains: Abstract `Interface` base class, TCP, UDP, Auto, Local (shared instance) implementations
- Depends on: Common constants, crypto for IFAC (Interface Access Code) validation
- Used by: Transport layer for packet I/O
- Key: Implementations handle framing (HDLC, KISS), bitrate reporting, tunnel synthesis

**Transport Layer:**
- Purpose: Core routing engine and packet forwarding
- Location: `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt`
- Contains: `Transport` singleton managing path tables, announce propagation, packet deduplication
- Depends on: Destination, Identity, Link, Packet, crypto providers
- Used by: Reticulum instance, Destinations, Links
- Key: Uses concurrent hash maps (`pathTable`, `linkTable`, `reverseTable`, `announceTable`), maintains traffic statistics, runs job loop for announces and table culling

**Destination Layer:**
- Purpose: Define named endpoints for routing and receiving packets
- Location: `rns-core/src/main/kotlin/network/reticulum/destination/Destination.kt`
- Contains: Destination type enum (SINGLE/GROUP/PLAIN/LINK), request handlers, packet callbacks
- Depends on: Identity, Transport for registration
- Used by: Applications, Links (as communication targets)
- Key: Hash computation from app name + aspects + identity; direction indicates IN (listener) vs OUT (sender)

**Link Layer:**
- Purpose: Establish encrypted, authenticated point-to-point communication
- Location: `rns-core/src/main/kotlin/network/reticulum/link/Link.kt`
- Contains: Link establishment via ECDH handshake, encryption/decryption, channel/resource management
- Depends on: Destination, Packet, Transport, crypto
- Used by: Applications for sending encrypted messages
- Key: Initiator side starts handshake, responder waits; callbacks for link_established, packet received

**Packet Layer:**
- Purpose: Encode/decode wire format with header variants
- Location: `rns-core/src/main/kotlin/network/reticulum/packet/Packet.kt`
- Contains: HEADER_1 (19 bytes: flags + hops + dest_hash) and HEADER_2 (35 bytes: + transport_id)
- Depends on: Enums for packet type, destination type, transport type
- Used by: Transport, Destination, Link for sending
- Key: Flags byte encodes packet type (DATA/ANNOUNCE/LINKREQUEST/PROOF), destination type, transport type; creation of PacketReceipt for delivery tracking

**Identity/Crypto Layer:**
- Purpose: Cryptographic operations and identity management
- Location: `rns-core/src/main/kotlin/network/reticulum/crypto/` and `rns-core/src/main/kotlin/network/reticulum/identity/Identity.kt`
- Contains: X25519/Ed25519 key pair management, signature generation/validation, hash functions (SHA-256)
- Depends on: BouncyCastle for crypto primitives
- Used by: Identity creation, Link encryption, packet signing
- Key: Identities are 64 bytes (X25519 public + Ed25519 public); hash is truncated to 16 bytes

**Resource Layer:**
- Purpose: Transfer large data over links with chunking, compression, retransmission
- Location: `rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt`
- Contains: Resource advertisement, chunk management, BZ2 compression, progress tracking
- Depends on: Link, Packet
- Used by: Applications for transferring arbitrary-sized data
- Key: Initiator sends advertisement, responder accepts; automatic retransmission on timeout

**Channel Layer:**
- Purpose: Reliable message delivery over Link with windowing and sequencing
- Location: `rns-core/src/main/kotlin/network/reticulum/channel/Channel.kt`
- Contains: Message type registration, handler dispatch, sequence tracking, backpressure
- Depends on: Link for transport
- Used by: Applications needing continuous bidirectional messaging
- Key: Different from Resource (continuous vs single transfer); window size adapts based on RTT

**Main Entry Point:**
- Purpose: Initialize and manage Reticulum instance lifecycle
- Location: `rns-core/src/main/kotlin/network/reticulum/Reticulum.kt`
- Contains: Singleton access, Transport startup, shared instance server/client setup
- Depends on: Transport, Identity, directory management
- Used by: All applications
- Key: Must call `Reticulum.start()` before using network; supports shared instance mode (multiple clients to one Transport instance)

**LXMF Layer (optional):**
- Purpose: Higher-level message format with timestamps, titles, content, extensible fields
- Location: `lxmf-core/src/main/kotlin/network/reticulum/lxmf/`
- Contains: LXMessage encoding/decoding, LXMRouter for propagation, LXStamper for proof-of-work
- Depends on: Destination, Link, Transport
- Used by: Messaging applications
- Key: Wire format: dest_hash (16) + source_hash (16) + signature (64) + msgpack payload; byte-perfect compatible with Python LXMF

## Data Flow

**Outgoing Packet (Application → Network):**

1. Application creates Destination (app_name + aspects + identity → hash)
2. Application creates Packet with data, destination hash, packet type
3. Packet.pack() encodes wire format (flags + hops + dest_hash + data/ciphertext)
4. Transport.sendPacket() processes packet:
   - Checks duplicate cache
   - Updates traffic stats
   - Routes to appropriate interfaces based on path table
   - Creates PacketReceipt for tracking
5. Interface.sendData() transmits raw bytes on network

**Incoming Packet (Network → Application):**

1. Interface.onPacketReceived() receives raw bytes from network
2. Transport.ingressPacket() processes:
   - Validates IFAC (Interface Access Code) if enabled
   - Parses wire format to Packet object
   - Checks duplicate cache and path table
   - May forward to other interfaces if transport enabled
3. Transport checks packet type:
   - ANNOUNCE: updates announce table, calls announce handlers
   - LINKREQUEST: checks Destination.acceptLinkRequests, initiates Link
   - DATA: delivers to registered Destination callback
   - PROOF: updates PacketReceipt status
4. Destination.packetCallback invoked with decrypted data

**Announce Propagation:**

1. Destination.announce() creates ANNOUNCE packet with signed identity
2. Transport queues announce with rate limiting (ANNOUNCE_CAP = 2% of bandwidth)
3. Transport.retransmitAnnounces() sends via all interfaces with hop count
4. Remote Transport updates pathTable (destination_hash → PathEntry with hops, interface, timestamp)
5. Announce handlers notified of new path

**Link Establishment (Encrypted Handshake):**

1. Initiator calls Link.create(destination) → sends LINKREQUEST
2. Responder receives LINKREQUEST, creates incoming Link, calls linkEstablishedCallback
3. Initiator/Responder exchange keys:
   - Ephemeral ECDH key exchange (X25519)
   - Signature verification (Ed25519)
4. Both sides derive link encryption key (via Token/HKDF)
5. Link callback fires on both sides
6. Subsequent packets encrypted with link key via Packet.encrypt()/decrypt()

**Shared Instance Mode:**

1. Server-side: Reticulum.start(shareInstance=true) → LocalServerInterface binds TCP port
2. Client-side: Reticulum.start(connectToSharedInstance=true) → LocalClientInterface connects to server
3. Client sends/receives Transport commands via TCP to server
4. Server manages single Transport instance for all clients
5. Allows multiple applications on device to share packet routing

**State Management:**

- **Path Table:** `Transport.pathTable[destination_hash] → PathEntry(hops, interface, timestamp)`
  - Updated on announces
  - Culled periodically (destinations expire after 7 days)
- **Link Table:** `Transport.linkTable[link_id] → LinkEntry(source, destination, confirmed)`
  - Created on LINKREQUEST
  - Destroyed on link close
- **Announce Table:** `Transport.announceTable[destination_hash] → AnnounceEntry(timestamp, hops, identity)`
  - Tracks known destinations and their identities
  - Used for key lookup before sending encrypted packets
- **Pending Receipts:** `Transport.pendingReceipts[packet_hash] → ProofCallback`
  - Set when PacketReceipt created
  - Called when PROOF packet received
  - Times out after per-hop timeout

## Key Abstractions

**Packet:**
- Purpose: Wire-format representation with parsing/serialization
- Examples: `rns-core/src/main/kotlin/network/reticulum/packet/Packet.kt`
- Pattern: Immutable after parsing; contains all header info plus encrypted/plaintext data; lazy packet hash computation

**Destination:**
- Purpose: Named endpoint with direction (IN/OUT) and type (SINGLE/GROUP/PLAIN/LINK)
- Examples: `rns-core/src/main/kotlin/network/reticulum/destination/Destination.kt`
- Pattern: Hash derived from app name + aspects + identity; registered with Transport for incoming packets; defines packet callback and link acceptance policy

**Identity:**
- Purpose: Cryptographic identity with X25519 (encryption) and Ed25519 (signing) keys
- Examples: `rns-core/src/main/kotlin/network/reticulum/identity/Identity.kt`
- Pattern: Private key optional (can be public-only for verification); persisted to file for transport identity; hash is 16-byte truncation of full public key SHA-256

**Link:**
- Purpose: Encrypted, authenticated bidirectional connection between peers
- Examples: `rns-core/src/main/kotlin/network/reticulum/link/Link.kt`
- Pattern: Initiator creates with remote destination; responder created by incoming LINKREQUEST; holds shared encryption key after handshake; supports channels and resources

**Resource:**
- Purpose: Large file/blob transfer with automatic chunking and retransmission
- Examples: `rns-core/src/main/kotlin/network/reticulum/resource/Resource.kt`
- Pattern: Advertised before transfer; chunks sent in order with sequence tracking; automatic BZ2 compression; callbacks for progress/completion

**Channel:**
- Purpose: Structured message delivery with type registration and handler dispatch
- Examples: `rns-core/src/main/kotlin/network/reticulum/channel/Channel.kt`
- Pattern: Defines custom message types (subclasses of MessageBase); window-based flow control; adapts window size based on round-trip time

**Interface:**
- Purpose: Hardware/protocol binding for packet transport
- Examples: `rns-interfaces/src/main/kotlin/network/reticulum/interfaces/`
- Pattern: Abstract base class; implementations handle framing (HDLC/KISS) and medium specifics (TCP, UDP, serial); IFAC for network isolation

## Entry Points

**Reticulum Instance:**
- Location: `rns-core/src/main/kotlin/network/reticulum/Reticulum.kt` (lines 124-139)
- Triggers: Application initialization
- Responsibilities: Create Transport singleton, load/create transport identity, start interfaces, manage shutdown hooks

**Transport Job Loop:**
- Location: `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt` (job scheduling methods)
- Triggers: Every 100ms (configurable via `customJobIntervalMs`)
- Responsibilities: Retransmit announces, cull expired path table entries, check receipt timeouts, process queued announces

**Packet Ingress:**
- Location: `rns-core/src/main/kotlin/network/reticulum/transport/Transport.kt` (ingressPacket method)
- Triggers: Interface receives raw bytes
- Responsibilities: Parse packet, validate IFAC, check duplicates, route or deliver locally, update path tables

**Destination Announce:**
- Location: `rns-core/src/main/kotlin/network/reticulum/destination/Destination.kt` (announce method)
- Triggers: Application calls `destination.announce(appData)`
- Responsibilities: Create ANNOUNCE packet, sign with identity, queue for Transport transmission

**Link Establishment:**
- Location: `rns-core/src/main/kotlin/network/reticulum/link/Link.kt` (create method)
- Triggers: Application calls `Link.create(destination)` or Transport receives LINKREQUEST
- Responsibilities: Exchange handshake packets, derive encryption key, invoke callbacks when ready

## Error Handling

**Strategy:** Exceptions for programming errors (uninitialized state, invalid parameters); callbacks for network events (timeouts, proof failures).

**Patterns:**
- Reticulum.getInstance() throws `IllegalStateException` if not started
- Identity.sigPrv throws `IllegalStateException` if no private key
- Packet parsing throws `IndexOutOfBoundsException` for malformed wire format
- Link establishment timeout fires callback with failure reason
- Resource transfer calls `failed()` callback on packet loss exceeding retries
- Transport logs issues but continues operating (e.g., unregistered destination)

## Cross-Cutting Concerns

**Logging:** Simple println with timestamp (no external logging framework); format: `[timestamp] [Component] message`
- Located in: All major classes (Reticulum, Transport, Link, etc.)
- Verbosity: INFO-level events (started, stopped, connection established)

**Validation:**
- Packet wire format validated during parsing (header size, hash lengths)
- Signature validation for announces and link handshakes (Ed25519)
- IFAC validation on interfaces (HMAC check if enabled)

**Authentication:**
- Destination identity included in announces (public keys only)
- Link handshake uses Ed25519 signatures to verify peer identity
- Packet receipts verify delivery by validating truncated hash match

**Thread Safety:**
- `Transport` singleton uses ConcurrentHashMap for all tables
- `Destination` callbacks invoked on Transport thread (caller must synchronize if needed)
- `Link` has internal lock for state changes
- `Reticulum` uses AtomicBoolean for started flag

