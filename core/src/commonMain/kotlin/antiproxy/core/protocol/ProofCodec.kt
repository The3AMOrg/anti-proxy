package antiproxy.core.protocol

fun encodeProof(proof: Proof): ByteArray {
    val data = ByteArray(PROOF_SIZE)
    data[0] = proof.protocolVersion
    var offset = 1
    putBytes(data, offset, proof.sessionId)
    offset += SESSION_ID_SIZE
    putBytes(data, offset, proof.nonce)
    offset += NONCE_SIZE
    putBytes(data, offset, proof.deviceKeyId)
    offset += KEY_ID_SIZE
    data[offset++] = proof.rssiMeanQ1
    putUInt16BE(data, offset, proof.rssiVarianceQ8_8)
    offset += 2
    putUInt16BE(data, offset, proof.sampleCount)
    offset += 2
    data[offset] = proof.proofFlags
    return data
}

fun decodeProof(data: ByteArray): Proof {
    require(data.size == PROOF_SIZE) { "Proof must be exactly $PROOF_SIZE bytes" }
    val version = data[0]
    var offset = 1
    val sessionId = getBytes(data, offset, SESSION_ID_SIZE)
    offset += SESSION_ID_SIZE
    val nonce = getBytes(data, offset, NONCE_SIZE)
    offset += NONCE_SIZE
    val deviceKeyId = getBytes(data, offset, KEY_ID_SIZE)
    offset += KEY_ID_SIZE
    val rssiMeanQ1 = data[offset++]
    val rssiVarianceQ8_8 = getUInt16BE(data, offset)
    offset += 2
    val sampleCount = getUInt16BE(data, offset)
    offset += 2
    val proofFlags = data[offset]
    
    return Proof(
        protocolVersion = version,
        sessionId = sessionId,
        nonce = nonce,
        deviceKeyId = deviceKeyId,
        rssiMeanQ1 = rssiMeanQ1,
        rssiVarianceQ8_8 = rssiVarianceQ8_8,
        sampleCount = sampleCount,
        proofFlags = proofFlags
    )
}

fun encodeProofEnvelope(envelope: ProofEnvelope): ByteArray {
    val data = ByteArray(PROOF_ENVELOPE_SIZE)
    val proofBytes = encodeProof(envelope.proof)
    putBytes(data, 0, proofBytes)
    putBytes(data, PROOF_SIZE, envelope.studentSignature)
    return data
}

fun decodeProofEnvelope(data: ByteArray): ProofEnvelope {
    require(data.size == PROOF_ENVELOPE_SIZE) { "ProofEnvelope must be exactly $PROOF_ENVELOPE_SIZE bytes" }
    val proofBytes = getBytes(data, 0, PROOF_SIZE)
    val proof = decodeProof(proofBytes)
    val signature = getBytes(data, PROOF_SIZE, SIGNATURE_SIZE)
    
    return ProofEnvelope(proof, signature)
}

fun extractProofBytes(envelopeData: ByteArray): ByteArray {
    require(envelopeData.size >= PROOF_SIZE) { "Data too short to extract Proof" }
    return getBytes(envelopeData, 0, PROOF_SIZE)
}
