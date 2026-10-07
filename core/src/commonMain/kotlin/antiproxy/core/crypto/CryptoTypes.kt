package antiproxy.core.crypto

import antiproxy.core.protocol.SIGNATURE_SIZE
import antiproxy.core.protocol.KEY_ID_SIZE

/**
 * Raw ECDSA signature: r[32] || s[32], 64 bytes.
 * Each scalar is unsigned big-endian 32-byte integer.
 * DER MUST NOT appear on the wire.
 */
@JvmInline
value class RawSignature(val bytes: ByteArray) {
    init {
        require(bytes.size == SIGNATURE_SIZE) { "RawSignature must be exactly $SIGNATURE_SIZE bytes" }
    }

    /** Extract r component (first 32 bytes). */
    fun r(): ByteArray = bytes.copyOfRange(0, 32)

    /** Extract s component (last 32 bytes). */
    fun s(): ByteArray = bytes.copyOfRange(32, 64)
}

/**
 * ECDSA P-256 public key in uncompressed X9.62 format (65 bytes: 0x04 || x[32] || y[32]).
 */
@JvmInline
value class PublicKeyBytes(val bytes: ByteArray) {
    init {
        require(bytes.size == 65) { "P-256 uncompressed public key must be 65 bytes" }
        require(bytes[0] == 0x04.toByte()) { "Public key must start with 0x04 (uncompressed)" }
    }
}

/**
 * Key identifier: first 16 bytes of SHA-256(canonical_public_key_encoding).
 * Identifies a key but does not replace the complete public key.
 */
@JvmInline
value class KeyId(val bytes: ByteArray) {
    init {
        require(bytes.size == KEY_ID_SIZE) { "KeyId must be exactly $KEY_ID_SIZE bytes" }
    }

    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Security level of the key storage backing. */
enum class KeySecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE
}
