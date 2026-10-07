package antiproxy.core.attendance

import antiproxy.core.crypto.KeyId
import kotlin.test.*

class IdempotencyKeyTest {
    @Test
    fun testIdempotencyKey() {
        val authId = ByteArray(16) { 1 }
        val sessionId = ByteArray(16) { 2 }
        val keyId = KeyId(ByteArray(16) { 3 })
        
        val k1 = computeIdempotencyKey(authId, sessionId, keyId.bytes)
        val k2 = computeIdempotencyKey(authId, sessionId, keyId.bytes)
        
        assertTrue(k1.contentEquals(k2))
        assertEquals(32, k1.size)
        
        val k3 = computeIdempotencyKey(ByteArray(16) { 4 }, sessionId, keyId.bytes)
        assertFalse(k1.contentEquals(k3))
        
        val k4 = computeIdempotencyKey(authId, ByteArray(16) { 4 }, keyId.bytes)
        assertFalse(k1.contentEquals(k4))
    }
}
