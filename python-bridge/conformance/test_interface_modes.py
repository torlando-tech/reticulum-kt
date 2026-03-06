"""
Interface mode announce filtering tests.

Validates that the target implementation respects interface modes when
broadcasting announces, matching Python Transport.py:1040-1084.

Key rules:
  - ACCESS_POINT: never broadcast announces
  - FULL/POINT_TO_POINT/GATEWAY: no mode-based restrictions

NOTE: Each test file can only use ONE Python RNS session (singleton limitation).
This file tests FULL mode as a control. AP mode tests are in test_interface_ap.py.
"""
import time
import pytest
from pipe_session import PipeSession


class TestFullMode:
    """FULL mode should allow all announce broadcasts (control test)."""

    @pytest.fixture(scope="class")
    def full_session(self, peer_cmd, rns_path):
        s = PipeSession(peer_cmd, rns_path)
        s.start(
            peer_action="announce",
            peer_mode="full",
        )
        ready = s.wait_for_ready()
        assert ready is not None
        yield s
        s.stop()

    def test_python_receives_announce_from_full_target(self, full_session):
        """FULL mode should allow announces through."""
        announced = full_session.wait_for_announced(timeout=15)
        assert announced is not None
        dest_hash = announced["destination_hash"]

        deadline = time.time() + 15
        while time.time() < deadline:
            if full_session.python_has_path(dest_hash):
                break
            time.sleep(0.2)

        assert full_session.python_has_path(dest_hash), \
            "Python should learn path via FULL-mode interface"
