"""
Link establishment: Kotlin client → Python shared instance → Python hub.

This reproduces the Ara/Android topology:
  - Python shared instance (Chaquopy rnsd) acts as transport
  - Python hub (rrc-nomadnet) announces and accepts links
  - Kotlin client (reticulum-kt via Ara) connects and establishes a link

Phase 1: All-Python baseline — verifies the test harness itself
Phase 2: Kotlin as client — tests Kotlin LocalClientInterface through Python shared instance

Topology:
    Hub (Python, link_listen) ──[TCP]──▶ Python Shared Instance ◀──[TCP]── Client (Python or Kotlin)
                                         (transport)                        (link_initiate)
"""
import os
import pytest
from python_shared_instance_session import PythonSharedInstanceSession


@pytest.fixture(scope="session")
def kt_client_cmd(peer_cmd):
    """Kotlin PipePeer command (reuses the peer_cmd fixture from conftest)."""
    return peer_cmd


# ─── Phase 1: All-Python Baseline ────────────────────────────────────────────


class TestAllPythonBaseline:
    """All-Python: verifies that link establishment works through a Python shared instance.

    This is the baseline that must pass before testing with Kotlin.
    """

    @pytest.fixture(scope="class")
    def session(self, rns_path):
        s = PythonSharedInstanceSession(rns_path)
        s.start(
            hub_action="link_listen",
            client_action="link_initiate",
            client_cmd=None,  # Python
            client_env_key="PIPE_PEER_SHARED_PORT",
        )
        yield s
        s.stop()

    def test_hub_announces(self, session):
        """Hub announces its destination through the shared instance."""
        msg = session.hub.wait_for_message("announced", timeout=15)
        assert msg is not None, (
            "Hub should announce. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )
        assert len(msg.get("destination_hash", "")) > 0

    def test_client_finds_destination(self, session):
        """Client discovers the hub's destination through the shared instance."""
        msg = session.client.wait_for_message("destination_found", timeout=20)
        assert msg is not None, (
            "Client should discover hub destination. "
            f"Client messages: {session.client.get_all_messages()}"
        )

    def test_link_established_on_client(self, session):
        """Client (initiator) reports link_established."""
        msg = session.client.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Client should establish link to hub. "
            f"Client messages: {session.client.get_all_messages()}"
        )
        assert len(msg.get("link_id", "")) > 0

    def test_link_established_on_hub(self, session):
        """Hub (listener) reports link_established."""
        msg = session.hub.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Hub should report link_established. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )

    def test_data_reaches_hub(self, session):
        """Data sent by client reaches the hub through the shared instance."""
        msg = session.hub.wait_for_message("link_data", timeout=15)
        assert msg is not None, (
            "Hub should receive data from client. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )
        assert msg["data_hex"] == b"hello-from-initiator".hex()


# ─── Phase 2: Kotlin Client ──────────────────────────────────────────────────


class TestKotlinClientViaPythonShared:
    """Kotlin client connecting to Python shared instance → Python hub.

    This is the Ara scenario: Kotlin reticulum-kt connects via
    LocalClientInterface to a Python shared instance (Chaquopy).
    """

    @pytest.fixture(scope="class")
    def session(self, rns_path, kt_client_cmd):
        s = PythonSharedInstanceSession(rns_path)
        s.start(
            hub_action="link_listen",
            client_action="link_initiate",
            client_cmd=kt_client_cmd,
            # Kotlin uses PIPE_PEER_SHARED_CLIENT_PORT to create a
            # LocalClientInterface (connects as client, not server)
            client_env_key="PIPE_PEER_SHARED_CLIENT_PORT",
        )
        yield s
        s.stop()

    def test_hub_announces(self, session):
        """Hub announces its destination through the shared instance."""
        msg = session.hub.wait_for_message("announced", timeout=15)
        assert msg is not None, (
            "Hub should announce. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )
        assert len(msg.get("destination_hash", "")) > 0

    def test_client_finds_destination(self, session):
        """Kotlin client discovers hub's destination through Python shared instance."""
        msg = session.client.wait_for_message("destination_found", timeout=20)
        assert msg is not None, (
            "Kotlin client should discover hub destination through Python shared instance. "
            f"Client messages: {session.client.get_all_messages()}"
        )

    def test_link_established_on_client(self, session):
        """Kotlin client (initiator) reports link_established."""
        msg = session.client.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Kotlin client should establish link to hub through Python shared instance. "
            f"Client messages: {session.client.get_all_messages()}"
        )
        assert len(msg.get("link_id", "")) > 0

    def test_link_established_on_hub(self, session):
        """Hub (listener) reports link_established from Kotlin client."""
        msg = session.hub.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Hub should report link_established from Kotlin client. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )

    def test_data_reaches_hub(self, session):
        """Data sent by Kotlin client reaches the Python hub."""
        msg = session.hub.wait_for_message("link_data", timeout=15)
        assert msg is not None, (
            "Hub should receive data from Kotlin client. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )
        assert msg["data_hex"] == b"hello-from-initiator".hex()
