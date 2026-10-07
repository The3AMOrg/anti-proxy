package antiproxy.core.crypto

/**
 * Verifies ECDSA-P256-SHA256 signatures.
 * 
 * The implementation MUST:
 * - Verify against the raw message bytes (SHA-256 hashing is done by the verification API)
 * - Accept only low-S signatures (reject s > n/2)
 * - Convert raw signature to DER if the platform API requires DER
 * - Reject malformed public keys and signatures
 */
interface Verifier {
    fun verify(publicKey: PublicKeyBytes, message: ByteArray, signature: RawSignature): Boolean
}
