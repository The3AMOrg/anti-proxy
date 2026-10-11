# AntiProxy: System Architecture Specification

## 1. Architectural Philosophy

Project AntiProxy enforces physical classroom attendance verification using a **local-first, peer-to-peer, asymmetric cryptographic model over Bluetooth Low Energy (BLE)**.

Traditional attendance systems suffer from two fatal extremes:
1. **Centralized Cloud Broadcast Systems (e.g., iBeacon / QR Codes)**: The teacher broadcasts a static nonce or displays a QR code. Students screenshot or forward the token over WhatsApp / Discord to absent peers who submit it to a cloud API from their dorms.
2. **Heavy GATT Central/Peripheral Meshes**: Systems where all students maintain persistent Bluetooth connections with the instructor's device. Android devices hardware-throttle peripheral connections to between **4 and 7 concurrent channels**, crashing the Bluetooth stack (`GATT_ERROR 133`) when 80+ students attempt to connect simultaneously.

AntiProxy solves both extremes by decoupling **Proximity Discovery** from **Proof Submission**:
- **Discovery & Evidence Collection**: Fully passive and asynchronous over ambient BLE advertising bursts.
- **Proof Submission**: Ultra-ephemeral point-to-point GATT connection (< 300ms) with immediate channel teardown.

---

## 2. High-Level Subsystem Layout

```
                               ┌────────────────────────────────────────────────────────┐
                               │                    AntiProxy System                    │
                               └────────────────────────────────────────────────────────┘
                                                           │
                      ┌────────────────────────────────────┴────────────────────────────────────┐
                      ▼                                                                         ▼
┌───────────────────────────────────────────┐                             ┌───────────────────────────────────────────┐
│               :core Module                │                             │                :app Module                │
│       (Pure Kotlin Multiplatform)         │                             │            (Android Application)          │
├───────────────────────────────────────────┤                             ├───────────────────────────────────────────┤
│ • Canonical Big-Endian Codecs             │                             │ • Android BLE GATT Engine                 │
│ • ECDSA-P256 Low-S Normalization          │                             │   - StudentScanner (Pre-Connection RSSI)  │
│ • Truncated SHA-256 KeyId Derivation      │◄────────────────────────────┤   - StudentGattClient (High Priority)     │
│ • Strict 13-Step Proof Verification Order │        (depends on)         │   - AuthorityAdvertiser & GattServer      │
│ • Fixed-Point Q1 / Q8.8 RSSI Math         │                             │ • Hardware Keystore (StrongBox / TEE)     │
│ • Slot & Retry Schedulers                 │                             │ • SQLite WAL Database & Atomic Outbox     │
│ • Abstract Storage & Sync Boundaries      │                             │ • Authority Foreground Service + WakeLock │
│ • 42 Unit & Property Tests                │                             │ • Dual-Role Material 3 User Interface     │
└───────────────────────────────────────────┘                             └───────────────────────────────────────────┘
```

---

## 3. Subsystem Breakdown

### 3.1 `:core` Module (Domain & Protocol Invariants)
The `:core` module contains zero Android SDK dependencies (`android.*`). It targets the JVM / KMP common source set:
- **`protocol/`**:
  - `Types.kt`: Normative sizes (133B `SessionSigned`, 121B `ProofEnvelope`, 57B `Proof`, 16B `KeyId`, 16B `SessionId`), UUIDs, and timing constraints.
  - `WireFormat.kt`: Explicit big-endian serialization primitives for 64-bit, 32-bit, and 16-bit unsigned integers.
  - `SessionCodec.kt`: Encodes/decodes the 133-byte `SessionSigned` structure and extracts the 69-byte signed body.
  - `ProofCodec.kt`: Encodes/decodes the 57-byte `Proof` body and 121-byte `ProofEnvelope`.
  - `FragmentCodec.kt`: Chunks payloads larger than the ATT MTU using a 6-byte fragmentation header.
  - `StatusCodes.kt`: 16 standard protocol status bytes (`0x00 SUCCESS` through `0x0F INTERNAL_STORAGE_FAILURE`).
- **`crypto/`**:
  - `SignatureFormat.kt`: Converts between ASN.1 DER and raw IEEE P1363 64-byte ($r[32] \parallel s[32]$) format, enforcing canonical Low-S ($1 \le s \le n/2$).
  - `KeyId.kt`: Derives a compact 16-byte identifier: $\text{KeyId} = \text{SHA-256}(\text{PublicKey})[0..15]$.
  - `Signer.kt` / `Verifier.kt`: Functional contracts for cryptographic signing and scalar verification.
- **`attendance/`**:
  - `ProofValidator.kt`: The Authority's strict 13-step verification pipeline.
  - `SessionValidator.kt`: Student-side verification of the Authority's session signature and temporal validity.
  - `RssiStats.kt`: Deterministic fixed-point arithmetic for Q1 mean (0.5 dBm steps, 0 to −127.5 dBm) and unsigned Q8.8 variance.
  - `ClockGuard.kt`: Wall-clock rollback detection enforcing monotonic time.
  - `IdempotencyKey.kt`: Deduplication hash computation:
    $$H = \text{SHA-256}(\text{"antiproxy-attendance-v1"} \mathbin{\Vert} \text{authority\_device\_id} \mathbin{\Vert} \text{session\_id} \mathbin{\Vert} \text{student\_device\_key\_id})$$

---

### 3.2 `:app` Module (Platform Engine & UI)

#### BLE Engine (`org.ap.protocol.ble`)
1. **`StudentScanner.kt`**: Scans in `SCAN_MODE_LOW_LATENCY` filtered by the AntiProxy service UUID. Collects $N \ge 10$ RSSI samples directly from advertising bursts *before* connecting.
2. **`StudentGattClient.kt`**: Connects via `TRANSPORT_LE`, calls `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` (11.25–15ms intervals), reads session, transmits proof envelope, awaits indication ACK, and disconnects immediately.
3. **`AuthorityAdvertiser.kt`**: Emits BLE discovery packets at 10 Hz (`ADVERTISE_MODE_LOW_LATENCY`) with high power.
4. **`GattServerCallback.kt`**: Implements the Authority's GATT server callback handling Session reads, Proof writes, CCCD indications, and fragment reassembly.

#### Hardware Security (`org.ap.protocol.keystore`)
1. **`AndroidKeyManager.kt`**: Manages hardware-backed NIST P-256 (`secp256r1`) keys in the Android Keystore. Prefers StrongBox (`FEATURE_STRONGBOX_KEYSTORE`) on API 28+ with transparent fallback to TEE.
2. **`AndroidSigner.kt`**: Signs binary digests in hardware, converting the output DER into Low-S normalized 64-byte raw format.
3. **`AndroidVerifier.kt`**: Validates Low-S and scalar bounds using JCA.

#### Persistence (`org.ap.protocol.storage`)
- **`AntiproxyDatabaseHelper.kt`**: Singleton SQLite helper configuring `PRAGMA synchronous=NORMAL` and `enableWriteAheadLogging()`.
- **`AndroidAuthorityStore.kt`**: Implements the Authority ledger. Commits the attendance record and sync outbox event in an atomic transaction *before* dispatching the success ACK to the student.
- **`AndroidStudentStore.kt`**: Persists student enrollment state, paired authority public keys, and local attendance history.

#### Background Protection (`org.ap.protocol.service`)
- **`AuthorityGattService.kt`**: An Android Foreground Service declared with `foregroundServiceType="connectedDevice"`. Holds a CPU `WakeLock` to prevent Android Doze Mode from freezing the BLE advertising stack or GATT server when the instructor locks their device.

---

## 4. End-to-End Attendance Data Flow

```
[Instructor Device (Authority)]                             [Student Device]
              │                                                     │
              │── 1. Start Session (Foreground Service)             │
              │      [Generates SessionSigned: 133B]                │
              │                                                     │
              │── 2. BLE Advertising (10 Hz) ──────────────────────>│
              │      [Service UUID: 9de3c1f4-...]                   │
              │                                                     │
              │                                              3. Passive Scan Burst
              │                                                 Collects 10 RSSI samples
              │                                                 Computes Q1 Mean & Q8.8 Variance
              │                                                     │
              │                                              4. Slot & Backoff Delay
              │                                                 SlotScheduler: 0-20s uniform
              │                                                     │
              │<── 5. Connect GATT (CONNECTION_PRIORITY_HIGH) ──────│
              │                                                     │
              │─── 6. Read SESSION_CHAR (133B) ────────────────────>│
              │                                                     │
              │                                              7. Verify Authority Signature
              │                                                 Validate Expiry & Nonce
              │                                                 Sign Proof (57B) with Keystore
              │                                                 Wrap ProofEnvelope (121B)
              │                                                     │
              │<── 8. Write PROOF_CHAR (121B Envelope) ─────────────│
              │                                                     │
       9. 13-Step Verification Order                                │
          Session lookup & Nonce match                              │
          Student Public Key lookup                                 │
          ECDSA P-256 verify (57B Proof)                            │
          RSSI Mean & Variance policy check                         │
          Atomic SQLite WAL Commit (Record + Outbox)                │
              │                                                     │
              │── 10. Indicate STATUS_CHAR (0x00 SUCCESS) ─────────>│
              │                                                     │
              │                                              11. Commit Record to StudentStore
              │<── 12. Immediate Disconnect (<300ms total) ─────────│
              ▼                                                     ▼
     Channel Freed (Ready for next student)                Attendance Confirmed
```

---

## 5. Failure Modes & Error Recovery

| Failure Condition | Subsystem Handling | Protocol Error Code |
| :--- | :--- | :--- |
| **Clock Rollback Attempt** | `ClockGuard` checks stored monotonic timestamp against system clock; aborts if backwards. | `CLOCK_ROLLBACK_DETECTED` |
| **Tampered / Corrupted Frame** | Frame length $\ne 121$ bytes or invalid version byte rejected before crypto. | `MALFORMED_FRAME (0x01)` |
| **Expired Session** | Submission after `expiresAt` rejected immediately. | `SESSION_EXPIRED (0x04)` |
| **Unenrolled / Unknown Student** | Student `KeyId` not found in Authority local roster cache. | `UNKNOWN_DEVICE (0x06)` |
| **Hallway / Distant Attempt** | RSSI mean below threshold or variance exceeds policy bounds. | `POLICY_REJECTED (0x0B)` |
| **Replay / Duplicate Submission** | Deduplication check finds matching idempotency key in SQLite. | `REPLAY (0x09)` |
| **GATT Channel Congestion** | Exponential backoff with random jitter in `RetryScheduler`. | Retries after backoff delay |
