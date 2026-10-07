package antiproxy.core.crypto

import java.security.MessageDigest

/**
 * SHA-256 digest computation using standard platform crypto provider.
 */
fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
