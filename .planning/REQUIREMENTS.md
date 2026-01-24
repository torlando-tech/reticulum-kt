# Requirements: Reticulum-KT LXMF Interop

**Defined:** 2026-01-23
**Core Value:** Perfect byte-level interoperability with Python LXMF

## v1 Requirements

Requirements for LXMF interoperability certification. Each maps to roadmap phases.

### LXMF Message Interop

- [ ] **LXMF-01**: Kotlin-packed LXMessage unpacks correctly in Python (round-trip verified)
- [ ] **LXMF-02**: Python-packed LXMessage unpacks correctly in Kotlin (enhance existing tests)
- [ ] **LXMF-03**: FIELD_FILE_ATTACHMENTS survives Kotlin↔Python round-trip
- [ ] **LXMF-04**: FIELD_IMAGE survives Kotlin↔Python round-trip
- [ ] **LXMF-05**: Custom fields (renderer, thread ID, etc.) survive round-trip
- [ ] **LXMF-06**: Message hash computed identically in Kotlin and Python
- [ ] **LXMF-07**: Message signature validates across implementations

### Stamp/Ticket Interop

- [ ] **STAMP-01**: Kotlin-generated stamp accepted by Python propagation node
- [ ] **STAMP-02**: Python-generated stamp validates in Kotlin
- [ ] **STAMP-03**: Stamp difficulty level matches Python computation

### End-to-End Delivery

- [ ] **E2E-01**: DIRECT delivery Kotlin→Python over established Link
- [ ] **E2E-02**: DIRECT delivery Python→Kotlin over established Link
- [ ] **E2E-03**: OPPORTUNISTIC delivery when path available
- [ ] **E2E-04**: PROPAGATED delivery via Python propagation node

### Resource Transfer (Large Messages)

- [ ] **RES-01**: Large LXMF message (>500 bytes) transfers as Resource correctly
- [ ] **RES-02**: Resource transfer with compression matches Python output
- [ ] **RES-03**: Resource chunking and reassembly interoperates

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Android Optimization

- **ANDROID-01**: Doze mode handling with foreground service
- **ANDROID-02**: WorkManager integration for background scheduling
- **ANDROID-03**: Battery optimization exemption flow
- **ANDROID-04**: Hardware-accelerated crypto on Android
- **ANDROID-05**: Memory footprint optimization (<50MB peak)

### Additional Interfaces

- **IFACE-01**: Auto Interface (mDNS peer discovery)
- **IFACE-02**: IFAC support for network isolation

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Hosting LXMF propagation nodes | Client-only scope for Columba integration |
| Serial interface | Not needed for TCP/UDP mesh connectivity |
| RNode/LoRa interface | Not needed for Columba use case |
| BLE interface | Not needed for Columba use case |
| I2P interface | Not needed for Columba use case |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| LXMF-01 | TBD | Pending |
| LXMF-02 | TBD | Pending |
| LXMF-03 | TBD | Pending |
| LXMF-04 | TBD | Pending |
| LXMF-05 | TBD | Pending |
| LXMF-06 | TBD | Pending |
| LXMF-07 | TBD | Pending |
| STAMP-01 | TBD | Pending |
| STAMP-02 | TBD | Pending |
| STAMP-03 | TBD | Pending |
| E2E-01 | TBD | Pending |
| E2E-02 | TBD | Pending |
| E2E-03 | TBD | Pending |
| E2E-04 | TBD | Pending |
| RES-01 | TBD | Pending |
| RES-02 | TBD | Pending |
| RES-03 | TBD | Pending |

**Coverage:**
- v1 requirements: 17 total
- Mapped to phases: 0
- Unmapped: 17 ⚠️

---
*Requirements defined: 2026-01-23*
*Last updated: 2026-01-23 after initial definition*
