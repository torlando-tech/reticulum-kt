"""
Session where Python is the shared instance server and clients connect via TCP.

This mirrors the Android/Ara topology:
    Python shared instance (Chaquopy) ← TCP ← Kotlin client (reticulum-kt)

Topology:
    Hub (Python, link_listen) ──[TCP]──▶  Python Shared Instance  ◀──[TCP]── Client (Python or Kotlin)
                                          (transport)                         (link_initiate)

The shared instance is always Python to match what Chaquopy provides on Android.
Clients can be either Python pipe_peer.py or Kotlin PipePeer (via kt_client_cmd).
"""
import json
import os
import random
import shlex
import subprocess
import threading
import time


class _Subprocess:
    """A subprocess that reports JSON messages on stderr."""

    def __init__(self, cmd, env):
        self._cmd = cmd
        self._env = env
        self.process = None
        self._messages = []
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._thread = None

    def start(self):
        self.process = subprocess.Popen(
            shlex.split(self._cmd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=self._env,
        )
        self._thread = threading.Thread(target=self._read_stderr, daemon=True)
        self._thread.start()

    def _read_stderr(self):
        try:
            for line in self.process.stderr:
                line = line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                    with self._cond:
                        self._messages.append(msg)
                        self._cond.notify_all()
                except json.JSONDecodeError:
                    pass
        except (ValueError, OSError):
            pass

    def wait_for_message(self, msg_type, timeout=15, predicate=None):
        deadline = time.time() + timeout
        with self._cond:
            while time.time() < deadline:
                for msg in self._messages:
                    if msg.get("type") == msg_type:
                        if predicate is None or predicate(msg):
                            self._messages.remove(msg)
                            return msg
                remaining = deadline - time.time()
                if remaining > 0:
                    self._cond.wait(timeout=min(remaining, 0.5))
        return None

    def get_all_messages(self):
        with self._lock:
            return list(self._messages)

    def stop(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass


class PythonSharedInstanceSession:
    """
    Python shared instance with multiple clients connected via TCP.

    The shared instance is always a Python pipe_peer.py subprocess with
    share_instance=Yes and transport enabled. Clients connect to it via TCP.

    Usage:
        session = PythonSharedInstanceSession(rns_path)
        session.start(
            hub_action="link_listen",
            client_action="link_initiate",
            client_cmd=kt_peer_cmd,  # or None for Python
        )
        # Read messages from session.hub and session.client
        session.stop()
    """

    def __init__(self, rns_path, tcp_port=None):
        self.rns_path = rns_path
        self.tcp_port = tcp_port or random.randint(37450, 47450)

        self.server = None   # Python shared instance
        self.hub = None      # Hub (link_listen)
        self.client = None   # Client (link_initiate)

    def start(
        self,
        hub_action="link_listen",
        client_action="link_initiate",
        client_cmd=None,
        client_env_key="PIPE_PEER_SHARED_PORT",
        app_name="pipetest",
        aspects="routing",
    ):
        """
        Start the session.

        Args:
            hub_action: Action for the hub subprocess (default: link_listen)
            client_action: Action for the client subprocess (default: link_initiate)
            client_cmd: Command for the client. None = Python pipe_peer.py.
                        For Kotlin, pass the kt-pipe-peer.jar command.
            client_env_key: Env var key for the TCP port on the client side.
                           Python uses PIPE_PEER_SHARED_PORT (share_instance=Yes in config).
                           Kotlin uses PIPE_PEER_SHARED_CLIENT_PORT (LocalClientInterface).
            app_name: RNS app name for destinations
            aspects: RNS aspects for destinations
        """
        project_root = self._find_project_root()
        py_peer_cmd = f"python3 {os.path.join(project_root, 'python-bridge/pipe_peer.py')}"

        # 1. Start Python shared instance (transport, gets the port)
        server_env = os.environ.copy()
        server_env["PIPE_PEER_ACTION"] = "transport"
        server_env["PIPE_PEER_APP_NAME"] = app_name
        server_env["PIPE_PEER_ASPECTS"] = aspects
        server_env["PIPE_PEER_TRANSPORT"] = "true"
        server_env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
        if "PYTHON_RNS_PATH" not in server_env:
            server_env["PYTHON_RNS_PATH"] = self.rns_path

        self.server = _Subprocess(py_peer_cmd, server_env)
        self.server.start()

        ready = self.server.wait_for_message("ready", timeout=20)
        if ready is None:
            raise RuntimeError(
                "Python shared instance did not emit ready. "
                f"Messages: {self.server.get_all_messages()}"
            )

        # Give the TCP server a moment
        time.sleep(0.5)

        # 2. Start hub (connects as TCP client to shared instance)
        hub_env = os.environ.copy()
        hub_env["PIPE_PEER_ACTION"] = hub_action
        hub_env["PIPE_PEER_APP_NAME"] = app_name
        hub_env["PIPE_PEER_ASPECTS"] = aspects
        hub_env["PIPE_PEER_TRANSPORT"] = "false"
        hub_env["PIPE_PEER_SHARED_PORT"] = str(self.tcp_port)
        if "PYTHON_RNS_PATH" not in hub_env:
            hub_env["PYTHON_RNS_PATH"] = self.rns_path

        self.hub = _Subprocess(py_peer_cmd, hub_env)
        self.hub.start()

        hub_ready = self.hub.wait_for_message("ready", timeout=15)
        if hub_ready is None:
            raise RuntimeError(
                "Hub did not emit ready. "
                f"Messages: {self.hub.get_all_messages()}"
            )

        # 3. Start client (Python or Kotlin)
        if client_cmd is None:
            client_cmd = py_peer_cmd

        client_env = os.environ.copy()
        client_env.pop("PIPE_PEER_SHARED_PORT", None)
        client_env["PIPE_PEER_ACTION"] = client_action
        client_env["PIPE_PEER_APP_NAME"] = app_name
        client_env["PIPE_PEER_ASPECTS"] = aspects
        client_env["PIPE_PEER_TRANSPORT"] = "false"
        client_env[client_env_key] = str(self.tcp_port)
        if "PYTHON_RNS_PATH" not in client_env:
            client_env["PYTHON_RNS_PATH"] = self.rns_path

        self.client = _Subprocess(client_cmd, client_env)
        self.client.start()

        client_ready = self.client.wait_for_message("ready", timeout=15)
        if client_ready is None:
            raise RuntimeError(
                "Client did not emit ready. "
                f"Messages: {self.client.get_all_messages()}"
            )

    def stop(self):
        if self.client:
            self.client.stop()
        if self.hub:
            self.hub.stop()
        if self.server:
            self.server.stop()

    @staticmethod
    def _find_project_root():
        d = os.path.dirname(os.path.abspath(__file__))
        while d != "/":
            if os.path.exists(os.path.join(d, "settings.gradle.kts")):
                return d
            d = os.path.dirname(d)
        raise RuntimeError(
            "Could not find project root (settings.gradle.kts not found). "
            "Run tests from within the reticulum-kt project tree."
        )
