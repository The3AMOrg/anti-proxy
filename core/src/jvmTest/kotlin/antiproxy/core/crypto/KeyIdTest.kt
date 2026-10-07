package antiproxy.core.crypto

import kotlin.test.*

class KeyIdTest {
    @Test
    fun testComputeKeyId() {
        val (_, pub1, id1) = generateTestKeyPair()
        val (_, pub2, id2) = generateTestKeyPair()
        
        assertEquals(16, id1.bytes.size)
        
        val id1b = computeKeyId(pub1)
        assertTrue(id1.bytes.contentEquals(id1b.bytes))
        
        assertFalse(id1.bytes.contentEquals(id2.bytes))
        
        val hash = sha256(pub1.bytes)
        assertTrue(id1.bytes.contentEquals(hash.copyOfRange(0, 16)))
    }
}
