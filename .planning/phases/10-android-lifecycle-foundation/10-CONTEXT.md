# Phase 10: Android Lifecycle Foundation - Context

**Gathered:** 2026-01-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Establish infrastructure for observing Android power states (Doze) and network conditions. This phase creates the observers that downstream phases use to make intelligent decisions — it does NOT implement the reactions to those states (that's Phase 12).

Deliverables:
- DozeStateObserver exposing Doze state via StateFlow
- NetworkStateObserver exposing WiFi/Cellular/None via StateFlow
- BatteryOptimizationChecker detecting if app is battery-optimized
- Foreground service type configuration (connectedDevice)
- Notification channel setup

</domain>

<decisions>
## Implementation Decisions

### Observer Exposure Pattern
- StateFlow as the single API (`.value` for current, `collect` for changes)
- Injectable instances, not singletons — passed to service/interfaces, testable with fakes
- Claude's discretion on whether to use interfaces + implementations or concrete classes (based on testing needs)

### Network Transition Granularity
- Track WiFi/Cellular/None — basic states only, no VPN/metered/captive portal
- Include interface names (wlan0, rmnet0) in state for detailed logging
- Trust ConnectivityManager's state — no extra connectivity pings
- Claude's discretion on debouncing rapid changes during WiFi/cellular handoffs

### Notification Channel Setup
- Two channels: "Reticulum Service" (low importance) + "Reticulum Alerts" (higher priority)
- Service channel: Low importance — shows in shade but no sound/vibration
- Alerts channel: For disconnect warnings and important status changes
- Channel creation at app launch (user can configure before service starts)

### State Change Logging
- Info level: Log state changes only (not every callback)
- Keep ring buffer of last 20 state changes with timestamps
- History exposed via API: `observer.recentHistory` returns timestamped changes
- Claude's discretion on log tag convention (match existing codebase patterns)

### Claude's Discretion
- Interface vs concrete class for each observer (based on JVM testing needs)
- Debounce timing for network changes
- Log tag naming convention
- Exact history buffer size (20 suggested, can adjust)

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard Android patterns for lifecycle observers.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 10-android-lifecycle-foundation*
*Context gathered: 2026-01-24*
