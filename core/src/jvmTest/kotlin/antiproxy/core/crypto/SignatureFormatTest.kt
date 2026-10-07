package antiproxy.core.crypto

import kotlin.test.*

class SignatureFormatTest {
    @Test
    fun testSignatureRoundTrip() {
        val (signer, _, _) = generateTestKeyPair()
        val sig = signer.sign(ByteArray(32) { 1 })
        assertEquals(64, sig.bytes.size)
        val der = rawToDer(sig)
        val raw = derToRaw(der)
        assertTrue(sig.bytes.contentEquals(raw.bytes))
    }

    @Test
    fun testLowS() {
        val (signer, _, _) = generateTestKeyPair()
        val sig = signer.sign(ByteArray(32) { 1 })
        assertTrue(isLowS(sig.s()))
    }
    
    @Test
    fun testRejectInvalid() {
        val raw = RawSignature(ByteArray(64)) // all zeros
        assertFalse(isLowS(raw.s()))
        assertFalse(isValidScalar(ByteArray(32)))
        
        val maxScalar = ByteArray(32) { 0xFF.toByte() }
        assertFalse(isValidScalar(maxScalar))
    }
}
