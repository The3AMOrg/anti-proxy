package antiproxy.core.protocol

import kotlin.test.*

class ProofCodecTest {
    @Test
    fun testProofRoundTrip() {
        val proof = Proof(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = ByteArray(16) { 1 },
            nonce = ByteArray(16) { 3 },
            deviceKeyId = ByteArray(16) { 2 },
            rssiMeanQ1 = (-50).toByte(),
            rssiVarianceQ8_8 = 256.toUShort(),
            sampleCount = 10.toUShort(),
            proofFlags = 0.toByte()
        )
        val encoded = encodeProof(proof)
        assertEquals(PROOF_SIZE, encoded.size)
        val decoded = decodeProof(encoded)
        assertEquals(proof, decoded)
        assertTrue(proof.sessionId.contentEquals(decoded.sessionId))
        assertTrue(proof.deviceKeyId.contentEquals(decoded.deviceKeyId))
        assertTrue(proof.nonce.contentEquals(decoded.nonce))
        assertEquals(proof.rssiMeanQ1, decoded.rssiMeanQ1)
        assertEquals(proof.rssiVarianceQ8_8, decoded.rssiVarianceQ8_8)
        assertEquals(proof.sampleCount, decoded.sampleCount)
        assertEquals(proof.proofFlags, decoded.proofFlags)
    }
    
    @Test
    fun testProofEnvelopeRoundTrip() {
        val proof = Proof(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = ByteArray(16),
            nonce = ByteArray(16),
            deviceKeyId = ByteArray(16),
            rssiMeanQ1 = 0.toByte(),
            rssiVarianceQ8_8 = 0.toUShort(),
            sampleCount = 0.toUShort(),
            proofFlags = 0.toByte()
        )
        val envelope = ProofEnvelope(proof, ByteArray(64) { 7 })
        val encoded = encodeProofEnvelope(envelope)
        assertEquals(PROOF_ENVELOPE_SIZE, encoded.size)
        val decoded = decodeProofEnvelope(encoded)
        assertEquals(envelope, decoded)
        assertTrue(envelope.studentSignature.contentEquals(decoded.studentSignature))
    }
    
    @Test
    fun testExtractProofBytes() {
        val bytes = ByteArray(PROOF_ENVELOPE_SIZE) { it.toByte() }
        val extracted = extractProofBytes(bytes)
        assertEquals(PROOF_SIZE, extracted.size)
        assertTrue(bytes.copyOfRange(0, PROOF_SIZE).contentEquals(extracted))
    }
    
    @Test
    fun testInvalidLength() {
        assertFailsWith<IllegalArgumentException> { decodeProof(ByteArray(PROOF_SIZE - 1)) }
        assertFailsWith<IllegalArgumentException> { decodeProofEnvelope(ByteArray(PROOF_ENVELOPE_SIZE + 1)) }
    }
    
    @Test
    fun testVersionDecoding() {
        val bytes = ByteArray(PROOF_ENVELOPE_SIZE)
        bytes[0] = 0x02
        val envelope = decodeProofEnvelope(bytes)
        assertEquals(0x02.toByte(), envelope.proof.protocolVersion)
    }
}
