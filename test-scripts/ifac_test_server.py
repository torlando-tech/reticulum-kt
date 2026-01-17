#!/usr/bin/env python3
"""
IFAC Test Server - Python RNS TCP server with IFAC enabled.

This server listens for TCP connections with IFAC network isolation enabled.
Use it to test Kotlin IFAC interoperability.

Usage:
    python ifac_test_server.py [--port PORT] [--netname NAME] [--passphrase PASS]

Default credentials:
    Network Name: test_network
    Passphrase: test_passphrase
    Port: 4242
"""

import argparse
import sys
import time
import tempfile
import os

# Add RNS to path if needed
sys.path.insert(0, os.path.expanduser("~/repos/Reticulum"))

import RNS

# Test identity for announces
test_identity = None
test_destination = None

def setup_destination(reticulum):
    """Create a test destination that announces itself."""
    global test_identity, test_destination

    # Create or load identity
    identity_path = os.path.join(tempfile.gettempdir(), "ifac_test_identity")
    if os.path.exists(identity_path):
        test_identity = RNS.Identity.from_file(identity_path)
        RNS.log(f"Loaded existing identity: {RNS.prettyhexrep(test_identity.hash)}")
    else:
        test_identity = RNS.Identity()
        test_identity.to_file(identity_path)
        RNS.log(f"Created new identity: {RNS.prettyhexrep(test_identity.hash)}")

    # Create destination
    test_destination = RNS.Destination(
        test_identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        "ifac_test",
        "echo"
    )

    # Set up link handler
    test_destination.set_link_established_callback(link_established)

    RNS.log(f"Destination hash: {RNS.prettyhexrep(test_destination.hash)}")
    return test_destination

def link_established(link):
    """Handle incoming link."""
    RNS.log(f"Link established from {RNS.prettyhexrep(link.get_remote_identity().hash) if link.get_remote_identity() else 'unknown'}")
    link.set_packet_callback(packet_received)
    link.set_link_closed_callback(link_closed)

def packet_received(message, packet):
    """Echo back received packets."""
    RNS.log(f"Received packet: {message[:50]}..." if len(message) > 50 else f"Received packet: {message}")
    # Echo back
    RNS.Packet(packet.link, message).send()

def link_closed(link):
    """Handle link closure."""
    RNS.log("Link closed")

def main():
    print("Starting IFAC Test Server...", flush=True)
    parser = argparse.ArgumentParser(description="IFAC Test Server")
    parser.add_argument("--port", type=int, default=4242, help="TCP listen port")
    parser.add_argument("--netname", default="test_network", help="IFAC network name")
    parser.add_argument("--passphrase", default="test_passphrase", help="IFAC passphrase")
    parser.add_argument("--no-ifac", action="store_true", help="Disable IFAC (for comparison testing)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging")
    args = parser.parse_args()

    # Set log level
    if args.verbose:
        RNS.loglevel = RNS.LOG_DEBUG
    else:
        RNS.loglevel = RNS.LOG_VERBOSE

    # Create config
    config_dir = tempfile.mkdtemp(prefix="ifac_test_")
    config_path = os.path.join(config_dir, "config")

    print(f"Port: {args.port}, netname: {args.netname}, passphrase: {args.passphrase}", flush=True)

    if args.no_ifac:
        config_content = f"""
[reticulum]
  enable_transport = True
  share_instance = False

[interfaces]
  [[IFAC Test Server]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 0.0.0.0
    listen_port = {args.port}
"""
        RNS.log(f"Starting TCP server on port {args.port} WITHOUT IFAC")
    else:
        config_content = f"""
[reticulum]
  enable_transport = True
  share_instance = False

[interfaces]
  [[IFAC Test Server]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 0.0.0.0
    listen_port = {args.port}
    networkname = {args.netname}
    passphrase = {args.passphrase}
"""
        RNS.log(f"Starting TCP server on port {args.port} with IFAC:")
        RNS.log(f"  Network Name: {args.netname}")
        RNS.log(f"  Passphrase: {args.passphrase}")

    with open(config_path, "w") as f:
        f.write(config_content)

    # Start Reticulum
    print("Creating Reticulum instance...", flush=True)
    reticulum = RNS.Reticulum(configdir=config_dir)
    print("Reticulum started!", flush=True)

    # Set up test destination
    dest = setup_destination(reticulum)

    # Announce periodically
    RNS.log("Server running. Press Ctrl+C to stop.")
    RNS.log(f"Connect from Kotlin with matching IFAC credentials to test.")

    try:
        announce_interval = 30
        last_announce = 0
        while True:
            now = time.time()
            if now - last_announce >= announce_interval:
                dest.announce()
                RNS.log("Sent announce")
                last_announce = now
            time.sleep(1)
    except KeyboardInterrupt:
        RNS.log("Shutting down...")
    finally:
        # Cleanup
        import shutil
        shutil.rmtree(config_dir, ignore_errors=True)

if __name__ == "__main__":
    main()
