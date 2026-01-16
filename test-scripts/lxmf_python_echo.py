#!/usr/bin/env python3
"""
Python LXMF Echo Bot for testing Kotlin interoperability.

Usage:
    python3 lxmf_python_echo.py

The bot will announce itself and print its LXMF address.
Connect with Kotlin and send a message to receive an echo.
"""

import LXMF
import RNS
import time
import os
import sys

# Configuration
STORAGE_PATH = "/tmp/lxmf_python_echo"
TCP_PORT = 4243  # Different from Kotlin's 4242
DISPLAY_NAME = "Python Echo Bot"

router = None
my_destination = None

def delivery_callback(message):
    """Called when we receive a message."""
    global router, my_destination

    sender_hash = RNS.prettyhexrep(message.source_hash)
    title = message.title_as_string() if message.title else "(no title)"
    content = message.content_as_string()

    print(f"\n{'='*60}")
    print(f"RECEIVED MESSAGE!")
    print(f"  From: {sender_hash}")
    print(f"  Title: {title}")
    print(f"  Content: {content}")
    print(f"  Signature valid: {message.signature_validated}")
    print(f"{'='*60}")

    # Get sender's identity to create reply destination
    sender_identity = RNS.Identity.recall(message.source_hash)
    if sender_identity is None:
        print(f"  [WARN] Cannot echo - sender identity not known")
        print(f"         Requesting path to learn identity...")
        RNS.Transport.request_path(message.source_hash)
        # Wait a bit for identity
        for i in range(10):
            time.sleep(0.5)
            sender_identity = RNS.Identity.recall(message.source_hash)
            if sender_identity:
                break
        if sender_identity is None:
            print(f"  [ERROR] Could not recall sender identity after waiting")
            return

    print(f"  Sender identity recalled, sending echo...")

    # Create destination to sender
    reply_dest = RNS.Destination(
        sender_identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf", "delivery"
    )

    # Create echo message (same content and title)
    echo_msg = LXMF.LXMessage(
        reply_dest, my_destination,
        content, title,
        desired_method=LXMF.LXMessage.OPPORTUNISTIC
    )

    # Send after short delay
    time.sleep(2)
    router.handle_outbound(echo_msg)
    print(f"  Echo sent to {sender_hash}")
    print()


def main():
    global router, my_destination

    print("=" * 60)
    print("LXMF Python Echo Bot")
    print("=" * 60)
    print()

    # Clean storage for fresh test
    os.makedirs(STORAGE_PATH, exist_ok=True)

    # Create Reticulum config with TCP server interface
    config_path = os.path.join(STORAGE_PATH, "config")
    os.makedirs(config_path, exist_ok=True)

    config_file = os.path.join(config_path, "config")
    with open(config_file, 'w') as f:
        f.write(f"""
[reticulum]
  enable_transport = False
  share_instance = False
  shared_instance_port = 37430
  instance_control = False

[logging]
  loglevel = 4

[interfaces]
  [[TCP Server]]
    type = TCPServerInterface
    enabled = true
    listen_ip = 0.0.0.0
    listen_port = {TCP_PORT}
""")

    print("Initializing Reticulum...")
    RNS.loglevel = RNS.LOG_NOTICE
    reticulum = RNS.Reticulum(configdir=config_path)

    # Check interfaces
    print("\nActive interfaces:")
    for interface in RNS.Transport.interfaces:
        status = "ONLINE" if interface.online else "OFFLINE"
        print(f"  - {interface.name}: {status}")
    print()

    print("Creating LXMF router...")
    router = LXMF.LXMRouter(storagepath=STORAGE_PATH, enforce_stamps=False)

    # Register for incoming messages
    router.register_delivery_callback(delivery_callback)

    # Create our identity
    identity = RNS.Identity()
    my_destination = router.register_delivery_identity(identity, display_name=DISPLAY_NAME)

    print(f"\n{'*'*60}")
    print(f"Echo Bot Address: {RNS.prettyhexrep(my_destination.hash)}")
    print(f"TCP Port: {TCP_PORT}")
    print(f"{'*'*60}\n")

    # Announce ourselves
    print("Announcing...")
    router.announce(my_destination.hash)

    print("Waiting for messages... (Ctrl+C to exit)\n")

    # Keep announcing periodically
    last_announce = time.time()
    try:
        while True:
            time.sleep(1)
            # Re-announce every 30 seconds
            if time.time() - last_announce > 30:
                router.announce(my_destination.hash)
                last_announce = time.time()
                print("[Announced]")
    except KeyboardInterrupt:
        print("\nShutting down...")
        sys.exit(0)


if __name__ == "__main__":
    main()
