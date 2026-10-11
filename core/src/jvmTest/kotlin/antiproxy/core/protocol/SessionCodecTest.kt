package antiproxy.core.protocol

import kotlin.test.*

class SessionCodecTest {
    @Test
    fun testRoundTrip() {
        val session = Session(
            authorityKeyId = ByteArray(16) { 2 },
            sessionId = ByteArray(16) { 1 },
            nonce = ByteArray(16) { 3 },
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyId = 1234
        )
        val signed = SessionSigned(session, ByteArray(64) { 3 })
        val encoded = encodeSessionSigned(signed)
        assertEquals(SESSION_SIGNED_SIZE, encoded.size)
        val decoded = decodeSessionSigned(encoded)
        assertEquals(signed, decoded)
        assertTrue(signed.session.sessionId.contentEquals(decoded.session.sessionId))
        assertTrue(signed.session.authorityKeyId.contentEquals(decoded.session.authorityKeyId))
        assertTrue(signed.session.nonce.contentEquals(decoded.session.nonce))
        assertEquals(signed.session.issuedAt, decoded.session.issuedAt)
        assertEquals(signed.session.expiresAt, decoded.session.expiresAt)
        assertEquals(signed.session.policyId, decoded.session.policyId)
        assertTrue(signed.authoritySignature.contentEquals(decoded.authoritySignature))
    }
    
    @Test
    fun testInvalidLength() {
        assertFailsWith<IllegalArgumentException> {
            decodeSessionSigned(ByteArray(SESSION_SIGNED_SIZE - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeSessionSigned(ByteArray(SESSION_SIGNED_SIZE + 1))
        }
    }
    
    @Test
    fun testInvalidVersion() {
        val bytes = ByteArray(SESSION_SIGNED_SIZE)
        bytes[0] = 0x7F
        assertFailsWith<IllegalArgumentException> {
            decodeSessionSigned(bytes)
        }
    }

    @Test
    fun testSessionBodySize() {
        val session = Session(ByteArray(16), ByteArray(16), ByteArray(16), 0L, 0L, 0)
        assertEquals(SESSION_BODY_SIZE, encodeSessionBody(session).size)
    }

    @Test
    fun testTimestamps() {
        val session = Session(ByteArray(16), ByteArray(16), ByteArray(16), 0L, Long.MAX_VALUE, 0)
        val signed = SessionSigned(session, ByteArray(64))
        val encoded = encodeSessionSigned(signed)
        val decoded = decodeSessionSigned(encoded).session
        assertEquals(0L, decoded.issuedAt)
        assertEquals(Long.MAX_VALUE, decoded.expiresAt)
    }
}
