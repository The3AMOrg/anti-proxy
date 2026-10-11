# AntiProxy: BLE Protocol & Wire Format Specification

## 1. BLE GATT Service Definition

AntiProxy exposes a primary GATT service on the Authority (Instructor) device with three characteristics:

| Characteristic | UUID | Properties | Permissions | Payload Size | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Service UUID** | `9de3c1f4-6187-48dd-8d05-976a94a9cdd7` | Primary | - | - | Advertised in discovery packets. |
| **Session Characteristic** | `f0ff0423-5422-495a-a68b-917b963125b4` | `READ` | `READ` | 133 bytes | Emits the Authority-signed session descriptor. |
| **Proof Characteristic** | `5262b34c-79b9-472b-9beb-652aa023d3ce` | `WRITE` | `WRITE` | 121 bytes | Receives student attendance proof envelope. |
| **Status Characteristic** | `63c56110-34fa-4d02-958e-54c4cb940ec8` | `INDICATE`, `READ` | `READ` | 1 byte | Dispatches confirmation ACK or rejection code. |
| **Client Config (CCCD)** | `00002902-0000-1000-8000-00805f9b34fb` | `READ`, `WRITE` | `READ`, `WRITE` | 2 bytes | Enables indication subscription on Status. |

**Beacon id (scan response).** Every Authority advertises the same service UUID, so the scan response
carries service data under that UUID: the first 8 bytes of `authority_key_id`. A student checks in to
one class at a time and locks on only to the beacon whose id matches that class's teacher, so a class
running next door is never picked up. The Authority then accepts only device keys of the members of the
class whose attendance is running; anyone else gets `UNKNOWN_DEVICE`.

---

## 2. Canonical Wire Formats

All integer fields are serialized in **Big-Endian (Network Byte Order)**.

---

### 2.1 `SessionSigned` Structure (133 Bytes)

The Authority creates and signs this structure at the start of an attendance session:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version(0x02)|          Authority Key ID (Bytes 0..14)        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|AuthKeyID(15)  |              Session ID (16 Bytes)            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Session Nonce (16 Bytes)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                   Issued At (64-bit Unix Seconds)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Expires At (64-bit Unix Seconds)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Policy ID (32-bit uint)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                 Authority Signature (64 Bytes)                +
|               Raw IEEE P1363 (r[32] || s[32])                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

#### Field Layout:
| Byte Offset | Field Name | Type | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | `version` | `uint8` | 1 | Protocol version (`0x02`). |
| `1..16` | `authority_key_id` | `byte[16]` | 16 | Truncated SHA-256 of Authority public key. |
| `17..32` | `session_id` | `byte[16]` | 16 | Cryptographically random unique session ID. |
| `33..48` | `nonce` | `byte[16]` | 16 | Cryptographically random session nonce. |
| `49..56` | `issued_at` | `uint64` | 8 | UTC Unix timestamp when session was issued. |
| `57..64` | `expires_at` | `uint64` | 8 | UTC Unix timestamp when session becomes invalid. |
| `65..68` | `policy_id` | `uint32` | 4 | Identifier for active RSSI acceptance policy. |
| **0..68** | **Signed Body** | - | **69** | **Bytes over which Authority signature is calculated.** |
| `69..132` | `signature` | `byte[64]` | 64 | ECDSA P-256 signature ($r[32] \parallel s[32]$) with Low-S. |

---

### 2.2 `Proof` Structure (57 Bytes)

The student device constructs this payload representing physical presence evidence:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version(0x02)|              Session ID (16 Bytes)            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Session Nonce (16 Bytes)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Student Device Key ID (16 Bytes)           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| RSSI Mean Q1  |   RSSI Var Q8.8 (uint16)      | SampleCnt(u16)|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Flags(0x00)  |
+-+-+-+-+-+-+-+-+
```

#### Field Layout:
| Byte Offset | Field Name | Type | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | `version` | `uint8` | 1 | Protocol version (`0x02`). |
| `1..16` | `session_id` | `byte[16]` | 16 | Must match Authority's active `session_id`. |
| `17..32` | `nonce` | `byte[16]` | 16 | Must match Authority's active `nonce`. |
| `33..48` | `device_key_id` | `byte[16]` | 16 | Truncated SHA-256 of Student device public key. |
| `49` | `rssi_mean_q1` | `uint8` | 1 | RSSI mean in $0.5\text{ dBm}$ steps below 0 dBm ($\mu = -q/2$, 0 to −127.5 dBm); clamped, never rejected by the encoder. v1 used a signed byte that stopped at −64 dBm, about a metre away. |
| `50..51` | `rssi_variance_q8_8`| `uint16`| 2 | Unsigned Q8.8 fixed-point sample variance. |
| `52..53` | `sample_count` | `uint16` | 2 | Number of RSSI samples gathered ($N \ge 10$). |
| `54` | `proof_flags` | `uint8` | 1 | Reserved flags (must be `0x00`). |

---

### 2.3 `ProofEnvelope` Structure (121 Bytes)

The complete packet written by the student to the `PROOF_CHAR` characteristic:

```
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Proof Body (57 Bytes)                      +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                  Student Signature (64 Bytes)                 +
|               Raw IEEE P1363 (r[32] || s[32])                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* Total size: **121 bytes**.
* The signature is generated by the student device's hardware Keystore over the raw 57-byte `Proof` body.

---

### 2.4 GATT Fragmentation Header (6 Bytes)

When MTU negotiation yields an effective payload smaller than 121 bytes, payloads are transmitted as indexed chunks using the fragmentation header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Transfer ID (uint16)    |SeqNum(uint8)  |TotalChk(uint8)|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|      Chunk Length (uint16)    |          Payload ...          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* `transfer_id` (2 bytes): Unique 16-bit identifier for the multi-packet transmission.
* `sequence_number` (1 byte): 0-indexed sequence counter ($0 \le \text{seq} < \text{total}$).
* `total_chunks` (1 byte): Total number of chunks in this transfer ($1 \le \text{total} \le 16$).
* `chunk_length` (2 bytes): Length of payload following this header.

---

## 3. Protocol Status Codes

Status indications emitted on `STATUS_CHAR` consist of a single byte:

| Code (Hex) | Name | Description |
| :--- | :--- | :--- |
| `0x00` | `SUCCESS` | Attendance verified and durably committed to SQLite WAL. |
| `0x01` | `MALFORMED_FRAME` | Frame length invalid, unknown format, or header malformed. |
| `0x02` | `UNSUPPORTED_VERSION`| Protocol version byte $\ne 0x02$. |
| `0x03` | `UNKNOWN_SESSION` | Specified `session_id` does not exist on Authority. |
| `0x04` | `SESSION_EXPIRED` | Submission arrived after session `expires_at` window. |
| `0x05` | `NONCE_MISMATCH` | Proof nonce does not match Authority's active session nonce. |
| `0x06` | `UNKNOWN_DEVICE` | Student `device_key_id` not found in Authority roster. |
| `0x07` | `DEVICE_REVOKED` | Student device has been revoked in Authority ledger. |
| `0x08` | `INVALID_SIGNATURE` | Cryptographic signature verification failed or high-S detected. |
| `0x09` | `REPLAY` | Idempotency key already exists (attendance already recorded). |
| `0x0A` | `INVALID_RSSI_EVIDENCE`| Sample count $N < 10$ or fixed-point values out of bounds. |
| `0x0B` | `POLICY_REJECTED` | RSSI mean below threshold or variance exceeds maximum. |
| `0x0C` | `CLOCK_SKEW_EXCEEDED` | Device clock drifts beyond tolerance threshold. |
| `0x0D` | `TRUST_LEASE_EXPIRED` | Authority offline trust lease from campus backend expired. |
| `0x0E` | `MTU_OR_FRAGMENT_ERROR`| Fragment reassembly timed out, chunk duplicated, or corrupted. |
| `0x0F` | `INTERNAL_STORAGE_FAILURE`| Database transaction or SQLite commit failed. |
