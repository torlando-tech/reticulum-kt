# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** v2 Android Production Readiness

## Current Position

Phase: 11 of 17 (Lifecycle-Aware Scope Injection)
Plan: 4 of 4 in current phase - COMPLETE
Status: Phase complete
Last activity: 2026-01-25 - Completed 11-03-PLAN.md (scope injection tests)

Progress: v2 [████░░░░░░] 50%

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
- Total plans completed: 6 (v2)
- Average duration: 2.5min
- Total execution time: 15min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 10 | 4/4 | 8min | 2min |
| 11 | 4/4 | 14min | 3.5min |

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

Last session: 2026-01-25
Stopped at: Completed 11-03-PLAN.md (scope injection tests)
Resume file: None
