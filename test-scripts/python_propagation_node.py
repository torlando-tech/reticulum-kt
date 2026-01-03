#!/usr/bin/env python3
"""
Python Propagation Node Test Script

Sets up a minimal propagation node that Kotlin can sync messages from.

Usage:
1. Run this script: python3 test-scripts/python_propagation_node.py
2. It will create test messages and save a client identity
3. Run Kotlin with the saved identity to sync messages

The script saves a test identity that Kotlin should load.
"""

import RNS
import LXMF
import time
import sys
import os
import tempfile

# Configuration
TCP_PORT = 4242
STORAGE_PATH = "/tmp/lxmf_prop_node_test"

def main():
    print(f"=== Python Propagation Node Test ===")
    print(f"Storage path: {STORAGE_PATH}")

    # Clean up previous run
    if os.path.exists(STORAGE_PATH):
        import shutil
        shutil.rmtree(STORAGE_PATH)
    os.makedirs(STORAGE_PATH, exist_ok=True)

    # Create config directory
    config_dir = os.path.join(STORAGE_PATH, "config")
    os.makedirs(config_dir, exist_ok=True)

    # Write config file with TCP server interface
    config_content = f"""
[reticulum]
share_instance = no
enable_transport = yes
shared_instance_port = 37429

[logging]
loglevel = 5

[interfaces]
  [[TCP Server Interface]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 0.0.0.0
    listen_port = {TCP_PORT}
"""
    config_file = os.path.join(config_dir, "config")
    with open(config_file, "w") as f:
        f.write(config_content)

    print(f"Config written to: {config_file}")
    print(f"Listening on TCP port {TCP_PORT}")

    # Initialize Reticulum with extreme logging
    RNS.Reticulum(configdir=config_dir, loglevel=RNS.LOG_EXTREME)

    # Create LXMF router with propagation enabled
    lxmf_storage = os.path.join(STORAGE_PATH, "lxmf")
    os.makedirs(lxmf_storage, exist_ok=True)

    router = LXMF.LXMRouter(
        storagepath=lxmf_storage,
        identity=RNS.Identity()
    )

    # Enable propagation mode (this is what makes it a propagation node)
    router.enable_propagation()

    # Get the propagation destination hash for logging
    prop_dest_hash = router.propagation_destination.hash.hex()
    print(f"\n=== Propagation Node Ready ===")
    print(f"Propagation destination: {prop_dest_hash}")

    # Create a test identity for the receiving client (Kotlin will use this)
    client_identity = RNS.Identity()
    client_identity_path = os.path.join(STORAGE_PATH, "client_identity")
    client_identity.to_file(client_identity_path)

    client_dest_hash = RNS.Destination.hash_from_name_and_identity(
        "lxmf.delivery", client_identity
    ).hex()

    print(f"\n=== Test Client Identity ===")
    print(f"Identity saved to: {client_identity_path}")
    print(f"Client destination hash: {client_dest_hash}")
    print(f"Client identity hash: {client_identity.hash.hex()}")

    # Create a sender identity and register it
    sender_identity = RNS.Identity()
    sender_dest = router.register_delivery_identity(sender_identity, display_name="TestSender")
    sender_dest_hash = sender_dest.hash.hex()

    print(f"\n=== Sender Identity ===")
    print(f"Sender destination hash: {sender_dest_hash}")
    print(f"Sender identity hash: {sender_identity.hash.hex()}")

    # Announce the sender identity so Kotlin can verify signatures
    print(f"Announcing sender identity...")
    sender_dest.announce()

    # Create receiver destination for storing messages
    receiver_dest = RNS.Destination(
        client_identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf", "delivery"
    )

    print(f"\n=== Creating Test Messages ===")

    # Create messages for the test client
    messagestore_path = os.path.join(lxmf_storage, "messagestore")
    os.makedirs(messagestore_path, exist_ok=True)

    for i in range(3):
        message = LXMF.LXMessage(
            receiver_dest,
            sender_dest,
            f"Test message {i+1} from propagation node! This is a test of the Kotlin LXMF sync functionality.",
            title=f"Test Message {i+1}"
        )

        # Pack the message (this generates the signature etc)
        message.pack()

        # Python's message_get_request strips STAMP_SIZE (32) bytes from the end.
        # Messages in propagation storage should have a stamp appended.
        # Add a dummy 32-byte stamp (0s work since we don't validate stamps in this test)
        packed_with_stamp = message.packed + bytes(32)

        # Get transient ID from the lxmf_data (without stamp)
        transient_id = RNS.Identity.full_hash(message.packed)

        # Save to messagestore directory (WITH stamp)
        filename = f"{transient_id.hex()}_{int(time.time())}_0"
        filepath = os.path.join(messagestore_path, filename)

        with open(filepath, "wb") as f:
            f.write(packed_with_stamp)

        # Add to propagation_entries dictionary
        # Format: {transient_id: [dest_hash, filepath, timestamp, msg_size, handled_peers, unhandled_peers, stamp_value]}
        router.propagation_entries[transient_id] = [
            receiver_dest.hash,     # 0: Destination hash (who the message is FOR)
            filepath,               # 1: File path
            time.time(),            # 2: Receive timestamp
            len(packed_with_stamp), # 3: Message size (with stamp)
            [],                     # 4: Handled peers
            [],                     # 5: Unhandled peers
            0                       # 6: Stamp value
        ]

        print(f"  Created message {i+1}: {transient_id.hex()[:16]}... ({len(packed_with_stamp)} bytes, {len(message.packed)} without stamp)")

    print(f"\nTotal messages in store: {len(router.propagation_entries)}")

    # Announce the propagation node
    print(f"\n=== Announcing Propagation Node ===")
    router.announce_propagation_node()

    print(f"\n" + "="*60)
    print(f"=== READY FOR KOTLIN CLIENT ===")
    print(f"="*60)
    print(f"\nKotlin client should:")
    print(f"  1. Connect to TCP port {TCP_PORT}")
    print(f"  2. Load identity from: {client_identity_path}")
    print(f"  3. Discover propagation node: {prop_dest_hash}")
    print(f"  4. Call requestMessagesFromPropagationNode()")
    print(f"  5. Receive {len(router.propagation_entries)} test messages")
    print(f"\nIdentity file contents (hex):")
    with open(client_identity_path, "rb") as f:
        print(f"  {f.read().hex()}")
    print(f"\nPress Ctrl+C to stop")
    print("="*60)

    # Keep running and announce periodically
    try:
        announce_interval = 10  # seconds
        last_announce = time.time()
        while True:
            time.sleep(1)
            if time.time() - last_announce >= announce_interval:
                print(f"[{time.strftime('%H:%M:%S')}] Re-announcing propagation node and sender...")
                router.announce_propagation_node()
                sender_dest.announce()  # Re-announce sender so Kotlin can verify signatures
                last_announce = time.time()
    except KeyboardInterrupt:
        print("\nShutting down...")
        RNS.Transport.detach_interfaces()

if __name__ == "__main__":
    main()
