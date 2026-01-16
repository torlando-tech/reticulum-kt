#!/usr/bin/env python3
"""
Link initiator script for testing Kotlin link responder in Android emulator.

Usage:
    1. Set up port forwarding:
       adb forward tcp:4242 tcp:4242

    2. Run the LinkResponderTest in the emulator:
       ./gradlew :rns-sample-app:connectedAndroidTest --tests "*linkResponderWaitsForConnection*"

    3. Run this script (it will connect and establish a link):
       python test-scripts/link_initiator.py <destination_hash>

    Or for automatic discovery:
       python test-scripts/link_initiator.py --discover
"""

import os
import sys
import time
import argparse
import tempfile

# Configuration
TCP_HOST = "127.0.0.1"
TCP_PORT = 4242
APP_NAME = "linktest"
ASPECTS = ["manual"]


def create_config_file(config_dir, host, port):
    """Create RNS config file with TCP interface."""
    config_content = f"""# Reticulum configuration for link initiator test

[reticulum]
  enable_transport = False
  share_instance = No
  panic_on_interface_errors = No

[interfaces]
  [[Emulator TCP Interface]]
    type = TCPClientInterface
    enabled = yes
    target_host = {host}
    target_port = {port}
"""
    os.makedirs(config_dir, exist_ok=True)
    config_path = os.path.join(config_dir, "config")
    with open(config_path, "w") as f:
        f.write(config_content)
    return config_path


class LinkInitiator:
    def __init__(self, destination_hash=None, config_dir=None):
        self.destination_hash = destination_hash
        self.link = None
        self.link_established = False
        self.config_dir = config_dir or tempfile.mkdtemp(prefix="rns_link_test_")

        # Create config file with TCP interface
        config_path = create_config_file(self.config_dir, TCP_HOST, TCP_PORT)
        print(f"Created config at: {config_path}")

        # Initialize Reticulum with config directory
        import RNS
        self.RNS = RNS
        RNS.loglevel = RNS.LOG_VERBOSE

        print(f"Initializing Reticulum with config dir: {self.config_dir}")
        self.reticulum = RNS.Reticulum(configdir=self.config_dir)
        print("Reticulum initialized")

        # Wait for interface to come online
        time.sleep(2)

        # Check interface status
        for iface in RNS.Transport.interfaces:
            print(f"Interface: {iface.name}, online: {iface.online}")

    def establish_link(self):
        """Establish a link to the destination."""
        RNS = self.RNS

        if not self.destination_hash:
            RNS.log("No destination hash provided", RNS.LOG_ERROR)
            return False

        # Convert hex string to bytes if needed
        if isinstance(self.destination_hash, str):
            dest_hash = bytes.fromhex(self.destination_hash)
        else:
            dest_hash = self.destination_hash

        RNS.log(f"Looking up destination: {dest_hash.hex()}", RNS.LOG_INFO)

        # Wait for path (either via announce or discovery)
        RNS.log("Waiting for path to destination...", RNS.LOG_INFO)

        timeout = 60
        start = time.time()
        while not RNS.Transport.has_path(dest_hash):
            if time.time() - start > timeout:
                RNS.log("Path discovery timeout", RNS.LOG_ERROR)
                return False
            time.sleep(0.5)
            print(".", end="", flush=True)

        print()
        RNS.log("Path found, recalling identity...", RNS.LOG_INFO)

        # Recall the destination's identity
        identity = RNS.Identity.recall(dest_hash)
        if not identity:
            RNS.log("Could not recall identity for destination", RNS.LOG_ERROR)
            return False

        RNS.log(f"Identity recalled: {identity}", RNS.LOG_INFO)

        # Create destination object
        destination = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            APP_NAME,
            *ASPECTS
        )

        RNS.log(f"Destination created: {destination}", RNS.LOG_INFO)

        # Create and establish link
        self.link = RNS.Link(destination)
        self.link.set_link_established_callback(self.on_link_established)
        self.link.set_link_closed_callback(self.on_link_closed)

        RNS.log("Establishing link...", RNS.LOG_INFO)

        # Wait for link establishment
        timeout = 30
        start = time.time()
        while not self.link_established:
            if time.time() - start > timeout:
                RNS.log("Link establishment timeout", RNS.LOG_ERROR)
                return False
            if self.link.status == RNS.Link.CLOSED:
                RNS.log(f"Link closed unexpectedly: {self.link.teardown_reason}", RNS.LOG_ERROR)
                return False
            time.sleep(0.1)

        return True

    def on_link_established(self, link):
        """Called when link is established."""
        RNS = self.RNS
        RNS.log("=" * 60, RNS.LOG_INFO)
        RNS.log("LINK ESTABLISHED!", RNS.LOG_INFO)
        RNS.log(f"Link ID: {link.link_id.hex()}", RNS.LOG_INFO)
        RNS.log(f"RTT: {link.rtt}ms", RNS.LOG_INFO)
        RNS.log("=" * 60, RNS.LOG_INFO)
        self.link_established = True

    def on_link_closed(self, link):
        """Called when link is closed."""
        RNS = self.RNS
        RNS.log(f"Link closed. Reason: {link.teardown_reason}", RNS.LOG_INFO)

    def discover_destinations(self):
        """Wait for announces and list discovered destinations."""
        RNS = self.RNS
        RNS.log("Waiting for announces...", RNS.LOG_INFO)
        RNS.log("Any destination that announces will be displayed.", RNS.LOG_INFO)

        # Wait and let announces come in
        time.sleep(30)

        # Check known destinations
        RNS.log("Checking transport for known paths...", RNS.LOG_INFO)

    def cleanup(self):
        """Clean up resources."""
        if self.link:
            self.link.teardown()


def main():
    parser = argparse.ArgumentParser(description="Link initiator for testing Kotlin responder")
    parser.add_argument("destination_hash", nargs="?", help="Destination hash to connect to")
    parser.add_argument("--discover", action="store_true", help="Discover destinations via announces")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument("--config-dir", help="Custom config directory")

    args = parser.parse_args()

    if not args.destination_hash and not args.discover:
        print("Usage: link_initiator.py <destination_hash>")
        print("       link_initiator.py --discover")
        print()
        print("Get the destination hash from the LinkResponderTest output in logcat:")
        print("  adb logcat -s LinkResponderTest | grep Destination:")
        sys.exit(1)

    initiator = LinkInitiator(args.destination_hash, args.config_dir)

    try:
        if args.discover:
            initiator.discover_destinations()
        else:
            if initiator.establish_link():
                print()
                print("=" * 60)
                print("Link test PASSED!")
                print("=" * 60)

                # Keep link alive briefly
                time.sleep(5)
            else:
                print()
                print("=" * 60)
                print("Link test FAILED!")
                print("=" * 60)
                sys.exit(1)
    finally:
        initiator.cleanup()


if __name__ == "__main__":
    main()
