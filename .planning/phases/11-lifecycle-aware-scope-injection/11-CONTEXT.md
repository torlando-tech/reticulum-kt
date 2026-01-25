# Phase 11: Lifecycle-Aware Scope Injection - Context

**Gathered:** 2026-01-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Coroutine scope propagation from ReticulumService to TCP/UDP network interfaces. Enables proper coroutine cancellation when the service stops, preventing leaks while maintaining backward compatibility for standalone JVM usage.

</domain>

<decisions>
## Implementation Decisions

### Scope injection pattern
- Constructor parameter approach: `parentScope: CoroutineScope? = null`
- Nullable with default null — null means "create your own standalone scope"
- Parameter position: last parameter (after required params, easy to omit)
- Always create child scope internally: `parentScope + Job()` — interface can cancel its own work without affecting siblings

### Cancellation behavior
- Graceful timeout: wait for in-flight I/O to complete before force cancel
- No cleanup/disconnect messages — Reticulum protocol handles reconnection naturally
- Cancel coroutines AND close socket — clean resource release
- Total cancellation must complete within 1 second (roadmap requirement)

### Standalone mode behavior
- When parentScope is null: create `GlobalScope + Job()` — lives until explicitly cancelled
- Keep stop() method for explicit cleanup in JVM tests and non-Android usage
- stop() triggers same cleanup as scope cancellation — consistent behavior paths
- JVM tests continue working without changes — defaults handle everything

### Error propagation
- SupervisorJob for interface isolation — one interface failing doesn't cancel others
- Log errors internally + optional callback if registered — flexible for debugging vs production
- CancellationException treated as expected (silent) — it's normal shutdown, not an error
- No reconnection attempts on parent scope cancellation — parent cancel = intentional shutdown

### Claude's Discretion
- Exact graceful timeout split (e.g., 500ms graceful + margin for cleanup)
- Internal scope creation details (dispatcher choice, naming for debugging)
- Error callback API design (if implemented)

</decisions>

<specifics>
## Specific Ideas

- Scope injection should feel "invisible" for existing code — add parameter, existing callers unchanged
- Mesh resilience through SupervisorJob — network is unreliable, one bad interface shouldn't take down others
- "Parent cancel = intentional shutdown" — this is the key semantic for Android lifecycle

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 11-lifecycle-aware-scope-injection*
*Context gathered: 2026-01-25*
