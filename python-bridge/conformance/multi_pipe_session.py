"""
Multi-pipe session management for multi-party conformance tests.

Manages:
  1. A target implementation subprocess with N pipe interfaces (via fd pairs)
  2. One in-process Python RNS instance connected to interface 0
  3. (N-1) remote pipe_peer.py subprocesses connected to interfaces 1..N-1

Topology:
    Local Python RNS ──[pipe 0]──▶ Target ◀──[pipe 1]── Remote Peer 0
                                   (transport)
                                     ▲
                                     │ [pipe 2]
                                     │
                              Remote Peer 1 (optional)
"""
import json
import os
import shlex
import subprocess
import sys
import tempfile
import threading
import time


class _RemotePeer:
    """A pipe_peer.py subprocess connected to the target via a pipe pair."""

    def __init__(self, cmd, env, fd_in, fd_out):
        self._cmd = cmd
        self._env = env
        self._fd_in = fd_in    # peer reads from this (target's output)
        self._fd_out = fd_out  # peer writes to this (target's input)
        self.process = None
        self._stderr_messages = []
        self._stderr_lock = threading.Lock()
        self._stderr_cond = threading.Condition(self._stderr_lock)
        self._stderr_thread = None

    def start(self):
        self.process = subprocess.Popen(
            shlex.split(self._cmd),
            stdin=self._fd_in,
            stdout=self._fd_out,
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

    def stop(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()


class MultiPipeSession:
    """
    Manages a target with N interfaces: one in-process Python RNS + remote peers.

    Usage:
        session = MultiPipeSession(target_cmd, rns_path, num_remote_peers=1)
        session.start(target_action="transport", remote_actions=["link_listen"])
        # Use session.python_* for local RNS, session.remote(i).* for remote peers
        session.stop()
    """

    def __init__(self, target_cmd, rns_path, num_remote_peers=1):
        self.target_cmd = target_cmd
        self.rns_path = rns_path
        self.num_remote_peers = num_remote_peers
        self.num_interfaces = num_remote_peers + 1  # +1 for local

        # Local Python RNS (interface 0)
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

        # Remote peers
        self.remote_peers = []

        # Python-side link data receiving
        self._python_link_data = []
        self._python_link_lock = threading.Lock()
        self._python_link_cond = threading.Condition(self._python_link_lock)
        self._python_link_closed = False

    def start(
        self,
        target_action="transport",
        target_transport=True,
        target_mode="full",
        remote_actions=None,
        remote_transport=False,
        remote_mode="full",
        app_name="pipetest",
        aspects="routing",
    ):
        """Start the target, local Python RNS, and remote peers."""
        if remote_actions is None:
            remote_actions = ["listen"] * self.num_remote_peers

        # Initialize Python RNS
        self._start_python_rns()

        # Create pipe pairs: for each interface, two OS pipes (to-target + from-target)
        pipe_pairs = []
        target_read_fds = []
        target_write_fds = []

        for i in range(self.num_interfaces):
            r_to_target, w_to_target = os.pipe()
            r_from_target, w_from_target = os.pipe()
            pipe_pairs.append((r_to_target, w_to_target, r_from_target, w_from_target))
            target_read_fds.append(r_to_target)
            target_write_fds.append(w_from_target)

        # Start target subprocess with all fd pairs
        env = os.environ.copy()
        env["PIPE_PEER_ACTION"] = target_action
        env["PIPE_PEER_APP_NAME"] = app_name
        env["PIPE_PEER_ASPECTS"] = aspects
        env["PIPE_PEER_TRANSPORT"] = "true" if target_transport else "false"
        env["PIPE_PEER_MODE"] = target_mode
        env["PIPE_PEER_NUM_IFACES"] = str(self.num_interfaces)
        if "PYTHON_RNS_PATH" not in env:
            env["PYTHON_RNS_PATH"] = self.rns_path

        all_target_fds = []
        for i, (r_in, _, _, w_out) in enumerate(pipe_pairs):
            env[f"PIPE_PEER_IFACE_{i}_FD_IN"] = str(r_in)
            env[f"PIPE_PEER_IFACE_{i}_FD_OUT"] = str(w_out)
            all_target_fds.extend([r_in, w_out])

        self.target_process = subprocess.Popen(
            shlex.split(self.target_cmd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            pass_fds=tuple(all_target_fds),
            env=env,
        )

        # Close target's fds in parent (target inherited them)
        for r_in, _, _, w_out in pipe_pairs:
            os.close(r_in)
            os.close(w_out)

        # Start target stderr reader
        self._target_stderr_thread = threading.Thread(
            target=self._read_target_stderr, daemon=True
        )
        self._target_stderr_thread.start()

        # Interface 0: connect local Python RNS
        _, w_to_target_0, r_from_target_0, _ = pipe_pairs[0]
        self._create_local_pipe(
            os.fdopen(r_from_target_0, 'rb', buffering=0),
            os.fdopen(w_to_target_0, 'wb', buffering=0),
        )

        # Interfaces 1..N: start remote peers
        project_root = self._find_project_root()
        remote_cmd = f"python3 {os.path.join(project_root, 'python-bridge/pipe_peer.py')}"

        for i in range(self.num_remote_peers):
            _, w_to_target, r_from_target, _ = pipe_pairs[i + 1]

            peer_env = os.environ.copy()
            peer_env["PIPE_PEER_ACTION"] = remote_actions[i]
            peer_env["PIPE_PEER_APP_NAME"] = app_name
            peer_env["PIPE_PEER_ASPECTS"] = aspects
            peer_env["PIPE_PEER_TRANSPORT"] = "true" if remote_transport else "false"
            peer_env["PIPE_PEER_MODE"] = remote_mode
            if "PYTHON_RNS_PATH" not in peer_env:
                peer_env["PYTHON_RNS_PATH"] = self.rns_path

            peer = _RemotePeer(remote_cmd, peer_env, r_from_target, w_to_target)
            peer.start()
            self.remote_peers.append(peer)

            # Close peer's fds in parent (peer subprocess inherited them)
            os.close(r_from_target)
            os.close(w_to_target)

    def stop(self):
        """Shut down everything."""
        if self.local_pipe:
            self.local_pipe.online = False
        for peer in self.remote_peers:
            peer.stop()
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

        self._config_path = tempfile.mkdtemp(prefix="rns_multi_py_")
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
                self.name = "MultiPipeLocal"
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
                return "MultiPipeLocal"

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

    def wait_for_target_ready(self, timeout=20):
        return self.wait_for_target_message("ready", timeout=timeout)

    # ─── Remote Peer Message Waiting ──────────────────────────────────

    def remote(self, index):
        """Get a remote peer by index."""
        return self.remote_peers[index]

    def wait_for_remote_message(self, peer_index, msg_type, timeout=15, predicate=None):
        return self.remote_peers[peer_index].wait_for_message(
            msg_type, timeout=timeout, predicate=predicate
        )

    def wait_for_remote_ready(self, peer_index, timeout=20):
        return self.wait_for_remote_message(peer_index, "ready", timeout=timeout)

    def wait_for_remote_announced(self, peer_index, timeout=15):
        return self.wait_for_remote_message(peer_index, "announced", timeout=timeout)

    # ─── Python-side Actions ──────────────────────────────────────────

    def python_announce(self, app_name="pipetest", aspects=("routing",)):
        """Create a Python destination and announce it. Returns (destination, identity)."""
        self.identity = self.RNS.Identity()
        self.destination = self.RNS.Destination(
            self.identity,
            self.RNS.Destination.IN,
            self.RNS.Destination.SINGLE,
            app_name,
            *aspects,
        )
        self.destination.announce()
        return self.destination, self.identity

    def python_has_path(self, dest_hash_hex):
        dest_bytes = bytes.fromhex(dest_hash_hex)
        return self.RNS.Transport.has_path(dest_bytes)

    def python_hops_to(self, dest_hash_hex):
        dest_bytes = bytes.fromhex(dest_hash_hex)
        if self.RNS.Transport.has_path(dest_bytes):
            return self.RNS.Transport.hops_to(dest_bytes)
        return None

    def python_create_link(self, dest_hash_hex, app_name="pipetest", aspects=("routing",)):
        """Create a Link to a destination. Returns the Link object."""
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
        """Set up data and closed callbacks on a Python-side Link."""
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
        """Wait for the Python-side Link to receive data. Returns bytes or None."""
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
