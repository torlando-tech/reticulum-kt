# Phase 1: Python Bridge Extension - Research

**Researched:** 2026-01-23
**Domain:** Python bridge server command extension for LXMF interoperability testing
**Confidence:** HIGH

## Summary

This phase extends the existing Python bridge server (`python-bridge/bridge_server.py`) to support LXMF operations. The bridge server already has 70+ commands for RNS operations and follows a proven pattern for cross-implementation testing.

The work is primarily Python development following established patterns. New commands need to:
1. Create LXMessage objects from provided parameters
2. Serialize/deserialize messages to/from wire format
3. Compute message hashes
4. Generate and validate stamps (proof-of-work)

**Primary recommendation:** Add 5-8 new `cmd_*` functions following the exact pattern of existing bridge commands, importing LXMF modules directly from `~/repos/LXMF`.

## Standard Stack

### Core (Already in Bridge)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Python 3.x | System | Runtime | Already required by bridge |
| RNS | Local | Crypto primitives | Already imported |
| umsgpack | Vendored | Message serialization | Already used for payload packing |

### Supporting (Need to Import)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| LXMF.LXMessage | ~/repos/LXMF | Message class | Message creation, packing, unpacking |
| LXMF.LXStamper | ~/repos/LXMF | Stamp operations | Workblock generation, stamp validation |

### No Additional Dependencies Required

The bridge already has all necessary infrastructure:
- Direct module loading via `importlib.util.spec_from_file_location()`
- Hex encoding/decoding helpers (`hex_to_bytes`, `bytes_to_hex`)
- JSON request/response handling
- Crypto primitives (SHA256, HKDF, Ed25519)
- Msgpack serialization via umsgpack

## Architecture Patterns

### Existing Bridge Pattern (MUST FOLLOW)

The bridge server uses a consistent pattern for all commands:

```python
def cmd_operation_name(params):
    """Docstring describing the operation."""
    # 1. Extract and decode parameters
    param1 = hex_to_bytes(params['hex_param'])
    param2 = params['string_param']
    param3 = int(params.get('optional_param', 0))

    # 2. Perform operation
    result = some_operation(param1, param2, param3)

    # 3. Return results as dictionary (bytes as hex)
    return {
        'result_field': bytes_to_hex(result),
        'other_field': value
    }

# Register in COMMANDS dict
COMMANDS = {
    ...
    'operation_name': cmd_operation_name,
    ...
}
```

### Recommended Project Structure

```
python-bridge/
├── bridge_server.py      # Add LXMF commands here (lines ~2700-2709)
```

No new files needed - all commands go in the existing bridge_server.py.

### LXMF Command Pattern

```python
# Near the top of bridge_server.py, after RNS imports
lxmf_path = os.path.abspath(os.environ.get('PYTHON_LXMF_PATH', '../../../LXMF'))
sys.path.insert(0, lxmf_path)

# Import LXMF modules
import LXMF.LXMessage as LXMessage
import LXMF.LXStamper as LXStamper
```

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Message hashing | Manual SHA256 | LXMessage.unpack_from_bytes() | Hash computation uses specific byte layout |
| Stamp validation | Custom POW check | LXStamper.stamp_valid() | Must match exact algorithm |
| Wire format packing | Manual msgpack | LXMessage.pack() | Payload structure has edge cases |
| Workblock generation | Manual HKDF loops | LXStamper.stamp_workblock() | Exact round count and salt computation matters |

**Key insight:** LXMF wire format compatibility requires using the exact Python implementation. The hash computation in particular depends on precise byte ordering and msgpack encoding.

## Common Pitfalls

### Pitfall 1: Trying to Create Full LXMessage Without Destinations
**What goes wrong:** LXMessage constructor requires RNS Destination objects
**Why it happens:** The constructor validates destination/source types
**How to avoid:** Use `destination_hash` and `source_hash` parameters, pass `destination=None, source=None`
**Warning signs:** `ValueError: LXMessage initialised with invalid destination`

### Pitfall 2: Signature Without Identity
**What goes wrong:** Cannot call `pack()` without a source identity that can sign
**Why it happens:** pack() calls `source.sign(signed_part)`
**How to avoid:** For testing serialization, use pre-computed signatures or create test identities
**Warning signs:** `AttributeError: 'NoneType' object has no attribute 'sign'`

### Pitfall 3: Stamp in Payload vs Message
**What goes wrong:** Hash computed with stamp included gives wrong result
**Why it happens:** Hash uses payload WITHOUT stamp; stamp is added after
**How to avoid:** Follow exact unpacking: extract stamp from payload, repack without stamp for hash
**Warning signs:** Hash mismatch between Kotlin and Python

### Pitfall 4: Msgpack Integer Encoding
**What goes wrong:** Small integers serialize differently
**Why it happens:** Msgpack uses variable-length encoding (fixint vs int8/16/32)
**How to avoid:** Use same msgpack library (umsgpack from RNS vendor)
**Warning signs:** Hash mismatch on messages with certain timestamp values

## Code Examples

### Command: Create LXMessage and Pack
```python
# Source: LXMF/LXMessage.py pack() method
def cmd_lxmf_pack(params):
    """Pack LXMF message from components."""
    destination_hash = hex_to_bytes(params['destination_hash'])
    source_hash = hex_to_bytes(params['source_hash'])
    title = params.get('title', '')
    content = params.get('content', '')
    timestamp = float(params['timestamp'])
    fields = params.get('fields', {})

    # Build payload: [timestamp, title, content, fields]
    payload = [
        timestamp,
        title.encode('utf-8'),
        content.encode('utf-8'),
        {int(k): v for k, v in fields.items()} if fields else {}
    ]

    # Pack payload
    packed_payload = umsgpack.packb(payload)

    # Compute hash: SHA256(dest_hash + source_hash + packed_payload)
    hashed_part = destination_hash + source_hash + packed_payload
    message_hash = hashlib.sha256(hashed_part).digest()

    # Signed part: hashed_part + hash
    signed_part = hashed_part + message_hash

    return {
        'hashed_part': bytes_to_hex(hashed_part),
        'message_hash': bytes_to_hex(message_hash),
        'signed_part': bytes_to_hex(signed_part),
        'packed_payload': bytes_to_hex(packed_payload)
    }
```

### Command: Unpack LXMessage
```python
# Source: LXMF/LXMessage.py unpack_from_bytes()
def cmd_lxmf_unpack(params):
    """Unpack LXMF message bytes."""
    lxmf_bytes = hex_to_bytes(params['lxmf_bytes'])

    DEST_LEN = 16
    SIG_LEN = 64

    destination_hash = lxmf_bytes[:DEST_LEN]
    source_hash = lxmf_bytes[DEST_LEN:2*DEST_LEN]
    signature = lxmf_bytes[2*DEST_LEN:2*DEST_LEN+SIG_LEN]
    packed_payload = lxmf_bytes[2*DEST_LEN+SIG_LEN:]

    unpacked = umsgpack.unpackb(packed_payload)

    # Extract stamp if present
    stamp = None
    if len(unpacked) > 4:
        stamp = unpacked[4]
        unpacked = unpacked[:4]
        packed_payload = umsgpack.packb(unpacked)  # Repack without stamp

    # Compute hash
    hashed_part = destination_hash + source_hash + packed_payload
    message_hash = hashlib.sha256(hashed_part).digest()

    return {
        'destination_hash': bytes_to_hex(destination_hash),
        'source_hash': bytes_to_hex(source_hash),
        'signature': bytes_to_hex(signature),
        'timestamp': unpacked[0],
        'title': unpacked[1].decode('utf-8') if isinstance(unpacked[1], bytes) else unpacked[1],
        'content': unpacked[2].decode('utf-8') if isinstance(unpacked[2], bytes) else unpacked[2],
        'fields': unpacked[3],
        'stamp': bytes_to_hex(stamp) if stamp else None,
        'message_hash': bytes_to_hex(message_hash)
    }
```

### Command: Generate Stamp Workblock
```python
# Source: LXMF/LXStamper.py stamp_workblock()
def cmd_lxmf_stamp_workblock(params):
    """Generate stamp workblock from message ID."""
    message_id = hex_to_bytes(params['message_id'])
    expand_rounds = int(params.get('expand_rounds', 3000))

    workblock = LXStamper.stamp_workblock(message_id, expand_rounds=expand_rounds)

    return {
        'workblock': bytes_to_hex(workblock),
        'size': len(workblock)
    }
```

### Command: Validate Stamp
```python
# Source: LXMF/LXStamper.py stamp_valid()
def cmd_lxmf_stamp_valid(params):
    """Validate stamp against workblock and cost."""
    stamp = hex_to_bytes(params['stamp'])
    target_cost = int(params['target_cost'])
    workblock = hex_to_bytes(params['workblock'])

    valid = LXStamper.stamp_valid(stamp, target_cost, workblock)
    value = LXStamper.stamp_value(workblock, stamp) if valid else 0

    return {
        'valid': valid,
        'value': value
    }
```

### Command: Generate Stamp
```python
# Source: LXMF/LXStamper.py generate_stamp()
def cmd_lxmf_stamp_generate(params):
    """Generate stamp meeting target cost."""
    message_id = hex_to_bytes(params['message_id'])
    stamp_cost = int(params['stamp_cost'])
    expand_rounds = int(params.get('expand_rounds', 3000))

    stamp, value = LXStamper.generate_stamp(message_id, stamp_cost, expand_rounds=expand_rounds)

    return {
        'stamp': bytes_to_hex(stamp) if stamp else None,
        'value': value
    }
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Static test vectors | Dynamic Python bridge | Already in use | Byte-perfect interop verification |
| Manual msgpack encoding | umsgpack from RNS vendor | Already in use | Exact encoding compatibility |

**Current patterns in codebase:**
- All interop tests extend `InteropTestBase`
- Tests use `bridge.executeSuccess(command, params)` or `python(command, pairs...)`
- Byte arrays passed as hex strings
- Tests compare Kotlin output to Python output

## Open Questions

### 1. Stamp Generation Timeout
- **What we know:** Python stamp generation uses multiprocessing and can take significant time
- **What's unclear:** Should the bridge command support async/timeout for stamp generation?
- **Recommendation:** Start with synchronous generation with modest cost (8-12), add timeout parameter later if needed

### 2. Full LXMessage Creation
- **What we know:** Creating a "real" LXMessage requires RNS Identity and Destination objects
- **What's unclear:** How much of the Python LXMF stack to import vs. replicate
- **Recommendation:** Keep commands stateless - pass pre-computed values rather than manage Python objects

## Required Bridge Commands

Based on success criteria, these commands are needed:

| Command | Purpose | Inputs | Outputs |
|---------|---------|--------|---------|
| `lxmf_pack` | Pack message fields to bytes | dest_hash, source_hash, title, content, timestamp, fields | hashed_part, message_hash, packed_payload |
| `lxmf_unpack` | Unpack message bytes | lxmf_bytes | dest_hash, source_hash, signature, timestamp, title, content, fields, stamp, message_hash |
| `lxmf_hash` | Compute message hash | dest_hash, source_hash, timestamp, title, content, fields | message_hash |
| `lxmf_stamp_workblock` | Generate workblock | message_id, expand_rounds | workblock |
| `lxmf_stamp_valid` | Validate stamp | stamp, target_cost, workblock | valid, value |
| `lxmf_stamp_generate` | Generate valid stamp | message_id, stamp_cost | stamp, value |

## Sources

### Primary (HIGH confidence)
- `./python-bridge/bridge_server.py` - Existing 70+ commands showing exact pattern
- `~/repos/LXMF/LXMF/LXMessage.py` - Reference message implementation (pack, unpack_from_bytes)
- `~/repos/LXMF/LXMF/LXStamper.py` - Reference stamp implementation

### Secondary (MEDIUM confidence)
- `./rns-test/src/test/kotlin/network/reticulum/interop/InteropTestBase.kt` - Test pattern
- `./lxmf-core/src/main/kotlin/network/reticulum/lxmf/LXMessage.kt` - Kotlin implementation to test

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing bridge pattern, no new dependencies
- Architecture: HIGH - All patterns already established in 70+ existing commands
- Pitfalls: HIGH - Based on direct code analysis of LXMessage.py

**Research date:** 2026-01-23
**Valid until:** 2026-03-23 (stable - bridge pattern won't change)
