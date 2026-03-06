"""
Link establishment and data exchange through a transport node.

Tests that a link from one peer to another, routed through the target
transport node, establishes correctly and can carry data bidirectionally.

Topology:
    Local Python RNS  ──[pipe 0]──▶  Target Transport  ◀──[pipe 1]──  Remote Peer
    (link initiator)                  (forwarding)                     (link listener)

The target's Transport must:
  1. Forward the LINKREQUEST from local to remote (via link_table creation)
  2. Forward the LINKPROOF from remote back to local
  3. Forward subsequent DATA packets in both directions using link_table
  4. Forward TEARDOWN when the link is closed

This is the core multi-hop link routing test that validates Transport's
link_table forwarding logic.

The Kotlin implementation forwards LINKREQUEST packets between interfaces
using link_table forwarding, matching the Python reference Transport.
"""
import time
import pytest
from multi_pipe_session import MultiPipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Multi-pipe session: target as transport, remote peer accepts links."""
    s = MultiPipeSession(peer_cmd, rns_path, num_remote_peers=1)
    s.start(
        target_action="transport",
        target_transport=True,
        remote_actions=["link_listen"],
    )
    assert s.wait_for_target_ready() is not None, "Target should emit ready"
    assert s.wait_for_remote_ready(0) is not None, "Remote peer should emit ready"
    yield s
    s.stop()


@pytest.fixture(scope="module")
def remote_dest(session):
    """Wait for remote peer to announce and local Python to learn the path."""
    announced = session.wait_for_remote_announced(0, timeout=15)
    assert announced is not None, "Remote peer should announce its destination"
    dest_hash = announced["destination_hash"]

    # Wait for local Python to learn the path through the target
    deadline = time.time() + 15
    while time.time() < deadline:
        if session.python_has_path(dest_hash):
            break
        time.sleep(0.2)

    assert session.python_has_path(dest_hash), \
        f"Local Python should learn path to remote destination {dest_hash}"

    return announced


@pytest.fixture(scope="module")
def active_link(session, remote_dest):
    """Create a link from local Python to the remote peer through the target."""
    dest_hash = remote_dest["destination_hash"]
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


class TestLinkThroughTransportEstablishment:
    """Link establishment through the transport node."""

    def test_link_becomes_active(self, active_link, session):
        """Link from local to remote through target reaches ACTIVE."""
        assert active_link.status == session.RNS.Link.ACTIVE

    def test_remote_reports_link_established(self, session, active_link):
        """Remote peer reports link_established on its control channel."""
        msg = session.wait_for_remote_message(0, "link_established", timeout=15)
        assert msg is not None, "Remote peer should report link_established"
        assert "link_id" in msg
        assert len(msg["link_id"]) > 0

    def test_hops_through_transport(self, session, remote_dest):
        """Path to remote destination has hops > 0 (through transport)."""
        dest_hash = remote_dest["destination_hash"]
        hops = session.python_hops_to(dest_hash)
        assert hops is not None and hops >= 1, \
            f"Hops to remote should be >= 1, got {hops}"


class TestDataLocalToRemote:
    """Data sent from local Python reaches the remote peer through transport."""

    def test_send_data_to_remote(self, session, active_link):
        """Data sent over the link reaches the remote peer."""
        RNS = session.RNS
        test_data = b"Hello through transport!"

        packet = RNS.Packet(active_link, test_data)
        packet.send()

        # Remote peer should report receiving the data
        msg = session.wait_for_remote_message(0, "link_data", timeout=15)
        assert msg is not None, "Remote peer should receive data over the link"
        assert msg["data_hex"] == test_data.hex(), \
            f"Data mismatch: expected {test_data.hex()}, got {msg['data_hex']}"


class TestLinkThroughTransportTeardown:
    """Link teardown propagates through the transport node."""

    def test_teardown_reaches_remote(self, session, active_link):
        """Teardown from local reaches the remote peer through transport."""
        active_link.teardown()

        msg = session.wait_for_remote_message(0, "link_closed", timeout=15)
        assert msg is not None, "Remote peer should report link_closed after teardown"
