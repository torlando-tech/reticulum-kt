"""
Reticulum Conformance Test Suite — pytest configuration and shared fixtures.

Tests the target implementation against the Python reference by connecting
them via PipeInterface (HDLC-framed stdin/stdout). The target implementation
runs as a subprocess speaking the pipe peer protocol.

Usage:
    # Test Kotlin implementation (default):
    pytest python-bridge/conformance/ --peer-cmd "java -jar rns-cli/build/libs/kt-pipe-peer.jar"

    # Test any implementation:
    pytest python-bridge/conformance/ --peer-cmd "./my-rns-pipe-peer"

    # Test Python against itself (sanity check):
    pytest python-bridge/conformance/ --peer-cmd "python3 python-bridge/pipe_peer.py"
"""
import os
import pytest

def pytest_addoption(parser):
    parser.addoption(
        "--peer-cmd",
        default=None,
        help="Command to launch the target pipe peer binary. "
             "Default: auto-detect kt-pipe-peer.jar or pipe_peer.py"
    )
    parser.addoption(
        "--java-home",
        default=os.environ.get("JAVA_HOME", None),
        help="JAVA_HOME for running JVM-based pipe peers"
    )


def _find_project_root():
    """Walk up from this file to find the project root (contains settings.gradle.kts)."""
    d = os.path.dirname(os.path.abspath(__file__))
    while d != "/":
        if os.path.exists(os.path.join(d, "settings.gradle.kts")):
            return d
        d = os.path.dirname(d)
    return os.path.dirname(os.path.abspath(__file__))


@pytest.fixture(scope="session")
def project_root():
    return _find_project_root()


@pytest.fixture(scope="session")
def peer_cmd(request, project_root):
    """Resolve the command to launch the target pipe peer."""
    cmd = request.config.getoption("--peer-cmd")
    if cmd:
        return cmd

    # Auto-detect: prefer Kotlin jar, fall back to Python pipe_peer.py
    kt_jar = os.path.join(project_root, "rns-cli/build/libs/kt-pipe-peer.jar")
    if os.path.exists(kt_jar):
        java_home = request.config.getoption("--java-home") or ""
        java = os.path.join(java_home, "bin", "java") if java_home else "java"
        return f"{java} -jar {kt_jar}"

    py_peer = os.path.join(project_root, "python-bridge/pipe_peer.py")
    if os.path.exists(py_peer):
        return f"python3 {py_peer}"

    pytest.skip("No pipe peer binary found. Build with: ./gradlew :rns-cli:pipePeerJar")


@pytest.fixture(scope="session")
def rns_path(project_root):
    """Find the Python RNS reference implementation."""
    env = os.environ.get("PYTHON_RNS_PATH")
    if env:
        return env
    home = os.path.expanduser("~")
    for candidate in [
        os.path.join(home, "repos/Reticulum"),
        os.path.join(home, "repos/public/Reticulum"),
        os.path.join(project_root, "../Reticulum"),
    ]:
        if os.path.isdir(candidate) and os.path.isdir(os.path.join(candidate, "RNS")):
            return candidate
    pytest.skip("Cannot find Python RNS. Set PYTHON_RNS_PATH env var.")
