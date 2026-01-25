# Requirements: Reticulum-KT v2 Android Production Readiness

**Defined:** 2026-01-24
**Core Value:** Always-connected background operation that survives Doze and battery optimization without excessive drain

## v2 Requirements

Requirements for Android production readiness. Each maps to roadmap phases.

### Background Connectivity

- [ ] **CONN-01**: Foreground service keeps TCP/UDP connections alive when app backgrounded
- [ ] **CONN-02**: Connection survives Doze mode via maintenance window scheduling
- [ ] **CONN-03**: Automatic reconnection after network changes (WiFi ↔ cellular, offline → online)
- [ ] **CONN-04**: Works on API 26+ (Android 8.0 Oreo and later)
- [ ] **CONN-05**: Connection persists across full device sleep/wake cycles
- [ ] **CONN-06**: Sub-second message delivery even when app is backgrounded

### Battery Management

- [ ] **BATT-01**: Battery drain <2% per hour while maintaining connection
- [ ] **BATT-02**: Battery optimization exemption request flow (guides user through settings)
- [ ] **BATT-03**: WorkManager integration for Doze-surviving periodic tasks (15-minute windows)
- [ ] **BATT-04**: Intelligent connection throttling during low battery (<15%)
- [ ] **BATT-05**: Battery usage statistics visible to user in app

### OEM Compatibility

- [ ] **OEM-01**: Works on Samsung devices (handles "sleeping apps" restriction)
- [ ] **OEM-02**: Works on Xiaomi/MIUI devices (autostart permission handling)
- [ ] **OEM-03**: Works on Huawei/EMUI devices (protected apps handling)
- [ ] **OEM-04**: Automatic OEM detection with manufacturer-specific guidance
- [ ] **OEM-05**: Deep-link to OEM-specific battery settings screens

### Service Infrastructure

- [ ] **SERV-01**: Foreground service with correct type (`connectedDevice` for P2P mesh)
- [ ] **SERV-02**: Notification channel with user controls (importance, sound, vibration)
- [ ] **SERV-03**: Lifecycle-aware coroutine management (proper cancellation on service stop)
- [ ] **SERV-04**: Connection status displayed in persistent notification
- [ ] **SERV-05**: Quick actions in notification (reconnect, pause connection)

### Memory & Stability

- [ ] **MEM-01**: Memory footprint <50MB peak during normal operation
- [ ] **MEM-02**: No memory leaks during multi-day operation
- [ ] **MEM-03**: Graceful handling of low-memory conditions (onTrimMemory)

## Future Requirements

Deferred to v3+. Tracked but not in current roadmap.

### Additional Interfaces

- **IFACE-01**: RNode/LoRa interface for hardware mesh radios
- **IFACE-02**: BLE interface for Bluetooth mesh
- **IFACE-03**: Auto Interface (mDNS peer discovery)

### Advanced Features

- **ADV-01**: IFAC (Interface Authentication)
- **ADV-02**: Multi-interface load balancing
- **ADV-03**: Connection quality metrics and diagnostics

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| FCM/Push notification integration | Reticulum is designed for direct mesh connectivity, not cloud relay |
| iOS support | Android-first; iOS has different background model |
| Hosting propagation nodes | Client-only scope for Columba |
| Custom ROM optimizations | Too fragmented; focus on stock + major OEMs |

## Traceability

Which phases cover which requirements. Populated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CONN-01 | TBD | Pending |
| CONN-02 | TBD | Pending |
| CONN-03 | TBD | Pending |
| CONN-04 | TBD | Pending |
| CONN-05 | TBD | Pending |
| CONN-06 | TBD | Pending |
| BATT-01 | TBD | Pending |
| BATT-02 | TBD | Pending |
| BATT-03 | TBD | Pending |
| BATT-04 | TBD | Pending |
| BATT-05 | TBD | Pending |
| OEM-01 | TBD | Pending |
| OEM-02 | TBD | Pending |
| OEM-03 | TBD | Pending |
| OEM-04 | TBD | Pending |
| OEM-05 | TBD | Pending |
| SERV-01 | TBD | Pending |
| SERV-02 | TBD | Pending |
| SERV-03 | TBD | Pending |
| SERV-04 | TBD | Pending |
| SERV-05 | TBD | Pending |
| MEM-01 | TBD | Pending |
| MEM-02 | TBD | Pending |
| MEM-03 | TBD | Pending |

**Coverage:**
- v2 requirements: 24 total
- Mapped to phases: 0 (pending roadmap creation)
- Unmapped: 24

---
*Requirements defined: 2026-01-24*
*Traceability updated: pending roadmap creation*
