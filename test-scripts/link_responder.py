#!/usr/bin/env python3
"""
Link responder script for testing Kotlin link initiator.

This script runs a Python RNS destination that accepts link requests,
allowing testing of Kotlin's link initiation capabilities.

Usage:
    1. Set up port forwarding:
       adb forward tcp:4243 tcp:4243

    2. Run this script (it will print the destination hash):
       python test-scripts/link_responder.py

    3. Run the LinkInitiatorTest in the emulator with the destination hash:
       ./gradlew :rns-sample-app:connectedAndroidTest \
         --tests "*LinkInitiatorTest*" \
         -Pandroid.testInstrumentationRunnerArguments.e.destHash=<hash>
"""

import os
import sys
import time
import argparse
import tempfile
import signal

# Configuration
TCP_HOST = "0.0.0.0"
TCP_PORT = 4243
APP_NAME = "linktest"
ASPECTS = ["responder"]


def create_config_file(config_dir, host, port):
    """Create RNS config file with TCP server interface."""
    config_content = f"""# Reticulum configuration for link responder test

[reticulum]
  enable_transport = False
  share_instance = No
  panic_on_interface_errors = No

[interfaces]
  [[Test TCP Server]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = {host}
    listen_port = {port}
"""
    os.makedirs(config_dir, exist_ok=True)
    config_path = os.path.join(config_dir, "config")
    with open(config_path, "w") as f:
        f.write(config_content)
    return config_path


class LinkResponder:
    def __init__(self, config_dir=None):
        self.config_dir = config_dir or tempfile.mkdtemp(prefix="rns_link_responder_")
        self.identity = None
        self.destination = None
        self.links = []
        self.running = True

        # Create config file with TCP server interface
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
        time.sleep(1)

        # Check interface status
        print("\nInterface status:")
        for iface in RNS.Transport.interfaces:
            print(f"  {iface.name}: online={iface.online}")

    def setup_destination(self):
        """Create identity and destination for accepting links."""
        RNS = self.RNS

        # Create new identity
        self.identity = RNS.Identity()
        RNS.log(f"Created identity: {self.identity}", RNS.LOG_INFO)

        # Create destination
        self.destination = RNS.Destination(
            self.identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME,
            *ASPECTS
        )

        # Set up link callbacks
        self.destination.set_link_established_callback(self.on_link_established)

        # Register destination
        RNS.log(f"Destination hash: {self.destination.hexhash}", RNS.LOG_INFO)

        return self.destination.hexhash

    def on_link_established(self, link):
        """Called when a link is established."""
        RNS = self.RNS
        RNS.log("=" * 60, RNS.LOG_INFO)
        RNS.log("LINK ESTABLISHED!", RNS.LOG_INFO)
        RNS.log(f"Link ID: {link.link_id.hex()}", RNS.LOG_INFO)
        RNS.log(f"RTT: {link.rtt}ms", RNS.LOG_INFO)
        RNS.log(f"Initiator: {link.initiator}", RNS.LOG_INFO)
        RNS.log("=" * 60, RNS.LOG_INFO)

        self.links.append(link)

        # Set up link closed callback
        link.set_link_closed_callback(self.on_link_closed)

    def on_link_closed(self, link):
        """Called when a link is closed."""
        RNS = self.RNS
        RNS.log(f"Link closed. Reason: {link.teardown_reason}", RNS.LOG_INFO)
        if link in self.links:
            self.links.remove(link)

    def announce(self):
        """Send an announce for the destination."""
        if self.destination:
            self.destination.announce()
            self.RNS.log("Announced destination", self.RNS.LOG_INFO)

    def run(self, duration=300):
        """Run the responder for the specified duration."""
        RNS = self.RNS

        dest_hash = self.setup_destination()

        print()
        print("=" * 60)
        print("LINK RESPONDER READY")
        print(f"Port: {TCP_PORT}")
        print(f"Destination: {dest_hash}")
        print()
        print("To connect from Android emulator:")
        print(f"  adb forward tcp:{TCP_PORT} tcp:{TCP_PORT}")
        print(f"  # Then run LinkInitiatorTest with destHash={dest_hash}")
        print("=" * 60)
        print()

        # Initial announce
        self.announce()

        # Announce periodically
        start = time.time()
        announce_interval = 10
        last_announce = start

        while self.running and (time.time() - start) < duration:
            try:
                time.sleep(1)

                # Periodic announce
                if time.time() - last_announce > announce_interval:
                    self.announce()
                    last_announce = time.time()

            except KeyboardInterrupt:
                print("\nShutting down...")
                break

        print(f"\nResponder finished. {len(self.links)} links established.")

    def cleanup(self):
        """Clean up resources."""
        self.running = False
        for link in self.links:
            try:
                link.teardown()
            except:
                pass


def main():
    parser = argparse.ArgumentParser(description="Link responder for testing Kotlin initiator")
    parser.add_argument("-d", "--duration", type=int, default=300,
                        help="Duration to run in seconds (default: 300)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument("--config-dir", help="Custom config directory")

    args = parser.parse_args()

    responder = LinkResponder(args.config_dir)

    # Handle SIGINT gracefully
    def signal_handler(sig, frame):
        print("\nReceived interrupt, shutting down...")
        responder.cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)

    try:
        responder.run(args.duration)
    finally:
        responder.cleanup()


if __name__ == "__main__":
    main()
