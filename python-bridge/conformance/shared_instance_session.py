"""
Shared instance session management for conformance tests.

Manages:
  1. A target implementation subprocess running as a shared instance (TCP)
  2. One or more Python pipe_peer.py clients connected to the shared instance

Topology:
    Client A (pipe_peer.py) ──[TCP]──▶  Target Shared Instance  ◀──[TCP]── Client B (pipe_peer.py)
    (link_listen)                       (transport + forwarding)              (link_initiate)

This tests the real shared instance infrastructure (LocalServerInterface +
LocalClientInterface) instead of pipe-based interfaces.
"""
import json
import os
import shlex
import subprocess
import sys
import threading
import time


class _Client:
    """A pipe_peer.py subprocess connected to a shared instance via TCP."""

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
        """Return all collected messages (for debugging)."""
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


class SharedInstanceSession:
    """
    Manages a target shared instance + Python clients connected via TCP.

    Usage:
        session = SharedInstanceSession(target_cmd, rns_path, tcp_port=37450)
        session.start(
            target_transport=True,
            clients=[
                {"action": "link_listen"},
                {"action": "link_initiate"},
            ]
        )
        # Read messages from clients
        session.stop()
    """

    def __init__(self, target_cmd, rns_path, tcp_port=None):
        self.target_cmd = target_cmd
        self.rns_path = rns_path
        # Pick a random-ish port to avoid conflicts
        self.tcp_port = tcp_port or (37450 + int(time.time() * 1000) % 500)

        # Target subprocess (shared instance server)
        self.target = None

        # Client subprocesses
        self.clients = []

    def start(
        self,
        target_transport=True,
        clients=None,
        app_name="pipetest",
        aspects="routing",
    ):
        """Start the shared instance target and all clients."""
        if clients is None:
            clients = []

        # Start target as shared instance
        target_env = os.environ.copy()
        target_env["PIPE_PEER_ACTION"] = "transport"
        target_env["PIPE_PEER_APP_NAME"] = app_name
        target_env["PIPE_PEER_ASPECTS"] = aspects
        target_env["PIPE_PEER_TRANSPORT"] = "true" if target_transport else "false"
        target_env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
        if "PYTHON_RNS_PATH" not in target_env:
            target_env["PYTHON_RNS_PATH"] = self.rns_path

        self.target = _Client(self.target_cmd, target_env)
        self.target.start()

        # Wait for target to be ready
        ready = self.target.wait_for_message("ready", timeout=20)
        if ready is None:
            raise RuntimeError("Target shared instance did not emit ready")

        # Give the server socket a moment to accept connections
        time.sleep(0.5)

        # Start client subprocesses
        project_root = self._find_project_root()
        client_cmd = f"python3 {os.path.join(project_root, 'python-bridge/pipe_peer.py')}"

        for i, client_config in enumerate(clients):
            client_env = os.environ.copy()
            client_env["PIPE_PEER_ACTION"] = client_config.get("action", "listen")
            client_env["PIPE_PEER_APP_NAME"] = app_name
            client_env["PIPE_PEER_ASPECTS"] = aspects
            client_env["PIPE_PEER_TRANSPORT"] = "false"
            client_env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
            if "PYTHON_RNS_PATH" not in client_env:
                client_env["PYTHON_RNS_PATH"] = self.rns_path

            client = _Client(client_cmd, client_env)
            client.start()
            self.clients.append(client)

            # Wait for client ready
            client_ready = client.wait_for_message("ready", timeout=15)
            if client_ready is None:
                raise RuntimeError(f"Client {i} did not emit ready")

    def stop(self):
        """Shut down everything."""
        for client in self.clients:
            client.stop()
        if self.target:
            self.target.stop()

    def wait_for_target_message(self, msg_type, timeout=15, predicate=None):
        return self.target.wait_for_message(msg_type, timeout=timeout, predicate=predicate)

    def wait_for_client_message(self, client_index, msg_type, timeout=15, predicate=None):
        return self.clients[client_index].wait_for_message(
            msg_type, timeout=timeout, predicate=predicate
        )

    @staticmethod
    def _find_project_root():
        d = os.path.dirname(os.path.abspath(__file__))
        while d != "/":
            if os.path.exists(os.path.join(d, "settings.gradle.kts")):
                return d
            d = os.path.dirname(d)
        return os.path.dirname(os.path.abspath(__file__))
