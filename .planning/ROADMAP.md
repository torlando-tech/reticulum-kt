# Roadmap: Reticulum-KT v2

## Milestones

- v1.0 LXMF Interoperability - Phases 1-9 (shipped 2026-01-24)
- v2.0 Android Production Readiness - Phases 10-17 (in progress)

## Overview

This milestone delivers production-ready Android background connectivity for TCP/UDP interfaces. Starting with lifecycle observation infrastructure, we progressively add scope injection for proper coroutine cancellation, Doze-aware connection management, WorkManager for maintenance windows, service notification UX, battery optimization guidance, OEM-specific compatibility, and memory optimization. Each phase builds on the previous to create an always-connected service that survives Doze mode, respects battery constraints, and runs reliably across Android 8.0+ devices from major manufacturers.

## Phases

<details>
<summary>v1.0 LXMF Interoperability (Phases 1-9) - SHIPPED 2026-01-24</summary>

See `.planning/milestones/v1-ROADMAP.md` for archived roadmap.

**Summary:** 25 plans across 10 phases (including 8.1 inserted) delivered complete LXMF interoperability with 120+ tests verifying byte-level compatibility with Python LXMF.

</details>

### v2.0 Android Production Readiness (In Progress)

**Milestone Goal:** Always-connected background operation that survives Doze and battery optimization without excessive drain

**Phase Numbering:** Continues from v1 (phases 10-17)

- [x] **Phase 10: Android Lifecycle Foundation** - Doze/network/battery state observers
- [x] **Phase 11: Lifecycle-Aware Scope Injection** - Coroutine scope propagation from service to interfaces
- [x] **Phase 12: Doze-Aware Connection Management** - Connection survival through Doze and network transitions
- [x] **Phase 13: WorkManager Integration** - Doze-surviving periodic maintenance
- [x] **Phase 14: Service Notification UX** - Status display and quick actions
- [x] **Phase 15: Battery Optimization UX** - Exemption flow and usage statistics
- [ ] **Phase 16: OEM Compatibility** - Samsung, Xiaomi, Huawei manufacturer handling
- [ ] **Phase 17: Memory Optimization** - Long-term stability and leak prevention

## Phase Details

### Phase 10: Android Lifecycle Foundation

**Goal**: Establish infrastructure for observing Android power states and network conditions
**Depends on**: Nothing (first v2 phase, builds on v1 ReticulumService)
**Requirements**: SERV-01, SERV-02, CONN-04
**Research flags**: Standard patterns, skip phase research. Official Android documentation covers all APIs.
**Success Criteria** (what must be TRUE):
  1. Service runs with connectedDevice foreground service type on API 26+
  2. Notification channel exists with configurable importance/sound/vibration
  3. DozeStateObserver exposes current Doze state via StateFlow
  4. NetworkStateObserver detects WiFi/cellular/VPN transitions
  5. BatteryOptimizationChecker detects if app is battery-optimized

**Plans:** 4 plans (3 parallel + 1 gap closure)

Plans:
- [x] 10-01-PLAN.md - Foreground service type + notification channels
- [x] 10-02-PLAN.md - DozeStateObserver with StateFlow API
- [x] 10-03-PLAN.md - NetworkStateObserver + BatteryOptimizationChecker
- [x] 10-04-PLAN.md - Gap closure: Wire observers into ReticulumService lifecycle

### Phase 11: Lifecycle-Aware Scope Injection

**Goal**: Enable proper coroutine cancellation when service stops, preventing leaks
**Depends on**: Phase 10
**Requirements**: SERV-03, CONN-01
**Research flags**: Standard coroutine patterns, skip phase research. Well-documented in Kotlin docs.
**Success Criteria** (what must be TRUE):
  1. TCPClientInterface accepts optional parentScope parameter (defaults to standalone)
  2. UDPInterface accepts optional parentScope parameter (defaults to standalone)
  3. Service stop cancels all interface I/O coroutines within 1 second
  4. TCP/UDP connections remain alive when app is backgrounded (service running)
  5. JVM tests continue working without Android dependencies
**Plans:** 4 plans (2 parallel in wave 1, 2 parallel in wave 2)

Plans:
- [x] 11-01-PLAN.md - TCPClientInterface parentScope injection
- [x] 11-02-PLAN.md - UDPInterface parentScope injection
- [x] 11-03-PLAN.md - Scope injection tests and verification
- [x] 11-04-PLAN.md - Wire InterfaceManager to pass parentScope to interfaces

### Phase 12: Doze-Aware Connection Management

**Goal**: Connections survive Doze mode and network transitions with intelligent backoff
**Depends on**: Phase 10, Phase 11
**Requirements**: CONN-02, CONN-03, CONN-05, BATT-04
**Research flags**: Needs testing-focused phase research. Doze behavior varies by OEM.
**Success Criteria** (what must be TRUE):
  1. Transport job loop uses longer intervals during Doze (reduces wake-ups)
  2. Interfaces reconnect automatically after WiFi/cellular transitions
  3. Connections persist across full device sleep/wake cycles
  4. Connection polling throttles when battery <15%
  5. Reconnection uses exponential backoff (avoids battery drain on flaky networks)
**Plans:** 5 plans (2 parallel in wave 1, 2 parallel in wave 2, 1 in wave 3)

Plans:
- [x] 12-01-PLAN.md - ConnectionPolicy and ConnectionPolicyProvider (combines state flows)
- [x] 12-02-PLAN.md - ExponentialBackoff utility for reconnection strategy
- [x] 12-03-PLAN.md - Wire policy into ReticulumService for Transport throttling
- [x] 12-04-PLAN.md - TCPClientInterface exponential backoff integration
- [x] 12-05-PLAN.md - InterfaceManager network change notifications

### Phase 13: WorkManager Integration

**Goal**: Periodic maintenance survives deep Doze via system-scheduled windows
**Depends on**: Phase 10, Phase 11
**Requirements**: BATT-03, CONN-06
**Research flags**: Standard WorkManager patterns, skip phase research. Official documentation sufficient.
**Success Criteria** (what must be TRUE):
  1. ReticulumWorker executes every 15 minutes without network constraint (mesh routing)
  2. Path table cleanup and announce processing run during maintenance windows
  3. Worker performs interface health checks and conditional service restart
  4. Sub-second delivery when app is in foreground or service active
  5. Worker survives app restart (re-enqueues on boot if service was running)
**Plans:** 3 plans (2 parallel in wave 1, 1 in wave 2)

Plans:
- [x] 13-01-PLAN.md -- Enhanced ReticulumWorker with recovery-focused health checks
- [x] 13-02-PLAN.md -- BootReceiver + service lifecycle WorkManager wiring
- [x] 13-03-PLAN.md -- Sample app ViewModel integration and autoStart wiring

### Phase 14: Service Notification UX

**Goal**: Persistent notification provides status and control without opening app
**Depends on**: Phase 10, Phase 11
**Requirements**: SERV-04, SERV-05
**Research flags**: Skip phase research. Standard notification patterns.
**Success Criteria** (what must be TRUE):
  1. Notification shows current connection status (connected/connecting/disconnected)
  2. Notification shows connected interface count and type (e.g., "2 TCP, 1 UDP")
  3. Quick action: Reconnect button triggers immediate reconnection attempt
  4. Quick action: Pause button temporarily disables connection (user-controlled)
  5. Tapping notification opens app to connection status screen
**Plans:** 3 plans (1 in wave 1, 1 in wave 2, 1 in wave 3)

Plans:
- [x] 14-01-PLAN.md -- Connection state model and notification content builder
- [x] 14-02-PLAN.md -- Notification quick actions (Reconnect + Pause/Resume)
- [x] 14-03-PLAN.md -- Wire notification updates into service lifecycle

### Phase 15: Battery Optimization UX

**Goal**: User understands battery impact and can optimize for their usage pattern
**Depends on**: Phase 12, Phase 13
**Requirements**: BATT-01, BATT-02, BATT-05
**Research flags**: Skip phase research. Exemption flow well-documented.
**Success Criteria** (what must be TRUE):
  1. Battery drain <2% per hour measured during 8-hour idle test
  2. App detects battery optimization status and shows guidance
  3. Exemption request flow explains why (mesh networking requires persistent connection)
  4. Battery usage statistics visible in app (current session, last 24h, weekly average)
  5. User can see if battery optimization is affecting connectivity
**Plans:** 3 plans (2 parallel in wave 1, 1 in wave 2)

Plans:
- [x] 15-01-PLAN.md -- Battery stats tracking infrastructure and exemption helper
- [x] 15-02-PLAN.md -- Service event tracking and kill detection wiring
- [x] 15-03-PLAN.md -- Monitor screen battery UX (chart, warning, exemption sheet)

### Phase 16: OEM Compatibility

**Goal**: Service runs reliably on Samsung, Xiaomi, and Huawei devices despite manufacturer restrictions
**Depends on**: Phase 15
**Requirements**: OEM-01, OEM-02, OEM-03, OEM-04, OEM-05
**Research flags**: Needs phase research for OEM-specific deep-link intents. Manufacturer settings paths change frequently.
**Success Criteria** (what must be TRUE):
  1. Samsung: App excluded from "sleeping apps" via user guidance
  2. Xiaomi: Autostart permission requested with explanation
  3. Huawei: Protected apps inclusion requested with explanation
  4. OEM detected at runtime with manufacturer-specific guidance shown
  5. Deep-links open correct battery settings screen per manufacturer
**Plans**: TBD

### Phase 17: Memory Optimization

**Goal**: Service runs for days without memory leaks or OOM crashes
**Depends on**: Phase 16
**Requirements**: MEM-01, MEM-02, MEM-03
**Research flags**: Standard profiling techniques, skip phase research. Android Studio Memory Profiler + LeakCanary cover all needs.
**Success Criteria** (what must be TRUE):
  1. Peak memory usage <50MB during normal operation (verified via profiler)
  2. No memory growth over 24-hour soak test (heap dumps at 1h, 8h, 24h)
  3. onTrimMemory triggers ByteArrayPool release and Transport cache trim
  4. Service survives TRIM_MEMORY_RUNNING_CRITICAL without crash
  5. LeakCanary reports zero leaks in test builds
**Plans**: TBD

## Progress

**Execution Order:** Phases 10 through 17 in sequence, with 12 and 13 parallelizable after 10+11.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 10. Android Lifecycle Foundation | 4/4 | ✓ Complete | 2026-01-25 |
| 11. Lifecycle-Aware Scope Injection | 4/4 | ✓ Complete | 2026-01-25 |
| 12. Doze-Aware Connection Management | 5/5 | ✓ Complete | 2026-01-25 |
| 13. WorkManager Integration | 3/3 | ✓ Complete | 2026-02-05 |
| 14. Service Notification UX | 3/3 | ✓ Complete | 2026-02-05 |
| 15. Battery Optimization UX | 3/3 | ✓ Complete | 2026-02-05 |
| 16. OEM Compatibility | 0/TBD | Not started | - |
| 17. Memory Optimization | 0/TBD | Not started | - |

---

*Created: 2026-01-24*
*Milestone: v2 Android Production Readiness*
