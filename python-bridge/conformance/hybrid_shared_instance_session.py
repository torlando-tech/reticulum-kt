"""
Hybrid session: Target with BOTH pipe interfaces AND a shared instance server.

Manages:
  1. A target implementation subprocess with:
     - N pipe interfaces (via fd pairs) for external peer connections
     - A TCP shared instance server for local client connections
  2. One in-process Python RNS instance connected to pipe interface 0 (external peer)
  3. M shared instance clients (pipe_peer.py subprocesses connecting via TCP)

Topology:
    Local Python RNS ──[pipe 0]──▶ Target ◀──[TCP shared instance]── Client A
    (external peer)                (transport + shared instance)       (link_listen)
                                     ▲
                                     │ [TCP shared instance]
                                     │
                                  Client B (optional)

This tests that the Kotlin transport correctly routes link requests and
proofs between pipe interfaces (external peers) and shared instance clients
(local apps like Ara behind Carina).
"""
import json
import os
import random
import shlex
import subprocess
import sys
import tempfile
import threading
import time


class _SharedClient:
    """A pipe_peer.py subprocess connected to the shared instance via TCP."""

    def __init__(self, cmd, env):
        self._cmd = cmd
        self._env = env
        self.process = None
        self._stderr_messages = []
        self._stderr_lock = threading.Lock()
        self._stderr_cond = threading.Condition(self._stderr_lock)
        self._stderr_thread = None

    def start(self):
        self.process = subprocess.Popen(
            shlex.split(self._cmd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=self._env,
        )
        self._stderr_thread = threading.Thread(target=self._read_stderr, daemon=True)
        self._stderr_thread.start()

    def _read_stderr(self):
        try:
            for line in self.process.stderr:
                line = line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                    with self._stderr_cond:
                        self._stderr_messages.append(msg)
                        self._stderr_cond.notify_all()
                except json.JSONDecodeError:
                    pass
        except (ValueError, OSError):
            pass

    def wait_for_message(self, msg_type, timeout=15, predicate=None):
        deadline = time.time() + timeout
        with self._stderr_cond:
            while time.time() < deadline:
                for msg in self._stderr_messages:
                    if msg.get("type") == msg_type:
                        if predicate is None or predicate(msg):
                            self._stderr_messages.remove(msg)
                            return msg
                remaining = deadline - time.time()
                if remaining > 0:
                    self._stderr_cond.wait(timeout=min(remaining, 0.5))
        return None

    def get_all_messages(self):
        with self._stderr_lock:
            return list(self._stderr_messages)

    def stop(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()


class HybridSharedInstanceSession:
    """
    Target with pipe interfaces + shared instance server, plus Python clients.

    Usage:
        session = HybridSharedInstanceSession(target_cmd, rns_path)
        session.start(
            target_transport=True,
            shared_clients=[{"action": "link_listen"}],
        )
        # Use session.python_* for local RNS (pipe), session.shared_client(i) for TCP clients
        session.stop()
    """

    def __init__(self, target_cmd, rns_path, tcp_port=None):
        self.target_cmd = target_cmd
        self.rns_path = rns_path
        self.tcp_port = tcp_port or random.randint(37450, 47450)

        # Local Python RNS (connected via pipe interface 0)
        self.RNS = None
        self.reticulum = None
        self.local_pipe = None
        self.identity = None
        self.destination = None
        self._config_path = None

        # Target subprocess
        self.target_process = None
        self._target_stderr_thread = None
        self._target_messages = []
        self._target_lock = threading.Lock()
        self._target_cond = threading.Condition(self._target_lock)

        # Shared instance clients (connected via TCP)
        self.shared_clients = []

        # Python-side link data receiving
        self._python_link_data = []
        self._python_link_lock = threading.Lock()
        self._python_link_cond = threading.Condition(self._python_link_lock)
        self._python_link_closed = False

    def start(
        self,
        target_transport=True,
        target_mode="full",
        shared_clients=None,
        app_name="pipetest",
        aspects="routing",
    ):
        """Start the target, local Python RNS, and shared instance clients."""
        if shared_clients is None:
            shared_clients = []

        # Initialize Python RNS
        self._start_python_rns()

        # Create pipe pair for interface 0 (local Python <-> target)
        r_to_target, w_to_target = os.pipe()
        r_from_target, w_from_target = os.pipe()

        # Start target subprocess with pipe interface 0 + shared instance server
        env = os.environ.copy()
        env["PIPE_PEER_ACTION"] = "transport"
        env["PIPE_PEER_APP_NAME"] = app_name
        env["PIPE_PEER_ASPECTS"] = aspects
        env["PIPE_PEER_TRANSPORT"] = "true" if target_transport else "false"
        env["PIPE_PEER_MODE"] = target_mode
        env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
        env["PIPE_PEER_NUM_IFACES"] = "1"
        env["PIPE_PEER_IFACE_0_FD_IN"] = str(r_to_target)
        env["PIPE_PEER_IFACE_0_FD_OUT"] = str(w_from_target)
        if "PYTHON_RNS_PATH" not in env:
            env["PYTHON_RNS_PATH"] = self.rns_path

        self.target_process = subprocess.Popen(
            shlex.split(self.target_cmd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            pass_fds=(r_to_target, w_from_target),
            env=env,
        )

        # Close target's fds in parent
        os.close(r_to_target)
        os.close(w_from_target)

        # Start target stderr reader
        self._target_stderr_thread = threading.Thread(
            target=self._read_target_stderr, daemon=True
        )
        self._target_stderr_thread.start()

        # Connect local Python RNS via pipe
        self._create_local_pipe(
            os.fdopen(r_from_target, 'rb', buffering=0),
            os.fdopen(w_to_target, 'wb', buffering=0),
        )

        # Wait for target to be ready (shared instance server + pipe started)
        ready = self.wait_for_target_message("ready", timeout=20)
        if ready is None:
            raise RuntimeError("Target did not emit ready")

        # Give the shared instance server a moment to accept connections
        time.sleep(0.5)

        # Start shared instance clients (Python pipe_peer subprocesses via TCP)
        project_root = self._find_project_root()
        client_cmd = f"python3 {os.path.join(project_root, 'python-bridge/pipe_peer.py')}"

        for i, client_config in enumerate(shared_clients):
            client_env = os.environ.copy()
            client_env["PIPE_PEER_ACTION"] = client_config.get("action", "link_listen")
            client_env["PIPE_PEER_APP_NAME"] = app_name
            client_env["PIPE_PEER_ASPECTS"] = aspects
            client_env["PIPE_PEER_TRANSPORT"] = "false"
            client_env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
            if "PYTHON_RNS_PATH" not in client_env:
                client_env["PYTHON_RNS_PATH"] = self.rns_path

            client = _SharedClient(client_cmd, client_env)
            client.start()
            self.shared_clients.append(client)

            client_ready = client.wait_for_message("ready", timeout=15)
            if client_ready is None:
                raise RuntimeError(f"Shared client {i} did not emit ready")

    def stop(self):
        """Shut down everything."""
        if self.local_pipe:
            self.local_pipe.online = False
        for client in self.shared_clients:
            client.stop()
        if self.target_process:
            self.target_process.terminate()
            try:
                self.target_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.target_process.kill()
                self.target_process.wait()
        if self.reticulum:
            self.RNS.Reticulum.exit_handler()
            self.RNS.Reticulum._Reticulum__instance = None
            self.RNS.Transport.interfaces = []
            self.RNS.Transport.destinations = []
            self.RNS.Transport.announce_handlers = []
            self.RNS.Transport.path_table = {}
            self.RNS.Transport.packet_hashlist = set()
            self.RNS.Transport.identity = None
            self.reticulum = None
        if self._config_path:
            import shutil
            shutil.rmtree(self._config_path, ignore_errors=True)

    # ─── Local Python RNS ─────────────────────────────────────────────

    def _start_python_rns(self):
        sys.path.insert(0, self.rns_path)
        import RNS
        self.RNS = RNS
        RNS.loglevel = RNS.LOG_CRITICAL

        self._config_path = tempfile.mkdtemp(prefix="rns_hybrid_py_")
        config_file = os.path.join(self._config_path, "config")
        os.makedirs(self._config_path, exist_ok=True)
        with open(config_file, "w") as f:
            f.write("[reticulum]\n")
            f.write("  enable_transport = No\n")
            f.write("  share_instance = No\n")
            f.write("\n[interfaces]\n")

        self.reticulum = RNS.Reticulum(
            configdir=self._config_path, loglevel=RNS.LOG_CRITICAL
        )

    def _create_local_pipe(self, pin, pout):
        """Create a PipeInterface for the local Python RNS using stream objects."""
        from RNS.Interfaces.Interface import Interface as BaseInterface

        class _StreamPipe(BaseInterface):
            FLAG = 0x7E
            ESC = 0x7D
            ESC_MASK = 0x20

            def __init__(self):
                super().__init__()
                self.HW_MTU = 1064
                self.name = "HybridPipeLocal"
                self.online = False
                self.bitrate = 1000000
                self.IN = True
                self.OUT = True
                self._pin = pin
                self._pout = pout
                self.online = True
                threading.Thread(target=self._read_loop, daemon=True).start()

            def process_outgoing(self, data):
                if not self.online:
                    return
                escaped = data.replace(
                    bytes([self.ESC]), bytes([self.ESC, self.ESC ^ self.ESC_MASK])
                )
                escaped = escaped.replace(
                    bytes([self.FLAG]), bytes([self.ESC, self.FLAG ^ self.ESC_MASK])
                )
                frame = bytes([self.FLAG]) + escaped + bytes([self.FLAG])
                try:
                    self._pout.write(frame)
                    self._pout.flush()
                    self.txb += len(data)
                except (BrokenPipeError, OSError):
                    self.online = False

            def _read_loop(self):
                try:
                    in_frame = False
                    escape = False
                    buf = b""
                    while self.online:
                        chunk = self._pin.read(1)
                        if not chunk:
                            break
                        byte = chunk[0]
                        if in_frame and byte == self.FLAG:
                            in_frame = False
                            if buf:
                                self.process_incoming(buf)
                        elif byte == self.FLAG:
                            in_frame = True
                            buf = b""
                        elif in_frame and len(buf) < self.HW_MTU:
                            if byte == self.ESC:
                                escape = True
                            else:
                                if escape:
                                    if byte == self.FLAG ^ self.ESC_MASK:
                                        byte = self.FLAG
                                    elif byte == self.ESC ^ self.ESC_MASK:
                                        byte = self.ESC
                                    escape = False
                                buf += bytes([byte])
                except (BrokenPipeError, OSError):
                    pass
                finally:
                    self.online = False

            def process_incoming(self, data):
                self.rxb += len(data)
                if hasattr(self, "owner") and self.owner is not None:
                    self.owner.inbound(data, self)

            def __str__(self):
                return "HybridPipeLocal"

        iface = _StreamPipe()
        iface.owner = self.RNS.Transport
        self.reticulum._add_interface(iface)
        self.local_pipe = iface
        return iface

    # ─── Target Stderr ────────────────────────────────────────────────

    def _read_target_stderr(self):
        try:
            for line in self.target_process.stderr:
                line = line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                    with self._target_cond:
                        self._target_messages.append(msg)
                        self._target_cond.notify_all()
                except json.JSONDecodeError:
                    pass
        except (ValueError, OSError):
            pass

    # ─── Target Message Waiting ───────────────────────────────────────

    def wait_for_target_message(self, msg_type, timeout=15, predicate=None):
        deadline = time.time() + timeout
        with self._target_cond:
            while time.time() < deadline:
                for msg in self._target_messages:
                    if msg.get("type") == msg_type:
                        if predicate is None or predicate(msg):
                            self._target_messages.remove(msg)
                            return msg
                remaining = deadline - time.time()
                if remaining > 0:
                    self._target_cond.wait(timeout=min(remaining, 0.5))
        return None

    # ─── Shared Client Message Waiting ────────────────────────────────

    def shared_client(self, index):
        return self.shared_clients[index]

    def wait_for_shared_client_message(self, client_index, msg_type, timeout=15, predicate=None):
        return self.shared_clients[client_index].wait_for_message(
            msg_type, timeout=timeout, predicate=predicate
        )

    def wait_for_shared_client_announced(self, client_index, timeout=15):
        return self.wait_for_shared_client_message(client_index, "announced", timeout=timeout)

    # ─── Python-side Actions ──────────────────────────────────────────

    def python_has_path(self, dest_hash_hex):
        dest_bytes = bytes.fromhex(dest_hash_hex)
        return self.RNS.Transport.has_path(dest_bytes)

    def python_hops_to(self, dest_hash_hex):
        dest_bytes = bytes.fromhex(dest_hash_hex)
        if self.RNS.Transport.has_path(dest_bytes):
            return self.RNS.Transport.hops_to(dest_bytes)
        return None

    def python_create_link(self, dest_hash_hex, app_name="pipetest", aspects=("routing",)):
        dest_bytes = bytes.fromhex(dest_hash_hex)
        identity = self.RNS.Identity.recall(dest_bytes)
        if identity is None:
            raise ValueError(f"Cannot recall identity for {dest_hash_hex}")
        dest = self.RNS.Destination(
            identity,
            self.RNS.Destination.OUT,
            self.RNS.Destination.SINGLE,
            app_name,
            *aspects,
        )
        return self.RNS.Link(dest)

    # ─── Python-side Link Data Receiving ──────────────────────────────

    def setup_python_link_callbacks(self, link):
        self._python_link_data = []
        self._python_link_closed = False

        def on_data(message, packet):
            data = message if isinstance(message, bytes) else bytes(message)
            with self._python_link_cond:
                self._python_link_data.append(data)
                self._python_link_cond.notify_all()

        def on_closed(link):
            with self._python_link_cond:
                self._python_link_closed = True
                self._python_link_cond.notify_all()

        link.set_packet_callback(on_data)
        link.set_link_closed_callback(on_closed)

    def wait_for_python_link_data(self, timeout=15):
        deadline = time.time() + timeout
        with self._python_link_cond:
            while time.time() < deadline:
                if self._python_link_data:
                    return self._python_link_data.pop(0)
                remaining = deadline - time.time()
                if remaining > 0:
                    self._python_link_cond.wait(timeout=min(remaining, 0.5))
        return None

    def wait_for_python_link_closed(self, timeout=15):
        deadline = time.time() + timeout
        with self._python_link_cond:
            while time.time() < deadline:
                if self._python_link_closed:
                    return True
                remaining = deadline - time.time()
                if remaining > 0:
                    self._python_link_cond.wait(timeout=min(remaining, 0.5))
        return False

    # ─── Helpers ──────────────────────────────────────────────────────

    @staticmethod
    def _find_project_root():
        d = os.path.dirname(os.path.abspath(__file__))
        while d != "/":
            if os.path.exists(os.path.join(d, "settings.gradle.kts")):
                return d
            d = os.path.dirname(d)
        return os.path.dirname(os.path.abspath(__file__))
