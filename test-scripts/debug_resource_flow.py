#!/usr/bin/env python3
"""
Debug Resource Protocol Flow

This diagnostic script traces the entire Resource transfer protocol between
Kotlin and Python, logging each phase:

1. RESOURCE_ADV - Advertisement from sender
2. RESOURCE_REQ - Request from receiver (requesting specific part hashes)
3. RESOURCE (0x01) - Data parts from sender
4. RESOURCE_HMU - Hashmap updates (if needed)
5. RESOURCE_PRF - Proof from receiver (confirming complete receipt)

Usage:
    python debug_resource_flow.py [host] [port]

The script starts an LXMF receiver and waits for large messages from Kotlin.
It logs detailed information about each step of the Resource protocol.
"""

import os
import sys
import time
import threading

# Insert local repos before system paths
sys.path.insert(0, os.path.expanduser("~/repos/LXMF"))
sys.path.insert(0, os.path.expanduser("~/repos/Reticulum"))

import RNS
import LXMF

# Configuration
APP_NAME = "debug_resource_flow"
STORAGE_PATH = f"/tmp/{APP_NAME}"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 15134

# Packet context constants (from RNS.Packet)
CONTEXT_NAMES = {
    0x00: "NONE",
    0x01: "RESOURCE",
    0x02: "RESOURCE_ADV",
    0x03: "RESOURCE_REQ",
    0x04: "RESOURCE_HMU",
    0x05: "RESOURCE_ICL",
    0x06: "RESOURCE_RCL",
    0x0D: "RESOURCE_PRF",  # Actually defined as 0x0D in RNS
}


def setup_logging():
    """Enable maximum logging for Resource debugging."""
    RNS.loglevel = RNS.LOG_EXTREME


def patch_resource_logging():
    """Monkey-patch Resource class to add detailed logging."""
    original_accept = RNS.Resource.accept
    original_request_next = None
    original_receive_part = None
    original_assemble = None
    original_prove = None

    @staticmethod
    def patched_accept(advertisement_packet, callback=None, progress_callback=None, request_id=None):
        print("\n" + "=" * 60)
        print("[RESOURCE] Resource.accept() called")
        print(f"  Advertisement packet size: {len(advertisement_packet.plaintext)} bytes")
        print(f"  Packet context: {advertisement_packet.context}")

        # Unpack advertisement for debugging
        try:
            adv = RNS.ResourceAdvertisement.unpack(advertisement_packet.plaintext)
            print(f"  Parsed advertisement:")
            print(f"    hash: {adv.h.hex()}")
            print(f"    randomHash: {adv.r.hex()}")
            print(f"    transfer_size: {adv.t}")
            print(f"    data_size: {adv.d}")
            print(f"    flags: 0x{adv.f:02x} (encrypted={bool(adv.f & 0x01)}, compressed={bool(adv.f & 0x02)})")
            print(f"    segment_index: {adv.i}")
            print(f"    total_segments: {adv.l}")
            print(f"    hashmap: {len(adv.m)} bytes ({len(adv.m) // 4} parts)")
        except Exception as e:
            print(f"  Error parsing advertisement: {e}")

        print("=" * 60 + "\n")

        # Wrap callbacks to add logging
        def wrapped_callback(resource):
            print("\n" + "=" * 60)
            print(f"[RESOURCE] Transfer COMPLETE callback")
            print(f"  Resource hash: {resource.hash.hex()}")
            print(f"  Status: {resource.status}")
            print(f"  Data size: {len(resource.data.read()) if hasattr(resource.data, 'read') else 'N/A'}")
            print("=" * 60 + "\n")
            if callback:
                callback(resource)

        def wrapped_progress(resource):
            print(f"[RESOURCE] Progress: {resource.get_progress():.1%}")
            if progress_callback:
                progress_callback(resource)

        result = original_accept(advertisement_packet,
                                 callback=wrapped_callback,
                                 progress_callback=wrapped_progress,
                                 request_id=request_id)

        if result:
            # Patch this specific resource instance
            patch_resource_instance(result)

        return result

    RNS.Resource.accept = patched_accept


def patch_resource_instance(resource):
    """Patch a Resource instance to log its methods."""
    original_request_next = resource.request_next
    original_receive_part = resource.receive_part
    original_prove = resource.prove

    def patched_request_next():
        print("\n" + "-" * 40)
        print("[RESOURCE] request_next() called")
        print(f"  consecutive_completed_height: {resource.consecutive_completed_height}")
        print(f"  received_count: {resource.received_count}/{resource.total_parts}")
        print(f"  window: {resource.window}")
        print(f"  waiting_for_hmu: {resource.waiting_for_hmu}")

        # Log what hashes we're requesting
        try:
            search_start = resource.consecutive_completed_height + 1
            search_size = resource.window
            requested = []
            for i, part in enumerate(resource.parts[search_start:search_start+search_size]):
                if part is None:
                    part_hash = resource.hashmap[search_start + i]
                    if part_hash:
                        requested.append(part_hash.hex())
            if requested:
                print(f"  Requesting part hashes: {', '.join(requested[:4])}{'...' if len(requested) > 4 else ''}")
        except Exception as e:
            print(f"  Error logging request: {e}")

        print("-" * 40 + "\n")
        original_request_next()

    def patched_receive_part(packet):
        print("\n" + "-" * 40)
        print("[RESOURCE] receive_part() called")
        print(f"  Packet data size: {len(packet.data)} bytes")
        print(f"  Part map_hash: {resource.get_map_hash(packet.data).hex()}")
        print("-" * 40 + "\n")
        original_receive_part(packet)
        print(f"  After receive: {resource.received_count}/{resource.total_parts} parts")

    def patched_prove():
        print("\n" + "=" * 60)
        print("[RESOURCE] prove() called - sending proof to sender")
        print(f"  Resource hash: {resource.hash.hex()}")
        print(f"  Status before prove: {resource.status}")
        print("=" * 60 + "\n")
        original_prove()

    resource.request_next = patched_request_next
    resource.receive_part = patched_receive_part
    resource.prove = patched_prove


def patch_link_logging():
    """Patch Link to log packet handling."""
    original_receive = RNS.Link.receive

    def patched_receive(self, packet):
        context = packet.context
        context_name = CONTEXT_NAMES.get(context, f"0x{context:02x}")

        if context in [0x01, 0x02, 0x03, 0x04, 0x0D]:  # Resource-related
            print(f"\n[LINK] Received packet: context={context_name}, size={len(packet.data)} bytes")
            if context == 0x01:  # RESOURCE
                print(f"  Data preview: {packet.data[:32].hex()}...")
            elif context == 0x02:  # RESOURCE_ADV
                print(f"  Advertisement data: {len(packet.data)} bytes")
            elif context == 0x03:  # RESOURCE_REQ
                print(f"  Request data: {len(packet.data)} bytes")
            elif context == 0x0D:  # RESOURCE_PRF
                print(f"  Proof data: {len(packet.data)} bytes (resource_hash + proof)")

        original_receive(self, packet)

    RNS.Link.receive = patched_receive


class ResourceFlowDebugger:
    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT):
        self.host = host
        self.port = port
        self.received_messages = []

    def run(self):
        print("=" * 60)
        print("RESOURCE FLOW DEBUGGER")
        print("=" * 60)
        print(f"Host: {self.host}:{self.port}")
        print()

        # Setup
        setup_logging()
        patch_resource_logging()
        patch_link_logging()

        # Create storage
        os.makedirs(STORAGE_PATH, exist_ok=True)

        # Create config
        config_path = os.path.join(STORAGE_PATH, "config")
        os.makedirs(config_path, exist_ok=True)

        config_file = os.path.join(config_path, "config")
        with open(config_file, 'w') as f:
            f.write(f"""
[reticulum]
  enable_transport = False
  share_instance = False

[logging]
  loglevel = 7

[interfaces]
  [[TCP Server]]
    type = TCPServerInterface
    enabled = true
    listen_ip = {self.host}
    listen_port = {self.port}
""")

        # Initialize RNS
        print("Initializing Reticulum...")
        self.reticulum = RNS.Reticulum(configdir=config_path)

        # Show interfaces
        print("\nActive interfaces:")
        for interface in RNS.Transport.interfaces:
            status = "ONLINE" if interface.online else "OFFLINE"
            print(f"  - {interface.name}: {status}")

        # Create LXMF router
        print("\nCreating LXMF router...")
        self.identity = RNS.Identity()
        self.router = LXMF.LXMRouter(identity=self.identity, storagepath=STORAGE_PATH)

        # Register delivery identity
        self.local_dest = self.router.register_delivery_identity(
            self.identity,
            display_name="ResourceDebugger"
        )

        # Register message callback
        self.router.register_delivery_callback(self.on_message)

        print(f"\nLXMF destination: {self.local_dest.hash.hex()}")
        print(f"  Announce hash: {RNS.prettyhexrep(self.local_dest.hash)}")

        # Announce
        print("\nAnnouncing LXMF destination...")
        self.local_dest.announce()

        print("\n" + "=" * 60)
        print("WAITING FOR CONNECTIONS...")
        print("Send a large LXMF message (>319 bytes) from Kotlin to trigger Resource transfer")
        print("=" * 60 + "\n")

        # Wait for messages
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nShutting down...")

    def on_message(self, message):
        print("\n" + "#" * 60)
        print("MESSAGE RECEIVED!")
        print("#" * 60)

        print(f"  From: {RNS.prettyhexrep(message.source_hash)}")
        print(f"  Title: {message.title}")
        print(f"  Content length: {len(message.content_as_string())} bytes")
        print(f"  Signature valid: {message.signature_validated}")

        content = message.content_as_string()
        if len(content) > 100:
            print(f"  Content preview: {content[:100]}...")
        else:
            print(f"  Content: {content}")

        self.received_messages.append(message)
        print("#" * 60 + "\n")


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_PORT

    debugger = ResourceFlowDebugger(host, port)
    debugger.run()


if __name__ == "__main__":
    main()
