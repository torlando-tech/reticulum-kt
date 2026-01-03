#!/usr/bin/env python3
"""
Test tunnel establishment between Python (server) and Kotlin (client).

Test Flow:
1. Python starts with TCPServerInterface on port 4244
2. Python creates a test destination and announces it
3. Kotlin connects as TCP client with wantsTunnel=true
4. Kotlin synthesizes tunnel (sends synthesis packet)
5. Python validates signature and creates tunnel entry
6. Python announces destination - path should be stored in tunnel
7. Can test disconnect/reconnect to verify path restoration

Usage:
    python3 python_tunnel_test.py

Expected output:
    - Tunnel establishment logs
    - Path association with tunnel logs
    - Announces visible to Kotlin
"""

import RNS
import time
import os
import sys
import signal

# Configuration
TCP_PORT = 4244
STORAGE_PATH = "/tmp/tunnel_test_python"
APP_NAME = "tunnel_test"

# Globals
reticulum = None
destination = None
tunnel_test_identity = None

def log(msg):
    """Print timestamped log message."""
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{timestamp}] {msg}", flush=True)

def setup_rns():
    """Initialize Reticulum with TCP server interface."""
    global reticulum, tunnel_test_identity, destination

    # Clean up previous test data
    if os.path.exists(STORAGE_PATH):
        import shutil
        shutil.rmtree(STORAGE_PATH)
    os.makedirs(STORAGE_PATH, exist_ok=True)

    # Create config directory
    config_path = os.path.join(STORAGE_PATH, "config")
    os.makedirs(config_path, exist_ok=True)

    # Create minimal config with TCP server
    config_content = f"""
[reticulum]
  enable_transport = yes
  share_instance = no
  shared_instance_port = 37429
  instance_control_port = 37430
  panic_on_interface_error = no

[logging]
  loglevel = 7

[interfaces]
  [[TCP Server Interface]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 127.0.0.1
    listen_port = {TCP_PORT}
"""

    config_file = os.path.join(config_path, "config")
    with open(config_file, "w") as f:
        f.write(config_content)

    log(f"Starting Reticulum with config at {config_path}")
    log(f"TCP Server listening on 127.0.0.1:{TCP_PORT}")

    # Initialize Reticulum
    reticulum = RNS.Reticulum(config_path)

    # Wait for startup
    time.sleep(1)

    # Create identity and destination for testing
    identity_path = os.path.join(STORAGE_PATH, "tunnel_test_identity")
    if os.path.exists(identity_path):
        tunnel_test_identity = RNS.Identity.from_file(identity_path)
        log(f"Loaded existing identity")
    else:
        tunnel_test_identity = RNS.Identity()
        tunnel_test_identity.to_file(identity_path)
        log(f"Created new identity")

    # Create destination
    destination = RNS.Destination(
        tunnel_test_identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        APP_NAME,
        "receiver"
    )

    log(f"Destination hash: {RNS.prettyhexrep(destination.hash)}")

    return True

def announce_destination():
    """Announce the test destination."""
    global destination

    if destination:
        destination.announce()
        log(f"Announced destination {RNS.prettyhexrep(destination.hash)}")

def check_tunnels():
    """Check and log active tunnels."""
    tunnel_count = len(RNS.Transport.tunnels)
    log(f"Active tunnels: {tunnel_count}")

    for tunnel_id, tunnel_entry in RNS.Transport.tunnels.items():
        interface = tunnel_entry[1] if len(tunnel_entry) > 1 else None
        paths = tunnel_entry[2] if len(tunnel_entry) > 2 else {}
        expires = tunnel_entry[3] if len(tunnel_entry) > 3 else 0

        interface_name = str(interface) if interface else "None"
        path_count = len(paths) if paths else 0

        log(f"  Tunnel {RNS.prettyhexrep(tunnel_id[:8])}: interface={interface_name}, paths={path_count}")

        if paths:
            for dest_hash, path_entry in paths.items():
                hops = path_entry[2] if len(path_entry) > 2 else "?"
                log(f"    Path to {RNS.prettyhexrep(dest_hash[:8])}: hops={hops}")

def check_interfaces():
    """Check and log interfaces."""
    log(f"Interfaces: {len(RNS.Transport.interfaces)}")
    for iface in RNS.Transport.interfaces:
        tunnel_id = getattr(iface, 'tunnel_id', None)
        tunnel_str = RNS.prettyhexrep(tunnel_id[:8]) if tunnel_id else "None"
        log(f"  {iface}: tunnel_id={tunnel_str}")

def main_loop():
    """Main loop - periodically announce and check tunnels."""
    log("Starting main loop - press Ctrl+C to exit")
    log("Waiting for Kotlin client to connect and establish tunnel...")

    last_announce = 0
    last_check = 0
    announce_interval = 30  # Announce every 30 seconds
    check_interval = 10     # Check tunnels every 10 seconds

    try:
        while True:
            now = time.time()

            # Periodic announce
            if now - last_announce > announce_interval:
                announce_destination()
                last_announce = now

            # Periodic tunnel check
            if now - last_check > check_interval:
                check_tunnels()
                check_interfaces()
                last_check = now

            time.sleep(1)

    except KeyboardInterrupt:
        log("Shutting down...")

def signal_handler(sig, frame):
    """Handle Ctrl+C gracefully."""
    log("Received interrupt signal")
    sys.exit(0)

def main():
    """Main entry point."""
    signal.signal(signal.SIGINT, signal_handler)

    log("=" * 60)
    log("Python Tunnel Test Server")
    log("=" * 60)

    if not setup_rns():
        log("Failed to initialize Reticulum")
        sys.exit(1)

    # Initial announce
    announce_destination()

    # Run main loop
    main_loop()

if __name__ == "__main__":
    main()
