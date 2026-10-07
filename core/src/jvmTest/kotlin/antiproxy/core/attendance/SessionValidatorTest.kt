package antiproxy.core.attendance

import antiproxy.core.protocol.*
import antiproxy.core.crypto.*
import kotlin.test.*

class SessionValidatorTest {
    @Test
    fun testValidSession() {
        val (signer, pubKey, keyId) = generateTestKeyPair()
        val session = Session(
            authorityKeyId = keyId.bytes,
            sessionId = ByteArray(16) { 1 },
            nonce = ByteArray(16) { 2 },
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyId = 1
        )
        val sig = signer.sign(encodeSessionBody(session))
        val signed = SessionSigned(session, sig.bytes)
        
        val knownKeys = mapOf(keyId to pubKey)
        val res = validateSession(signed, knownKeys, JvmTestVerifier(), 1500L, 30L)
        assertTrue(res is SessionValidationResult.Valid)
    }

    @Test
    fun testUnknownAuthority() {
        val (signer, _, keyId) = generateTestKeyPair()
        val session = Session(
            authorityKeyId = keyId.bytes,
            sessionId = ByteArray(16) { 1 },
            nonce = ByteArray(16) { 2 },
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyId = 1
        )
        val sig = signer.sign(encodeSessionBody(session))
        val signed = SessionSigned(session, sig.bytes)
        
        val res = validateSession(signed, emptyMap(), JvmTestVerifier(), 1500L, 30L)
        assertTrue(res is SessionValidationResult.Failed)
        assertEquals(StatusCode.UNKNOWN_DEVICE, (res as SessionValidationResult.Failed).status)
    }
    
    @Test
    fun testExpiredSession() {
        val (signer, pubKey, keyId) = generateTestKeyPair()
        val session = Session(
            authorityKeyId = keyId.bytes,
            sessionId = ByteArray(16) { 1 },
            nonce = ByteArray(16) { 2 },
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyId = 1
        )
        val sig = signer.sign(encodeSessionBody(session))
        val signed = SessionSigned(session, sig.bytes)
        
        val knownKeys = mapOf(keyId to pubKey)
        val res = validateSession(signed, knownKeys, JvmTestVerifier(), 2031L, 30L)
        assertTrue(res is SessionValidationResult.Failed)
        assertEquals(StatusCode.SESSION_EXPIRED, (res as SessionValidationResult.Failed).status)
    }
}
