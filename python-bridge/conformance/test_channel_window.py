"""
Channel conformance tests focused on proof-driven send window recovery.

These tests validate a subtle but important requirement:
- the peer must not only deliver initial channel packets
- it must also accept the returned proofs and reopen the channel send window

A broken implementation can still get the first one or two channel packets to the
remote side while failing to validate their proofs locally. In that case, the
send window never reopens and the next channel send stalls or the link later
tears down.
"""
import threading
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Session where the target accepts a link and serves channel messages."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(peer_action="channel_serve")
    ready = s.wait_for_ready()
    assert ready is not None
    yield s
    s.stop()


@pytest.fixture(scope="module")
def target_dest(session):
    """Wait for target announce and confirm Python learned the path."""
    announced = session.wait_for_announced(timeout=15)
    assert announced is not None, "Target should announce its destination"

    dest_hash = announced["destination_hash"]
    deadline = time.time() + 15
    while time.time() < deadline:
        if session.python_has_path(dest_hash):
            break
        time.sleep(0.2)

    assert session.python_has_path(dest_hash), \
        f"Python should learn path to target destination {dest_hash}"

    return announced


class BridgeMessageFactory:
    @staticmethod
    def make(RNS):
        class BridgeMessage(RNS.Channel.MessageBase):
            MSGTYPE = 0x0101

            def __init__(self, data=b""):
                self.data = data

            def pack(self):
                return self.data

            def unpack(self, raw):
                self.data = raw

        return BridgeMessage


class TestChannelSendWindow:
    """Target should reopen the channel send window after receiving proofs."""

    @pytest.fixture(scope="class")
    def active_link(self, session, target_dest):
        dest_hash = target_dest["destination_hash"]
        RNS = session.RNS
        dest_bytes = bytes.fromhex(dest_hash)
        identity = RNS.Identity.recall(dest_bytes)
        assert identity is not None, "Should have identity from announce"

        dest = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "pipetest",
            "routing",
        )

        link = RNS.Link(dest)

        deadline = time.time() + 15
        while time.time() < deadline:
            if link.status == RNS.Link.ACTIVE:
                break
            time.sleep(0.1)

        assert link.status == RNS.Link.ACTIVE, \
            f"Link should become ACTIVE, got status {link.status}"

        msg = session.wait_for_link_established(timeout=15)
        assert msg is not None, "Target should report link_established"

        yield link

        if link.status == RNS.Link.ACTIVE:
            link.teardown()
            time.sleep(0.5)

    def test_target_reopens_channel_window_after_proofs(self, session, active_link):
        RNS = session.RNS
        BridgeMessage = BridgeMessageFactory.make(RNS)
        channel = active_link.get_channel()
        channel.register_message_type(BridgeMessage)

        received = []
        cond = threading.Condition()

        def on_channel_message(message):
            if isinstance(message, BridgeMessage):
                with cond:
                    received.append(bytes(message.data))
                    cond.notify_all()
                return True
            return False

        channel.add_message_handler(on_channel_message)

        expected = [b"channel-one", b"channel-two", b"channel-three"]
        deadline = time.time() + 15
        with cond:
            while time.time() < deadline and len(received) < len(expected):
                cond.wait(timeout=min(deadline - time.time(), 0.5))

        assert received[:3] == expected, \
            f"Expected proof-gated channel sequence {expected}, got {received}"
        assert active_link.status == RNS.Link.ACTIVE, "Link should remain active after channel proof exchange"
        assert session.wait_for_error(timeout=1.5) is None, \
            "Target should not report a stalled channel send"
        assert session.wait_for_link_closed(timeout=1.5) is None, \
            "Target should not tear the link down during the channel sequence"
