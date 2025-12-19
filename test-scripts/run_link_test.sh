#!/bin/bash
# Link Test Runner
#
# This script orchestrates a Link test between Python RNS and rnsd-kt.
#
# Test setup:
# 1. Python link_test_server.py - runs TCP server on port 4242, creates linktest.server destination
# 2. rnsd-kt - connects to Python server on 4242, provides shared instance on 37428
# 3. Python link_test_client.py - connects to rnsd-kt shared instance, establishes Link to server
#
# The Link request flows: client -> rnsd-kt (shared instance) -> Python server
# The Link proof flows: Python server -> rnsd-kt -> client

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Paths
RNSD_KT_JAR="$PROJECT_DIR/rns-cli/build/libs/rnsd-kt.jar"
SERVER_SCRIPT="$SCRIPT_DIR/link_test_server.py"
CLIENT_SCRIPT="$SCRIPT_DIR/link_test_client.py"
SERVER_CONFIG="$SCRIPT_DIR/link_test_server_config"
RNSD_KT_CONFIG="/tmp/rnsd-kt-link-test"
CLIENT_CONFIG="/tmp/rns-link-test-client"

# Log files
LOG_DIR="/tmp/link-test-logs"
SERVER_LOG="$LOG_DIR/server.log"
RNSD_KT_LOG="$LOG_DIR/rnsd-kt.log"
CLIENT_LOG="$LOG_DIR/client.log"

# PIDs for cleanup
SERVER_PID=""
RNSD_KT_PID=""

cleanup() {
    echo -e "\n${YELLOW}Cleaning up...${NC}"

    if [ -n "$SERVER_PID" ]; then
        echo "Stopping Python server (PID $SERVER_PID)..."
        kill $SERVER_PID 2>/dev/null || true
    fi

    if [ -n "$RNSD_KT_PID" ]; then
        echo "Stopping rnsd-kt (PID $RNSD_KT_PID)..."
        kill $RNSD_KT_PID 2>/dev/null || true
    fi

    # Give processes time to exit
    sleep 1

    # Force kill if still running
    [ -n "$SERVER_PID" ] && kill -9 $SERVER_PID 2>/dev/null || true
    [ -n "$RNSD_KT_PID" ] && kill -9 $RNSD_KT_PID 2>/dev/null || true

    echo -e "${GREEN}Cleanup complete${NC}"
}

trap cleanup EXIT

# Check prerequisites
check_prereqs() {
    echo -e "${YELLOW}Checking prerequisites...${NC}"

    # Check Python RNS
    if ! python3 -c "import RNS" 2>/dev/null; then
        echo -e "${RED}ERROR: Python RNS not installed${NC}"
        echo "Install with: pip install rns"
        exit 1
    fi

    # Check rnsd-kt jar
    if [ ! -f "$RNSD_KT_JAR" ]; then
        echo -e "${RED}ERROR: rnsd-kt.jar not found at $RNSD_KT_JAR${NC}"
        echo "Build with: ./gradlew :rns-cli:shadowJar"
        exit 1
    fi

    # Check Java
    if ! command -v java &>/dev/null; then
        echo -e "${RED}ERROR: Java not found${NC}"
        exit 1
    fi

    echo -e "${GREEN}Prerequisites OK${NC}"
}

# Create rnsd-kt config
create_rnsd_kt_config() {
    echo -e "${YELLOW}Creating rnsd-kt config...${NC}"

    mkdir -p "$RNSD_KT_CONFIG"
    cat > "$RNSD_KT_CONFIG/config" << 'EOF'
# rnsd-kt config for Link test
# Connects to Python server and provides shared instance
# Uses non-standard ports to avoid conflicts

[reticulum]
  enable_transport = Yes
  share_instance = Yes
  shared_instance_port = 47428
  instance_control_port = 47429
  panic_on_interface_error = No

[logging]
  loglevel = 7

[interfaces]
  # TCP Client to connect to Python server
  [[Link Test Server]]
    type = TCPClientInterface
    enabled = yes
    target_host = 127.0.0.1
    target_port = 14242
EOF

    echo -e "${GREEN}Config created at $RNSD_KT_CONFIG/config${NC}"
}

# Create client config (connects to rnsd-kt shared instance)
create_client_config() {
    echo -e "${YELLOW}Creating client config...${NC}"

    mkdir -p "$CLIENT_CONFIG"
    cat > "$CLIENT_CONFIG/config" << 'EOF'
# Client config for Link test
# Will try to create shared instance, fail (port in use), then connect as client

[reticulum]
  enable_transport = No
  share_instance = Yes
  shared_instance_type = tcp
  shared_instance_port = 47428
  instance_control_port = 47429
  panic_on_interface_error = No

[logging]
  loglevel = 7
EOF

    echo -e "${GREEN}Client config created at $CLIENT_CONFIG/config${NC}"
}

# Start Python server
start_server() {
    echo -e "${YELLOW}Starting Python Link Test Server...${NC}"

    # Use unbuffered Python (-u) so output is written immediately
    python3 -u "$SERVER_SCRIPT" --config "$SERVER_CONFIG" -vv > "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!

    echo "Server PID: $SERVER_PID"
    echo "Server log: $SERVER_LOG"

    # Wait for server to start
    sleep 3

    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo -e "${RED}ERROR: Server failed to start${NC}"
        cat "$SERVER_LOG"
        exit 1
    fi

    # Get destination hash from log
    DEST_HASH=$(grep -o "Server destination hash: <[^>]*>" "$SERVER_LOG" | head -1 | sed 's/.*<\([^>]*\)>/\1/' | tr -d ' ')
    echo -e "${GREEN}Server started. Destination: $DEST_HASH${NC}"
}

# Start rnsd-kt
start_rnsd_kt() {
    echo -e "${YELLOW}Starting rnsd-kt...${NC}"

    /usr/lib/jvm/java-21-openjdk/bin/java -jar "$RNSD_KT_JAR" \
        --config "$RNSD_KT_CONFIG" -vvv > "$RNSD_KT_LOG" 2>&1 &
    RNSD_KT_PID=$!

    echo "rnsd-kt PID: $RNSD_KT_PID"
    echo "rnsd-kt log: $RNSD_KT_LOG"

    # Wait for rnsd-kt to start and connect
    sleep 3

    if ! kill -0 $RNSD_KT_PID 2>/dev/null; then
        echo -e "${RED}ERROR: rnsd-kt failed to start${NC}"
        cat "$RNSD_KT_LOG"
        exit 1
    fi

    # Check if connected
    if grep -q "Connected to" "$RNSD_KT_LOG" 2>/dev/null || grep -q "Listening" "$RNSD_KT_LOG" 2>/dev/null; then
        echo -e "${GREEN}rnsd-kt started and connected${NC}"
    else
        echo -e "${YELLOW}rnsd-kt started (check log for connection status)${NC}"
    fi
}

# Run client test
run_client() {
    echo -e "${YELLOW}Running Link Test Client...${NC}"
    echo "This client connects to rnsd-kt shared instance and establishes a Link to the Python server"
    echo ""

    # Wait for announce to propagate
    echo "Waiting for announce to propagate through rnsd-kt..."
    sleep 5

    # Run client with our test config (connects to rnsd-kt shared instance)
    # Pass the known destination hash directly since announce handlers don't work reliably
    # when connected as a shared instance client
    python3 "$CLIENT_SCRIPT" --config "$CLIENT_CONFIG" -vv --messages 3 --dest "$DEST_HASH" 2>&1 | tee "$CLIENT_LOG"

    CLIENT_EXIT=$?

    if [ $CLIENT_EXIT -eq 0 ]; then
        echo -e "\n${GREEN}=== TEST PASSED ===${NC}"
    else
        echo -e "\n${RED}=== TEST FAILED ===${NC}"
    fi

    return $CLIENT_EXIT
}

# Show logs
show_logs() {
    echo -e "\n${YELLOW}=== Server Log (last 30 lines) ===${NC}"
    tail -30 "$SERVER_LOG" 2>/dev/null || echo "(no log)"

    echo -e "\n${YELLOW}=== rnsd-kt Log (last 50 lines) ===${NC}"
    tail -50 "$RNSD_KT_LOG" 2>/dev/null || echo "(no log)"
}

# Main
main() {
    echo "==========================================="
    echo "  Link Test: Python <-> rnsd-kt <-> Python"
    echo "==========================================="
    echo ""

    # Create log directory
    mkdir -p "$LOG_DIR"

    # Check prerequisites
    check_prereqs

    # Create configs
    create_rnsd_kt_config
    create_client_config

    # Start components
    start_server
    start_rnsd_kt

    echo ""
    echo "=== Test Components Running ==="
    echo "  Python Server: listening on TCP 14242"
    echo "  rnsd-kt:       connecting to 14242, shared instance on 47428"
    echo "  Client:        will connect to shared instance on 47428"
    echo ""

    # Run the test
    run_client
    TEST_RESULT=$?

    # Show logs on failure
    if [ $TEST_RESULT -ne 0 ]; then
        show_logs
    fi

    echo ""
    echo "Logs saved to: $LOG_DIR/"

    exit $TEST_RESULT
}

# Parse arguments
case "${1:-}" in
    --logs)
        show_logs
        ;;
    --server-only)
        check_prereqs
        mkdir -p "$LOG_DIR"
        start_server
        echo "Press Ctrl+C to stop"
        wait $SERVER_PID
        ;;
    --help|-h)
        echo "Usage: $0 [options]"
        echo ""
        echo "Options:"
        echo "  --logs         Show logs from last run"
        echo "  --server-only  Start only the Python server"
        echo "  --help         Show this help"
        ;;
    *)
        main
        ;;
esac
