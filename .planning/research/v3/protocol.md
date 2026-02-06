# BLE Mesh Protocol Internals for Reticulum

**Researched:** 2026-02-06
**Sources:** `~/repos/public/ble-reticulum/` (Python reference), `~/repos/public/columba/` (Kotlin Android reference)
**Protocol version:** v2.2 (stable), v0.3.0 (capability extension)
**Confidence:** HIGH -- all findings derived from reading the actual source code

---

## 1. GATT Service Structure

The entire BLE protocol operates over a single custom GATT service with three characteristics.

### Service UUID

```
37145b00-442d-4a94-917f-8f42c5da28e3
```

### Characteristics

| Name | UUID | Properties | Direction | Purpose |
|------|------|------------|-----------|---------|
| RX | `37145b00-442d-4a94-917f-8f42c5da28e5` | WRITE, WRITE_WITHOUT_RESPONSE | Central -> Peripheral | Central writes data to peripheral |
| TX | `37145b00-442d-4a94-917f-8f42c5da28e4` | READ, NOTIFY | Peripheral -> Central | Peripheral notifies central with data |
| Identity | `37145b00-442d-4a94-917f-8f42c5da28e6` | READ | Central reads from peripheral | 16-byte Transport identity hash |
| (CCCD) | `00002902-0000-1000-8000-00805f9b34fb` | -- | -- | Standard descriptor for TX notifications |

**Key design note:** All data flows through RX (write) and TX (notify). The Identity characteristic is read-only and only read once during connection setup.

**Source:** `BLEInterface.py:242-245`, `BLEGATTServer.py:60-69`, `BleConstants.kt:17-49`

---

## 2. MAC Address Sorting (Connection Direction)

### Purpose

Prevents both devices from simultaneously trying to connect to each other. Without this, two dual-mode devices would both scan, both discover each other, and both try to connect as central -- causing connection failures.

### Algorithm

```
Given: local_mac, peer_mac (both as 6-byte MAC addresses)

1. Strip colons and parse as integers:
   my_mac_int  = int(local_mac.replace(":", ""), 16)
   peer_mac_int = int(peer_mac.replace(":", ""), 16)

2. Compare:
   if my_mac_int < peer_mac_int:
       I initiate connection (act as BLE central)
   elif my_mac_int > peer_mac_int:
       I wait (act as BLE peripheral only)
   else:
       MAC collision -- theoretically impossible (48-bit space)
       Fall through to normal connection logic
```

**Source:** `BLEInterface.py:1718-1738`

```python
# Exact code from BLEInterface.py:1722-1735
if self.local_address is not None:
    try:
        my_mac = self.local_address.replace(":", "")
        peer_mac = address.replace(":", "")
        my_mac_int = int(my_mac, 16)
        peer_mac_int = int(peer_mac, 16)
        if my_mac_int > peer_mac_int:
            # Our MAC is higher - let them connect to us
            continue
    except (ValueError, AttributeError) as e:
        pass  # Fall through to normal connection logic
```

### Example

```
Pi1 MAC: B8:27:EB:A8:A7:22 = 0xB827EBA8A722
Pi2 MAC: B8:27:EB:10:28:CD = 0xB827EB1028CD

0xB827EBA8A722 > 0xB827EB1028CD  =>  Pi2 (lower) connects TO Pi1 (higher)

Result:
  Pi2 = Central (lower MAC, initiates connection)
  Pi1 = Peripheral (higher MAC, accepts connection)
```

### Tie-Breaking (Same MAC)

The code treats this as impossible. If it happens, the MAC sorting check is skipped (falls through), meaning both devices may try to connect -- the standard pre-v2.2 behavior.

**Source:** `BLE_PROTOCOL_v2.2.md:162-176`

### v0.3.0 Capability Override

For devices that cannot act as central (e.g., ESP32-S3), v0.3.0 adds manufacturer-specific advertising data to override MAC sorting:

```
Manufacturer Data (4 bytes, Company ID 0xFFFF):
  Byte 0-1: CID (little-endian) = 0xFFFF
  Byte 2:   Protocol version = 0x03
  Byte 3:   Capability flags:
            Bit 0: PERIPHERAL_ONLY (1 = cannot act as central)
            Bit 1: Reserved (CENTRAL_ONLY)
            Bits 2-7: Reserved (must be 0)
```

Decision algorithm with capabilities:

```
if peer_peripheral_only AND NOT local_peripheral_only:
    WE initiate (peer cannot)
elif local_peripheral_only AND NOT peer_peripheral_only:
    PEER initiates (we cannot)
elif BOTH peripheral_only:
    Connection IMPOSSIBLE
else:
    Use v2.2 MAC sorting (lower MAC initiates)
```

If no manufacturer data is present (v2.2 device), assume full capability and fall back to MAC sorting.

**Source:** `BLE_PROTOCOL_v0.3.0.md:83-103`

**Android note:** Android API excludes the CID from the byte array in `addManufacturerData()`, so the data is only 2 bytes: `[version, flags]`.

---

## 3. Identity Handshake Protocol

### The Problem

In BLE's asymmetric model:
- Centrals can read characteristics from peripherals (via GATT read)
- Peripherals CANNOT read characteristics from centrals

So after MAC sorting establishes a connection, the central knows the peripheral's identity (reads Identity characteristic), but the peripheral has NO way to learn the central's identity.

### Solution: First-Packet Handshake

After connecting, the central **immediately writes exactly 16 bytes** to the RX characteristic. These 16 bytes are the central's Reticulum Transport identity hash (`RNS.Transport.identity.hash`).

### Complete Connection Flow (Wire-Level)

```
Central (lower MAC)                    Peripheral (higher MAC)
     |                                       |
     | 1. BLE CONNECT                        |
     |======================================>|
     |                                       |
     | 2. GATT Service Discovery             |
     |   (find service 37145b00-...-e3)      |
     |                                       |
     | 3. READ Identity Characteristic       |
     |   (UUID: ...e6)                       |
     |<--------------------------------------| Returns: 16 bytes (peripheral's identity)
     |                                       |
     | 4. SUBSCRIBE to TX Notifications      |
     |   (write CCCD on ...e4)               |
     |-------------------------------------->|
     |                                       |
     | 5. WRITE RX Characteristic            |
     |   (UUID: ...e5)                       |
     |   Payload: 16 bytes (our identity)    |
     |   Write type: write_with_response     |
     |======================================>| 6. Receive 16-byte write
     |                                       |    - Detect: len(data)==16 AND no identity stored
     |                                       |    - Store identity mapping
     |                                       |    - Create peer interface
     |                                       |    - Create fragmenter/reassembler
     |                                       |
     | 7. Normal fragmented data exchange    |
     |<=====================================>|
```

### Handshake Packet Format

```
Offset  Size   Field
------  ----   -----
0       16     Central's identity hash (raw bytes from RNS.Transport.identity.hash)

Total: exactly 16 bytes
Characteristic: RX (37145b00-442d-4a94-917f-8f42c5da28e5)
Write type: WRITE_WITH_RESPONSE (response=True)
```

This is NOT the same as RNS.Identity.full_hash(). It is the raw 16-byte `identity.hash` property, which is the truncated hash of the public keys.

**Source:** `linux_bluetooth_driver.py:1141-1164`

```python
# Central side - sending handshake
if self._local_identity:
    await client.write_gatt_char(
        self.rx_char_uuid,
        self._local_identity,   # 16 bytes
        response=True           # write_with_response
    )
```

### Handshake Detection (Peripheral Side)

```python
# BLEInterface.py:1174-1279
def _handle_identity_handshake(self, address, data):
    # Detection: exactly 16 bytes
    if len(data) != 16:
        return False  # Not a handshake

    # If we already have identity for this address
    peer_identity = self.address_to_identity.get(address)
    if peer_identity:
        if data == peer_identity:
            return True   # Duplicate handshake, consume silently
        else:
            return True   # Mismatched 16 bytes, consume (don't pass to reassembler)

    # Store the central's identity
    central_identity = bytes(data)
    identity_hash = central_identity.hex()[:16]  # 8-byte truncated hash for map key

    # Store mappings
    self.address_to_identity[address] = central_identity
    self.identity_to_address[identity_hash] = address

    # Create fragmenter/reassembler and spawn peer interface
    ...
    return True
```

### Edge Cases

**Q: What if the first real data packet happens to be exactly 16 bytes?**
A: Only 16-byte packets received BEFORE identity is established are treated as handshakes. Once `address_to_identity[address]` is set, all subsequent 16-byte packets go through normal fragment reassembly.

**Q: What if the handshake arrives twice?**
A: Idempotent. The duplicate is detected (`data == peer_identity`) and consumed silently.

**Q: What if the handshake fails?**
A: Non-fatal. The peripheral drops all subsequent data for that address until identity is learned through another mechanism (e.g., reconnection).

**Source:** `BLE_PROTOCOL_v2.2.md:296-304`, `BLEInterface.py:1174-1279`

### Identity Source

The 16-byte identity is `RNS.Transport.identity.hash`, set on the driver via:
```python
# BLEInterface.py:583-627
identity_hash = Transport.identity.hash   # 16 bytes
self.driver.set_identity(identity_hash)
self.driver.start_advertising(self.device_name, identity_hash)
```

And on Android (Columba), it follows the same flow through the KotlinBLEBridge.

---

## 4. Fragmentation Protocol

### Why

BLE MTU is limited:
- Minimum: 23 bytes (BLE 4.0)
- Common: 185 bytes (BLE 4.2+)
- Maximum: 517 bytes (BLE 5.0+)

Reticulum packets can be up to ~500 bytes (HW_MTU = 500 in BLEInterface), requiring fragmentation.

### Fragment Header Format

```
Byte Order: Network (big-endian), using struct format "!BHH"

Offset  Size  Type      Field           Values
------  ----  --------  -----------     ------
0       1     uint8     Fragment Type   0x01=START, 0x02=CONTINUE, 0x03=END
1       2     uint16    Sequence Number 0 to 65535
3       2     uint16    Total Fragments 1 to 65535

Total header: 5 bytes
Payload: (MTU - 5) bytes
```

**Source:** `BLEFragmentation.py:33-38, 61-67`

```python
# BLEFragmentation.py:61-66
TYPE_START = 0x01
TYPE_CONTINUE = 0x02
TYPE_END = 0x03
HEADER_SIZE = 5  # 1 byte type + 2 bytes sequence + 2 bytes total
```

### Fragment Types

| Type | Value | Meaning |
|------|-------|---------|
| START | 0x01 | First fragment of a packet |
| CONTINUE | 0x02 | Middle fragment |
| END | 0x03 | Last fragment |

**Note:** Columba (Android) also defines `FRAGMENT_TYPE_LONE = 0x00` in `BleConstants.kt:143`, but the Python implementation does NOT use this type. Single-fragment packets use START (0x01) type. The Python fragmenter always uses the header, even for single-fragment packets ("Always use fragmentation protocol for consistency" -- `BLEFragmentation.py:125-126`).

### Fragmentation Example

```
Input:  233-byte Reticulum packet
MTU:    23 bytes
Payload per fragment: 23 - 5 = 18 bytes
Fragments needed: ceil(233/18) = 13

Fragment 0: [0x01][0x0000][0x000D][18 bytes data]   (START, seq=0, total=13)
Fragment 1: [0x02][0x0001][0x000D][18 bytes data]   (CONTINUE, seq=1, total=13)
Fragment 2: [0x02][0x0002][0x000D][18 bytes data]   (CONTINUE, seq=2, total=13)
...
Fragment 12:[0x03][0x000C][0x000D][17 bytes data]   (END, seq=12, total=13)
```

With MTU=517 (BLE 5.0), a 500-byte packet fits in a single fragment:
```
Fragment 0: [0x01][0x0000][0x0001][500 bytes data]  (START, seq=0, total=1)
```

### Struct Packing

```python
# BLEFragmentation.py:145-150
header = struct.pack(
    "!BHH",        # Network byte order: uint8, uint16, uint16
    frag_type,     # 1 byte
    i,             # sequence number, 2 bytes
    num_fragments  # total fragments, 2 bytes
)
fragment = header + data
```

For Kotlin port, this is:
```kotlin
// Big-endian (network byte order)
val header = ByteArray(5)
header[0] = fragType
header[1] = (sequence shr 8).toByte()
header[2] = (sequence and 0xFF).toByte()
header[3] = (totalFragments shr 8).toByte()
header[4] = (totalFragments and 0xFF).toByte()
```

### Reassembly

**Source:** `BLEFragmentation.py:176-428`

State per sender:
```python
# BLEFragmentation.py:197-198
self.reassembly_buffers = {}
# Key: (sender_id, sequence // total, total)
# Value: {
#     'fragments': {seq: data},     # dict mapping sequence number to payload
#     'total': int,                  # expected total count
#     'start_time': float,           # when first fragment arrived
#     'sender_id': sender_id         # originating address
# }
```

Reassembly rules:
1. Fragment with sequence 0 creates a new buffer
2. Non-zero sequence fragments are matched to existing buffers by `(sender_id, total, timeout_check)`
3. When `len(fragments) == total`, reassemble in sequence order
4. Duplicate fragments with identical data are silently ignored
5. Duplicate fragments with different data cause the entire buffer to be discarded (corruption)
6. Total-count mismatch within a packet causes buffer discard

**Timeout:** 30 seconds default (`BLEReassembler.DEFAULT_TIMEOUT = 30.0`). Periodic cleanup runs every 30 seconds via `_start_cleanup_timer()`.

**Source:** `BLEFragmentation.py:184-186`, `BLEInterface.py:696-698`

---

## 5. MTU Negotiation

### Mechanism

MTU negotiation happens at the BLE GATT level, NOT at the application protocol level. The protocol does NOT define its own MTU negotiation messages.

### Central Side (Linux/bleak)

Three fallback methods attempted in order:

```python
# linux_bluetooth_driver.py:1285-1324
# Method 1: Read from GATT characteristic property (BlueZ 5.62+)
char_props["MTU"]

# Method 2: _acquire_mtu() on older BlueZ
await client._backend._acquire_mtu()

# Method 3: Fallback to client.mtu_size
mtu = client.mtu_size

# Ultimate fallback: 23 (BLE 4.0 minimum)
```

### Central Side (Android/Kotlin)

```kotlin
// Android requests maximum MTU of 517
// BleConstants.kt:69
const val MAX_MTU = 517

// After connection, Android calls requestMtu(517)
// onMtuChanged callback provides the negotiated value
```

### Peripheral Side

MTU is managed by the BLE stack (BlueZ on Linux, Android BLE stack on Android). The peripheral does NOT explicitly negotiate -- it accepts whatever the central requests.

On the GATT server side, the MTU is extracted from D-Bus callback options:
```python
# BLEGATTServer.py:170-171
mtu = options.get("mtu", None)
```

### How MTU Affects Fragmentation

The fragmenter is created with the negotiated MTU:
```python
# BLEInterface.py:1134
self.fragmenters[frag_key] = BLEFragmenter(mtu=mtu)

# BLEFragmentation.py:68-77
def __init__(self, mtu=185):
    self.mtu = max(mtu, 20)          # Floor at 20
    self.payload_size = self.mtu - self.HEADER_SIZE  # MTU - 5
```

**Important:** The `mtu` value passed to the fragmenter is the GATT ATT MTU, which already accounts for the 3-byte ATT protocol header. So the fragmenter subtracts only its own 5-byte header, NOT an additional 3 bytes.

### MTU Race Condition

MTU negotiation may complete before or after identity is established. The code handles this with a `pending_mtu` dict:

```python
# BLEInterface.py:1120-1127
def _mtu_negotiated_callback(self, address, mtu):
    peer_identity = self.address_to_identity.get(address)
    if not peer_identity:
        # Race: MTU arrived before identity
        self.pending_mtu[address] = mtu
        return
    # Normal: create fragmenter with negotiated MTU
```

When identity arrives later, pending MTU is applied:
```python
# BLEInterface.py:1000-1004
if address in self.pending_mtu:
    pending_mtu = self.pending_mtu.pop(address)
    self._mtu_negotiated_callback(address, pending_mtu)
```

---

## 6. Deduplication

### Packet-Level Dedup

The BLE interface does **NOT** implement packet-level deduplication. This is handled by Reticulum's Transport layer, which deduplicates based on packet hashes.

### Connection-Level Dedup (Identity-Based)

The BLE interface deduplicates at the **connection level** to prevent the same identity from having multiple simultaneous connections (caused by Android MAC rotation or dual central+peripheral connections).

**Source:** `BLEInterface.py:1023-1110`

```python
def _check_duplicate_identity(self, address, peer_identity):
    identity_hash = self._compute_identity_hash(peer_identity)
    existing_address = self.identity_to_address.get(identity_hash)

    if existing_address and existing_address != address:
        # Same identity, different MAC
        # Check 1: pending detach? -> allow (old connection dying)
        # Check 2: old address still in connected_peers? -> if not, allow
        # Check 3: zombie connection? -> if no data for 30s, allow
        # Otherwise: reject (true duplicate)
        ...
```

### Dual Connection Deduplication (Columba/Android)

When MAC sorting determines connection direction but both devices have overlapping scan/advertise timing, BOTH a central and peripheral connection may form simultaneously. The Kotlin bridge detects this and closes one:

```kotlin
// KotlinBLEBridge.kt (conceptual)
// When same identity found at two addresses:
// - Close the central connection (prefer peripheral -- lower overhead)
// - Call on_address_changed(old_address, new_address, identity_hash)
```

### Fragment Dedup

Fragment-level dedup exists within the reassembler:

```python
# BLEFragmentation.py:288-308
if sequence in buffer['fragments']:
    existing_data = buffer['fragments'][sequence]
    if existing_data == data:
        return None  # Benign duplicate, ignore
    else:
        del self.reassembly_buffers[packet_key]  # Corruption, discard entire buffer
        raise ValueError("Fragment data mismatch")
```

---

## 7. Wire Format Summary

### All Message Types Exchanged Between BLE Peers

#### Message Type 1: Identity Handshake

```
Direction: Central -> Peripheral (written to RX characteristic)
When: Immediately after connection setup, before any data
Size: Exactly 16 bytes
Write type: WRITE_WITH_RESPONSE

Byte Layout:
+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+
| B0 | B1 | B2 | B3 | B4 | B5 | B6 | B7 | B8 | B9 | BA | BB | BC | BD | BE | BF |
+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+
|                    16-byte Reticulum Transport Identity Hash                    |
+---------------------------------------------------------------------------------+

Value: RNS.Transport.identity.hash (raw bytes, NOT hex-encoded)
```

#### Message Type 2: Data Fragment

```
Direction: Bidirectional
  Central -> Peripheral: Written to RX characteristic (WRITE_WITHOUT_RESPONSE for data)
  Peripheral -> Central: Sent via TX notification

Byte Layout:
+------+-----------+-----------+---------------------------+
| Type | Seq (MSB) | Seq (LSB) | Total (MSB) | Total (LSB)| Payload...
+------+-----------+-----------+-------------+-------------+----------
| 1B   |    2B (big-endian)    |    2B (big-endian)        | (MTU-5)B
+------+-----------------------+---------------------------+----------

Type values:
  0x01 = START (first fragment)
  0x02 = CONTINUE (middle fragment)
  0x03 = END (last fragment)

Sequence: 0-indexed fragment number (0, 1, 2, ...)
Total: Total number of fragments in this packet (1, 2, 3, ...)

Maximum payload per fragment: MTU - 5 bytes
Minimum fragment size: 6 bytes (5 header + 1 data)
Maximum sequence value: 65535 (uint16)
```

#### Message Type 3: Keep-Alive

```
Direction: Central -> Peripheral (or either direction on Android)
When: Every 15 seconds during idle periods
Size: Exactly 1 byte
Write type: WRITE_WITHOUT_RESPONSE

Byte Layout:
+------+
| 0x00 |
+------+

Purpose: Prevents Android BLE supervision timeout (20-30s inactivity = disconnect)
Handling: Receiver detects len(data)==1 && data[0]==0x00, ignores silently
```

**Source:** `BLEInterface.py:1968-1971`, `BleConstants.kt:222-224`

```python
# BLEInterface.py:1968-1971
if len(data) == 1 and data[0] == 0x00:
    RNS.log(f"received keep-alive from peer, ignoring", RNS.LOG_EXTREME)
    return
```

#### Message Type 4: Identity Characteristic Read (GATT Read, not a packet)

```
Direction: Central reads from peripheral's Identity characteristic
When: During connection setup, before handshake
Size: 16 bytes (or 0 if not yet set)
UUID: 37145b00-442d-4a94-917f-8f42c5da28e6

Byte Layout:
+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+----+
|                    16-byte Reticulum Transport Identity Hash                    |
+---------------------------------------------------------------------------------+

Same format as handshake identity.
Empty (0 bytes) if Transport.identity is not loaded yet.
```

### Distinguishing Message Types

The receiver uses this decision tree:

```
receive(data, address):
    if len(data) == 1 AND data[0] == 0x00:
        -> Keep-alive, ignore

    if len(data) == 16 AND no identity stored for address:
        -> Identity handshake, process as handshake

    if len(data) >= 5 AND data[0] in {0x01, 0x02, 0x03}:
        -> Fragment, pass to reassembler

    else:
        -> Unknown/malformed, drop with warning
```

**Critical ambiguity:** A 16-byte data fragment could be confused with a handshake if identity is not yet stored. The protocol relies on the ordering invariant: the handshake is ALWAYS the first packet on a new connection, and identity is stored before any data fragments arrive.

---

## 8. Connection Lifecycle

### States

```
IDLE -> SCANNING -> DISCOVERED -> CONNECTING -> CONNECTED -> DATA_EXCHANGE -> DISCONNECTED
                                                    |
                                         IDENTITY_PENDING (peripheral only)
                                                    |
                                         IDENTITY_RECEIVED -> DATA_EXCHANGE
```

### Detailed State Machine

#### Central Role (Lower MAC):

```
1. SCANNING
   - Scan for service UUID 37145b00-...-e3
   - Filter by MIN_RSSI (-85 dBm)
   - Score discovered peers (RSSI 60%, history 30%, recency 10%)

2. DISCOVERED
   - MAC sorting check: only proceed if our MAC < peer MAC
   - Blacklist check: skip if peer recently failed
   - Rate limit: 5 seconds between attempts to same peer

3. CONNECTING
   - BLE connect (with timeout: 30s default)
   - Optional LE-specific D-Bus connection (BlueZ >= 5.49)
   - Service discovery delay (1.5s default for bluezero timing)

4. SERVICE_DISCOVERY
   - Find Reticulum service by UUID
   - Locate RX, TX, Identity characteristics

5. IDENTITY_READ
   - Read Identity characteristic (16 bytes)
   - Check for duplicate identity (MAC rotation)

6. MTU_NEGOTIATION
   - Three fallback methods (see section 5)

7. NOTIFICATION_SETUP
   - Subscribe to TX characteristic notifications
   - Retry up to 3 times with backoff (0.2s, 0.5s, 1.0s)

8. HANDSHAKE_SEND
   - Write 16-byte identity to RX characteristic
   - write_with_response=True

9. CONNECTED
   - Create PeerConnection in driver
   - Create BLEPeerInterface
   - Create BLEFragmenter (with negotiated MTU)
   - Create BLEReassembler (30s timeout)
   - Register interface with RNS.Transport

10. DATA_EXCHANGE
    - Send: fragment -> write to RX (WRITE_WITHOUT_RESPONSE)
    - Receive: TX notification -> reassemble -> deliver to Transport
```

#### Peripheral Role (Higher MAC):

```
1. ADVERTISING
   - Advertise service UUID
   - Serve Identity characteristic with local identity hash
   - Accept incoming connections

2. CONNECTION_ACCEPTED
   - BLE stack accepts connection
   - Track as pending identity connection (30s timeout)

3. IDENTITY_PENDING
   - Wait for 16-byte write to RX characteristic (handshake)
   - If timeout (30s): disconnect

4. HANDSHAKE_RECEIVED
   - Detect: len(data)==16 AND no identity for this address
   - Store identity mapping
   - Get MTU (from GATT server callback options, default 185)
   - Create fragmenter/reassembler
   - Spawn peer interface

5. DATA_EXCHANGE
   - Send: fragment -> TX notification
   - Receive: RX write -> reassemble -> deliver to Transport
```

### Reconnection

- **Blacklist with exponential backoff:** After 3 failures, blacklist for 60s, then 120s, 240s, 480s (capped at 8x)
- **Blacklist cleared on success:** Any successful connection resets the blacklist
- **MAC rotation handling:** Identity-based tracking survives MAC changes. Old address mappings cleaned up with 2-second grace period
- **Zombie detection:** Connections with keepalives but no real data for 30s are considered zombies and can be replaced

### Disconnection Cleanup

```python
# BLEInterface.py:1295-1378
1. Remove from peers dict
2. Cache identity (60s TTL) for quick reconnection
3. Check if other addresses still have same identity
   - YES: Keep interface alive, update address mapping
   - NO: Schedule detach with 2s grace period
4. After grace period, if no new connection: detach interface
   - Remove from Transport.interfaces
   - Clean up fragmenter/reassembler
   - Clean up identity mappings
```

### Timeout Values

| Timeout | Value | Purpose |
|---------|-------|---------|
| Connection timeout | 30s | BLE connection establishment |
| Identity handshake timeout | 30s | Wait for identity on peripheral side |
| Reassembly timeout | 30s | Incomplete fragment buffers |
| Cleanup interval | 30s | Periodic stale buffer cleanup |
| Identity cache TTL | 60s | Cached identity for reconnecting peers |
| Pending detach grace period | 2s | Allow new connections before detaching interface |
| Zombie timeout | 30s | No real data = zombie connection |
| Keepalive interval | 15s | Android BLE supervision timeout prevention |
| Blacklist base | 60s (Python), 30s (Android) | Base backoff for failed connections |
| Service discovery delay | 1.5s | BlueZ D-Bus registration timing |

---

## 9. Identity-Based Keying

### Why Not MAC Addresses

BLE devices can rotate MAC addresses for privacy (Android does this every ~15 minutes). If fragmenters/reassemblers are keyed by MAC address, they become orphaned on rotation.

### Key Scheme

All peer-specific data structures use the peer's 16-byte identity as key:

```python
# For fragmenters and reassemblers (full precision):
frag_key = peer_identity.hex()  # 32-char hex string (full 16 bytes)

# For spawned_interfaces and identity_to_address (truncated):
identity_hash = peer_identity.hex()[:16]  # 16-char hex string (8 bytes = 64 bits)
```

### Mapping Tables

```
address_to_identity:  MAC address -> 16-byte identity (bytes)
identity_to_address:  16-char hex  -> MAC address (current)
address_to_interface: MAC address  -> BLEPeerInterface
spawned_interfaces:   16-char hex  -> BLEPeerInterface
fragmenters:          32-char hex  -> BLEFragmenter
reassemblers:         32-char hex  -> BLEReassembler
```

When MAC rotates:
1. New address discovered with same identity (via GATT read)
2. Old address removed from address_to_identity
3. New address added to address_to_identity
4. identity_to_address updated to new address
5. Fragmenter/reassembler UNCHANGED (keyed by identity, not address)
6. BLEPeerInterface.peer_address updated

**Source:** `BLEInterface.py:1858-1890`

---

## 10. Advertising Packet Layout

### v2.2 (Standard)

```
Main Advertising Packet (31 bytes max):
+-- Flags AD (3 bytes)
|   Type: 0x01, Data: 0x06 (LE General Discoverable + BR/EDR Not Supported)
|
+-- Complete 128-bit Service UUID (18 bytes)
|   Type: 0x07, Data: 37145b00-442d-4a94-917f-8f42c5da28e3
|
+-- Remaining: ~10 bytes (for optional short device name)

Scan Response Packet (31 bytes max):
+-- Complete Local Name (variable)
    "RNS-{first 3 bytes of identity hex}" or omitted
    Example: "RNS-1b9d2b" (10 bytes)
```

### v0.3.0 (With Capability Flags)

```
Main Advertising Packet (31 bytes max):
+-- Flags AD (3 bytes)
+-- Complete 128-bit Service UUID (18 bytes)
+-- Manufacturer Specific Data (5 bytes)
|   Type: 0xFF
|   Data: [CID_lo=0xFF][CID_hi=0xFF][Version=0x03][Flags]
|
+-- Remaining: ~5 bytes
```

---

## 11. Platform-Specific Notes for Kotlin Port

### Android Differences from Python/Linux

1. **MAC rotation:** Android rotates BLE MAC every ~15 minutes. Identity-based keying is essential.

2. **MTU:** Android requests 517 bytes via `requestMtu()`. Most Android devices negotiate 512-517.

3. **Keepalive:** Android requires keepalive packets (0x00) every 15 seconds or the BLE supervision timeout kills the connection (status code 8).

4. **GATT server:** Android's `BluetoothGattServer` API is callback-based, unlike bluezero's blocking model.

5. **Write type:** For data fragments, use `WRITE_WITHOUT_RESPONSE` (faster, no ACK). For identity handshake, use `WRITE_WITH_RESPONSE` (reliable).

6. **Pairing:** Android-to-Android BLE requires "Just Works" or Numeric Comparison pairing. The Columba implementation avoids bonding and relies on app-layer identity tracking instead.

7. **Operation serialization:** Android BLE requires operations to be serialized (one at a time). Columba uses `BleOperationQueue` for this.

8. **GATT error 133:** Common Android-specific error indicating stack issues. Requires retry with backoff.

### Key Constants for Port

```kotlin
val SERVICE_UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e3")
val RX_CHAR_UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e5")
val TX_CHAR_UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e4")
val IDENTITY_CHAR_UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e6")
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

const val FRAGMENT_HEADER_SIZE = 5
const val FRAGMENT_TYPE_START: Byte = 0x01
const val FRAGMENT_TYPE_CONTINUE: Byte = 0x02
const val FRAGMENT_TYPE_END: Byte = 0x03

const val REASSEMBLY_TIMEOUT_MS = 30_000L
const val KEEPALIVE_INTERVAL_MS = 15_000L
const val IDENTITY_SIZE = 16  // bytes

const val HW_MTU = 500  // Reticulum standard
const val MIN_BLE_MTU = 23
const val DEFAULT_BLE_MTU = 185
const val MAX_BLE_MTU = 517
```

---

## 12. Data Flow Diagram (Send Path)

```
Application
    |
    v
Transport.outbound(data, interface)
    |
    v
BLEPeerInterface.process_outgoing(data)
    |
    v
BLEFragmenter.fragment_packet(data)
    |  returns: [frag0, frag1, ..., fragN]
    v
For each fragment:
    driver.send(peer_address, fragment)
        |
        +-- Central role: client.write_gatt_char(RX_UUID, fragment, response=False)
        |
        +-- Peripheral role: gatt_server.send_notification(address, fragment)
```

## 13. Data Flow Diagram (Receive Path)

```
BLE Stack Notification/Write Callback
    |
    v
driver.on_data_received(address, data)
    |
    v
BLEInterface._data_received_callback(address, data)
    |
    +-- Is identity handshake? (len==16 && no identity stored)
    |   YES: _handle_identity_handshake() -> return
    |
    +-- Is keepalive? (len==1 && data[0]==0x00)
    |   YES: ignore -> return
    |
    v
BLEInterface._handle_ble_data(address, data)
    |
    v
Look up peer_identity from address_to_identity
    |  (with identity cache fallback)
    v
Compute frag_key = peer_identity.hex()
    |
    v
BLEReassembler.receive_fragment(data, sender_id=address)
    |
    +-- Parse header: type, sequence, total
    +-- Store fragment in buffer
    +-- If all fragments received: reassemble -> return complete packet
    |
    v
BLEPeerInterface.process_incoming(complete_packet)
    |
    v
Transport.inbound(data, receiving_interface)
```
