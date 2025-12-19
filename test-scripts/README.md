# Link Test Scripts

These scripts test Link establishment between Python RNS and rnsd-kt.

## Quick Start

Run the automated test:
```bash
./run_link_test.sh
```

## Test Architecture

```
┌─────────────────┐     TCP:4242      ┌─────────────┐    SharedInstance:37428    ┌─────────────────┐
│  Python Server  │◄─────────────────►│   rnsd-kt   │◄──────────────────────────►│  Python Client  │
│  (destination)  │                   │  (router)   │                            │ (Link initiator)│
└─────────────────┘                   └─────────────┘                            └─────────────────┘

Link Request flow:  Client ──► rnsd-kt ──► Server
Link Proof flow:    Server ──► rnsd-kt ──► Client
Data flow:          Bidirectional over established Link
```

## Manual Testing

### Terminal 1: Python Server
```bash
cd test-scripts
python3 link_test_server.py --config link_test_server_config -vv
```

### Terminal 2: rnsd-kt
```bash
# Create config at /tmp/rnsd-kt-link-test/config with:
# - TCPClientInterface connecting to 127.0.0.1:4242
# - share_instance = Yes on port 37428

JAVA_HOME=/usr/lib/jvm/java-21-openjdk java -jar ../rns-cli/build/libs/rnsd-kt.jar \
    --config /tmp/rnsd-kt-link-test -vvv
```

### Terminal 3: Python Client
```bash
# Wait for server announce to propagate, then:
python3 link_test_client.py -vv

# Or with specific destination:
python3 link_test_client.py -vv --dest <destination_hash>

# Interactive mode:
python3 link_test_client.py -vv --interactive
```

## What the Test Validates

1. **Announce propagation**: Server's announce reaches client via rnsd-kt
2. **Link request forwarding**: rnsd-kt forwards Link request from client to server
3. **Link proof routing**: rnsd-kt correctly routes LRPROOF back to client
4. **Data over Link**: Bidirectional data flows correctly

## Expected Output

### Successful Link
```
Link established!
  Link ID: <abc123...>
  RTT: 50ms
Sent: Test message 1
Received: ECHO: Test message 1
```

### Failed Link (proof routing issue)
```
Link timeout (status: 1)
```
Check rnsd-kt logs for:
- `Forwarding Link request for ...`
- `LRPROOF dest=... not found` (indicates routing problem)
- `Forwarding LRPROOF for ...` (successful proof routing)

## Logs

Logs are saved to `/tmp/link-test-logs/`:
- `server.log` - Python server output
- `rnsd-kt.log` - rnsd-kt output (most useful for debugging)
- `client.log` - Python client output

View logs after a run:
```bash
./run_link_test.sh --logs
```
