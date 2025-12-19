#!/usr/bin/env python3
"""
LXMF Message Interoperability Test

This script generates LXMF messages using the Python reference implementation
and outputs them in a format that Kotlin tests can verify for byte-perfect
interoperability.

Output format (JSON):
{
    "source_hash": "<hex>",
    "destination_hash": "<hex>",
    "title": "<string>",
    "content": "<string>",
    "timestamp": <float>,
    "message_hash": "<hex>",
    "signature": "<hex>",
    "packed": "<hex>"
}
"""

import sys
import json
import time
import os

# Add LXMF to path
LXMF_PATH = os.path.expanduser("~/repos/LXMF")
if LXMF_PATH not in sys.path:
    sys.path.insert(0, LXMF_PATH)

try:
    import RNS
    import LXMF
except ImportError as e:
    print(f"Error importing RNS/LXMF: {e}", file=sys.stderr)
    print("Make sure Reticulum and LXMF are installed or in your PYTHONPATH", file=sys.stderr)
    sys.exit(1)


def generate_test_message(
    title: str,
    content: str,
    fields: dict = None
) -> dict:
    """
    Generate a test LXMF message and return its packed representation
    along with all components for verification.
    """
    # Create identities for source and destination
    source_identity = RNS.Identity()
    dest_identity = RNS.Identity()

    # Create destinations
    source_destination = RNS.Destination(
        source_identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        "lxmf",
        "delivery"
    )

    dest_destination = RNS.Destination(
        dest_identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf",
        "delivery"
    )

    # Create LXMF message
    lxm = LXMF.LXMessage(
        dest_destination,
        source_destination,
        content,
        title=title,
        fields=fields,
        desired_method=LXMF.LXMessage.DIRECT
    )

    # Pack the message (this triggers signing)
    lxm.pack()

    # Extract components
    # Get public key bytes (64 bytes: X25519 + Ed25519)
    source_pub_bytes = source_identity.get_public_key()

    result = {
        "source_hash": source_destination.hash.hex(),
        "source_public_key": source_pub_bytes.hex(),
        "destination_hash": dest_destination.hash.hex(),
        "title": title,
        "content": content,
        "timestamp": lxm.timestamp,
        "message_hash": lxm.hash.hex(),
        "signature": lxm.signature.hex(),
        "packed": lxm.packed.hex(),
        "packed_len": len(lxm.packed),
        "representation": "PACKET" if lxm.representation == LXMF.LXMessage.PACKET else "RESOURCE"
    }

    if fields:
        result["fields"] = {str(k): str(v) for k, v in fields.items()}

    return result


def main():
    # Suppress RNS logging for cleaner output
    RNS.loglevel = RNS.LOG_CRITICAL

    # Initialize Reticulum in minimal mode (no transport)
    # This is needed before creating destinations
    reticulum = RNS.Reticulum(configdir=None, loglevel=RNS.LOG_CRITICAL)

    test_cases = []

    # Test Case 1: Simple message
    test_cases.append({
        "name": "simple_message",
        "data": generate_test_message(
            title="Hello",
            content="Hello, World!"
        )
    })

    # Test Case 2: Empty content
    test_cases.append({
        "name": "empty_content",
        "data": generate_test_message(
            title="",
            content=""
        )
    })

    # Test Case 3: Unicode content
    test_cases.append({
        "name": "unicode_content",
        "data": generate_test_message(
            title="Test",
            content="Hello, World!"
        )
    })

    # Test Case 4: Large message (triggers RESOURCE representation)
    large_content = "X" * 500
    test_cases.append({
        "name": "large_message",
        "data": generate_test_message(
            title="Large",
            content=large_content
        )
    })

    # Test Case 5: Message with fields
    test_cases.append({
        "name": "message_with_fields",
        "data": generate_test_message(
            title="With Fields",
            content="Test content",
            fields={
                LXMF.FIELD_RENDERER: LXMF.RENDERER_MARKDOWN
            }
        )
    })

    # Output as JSON
    output = {
        "generated_at": time.time(),
        "python_version": sys.version,
        "rns_version": RNS.__version__ if hasattr(RNS, '__version__') else "unknown",
        "test_cases": test_cases
    }

    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
