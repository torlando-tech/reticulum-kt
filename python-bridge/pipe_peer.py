#!/usr/bin/env python3
"""
Python RNS Pipe Peer for Routing Parity Tests.

This script runs as a subprocess, communicating with a Kotlin PipeInterface
via stdin/stdout (HDLC-framed RNS packets) and stderr (JSON control messages).

Protocol:
  - stdin/stdout: HDLC-framed Reticulum packets (same as PipeInterface)
  - stderr: JSON status/control messages, one per line

The script accepts commands via environment variables:
  PIPE_PEER_ACTION: What to do after startup
    "announce" - Create a destination and announce it
    "listen"   - Just listen and report what arrives
    "transport" - Enable transport mode and forward

  PIPE_PEER_APP_NAME: App name for destination (default: "pipetest")
  PIPE_PEER_ASPECTS: Comma-separated aspects (default: "routing")
  PIPE_PEER_TRANSPORT: Enable transport (default: "false")

Status messages on stderr (JSON, one per line):
  {"type": "ready", "identity_hash": "..."}
  {"type": "announced", "destination_hash": "...", "identity_hash": "...", "identity_public_key": "..."}
  {"type": "path_learned", "destination_hash": "...", "hops": N, "next_hop": "..."}
  {"type": "announce_received", "destination_hash": "...", "hops": N, "identity_hash": "..."}
  {"type": "path_table", "entries": [...]}
  {"type": "error", "message": "..."}
"""

import sys
import os
import json
import time
import threading
import tempfile

# Add RNS to path
rns_path = os.environ.get('PYTHON_RNS_PATH',
    os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', 'Reticulum')))
sys.path.insert(0, rns_path)

def emit(msg):
    """Write a JSON message to stderr (control channel)."""
    sys.stderr.write(json.dumps(msg) + "\n")
    sys.stderr.flush()


def bytes_to_hex(b):
    return b.hex() if b else ""


def main():
    import RNS

    action = os.environ.get("PIPE_PEER_ACTION", "announce")
    app_name = os.environ.get("PIPE_PEER_APP_NAME", "pipetest")
    aspects = os.environ.get("PIPE_PEER_ASPECTS", "routing").split(",")
    enable_transport = os.environ.get("PIPE_PEER_TRANSPORT", "false").lower() == "true"

    # Suppress RNS logging to avoid polluting stdout (which is the data channel)
    RNS.loglevel = RNS.LOG_CRITICAL

    # Create temp config dir
    config_path = tempfile.mkdtemp(prefix="rns_pipe_peer_")
    config_file = os.path.join(config_path, "config")
    os.makedirs(config_path, exist_ok=True)
    with open(config_file, "w") as f:
        f.write("[reticulum]\n")
        f.write(f"  enable_transport = {'Yes' if enable_transport else 'No'}\n")
        f.write("  share_instance = No\n")
        f.write("\n[interfaces]\n")

    # Start Reticulum
    reticulum = RNS.Reticulum(configdir=config_path, loglevel=RNS.LOG_CRITICAL)

    # Create a custom pipe interface that reads from stdin and writes to stdout
    pipe_iface = _create_stdio_pipe_interface(RNS)

    # Set owner to Transport so inbound packets are delivered.
    # Python's real PipeInterface receives owner as a constructor arg;
    # _add_interface() does NOT set it.
    pipe_iface.owner = RNS.Transport

    # Register with Transport
    reticulum._add_interface(pipe_iface)

    identity_hash = bytes_to_hex(RNS.Transport.identity.hash) if RNS.Transport.identity else ""
    emit({"type": "ready", "identity_hash": identity_hash})

    # Register announce handler (Python RNS requires an object with
    # aspect_filter attribute and received_announce method)
    handler = _AnnounceHandler(RNS)
    RNS.Transport.register_announce_handler(handler)

    if action == "announce":
        # Create destination and announce
        identity = RNS.Identity()
        destination = RNS.Destination(
            identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            app_name,
            *aspects
        )

        # Announce the destination
        destination.announce()
        emit({
            "type": "announced",
            "destination_hash": bytes_to_hex(destination.hash),
            "identity_hash": bytes_to_hex(identity.hash),
            "identity_public_key": bytes_to_hex(identity.get_public_key()),
        })

        # Periodically dump path table to stderr
        _path_table_dumper(RNS)

    elif action == "listen":
        _path_table_dumper(RNS)

    elif action == "transport":
        _path_table_dumper(RNS)


class _AnnounceHandler:
    """Announce handler object compatible with RNS.Transport.register_announce_handler.

    Must have:
    - aspect_filter attribute (None = receive all announces)
    - received_announce(destination_hash, announced_identity, app_data) method
    """

    def __init__(self, RNS):
        self.aspect_filter = None
        self._RNS = RNS

    def received_announce(self, destination_hash, announced_identity, app_data):
        RNS = self._RNS
        hops = RNS.Transport.hops_to(destination_hash) if RNS.Transport.has_path(destination_hash) else -1
        emit({
            "type": "announce_received",
            "destination_hash": bytes_to_hex(destination_hash),
            "identity_hash": bytes_to_hex(announced_identity.hash) if announced_identity else "",
            "hops": hops,
        })


def _path_table_dumper(RNS):
    """Periodically dump path table state to stderr, and keep the process alive."""
    last_dump = ""
    try:
        while True:
            time.sleep(1)
            # Dump path table
            entries = []
            for dest_hash in RNS.Transport.path_table:
                entry = RNS.Transport.path_table[dest_hash]
                # Python path_table entry format (IDX_PT_*):
                # [timestamp, next_hop, hops, expires, random_blobs, receiving_interface, packet_hash]
                entries.append({
                    "destination_hash": dest_hash.hex() if isinstance(dest_hash, bytes) else str(dest_hash),
                    "hops": entry[2],
                    "next_hop": entry[1].hex() if isinstance(entry[1], bytes) else str(entry[1]),
                    "receiving_interface": str(entry[5]),
                })

            current = json.dumps(entries, sort_keys=True)
            if current != last_dump:
                emit({"type": "path_table", "entries": entries})
                last_dump = current

    except (KeyboardInterrupt, BrokenPipeError):
        pass


def _create_stdio_pipe_interface(RNS):
    """Create a PipeInterface-like object that uses stdin/stdout.

    Python's PipeInterface spawns a subprocess and talks to it.
    We ARE the subprocess, so we flip the direction: we read from our
    stdin and write to our stdout. We create a minimal Interface subclass
    that does this.
    """
    from RNS.Interfaces.Interface import Interface as BaseInterface

    class StdioPipeInterface(BaseInterface):
        """Interface that communicates via stdin/stdout using HDLC framing."""

        FLAG = 0x7E
        ESC = 0x7D
        ESC_MASK = 0x20
        MAX_CHUNK = 32768

        def __init__(self):
            super().__init__()
            self.HW_MTU = 1064
            self.name = "StdioPipe"
            self.online = False
            self.bitrate = 1000000
            self.IN = True
            self.OUT = True

            # Use binary stdin/stdout
            self._stdin = sys.stdin.buffer
            self._stdout = sys.stdout.buffer

            # Start read thread
            self.online = True
            read_thread = threading.Thread(target=self._read_loop, daemon=True)
            read_thread.start()

        def process_outgoing(self, data):
            if self.online:
                escaped = self._escape(data)
                frame = bytes([self.FLAG]) + escaped + bytes([self.FLAG])
                try:
                    self._stdout.write(frame)
                    self._stdout.flush()
                    self.txb += len(data)
                except (BrokenPipeError, OSError):
                    self.online = False

        def _escape(self, data):
            data = data.replace(bytes([self.ESC]), bytes([self.ESC, self.ESC ^ self.ESC_MASK]))
            data = data.replace(bytes([self.FLAG]), bytes([self.ESC, self.FLAG ^ self.ESC_MASK]))
            return data

        def _read_loop(self):
            try:
                in_frame = False
                escape = False
                data_buffer = b""

                while self.online:
                    chunk = self._stdin.read(1)
                    if not chunk:
                        break

                    byte = chunk[0]

                    if in_frame and byte == self.FLAG:
                        in_frame = False
                        if len(data_buffer) > 0:
                            self.process_incoming(data_buffer)
                    elif byte == self.FLAG:
                        in_frame = True
                        data_buffer = b""
                    elif in_frame and len(data_buffer) < self.HW_MTU:
                        if byte == self.ESC:
                            escape = True
                        else:
                            if escape:
                                if byte == self.FLAG ^ self.ESC_MASK:
                                    byte = self.FLAG
                                elif byte == self.ESC ^ self.ESC_MASK:
                                    byte = self.ESC
                                escape = False
                            data_buffer += bytes([byte])

            except (BrokenPipeError, OSError):
                pass
            finally:
                self.online = False

        def process_incoming(self, data):
            self.rxb += len(data)
            if hasattr(self, "owner") and self.owner is not None:
                self.owner.inbound(data, self)

        def __str__(self):
            return "StdioPipeInterface[StdioPipe]"

    return StdioPipeInterface()


if __name__ == "__main__":
    main()
