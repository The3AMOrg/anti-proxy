package antiproxy.core.crypto

import antiproxy.core.protocol.KEY_ID_SIZE

/**
 * Compute key identifier from canonical public key encoding.
 * key_id = first 16 bytes of SHA-256(public_key_bytes)
 */
fun computeKeyId(publicKey: PublicKeyBytes): KeyId {
    val hash = sha256(publicKey.bytes)
    return KeyId(hash.copyOfRange(0, KEY_ID_SIZE))
}
