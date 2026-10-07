package antiproxy.core.crypto

/**
 * Signs messages using ECDSA-P256-SHA256.
 * The platform implementation holds the private key reference (e.g., Android Keystore alias).
 * Private key material never enters application-managed storage.
 *
 * The implementation MUST:
 * - Sign the raw message bytes (SHA-256 hashing is performed by the signing API, not pre-hashed)
 * - Return a low-S normalized raw signature (r[32] || s[32])
 * - Convert from DER if the platform API produces DER
 */
interface Signer {
    fun sign(message: ByteArray): RawSignature
}
