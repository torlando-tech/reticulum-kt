"""
Self-link through a shared instance (Carina/Ara scenario).

Tests that a single client connected to a shared instance can create a link
to its OWN destination. The LINKREQUEST bounces through the shared instance
and returns to the same client. Both link endpoints (initiator and responder)
are in the same client process. After establishment, the local loopback in
processOutbound delivers DATA directly between the two endpoints.

Topology:
    Client A (self_link) ──[TCP]──▶  Shared Instance  ◀──[TCP]── (same Client A)
                                     (transport)

This is the exact scenario that breaks in the Kotlin implementation (Carina):
  1. Ara connects to Carina (Kotlin shared instance)
  2. Ara creates a hub destination and a link to itself
  3. Both link endpoints are in Ara's process
  4. Link establishment works (bounces through Carina)
  5. DATA packets should use the local loopback in processOutbound
  6. But in Kotlin, DATA is not delivered after link activation

Exercises:
  Fix 1: addPacketHash guard — shared instance must not store hashes that
         prevent the LINKREQUEST/LINKPROOF from being forwarded back
  Fix 2: processData ordering — if the link_id is in both link_table and
         activeLinks on the shared instance, link_table must be checked first
  Fix 3: Local loopback — processOutbound must find the peer endpoint in
         activeLinks and deliver directly
"""
import time
import pytest
from shared_instance_session import SharedInstanceSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Shared instance session: target as transport, one self-linking client."""
    s = SharedInstanceSession(peer_cmd, rns_path)
    s.start(
        target_transport=True,
        clients=[
            {"action": "self_link"},  # Client 0: creates dest + link to itself
        ],
    )
    yield s
    s.stop()


class TestSelfLinkEstablishment:
    """Self-link establishment through the shared instance."""

    def test_client_announces(self, session):
        """Client announces its destination through the shared instance."""
        msg = session.wait_for_client_message(0, "announced", timeout=15)
        assert msg is not None, "Client should announce its destination"

    def test_self_link_initiated(self, session):
        """Client initiates a link to its own destination."""
        msg = session.wait_for_client_message(0, "self_link_initiated", timeout=20)
        assert msg is not None, \
            "Client should initiate a self-link (LINKREQUEST sent)"

    def test_self_link_becomes_active(self, session):
        """Self-link becomes ACTIVE after bouncing through shared instance."""
        msg = session.wait_for_client_message(0, "self_link_active", timeout=25)
        assert msg is not None, \
            "Self-link should become ACTIVE (both endpoints in same process)"

    def test_responder_receives_link(self, session):
        """Responder side (same process) reports link_established."""
        msg = session.wait_for_client_message(0, "link_established", timeout=25)
        assert msg is not None, \
            "Responder endpoint should report link_established"


class TestSelfLinkData:
    """Data exchange on a self-link (local loopback)."""

    def test_data_sent_on_self_link(self, session):
        """Client sends data on the self-link."""
        msg = session.wait_for_client_message(0, "self_link_data_sent", timeout=15)
        assert msg is not None, "Client should send data on the self-link"
        assert msg["data_hex"] == b"self-link-test-data".hex()

    def test_responder_receives_data(self, session):
        """Responder endpoint receives data sent by initiator (via local loopback)."""
        msg = session.wait_for_client_message(0, "link_data", timeout=15)
        assert msg is not None, \
            "Responder should receive data sent by initiator on the self-link"
        assert msg["data_hex"] == b"self-link-test-data".hex(), \
            f"Data mismatch: expected {b'self-link-test-data'.hex()}, got {msg['data_hex']}"
