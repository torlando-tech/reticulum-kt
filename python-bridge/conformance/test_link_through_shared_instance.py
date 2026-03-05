"""
Link establishment between two clients through a shared instance.

Tests that two clients connected to the same shared instance (transport node)
can establish a link and exchange data through the shared instance's Transport.

Topology:
    Client A (link_listen) ──[TCP]──▶  Shared Instance  ◀──[TCP]── Client B (link_initiate)
                                       (transport)

This exercises:
  Fix 1: addPacketHash guard — the shared instance must not add packet hashes
         that prevent forwarding to/from local clients
  Fix 2: processData ordering — link_table forwarding must happen correctly
         for DATA packets routed between two local clients

Both fixes from the plan at indexed-bubbling-sparrow.md are needed for these
tests to pass on Kotlin. They pass on the Python reference implementation.
"""
import time
import pytest
from shared_instance_session import SharedInstanceSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Shared instance session: target as transport, two Python clients."""
    s = SharedInstanceSession(peer_cmd, rns_path)
    s.start(
        target_transport=True,
        clients=[
            {"action": "link_listen"},    # Client 0: accepts links
            {"action": "link_initiate"},  # Client 1: creates link
        ],
    )
    yield s
    s.stop()


class TestLinkThroughSharedInstance:
    """Link between two clients through the shared instance transport."""

    def test_client_a_announces(self, session):
        """Client A (link_listen) announces its destination."""
        msg = session.wait_for_client_message(0, "announced", timeout=15)
        assert msg is not None, "Client A should announce its destination"
        assert "destination_hash" in msg
        assert len(msg["destination_hash"]) > 0

    def test_client_b_finds_destination(self, session):
        """Client B discovers Client A's destination through the shared instance."""
        msg = session.wait_for_client_message(1, "destination_found", timeout=20)
        assert msg is not None, \
            "Client B should discover Client A's destination through the shared instance"

    def test_link_established_on_initiator(self, session):
        """Client B (initiator) reports link established."""
        msg = session.wait_for_client_message(1, "link_established", timeout=25)
        assert msg is not None, \
            "Client B should establish a link to Client A through the shared instance"
        assert "link_id" in msg
        assert len(msg["link_id"]) > 0

    def test_link_established_on_listener(self, session):
        """Client A (listener) reports link established."""
        msg = session.wait_for_client_message(0, "link_established", timeout=25)
        assert msg is not None, \
            "Client A should report link_established from Client B"

    def test_data_from_initiator_reaches_listener(self, session):
        """Data sent by Client B reaches Client A through the shared instance."""
        msg = session.wait_for_client_message(0, "link_data", timeout=15)
        assert msg is not None, \
            "Client A should receive data from Client B through the shared instance"
        assert msg["data_hex"] == b"hello-from-initiator".hex(), \
            f"Data mismatch: expected {b'hello-from-initiator'.hex()}, got {msg['data_hex']}"
