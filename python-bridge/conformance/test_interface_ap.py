"""
Access Point (AP) mode announce filtering tests.

Validates that the target implementation:
  - Does NOT broadcast announces on AP-mode interfaces (outbound blocked)
  - DOES receive announces on AP-mode interfaces (inbound allowed)

AP mode blocks outbound announces only, matching Python Transport.py:1040-1084.
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Target runs with AP-mode pipe interface in announce mode."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(
        peer_action="announce",
        peer_mode="ap",
    )
    ready = s.wait_for_ready()
    assert ready is not None
    yield s
    s.stop()


class TestAccessPointOutbound:
    """AP mode should block outbound announce broadcasts."""

    def test_python_does_not_receive_announce_from_ap_target(self, session):
        """When the target's pipe interface is AP-mode, the target's announce
        should NOT be sent out on that interface."""
        announced = session.wait_for_announced(timeout=15)

        if announced is None:
            # Target may not even emit 'announced' if it knows AP blocks it.
            pytest.skip("Target did not announce (may have detected AP mode)")

        dest_hash = announced["destination_hash"]

        # Python should NOT learn this path — AP blocks outbound announces
        time.sleep(5)
        has_path = session.python_has_path(dest_hash)

        assert not has_path, \
            f"Python should NOT learn path via AP-mode interface, but has_path={has_path}"


class TestAccessPointInbound:
    """AP mode should allow inbound announces (AP blocks outbound, not inbound)."""

    def test_target_receives_announce_on_ap_interface(self, session):
        """AP mode blocks OUTBOUND announces, not inbound. Target should
        still receive and process Python's announce."""
        dest, _ = session.python_announce(
            app_name="pipetest", aspects=("ap_inbound",)
        )
        dest_hash = dest.hash.hex()

        msg = session.wait_for_announce_received(dest_hash, timeout=15)
        assert msg is not None, \
            "Target should receive announce on AP interface (AP blocks outbound, not inbound)"
