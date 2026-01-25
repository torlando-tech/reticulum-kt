# Phase 12: Doze-Aware Connection Management - Context

**Gathered:** 2026-01-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Connections survive Doze mode and network transitions with intelligent backoff. TCP/UDP connections adapt their polling intervals based on Android power states, reconnect automatically after network changes, and throttle when battery is low. This phase reacts to the state observers built in Phase 10 and uses the scope injection from Phase 11.

</domain>

<decisions>
## Implementation Decisions

### Doze Timing Behavior
- Two-tier throttling: Light Doze = 2x slower, Deep Doze = 5x slower
- Immediate resume to normal polling when exiting Doze (no gradual ramp-up)
- Throttling applies to Transport job loop intervals

### Claude's Discretion (Doze Timing)
- Whether to throttle TCP reader loops or only maintenance
- Minimum wake interval floor (if any)
- Exact interval calculations based on Python Reticulum reference

### Reconnection Strategy
- Exponential backoff starting at 1 second
- Maximum backoff caps at 1 minute (60 seconds)
- Give up after 10 consecutive failures (requires manual reconnect)
- Network changes reset the backoff counter (fresh start on new network)

### Network Transition Handling
- Use existing 500ms debounce from NetworkStateObserver (Phase 10)
- Pause connections immediately when network becomes unavailable
- Resume/reconnect when network returns (backoff resets)
- VPN transitions treated the same as WiFi/cellular transitions

### Claude's Discretion (Network)
- Whether to proactively drop TCP connections on WiFi→cellular handoff or let TCP handle naturally

### Low Battery Throttling
- Throttle at 15% battery (standard Android threshold)
- Same aggressiveness as deep Doze (5x slower)
- Charging state overrides: if plugged in, run at full speed regardless of battery level
- 3% hysteresis: throttle at 15%, resume at 18%

</decisions>

<specifics>
## Specific Ideas

- Mesh networking context: connectivity is more important than typical apps, but battery life still matters for mobile devices
- User expectation: "I plugged in my phone, it should work at full speed even if battery is low"
- The Phase 10 observers already provide debounced state streams — this phase consumes them

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 12-doze-aware-connection-management*
*Context gathered: 2026-01-25*
