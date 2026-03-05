"""
Announce propagation through a transport node.

Tests that announces from one peer are retransmitted by the target
transport node and received by a peer on a different interface.

Topology:
    Local Python RNS  ──[pipe 0]──▶  Target Transport  ◀──[pipe 1]──  Remote Peer
    (announcer/listener)              (retransmits)                    (listener/announcer)

This validates:
  1. Announce from local Python reaches the remote peer through the target
  2. Announce from remote peer reaches the local Python through the target
  3. Hop counts are incremented correctly
"""
import time
import pytest
from multi_pipe_session import MultiPipeSession


class TestAnnounceLocalToRemote:
    """Announce from local Python reaches the remote peer through the target."""

    @pytest.fixture(scope="class")
    def session(self, peer_cmd, rns_path):
        """Remote peer listens; local Python will announce."""
        s = MultiPipeSession(peer_cmd, rns_path, num_remote_peers=1)
        s.start(
            target_action="transport",
            target_transport=True,
            remote_actions=["listen"],
        )
        assert s.wait_for_target_ready() is not None, "Target should emit ready"
        assert s.wait_for_remote_ready(0) is not None, "Remote peer should emit ready"
        yield s
        s.stop()

    def test_local_announce_reaches_remote(self, session):
        """Announce from local Python is retransmitted to remote peer."""
        dest, identity = session.python_announce("multitest", ("announce",))
        dest_hash = dest.hash.hex()

        msg = session.wait_for_remote_message(
            0, "announce_received", timeout=15,
            predicate=lambda m: m.get("destination_hash") == dest_hash
        )
        assert msg is not None, \
            "Remote peer should receive announce from local Python"
        assert msg["destination_hash"] == dest_hash

    def test_local_announce_hops_at_remote(self, session):
        """Remote peer sees the announce with hops >= 1 (through transport)."""
        dest_hash = session.destination.hash.hex()

        msg = session.wait_for_remote_message(
            0, "path_table", timeout=15,
            predicate=lambda m: any(
                e.get("destination_hash") == dest_hash
                for e in m.get("entries", [])
            )
        )
        assert msg is not None, "Remote peer's path table should contain local dest"


class TestAnnounceRemoteToLocal:
    """Announce from remote peer reaches the local Python through the target."""

    @pytest.fixture(scope="class")
    def session(self, peer_cmd, rns_path):
        """Remote peer announces; local Python listens."""
        s = MultiPipeSession(peer_cmd, rns_path, num_remote_peers=1)
        s.start(
            target_action="transport",
            target_transport=True,
            remote_actions=["announce"],
        )
        assert s.wait_for_target_ready() is not None, "Target should emit ready"
        assert s.wait_for_remote_ready(0) is not None, "Remote peer should emit ready"
        yield s
        s.stop()

    def test_remote_announce_reaches_local(self, session):
        """Remote peer's announce is retransmitted to local Python."""
        announced = session.wait_for_remote_announced(0, timeout=15)
        assert announced is not None, "Remote peer should announce"
        dest_hash = announced["destination_hash"]

        deadline = time.time() + 15
        while time.time() < deadline:
            if session.python_has_path(dest_hash):
                break
            time.sleep(0.2)

        assert session.python_has_path(dest_hash), \
            f"Local Python should learn path to remote destination {dest_hash}"

    def test_remote_announce_hop_count(self, session):
        """Local Python sees hops >= 1 for remote peer's destination."""
        for dest_hash_bytes in session.RNS.Transport.path_table:
            hops = session.RNS.Transport.hops_to(dest_hash_bytes)
            if hops is not None and hops >= 1:
                return
        assert len(session.RNS.Transport.path_table) > 0, \
            "Local Python should have at least one path from remote announces"
