"""
Link establishment and data transfer tests.

Validates that the target implementation correctly:
  - Accepts incoming link requests
  - Completes the link handshake (LINKREQUEST → LINKPROOF)
  - Receives data over an established link
  - Reports link closure

The test runner (Python) initiates the link to the target peer.
The target runs in link_listen mode, announcing a destination and
accepting incoming links.
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Session where the target listens for links."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(peer_action="link_listen")
    ready = s.wait_for_ready()
    assert ready is not None
    yield s
    s.stop()


@pytest.fixture(scope="module")
def target_dest(session):
    """Wait for target to announce its link-accepting destination."""
    announced = session.wait_for_announced(timeout=15)
    assert announced is not None, "Target should announce its destination"

    dest_hash = announced["destination_hash"]

    # Wait for Python to learn the path
    deadline = time.time() + 15
    while time.time() < deadline:
        if session.python_has_path(dest_hash):
            break
        time.sleep(0.2)

    assert session.python_has_path(dest_hash), \
        f"Python should learn path to target destination {dest_hash}"

    return announced


class TestLinkEstablishment:
    """Test that the target accepts incoming links."""

    @pytest.fixture(scope="class")
    def link_and_msg(self, session, target_dest):
        """Establish a link and return (link, link_established_msg)."""
        dest_hash = target_dest["destination_hash"]

        # Recall identity from the announce
        RNS = session.RNS
        dest_bytes = bytes.fromhex(dest_hash)
        identity = RNS.Identity.recall(dest_bytes)
        assert identity is not None, "Should have identity from announce"

        # Build outgoing destination matching the target's app_name/aspects
        dest = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "pipetest",
            "routing",
        )

        # Initiate link
        link = RNS.Link(dest)

        # Wait for link to be established (Python side)
        deadline = time.time() + 15
        while time.time() < deadline:
            if link.status == RNS.Link.ACTIVE:
                break
            time.sleep(0.1)

        assert link.status == RNS.Link.ACTIVE, \
            f"Link should become ACTIVE, got status {link.status}"

        # Wait for target to report link_established
        msg = session.wait_for_link_established(timeout=15)
        assert msg is not None, "Target should report link_established"

        yield link, msg

        # Teardown
        if link.status == RNS.Link.ACTIVE:
            link.teardown()
            time.sleep(0.5)

    def test_link_becomes_active(self, link_and_msg):
        """Link should reach ACTIVE status."""
        link, _ = link_and_msg
        # Already asserted in fixture, but explicit test for clarity
        assert link.status == link.ACTIVE

    def test_target_reports_link_established(self, link_and_msg):
        """Target should emit a link_established message."""
        _, msg = link_and_msg
        assert "link_id" in msg
        assert len(msg["link_id"]) > 0

    def test_send_data_over_link(self, session, link_and_msg):
        """Data sent over the link should be received by the target."""
        link, _ = link_and_msg
        RNS = session.RNS

        test_data = b"Hello from conformance test!"
        packet = RNS.Packet(link, test_data)
        packet.send()

        msg = session.wait_for_link_data(timeout=15)
        assert msg is not None, "Target should receive data over the link"
        assert msg["data_hex"] == test_data.hex(), \
            f"Data should match: expected {test_data.hex()}, got {msg['data_hex']}"

    def test_link_teardown_reported(self, session, link_and_msg):
        """When the link is torn down, target should report link_closed."""
        link, _ = link_and_msg
        link.teardown()

        msg = session.wait_for_link_closed(timeout=15)
        assert msg is not None, "Target should report link_closed after teardown"
