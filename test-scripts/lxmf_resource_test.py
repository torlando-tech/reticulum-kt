#!/usr/bin/env python3
"""
LXMF Resource Transfer Test

Sends a large message (>319 bytes) to trigger resource-based delivery.
Messages larger than LINK_PACKET_MAX_CONTENT (319 bytes) are sent as Resources.
"""

import RNS
import LXMF
import time
import sys

# Configuration
APP_NAME = "lxmf_resource_test"
DISPLAY_NAME = "Python Resource Tester"

# Generate a large message content (>500 bytes to ensure resource transfer)
LARGE_CONTENT = """This is a test message designed to exceed the LINK_PACKET_MAX_CONTENT limit.
When an LXMF message is larger than 319 bytes, it must be sent as a Resource over a Link.
This triggers the Resource transfer protocol which handles:
- Splitting data into segments
- Windowed flow control
- Compression (optional)
- Reliable delivery with acknowledgments

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt
ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation
ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in
reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.

This message is approximately 800 bytes to ensure resource transfer is triggered.
End of test message.
"""

class ResourceTester:
    def __init__(self, dest_hash_hex, host="127.0.0.1", port=4242):
        self.dest_hash_hex = dest_hash_hex
        self.dest_hash = bytes.fromhex(dest_hash_hex)
        self.host = host
        self.port = port
        self.echo_received = False
        self.router = None
        self.identity = None
        self.local_dest = None
        self.storage_path = f"/tmp/{APP_NAME}"

    def run(self):
        print("=" * 60)
        print("LXMF Resource Transfer Test")
        print("=" * 60)
        print()
        print(f"  Target: {self.dest_hash_hex}")
        print(f"  Host: {self.host}:{self.port}")
        print(f"  Content size: {len(LARGE_CONTENT)} bytes")
        print()

        # Create Reticulum config for TCP interface
        import os
        os.makedirs(self.storage_path, exist_ok=True)
        config_path = os.path.join(self.storage_path, "config")
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
  loglevel = 5

[interfaces]
  [[TCP Client to Kotlin]]
    type = TCPClientInterface
    enabled = true
    target_host = {self.host}
    target_port = {self.port}
""")

        # Initialize Reticulum
        print("Initializing Reticulum...")
        RNS.loglevel = RNS.LOG_VERBOSE
        self.reticulum = RNS.Reticulum(configdir=config_path)

        # Show active interfaces
        print("\nActive interfaces:")
        for interface in RNS.Transport.interfaces:
            status = "ONLINE" if interface.online else "OFFLINE"
            print(f"  - {interface.name}: {status}")

        # Create identity and router
        print("\nCreating identity and LXMF router...")
        self.identity = RNS.Identity()
        self.router = LXMF.LXMRouter(identity=self.identity, storagepath=self.storage_path)

        # Register our identity for receiving replies
        self.local_dest = self.router.register_delivery_identity(
            self.identity,
            display_name=DISPLAY_NAME
        )

        # Register callback for incoming messages
        self.router.register_delivery_callback(self.on_message)

        print(f"  Our address: <{RNS.prettyhexrep(self.local_dest.hash)}>")

        # Announce ourselves
        print("Announcing...")
        self.local_dest.announce()

        # Wait for network setup
        time.sleep(2)

        # Send large message
        self.send_large_message()

        # Wait for echo
        self.wait_for_echo()

    def on_message(self, message):
        """Handle incoming message (echo reply)"""
        sender_hash = RNS.prettyhexrep(message.source_hash)

        print()
        print("=" * 60)
        print("RECEIVED ECHO REPLY!")
        print(f"  From: <{sender_hash}>")
        print(f"  Title: {message.title or '(no title)'}")
        content_preview = message.content_as_string()[:100] if message.content else "(empty)"
        if len(message.content_as_string()) > 100:
            content_preview += "..."
        print(f"  Content preview: {content_preview}")
        print(f"  Content size: {len(message.content_as_string())} bytes")
        print(f"  Signature valid: {message.signature_validated}")
        print("=" * 60)
        print()

        self.echo_received = True

    def send_large_message(self):
        """Send a large message that requires resource transfer"""
        print()
        print("Checking path to destination...")

        # Request path if needed
        if not RNS.Transport.has_path(self.dest_hash):
            print("  Path not known, requesting...")
            RNS.Transport.request_path(self.dest_hash)

            # Wait for path
            timeout = 30
            start = time.time()
            while not RNS.Transport.has_path(self.dest_hash):
                if time.time() - start > timeout:
                    print("  ERROR: Could not find path to destination!")
                    sys.exit(1)
                time.sleep(0.5)

        print("  Path found!")

        # Recall recipient identity
        print("Recalling recipient identity...")
        recipient_identity = RNS.Identity.recall(self.dest_hash)
        if recipient_identity is None:
            print("  ERROR: Could not recall recipient identity!")
            sys.exit(1)

        print("  Identity recalled!")

        # Create destination
        recipient_dest = RNS.Destination(
            recipient_identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf",
            "delivery"
        )

        # Create large message
        print()
        print("Creating large message...")
        print(f"  Content size: {len(LARGE_CONTENT)} bytes")
        print(f"  Will use: RESOURCE transfer (>319 bytes)")

        message = LXMF.LXMessage(
            recipient_dest,
            self.local_dest,
            LARGE_CONTENT,
            title="Resource Test",
            desired_method=LXMF.LXMessage.DIRECT  # DIRECT required for resource transfer
        )

        # Set callbacks
        message.register_delivery_callback(self.on_delivery)
        message.register_failed_callback(self.on_failed)

        # Send
        print()
        print("Sending message (this will establish a link and transfer as resource)...")
        self.router.handle_outbound(message)

        # Hash is available after handle_outbound
        if message.hash:
            print(f"  Message hash: <{RNS.prettyhexrep(message.hash)}>")

    def on_delivery(self, message):
        """Called when message is delivered"""
        print(f"  Message delivered! State: {message.state}")

    def on_failed(self, message):
        """Called when message delivery fails"""
        print(f"  Message FAILED! State: {message.state}")

    def wait_for_echo(self):
        """Wait for echo reply"""
        print()
        print("Waiting for echo reply (60 seconds for resource transfer)...")

        timeout = 60
        for i in range(timeout, 0, -1):
            if self.echo_received:
                print("Echo received successfully!")
                return
            time.sleep(1)
            if i % 10 == 0:
                print(f"  {i} seconds remaining...")

        if not self.echo_received:
            print("Timeout - no echo received")


def main():
    if len(sys.argv) < 2:
        print("Usage: lxmf_resource_test.py <dest_hash> [host] [port]")
        print()
        print("Example:")
        print("  lxmf_resource_test.py 8ee2b454f7ab6404a50e6a865f05e1f2 127.0.0.1 4242")
        sys.exit(1)

    dest_hash = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 4242

    tester = ResourceTester(dest_hash, host, port)
    tester.run()


if __name__ == "__main__":
    main()
