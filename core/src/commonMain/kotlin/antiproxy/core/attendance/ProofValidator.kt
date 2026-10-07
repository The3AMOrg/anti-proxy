package antiproxy.core.attendance

import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.Verifier
import antiproxy.core.protocol.MIN_RSSI_SAMPLES
import antiproxy.core.protocol.PROOF_ENVELOPE_SIZE
import antiproxy.core.protocol.PROTOCOL_VERSION
import antiproxy.core.protocol.Session
import antiproxy.core.protocol.StatusCode
import antiproxy.core.protocol.decodeProofEnvelope
import antiproxy.core.protocol.extractProofBytes
import antiproxy.core.crypto.RawSignature

enum class DeviceStatus { ACTIVE, REVOKED, UNKNOWN }

fun validateProofEnvelope(
    envelopeData: ByteArray,
    sessionLookup: (ByteArray) -> Session?,
    deviceKeyLookup: (ByteArray) -> PublicKeyBytes?,
    deviceStatusCheck: (ByteArray) -> DeviceStatus,
    verifier: Verifier,
    rssiPolicy: RssiPolicy,
    replayCheck: (ByteArray, ByteArray) -> Boolean,
    currentTimeSeconds: Long
): StatusCode {
    if (envelopeData.size != PROOF_ENVELOPE_SIZE) return StatusCode.MALFORMED_FRAME
    
    val envelope = try {
        decodeProofEnvelope(envelopeData)
    } catch (e: Exception) {
        return StatusCode.MALFORMED_FRAME
    }
    
    val proof = envelope.proof
    if (proof.protocolVersion != PROTOCOL_VERSION) return StatusCode.UNSUPPORTED_VERSION
    if (proof.proofFlags != 0x00.toByte()) return StatusCode.MALFORMED_FRAME
    
    val session = sessionLookup(proof.sessionId) ?: return StatusCode.UNKNOWN_SESSION
    if (!proof.nonce.contentEquals(session.nonce)) return StatusCode.NONCE_MISMATCH
    if (currentTimeSeconds > session.expiresAt) return StatusCode.SESSION_EXPIRED
    
    val publicKey = deviceKeyLookup(proof.deviceKeyId) ?: return StatusCode.UNKNOWN_DEVICE
    
    val status = deviceStatusCheck(proof.deviceKeyId)
    if (status == DeviceStatus.REVOKED) return StatusCode.DEVICE_REVOKED
    if (status == DeviceStatus.UNKNOWN) return StatusCode.UNKNOWN_DEVICE
    
    val proofBytes = extractProofBytes(envelopeData)
    val signature = RawSignature(envelope.studentSignature)
    if (!verifier.verify(publicKey, proofBytes, signature)) return StatusCode.INVALID_SIGNATURE
    
    val sampleCount = proof.sampleCount.toInt()
    if (sampleCount < MIN_RSSI_SAMPLES) return StatusCode.INVALID_RSSI_EVIDENCE
    
    val meanDbm = try {
        fromQ1(proof.rssiMeanQ1)
    } catch (e: IllegalArgumentException) {
        return StatusCode.INVALID_RSSI_EVIDENCE
    }
    
    val varianceDbm2 = try {
        fromQ8_8(proof.rssiVarianceQ8_8)
    } catch (e: IllegalArgumentException) {
        return StatusCode.INVALID_RSSI_EVIDENCE
    }
    
    if (!rssiPolicy.evaluate(meanDbm, varianceDbm2, sampleCount)) return StatusCode.POLICY_REJECTED
    
    if (replayCheck(proof.sessionId, proof.deviceKeyId)) return StatusCode.REPLAY
    
    return StatusCode.SUCCESS
}
