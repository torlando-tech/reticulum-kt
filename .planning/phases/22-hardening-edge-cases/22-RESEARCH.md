# Phase 22: Hardening and Edge Cases - Research

**Researched:** 2026-02-06
**Domain:** Android BLE connection resilience, peer management, zombie detection
**Confidence:** MEDIUM (codebase analysis HIGH, Android BLE stack behavior MEDIUM, OEM variance LOW)

## Summary

This phase hardens an already-working BLE mesh implementation (Phases 18-21) for production resilience. The existing codebase in `BLEInterface.kt` and `BLEPeerInterface.kt` already has basic blacklisting (60s flat), reconnection backoff (7s flat), duplicate identity detection (keep newest), MAC rotation handling (updateConnection), and keepalive sending (0x00 every 15s). Phase 22 upgrades these to production quality: zombie detection via real-data tracking, exponential blacklist backoff, peer scoring for eviction decisions, and connection limit enforcement with graceful degradation.

The Python ble-reticulum reference implementation (torlando-tech/ble-reticulum) provides a direct model for most of these features, and our existing Kotlin code was designed with Phase 22 in mind (e.g., `shouldInitiateConnection` is already stubbed, `DiscoveredPeer.connectionScore()` already exists with the correct 60/30/10 weights).

**Primary recommendation:** Modify `BLEInterface.kt` to add zombie detection (real-data tracking), exponential blacklist backoff (replacing flat 60s), eviction logic at connection limit, and incoming connection policy. Modify `BLEPeerInterface.kt` to track last-real-data timestamps and report zombie status. `DiscoveredPeer.kt` scoring is already correct and needs no changes.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines | existing | Async keepalive, zombie check loops | Already used throughout BLE stack |
| ConcurrentHashMap | JDK | Thread-safe peer/blacklist state | Already used in BLEInterface.kt |
| System.currentTimeMillis() | JDK | Timestamp tracking for zombie/recency | Already used for blacklist/backoff |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| AtomicLong | JDK | Last-real-data timestamp in BLEPeerInterface | Thread-safe volatile timestamp |
| @Volatile | Kotlin | Simple flags (zombie state) | Single-writer fields |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| System.currentTimeMillis() | SystemClock.elapsedRealtime() | elapsedRealtime is monotonic (better), but not available in pure JVM rns-interfaces module; currentTimeMillis already used everywhere |
| ConcurrentHashMap for blacklist | Mutex-guarded HashMap | ConcurrentHashMap already used; simpler, no suspend needed |

**No new dependencies.** All hardening uses existing JDK/coroutines primitives.

## Architecture Patterns

### Recommended Changes

The hardening logic lives in two existing files:

```
rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/
+-- BLEInterface.kt      # Zombie detection loop, blacklist upgrade, eviction logic
+-- BLEPeerInterface.kt  # lastRealDataReceived tracking, zombie status reporting
+-- DiscoveredPeer.kt    # NO CHANGES (scoring already correct)
+-- BLEConstants.kt      # Add zombie/blacklist constants
```

### Pattern 1: Real-Data Tracking for Zombie Detection
**What:** Track when each peer last received non-keepalive data. A peer that receives only keepalives for >45s is a zombie.
**When to use:** Zombie detection (HARD-01)
**Existing code to modify:** `BLEPeerInterface.receiveLoop()` already filters keepalives at line 80. Add a timestamp update for all non-keepalive data.

```kotlin
// In BLEPeerInterface.receiveLoop():
// Current code already filters keepalives:
if (fragment.size == 1 && fragment[0] == BLEConstants.KEEPALIVE_BYTE) {
    lastKeepaliveReceived = System.currentTimeMillis()
    return@collect
}

// ADD: Track last real data receipt (everything that isn't keepalive or identity)
lastRealDataReceived.set(System.currentTimeMillis())
```

**Key insight from ble-reticulum reference:** Keepalives explicitly DO NOT update the real-data timestamp. Only actual Reticulum packets prove the link is usable for data. This catches the case where "keepalives succeed but larger data packets fail" due to BLE link degradation.

### Pattern 2: Exponential Blacklist Backoff
**What:** Replace flat 60s blacklist with exponential backoff: 60s, 120s, 240s, 480s (capped).
**When to use:** Blacklist on handshake timeout or zombie teardown (HARD-02)
**Existing code to modify:** `BLEInterface.blacklist` map and `blacklistDurationMs` constant.

```kotlin
// Replace flat blacklist with exponential:
// blacklist: address -> (expiry, failureCount)
private val blacklist = ConcurrentHashMap<String, Pair<Long, Int>>()

private fun addToBlacklist(address: String) {
    val existing = blacklist[address]
    val failureCount = (existing?.second ?: 0) + 1
    val multiplier = minOf(failureCount, 8)
    val duration = BLACKLIST_BASE_DURATION_MS * multiplier  // 60s * 1..8
    blacklist[address] = Pair(System.currentTimeMillis() + duration, failureCount)
}
```

**Decision point:** The existing BleGattClient has its own 60s blacklist for GATT 133 retries. These are SEPARATE systems per CONTEXT.md: GATT 133 retries use existing exponential backoff (1s-16s); BLEInterface blacklist triggers only on handshake timeout and zombie teardown. No need to unify them.

### Pattern 3: Connection Limit Eviction
**What:** When at max connections and a better peer is discovered, evict lowest-scored peer if new peer scores significantly higher.
**When to use:** At capacity in `collectDiscoveredPeers()` (HARD-03)
**Existing code to modify:** The `if (peers.size >= maxConnections) return@collect` line.

```kotlin
// Replace hard skip with eviction check:
if (peers.size >= maxConnections) {
    val lowestPeer = findLowestScoredPeer() ?: return@collect
    val newScore = peer.connectionScore()
    val lowestScore = lowestPeer.connectionScore()

    // Evict only if new peer is meaningfully better (>20% margin)
    if (newScore > lowestScore + EVICTION_MARGIN) {
        tearDownPeer(lowestPeer.identityHex)
        // Continue to connection attempt
    } else {
        return@collect
    }
}
```

### Pattern 4: Incoming Connection Policy at Capacity
**What:** Accept incoming connections even at capacity (always accept), then evict lowest-scored peer if needed.
**When to use:** In `handleIncomingConnection()` (HARD-03)
**Rationale:** Incoming connections represent a peer that has already invested effort to connect. Rejecting them wastes that peer's resources and may cause the peer to blacklist us. The correct approach: accept first, then evict the lowest scorer (which might be the newly connected peer if it scores poorly).

```kotlin
// In handleIncomingConnection(), after successful handshake:
if (peers.size >= maxConnections) {
    // Just completed handshake -- we now have score info for all peers
    // Evict lowest scorer (might be the new peer itself)
    val allPeersWithNew = peers.values + newPeerInterface
    val lowestScorer = allPeersWithNew.minByOrNull { it.currentScore() }
    if (lowestScorer == newPeerInterface) {
        // New peer is worst -- reject it
        tearDownPeer(newIdentityHex)
        return
    } else {
        // Evict existing worst peer
        tearDownPeer(lowestScorer.identityHex)
    }
}
```

### Pattern 5: Blacklist Forgiveness on Re-discovery
**What:** Clear blacklist entry when a peer is re-discovered via BLE scan.
**When to use:** In `collectDiscoveredPeers()` (HARD-02)
**Rationale per CONTEXT.md:** "BLE peers go in and out of range constantly, don't permanently punish them." Re-discovery proves the peer is alive and nearby.

```kotlin
// In collectDiscoveredPeers(), before the blacklist check:
if (blacklist.containsKey(peer.address)) {
    // Peer re-discovered via scan -- forgive and clear blacklist
    blacklist.remove(peer.address)
    log("Cleared blacklist for re-discovered ${peer.address.takeLast(8)}")
}
```

### Anti-Patterns to Avoid
- **Don't evict for marginal improvements:** A 1% score difference does not justify connection churn. Use a meaningful margin (recommend 0.15 on the [0,1] scale, which is ~20% of the maximum score range).
- **Don't blacklist for GATT 133 at BLEInterface level:** GATT 133 is already handled by BleGattClient with its own exponential backoff. Duplicate blacklisting would prevent reconnection even when the GATT stack recovers.
- **Don't persist blacklist to disk:** Session-only per CONTEXT.md. Fresh start on app restart.
- **Don't use shouldInitiateConnection for eviction decisions:** It exists for connection direction dedup, not scoring. Scoring is separate.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Thread-safe timestamp tracking | Custom lock around Long | AtomicLong or @Volatile | Single-writer pattern, no lock needed |
| Exponential backoff calculation | Custom timer/scheduler | `minOf(count, cap) * base` inline | One line of math, no abstraction needed |
| Peer scoring | New scoring system | Existing `DiscoveredPeer.connectionScore()` | Already implements 60/30/10 weights correctly |
| Connection state tracking | New state machine | Existing `peers` ConcurrentHashMap + online/detached flags | Already tracks everything needed |

**Key insight:** Almost all the infrastructure is already in place from Phases 18-21. Phase 22 adds behavior ON TOP of existing data structures, not new data structures.

## Common Pitfalls

### Pitfall 1: GATT Error 133 Persistent State
**What goes wrong:** On some Android 14 devices, GATT error 133 can persist across `gatt.close()` and reconnect cycles. The Android BLE stack caches bad state internally, and subsequent connection attempts to the same address fail with 133 until Bluetooth is toggled or the device restarts.
**Why it happens:** Error 133 is Android's generic "something went wrong" GATT error (0x85). It can be caused by: connection collision, missed CONNECT_REQ, 30s connection timeout, or BLE stack internal state corruption. The Android source defines `BTM_BLE_CONN_TIMEOUT_DEF` as 2000 (units vary, but effective timeout is 20 seconds on pre-Android-10, reduced to ~5 seconds on Android 10+).
**How to avoid:** The existing BleGattClient already handles this correctly: full `gatt.close()` (no disconnect first) + fresh `connectGatt()` with exponential backoff (1s, 2s, 4s, 8s, 16s), 5 retries. After max retries, 60s blacklist. Phase 22 should NOT change this mechanism. The BLEInterface-level blacklist (handshake timeout, zombie) is a separate, higher-level concern.
**Warning signs:** Repeated 133 errors for the same address across multiple connection attempts with full close in between. If seen persistently, the user may need to toggle Bluetooth.
**Confidence:** MEDIUM -- based on multiple GitHub issues and Nordic DevZone reports, but specific Android 14 behavior is device/OEM-dependent.

### Pitfall 2: MAC Rotation Creating Duplicate Connections
**What goes wrong:** Android rotates BLE MAC addresses approximately every 15 minutes (Bluetooth spec default for Resolvable Private Address). When this happens, a previously-connected peer appears as a "new" device with a different MAC address. Without identity-based tracking, this creates duplicate connections.
**Why it happens:** BLE privacy feature: devices use Resolvable Private Addresses (RPAs) that rotate periodically to prevent tracking. The spec says ~15 minutes, but Bluetooth 6.1 (April 2025) introduced randomized rotation intervals (min-max range instead of fixed). Different OEMs may vary.
**How to avoid:** The existing code already handles this well. `BLEInterface.spawnPeerInterface()` checks for existing interface by identity and calls `updateConnection()` instead of creating a new interface. The identity handshake resolves the real identity regardless of MAC. Phase 22 should ensure the `shouldInitiateConnection` dedup enhancement doesn't break this existing MAC rotation handling.
**Warning signs:** Two `BLEPeerInterface` entries for the same identity hex in the `peers` map. This should be impossible given the existing dedup in `connectToPeer()` and `handleIncomingConnection()`.
**Confidence:** HIGH -- this is already handled in the codebase and verified against the ble-reticulum reference.

### Pitfall 3: Android BLE Connection Limit Behavior
**What goes wrong:** Android supports approximately 7 BLE connections total across ALL apps. When this limit is exceeded, new `connectGatt()` calls fail silently or return error 133. There is no official API to query the remaining connection slots.
**Why it happens:** Hardware radio constraint -- BLE, Bluetooth Classic, and Wi-Fi share the same radio, limiting available time slices. The limit varies by device/chipset but is typically 7 for Android (versus ~8 for iOS).
**How to avoid:** Default to 5 max connections (as specified in CONTEXT.md), leaving 2 slots for other apps/devices (headphones, watch, RNode). The configurable `maxConnections` parameter lets users adjust. Do NOT set above 7.
**Warning signs:** Connections failing for all peers simultaneously, especially when other BLE peripherals are connected.
**Confidence:** MEDIUM -- the 7-connection limit is widely reported but the exact behavior when exceeded is poorly documented and varies by device.

### Pitfall 4: Zombie Detection False Positives in Low-Traffic Mesh
**What goes wrong:** In a low-traffic mesh where no Reticulum packets are flowing, ALL peers will appear as zombies because no "real data" is being exchanged -- only keepalives.
**Why it happens:** The zombie detection algorithm only counts non-keepalive data as proof of life. If no app-level traffic exists, the timer will expire.
**How to avoid:** Use a generous zombie timeout (45 seconds per CONTEXT.md, with 15s keepalive interval = 3 missed keepalives). Additionally, reset the real-data timer on keepalive receipt as well, but separately track whether ONLY keepalives have been received (no data). A more robust approach: track `lastAnyTraffic` (updated by keepalives AND data) and `lastRealData` (updated only by real data). Zombie = lastAnyTraffic is stale (no keepalives either, meaning the link is truly dead).
**Revised recommendation:** The zombie detection should actually be "3 missed keepalives" as stated in CONTEXT.md, meaning NO traffic at all for 45 seconds. The "keepalive doesn't count as real data" pattern from ble-reticulum is for a different purpose (dedup zombie check, not primary zombie detection). For the primary keepalive-based zombie, ANY traffic resets the timer per CONTEXT.md.
**Confidence:** HIGH -- CONTEXT.md explicitly says "Any traffic resets the keepalive timer" and "3 missed keepalives declares zombie."

### Pitfall 5: Eviction During Active Data Transfer
**What goes wrong:** If a peer is evicted while it's in the middle of receiving a multi-fragment packet, the partial reassembly buffer is lost and the sending peer's packet is silently dropped.
**Why it happens:** Eviction calls `tearDownPeer()` which calls `detach()` which cancels the receive loop.
**How to avoid:** Accept this as a trade-off. Reticulum handles retransmission at a higher level. The eviction margin (>20% score improvement) makes this rare. Adding "is currently receiving" tracking would add complexity for minimal gain.
**Confidence:** HIGH -- this is a known acceptable trade-off in BLE mesh networking.

## Code Examples

### Zombie Detection Loop (new coroutine in BLEInterface)
```kotlin
// Source: Adapted from ble-reticulum _check_duplicate_identity() pattern
// and CONTEXT.md zombie specification

private suspend fun zombieDetectionLoop() {
    while (online.get() && !detached.get()) {
        delay(ZOMBIE_CHECK_INTERVAL_MS)  // Check every 15s (same as keepalive)
        val now = System.currentTimeMillis()

        for ((identityHex, peerInterface) in peers.toMap()) {
            val lastTraffic = peerInterface.lastTrafficReceived
            if (now - lastTraffic > ZOMBIE_TIMEOUT_MS) {
                log("Zombie detected: ${identityHex.take(8)} (no traffic for ${(now - lastTraffic) / 1000}s)")

                // Attempt graceful disconnect first
                try {
                    val address = identityToAddress[identityHex]
                    if (address != null) driver.disconnect(address)
                } catch (_: Exception) {}

                // Wait for graceful close, then force
                delay(ZOMBIE_GRACE_PERIOD_MS)  // 5s per CONTEXT.md

                if (peers.containsKey(identityHex)) {
                    tearDownPeer(identityHex)
                    // Blacklist after zombie teardown
                    val address = identityToAddress[identityHex]
                    if (address != null) addToBlacklist(address)
                }
            }
        }
    }
}
```

### Exponential Blacklist
```kotlin
// Source: Adapted from ble-reticulum _record_connection_failure()

companion object {
    const val BLACKLIST_BASE_DURATION_MS = 60_000L  // 60s base
    const val BLACKLIST_MAX_MULTIPLIER = 8          // Cap at 480s
}

// blacklist: address -> BlacklistEntry
private data class BlacklistEntry(val expiry: Long, val failureCount: Int)
private val blacklist = ConcurrentHashMap<String, BlacklistEntry>()

private fun addToBlacklist(address: String) {
    val existing = blacklist[address]
    val count = (existing?.failureCount ?: 0) + 1
    val multiplier = minOf(count, BLACKLIST_MAX_MULTIPLIER)
    val duration = BLACKLIST_BASE_DURATION_MS * multiplier
    blacklist[address] = BlacklistEntry(
        expiry = System.currentTimeMillis() + duration,
        failureCount = count
    )
    log("Blacklisted ${address.takeLast(8)} for ${duration / 1000}s (failure #$count)")
}

private fun isBlacklisted(address: String): Boolean {
    val entry = blacklist[address] ?: return false
    if (System.currentTimeMillis() > entry.expiry) {
        blacklist.remove(address)
        return false
    }
    return true
}
```

### Peer Scoring for Eviction
```kotlin
// Source: Uses existing DiscoveredPeer.connectionScore()
// Peers need DiscoveredPeer data attached for scoring

companion object {
    const val EVICTION_MARGIN = 0.15  // New peer must score >15% higher
}

private fun findLowestScoredPeer(): PeerWithScore? {
    return peers.entries.mapNotNull { (identityHex, peerInterface) ->
        val address = identityToAddress[identityHex] ?: return@mapNotNull null
        // Build a DiscoveredPeer from current state for scoring
        val rssi = peerInterface.lastRssi  // Need to track this
        val score = computePeerScore(rssi, peerInterface)
        PeerWithScore(identityHex, peerInterface, score)
    }.minByOrNull { it.score }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Fixed 15-min RPA rotation | Randomized min/max interval | Bluetooth 6.1 (April 2025) | MAC rotation timing is now unpredictable; identity-based tracking (already implemented) is the correct approach |
| 20s supervision timeout on Android | ~5s supervision timeout | Android 10 (2020) | Zombie connections detected faster on modern Android |
| No GATT cache clearing API | Still no public API | Ongoing | BluetoothGatt.refresh() remains hidden/private API; cannot be used safely |
| Fixed blacklist duration | Exponential backoff | ble-reticulum implementation | Prevents reconnect storms while allowing recovery |

## Open Questions

### 1. Connection History Metric for 30% Scoring Weight
- **What we know:** DiscoveredPeer already tracks `connectionAttempts` and `connectionSuccesses`. The `connectionScore()` function uses `successRate = successes / attempts` for the history component.
- **What's unclear:** Should we also track connection duration (uptime) or throughput? The ble-reticulum reference uses simple success rate.
- **Recommendation:** Use success rate (already implemented). It's the most practical metric -- successful sessions are easily tracked via existing connect/disconnect events. Uptime ratio adds complexity for marginal improvement. The existing `DiscoveredPeer.connectionScore()` is already correct and needs no changes to the history component.

### 2. RSSI Tracking for Connected Peers
- **What we know:** `DiscoveredPeer` receives RSSI from scan results. Once connected, RSSI is available via `BluetoothGatt.readRemoteRssi()` but is not currently tracked.
- **What's unclear:** Should we periodically poll RSSI for connected peers (for eviction scoring), or use the last-known scan RSSI?
- **Recommendation:** Use last-known scan RSSI from discovery. Polling RSSI adds GATT operations that compete with data transfer. For eviction, the scan RSSI is recent enough (within 3s throttle). Store the discovery RSSI in `BLEPeerInterface` or associate it with the peer entry.

### 3. OEM-Specific GATT 133 Persistence
- **What we know:** On some Android 14 devices (particularly tablets per Nordic DevZone reports), GATT 133 can enter a persistent state where ALL connection attempts fail until Bluetooth is toggled.
- **What's unclear:** Which specific OEMs/chipsets are affected. Whether the problem exists on Pixel or Samsung flagship phones (likely targets for this app).
- **Recommendation:** The existing 5-retry + blacklist mechanism is the best we can do. Add logging that suggests toggling Bluetooth if a peer stays blacklisted through multiple scan re-discoveries. This is a diagnostic aid, not an automated fix.

### 4. shouldInitiateConnection Usage
- **What we know:** `shouldInitiateConnection(peerIdentity)` is already implemented and stubbed with `@Suppress("unused")`. It returns true if our identity hash is lower (we should initiate as central).
- **What's unclear:** Whether to use this for preventing dual-connection attempts (both sides connecting simultaneously) or leave it unused.
- **Recommendation:** Consider using it to prevent simultaneous dual connections. After identity handshake, if `!shouldInitiateConnection(peerIdentity)` AND we initiated as central, AND the peer also has us as central, we should yield. However, the existing "keep newest" dedup already handles the result of dual connections. Using `shouldInitiateConnection` as a pre-filter would reduce wasted connection attempts but adds complexity. **Recommend leaving it unused for now** -- the existing dedup is sufficient, and the marginal improvement doesn't justify the risk of breaking the working connection flow.

## Claude's Discretion Recommendations

Based on research findings, here are recommendations for the areas left to Claude's discretion in CONTEXT.md:

### Backoff Scheme: Replace with Exponential
**Recommendation:** Replace the flat 60s blacklist with exponential backoff (60s * min(failures, 8)). Cap at 480s (8 minutes). This is cleaner than building on the existing 60s/7s because:
- The existing 60s blacklist and 7s reconnect backoff serve different purposes (blacklist = don't try at all, backoff = delay retry)
- Exponential backoff is the standard pattern for this problem
- The ble-reticulum reference uses exactly this pattern
- 480s cap is reasonable: a peer gone for 8 minutes has likely moved or crashed

### Incoming Connection Policy: Always Accept
**Recommendation:** Always accept incoming connections, even at capacity. Then evaluate whether to keep the new peer or evict it:
1. Incoming connection arrives at capacity
2. Complete identity handshake normally
3. Compare new peer's score to lowest existing peer's score
4. If new peer scores higher by EVICTION_MARGIN, evict the lowest
5. If new peer scores lower, tear down the new peer
**Rationale:** Rejecting before handshake wastes the initiator's connection effort and we don't have scoring data yet. Accepting then evaluating lets us make an informed decision.

### Connection History Metric: Success Rate
**Recommendation:** Use `connectionSuccesses / connectionAttempts` (already implemented in `DiscoveredPeer.connectionScore()`). This is the most practical and requires no new tracking infrastructure. The ble-reticulum reference uses the same metric.

### Eviction Margin Threshold: 0.15 (15%)
**Recommendation:** A new peer must score at least 0.15 higher than the lowest existing peer (on the [0, 1] scale) to trigger eviction. This prevents churn from marginal score differences while still allowing genuinely better peers to join.
- 0.15 represents roughly: 15 dBm better RSSI, OR 50% better history, OR a combination
- Too low (0.05): constant churn as peers move slightly
- Too high (0.30): practically never evict, defeating the purpose
- The ble-reticulum reference doesn't have a margin (just uses slot availability), but our CONTEXT.md specifically calls for one

### Duplicate Identity Resolution: Existing Code Sufficient
**Recommendation:** The existing duplicate identity detection in `connectToPeer()` and `handleIncomingConnection()` (keep newest, tear down oldest) is sufficient. The edge case to harden: simultaneous dual connections where both sides connect as central. The existing code handles this because both sides will complete handshake and detect the duplicate -- the one that completes second wins (tears down the first). No additional logic needed beyond what Phase 21 already delivered.

## Sources

### Primary (HIGH confidence)
- Existing codebase: `BLEInterface.kt`, `BLEPeerInterface.kt`, `DiscoveredPeer.kt`, `BLEConstants.kt`, `BleGattClient.kt`, `BleScanner.kt`, `BleGattServer.kt`, `AndroidBLEDriver.kt`
- Phase 22 CONTEXT.md decisions and constraints
- ble-reticulum reference implementation (torlando-tech/ble-reticulum on GitHub) -- BLEInterface.py scoring, blacklist, zombie, dedup patterns

### Secondary (MEDIUM confidence)
- [Nordic DevZone: Android 14 GATT 133 issues](https://devzone.nordicsemi.com/f/nordic-q-a/111467/android-14-connection-issues---repeated-gatt-133-errors) -- GATT 133 persistence on Android 14
- [GitHub android/connectivity-samples #18](https://github.com/android/connectivity-samples/issues/18) -- GATT 133 recovery procedures
- [Google Issue Tracker: BLE supervision timeout](https://issuetracker.google.com/issues/37119344) -- Default 20s supervision timeout, reduced to 5s on Android 10+
- [Nordic DevZone: BLE max connections](https://github.com/NordicSemiconductor/Android-BLE-Library/issues/189) -- ~7 connection limit, device-dependent, no official error code
- [Bluetooth SIG: Randomized RPA Updates](https://www.bluetooth.com/specifications/specs/randomized-rpa-updates-public-release/) -- Bluetooth 6.1 randomized RPA rotation
- [BleepingComputer: Bluetooth 6.1 privacy](https://www.bleepingcomputer.com/news/security/bluetooth-61-enhances-privacy-with-randomized-rpa-timing/) -- Randomized RPA timing details

### Tertiary (LOW confidence)
- OEM-specific GATT 133 persistence behavior -- reported anecdotally, no systematic documentation
- Exact connection limit for specific Samsung/Pixel devices -- commonly cited as 7 but varies
- BluetoothGatt.refresh() as hidden API -- exists but is not public and may be removed; do NOT use

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new dependencies, all existing primitives
- Architecture: HIGH -- patterns from reference implementation and existing codebase structure
- Pitfalls: MEDIUM -- Android BLE stack behavior is well-documented but OEM variance is real
- Zombie detection: HIGH -- CONTEXT.md specifies exact parameters (3 missed keepalives, 45s, 15s interval)
- Blacklist/backoff: HIGH -- clear pattern from reference, simple math
- Connection limits: MEDIUM -- 7-connection limit widely reported but error behavior poorly documented
- MAC rotation: HIGH -- already handled in existing code, just needs hardening verification

**Research date:** 2026-02-06
**Valid until:** 2026-03-06 (30 days -- stable domain, no fast-moving dependencies)
