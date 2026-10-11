package antiproxy.core.attendance

import antiproxy.core.protocol.*
import antiproxy.core.crypto.*
import kotlin.test.*

class ProofValidatorTest {

    private fun makeSession(sessionId: ByteArray = ByteArray(16) { 1 }): Session {
        return Session(
            authorityKeyId = ByteArray(16) { 2 },
            sessionId = sessionId,
            nonce = ByteArray(16) { 3 },
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyId = 1
        )
    }

    @Test
    fun testValidProofPassesAllSteps() {
        val (studentSigner, studentPubKey, studentKeyId) = generateTestKeyPair()
        val session = makeSession()

        val proof = Proof(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = session.sessionId,
            nonce = session.nonce,
            deviceKeyId = studentKeyId.bytes,
            rssiMeanQ1 = toQ1(-50.0),
            rssiVarianceQ8_8 = toQ8_8(10.0),
            sampleCount = 15.toUShort(),
            proofFlags = 0.toByte()
        )

        val sig = studentSigner.sign(encodeProof(proof))
        val envelope = ProofEnvelope(proof, sig.bytes)
        val envelopeBytes = encodeProofEnvelope(envelope)

        val status = validateProofEnvelope(
            envelopeData = envelopeBytes,
            sessionLookup = { sid -> if (sid.contentEquals(session.sessionId)) session else null },
            deviceKeyLookup = { kid -> if (kid.contentEquals(studentKeyId.bytes)) studentPubKey else null },
            deviceStatusCheck = { DeviceStatus.ACTIVE },
            verifier = JvmTestVerifier(),
            rssiPolicy = RssiPolicy.DEFAULT,
            replayCheck = { _, _ -> false },
            currentTimeSeconds = 1500L
        )

        assertEquals(StatusCode.SUCCESS, status)
    }

    @Test
    fun testValidationOrderAndErrors() {
        val (studentSigner, studentPubKey, studentKeyId) = generateTestKeyPair()
        val session = makeSession()

        val proof = Proof(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = session.sessionId,
            nonce = session.nonce,
            deviceKeyId = studentKeyId.bytes,
            rssiMeanQ1 = toQ1(-50.0),
            rssiVarianceQ8_8 = toQ8_8(10.0),
            sampleCount = 15.toUShort(),
            proofFlags = 0.toByte()
        )

        val sig = studentSigner.sign(encodeProof(proof))
        val envelope = ProofEnvelope(proof, sig.bytes)
        val envelopeBytes = encodeProofEnvelope(envelope)

        // 1. Frame length
        val truncated = envelopeBytes.copyOfRange(0, 100)
        assertEquals(
            StatusCode.MALFORMED_FRAME,
            validateProofEnvelope(truncated, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 2. Protocol version
        val badVersionProof = proof.copy(protocolVersion = 0x7F.toByte())
        val badVersionBytes = encodeProofEnvelope(ProofEnvelope(badVersionProof, sig.bytes))
        assertEquals(
            StatusCode.UNSUPPORTED_VERSION,
            validateProofEnvelope(badVersionBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 3. Flags non-zero
        val badFlagsProof = proof.copy(proofFlags = 0x01.toByte())
        val badFlagsBytes = encodeProofEnvelope(ProofEnvelope(badFlagsProof, sig.bytes))
        assertEquals(
            StatusCode.MALFORMED_FRAME,
            validateProofEnvelope(badFlagsBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 4. Session existence
        assertEquals(
            StatusCode.UNKNOWN_SESSION,
            validateProofEnvelope(envelopeBytes, { null }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 5. Nonce mismatch
        val badNonceSession = session.copy(nonce = ByteArray(16) { 9 })
        assertEquals(
            StatusCode.NONCE_MISMATCH,
            validateProofEnvelope(envelopeBytes, { badNonceSession }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 6. Session expired
        assertEquals(
            StatusCode.SESSION_EXPIRED,
            validateProofEnvelope(envelopeBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 2001L)
        )

        // 7. Unknown device
        assertEquals(
            StatusCode.UNKNOWN_DEVICE,
            validateProofEnvelope(envelopeBytes, { session }, { null }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 8. Device revoked
        assertEquals(
            StatusCode.DEVICE_REVOKED,
            validateProofEnvelope(envelopeBytes, { session }, { studentPubKey }, { DeviceStatus.REVOKED }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 9. Invalid signature
        val badSigEnvelope = envelope.copy(studentSignature = ByteArray(64) { 1 })
        assertEquals(
            StatusCode.INVALID_SIGNATURE,
            validateProofEnvelope(encodeProofEnvelope(badSigEnvelope), { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 10. Sample count < MIN_RSSI_SAMPLES
        val lowSampleProof = proof.copy(sampleCount = 5.toUShort())
        val lowSampleSig = studentSigner.sign(encodeProof(lowSampleProof))
        val lowSampleBytes = encodeProofEnvelope(ProofEnvelope(lowSampleProof, lowSampleSig.bytes))
        assertEquals(
            StatusCode.INVALID_RSSI_EVIDENCE,
            validateProofEnvelope(lowSampleBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> false }, 1500L)
        )

        // 12. RSSI Policy rejection
        val strictPolicy = RssiPolicy(
            policyVersion = 1,
            minMeanRssi = -40.0, // proof has -50.0 dBm which fails minMeanRssi
            maxVariance = 200.0,
            minSampleCount = 10
        )
        assertEquals(
            StatusCode.POLICY_REJECTED,
            validateProofEnvelope(envelopeBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), strictPolicy, { _, _ -> false }, 1500L)
        )

        // 13. Replay rejection
        assertEquals(
            StatusCode.REPLAY,
            validateProofEnvelope(envelopeBytes, { session }, { studentPubKey }, { DeviceStatus.ACTIVE }, JvmTestVerifier(), RssiPolicy.DEFAULT, { _, _ -> true }, 1500L)
        )
    }
}
