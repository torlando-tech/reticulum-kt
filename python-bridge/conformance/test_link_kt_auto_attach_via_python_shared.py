"""
Link establishment via the auto-attach codepath.

This test covers the same topology as test_link_kt_client_via_python_shared.py
(Kotlin client → Python shared instance → Python hub), but the Kotlin client
attaches via Reticulum.start(connectToSharedInstance=true) plus the
setLocalClientFactory / setInterfaceRegistrar setters — instead of constructing
LocalClientInterface and calling Transport.registerInterface manually.

Why this exists: rns-android.ReticulumService is the only production caller of
that auto-attach codepath today, and the contract that the factory and the
registrar are codependent ("set both or packets silently drop") was previously
only exercised by hand on a physical Android device. A regression in either
the rns-core auto-attach plumbing (Reticulum.tryConnectToSharedInstance) or
the wrapper wiring discipline would ship green through CI. This test exercises
the same wiring discipline as ReticulumService through PipePeer and asserts
packets actually flow end-to-end through the shared instance.

Concretely, this would have failed-fast on the bug that prompted PR #68
(rns-android wired the factory but forgot the registrar — "Connected to
shared instance" logged, every packet silently dropped) by simulating any
future caller making the same mistake.

Topology (identical to test_link_kt_client_via_python_shared Phase 2):
    Hub (Python, link_listen) ──[TCP]──▶ Python Shared Instance ◀──[TCP]── Kotlin Client (auto-attach)
                                         (transport)                       (link_initiate)
"""
import pytest
from python_shared_instance_session import PythonSharedInstanceSession


@pytest.fixture(scope="session")
def kt_client_cmd(peer_cmd):
    """Kotlin PipePeer command (reuses the peer_cmd fixture from conftest)."""
    return peer_cmd


class TestKotlinAutoAttachViaPythonShared:
    """Kotlin client using Reticulum.start(connectToSharedInstance=true)
    against a Python shared instance.

    The client wires LocalClientInterface only via the rns-core factory +
    registrar setters; rns-core's tryConnectToSharedInstance is what
    constructs the interface and (via the registrar) hands it to Transport.
    Failure mode this test exists to catch: registrar not wired → Transport
    never sees the interface → outbound packets are not routed, inbound
    packets from the shared instance hit no onPacketReceived → link
    establishment hangs.
    """

    @pytest.fixture(scope="class")
    def session(self, rns_path, kt_client_cmd):
        s = PythonSharedInstanceSession(rns_path)
        s.start(
            hub_action="link_listen",
            client_action="link_initiate",
            client_cmd=kt_client_cmd,
            client_env_key="PIPE_PEER_SHARED_CLIENT_PORT",
            client_extra_env={"PIPE_PEER_AUTO_ATTACH": "true"},
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
        """Auto-attached Kotlin client receives hub announce via Transport.inbound.

        This is the canary for the registrar-not-wired bug: if the registrar
        never called Transport.registerInterface, the LocalClientInterface's
        onPacketReceived would not be wired to Transport.inbound, and the
        announce arriving from the shared instance would be silently dropped.
        """
        msg = session.client.wait_for_message("destination_found", timeout=20)
        assert msg is not None, (
            "Auto-attached Kotlin client should discover hub destination through "
            "Python shared instance. If this fails with no destination_found, "
            "check that Reticulum.tryConnectToSharedInstance is invoking the "
            "interface registrar (rns-core) and that PipePeer's auto-attach "
            "branch is setting both setLocalClientFactory AND "
            "setInterfaceRegistrar before Reticulum.start(). "
            f"Client messages: {session.client.get_all_messages()}"
        )

    def test_link_established_on_client(self, session):
        """Auto-attached Kotlin client (initiator) reports link_established."""
        msg = session.client.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Auto-attached Kotlin client should establish link to hub. "
            f"Client messages: {session.client.get_all_messages()}"
        )
        assert len(msg.get("link_id", "")) > 0

    def test_link_established_on_hub(self, session):
        """Hub reports link_established from the auto-attached Kotlin client.

        Mirrors the client-side assertion to confirm the link is bidirectional
        — outbound packets from the auto-attached client actually reached the
        shared instance and were routed to the hub.
        """
        msg = session.hub.wait_for_message("link_established", timeout=25)
        assert msg is not None, (
            "Hub should report link_established from auto-attached Kotlin client. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )

    def test_data_reaches_hub(self, session):
        """Data sent over the link by the auto-attached client reaches the hub."""
        msg = session.hub.wait_for_message("link_data", timeout=15)
        assert msg is not None, (
            "Hub should receive data from auto-attached Kotlin client. "
            f"Hub messages: {session.hub.get_all_messages()}"
        )
        assert msg["data_hex"] == b"hello-from-initiator".hex()
