#!/usr/bin/env python3
"""
Minimal TCP test server for Kotlin-Python TCP interop testing.

Uses RNS TCPServerInterface to accept connections and exchange HDLC-framed packets.
Communication with test harness via stdin/stdout protocol:

Stdin commands:
  SEND <hex>  - Send hex bytes to connected client (will be HDLC framed)
  QUIT        - Clean shutdown

Stdout protocol:
  READY       - Printed when server is listening
  RECEIVED: <hex> - Printed for each deframed packet received
  SENT: <hex>     - Confirmation after sending
  ERROR: <msg>    - Error message

Usage:
  python python_tcp_test_server.py <port>
"""

import sys
import os
import threading
import time
import binascii

# Suppress RNS logging
os.environ["RNS_LOG_LEVEL"] = "7"  # LOG_CRITICAL - suppress most output

# Import RNS
import RNS

# HDLC constants (from RNS)
class HDLC:
    FLAG = 0x7E
    ESC = 0x7D
    ESC_MASK = 0x20

    @staticmethod
    def escape(data):
        data = data.replace(bytes([HDLC.ESC]), bytes([HDLC.ESC, HDLC.ESC ^ HDLC.ESC_MASK]))
        data = data.replace(bytes([HDLC.FLAG]), bytes([HDLC.ESC, HDLC.FLAG ^ HDLC.ESC_MASK]))
        return data

    @staticmethod
    def frame(data):
        return bytes([HDLC.FLAG]) + HDLC.escape(data) + bytes([HDLC.FLAG])


class MinimalTCPServer:
    """Minimal TCP server that uses RNS TCPServerInterface patterns."""

    def __init__(self, port):
        self.port = port
        self.client_socket = None
        self.running = True
        self.lock = threading.Lock()
        self.received_frames = []

    def start(self):
        """Start the TCP server."""
        import socket
        import socketserver

        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(("127.0.0.1", self.port))
        self.server_socket.listen(1)
        self.server_socket.settimeout(1.0)  # Allow periodic checks

        # Start accept thread
        self.accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.accept_thread.start()

        print("READY", flush=True)

    def _accept_loop(self):
        """Accept incoming connections."""
        while self.running:
            try:
                client_socket, addr = self.server_socket.accept()
                client_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

                with self.lock:
                    self.client_socket = client_socket

                # Start read loop for this client
                read_thread = threading.Thread(target=self._read_loop, args=(client_socket,), daemon=True)
                read_thread.start()

            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"ERROR: accept failed: {e}", flush=True)
                break

    def _read_loop(self, sock):
        """Read and deframe incoming data using HDLC framing."""
        frame_buffer = b""

        try:
            while self.running:
                try:
                    data = sock.recv(4096)
                    if not data:
                        break

                    # HDLC deframing (same algorithm as RNS TCPInterface)
                    frame_buffer += data

                    while True:
                        frame_start = frame_buffer.find(bytes([HDLC.FLAG]))
                        if frame_start == -1:
                            break

                        frame_end = frame_buffer.find(bytes([HDLC.FLAG]), frame_start + 1)
                        if frame_end == -1:
                            break

                        frame = frame_buffer[frame_start + 1:frame_end]
                        frame_buffer = frame_buffer[frame_end:]

                        # Unescape
                        frame = frame.replace(bytes([HDLC.ESC, HDLC.FLAG ^ HDLC.ESC_MASK]), bytes([HDLC.FLAG]))
                        frame = frame.replace(bytes([HDLC.ESC, HDLC.ESC ^ HDLC.ESC_MASK]), bytes([HDLC.ESC]))

                        # Report received frame (skip empty keepalive frames)
                        if len(frame) > 0:
                            hex_data = binascii.hexlify(frame).decode('ascii')
                            print(f"RECEIVED: {hex_data}", flush=True)
                            with self.lock:
                                self.received_frames.append(frame)

                except socket.timeout:
                    continue

        except Exception as e:
            if self.running:
                print(f"ERROR: read loop: {e}", flush=True)

    def send(self, data):
        """Send HDLC-framed data to connected client."""
        with self.lock:
            if self.client_socket is None:
                print("ERROR: no client connected", flush=True)
                return False

            try:
                framed = HDLC.frame(data)
                self.client_socket.sendall(framed)
                hex_data = binascii.hexlify(data).decode('ascii')
                print(f"SENT: {hex_data}", flush=True)
                return True
            except Exception as e:
                print(f"ERROR: send failed: {e}", flush=True)
                return False

    def stop(self):
        """Stop the server."""
        self.running = False
        with self.lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
        try:
            self.server_socket.close()
        except:
            pass


def main():
    if len(sys.argv) != 2:
        print("ERROR: Usage: python_tcp_test_server.py <port>", flush=True)
        sys.exit(1)

    try:
        port = int(sys.argv[1])
    except ValueError:
        print(f"ERROR: Invalid port: {sys.argv[1]}", flush=True)
        sys.exit(1)

    server = MinimalTCPServer(port)
    server.start()

    # Process stdin commands
    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue

            if line == "QUIT":
                break
            elif line.startswith("SEND "):
                hex_data = line[5:].strip()
                try:
                    data = binascii.unhexlify(hex_data)
                    server.send(data)
                except Exception as e:
                    print(f"ERROR: invalid hex data: {e}", flush=True)
            else:
                print(f"ERROR: unknown command: {line}", flush=True)

    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == "__main__":
    import socket
    main()
