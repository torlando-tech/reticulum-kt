#!/usr/bin/env python3
"""
Link Test Client - Python RNS client that establishes Links

This script connects to an RNS network (optionally via a shared instance like
rnsd-kt) and attempts to establish a Link to a destination.

Usage:
    # Connect to linktest.server destination (discovered via announce)
    python3 link_test_client.py

    # Connect to specific destination hash
    python3 link_test_client.py --dest <destination_hash>

    # Use shared instance (e.g., rnsd-kt)
    python3 link_test_client.py --shared

The client will:
1. Wait for an announce from linktest.server (or use provided hash)
2. Establish a Link to the destination
3. Send test messages over the Link
4. Display responses
"""

import RNS
import time
import sys
import os
import argparse

# Configuration
APP_NAME = "linktest"
ASPECT = "server"

class LinkTestClient:
    def __init__(self, config_path=None, shared_instance=False):
        # Initialize Reticulum
        if shared_instance:
            RNS.log("Connecting to shared instance...", RNS.LOG_INFO)

        self.reticulum = RNS.Reticulum(config_path)

        # Create identity for this client
        self.identity = RNS.Identity()

        # Target destination
        self.target_dest_hash = None
        self.target_dest = None
        self.link = None

        # Message counter
        self.msg_count = 0

        RNS.log(f"Client identity: {RNS.prettyhexrep(self.identity.hash)}", RNS.LOG_INFO)

    def destination_discovered(self, destination_hash, announced_identity, app_data):
        """Called when an announce is received"""
        RNS.log(f"Discovered destination: {RNS.prettyhexrep(destination_hash)}", RNS.LOG_INFO)

        if app_data:
            try:
                RNS.log(f"  App data: {app_data.decode('utf-8')}", RNS.LOG_DEBUG)
            except:
                pass

        # If we're looking for any linktest.server destination
        if self.target_dest_hash is None:
            self.target_dest_hash = destination_hash
            RNS.log(f"Using discovered destination", RNS.LOG_INFO)

    def wait_for_destination(self, dest_hash=None, timeout=30):
        """Wait for destination to be known"""
        if dest_hash:
            # Use provided hash
            self.target_dest_hash = bytes.fromhex(dest_hash)
            RNS.log(f"Using provided destination: {RNS.prettyhexrep(self.target_dest_hash)}", RNS.LOG_INFO)
        else:
            # Register for announces
            RNS.Transport.register_announce_handler(self.destination_discovered)
            RNS.log(f"Waiting for {APP_NAME}.{ASPECT} announce...", RNS.LOG_INFO)

        # Wait for destination
        start = time.time()
        while self.target_dest_hash is None and time.time() - start < timeout:
            time.sleep(0.5)

        if self.target_dest_hash is None:
            RNS.log("Timeout waiting for destination", RNS.LOG_ERROR)
            return False

        # Check if we already know the identity
        identity = RNS.Identity.recall(self.target_dest_hash)
        if identity is None:
            RNS.log("Waiting for destination identity...", RNS.LOG_INFO)
            # Request path if needed
            if not RNS.Transport.has_path(self.target_dest_hash):
                RNS.log("Requesting path...", RNS.LOG_INFO)
                RNS.Transport.request_path(self.target_dest_hash)

            # Wait for identity
            start = time.time()
            while identity is None and time.time() - start < timeout:
                identity = RNS.Identity.recall(self.target_dest_hash)
                time.sleep(0.5)

        if identity is None:
            RNS.log("Could not recall destination identity", RNS.LOG_ERROR)
            return False

        # Create destination object
        self.target_dest = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            APP_NAME,
            ASPECT
        )

        RNS.log(f"Destination ready: {RNS.prettyhexrep(self.target_dest.hash)}", RNS.LOG_INFO)
        return True

    def establish_link(self, timeout=15):
        """Establish a Link to the target destination"""
        if self.target_dest is None:
            RNS.log("No target destination", RNS.LOG_ERROR)
            return False

        RNS.log(f"Establishing link to {RNS.prettyhexrep(self.target_dest.hash)}...", RNS.LOG_INFO)

        # Create Link
        self.link = RNS.Link(self.target_dest)

        # Set callbacks
        self.link.set_link_established_callback(self.link_established)
        self.link.set_link_closed_callback(self.link_closed)
        self.link.set_packet_callback(self.packet_received)

        # Wait for link to establish
        start = time.time()
        while self.link.status != RNS.Link.ACTIVE and time.time() - start < timeout:
            if self.link.status == RNS.Link.CLOSED:
                RNS.log("Link failed to establish", RNS.LOG_ERROR)
                return False
            time.sleep(0.1)

        if self.link.status != RNS.Link.ACTIVE:
            RNS.log(f"Link timeout (status: {self.link.status})", RNS.LOG_ERROR)
            return False

        return True

    def link_established(self, link):
        """Called when Link is established"""
        RNS.log(f"Link established!", RNS.LOG_INFO)
        RNS.log(f"  Link ID: {RNS.prettyhexrep(link.link_id)}", RNS.LOG_INFO)
        RNS.log(f"  RTT: {link.rtt}ms", RNS.LOG_INFO)

    def link_closed(self, link):
        """Called when Link is closed"""
        RNS.log(f"Link closed (reason: {link.teardown_reason})", RNS.LOG_INFO)
        self.link = None

    def packet_received(self, message, packet):
        """Called when data is received over the Link"""
        RNS.log(f"Received: {len(message)} bytes", RNS.LOG_INFO)
        try:
            text = message.decode('utf-8')
            RNS.log(f"  Content: {text}", RNS.LOG_INFO)
        except:
            RNS.log(f"  (binary data)", RNS.LOG_DEBUG)

    def send_message(self, message):
        """Send a message over the Link"""
        if self.link is None or self.link.status != RNS.Link.ACTIVE:
            RNS.log("No active link", RNS.LOG_ERROR)
            return False

        try:
            data = message.encode('utf-8') if isinstance(message, str) else message
            RNS.Packet(self.link, data).send()
            self.msg_count += 1
            RNS.log(f"Sent message #{self.msg_count}: {message}", RNS.LOG_INFO)
            return True
        except Exception as e:
            RNS.log(f"Error sending: {e}", RNS.LOG_ERROR)
            return False

    def run_test(self, dest_hash=None, messages=3):
        """Run a complete Link test"""
        RNS.log("=== Link Test Client ===", RNS.LOG_INFO)

        # Wait for destination
        if not self.wait_for_destination(dest_hash):
            return False

        # Establish Link
        if not self.establish_link():
            return False

        # Wait a moment for welcome message
        time.sleep(1)

        # Send test messages
        for i in range(messages):
            msg = f"Test message {i+1} from Kotlin-routed client"
            self.send_message(msg)
            time.sleep(2)  # Wait for echo response

        RNS.log("Test complete!", RNS.LOG_INFO)

        # Keep link alive for a bit
        RNS.log("Keeping link alive for 10 seconds...", RNS.LOG_INFO)
        time.sleep(10)

        # Teardown
        if self.link:
            self.link.teardown()

        return True

    def interactive(self, dest_hash=None):
        """Interactive mode - establish link and allow manual message sending"""
        RNS.log("=== Link Test Client (Interactive) ===", RNS.LOG_INFO)

        # Wait for destination
        if not self.wait_for_destination(dest_hash):
            return

        # Establish Link
        if not self.establish_link():
            return

        RNS.log("Link active! Type messages to send (Ctrl+C to quit)", RNS.LOG_INFO)

        try:
            while self.link and self.link.status == RNS.Link.ACTIVE:
                try:
                    msg = input("> ")
                    if msg:
                        self.send_message(msg)
                except EOFError:
                    break
        except KeyboardInterrupt:
            pass

        RNS.log("Closing link...", RNS.LOG_INFO)
        if self.link:
            self.link.teardown()

def main():
    parser = argparse.ArgumentParser(description="Link Test Client")
    parser.add_argument("--config", "-c", help="Path to Reticulum config directory")
    parser.add_argument("--dest", "-d", help="Destination hash (hex)")
    parser.add_argument("--shared", "-s", action="store_true", help="Use shared instance")
    parser.add_argument("--interactive", "-i", action="store_true", help="Interactive mode")
    parser.add_argument("--verbose", "-v", action="count", default=0, help="Increase verbosity")
    parser.add_argument("--messages", "-m", type=int, default=3, help="Number of test messages")
    args = parser.parse_args()

    # Set log level
    if args.verbose >= 2:
        RNS.loglevel = RNS.LOG_DEBUG
    elif args.verbose >= 1:
        RNS.loglevel = RNS.LOG_VERBOSE
    else:
        RNS.loglevel = RNS.LOG_INFO

    # Config path for shared instance
    config_path = args.config
    if args.shared and not config_path:
        # Use default shared instance config (connects to local daemon)
        config_path = None  # RNS will auto-detect shared instance

    client = LinkTestClient(config_path, args.shared)

    if args.interactive:
        client.interactive(args.dest)
    else:
        success = client.run_test(args.dest, args.messages)
        sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
