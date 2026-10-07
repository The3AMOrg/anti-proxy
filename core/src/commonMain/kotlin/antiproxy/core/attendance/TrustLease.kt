package antiproxy.core.attendance

import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.RawSignature
import antiproxy.core.crypto.Verifier

data class TrustLease(
    val authorityDeviceId: ByteArray,   // 16 bytes
    val issuedAt: Long,                  // UTC Unix seconds
    val expiresAt: Long,                 // UTC Unix seconds
    val policyVersion: Int,
    val authorityKeyId: ByteArray,       // 16 bytes
    val serverSignature: ByteArray       // 64 bytes - signed by backend server
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustLease) return false
        return authorityDeviceId.contentEquals(other.authorityDeviceId) &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            policyVersion == other.policyVersion &&
            authorityKeyId.contentEquals(other.authorityKeyId) &&
            serverSignature.contentEquals(other.serverSignature)
    }
    
    override fun hashCode(): Int {
        var result = authorityDeviceId.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + policyVersion
        result = 31 * result + authorityKeyId.contentHashCode()
        result = 31 * result + serverSignature.contentHashCode()
        return result
    }
}

sealed class TrustLeaseStatus {
    object Valid : TrustLeaseStatus()
    data class Expired(val expiredAt: Long) : TrustLeaseStatus()
    data class NotYetValid(val validFrom: Long) : TrustLeaseStatus()
    object SignatureInvalid : TrustLeaseStatus()
}

/**
 * Validate trust lease. Authority MUST NOT create new sessions after trust lease expiry.
 * Server signature verification is optional (requires server public key).
 */
fun validateTrustLease(
    lease: TrustLease,
    currentTimeSeconds: Long,
    serverPublicKey: PublicKeyBytes? = null,
    verifier: Verifier? = null
): TrustLeaseStatus {
    if (serverPublicKey != null && verifier != null) {
        val bytes = ByteArray(16 + 8 + 8 + 4 + 16)
        var offset = 0
        lease.authorityDeviceId.copyInto(bytes, offset)
        offset += 16
        bytes[offset++] = (lease.issuedAt ushr 56).toByte()
        bytes[offset++] = (lease.issuedAt ushr 48).toByte()
        bytes[offset++] = (lease.issuedAt ushr 40).toByte()
        bytes[offset++] = (lease.issuedAt ushr 32).toByte()
        bytes[offset++] = (lease.issuedAt ushr 24).toByte()
        bytes[offset++] = (lease.issuedAt ushr 16).toByte()
        bytes[offset++] = (lease.issuedAt ushr 8).toByte()
        bytes[offset++] = lease.issuedAt.toByte()
        bytes[offset++] = (lease.expiresAt ushr 56).toByte()
        bytes[offset++] = (lease.expiresAt ushr 48).toByte()
        bytes[offset++] = (lease.expiresAt ushr 40).toByte()
        bytes[offset++] = (lease.expiresAt ushr 32).toByte()
        bytes[offset++] = (lease.expiresAt ushr 24).toByte()
        bytes[offset++] = (lease.expiresAt ushr 16).toByte()
        bytes[offset++] = (lease.expiresAt ushr 8).toByte()
        bytes[offset++] = lease.expiresAt.toByte()
        bytes[offset++] = (lease.policyVersion ushr 24).toByte()
        bytes[offset++] = (lease.policyVersion ushr 16).toByte()
        bytes[offset++] = (lease.policyVersion ushr 8).toByte()
        bytes[offset++] = lease.policyVersion.toByte()
        lease.authorityKeyId.copyInto(bytes, offset)
        
        if (!verifier.verify(serverPublicKey, bytes, RawSignature(lease.serverSignature))) {
            return TrustLeaseStatus.SignatureInvalid
        }
    }
    
    if (currentTimeSeconds < lease.issuedAt) return TrustLeaseStatus.NotYetValid(lease.issuedAt)
    if (currentTimeSeconds > lease.expiresAt) return TrustLeaseStatus.Expired(lease.expiresAt)
    
    return TrustLeaseStatus.Valid
}
