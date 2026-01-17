#!/usr/bin/env python3
"""
Debug script to inspect the raw bytes of RESOURCE_ADV packets.
This helps identify if the context byte corruption happens during packing.
"""
import sys
import os

sys.path.insert(0, os.path.expanduser("~/repos/LXMF"))
sys.path.insert(0, os.path.expanduser("~/repos/Reticulum"))

import RNS

def main():
    # Initialize Reticulum (needed for constants)
    RNS.Reticulum()

    print("=== Packet Context Values ===")
    print(f"NONE         = 0x{RNS.Packet.NONE:02x}")
    print(f"RESOURCE_ADV = 0x{RNS.Packet.RESOURCE_ADV:02x}")
    print(f"LRRTT        = 0x{RNS.Packet.LRRTT:02x}")
    print(f"LINKCLOSE    = 0x{RNS.Packet.LINKCLOSE:02x}")
    print()

    # Create a mock identity for testing
    identity = RNS.Identity()

    # Create a mock destination
    dest = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "test", "dest")

    print("=== Testing Packet Packing ===")
    print(f"Destination hash: {dest.hash.hex()}")
    print(f"Truncated hash length: {RNS.Reticulum.TRUNCATED_HASHLENGTH//8} bytes")
    print()

    # Test 1: Regular DATA packet with NONE context
    print("--- Test 1: DATA packet with NONE context ---")
    pkt1 = RNS.Packet(dest, b"test data", context=RNS.Packet.NONE, create_receipt=False)
    pkt1.pack()
    print(f"Raw length: {len(pkt1.raw)} bytes")
    print(f"Bytes 0-25: {' '.join(f'{b:02x}' for b in pkt1.raw[:25])}")
    print(f"Byte 18 (context): 0x{pkt1.raw[18]:02x} (expected 0x00 for NONE)")
    print()

    # Test 2: DATA packet with RESOURCE_ADV context
    print("--- Test 2: DATA packet with RESOURCE_ADV context ---")
    pkt2 = RNS.Packet(dest, b"resource adv data", context=RNS.Packet.RESOURCE_ADV, create_receipt=False)
    pkt2.pack()
    print(f"Raw length: {len(pkt2.raw)} bytes")
    print(f"Bytes 0-25: {' '.join(f'{b:02x}' for b in pkt2.raw[:25])}")
    print(f"Byte 18 (context): 0x{pkt2.raw[18]:02x} (expected 0x02 for RESOURCE_ADV)")
    print()

    # Test 3: DATA packet with LRRTT context
    print("--- Test 3: DATA packet with LRRTT context ---")
    pkt3 = RNS.Packet(dest, b"lrrtt data", context=RNS.Packet.LRRTT, create_receipt=False)
    pkt3.pack()
    print(f"Raw length: {len(pkt3.raw)} bytes")
    print(f"Bytes 0-25: {' '.join(f'{b:02x}' for b in pkt3.raw[:25])}")
    print(f"Byte 18 (context): 0x{pkt3.raw[18]:02x} (expected 0xfe for LRRTT)")
    print()

    # Test 4: DATA packet with LINKCLOSE context
    print("--- Test 4: DATA packet with LINKCLOSE context ---")
    pkt4 = RNS.Packet(dest, b"close data", context=RNS.Packet.LINKCLOSE, create_receipt=False)
    pkt4.pack()
    print(f"Raw length: {len(pkt4.raw)} bytes")
    print(f"Bytes 0-25: {' '.join(f'{b:02x}' for b in pkt4.raw[:25])}")
    print(f"Byte 18 (context): 0x{pkt4.raw[18]:02x} (expected 0xfc for LINKCLOSE)")
    print()

    # Verify wire format
    print("=== Wire Format Verification ===")
    print("Expected HEADER_1 format:")
    print("  [0]     = flags")
    print("  [1]     = hops")
    print("  [2-17]  = destination hash (16 bytes)")
    print("  [18]    = context")
    print("  [19+]   = data")
    print()

    # Check if all context bytes are correct
    all_correct = True
    tests = [
        (pkt1, 0x00, "NONE"),
        (pkt2, 0x02, "RESOURCE_ADV"),
        (pkt3, 0xfe, "LRRTT"),
        (pkt4, 0xfc, "LINKCLOSE"),
    ]

    print("=== Context Byte Verification ===")
    for pkt, expected, name in tests:
        actual = pkt.raw[18]
        status = "OK" if actual == expected else "FAIL"
        if actual != expected:
            all_correct = False
        print(f"{name}: expected 0x{expected:02x}, got 0x{actual:02x} - {status}")

    print()
    if all_correct:
        print("SUCCESS: All context bytes are correctly packed by Python!")
        print("The corruption must be happening elsewhere (transmission or Kotlin unpacking).")
    else:
        print("FAILURE: Some context bytes are incorrectly packed!")
        return 1

    return 0

if __name__ == "__main__":
    sys.exit(main())
