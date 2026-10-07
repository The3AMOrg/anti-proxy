# AntiProxy: Ultra-Fast Offline BLE Attendance Verification System

**AntiProxy** is a production-grade, local-first academic attendance verification system built for Android. It enforces **verifiable physical presence** without relying on internet access, cloud databases, QR codes, or central server connectivity during attendance sessions.

By pairing **hardware-backed asymmetric cryptography (ECDSA P-256)** with **ultra-ephemeral Bluetooth Low Energy (BLE) GATT exchanges** and **scan-time statistical RSSI gating**, AntiProxy completely eliminates remote proxy attempts (such as students sharing nonces or QR codes over messaging apps).

---

## 🚀 Key Capabilities & Design Innovations

### 1. 100% Offline & Local-First Verification
- Verification occurs directly peer-to-peer between the **Instructor (Authority)** and **Student** devices over BLE.
- Zero reliance on Wi-Fi, cellular networks, DNS, or backend cloud servers inside the classroom.
- Durable local ledger powered by SQLite with **Write-Ahead Logging (WAL)** and an atomic pre-ACK commit invariant.

### 2. Elimination of Remote Proxy / Relay Attacks
- In naive beacon/QR systems, a student in class can text a code or nonce to absent peers in dorms.
- AntiProxy requires the student device to establish a direct local **BLE GATT physical connection** with the instructor's device. If a student is not physically in radio range (~10–15m), they cannot submit attendance.

### 3. Scan-Time RSSI Multi-Sampling
- Conventional BLE attendance systems connect to GATT and sequentially poll RSSI, holding connection channels open for 1,000–1,500ms.
- AntiProxy passively collects $\ge 10$ broadcast advertisement packets over the air *before connecting*. Fixed-point Q1 mean and Q8.8 variance are computed locally, eliminating on-connection polling overhead.

### 4. Ultra-Ephemeral GATT Sessions (< 300ms)
- Android chipsets are hardware-limited to **4–7 concurrent peripheral GATT connections**.
- AntiProxy requests `CONNECTION_PRIORITY_HIGH` (11.25–15ms intervals) immediately upon connection, exchanges binary-packed payloads, receives a 1-byte Status Indication ACK, and calls `disconnect()`.
- Frees connection channels in **< 300ms**, preventing the classic `GATT_ERROR 133` "thundering herd" in classrooms with 100+ students.

### 5. Hardware-Backed Cryptography
- Generates non-exportable NIST P-256 (`secp256r1`) keys in the device's hardware **StrongBox** or **Trusted Execution Environment (TEE)**.
- Enforces raw 64-byte IEEE P1363 ($r \parallel s$) format with strict **Low-S canonicalization** ($1 \le s \le n/2$) to prevent ECDSA signature malleability.

### 6. Authority Foreground Service Protection
- Runs the instructor's GATT server and BLE advertiser inside an Android **Foreground Service** (`foregroundServiceType="connectedDevice"`).
- Holds a CPU `WakeLock` to prevent Android Doze Mode from throttling advertising or freezing GATT callbacks when the instructor locks their screen.

---

## 📁 Repository Structure

```
AntiProxy/
├── core/                                    # Pure Kotlin Multiplatform (KMP) Module
│   ├── src/commonMain/                      # Canonical wire formats, crypto, and verification logic
│   │   ├── protocol/                        # SessionCodec (133B), ProofCodec (121B), FragmentCodec (6B)
│   │   ├── crypto/                          # ECDSA P-256 Low-S, SHA-256 KeyId, Signer/Verifier contracts
│   │   ├── attendance/                      # Strict 13-step ProofValidator, SessionValidator, RssiStats
│   │   ├── scheduling/                      # SlotScheduler (200 slots x 100ms), RetryScheduler
│   │   ├── storage/                         # Storage interfaces & event outbox models
│   │   └── integration/                     # StudentApi & AuthorityApi contracts
│   └── src/jvmTest/                         # 42 unit and property tests (runs on JVM in < 1s)
│
├── app/                                     # Android Application Module (Runnable in Android Studio)
│   ├── src/main/AndroidManifest.xml         # BLE permissions, Foreground Service, Launcher Activity
│   └── src/main/kotlin/org/ap/protocol/
│       ├── ble/                             # StudentScanner (Pre-RSSI), StudentGattClient, AuthorityAdvertiser
│       ├── keystore/                        # AndroidKeyManager (StrongBox/TEE), AndroidSigner, AndroidVerifier
│       ├── storage/                         # SQLite WAL DatabaseFactory, Migrations, Student/Authority Stores
│       ├── service/                         # AuthorityGattService (Foreground Service + WakeLock)
│       ├── flow/                            # AuthorityFlow & StudentFlow orchestrators
│       └── ui/                              # MainActivity (Dual-role Material 3 interface)
│
├── docs/                                    # In-Depth Technical Documentation
│   ├── ARCHITECTURE.md                      # System layers, data flows, and state machines
│   ├── PROTOCOL.md                          # Binary wire formats, GATT UUIDs, packet structures
│   ├── SECURITY.md                          # Cryptographic suite, threat analysis, 13-step validation
│   └── PERFORMANCE.md                       # RF optimization, ephemeral GATT, concurrency benchmarks
│
├── build.gradle.kts                         # Root Gradle buildscript
├── settings.gradle.kts                      # Module includes (:core, :app)
└── gradle.properties                        # AndroidX & JVM configuration
```

---

## 🛠️ Building & Running

### Prerequisites
- **JDK 17+** (JDK 21 or 25 recommended)
- **Android SDK** (API Level 35+, Build Tools 35.0.0+)
- **Gradle 9.3+** (Wrapper included)

### 1. Build via Gradle
```bash
# Run pure KMP core test suite (42 unit tests)
./gradlew :core:jvmTest

# Assemble debug APK
./gradlew :app:assembleDebug
```
The output APK will be generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### 2. Install on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Physical Device Quick-Start Guide

To test the application across two physical Android devices:

```
               [Device A: Instructor]                      [Device B: Student]
                         │                                           │
  1. Open App ──────────>│ Select "Instructor"                       │
                         │ Tap "Copy Authority Key"                  │
                         │                                           │<────────── 2. Open App
                         │                                           │            Select "Student"
                         │                                           │            Tap "Pair Authority Key" (Paste Key A)
                         │                                           │            Tap "Copy My Public Key"
                         │<──────────────────────────────────────────│            Send Key B to Instructor
  3. Enroll Student ────>│ Tap "Enroll Student Key" (Paste Key B)    │
                         │                                           │
  4. Start Session ─────>│ Tap "Start Session (5m)"                  │
                         │ [Notification Active: 0 Verified]         │
                         │                                           │<────────── 5. Check In
                         │                                           │            Tap "Check In / Mark Attendance"
                         │<═════════ Ultra-Fast BLE GATT (<300ms) ═══│            [HUD: Scanning -> Verified!]
  6. Verified! ─────────>│ Counter increments to 1                   │
                         │ Student appears in Live Feed              │
```

1. **Device A (Instructor / Authority)**:
   - Launch AntiProxy $\rightarrow$ Select **Instructor / Authority**.
   - Tap **Copy Authority Public Key**.
   - Tap **Start Session (5m)** (an ongoing notification appears: *"AntiProxy Verification Active"*).
2. **Device B (Student)**:
   - Launch AntiProxy $\rightarrow$ Select **Student Node**.
   - Tap **Pair Authority Public Key** $\rightarrow$ Paste Device A's public key $\rightarrow$ Save.
   - Tap **Copy My Public Key** $\rightarrow$ On Device A, tap **Enroll Student Public Key** $\rightarrow$ Paste and save.
3. **Execute Attendance**:
   - On Device B (Student), tap **Check In / Mark Attendance**.
   - The HUD transitions: `Scanning` $\rightarrow$ `Discovered Beacon` $\rightarrow$ `Connecting` $\rightarrow$ `Verified!`.
   - On Device A (Instructor), the attendee counter increments instantly, and the student's truncated Key ID, timestamp, and RSSI statistics appear in the live feed.

---

## 📚 Technical Documentation Index

For exhaustive technical specifications, consult the documents in [`docs/`](file:///mnt/fedora_disk2/Projects2/AntiProxy/docs):

* **[Architecture Guide (`docs/ARCHITECTURE.md`)](file:///mnt/fedora_disk2/Projects2/AntiProxy/docs/ARCHITECTURE.md)**: Layered architecture, subsystem interactions, background service lifecycles, and SQLite WAL design.
* **[Protocol Specification (`docs/PROTOCOL.md`)](file:///mnt/fedora_disk2/Projects2/AntiProxy/docs/PROTOCOL.md)**: Exact byte-level layouts for `SessionSigned` (133B), `ProofEnvelope` (121B), `FragmentCodec` (6B), and GATT service/characteristic UUIDs.
* **[Security Architecture (`docs/SECURITY.md`)](file:///mnt/fedora_disk2/Projects2/AntiProxy/docs/SECURITY.md)**: ECDSA P-256 Low-S formatting, KeyId computation, threat model, wall-clock rollback guard, and the strict 13-step verification pipeline.
* **[Performance & RF Optimization (`docs/PERFORMANCE.md`)](file:///mnt/fedora_disk2/Projects2/AntiProxy/docs/PERFORMANCE.md)**: Mathematical analysis of scan-time RSSI sampling, ultra-ephemeral GATT channel recycling, and classroom thundering herd mitigation.

---

## 📄 License
This project is part of Project AntiProxy under the 3AM Organization. All rights reserved.
