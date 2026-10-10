# AntiProxy Security & High-Demand Performance Remediation

This directory contains in-depth technical documentation, physical RF calculations, concurrency models, and code comparisons for the Priority 1 remediations implemented in AntiProxy.

## Index of Documentation

1. [High-Demand Throughput, BLE Physics, and Slot Scheduling](01_HIGH_DEMAND_THROUGHPUT_AND_PHYSICS.md)
   - RF physics, 2.4 GHz channel contention, and hardware connection limits (4–7 channels).
   - Mathematical proof comparing the 20-second window ($\rho = 1.875$, unstable) vs. 60-second window ($\rho = 0.625$, stable).
   - Slot scaling (`NUM_SLOTS = 600`, 100 ms slots) and uniform random distribution.
   - Bounded exponential backoff and decorrelated jitter algorithm in `RetryScheduler`.
   - Intuitive classroom arrival scenarios and auto-recovery walkthroughs.

2. [GATT Server Concurrency, Channel Recycling, and Binder Pipeline](02_GATT_SERVER_CONCURRENCY.md)
   - Android Bluetooth IPC Binder architecture and the thread-blocking hazard.
   - Non-blocking asynchronous verification and SQLite WAL commit pipeline using `Dispatchers.IO`.
   - Central channel recycling via active 200 ms grace period disconnections.
   - Periodic 2-second zombie connection reaper (`cleanupStaleConnections`).
   - `onServiceAdded` asynchronous synchronization preventing GATT discovery race conditions.
   - API 33+ thread-safe indications protecting against characteristic clobbering.
   - Transfer ID randomization and clean client lifecycle across retries.

---

## Summary of Code Modifications (Priority 1)

| File | Change | Security & Performance Impact |
|------|--------|-------------------------------|
| `core/src/commonMain/kotlin/antiproxy/core/protocol/Types.kt` | Increased `NUM_SLOTS` from 200 to 600; increased `DEFAULT_VERIFICATION_WINDOW_SECONDS` to 60; added `GattTransportException`. | Reduces peak channel demand from 7.5/s to 2.5/s, stabilizing queue intensity at $\rho = 0.625$. Enables retry classification. |
| `app/src/main/kotlin/org/ap/protocol/ble/StudentGattClient.kt` | Randomized `transferId` in `writeProofEnvelope`. | Eliminates stale fragment collisions in GATT server reassembly table. |
| `app/src/main/kotlin/org/ap/protocol/flow/StudentFlow.kt` | Throws `GattTransportException` on transient drops; catches it to execute backoff retry; instantiates clean `StudentGattClient` per attempt. | Eliminates silent single-failure drops. Provides self-healing attendance for students experiencing transient RF interference. |
| `app/src/main/kotlin/org/ap/protocol/ble/GattServerCallback.kt` | Offloaded verification & DB writes to `Dispatchers.IO`; thread-safe indications; added `onServiceAddedListener`, `disconnectClient`, and `cleanupStaleConnections`. | Unblocks Android Bluetooth IPC Binder thread; prevents indication value corruption across concurrent centrals. |
| `app/src/main/kotlin/org/ap/protocol/ble/AuthorityAdvertiser.kt` | Suspends `start()` until `onServiceAdded` completes; exposes `disconnectClient` and `cleanupStaleConnections`; cleanly cancels on `stop()`. | Eliminates service discovery race conditions when students connect immediately upon advertising start. |
| `app/src/main/kotlin/org/ap/protocol/flow/AuthorityFlow.kt` | Added `sessionScope`, automatic client disconnection after 200 ms ACK delay, and a 2s periodic reaper for stale connections (>5s). | Reclaims limited GATT central connection handles (4–7 max) in ~200 ms, keeping channels available for remaining students. |
| `core/src/jvmTest/kotlin/antiproxy/core/scheduling/RetrySchedulerTest.kt` | Added test cases for backoff bounds across all retry counts and `GattTransportException` hierarchy. | 100% test coverage for retry mechanics. |
