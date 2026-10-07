package org.ap.protocol.keystore

import antiproxy.core.crypto.*
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

/**
 * Signs messages using ECDSA-P256-SHA256 backed by Android Keystore.
 * The Keystore performs SHA-256 hashing internally — message is NOT pre-hashed.
 * Output DER signature is converted to canonical 64-byte raw format (r[32] || s[32]) with low-S normalized.
 */
class AndroidSigner(private val keyAlias: String) : Signer {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun sign(message: ByteArray): RawSignature {
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Private key entry not found for alias: $keyAlias")

        val privateKey: PrivateKey = entry.privateKey

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message)
        val derSignature = signature.sign()

        return derToRaw(derSignature)
    }
}
