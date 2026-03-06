"""
Link establishment from an external peer to a destination behind a shared instance.

Tests that a link from an external peer (connected via pipe) to a destination
registered by a shared instance client (connected via TCP) is correctly routed
through the target transport node.

Topology:
    External Peer (Python) ──[pipe]──▶ Target (Kotlin transport + shared instance) ◀──[TCP]── Client (Python, link_listen)
    (link initiator)                   (forwarding via link_table)                             (destination + link acceptor)

This mirrors the real-world topology:
    rrc-web ──▶ rnsd ──▶ TCP ──▶ Carina (shared instance) ◀──TCP── Ara (app with destination)

The target's Transport must:
  1. Receive the client's announce via the shared instance TCP interface
  2. Retransmit the announce to the external peer via the pipe interface
  3. Forward the LINKREQUEST from external peer to the shared instance client
  4. Forward the LRPROOF from the client back to the external peer
  5. Forward subsequent DATA packets in both directions
  6. Forward TEARDOWN when the link is closed

Bug being guarded against:
  When a shared instance client generates an LRPROOF (its destination accepted
  a link), the proof must travel: client → shared instance server → external peer.
  If the transport node's processProof fails to find the link_table entry (wrong
  hop count, wrong interface check, or missing return), the proof either gets
  dropped or bounces infinitely between client and server, preventing link
  establishment.
"""
import time
import pytest
from hybrid_shared_instance_session import HybridSharedInstanceSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Hybrid session: target as transport + shared instance, client accepts links."""
    s = HybridSharedInstanceSession(peer_cmd, rns_path)
    s.start(
        target_transport=True,
        shared_clients=[{"action": "link_listen"}],
    )
    yield s
    s.stop()


@pytest.fixture(scope="module")
def client_dest(session):
    """Wait for the shared instance client to announce and external peer to learn the path."""
    announced = session.wait_for_shared_client_announced(0, timeout=15)
    assert announced is not None, "Shared instance client should announce its destination"
    dest_hash = announced["destination_hash"]

    # Wait for local Python (external peer) to learn the path through the target
    deadline = time.time() + 15
    while time.time() < deadline:
        if session.python_has_path(dest_hash):
            break
        time.sleep(0.2)

    assert session.python_has_path(dest_hash), \
        f"External peer should learn path to shared instance client destination {dest_hash}"

    return announced


@pytest.fixture(scope="module")
def active_link(session, client_dest):
    """Create a link from external peer to the shared instance client through the target."""
    dest_hash = client_dest["destination_hash"]
    RNS = session.RNS

    link = session.python_create_link(dest_hash)
    session.setup_python_link_callbacks(link)

    # Wait for link to become ACTIVE
    deadline = time.time() + 20
    while time.time() < deadline:
        if link.status == RNS.Link.ACTIVE:
            break
        time.sleep(0.1)

    assert link.status == RNS.Link.ACTIVE, \
        f"Link should become ACTIVE, got status {link.status}"

    yield link

    if link.status == RNS.Link.ACTIVE:
        link.teardown()
        time.sleep(0.5)


class TestLinkThroughSharedInstanceEstablishment:
    """Link establishment from external peer to shared instance client."""

    def test_link_becomes_active(self, active_link, session):
        """Link from external peer to shared instance client reaches ACTIVE."""
        assert active_link.status == session.RNS.Link.ACTIVE

    def test_client_reports_link_established(self, session, active_link):
        """Shared instance client reports link_established."""
        msg = session.wait_for_shared_client_message(0, "link_established", timeout=15)
        assert msg is not None, "Shared instance client should report link_established"
        assert "link_id" in msg
        assert len(msg["link_id"]) > 0

    def test_hops_through_shared_instance(self, session, client_dest):
        """Path to shared instance client has hops >= 1 (through transport)."""
        dest_hash = client_dest["destination_hash"]
        hops = session.python_hops_to(dest_hash)
        assert hops is not None and hops >= 1, \
            f"Hops to shared instance client should be >= 1, got {hops}"


class TestDataToSharedInstanceClient:
    """Data sent from external peer reaches the shared instance client."""

    def test_send_data_to_client(self, session, active_link):
        """Data sent over the link reaches the shared instance client."""
        RNS = session.RNS
        test_data = b"Hello through shared instance!"

        packet = RNS.Packet(active_link, test_data)
        packet.send()

        msg = session.wait_for_shared_client_message(0, "link_data", timeout=15)
        assert msg is not None, "Shared instance client should receive data over the link"
        assert msg["data_hex"] == test_data.hex(), \
            f"Data mismatch: expected {test_data.hex()}, got {msg['data_hex']}"


class TestLinkThroughSharedInstanceTeardown:
    """Link teardown propagates through the shared instance."""

    def test_teardown_reaches_client(self, session, active_link):
        """Teardown from external peer reaches the shared instance client."""
        active_link.teardown()

        msg = session.wait_for_shared_client_message(0, "link_closed", timeout=15)
        assert msg is not None, "Shared instance client should report link_closed after teardown"
