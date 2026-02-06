# Phase 13: WorkManager Integration - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Periodic maintenance via Android WorkManager that survives deep Doze. The foreground service already runs Transport's continuous job loop (path cleanup, announce processing, link management). WorkManager's role is recovery-focused: when Doze suspends the service, the worker wakes up, checks service/interface health, reconnects if needed, and flushes queued messages. This is a companion to the service, not a replacement for it.

</domain>

<decisions>
## Implementation Decisions

### Worker scope
- **Recovery-focused, not duplicating the job loop** — Transport's continuous maintenance (path expiry, announce retransmit, link cleanup) runs in the foreground service. The worker handles what accumulates during Doze suspension
- **Tasks:** Interface health check (verify TCP/UDP connections, trigger reconnect if dead), announce processing (process queued inbound announces), queued LXMF message delivery (explicitly flush messages that accumulated while sleeping)
- **No network connectivity constraint** — Reticulum can route over LoRa/BLE without internet. Worker runs regardless of Android's network state since the phone may be off-grid but connected to mesh interfaces

### Scheduling policy
- **15-minute base interval** (WorkManager minimum)
- **Honor Phase 12 battery mode** — MAXIMUM_BATTERY could skip non-critical tasks or reduce work; PERFORMANCE always runs full suite
- **WorkManager default backoff on failure** — Exponential backoff starting at 10s, capped at 5 hours. Standard Android behavior, no custom backoff
- **Adaptive intervals:** Claude's discretion on whether to use adaptive scheduling based on activity level vs always-15-minutes

### Foreground vs background split
- **Worker is a service companion, not standalone** — Worker operates alongside the foreground service
- **Service restart:** Worker restarts service if killed by OS, but only if user has "auto-restart" preference enabled. Respects user's explicit choice
- **START_STICKY interaction:** Claude's discretion on whether to complement or replace START_STICKY with WorkManager-based restart
- **Healthy service handling:** Claude's discretion on whether worker no-ops when service is healthy or always runs checks

### Boot & restart behavior
- **Stop means stop** — When user explicitly stops service via UI, cancel WorkManager periodic work too. No background activity until user starts again
- **Auto re-enqueue after app update** — Use WorkManager's KEEP policy so existing work survives app updates
- **Boot sequence:** Claude's discretion on whether boot receiver starts service directly (immediate) or delegates to WorkManager (up to 15min delay)
- **Enqueue timing:** Claude's discretion on when to first enqueue WorkManager (service start vs app launch)

### Claude's Discretion
- Adaptive scheduling intervals vs fixed 15-minute
- No-op when service healthy vs always-verify
- Boot sequence: direct service start vs WorkManager-first
- WorkManager enqueue lifecycle timing
- Whether worker can operate without foreground service
- Complement vs replace START_STICKY for service restart

</decisions>

<specifics>
## Specific Ideas

- Python Reticulum runs a continuous 0.25s job loop with 9 periodic tasks (1s to 2hr intervals). This is NOT the model for WorkManager — the foreground service already replicates this. WorkManager handles what Python never needs to (Android power management recovery)
- The "no network constraint" decision is important: typical Android apps use `NetworkType.CONNECTED` constraint, but Reticulum routes over non-internet transports (LoRa, BLE) that Android doesn't consider "connected"

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 13-workmanager-integration*
*Context gathered: 2026-02-05*
