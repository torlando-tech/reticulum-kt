"""
Announce retransmission tests.

Validates that the target implementation correctly:
  - Retransmits received announces on other interfaces
  - Does NOT retransmit back to the receiving interface
  - Increments hop count on retransmission

This requires the target to be in transport mode with at least two interfaces.
Since our pipe peer only has one interface, retransmission cannot be directly
tested via the pipe. Instead, we verify the target correctly processes and
stores announces that could be retransmitted.
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Session where the target listens and Python announces."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(peer_action="listen")
    ready = s.wait_for_ready()
    assert ready is not None
    yield s
    s.stop()


class TestAnnounceStorage:
    """Verify the target correctly stores announce data."""

    @pytest.fixture(scope="class")
    def announce_data(self, session):
        dest, identity = session.python_announce(
            app_name="pipetest", aspects=("storage",)
        )
        dest_hash = dest.hash.hex()
        msg = session.wait_for_announce_received(dest_hash, timeout=15)
        assert msg is not None
        return {
            "dest_hash": dest_hash,
            "dest": dest,
            "identity": identity,
            "msg": msg,
        }

    def test_announce_stored_with_correct_hop_count(self, announce_data):
        """Target should store announce with hop count = 1 (direct pipe)."""
        assert announce_data["msg"]["hops"] == 1

    def test_announce_stored_in_path_table(self, session, announce_data):
        """Target's path table should contain the announced destination."""
        dest_hash = announce_data["dest_hash"]
        msg = session.wait_for_path_table_entry(dest_hash, timeout=15)
        assert msg is not None, "Path table should contain announced destination"

    def test_announce_identity_stored(self, announce_data):
        """The announce_received message should include the correct identity hash."""
        expected_hash = announce_data["identity"].hash.hex()
        assert announce_data["msg"]["identity_hash"] == expected_hash
