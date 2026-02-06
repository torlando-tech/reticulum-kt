# Phase 22: Hardening and Edge Cases - Context

**Gathered:** 2026-02-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Production-quality resilience for the BLE mesh under adverse conditions. Zombie detection, failed-peer blacklisting, duplicate identity resolution, connection limit management, and peer scoring for eviction. This phase hardens the existing Phase 21 BLEInterface/BLEPeerInterface orchestration — no new connectivity features.

</domain>

<decisions>
## Implementation Decisions

### Zombie Detection
- 3 missed keepalives (45s with 15s interval) declares a peer a zombie
- Any traffic resets the keepalive timer — not just keepalive bytes, real data also proves liveness
- Teardown sequence: attempt graceful disconnect first, wait 5s, then force-close
- After zombie teardown, peer enters standard blacklist (same as handshake timeout)

### Blacklist & Backoff Policy
- Blacklist triggers: handshake timeout (30s) and zombie teardown only
- NOT blacklisted for: GATT 133 connection retries (existing exponential backoff handles this) or short sessions
- Blacklist tracks by BLE MAC address (not identity)
- Blacklist forgiveness: entries clear when the peer is re-discovered via BLE scan (suggests peer came back to life)
- Backoff scheme: Claude's Discretion — build on existing 60s/7s or replace with exponential, whichever is cleaner

### Connection Limit Behavior
- Max connections: configurable via interface config, default 5 peers
- At capacity with new peer discovered: evict lowest-scored existing peer IF new peer scores significantly higher (meaningful margin, not marginal improvement)
- Incoming connection policy: Claude's Discretion — determine whether incoming connections should always be accepted or enforced against the limit
- Scoring used for eviction decisions only — NOT for connection priority

### Peer Scoring
- Weights: RSSI 60% + connection history 30% + recency 10% (as specified in roadmap)
- Connection history metric: Claude's Discretion — pick the most practical metric (successful sessions, uptime ratio, or throughput)
- Scores are session-only — fresh each app restart, no persistence to disk
- Scoring scope: eviction decisions only. Connection order follows discovery order.

### Claude's Discretion
- Backoff scheme details (exponential vs. building on existing 60s/7s)
- Incoming connection policy at capacity (accept always vs. enforce limit)
- Connection history metric for the 30% scoring weight
- Eviction margin threshold (how much better a new peer must score to justify evicting)
- Duplicate identity resolution details (Phase 21 already has "keep newest" — harden edge cases)

</decisions>

<specifics>
## Specific Ideas

- Blacklist clears on re-discovery is key — BLE peers go in and out of range constantly, don't permanently punish them
- The 5-peer default leaves headroom for other BLE peripherals (watch, headphones, RNode) which is important for real-world use
- Eviction margin prevents connection churn — don't drop a good connection for a marginally better one

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 22-hardening-edge-cases*
*Context gathered: 2026-02-06*
