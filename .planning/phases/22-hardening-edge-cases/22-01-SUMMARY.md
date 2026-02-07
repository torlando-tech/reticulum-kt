---
phase: 22
plan: 01
subsystem: ble-hardening
tags: [ble, zombie-detection, blacklist, exponential-backoff, mesh-resilience]
depends_on:
  requires: [21-01, 21-02]
  provides: [zombie-detection, exponential-blacklist, blacklist-forgiveness, traffic-tracking]
  affects: [22-02]
tech-stack:
  added: []
  patterns: [exponential-backoff, zombie-detection, traffic-liveness-tracking]
key-files:
  created: []
  modified:
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEConstants.kt
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEPeerInterface.kt
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BLEInterface.kt
decisions:
  - Blacklist forgiveness replaces isBlacklisted check in collectDiscoveredPeers (re-discovery proves liveness)
  - Zombie timeout 45s = 3 missed keepalives (keepalive interval 15s)
  - Exponential blacklist uses linear multiplier (count * base) capped at 8x, not power-of-2
  - Incoming connections get mid-range RSSI default (-70) since no scan data available
metrics:
  duration: 3min
  completed: 2026-02-07
---

# Phase 22 Plan 01: Zombie Detection and Exponential Blacklist Summary

**One-liner:** Zombie peer detection (45s timeout with graceful teardown) and exponential blacklist backoff (60s-480s with scan-based forgiveness)

## What Was Done

### Task 1: Hardening Constants and Traffic Tracking
- Added 6 hardening constants to `BLEConstants.kt`: `ZOMBIE_TIMEOUT_MS` (45s), `ZOMBIE_CHECK_INTERVAL_MS` (15s), `ZOMBIE_GRACE_PERIOD_MS` (5s), `BLACKLIST_BASE_DURATION_MS` (60s), `BLACKLIST_MAX_MULTIPLIER` (8), `EVICTION_MARGIN` (0.15)
- Added `lastTrafficReceived` volatile field to `BLEPeerInterface` -- updated on every incoming fragment (including keepalives) at the top of the `collect` lambda
- Added `discoveryRssi` volatile field for scoring/eviction decisions
- Reset `lastTrafficReceived` in `updateConnection()` (MAC rotation proves liveness)

### Task 2: Zombie Detection Loop and Exponential Blacklist
- Replaced flat `ConcurrentHashMap<String, Long>` blacklist with `BlacklistEntry(expiry, failureCount)` data class
- Added `addToBlacklist()` as single write path -- computes `base * min(count, 8)` duration
- Removed `blacklistDurationMs` field (replaced by `BLEConstants.BLACKLIST_BASE_DURATION_MS`)
- Added `zombieDetectionLoop()`: runs every 15s, detects peers with no traffic for >45s
- Zombie teardown flow: graceful disconnect -> 5s grace period -> force teardown -> blacklist
- Blacklist forgiveness in `collectDiscoveredPeers()`: re-discovery via BLE scan clears blacklist entry entirely
- Added `rssi` parameter to `spawnPeerInterface()` and set `discoveryRssi` on peer interfaces

## Decisions Made

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Blacklist forgiveness replaces isBlacklisted check entirely | Re-discovery proves peer is alive; subsequent backoff/capacity checks still apply |
| 2 | 45s zombie timeout (3 missed keepalives) | Conservative: avoids false positives from single dropped keepalive |
| 3 | Linear multiplier (count * base) capped at 8x | Simpler than power-of-2, gives 60/120/180/240/300/360/420/480s progression |
| 4 | Incoming connections get -70 RSSI default | No scan RSSI available for peripheral-side connections; -70 is mid-range |
| 5 | Zombie check on same cadence as keepalive (15s) | Natural alignment; zombie is 3x keepalive interval |

## Deviations from Plan

None -- plan executed exactly as written.

## Verification

1. `./gradlew :rns-interfaces:compileKotlin` -- BUILD SUCCESSFUL
2. `./gradlew :rns-interfaces:test --tests "network.reticulum.interfaces.ble.*"` -- BUILD SUCCESSFUL (all fragmentation tests green)
3. `zombieDetectionLoop` launched in `start()` (line 109)
4. `addToBlacklist` is sole blacklist write path (4 call sites: 2 timeout catches, 1 zombie teardown, 1 internal)
5. No references to `blacklistDurationMs` remain
6. Blacklist forgiveness at line 156-160, before backoff/capacity checks

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | c5ca8f6 | feat(22-01): add hardening constants and traffic tracking in BLEPeerInterface |
| 2 | e6e7a00 | feat(22-01): zombie detection loop and exponential blacklist in BLEInterface |

## Next Phase Readiness

Plan 22-02 can proceed. This plan provides:
- `lastTrafficReceived` on `BLEPeerInterface` for any future liveness checks
- `discoveryRssi` on `BLEPeerInterface` for eviction/scoring decisions
- `EVICTION_MARGIN` constant ready for use in connection eviction logic
- `addToBlacklist()` as reusable blacklist API for any future failure paths
