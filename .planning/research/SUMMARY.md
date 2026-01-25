# Project Research Summary

**Project:** Reticulum-KT v2 - Android Production Readiness
**Domain:** Always-connected background networking for decentralized mesh messaging
**Researched:** 2026-01-24
**Confidence:** HIGH

## Executive Summary

Making Reticulum-KT production-ready on Android requires navigating a hostile environment: aggressive Doze mode restrictions, 6-hour service time limits (Android 15+), manufacturer-specific battery killers, and evolving platform policies. The core challenge is maintaining persistent TCP/UDP connections for mesh networking while surviving deep idle states that completely suspend network access.

The recommended approach combines foreground services with `connectedDevice` type (avoiding the 6-hour dataSync limit), lifecycle-aware coroutine management, battery optimization exemption (justified for mesh networking), and WorkManager for Doze-surviving maintenance. The existing architecture (Transport, Interface layers using coroutines) is fundamentally sound but needs Android-aware wrapper layers with lifecycle integration. Critical: avoid tight coupling to Android APIs in core modules to preserve JVM testability and CLI compatibility.

Key risks center on OEM-specific killing mechanisms (Xiaomi, Huawei, Samsung override standard Android behavior), service type time limits, and battery drain from improper wake lock usage. Mitigation requires manufacturer-specific user guidance, extensive physical device testing across OEMs, exponential backoff for reconnection, and battery profiling with Android Battery Historian. Expected outcome: <2% battery drain per hour backgrounded (vs current 14-24%), surviving 24+ hours in Doze mode with maintained connections.

## Key Findings

### Recommended Stack

For persistent TCP/UDP mesh networking on Android, use first-party Android APIs with minimal dependencies. WorkManager 2.11.0 replaces the current 250ms polling job loop with Doze-aware 15-minute maintenance intervals. Kotlin coroutines 1.8.0+ replace all Thread.sleep() and blocking I/O with structured concurrency. Foreground services use the built-in Service class with `connectedDevice` type (no library needed). The existing Kotlin 1.9.22, Coroutines 1.8.0, and BouncyCastle 1.77 stack is fully compatible.

**Core technologies:**
- **WorkManager 2.11.0** (androidx.work:work-runtime-ktx): Periodic maintenance during Doze — replaces Transport.kt job loop polling, executes in maintenance windows
- **Kotlin Coroutines 1.8.0+**: Async I/O and lifecycle management — already in use, needs scope injection from Android service lifecycle
- **Foreground Service (built-in)**: Persistent connection container — use `connectedDevice` type (not dataSync) to avoid 6-hour limit on Android 15+
- **PowerManager (built-in)**: Battery exemption and Doze detection — required for mesh networking, justified under Play Store policy
- **ConnectivityManager.NetworkCallback (built-in)**: Network state monitoring — detect WiFi/cellular transitions, VPN changes, airplane mode

**Critical version/configuration notes:**
- **minSdk 26, targetSdk 34**: All required APIs available, foreground service restrictions in effect
- **BouncyCastle**: Use bcprov-jdk18on:1.77 variant (Android compatible)
- **Service type**: `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (requires FOREGROUND_SERVICE_CONNECTED_DEVICE permission in manifest)
- **Notification importance**: IMPORTANCE_LOW minimum (not MIN — prevents service termination)

### Expected Features

**Must have (table stakes):**
- **Background message delivery** — messages arrive while backgrounded/screen off/in Doze mode. Standard behavior since Android 2.x; any messaging app that doesn't deliver in background feels broken. Implementation: foreground service + Doze maintenance windows
- **Persistent notification** — required by Android for foreground services. Must show connection status, be tappable, include "Stop service" action. Cannot be hidden (Android 8.0+ enforcement)
- **Connection persistence** — maintain TCP/UDP connections during sleep, screen-off, backgrounding. Requires foreground service + network state monitoring + automatic reconnection with exponential backoff
- **Offline message queue** — queue outgoing messages when network unavailable, send when connectivity returns. Users expect "send" to work offline. Implementation: Room database + WorkManager retry
- **Conversation notifications** — MessagingStyle, notification channels, direct reply. Android 8.0+ standard; users expect rich notification UX
- **Battery efficiency** — <5% per 8 hours idle. Google Play policy (March 2026) enforces wake lock thresholds; apps exceeding limits get warnings/removal

**Should have (competitive differentiators):**
- **True offline mesh networking** — messages route through nearby devices without internet. Reticulum's core value proposition; competitive advantage vs Signal/WhatsApp
- **Transparent network transition** — maintain conversation context during WiFi/cellular/mesh transitions without disrupting UI
- **Propagation node sync status** — show "queued locally" vs "stored at propagation node" vs "delivered" states (unique to Reticulum architecture)
- **Resource transfer progress** — show progress for large file transfers in notification and UI

**Defer (anti-features to avoid):**
- **Battery optimization exemption auto-request** — Google Play policy violation unless core function breaks. Instead: design to work within constraints, document manual exemption for users experiencing issues
- **Auto-start on boot without user action** — surprising behavior, battery drain. Instead: restore service only if user previously enabled it
- **Permanent wake locks** — massive battery drain, Play Store violation. Instead: acquire only during active operations with timeouts
- **Polling for messages** — inefficient, delayed delivery. Instead: persistent connection with event-driven delivery

### Architecture Approach

The existing JVM architecture (Transport, Interface, LXMRouter with coroutines) is fundamentally sound and should remain platform-agnostic for testability. Integration requires Android-aware wrapper layers that inject lifecycle management without leaking Android dependencies into core modules. Pattern: optional CoroutineScope injection in core modules (defaults to standalone for JVM), with Android service injecting lifecycleScope for automatic cancellation. State observation via callbacks and StateFlow (not direct Android API usage in core).

**Major components:**
1. **ReticulumService (Android layer)** — LifecycleService wrapper that manages foreground service, Doze detection, network monitoring. Injects lifecycleScope into Transport and InterfaceManager. Already partially implemented (needs Doze observer, scope injection refinement)
2. **ReticulumWorker (NEW - Android layer)** — CoroutineWorker for Doze-surviving maintenance. Runs every 15 minutes with network constraints, performs Transport path table cleanup and announce processing. Referenced but not implemented
3. **Lifecycle observers (NEW - Android layer)** — DozeStateObserver (broadcasts Doze state via StateFlow), NetworkStateObserver (monitors WiFi/cellular/VPN), BatteryOptimizationChecker (warns users about restrictions). Enables core modules to react without Android coupling
4. **Transport (core - JVM)** — Accepts external CoroutineScope via configureCoroutineJobLoop() (already implemented). Needs Doze state awareness to adjust intervals, no code changes required
5. **Interface layer (core - JVM)** — TCPClientInterface/UDPInterface need optional parentScope parameter for lifecycle integration. Currently create own scope (works for JVM, leaks on Android service shutdown)
6. **LXMRouter (core - JVM)** — Needs service integration for message queue persistence (storagePath already configured). WorkManager for delivery retries during Doze

**Integration pattern:**
```
Application Scope
  └── Service Scope (ReticulumService.lifecycleScope)
       ├── Transport Job Loop (injected)
       ├── InterfaceManager (passed from service)
       │    └── Interface I/O Scopes (child scopes)
       └── LXMRouter Processing (if integrated)
```

### Critical Pitfalls

1. **Wrong foreground service type (dataSync)** — Android 15+ imposes 6-hour cumulative time limit on dataSync services. After 6 hours, service crashes with RemoteServiceException. Solution: use `connectedDevice` type for always-on connections, declare in manifest with corresponding permission. Test service running >6 hours.

2. **Assuming foreground services bypass Doze restrictions** — Network access is completely suspended during deep Doze despite foreground service running. TCP connections silently close, reconnection attempts fail. Solution: design for connection loss as default state, implement exponential backoff, use setAndAllowWhileIdle() alarms (max once per 9 minutes), monitor ConnectivityManager.NetworkCallback.

3. **Notification channel importance too low** — Android 13+ allows users to dismiss foreground service notifications. Low importance makes notifications "invisible," users dismiss without understanding consequences, killing service. Solution: use IMPORTANCE_DEFAULT or HIGH, clear notification text explaining persistence requirement, implement deleteIntent to detect dismissal and recreate.

4. **Ignoring OEM-specific battery killers** — Xiaomi (autostart + background autostart permissions), Huawei (protected apps), Samsung (sleeping apps) override standard Android behavior, killing services despite proper configuration. Solution: manufacturer detection at runtime, device-specific onboarding instructions, in-app troubleshooting wizard, test on physical devices from each major OEM.

5. **Not testing Doze mode transitions** — ADB connection prevents Doze activation. Developers test while connected, miss production connection drops. Solution: use `adb shell dumpsys deviceidle force-idle` to test Doze behavior, test all App Standby buckets (especially restricted), run 24+ hour soak tests, test with battery saver enabled.

## Implications for Roadmap

Based on research, the work naturally divides into 8 phases following a layered integration strategy: foundation (lifecycle observers) → scope management → Doze adaptation → WorkManager persistence → service type validation → LXMF integration → battery UX → memory optimization.

### Phase 1: Android Lifecycle Foundation
**Rationale:** Establishes Doze and network state observation infrastructure. All subsequent phases depend on this foundation for detecting when to adapt behavior (Doze mode, network transitions). Low-risk, high-visibility improvements.

**Delivers:** DozeStateObserver, NetworkStateObserver, BatteryOptimizationChecker integrated into ReticulumService

**Addresses:** Critical Pitfall #2 (Doze detection), Critical Pitfall #4 (OEM detection setup)

**Avoids:** Building features that don't respect Android power states

**Research flags:** Standard patterns, skip phase research. Official Android documentation covers all APIs.

### Phase 2: Lifecycle-Aware Scope Injection
**Rationale:** Enables proper cancellation when service stops, preventing leaks. Must come before Doze handling (which adjusts scope-managed jobs). Requires careful JVM compatibility testing.

**Delivers:** TCPClientInterface/UDPInterface accept optional parentScope, InterfaceManager passes service scope, Transport uses injected scope from ReticulumService

**Addresses:** Table stakes feature "Connection persistence" (proper lifecycle management)

**Uses:** Kotlin Coroutines with lifecycle integration

**Implements:** Architecture component "Transport accepts external scope" (already 90% done)

**Avoids:** Memory leaks, uncancelled background jobs, service restart storms

**Research flags:** Standard coroutine patterns, skip phase research. Well-documented in Kotlin docs.

### Phase 3: Doze-Aware Connection Management
**Rationale:** Reduces battery drain during idle periods. Depends on Phase 1 (Doze observer) and Phase 2 (scope management for job scheduling). Highest impact on battery life.

**Delivers:** Transport job loop adjusts intervals during Doze, Interface reconnection backoff increases during Doze, ConnectivityManager.NetworkCallback integration

**Addresses:** Table stakes "Battery efficiency" (<5% per 8 hours), Critical Pitfall #2 (Doze restrictions)

**Uses:** DozeStateObserver (Phase 1), injected scopes (Phase 2)

**Avoids:** Critical Pitfall #2 (assuming Doze doesn't affect foreground services), excessive wake locks

**Research flags:** Needs testing-focused phase research. Doze behavior varies by OEM; document test matrix (Samsung, Xiaomi, Huawei, OnePlus, Google Pixel).

### Phase 4: WorkManager Integration
**Rationale:** Survives deep Doze by executing maintenance during system-scheduled windows. Depends on Phase 1 (state observers) for intelligent scheduling. Replaces current 250ms polling with 15-minute intervals.

**Delivers:** ReticulumWorker implemented (periodic maintenance), Transport maintenance API (path table cleanup, announce processing), WorkManager scheduling in ReticulumService

**Addresses:** Architecture gap "ReticulumWorker missing implementation"

**Uses:** WorkManager 2.11.0 (from stack research)

**Implements:** Architecture component "WorkManager for Transport nodes"

**Avoids:** Battery drain from continuous polling, job quota exhaustion (Android 16+)

**Research flags:** Standard WorkManager patterns, skip phase research. Official documentation sufficient.

### Phase 5: Service Type Evaluation & Migration
**Rationale:** Determines if current dataSync type needs replacement (6-hour limit on Android 15+). Depends on Phases 1-4 being complete for accurate long-running testing. Critical for 24/7 operation.

**Delivers:** Service type decision (dataSync vs connectedDevice), manifest updates, runtime type selection logic, 24+ hour service uptime validation

**Addresses:** Critical Pitfall #1 (wrong service type), differentiator "True offline mesh networking" (requires always-on)

**Uses:** Foreground service built-in APIs (from stack)

**Implements:** Architecture component "ReticulumService type configuration"

**Avoids:** Service crashes after 6 hours, user-facing connection drops

**Research flags:** Needs phase research for service type tradeoffs. Limited documentation on connectedDevice use cases for P2P networking (not traditional Bluetooth/USB).

### Phase 6: LXMF Message Layer Integration
**Rationale:** Adds end-to-end messaging on top of stable connection foundation. Depends on Phases 1-5 for reliable background operation. Unlocks user-facing messaging features.

**Delivers:** LXMRouter initialized in ReticulumService, message queue persistence (Room database), WorkManager for delivery retries, service binder API for app access

**Addresses:** Table stakes "Offline message queue", differentiator "Propagation node sync status"

**Uses:** LXMRouter (core module), WorkManager (Phase 4)

**Implements:** Architecture component "LXMRouter integration with service"

**Avoids:** Message loss during service restart, delivery failures during Doze

**Research flags:** Skip phase research. LXMRouter already implemented in core; integration is straightforward service initialization.

### Phase 7: Battery Optimization UX
**Rationale:** Helps users understand and resolve battery restrictions. Depends on all core functionality working (Phases 1-6) so users see value before being asked for exemption. Play Store compliance critical.

**Delivers:** BatteryOptimizationChecker UI, exemption request flow with explanation, diagnostics screen (Doze state, network state, battery state), manufacturer-specific guidance (Samsung/Xiaomi/Huawei)

**Addresses:** Critical Pitfall #4 (OEM-specific issues), Critical Pitfall #9 (exemption UX), differentiator "Connection resilience metrics"

**Uses:** BatteryOptimizationChecker (Phase 1), DozeStateObserver (Phase 1)

**Implements:** User-facing battery management tools

**Avoids:** Play Store policy violations, user distrust, negative reviews

**Research flags:** Needs phase research for OEM-specific deep-link intents. Manufacturer settings paths change frequently; verify current paths for 2026 devices.

### Phase 8: Memory Optimization & Long-Term Stability
**Rationale:** Ensures service survives memory pressure and multi-day operation. Depends on complete implementation (Phases 1-7) for realistic memory profiling. Final production readiness step.

**Delivers:** Enhanced Transport.trimMemory() implementation, periodic memory trim in WorkManager, ByteArrayPool monitoring, memory leak detection (LeakCanary), 24+ hour soak test validation

**Addresses:** Architecture gap "Memory footprint for long-running service"

**Uses:** ReticulumService.onTrimMemory() (already implemented), ReticulumWorker (Phase 4)

**Implements:** Architecture component "Memory optimization"

**Avoids:** OOM kills, service restarts, degraded performance over time

**Research flags:** Standard profiling techniques, skip phase research. Android Studio Memory Profiler + LeakCanary cover all needs.

### Phase Ordering Rationale

- **Foundation first (Phases 1-2):** Lifecycle infrastructure must exist before adapting behavior. Doze observers and scope management are dependencies for all subsequent work.
- **Doze handling before WorkManager (Phases 3-4):** Connection management needs Doze awareness before adding WorkManager persistence. Testing connection resilience validates WorkManager is necessary.
- **Service type after stable foundation (Phase 5):** Can't accurately test 6-hour service behavior until connection management is solid. Need baseline before changing service type.
- **LXMF after connection stability (Phase 6):** Messaging layer requires reliable background connectivity. No point integrating LXMRouter until TCP/UDP interfaces survive Doze.
- **UX and optimization last (Phases 7-8):** Need working system before helping users configure it. Memory profiling requires complete implementation to identify real leaks.

**Dependency chain:**
```
Phase 1 (observers) ──┬──> Phase 3 (Doze handling) ──> Phase 5 (service type)
                      │
Phase 2 (scopes) ─────┴──> Phase 4 (WorkManager) ────> Phase 6 (LXMF) ──> Phase 7 (UX) ──> Phase 8 (memory)
```

### Research Flags

**Phases needing deeper research during planning:**
- **Phase 3 (Doze handling):** OEM-specific Doze behavior varies; create test matrix for Samsung, Xiaomi, Huawei, OnePlus, Google Pixel. Document expected vs actual behavior per manufacturer.
- **Phase 5 (Service type):** Limited documentation on `connectedDevice` type for P2P mesh networking (most examples are Bluetooth/USB companions). Research alternative apps using this type, verify Play Store acceptance.
- **Phase 7 (Battery UX):** Manufacturer settings deep-link intents change frequently. Research current paths for One UI 6.x, MIUI 14+, EMUI/HarmonyOS, OxygenOS, ColorOS. Verify `dontkillmyapp.com` accuracy for 2026.

**Phases with standard patterns (skip research-phase):**
- **Phase 1:** DozeStateObserver, NetworkStateObserver — official Android APIs, well-documented
- **Phase 2:** Coroutine scope injection — Kotlin coroutines best practices, established patterns
- **Phase 4:** WorkManager — official Jetpack library, comprehensive documentation
- **Phase 6:** LXMRouter integration — internal API, no external research needed
- **Phase 8:** Memory profiling — Android Studio tooling, standard techniques

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All recommendations from official Android docs (Jan 2026), verified WorkManager version, confirmed BouncyCastle Android compatibility |
| Features | HIGH | Feature landscape based on official Android requirements (foreground service notifications, Doze constraints) and established messaging app patterns (Signal, WhatsApp behavior) |
| Architecture | HIGH | Integration strategy preserves JVM testability (verified with existing codebase structure), lifecycle patterns from official Kotlin/Android docs, WorkManager usage well-documented |
| Pitfalls | HIGH | Critical pitfalls from official Android documentation (service types, Doze restrictions, Android 14-16 changes), OEM issues from dontkillmyapp.com (maintained 2024-2026) + manufacturer forums |

**Overall confidence:** HIGH

Research based on authoritative sources: official Android documentation (updated Jan 2026), verified Kotlin coroutines best practices, WorkManager 2.11.0 release notes, dontkillmyapp.com OEM tracking. All stack recommendations use first-party APIs with known compatibility. Architecture preserves existing JVM module boundaries (verified by reading actual codebase structure).

### Gaps to Address

- **Service type validation:** `connectedDevice` type recommended for always-on connections, but limited examples of P2P mesh networking use cases. Most documentation shows Bluetooth/USB companion devices. **Mitigation:** Implement Phase 5 as spike to validate Android accepts this type for network connections; fallback to dataSync + 6-hour restart cycle if rejected.

- **OEM-specific Doze behavior variance:** Research indicates major differences between Samsung (improved in One UI 6.0), Xiaomi (aggressive killing), Huawei (ranked #1 problematic). Exact behavior on 2026 devices unknown until tested. **Mitigation:** Phase 3 includes physical device testing on target manufacturers; document workarounds per OEM; provide user guidance for unsupported configurations.

- **Battery exemption Play Store policy interpretation:** Research confirms mesh networking qualifies for exemption, but Play Store review is subjective. **Mitigation:** Design Phases 1-6 to work without exemption (degraded but functional); Phase 7 requests exemption with clear justification; prepare policy compliance documentation for review.

- **Android 16+ job quota impact:** Research shows WorkManager jobs during foreground service now count toward quota (Android 16 change). Quota limits vary by App Standby bucket. **Mitigation:** Phase 4 testing includes restricted bucket validation; monitor WorkInfo.getStopReason() for quota exhaustion; adjust worker intervals if needed.

- **LXMRouter service integration details:** LXMRouter exists in lxmf-core but sample app doesn't show service integration. Unclear if API changes needed for Android lifecycle. **Mitigation:** Phase 6 design includes API review; LXMRouter likely needs scope parameter (same pattern as Interface layer); low risk given existing architecture.

## Sources

### Primary (HIGH confidence)

**Official Android Documentation (Jan 2026):**
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) — Network restrictions, maintenance windows, battery exemption policy
- [Foreground Service Types](https://developer.android.com/develop/background-work/services/fgs/service-types) — connectedDevice vs dataSync requirements, permission declarations
- [Foreground Service Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) — 6-hour dataSync limit (Android 15+), onTimeout() behavior
- [Changes to Foreground Services](https://developer.android.com/develop/background-work/services/fgs/changes) — Android 14-16 restriction timeline
- [WorkManager Releases](https://developer.android.com/jetpack/androidx/releases/work) — Version 2.11.0 features, CoroutineWorker patterns
- [Best Practices for Coroutines in Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) — Lifecycle scope integration, structured concurrency
- [App Standby Buckets](https://developer.android.com/topic/performance/appstandby) — Bucket restrictions, network access policies
- [Notification Runtime Permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) — POST_NOTIFICATIONS requirement (Android 13+)

**Reticulum Protocol:**
- [Reticulum Network Stack Manual v1.1.3](https://reticulum.network/manual/Reticulum%20Manual.pdf) — Transport layer, announce mechanism, path table management
- [LXMF Protocol](https://github.com/markqvist/LXMF) — Propagation nodes, opportunistic delivery, message queue design

### Secondary (MEDIUM confidence)

**OEM Battery Optimization (2024-2026):**
- [Don't Kill My App](https://dontkillmyapp.com/) — Manufacturer rankings (Huawei #1, Xiaomi #2, OnePlus #3), device-specific workarounds
- [Samsung - Don't Kill My App](https://dontkillmyapp.com/samsung) — One UI 6.0 improvements, "Never sleeping apps" requirement
- [Xiaomi - Don't Kill My App](https://dontkillmyapp.com/xiaomi) — Autostart + background autostart permissions (MIUI 14+), battery saver override

**Community Best Practices:**
- [Foreground Service vs WorkManager in Android (Medium, 2024)](https://medium.com/@amar90aqi/foreground-service-vs-workmanager-in-android-choosing-the-right-tool-for-background-tasks-32c1242f9898) — Decision matrix for service type selection
- [Understanding Foreground Services in Android (Medium, 2024)](https://medium.com/@RhoumaMaher/understanding-and-implementing-foreground-services-in-android-2e1e3fc234ce) — Notification channel setup, importance levels
- [Offline-First Android with Room, WorkManager (Medium, 2024)](https://medium.com/@vishalpvijayan4/offline-first-android-build-resilient-apps-for-spotty-networks-with-room-datastore-workmanager-4a23144e8ea2) — Message queue patterns

**Google Policy Updates:**
- [Excessive Wake Lock Policy (Android Developers Blog, Nov 2025)](https://android-developers.googleblog.com/2025/11/raising-bar-on-battery-performance.html) — March 1, 2026 enforcement date, Play Store consequences
- [Wake Lock Optimization Guide (Android Developers Blog, Sep 2025)](https://android-developers.googleblog.com/2025/09/guide-to-excessive-wake-lock-usage.html) — Threshold definitions, monitoring tools

### Tertiary (LOW confidence, needs validation)

**OEM-Specific Workarounds:**
- [How to Manage Autostart Service on Xiaomi Devices](https://nine-faq.9folders.com/articles/8772-how-to-manage-autostart-service-on-the-xiaomi-devices) — Settings paths (may change with MIUI updates)
- [Xiaomi Phone Closes Background Apps? Fix Guide](https://appuals.com/xiaomi-phone-closes-background-apps/) — User-reported solutions (not official documentation)
- [How to Run Background Service in Xiaomi Mobiles](https://www.freakyjolly.com/how-to-run-background-service-in-xiaomi-mobiles/) — Developer workarounds (community-sourced)

**Technical Discussions:**
- [Qt Forum: Keep-Alive Messages in Doze Mode](https://forum.qt.io/topic/90939/sending-keep-alive-messages-on-android-even-if-the-device-is-in-sleep-doze-mode) — Real-world Doze behavior reports
- [Microsoft Q&A: WebSocket Connection Stability Issues](https://learn.microsoft.com/en-us/answers/questions/2281522/intermittent-availability-issues-with-acs-sdk-in-a) — Network suspension during Doze

---
*Research completed: 2026-01-24*
*Ready for roadmap: yes*
