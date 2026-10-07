package antiproxy.core.attendance

import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.Verifier
import antiproxy.core.crypto.RawSignature
import antiproxy.core.protocol.MAX_CLOCK_SKEW_SECONDS
import antiproxy.core.protocol.Session
import antiproxy.core.protocol.SessionSigned
import antiproxy.core.protocol.StatusCode
import antiproxy.core.protocol.encodeSessionBody

sealed class SessionValidationResult {
    data class Valid(val session: Session) : SessionValidationResult()
    data class Failed(val status: StatusCode, val reason: String) : SessionValidationResult()
}

fun validateSession(
    signedSession: SessionSigned,
    knownAuthorityKeys: Map<KeyId, PublicKeyBytes>,
    verifier: Verifier,
    currentTimeSeconds: Long,
    maxClockSkew: Long = MAX_CLOCK_SKEW_SECONDS
): SessionValidationResult {
    val authorityKeyIdBytes = signedSession.session.authorityKeyId
    val keyId = knownAuthorityKeys.keys.firstOrNull { it.bytes.contentEquals(authorityKeyIdBytes) }
    
    if (keyId == null) {
        return SessionValidationResult.Failed(StatusCode.UNKNOWN_DEVICE, "Authority key ID not found in known keys")
    }
    
    val publicKey = knownAuthorityKeys[keyId]!!
    val sessionBody = encodeSessionBody(signedSession.session)
    val signature = RawSignature(signedSession.authoritySignature)
    
    if (!verifier.verify(publicKey, sessionBody, signature)) {
        return SessionValidationResult.Failed(StatusCode.INVALID_SIGNATURE, "Invalid authority signature")
    }
    
    if (signedSession.session.issuedAt > currentTimeSeconds + maxClockSkew) {
        return SessionValidationResult.Failed(StatusCode.SESSION_EXPIRED, "Session issued in the far future")
    }
    
    if (currentTimeSeconds - maxClockSkew > signedSession.session.expiresAt) {
        return SessionValidationResult.Failed(StatusCode.SESSION_EXPIRED, "Session has expired")
    }
    
    return SessionValidationResult.Valid(signedSession.session)
}
