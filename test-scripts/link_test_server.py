#!/usr/bin/env python3
"""
Link Test Server - Python RNS destination that accepts Links

This script creates a destination that accepts incoming Links and responds
to data sent over the Link. It runs as a standalone RNS node with a TCP
server interface that rnsd-kt can connect to.

Usage:
    python3 link_test_server.py

The server will:
1. Create a destination with app_name="linktest" and aspect="server"
2. Announce the destination periodically
3. Accept incoming Links and log all activity
4. Echo back any data received over the Link
"""

import RNS
import time
import sys
import os
import argparse

# Configuration
APP_NAME = "linktest"
ASPECT = "server"

class LinkTestServer:
    def __init__(self, config_path=None):
        # Initialize Reticulum
        self.reticulum = RNS.Reticulum(config_path)

        # Create identity (or load existing)
        identity_path = os.path.expanduser("~/.reticulum/linktest_server_identity")
        if os.path.exists(identity_path):
            self.identity = RNS.Identity.from_file(identity_path)
            RNS.log(f"Loaded existing identity", RNS.LOG_INFO)
        else:
            self.identity = RNS.Identity()
            self.identity.to_file(identity_path)
            RNS.log(f"Created new identity", RNS.LOG_INFO)

        # Create destination
        self.destination = RNS.Destination(
            self.identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME,
            ASPECT
        )

        # Set link callback
        self.destination.set_link_established_callback(self.link_established)

        # Track active links
        self.links = []

        RNS.log(f"Server destination hash: {RNS.prettyhexrep(self.destination.hash)}", RNS.LOG_INFO)
        RNS.log(f"Full destination name: {self.destination.name}", RNS.LOG_INFO)

    def link_established(self, link):
        """Called when a new Link is established"""
        RNS.log(f"Link established! Link ID: {RNS.prettyhexrep(link.link_id)}", RNS.LOG_INFO)
        RNS.log(f"  RTT: {link.rtt}ms", RNS.LOG_INFO)

        # Set callbacks for this link
        link.set_packet_callback(self.packet_received)
        link.set_link_closed_callback(self.link_closed)

        self.links.append(link)

        # Send a welcome message
        try:
            data = b"Welcome to the Link Test Server!"
            RNS.Packet(link, data).send()
            RNS.log(f"Sent welcome message", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Error sending welcome: {e}", RNS.LOG_ERROR)

    def packet_received(self, message, packet):
        """Called when data is received over a Link"""
        RNS.log(f"Received packet: {len(message)} bytes", RNS.LOG_INFO)
        try:
            text = message.decode('utf-8')
            RNS.log(f"  Content: {text}", RNS.LOG_INFO)
        except:
            RNS.log(f"  (binary data)", RNS.LOG_INFO)

        # Echo back the message
        try:
            link = packet.link
            echo_data = b"ECHO: " + message
            RNS.Packet(link, echo_data).send()
            RNS.log(f"Sent echo response", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Error sending echo: {e}", RNS.LOG_ERROR)

    def link_closed(self, link):
        """Called when a Link is closed"""
        RNS.log(f"Link closed: {RNS.prettyhexrep(link.link_id)}", RNS.LOG_INFO)
        if link in self.links:
            self.links.remove(link)

    def announce(self):
        """Announce the destination"""
        self.destination.announce()
        RNS.log(f"Announced destination", RNS.LOG_INFO)

    def run(self):
        """Main loop"""
        RNS.log("Link Test Server running...", RNS.LOG_INFO)
        RNS.log(f"Destination: {RNS.prettyhexrep(self.destination.hash)}", RNS.LOG_INFO)

        # Initial announce
        self.announce()

        # Re-announce after 5 seconds to catch any late-connecting nodes
        time.sleep(5)
        RNS.log("Re-announcing for late connectors...", RNS.LOG_VERBOSE)
        self.announce()

        last_announce = time.time()
        announce_interval = 30  # Re-announce every 30 seconds for testing

        try:
            while True:
                time.sleep(1)

                # Periodic re-announce
                if time.time() - last_announce > announce_interval:
                    self.announce()
                    last_announce = time.time()

                # Log link status
                if self.links:
                    for link in self.links:
                        if link.status == RNS.Link.ACTIVE:
                            RNS.log(f"Active link: {RNS.prettyhexrep(link.link_id)}, RTT: {link.rtt}ms", RNS.LOG_DEBUG)

        except KeyboardInterrupt:
            RNS.log("Shutting down...", RNS.LOG_INFO)
            for link in self.links:
                link.teardown()

def main():
    parser = argparse.ArgumentParser(description="Link Test Server")
    parser.add_argument("--config", "-c", help="Path to Reticulum config directory")
    parser.add_argument("--verbose", "-v", action="count", default=0, help="Increase verbosity")
    args = parser.parse_args()

    # Set log level
    if args.verbose >= 2:
        RNS.loglevel = RNS.LOG_DEBUG
    elif args.verbose >= 1:
        RNS.loglevel = RNS.LOG_VERBOSE
    else:
        RNS.loglevel = RNS.LOG_INFO

    server = LinkTestServer(args.config)
    server.run()

if __name__ == "__main__":
    main()
