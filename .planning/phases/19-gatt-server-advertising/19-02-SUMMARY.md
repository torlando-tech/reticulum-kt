---
phase: 19-gatt-server-advertising
plan: 02
subsystem: ble-peripheral
tags: [ble, advertising, gatt, operation-queue, coroutines]
depends_on:
  requires: [18-01, 18-02]
  provides: [BleAdvertiser, BleOperationQueue, BleOperationTimeoutException]
  affects: [19-03, 20-01, 20-02, 21-01]
tech-stack:
  added: []
  patterns: [channel-based-queue, suspend-serialization, periodic-refresh]
key-files:
  created:
    - rns-interfaces/src/main/kotlin/network/reticulum/interfaces/ble/BleOperationQueue.kt
    - rns-interfaces/src/test/kotlin/network/reticulum/interfaces/ble/BleOperationQueueTest.kt
    - rns-android/src/main/kotlin/network/reticulum/android/ble/BleAdvertiser.kt
  modified: []
decisions:
  - id: "19-02-01"
    decision: "BleOperationQueue uses Channel<Operation> with single consumer coroutine"
    rationale: "Simpler than Mutex-based serialization; Channel guarantees FIFO; single consumer guarantees serial execution"
  - id: "19-02-02"
    decision: "No scan response data in advertising payload"
    rationale: "CONTEXT.md specifies minimal payload (service UUID only), no device name needed"
  - id: "19-02-03"
    decision: "Linear backoff (2s, 4s, 6s) for advertising retry instead of exponential"
    rationale: "BLE advertising failures are typically transient OEM issues; aggressive retry is appropriate"
metrics:
  duration: "3min"
  completed: "2026-02-06"
---

# Phase 19 Plan 02: BLE Advertising and Operation Queue Summary

**One-liner:** BLE advertiser with service UUID discovery, 60s refresh, OEM resilience + generic GATT operation queue (pure JVM)

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Implement BleOperationQueue.kt | 811317b | BleOperationQueue.kt, BleOperationQueueTest.kt |
| 2 | Implement BleAdvertiser.kt | 38448d5 | BleAdvertiser.kt |

## What Was Built

### BleOperationQueue (rns-interfaces, pure JVM)

Generic suspend-based operation queue that serializes execution via `Channel<Operation>`. A single consumer coroutine processes operations one at a time in FIFO order. Each operation gets its own `CompletableDeferred` for result delivery and configurable per-operation timeout.

Key design: The queue is completely generic -- it takes `suspend () -> T` blocks, not BLE-specific types. This means Phase 20's GATT client can use it for write/read serialization without the queue knowing anything about Android BLE APIs.

**Public API:**
- `enqueue(timeoutMs, block)` -- suspend until operation completes
- `shutdown()` -- close channel, cancel scope

**8 unit tests covering:**
- FIFO execution order
- Serialization (no interleaving)
- Timeout with BleOperationTimeoutException
- Custom timeout override
- Exception propagation
- Queue continues after failure
- Concurrent enqueue correctness
- Type-safe return values

### BleAdvertiser (rns-android)

BLE advertising lifecycle manager for peer discovery. Advertises the Reticulum service UUID (`37145b00-442d-4a94-917f-8f42c5da28e3`) with minimal payload (no device name, no TX power level).

**Key features:**
- **ADV-01:** Connectable advertising with service UUID, indefinite timeout
- **ADV-02:** `restartIfNeeded()` for OEM auto-stop resilience (called by BleGattServer on connection state change)
- **ADV-03:** 60-second periodic refresh timer -- stops and restarts advertising to combat background kill
- **Configurable mode:** `AdvertiseMode` enum (LOW_POWER ~1000ms, BALANCED ~250ms, LOW_LATENCY ~100ms)
- **Retry:** Linear backoff (2s, 4s, 6s) up to 3 attempts on advertising failure
- **State:** `StateFlow<Boolean>` for advertising state observation
- **Permissions:** BLUETOOTH_ADVERTISE check on API 31+, `isMultipleAdvertisementSupported` check

## Deviations from Plan

None -- plan executed exactly as written.

## Decisions Made

1. **Channel-based queue over Mutex** -- The plan suggested Channel + Mutex + withTimeout. In practice, Channel alone provides FIFO ordering and the single consumer coroutine provides serialization. Mutex was unnecessary. withTimeout is used per-operation.

2. **No scan response data** -- Columba's BleAdvertiser includes scan response with device name. Per CONTEXT.md's "minimal advertising payload -- service UUID only", scan response was omitted entirely.

3. **Linear backoff for retry** -- Plan specified linear backoff (2s, 4s, 6s). Columba uses the same pattern. Kept as specified since BLE advertising failures are typically transient.

## Next Phase Readiness

Phase 19 Plan 02 deliverables are ready for:
- **Phase 19 Plan 03 (if exists):** BleGattServer can call `BleAdvertiser.restartIfNeeded()` on connection state change
- **Phase 20:** BleOperationQueue is ready for GATT client write/read serialization
- **Phase 21:** BleAdvertiser.startAdvertising/stopAdvertising integrates into BLEInterface orchestration
