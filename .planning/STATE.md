# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** v2 Android Production Readiness

## Current Position

Phase: 14 of 17 (Service Notification UX)
Plan: 2 of 3 in current phase
Status: In progress
Last activity: 2026-02-05 - Completed 14-02-PLAN.md (Notification Quick Actions)

Progress: v2 [█████████░] 84%

## Milestone Goals

**v2: Android Production Readiness**

Make existing Reticulum-KT interfaces (TCP/UDP) production-ready for Android:
- Always-connected background operation
- Survives Doze and battery optimization
- Reasonable power consumption (<2% per hour)
- API 26+ (Android 8.0+)
- OEM compatibility (Samsung, Xiaomi, Huawei)

## Performance Metrics

**Velocity:**
- Total plans completed: 18 (v2)
- Average duration: 2.5min
- Total execution time: 44.5min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 10 | 4/4 | 8min | 2min |
| 11 | 4/4 | 14min | 3.5min |
| 12 | 5/5 | 14min | 2.8min |
| 13 | 3/3 | 5min | 1.7min |
| 14 | 2/3 | 3.5min | 1.75min |

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [v1]: API 26+ target floor (enables modern Android APIs)
- [v1]: TCP/UDP interfaces already working (v2 adds lifecycle management)
- [v1]: No Python at runtime (pure Kotlin for production)
- [10-01]: connectedDevice service type for P2P mesh networking (not dataSync)
- [10-01]: Dual notification channels (service low-priority, alerts default-priority)
- [10-02]: StateFlow over callback-based listener for reactive Doze API
- [10-02]: Injectable class design over singleton for testability
- [10-02]: ConcurrentLinkedDeque for thread-safe history buffer
- [10-03]: 500ms debounce for network state changes to coalesce rapid handoffs
- [10-03]: String constant for hidden PowerManager API (POWER_SAVE_WHITELIST_CHANGED)
- [10-03]: No history buffer for battery status (rare, user-initiated changes)
- [10-04]: Observers stopped before serviceScope.cancel() for clean shutdown
- [10-04]: StateFlow collectors use placeholder logging - reactions deferred to Phase 12/15
- [11-01]: SupervisorJob(parentJob) for child scope - independent failure isolation
- [11-01]: invokeOnCompletion listener for automatic cleanup on parent cancellation
- [11-01]: Default null parentScope preserves backward compatibility for JVM tests
- [11-02]: parentScope as last parameter preserves all existing call sites
- [11-02]: stop() delegates to detach() - single cleanup path
- [11-04]: parentScope = scope wired in InterfaceManager TCP_CLIENT case
- [11-04]: stopInterface uses stop() for TCPClientInterface, detach() fallback for others
- [11-03]: awaitCancellation() watcher for immediate cancellation response
- [11-03]: Test both positive path (stays alive) and negative path (cancels correctly)
- [12-01]: 3% hysteresis for battery throttling (15% start, 18% resume)
- [12-01]: 30-second polling for battery state (simpler than callbackFlow)
- [12-01]: Charging overrides battery throttling but not Doze
- [12-02]: Pure Kotlin ExponentialBackoff in rns-interfaces module (not Android-specific)
- [12-02]: Default backoff: 1s initial, 60s max, 2.0 multiplier, 10 attempts
- [12-03]: Log.d for state changes (policy collector handles throttling reactions)
- [12-03]: Reverse lifecycle order: policyProvider.stop() before batteryMonitor.stop()
- [12-04]: Backoff resets on success or network change, not on reconnect cycle start
- [12-04]: Max attempts causes detach() with log message for debugging
- [12-05]: ViewModel-owned NetworkStateObserver for InterfaceManager (separate from ReticulumService)
- [12-05]: Only notify on actual type changes (previousType != currentType) - avoids redundant notifications
- [12-05]: Optional networkObserver parameter with null default - backward compatible
- [13-01]: inputData for auto-restart flag keeps library module independent of app DataStore
- [13-01]: BatteryManager direct read in worker (short-lived, no BatteryMonitor needed)
- [13-01]: Interface health check is logging-only; reconnection handled by TCPClientInterface
- [13-02]: WorkManager scheduled in all modes, not just transport (recovery is universal)
- [13-02]: Cancel WorkManager before Reticulum.stop() to avoid restart race
- [13-02]: MY_PACKAGE_REPLACED as safety net for KEEP policy across app updates
- [13-03]: Belt-and-suspenders WorkManager: ViewModel schedules/cancels alongside Service's own management
- [13-03]: DONT_KILL_APP flag when toggling BootReceiver to avoid disrupting running app
- [13-03]: Preference-to-component binding: DataStore preference drives PackageManager component state
- [14-01]: Em dash separator in notification content text for clean visual
- [14-01]: Interface detail in parentheses in expanded view for readability
- [14-01]: Sort interface breakdown by count descending (most common type first)
- [14-02]: Static instance pattern for BroadcastReceiver-to-Service communication
- [14-02]: Long.MAX_VALUE interval for pause (Transport.stopCoroutineJobLoop is private)
- [14-02]: Reconnect hidden when paused (contradictory to frozen state)
- [14-02]: Callback-based onReconnectRequested (service doesn't own InterfaceManager)

### From v1

Codebase ready for Android optimization:
- 31,126 LOC (Kotlin + Python bridge for testing)
- TCP/UDP interfaces working
- 120+ interop tests can verify Android changes don't break protocol
- ReticulumService fully implemented with observer lifecycle management

### Pending Todos

None yet.

### Blockers/Concerns

- **[RESOLVED]** Android battery/Doze issues - Phase 10 foundation complete, reactions in Phase 12

## Session Continuity

Last session: 2026-02-05
Stopped at: Completed 14-02-PLAN.md (Notification Quick Actions)
Resume file: None
