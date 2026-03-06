"""
Shared instance link tests — transport node with its own destination.

Validates the exact scenario that broke Carina/RRC: the target runs as
a transport node AND has its own link-accepting destination (link_serve).
A Python client connects, and the server sends data back over the link.

This tests:
  1. Link from client to transport node's own destination becomes ACTIVE
  2. Transport node sends welcome data to client (server→client direction)
  3. Client sends data and receives an echo (client→server→client round-trip)
  4. Link teardown propagates correctly

The target runs in 'link_serve' mode with transport enabled. It:
  - Announces a destination
  - Accepts incoming links
  - Sends a "welcome" message after link establishment
  - Echoes any received data back to the client
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Session where the target is a transport node with its own destination."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(
        peer_action="link_serve",
        peer_transport=True,
    )
    ready = s.wait_for_ready()
    assert ready is not None, "Target should emit ready"
    yield s
    s.stop()


@pytest.fixture(scope="module")
def target_dest(session):
    """Wait for the target to announce its link-serving destination."""
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


@pytest.fixture(scope="module")
def active_link(session, target_dest):
    """Establish a link from Python client to the target's destination."""
    dest_hash = target_dest["destination_hash"]
    RNS = session.RNS

    link = session.python_create_link(dest_hash)
    session.setup_python_link_callbacks(link)

    # Wait for link to become ACTIVE
    deadline = time.time() + 15
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


class TestSharedInstanceLinkEstablishment:
    """Link to a transport node's own destination establishes correctly."""

    def test_link_becomes_active(self, active_link, session):
        """Link from client to transport node's destination reaches ACTIVE."""
        assert active_link.status == session.RNS.Link.ACTIVE

    def test_target_reports_link_established(self, session, active_link):
        """Target reports link_established on its stderr control channel."""
        msg = session.wait_for_link_established(timeout=15)
        assert msg is not None, "Target should report link_established"
        assert "link_id" in msg
        assert len(msg["link_id"]) > 0


class TestSharedInstanceServerToClient:
    """Data sent BY the transport node over the link reaches the client.

    This is the KEY scenario that broke Carina: the hub (in-process on
    the transport node) sends data over a link to a connected client.
    """

    def test_welcome_received_by_client(self, session, active_link):
        """The target's welcome message reaches the Python client."""
        # The target sends "welcome" 500ms after link establishment.
        # We may need to wait a bit for it to arrive.
        data = session.wait_for_python_link_data(timeout=10)
        assert data is not None, \
            "Client should receive welcome data from transport node"
        assert data == b"welcome", \
            f"Expected b'welcome', got {data!r}"

    def test_target_reports_welcome_sent(self, session, active_link):
        """Target reports it sent the welcome message."""
        msg = session.wait_for_link_sent(timeout=10)
        assert msg is not None, "Target should report link_sent for welcome"
        assert msg["data_hex"] == b"welcome".hex()


class TestSharedInstanceClientToServer:
    """Data sent by the client reaches the transport node and is echoed back."""

    def test_send_data_and_receive_echo(self, session, active_link):
        """Client sends data, target receives it and echoes it back."""
        RNS = session.RNS
        test_data = b"Hello from client!"

        # Send data from client to target
        packet = RNS.Packet(active_link, test_data)
        packet.send()

        # Target should report receiving the data
        msg = session.wait_for_link_data(timeout=15)
        assert msg is not None, "Target should receive data over the link"
        assert msg["data_hex"] == test_data.hex(), \
            f"Target received wrong data: expected {test_data.hex()}, got {msg['data_hex']}"

        # Target echoes data back — client should receive it
        echo = session.wait_for_python_link_data(timeout=15)
        assert echo is not None, \
            "Client should receive echoed data from transport node"
        assert echo == test_data, \
            f"Echo should match: expected {test_data!r}, got {echo!r}"


class TestSharedInstanceLinkTeardown:
    """Link teardown from client propagates to the transport node."""

    def test_teardown_reaches_target(self, session, active_link):
        """Teardown sent by client is reported by the target."""
        active_link.teardown()

        msg = session.wait_for_link_closed(timeout=15)
        assert msg is not None, "Target should report link_closed after teardown"
