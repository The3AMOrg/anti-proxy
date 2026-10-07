package antiproxy.core.attendance

import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.Signer
import antiproxy.core.protocol.MIN_RSSI_SAMPLES
import antiproxy.core.protocol.PROTOCOL_VERSION
import antiproxy.core.protocol.Proof
import antiproxy.core.protocol.ProofEnvelope
import antiproxy.core.protocol.Session
import antiproxy.core.protocol.encodeProof

fun buildProofEnvelope(
    session: Session,
    deviceKeyId: KeyId,
    rssiSamples: List<Int>,
    signer: Signer
): ProofEnvelope {
    require(rssiSamples.size >= MIN_RSSI_SAMPLES) { "Not enough RSSI samples" }
    
    val mean = rssiMean(rssiSamples)
    val variance = rssiVariance(rssiSamples)
    
    val meanQ1 = toQ1(mean)
    val varianceQ8_8 = toQ8_8(variance)
    
    val proof = Proof(
        protocolVersion = PROTOCOL_VERSION,
        sessionId = session.sessionId,
        nonce = session.nonce,
        deviceKeyId = deviceKeyId.bytes,
        rssiMeanQ1 = meanQ1,
        rssiVarianceQ8_8 = varianceQ8_8,
        sampleCount = rssiSamples.size.toUShort(),
        proofFlags = 0x00
    )
    
    val canonicalBytes = encodeProof(proof)
    val signature = signer.sign(canonicalBytes)
    
    return ProofEnvelope(proof, signature.bytes)
}
