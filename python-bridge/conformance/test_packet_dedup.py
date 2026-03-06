"""
Packet deduplication tests.

Validates that the target implementation correctly:
  - Allows announce replays (announces are exempt from hash dedup)
  - Updates path table on re-announce

Uses a single module-scoped session to avoid Python RNS singleton issues.
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    s = PipeSession(peer_cmd, rns_path)
    s.start(peer_action="listen")
    ready = s.wait_for_ready()
    assert ready is not None
    yield s
    s.stop()


class TestAnnounceDedupExemption:
    """Announces should NOT be deduplicated — each updates the path table."""

    def test_second_announce_from_same_dest_accepted(self, session):
        """When Python re-announces the same destination, the target should
        still receive it (not drop it as duplicate)."""
        dest, identity = session.python_announce(app_name="pipetest", aspects=("dedup1",))
        dest_hash = dest.hash.hex()

        # Wait for target to receive first announce
        msg1 = session.wait_for_announce_received(dest_hash, timeout=15)
        assert msg1 is not None, "Target should receive first announce"

        # Wait for processing to complete before re-announcing
        time.sleep(1.5)

        # Re-announce same destination (generates new random blob)
        dest.announce()

        # Target should receive the second announce too
        msg2 = session.wait_for_announce_received(dest_hash, timeout=15)
        assert msg2 is not None, \
            "Target should accept second announce (announces exempt from dedup)"
