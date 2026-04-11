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
    "announce"      - Create a destination and announce it
    "listen"        - Just listen and report what arrives
    "link_listen"   - Create a destination, announce it, and accept incoming links
    "channel_serve" - Accept a link and send a proof-dependent channel sequence
    "transport"     - Enable transport mode and forward

  PIPE_PEER_APP_NAME: App name for destination (default: "pipetest")
  PIPE_PEER_ASPECTS: Comma-separated aspects (default: "routing")
  PIPE_PEER_TRANSPORT: Enable transport (default: "false")

  PIPE_PEER_MODE: Interface mode (default: "full")
    "full" | "ap" | "roaming" | "boundary" | "gateway" | "p2p"

Status messages on stderr (JSON, one per line):
  {"type": "ready", "identity_hash": "..."}
  {"type": "announced", "destination_hash": "...", "identity_hash": "...", "identity_public_key": "..."}
  {"type": "announce_received", "destination_hash": "...", "hops": N, "identity_hash": "..."}
  {"type": "path_table", "entries": [...]}
  {"type": "link_established", "link_id": "...", "destination_hash": "..."}
  {"type": "link_closed", "link_id": "...", "destination_hash": "..."}
  {"type": "link_data", "link_id": "...", "data_hex": "...", "data_utf8": "..."}
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


_BridgeMessageClass = None


def _get_bridge_message_class():
    """Create a simple channel message type for interoperability tests."""
    import RNS
    global _BridgeMessageClass
    if _BridgeMessageClass is None:
        class BridgeMessage(RNS.Channel.MessageBase):
            MSGTYPE = 0x0101

            def __init__(self, data=b""):
                self.data = data

            def pack(self):
                return self.data

            def unpack(self, raw):
                self.data = raw

        _BridgeMessageClass = BridgeMessage
    return _BridgeMessageClass


def main():
    import RNS

    action = os.environ.get("PIPE_PEER_ACTION", "announce")
    app_name = os.environ.get("PIPE_PEER_APP_NAME", "pipetest")
    aspects = os.environ.get("PIPE_PEER_ASPECTS", "routing").split(",")
    enable_transport = os.environ.get("PIPE_PEER_TRANSPORT", "false").lower() == "true"
    mode_str = os.environ.get("PIPE_PEER_MODE", "full").lower()
    shared_port = int(os.environ.get("PIPE_PEER_SHARED_PORT", "0"))

    # Suppress RNS logging to avoid polluting stdout (which is the data channel)
    RNS.loglevel = RNS.LOG_CRITICAL

    # Create temp config dir
    config_path = tempfile.mkdtemp(prefix="rns_pipe_peer_")
    config_file = os.path.join(config_path, "config")
    os.makedirs(config_path, exist_ok=True)
    with open(config_file, "w") as f:
        f.write("[reticulum]\n")
        f.write(f"  enable_transport = {'Yes' if enable_transport else 'No'}\n")
        if shared_port > 0:
            # Use shared instance on this port.
            # Python Reticulum: the first process to bind the port becomes
            # the server (LocalServerInterface); subsequent processes fail
            # to bind and fall back to connecting as clients
            # (LocalClientInterface). So ALL processes use share_instance=Yes.
            f.write("  share_instance = Yes\n")
            f.write(f"  shared_instance_port = {shared_port}\n")
            f.write("  shared_instance_type = tcp\n")
        else:
            f.write("  share_instance = No\n")
        f.write("\n[interfaces]\n")

    # Start Reticulum
    reticulum = RNS.Reticulum(configdir=config_path, loglevel=RNS.LOG_CRITICAL)

    # Map mode string to RNS interface mode constant
    mode_map = {
        "full": RNS.Interfaces.Interface.Interface.MODE_FULL,
        "ap": RNS.Interfaces.Interface.Interface.MODE_ACCESS_POINT,
        "access_point": RNS.Interfaces.Interface.Interface.MODE_ACCESS_POINT,
        "roaming": RNS.Interfaces.Interface.Interface.MODE_ROAMING,
        "boundary": RNS.Interfaces.Interface.Interface.MODE_BOUNDARY,
        "gateway": RNS.Interfaces.Interface.Interface.MODE_GATEWAY,
        "p2p": RNS.Interfaces.Interface.Interface.MODE_POINT_TO_POINT,
        "point_to_point": RNS.Interfaces.Interface.Interface.MODE_POINT_TO_POINT,
    }
    iface_mode = mode_map.get(mode_str, RNS.Interfaces.Interface.Interface.MODE_FULL)

    # Create interfaces: shared instance or pipe-based
    if shared_port > 0:
        # Shared instance client mode: Reticulum already connected via config
        pass
    else:
        # Pipe interface mode
        num_ifaces = int(os.environ.get("PIPE_PEER_NUM_IFACES", "0"))
        if num_ifaces > 0:
            # Multi-interface mode: create N interfaces from fd pairs
            for i in range(num_ifaces):
                fd_in = int(os.environ[f"PIPE_PEER_IFACE_{i}_FD_IN"])
                fd_out = int(os.environ[f"PIPE_PEER_IFACE_{i}_FD_OUT"])
                iface = _create_pipe_interface(
                    RNS,
                    os.fdopen(fd_in, 'rb', buffering=0),
                    os.fdopen(fd_out, 'wb', buffering=0),
                    f"Pipe{i}"
                )
                iface.owner = RNS.Transport
                reticulum._add_interface(iface, mode=iface_mode)
        else:
            # Single interface mode: stdin/stdout
            pipe_iface = _create_pipe_interface(
                RNS, sys.stdin.buffer, sys.stdout.buffer, "StdioPipe"
            )
            pipe_iface.owner = RNS.Transport
            reticulum._add_interface(pipe_iface, mode=iface_mode)

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

    elif action == "link_listen":
        # Create destination that accepts incoming links
        identity = RNS.Identity()
        destination = RNS.Destination(
            identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            app_name,
            *aspects
        )
        destination.set_link_established_callback(_link_established)
        destination.announce()
        emit({
            "type": "announced",
            "destination_hash": bytes_to_hex(destination.hash),
            "identity_hash": bytes_to_hex(identity.hash),
            "identity_public_key": bytes_to_hex(identity.get_public_key()),
        })
        _path_table_dumper(RNS)

    elif action == "link_serve":
        # Transport node with its own link-accepting destination.
        # Sends a welcome message on link establishment and echoes received data.
        # Simulates a hub (like Carina/RRC) that is also a transport node.
        identity = RNS.Identity()
        destination = RNS.Destination(
            identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            app_name,
            *aspects
        )
        destination.set_link_established_callback(_link_serve_established)
        destination.announce()
        emit({
            "type": "announced",
            "destination_hash": bytes_to_hex(destination.hash),
            "identity_hash": bytes_to_hex(identity.hash),
            "identity_public_key": bytes_to_hex(identity.get_public_key()),
        })
        _path_table_dumper(RNS)

    elif action == "channel_serve":
        identity = RNS.Identity()
        destination = RNS.Destination(
            identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            app_name,
            *aspects
        )
        destination.set_link_established_callback(_channel_serve_established)
        destination.announce()
        emit({
            "type": "announced",
            "destination_hash": bytes_to_hex(destination.hash),
            "identity_hash": bytes_to_hex(identity.hash),
            "identity_public_key": bytes_to_hex(identity.get_public_key()),
        })
        _path_table_dumper(RNS)

    elif action == "self_link":
        # Create a destination AND a link to itself.
        # Both link endpoints will be in this process.
        # The LINKREQUEST bounces through the shared instance/pipe and returns.
        identity = RNS.Identity()
        destination = RNS.Destination(
            identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            app_name,
            *aspects
        )
        destination.set_link_established_callback(_link_established)
        destination.announce()
        emit({
            "type": "announced",
            "destination_hash": bytes_to_hex(destination.hash),
            "identity_hash": bytes_to_hex(identity.hash),
            "identity_public_key": bytes_to_hex(identity.get_public_key()),
        })

        # Give the announce time to propagate through the shared instance.
        # The shared instance stores the path; we don't need it locally.
        # Transport.outbound() will broadcast the LINKREQUEST on all
        # interfaces (no path_table entry needed), and the shared instance
        # routes it back to us.
        time.sleep(2)

        # Create a link to our own destination.
        # The LINKREQUEST goes out via LocalClientInterface to the shared
        # instance, which forwards it back. The responder side (same
        # process) handles it and sends a LINKPROOF back through the
        # shared instance. Both endpoints live in this process.
        link = RNS.Link(destination)
        emit({
            "type": "self_link_initiated",
            "destination_hash": bytes_to_hex(destination.hash),
            "link_id": bytes_to_hex(link.link_id) if link.link_id else "",
        })

        # Wait for link to become active
        link_deadline = time.time() + 20
        while time.time() < link_deadline:
            if link.status == RNS.Link.ACTIVE:
                break
            time.sleep(0.1)

        if link.status == RNS.Link.ACTIVE:
            emit({
                "type": "self_link_active",
                "link_id": bytes_to_hex(link.link_id),
            })

            # Set up data callback on the initiator link
            received_data = []
            def on_initiator_data(message, packet):
                data = message if isinstance(message, bytes) else bytes(message)
                received_data.append(data)
                emit({
                    "type": "self_link_data_received",
                    "link_id": bytes_to_hex(link.link_id),
                    "data_hex": data.hex(),
                    "data_utf8": data.decode("utf-8", errors="replace"),
                    "side": "initiator",
                })
            link.set_packet_callback(on_initiator_data)

            # Send test data on the link
            test_data = b"self-link-test-data"
            RNS.Packet(link, test_data).send()
            emit({
                "type": "self_link_data_sent",
                "link_id": bytes_to_hex(link.link_id),
                "data_hex": test_data.hex(),
            })

            _path_table_dumper(RNS)
        else:
            emit({
                "type": "error",
                "message": f"Self-link did not become active, status={link.status}",
            })
            _path_table_dumper(RNS)

    elif action == "link_initiate":
        # Wait for a destination to appear, then create a link to it.
        # Used with shared instance tests where another client announces.
        emit({"type": "waiting_for_destination"})

        # Wait for a path to any destination matching our app/aspects
        dest_hash = None
        dest_identity = None
        deadline = time.time() + 20
        while time.time() < deadline:
            for dh in RNS.Transport.path_table:
                dest_hash = dh
                dest_identity = RNS.Identity.recall(dh)
                if dest_identity is not None:
                    break
            if dest_identity is not None:
                break
            time.sleep(0.2)

        if dest_identity is None:
            emit({"type": "error", "message": "No destination found within timeout"})
            _path_table_dumper(RNS)
        else:
            dest_hash_hex = bytes_to_hex(dest_hash)
            emit({"type": "destination_found", "destination_hash": dest_hash_hex})

            # Create OUT destination for linking
            out_dest = RNS.Destination(
                dest_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                app_name,
                *aspects
            )
            link = RNS.Link(out_dest)
            emit({
                "type": "link_initiated",
                "destination_hash": dest_hash_hex,
                "link_id": bytes_to_hex(link.link_id) if link.link_id else "",
            })

            # Wait for link to become active
            link_deadline = time.time() + 20
            while time.time() < link_deadline:
                if link.status == RNS.Link.ACTIVE:
                    break
                time.sleep(0.1)

            if link.status == RNS.Link.ACTIVE:
                emit({
                    "type": "link_established",
                    "link_id": bytes_to_hex(link.link_id),
                    "destination_hash": dest_hash_hex,
                })

                # Set up data receiving
                link.set_packet_callback(_link_data)
                link.set_link_closed_callback(_link_closed)

                # Send test data
                test_data = b"hello-from-initiator"
                RNS.Packet(link, test_data).send()
                emit({
                    "type": "link_sent",
                    "link_id": bytes_to_hex(link.link_id),
                    "data_hex": test_data.hex(),
                })

                _path_table_dumper(RNS)
            else:
                emit({
                    "type": "error",
                    "message": f"Link did not become active, status={link.status}",
                })
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


def _link_established(link):
    """Called when a link is established to our destination."""
    link_id = link.link_id.hex() if link.link_id else ""
    dest_hash = link.destination.hash.hex() if link.destination else ""
    emit({
        "type": "link_established",
        "link_id": link_id,
        "destination_hash": dest_hash,
    })
    link.set_link_closed_callback(_link_closed)
    link.set_packet_callback(_link_data)


def _link_serve_established(link):
    """Called when a link is established in link_serve mode.

    Sends a welcome message, then echoes back any received data.
    This simulates a hub (like RRC) that sends data to the client.
    """
    import RNS as _RNS

    link_id = link.link_id.hex() if link.link_id else ""
    dest_hash = link.destination.hash.hex() if link.destination else ""
    emit({
        "type": "link_established",
        "link_id": link_id,
        "destination_hash": dest_hash,
    })
    link.set_link_closed_callback(_link_closed)

    def _serve_data(message, packet):
        """Report received data and echo it back."""
        lnk = packet.link
        lid = lnk.link_id.hex() if lnk and lnk.link_id else ""
        data = message if isinstance(message, bytes) else bytes(message)
        emit({
            "type": "link_data",
            "link_id": lid,
            "data_hex": data.hex(),
            "data_utf8": data.decode("utf-8", errors="replace"),
        })
        # Echo data back over the link
        try:
            _RNS.Packet(lnk, data).send()
            emit({"type": "link_sent", "link_id": lid, "data_hex": data.hex()})
        except Exception as e:
            emit({"type": "error", "message": f"Echo send failed: {e}"})

    link.set_packet_callback(_serve_data)

    # Send welcome message after a short delay (let link fully activate)
    import threading
    def send_welcome():
        import time
        time.sleep(0.5)
        try:
            welcome = b"welcome"
            _RNS.Packet(link, welcome).send()
            emit({"type": "link_sent", "link_id": link_id, "data_hex": welcome.hex()})
        except Exception as e:
            emit({"type": "error", "message": f"Welcome send failed: {e}"})

    threading.Thread(target=send_welcome, daemon=True).start()


def _setup_channel_peer(link, send_sequence=False):
    """Register a simple channel type and optionally send a proof-gated sequence."""
    BridgeMessage = _get_bridge_message_class()
    channel = link.get_channel()
    channel.register_message_type(BridgeMessage)

    def on_channel_message(message):
        if isinstance(message, BridgeMessage):
            data = bytes(message.data)
            emit({
                "type": "channel_data",
                "link_id": bytes_to_hex(link.link_id),
                "data_hex": data.hex(),
                "data_utf8": data.decode("utf-8", errors="replace"),
            })
            return True
        return False

    channel.add_message_handler(on_channel_message)

    if not send_sequence:
        return

    def send_messages():
        time.sleep(1.0)
        for payload in (b"channel-one", b"channel-two", b"channel-three"):
            deadline = time.time() + 5.0
            while time.time() < deadline and link.status == link.ACTIVE and not channel.is_ready_to_send():
                time.sleep(0.05)

            if link.status != link.ACTIVE:
                emit({"type": "error", "message": "Link became inactive before channel send"})
                return

            if not channel.is_ready_to_send():
                emit({"type": "error", "message": "Channel never became ready for next send"})
                return

            try:
                channel.send(BridgeMessage(payload))
                emit({"type": "channel_sent", "link_id": bytes_to_hex(link.link_id), "data_hex": payload.hex()})
            except Exception as e:
                emit({"type": "error", "message": f"Channel send failed: {e}"})
                return

    threading.Thread(target=send_messages, daemon=True).start()


def _channel_serve_established(link):
    """Called when a link is established in channel_serve mode."""
    link_id = link.link_id.hex() if link.link_id else ""
    dest_hash = link.destination.hash.hex() if link.destination else ""
    emit({
        "type": "link_established",
        "link_id": link_id,
        "destination_hash": dest_hash,
    })
    link.set_link_closed_callback(_link_closed)
    _setup_channel_peer(link, send_sequence=True)


def _link_closed(link):
    """Called when a link is closed."""
    link_id = link.link_id.hex() if link.link_id else ""
    dest_hash = link.destination.hash.hex() if link.destination else ""
    emit({
        "type": "link_closed",
        "link_id": link_id,
        "destination_hash": dest_hash,
    })


def _link_data(message, packet):
    """Called when data is received on a link."""
    link = packet.link
    link_id = link.link_id.hex() if link and link.link_id else ""
    data = message if isinstance(message, bytes) else bytes(message)
    emit({
        "type": "link_data",
        "link_id": link_id,
        "data_hex": data.hex(),
        "data_utf8": data.decode("utf-8", errors="replace"),
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


def _create_pipe_interface(RNS, pin, pout, name="StdioPipe"):
    """Create a PipeInterface-like object from input/output binary streams.

    Used for both stdin/stdout (single interface) and fd-backed streams
    (multi-interface mode).
    """
    from RNS.Interfaces.Interface import Interface as BaseInterface

    class StreamPipeInterface(BaseInterface):
        """Interface that communicates via HDLC framing over binary streams."""

        FLAG = 0x7E
        ESC = 0x7D
        ESC_MASK = 0x20

        def __init__(self):
            super().__init__()
            self.HW_MTU = 1064
            self.name = name
            self.online = False
            self.bitrate = 1000000
            self.IN = True
            self.OUT = True
            self._stdin = pin
            self._stdout = pout
            self.online = True
            threading.Thread(target=self._read_loop, daemon=True).start()

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
            return f"StreamPipeInterface[{name}]"

    return StreamPipeInterface()


if __name__ == "__main__":
    main()
