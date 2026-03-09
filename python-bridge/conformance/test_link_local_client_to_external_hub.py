"""
Link establishment from a local shared instance client to an external hub.

Tests that a LINKREQUEST from a local client (connected via TCP) to an external
destination (connected via pipe) is correctly routed through the Kotlin shared
instance transport node.

Topology:
    External Hub (Python, link_listen) ──[pipe]──▶ Target (Kotlin transport + shared instance) ◀──[TCP]── Client (Python, link_initiate)

This mirrors the Ara → Carina → remote hub topology:
    Ara (link initiator) ──TCP──▶ Carina (shared instance) ──▶ rnsd ──▶ rrc-nomadnet (hub)

Bug being guarded against:
    When a local client sends a LINKREQUEST to a destination beyond the shared
    instance (hops >= 1 from the shared instance), the client's Transport.outbound
    must inject transport headers (HEADER_2) so the shared instance can route it.
    Without this, the LINKREQUEST arrives as HEADER_1 with no transport_id, and
    the shared instance's processInbound cannot forward it to the backbone interface.
    Python Transport.py:993-1011 handles this on the client side; the Kotlin
    implementation was missing this special case.
"""
import time
import pytest
from hybrid_shared_instance_session import HybridSharedInstanceSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Hybrid session: target as transport + shared instance, external hub listens."""
    s = HybridSharedInstanceSession(peer_cmd, rns_path)
    s.start(
        target_transport=True,
        # The local client will initiate a link to the external hub
        shared_clients=[{"action": "link_initiate"}],
    )
    yield s
    s.stop()


@pytest.fixture(scope="module")
def hub_dest(session):
    """Create a destination on the external Python hub and announce it."""
    RNS = session.RNS

    identity = RNS.Identity()
    destination = RNS.Destination(
        identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        "pipetest",
        "routing",
    )

    # Accept incoming links
    established_links = []

    def link_established(link):
        established_links.append(link)

    destination.set_link_established_callback(link_established)
    destination.announce()

    return {
        "identity": identity,
        "destination": destination,
        "dest_hash_hex": destination.hash.hex(),
        "established_links": established_links,
    }


class TestLocalClientToExternalHub:
    """Local shared instance client initiates a link to an external hub."""

    def test_hub_destination_announced(self, session, hub_dest):
        """External hub successfully announces its destination through the pipe."""
        # The hub's announce should propagate through the target to the local client.
        # Wait for the local client to discover the destination.
        msg = session.wait_for_shared_client_message(
            0, "destination_found", timeout=20
        )
        assert msg is not None, (
            "Local client should discover the external hub destination. "
            f"Client messages: {session.shared_client(0).get_all_messages()}"
        )

    def test_link_initiated_by_client(self, session, hub_dest):
        """Local client initiates a link to the external hub."""
        msg = session.wait_for_shared_client_message(
            0, "link_initiated", timeout=10
        )
        assert msg is not None, (
            "Local client should initiate a link to the external hub. "
            f"Client messages: {session.shared_client(0).get_all_messages()}"
        )

    def test_link_established_on_client(self, session, hub_dest):
        """Local client reports link_established to the external hub."""
        msg = session.wait_for_shared_client_message(
            0, "link_established", timeout=25
        )
        assert msg is not None, (
            "Local client should establish link to external hub through shared instance. "
            f"Client messages: {session.shared_client(0).get_all_messages()}"
        )
        assert len(msg.get("link_id", "")) > 0

    def test_link_established_on_hub(self, session, hub_dest):
        """External hub (listener) accepts the link from the local client."""
        deadline = time.time() + 25
        while time.time() < deadline:
            if hub_dest["established_links"]:
                break
            time.sleep(0.2)

        assert len(hub_dest["established_links"]) > 0, (
            "External hub should accept a link from the local client"
        )

    def test_data_reaches_hub(self, session, hub_dest):
        """Data sent by the local client reaches the external hub."""
        # The link_initiate action sends b"hello-from-initiator" after establishment
        deadline = time.time() + 15
        received_data = []

        for link in hub_dest["established_links"]:
            def on_data(message, packet, _received=received_data):
                data = message if isinstance(message, bytes) else bytes(message)
                _received.append(data)
            link.set_packet_callback(on_data)

        while time.time() < deadline:
            if received_data:
                break
            time.sleep(0.2)

        assert len(received_data) > 0, (
            "External hub should receive data from local client"
        )
        assert received_data[0] == b"hello-from-initiator"
