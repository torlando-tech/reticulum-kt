#!/usr/bin/env python3
"""
LXMF sender to test interop with Kotlin echo bot.

Usage:
    python3 lxmf_send_to_kotlin.py <destination_hash> [opportunistic|direct]

Example:
    python3 lxmf_send_to_kotlin.py af75975ced6ded2d7a811808a4f56383 opportunistic
"""

import LXMF
import RNS
import sys
import time
import os

# Configuration
STORAGE_PATH = "/tmp/lxmf_sender_storage"
KOTLIN_TCP_PORT = 4242

def delivery_callback(message):
    """Called when we receive a message (echo reply)."""
    sender = RNS.prettyhexrep(message.source_hash)
    print(f"\n{'='*60}")
    print(f"RECEIVED ECHO REPLY!")
    print(f"  From: {sender}")
    print(f"  Title: {message.title_as_string() if message.title else '(none)'}")
    print(f"  Content: {message.content_as_string()}")
    print(f"  Signature valid: {message.signature_validated}")
    print(f"{'='*60}\n")

def message_notification_callback(message, notification_type):
    """Called when message state changes."""
    state_names = {
        LXMF.LXMessage.GENERATING: "GENERATING",
        LXMF.LXMessage.OUTBOUND: "OUTBOUND",
        LXMF.LXMessage.SENDING: "SENDING",
        LXMF.LXMessage.SENT: "SENT",
        LXMF.LXMessage.DELIVERED: "DELIVERED",
        LXMF.LXMessage.FAILED: "FAILED",
    }
    state = state_names.get(message.state, f"UNKNOWN({message.state})")
    print(f"[Message State] {state}")

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    recipient_hexhash = sys.argv[1]
    method = sys.argv[2] if len(sys.argv) > 2 else "opportunistic"

    print(f"LXMF Sender - Testing Kotlin Interop")
    print(f"  Recipient: {recipient_hexhash}")
    print(f"  Method: {method.upper()}")
    print()

    # Clean storage for fresh test
    os.makedirs(STORAGE_PATH, exist_ok=True)

    # Create Reticulum config to use TCP interface to Kotlin
    config_path = os.path.join(STORAGE_PATH, "config")
    os.makedirs(config_path, exist_ok=True)

    config_file = os.path.join(config_path, "config")
    with open(config_file, 'w') as f:
        f.write("""
[reticulum]
  enable_transport = False
  share_instance = False
  shared_instance_port = 37429
  instance_control = False

[logging]
  loglevel = 5

[interfaces]
  [[TCP Client to Kotlin]]
    type = TCPClientInterface
    enabled = true
    target_host = 127.0.0.1
    target_port = 4242
""")

    print("Initializing Reticulum...")
    RNS.loglevel = RNS.LOG_VERBOSE
    reticulum = RNS.Reticulum(configdir=config_path)

    # Check interfaces
    print("\nActive interfaces:")
    for interface in RNS.Transport.interfaces:
        status = "ONLINE" if interface.online else "OFFLINE"
        print(f"  - {interface.name}: {status}")
    print()

    print("Creating LXMF router...")
    router = LXMF.LXMRouter(storagepath=STORAGE_PATH, enforce_stamps=False)

    # Register for incoming messages (echo replies)
    router.register_delivery_callback(delivery_callback)

    # Create our identity
    identity = RNS.Identity()
    source = router.register_delivery_identity(identity, display_name="Python Test Sender")
    print(f"  Our address: {RNS.prettyhexrep(source.hash)}")

    # Announce ourselves so Kotlin can reply
    print("Announcing ourselves...")
    router.announce(source.hash)

    # Wait a moment for network setup
    time.sleep(2)

    # Parse recipient hash
    recipient_hash = bytes.fromhex(recipient_hexhash)

    # Check if we have path to recipient
    print(f"Checking path to {recipient_hexhash}...")
    if not RNS.Transport.has_path(recipient_hash):
        print("  Path not known, requesting...")
        RNS.Transport.request_path(recipient_hash)
        timeout = 30
        while not RNS.Transport.has_path(recipient_hash) and timeout > 0:
            time.sleep(0.5)
            timeout -= 0.5
            if timeout % 5 == 0:
                print(f"    Waiting... {timeout}s remaining")
        if timeout <= 0:
            print("  ERROR: Could not find path to recipient!")
            print("  Make sure Kotlin echo bot is running and announced.")
            sys.exit(1)

    print("  Path found!")

    # Recall recipient identity
    print("Recalling recipient identity...")
    recipient_identity = RNS.Identity.recall(recipient_hash)
    if not recipient_identity:
        print("  ERROR: Could not recall recipient identity!")
        sys.exit(1)

    print("  Identity recalled successfully")

    # Create destination
    dest = RNS.Destination(
        recipient_identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        "lxmf", "delivery"
    )

    # Create and send message
    content = "Hello from Python! This is a test message."
    title = "Python Test"

    if method.lower() == "direct":
        desired_method = LXMF.LXMessage.DIRECT
        print(f"\nSending DIRECT message (link will be established)...")
    else:
        desired_method = LXMF.LXMessage.OPPORTUNISTIC
        print(f"\nSending OPPORTUNISTIC message (single packet)...")

    lxm = LXMF.LXMessage(
        dest, source, content, title,
        desired_method=desired_method
    )

    # Set notification callback
    lxm.register_delivery_callback(lambda msg: message_notification_callback(msg, "delivery"))
    lxm.register_failed_callback(lambda msg: message_notification_callback(msg, "failed"))

    router.handle_outbound(lxm)

    print(f"  Message hash: {RNS.prettyhexrep(lxm.hash)}")
    print("\nWaiting for echo reply (30 seconds)...")

    # Wait for echo reply
    timeout = 30
    while timeout > 0:
        time.sleep(1)
        timeout -= 1
        if timeout % 5 == 0:
            print(f"  {timeout} seconds remaining...")

    print("\nTest complete!")

if __name__ == "__main__":
    main()
