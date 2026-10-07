# AntiProxy: Security Architecture & Cryptographic Threat Model

## 1. Cryptographic Suite Specifications

All cryptographic operations in AntiProxy adhere to standard NIST recommendations while enforcing strict canonical encoding to prevent malleability:

### 1.1 Curve & Signature Scheme
- **Algorithm**: ECDSA over NIST P-256 (`secp256r1` / `prime256v1`).
- **Hash Function**: SHA-256 (FIPS 180-4).
- **Public Key Representation**: Uncompressed X9.62 format:
  $$\text{PublicKey} = 0x04 \mathbin{\Vert} X[32] \mathbin{\Vert} Y[32] \quad (65\text{ bytes})$$

### 1.2 Raw IEEE P1363 Wire Format (No DER on Wire)
Standard ASN.1 DER-encoded signatures vary in length (70–72 bytes) and introduce encoding ambiguity. AntiProxy strictly transmits signatures as **fixed 64-byte IEEE P1363 format**:
$$\text{Signature} = r[32] \mathbin{\Vert} s[32]$$
Both $r$ and $s$ are serialized as 32-byte big-endian unsigned integers, zero-padded if necessary.

### 1.3 Canonical Low-S Enforcement (Malleability Prevention)
In ECDSA, given a valid signature $(r, s)$ for message $m$, the signature $(r, -s \pmod n)$ is also valid. This malleability allows adversaries to alter signature bytes without invalidating verification.

AntiProxy eliminates signature malleability by enforcing:
$$1 \le r < n \quad \text{and} \quad 1 \le s \le \frac{n}{2}$$
where $n$ is the curve order of `secp256r1`.
- **Signer**: If the Keystore produces $s > n/2$, the signer automatically normalizes it to $s' = n - s$.
- **Verifier**: Any signature with $s > n/2$ is rejected immediately with `INVALID_SIGNATURE (0x08)`.

### 1.4 Compact Key Identifiers (`KeyId`)
To minimize BLE airtime, public keys are identified by a 16-byte cryptographic fingerprint:
$$\text{KeyId} = \text{SHA-256}(\text{PublicKey}_{X9.62})[0..15]$$
Collision probability in a 16-byte space across a university population ($10^5$ devices) is $< 10^{-19}$.

---

## 2. Threat Analysis & Countermeasures

| Attack Vector | Attacker Strategy | AntiProxy Countermeasure |
| :--- | :--- | :--- |
| **Remote Relay Attack ("WhatsApp Proxy")** | An in-class student sends the session nonce or screenshot to an absent peer in their dorm. | **Defeated by local BLE GATT constraint**: Attendance requires establishing a direct, physical peer-to-point BLE GATT connection with the instructor's device. An absent peer cannot establish BLE radio communication from a remote location. |
| **Replay Attack** | An eavesdropper captures an in-class student's `ProofEnvelope` over the air and re-submits it. | **Defeated by 16-byte Nonces & Idempotency Key**: Each session features a fresh 128-bit random nonce. The Authority computes: $$H = \text{SHA-256}(\text{AuthID} \mathbin{\Vert} \text{SessionID} \mathbin{\Vert} \text{StudentID})$$ Any duplicate submission triggers `REPLAY (0x09)` and is dropped. |
| **Signature Malleability** | An attacker flips the $s$ scalar in a student's signature to generate a new transaction hash. | **Defeated by Low-S Canonicalization**: High-S signatures are strictly rejected prior to cryptographic verification. |
| **Clock Rollback / Tampering** | An attacker sets their phone clock backwards to submit a proof for an expired session. | **Defeated by `ClockGuard`**: Stores monotonic timestamps in SQLite. Any attempt to rewind the clock is caught and rejected locally. Furthermore, the Authority checks its own authoritative clock ($t \le \text{expires\_at}$). |
| **Key Extraction / Clone** | A student roots their device to extract their private key and install it on an automated bot. | **Defeated by Hardware Keystore**: Private keys are generated with `PURPOSE_SIGN` inside hardware StrongBox / TEE with non-exportable flags. Private keys cannot be extracted even on rooted devices. |
| **Hallway / Adjacent Room Snooping** | A student connects through a wall from the hallway to mark attendance. | **Defeated by Multi-Sample RSSI Gating**: Requires $N \ge 10$ RSSI samples. If the calculated mean is below the classroom threshold (e.g., $-75\text{ dBm}$) or variance indicates wall reflection attenuation, the Authority rejects with `POLICY_REJECTED (0x0B)`. |

---

## 3. Strict 13-Step Authority Verification Pipeline

To protect the Authority against Denial-of-Service (DoS) and expensive CPU exhaustion (ECDSA verification is CPU-intensive), the Authority evaluates proof envelopes in an **exact fail-fast sequence**, executing cheap structural and bounds checks before performing asymmetric cryptography:

```
                  Incoming ProofEnvelope (Raw Bytes)
                                  │
       1. Exact Length Check (121 Bytes) ───────> Fail: MALFORMED_FRAME (0x01)
                                  │
       2. Protocol Version (0x01) ──────────────> Fail: UNSUPPORTED_VERSION (0x02)
                                  │
       3. Reserved Flags (0x00) ────────────────> Fail: MALFORMED_FRAME (0x01)
                                  │
       4. Session Existence Lookup ─────────────> Fail: UNKNOWN_SESSION (0x03)
                                  │
       5. Session Nonce Match ──────────────────> Fail: NONCE_MISMATCH (0x05)
                                  │
       6. Session Expiry (now <= expires_at) ───> Fail: SESSION_EXPIRED (0x04)
                                  │
       7. Student Device Key Lookup ────────────> Fail: UNKNOWN_DEVICE (0x06)
                                  │
       8. Revocation Status Check ──────────────> Fail: DEVICE_REVOKED (0x07)
                                  │
       9. Low-S Scalar Bound Check ─────────────> Fail: INVALID_SIGNATURE (0x08)
                                  │
      10. ECDSA-P256 Signature Verify (57B) ────> Fail: INVALID_SIGNATURE (0x08)
                                  │
      11. RSSI Sample Count (N >= 10) ──────────> Fail: INVALID_RSSI_EVIDENCE (0x0A)
                                  │
      12. RSSI Policy Evaluation (Mean & Var) ──> Fail: POLICY_REJECTED (0x0B)
                                  │
      13. Replay / Deduplication Check ─────────> Fail: REPLAY (0x09)
                                  │
                                  ▼
                Atomic SQLite WAL Commit (Record + Outbox)
                                  │
                                  ▼
                     Indicate SUCCESS (0x00)
```

---

## 4. Local Ledger Invariant (Pre-ACK Commit)

Under no circumstances may the Authority send a `SUCCESS (0x00)` indication before the record is persisted to non-volatile disk. 

The Authority executes:
```kotlin
db.beginTransaction()
try {
    // 1. Insert attendance record (fails if duplicate)
    // 2. Insert sync event outbox record
    db.setTransactionSuccessful()
} finally {
    db.endTransaction()
}
// Only after transaction successfully closes:
advertiser.sendStatus(device, StatusCode.SUCCESS)
```
If power fails or the app is killed at any instant, either the transaction fully rolled back (allowing safe retry) or was committed prior to ACK. State inconsistency is impossible.
