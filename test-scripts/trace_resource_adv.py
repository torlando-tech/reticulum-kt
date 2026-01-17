#!/usr/bin/env python3
"""
Trace the exact bytes sent for RESOURCE_ADV packets.
Patches the TCPInterface.process_outgoing to log packet headers.
"""
import sys
import os

sys.path.insert(0, os.path.expanduser("~/repos/LXMF"))
sys.path.insert(0, os.path.expanduser("~/repos/Reticulum"))

import RNS

# Monkey-patch TCPClientInterface to log outgoing packets
original_process_outgoing = None

def patched_process_outgoing(self, data):
    """Patched version that logs packet headers before sending."""
    # Only log larger packets that might be RESOURCE_ADV
    if len(data) >= 100:
        header_bytes = ' '.join(f'[{i}]=0x{b:02x}' for i, b in enumerate(data[:25]))
        context_byte = data[18] if len(data) > 18 else None
        context_name = {
            0x00: "NONE",
            0x02: "RESOURCE_ADV",
            0x03: "RESOURCE_REQ",
            0xfe: "LRRTT",
            0xfc: "LINKCLOSE",
        }.get(context_byte, f"0x{context_byte:02x}" if context_byte else "?")
        print(f"[Python SEND] {len(data)}-byte packet, context={context_name}: {header_bytes}")

    return original_process_outgoing(self, data)

def main():
    global original_process_outgoing

    # Initialize Reticulum first
    config_path = "/tmp/rns_test_config"
    RNS.Reticulum(configdir=config_path, loglevel=RNS.LOG_DEBUG)

    # Now patch the process_outgoing method
    from RNS.Interfaces.TCPInterface import TCPClientInterface
    original_process_outgoing = TCPClientInterface.process_outgoing
    TCPClientInterface.process_outgoing = patched_process_outgoing

    print("Patched TCPClientInterface.process_outgoing for tracing")

    # Now run the actual test
    import LXMF
    import time

    ECHO_BOT_HASH = bytes.fromhex("af75975ced6ded2d7a811808a4f56383")

    # Create LXMF router
    storage_path = "/tmp/lxmf_test_storage"
    os.makedirs(storage_path, exist_ok=True)
    router = LXMF.LXMRouter(storagepath=storage_path)

    # Create identity and register
    identity = RNS.Identity()
    source = router.register_delivery_identity(identity, display_name="PythonTrace")
    print(f"Our address: {source.hash.hex()}")

    # Announce and wait for path
    router.announce(source.hash)
    time.sleep(1)

    if not RNS.Transport.has_path(ECHO_BOT_HASH):
        RNS.Transport.request_path(ECHO_BOT_HASH)
        timeout = 30
        start = time.time()
        while not RNS.Transport.has_path(ECHO_BOT_HASH) and (time.time() - start) < timeout:
            time.sleep(0.5)

    if not RNS.Transport.has_path(ECHO_BOT_HASH):
        print("ERROR: Could not find path to echo bot")
        return 1

    print("Path found, waiting for identity...")
    time.sleep(2)

    echo_identity = RNS.Identity.recall(ECHO_BOT_HASH)
    if not echo_identity:
        print("ERROR: Could not recall echo bot identity")
        return 1

    print("Identity recalled, creating message...")

    # Create destination
    dest = RNS.Destination(echo_identity, RNS.Destination.OUT,
                          RNS.Destination.SINGLE, "lxmf", "delivery")

    # Create and send message with DIRECT delivery
    message = LXMF.LXMessage(
        dest,
        source,
        "Hello from Python trace test!",
        "Trace Test",
        desired_method=LXMF.LXMessage.DIRECT
    )

    print("Sending message with DIRECT delivery...")
    router.handle_outbound(message)

    # Wait a bit for the transfer to start
    print("Waiting for transfer (watching for RESOURCE_ADV packets)...")
    time.sleep(10)

    print("Trace complete")
    return 0

if __name__ == "__main__":
    sys.exit(main())
