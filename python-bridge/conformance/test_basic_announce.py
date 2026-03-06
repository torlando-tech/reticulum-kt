"""
Basic announce propagation tests — target announces, Python verifies.

Validates that the target implementation correctly:
  - Sends announces that Python can receive and store paths for
  - Agrees on hop counts for direct pipe connections
  - Correctly stores identity from announces
"""
import time
import pytest
from pipe_session import PipeSession


@pytest.fixture(scope="module")
def session(peer_cmd, rns_path):
    """Session where the target announces and Python listens."""
    s = PipeSession(peer_cmd, rns_path)
    s.start(peer_action="announce", peer_app_name="pipetest", peer_aspects="routing")
    ready = s.wait_for_ready()
    assert ready is not None, "Target should emit 'ready'"
    yield s
    s.stop()


@pytest.fixture(scope="module")
def target_announce(session):
    """Wait for the target to announce and propagate to Python."""
    announced = session.wait_for_announced()
    assert announced is not None, "Target should announce a destination"

    dest_hash = announced["destination_hash"]

    # Wait for Python to learn the path
    deadline = time.time() + 15
    while time.time() < deadline:
        if session.python_has_path(dest_hash):
            break
        time.sleep(0.2)

    return {
        "dest_hash": dest_hash,
        "identity_hash": announced.get("identity_hash", ""),
        "identity_public_key": announced.get("identity_public_key", ""),
    }


class TestTargetToPython:
    """Target announces, Python verifies."""

    def test_python_learns_path_from_target_announce(self, session, target_announce):
        dest_hash = target_announce["dest_hash"]
        assert session.python_has_path(dest_hash), \
            f"Python should learn path to target destination {dest_hash}"

    def test_hop_count_is_one_for_direct_pipe(self, session, target_announce):
        dest_hash = target_announce["dest_hash"]
        hops = session.python_hops_to(dest_hash)
        assert hops == 1, f"Direct pipe should be 1 hop, got {hops}"

    def test_path_table_entry_has_correct_structure(self, session, target_announce):
        dest_hash = target_announce["dest_hash"]
        entry = session.python_path_table_entry(dest_hash)
        assert entry is not None, "Path table should contain entry"
        # entry format: [timestamp, next_hop, hops, expires, random_blobs, receiving_interface, packet_hash]
        assert entry[2] == 1, f"Hops should be 1, got {entry[2]}"
        assert entry[0] > 0, "Timestamp should be set"
        assert len(entry[4]) > 0, "Random blobs should be present"

    def test_identity_recalled_from_announce(self, session, target_announce):
        dest_hash = target_announce["dest_hash"]
        recalled = session.python_recall_identity(dest_hash)
        assert recalled is not None, "Should recall identity from target announce"

        expected_pub = target_announce.get("identity_public_key", "")
        if expected_pub:
            actual_pub = recalled.get_public_key().hex()
            assert actual_pub == expected_pub, \
                f"Recalled public key should match: expected {expected_pub[:24]}..., got {actual_pub[:24]}..."


class TestPythonToTarget:
    """Python announces to target using the same session.

    The target is in announce mode, but can also receive announces from Python.
    This uses a single python_announce call to test both receipt and path table.
    """

    @pytest.fixture(scope="class")
    def python_announce(self, session):
        """Single Python announce shared by all tests in this class."""
        dest, identity = session.python_announce(app_name="pipetest", aspects=("pyannounce",))
        dest_hash = dest.hash.hex()

        msg = session.wait_for_announce_received(dest_hash, timeout=15)
        assert msg is not None, f"Target should receive Python's announce for {dest_hash}"

        return {"dest_hash": dest_hash, "announce_msg": msg, "dest": dest, "identity": identity}

    def test_target_receives_python_announce(self, python_announce):
        assert python_announce["announce_msg"]["hops"] == 1, \
            f"Target should see 1 hop, got {python_announce['announce_msg'].get('hops')}"

    def test_target_path_table_contains_python_dest(self, session, python_announce):
        dest_hash = python_announce["dest_hash"]

        msg = session.wait_for_path_table_entry(dest_hash, timeout=15)
        assert msg is not None, "Target's path table should contain Python destination"

        entries = msg["entries"]
        entry = next(e for e in entries if e["destination_hash"] == dest_hash)
        assert entry["hops"] == 1, f"Target should see 1 hop, got {entry['hops']}"

    def test_hop_counts_match(self, session, target_announce, python_announce):
        """Both sides announce — verify hop count agreement."""
        # Python's view of target's announce
        py_hops = session.python_hops_to(target_announce["dest_hash"])
        assert py_hops is not None, "Python should have path to target"

        # Target's view of Python's announce (from the announce_received message)
        target_hops = python_announce["announce_msg"]["hops"]

        assert py_hops == target_hops, \
            f"Hop counts should match: Python sees {py_hops}, target sees {target_hops}"
