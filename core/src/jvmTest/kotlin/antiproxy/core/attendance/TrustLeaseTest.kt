package antiproxy.core.attendance

import antiproxy.core.crypto.*
import kotlin.test.*

class TrustLeaseTest {
    @Test
    fun testValidation() {
        val lease = TrustLease(
            authorityDeviceId = ByteArray(16),
            issuedAt = 1000L,
            expiresAt = 2000L,
            policyVersion = 1,
            authorityKeyId = ByteArray(16),
            serverSignature = ByteArray(64)
        )
        
        assertEquals(TrustLeaseStatus.Valid, validateTrustLease(lease, 1500L, null, null))
        assertTrue(validateTrustLease(lease, 2001L, null, null) is TrustLeaseStatus.Expired)
        assertTrue(validateTrustLease(lease, 999L, null, null) is TrustLeaseStatus.NotYetValid)
        assertEquals(TrustLeaseStatus.Valid, validateTrustLease(lease, 2000L, null, null))
        assertEquals(TrustLeaseStatus.Valid, validateTrustLease(lease, 1000L, null, null))
    }
}
