package org.ap.protocol.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import antiproxy.core.crypto.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val DEFAULT_KEY_ALIAS = "antiproxy_device_signing_key"

data class KeyGenerationResult(
    val alias: String,
    val publicKey: PublicKeyBytes,
    val keyId: KeyId,
    val securityLevel: KeySecurityLevel
)

/**
 * Manages hardware-backed ECDSA P-256 keys in Android Keystore.
 * Prefers StrongBox where supported; falls back to TEE.
 * Private keys are non-exportable and never enter application-managed memory/storage.
 */
class AndroidKeyManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun generateKey(alias: String = DEFAULT_KEY_ALIAS): KeyGenerationResult {
        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        if (hasStrongBox) {
            try {
                return generateKeyWithStrongBox(alias)
            } catch (_: Exception) {
                try { keyStore.deleteEntry(alias) } catch (_: Exception) { }
            }
        }

        return generateKeyStandard(alias)
    }

    private fun generateKeyWithStrongBox(alias: String): KeyGenerationResult {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(true)
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        kpg.initialize(spec)
        val keyPair = kpg.generateKeyPair()
        val pubKeyBytes = extractPublicKeyBytes(keyPair.public)
        val keyId = computeKeyId(pubKeyBytes)
        return KeyGenerationResult(alias, pubKeyBytes, keyId, KeySecurityLevel.STRONGBOX)
    }

    private fun generateKeyStandard(alias: String): KeyGenerationResult {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        kpg.initialize(spec)
        val keyPair = kpg.generateKeyPair()
        val pubKeyBytes = extractPublicKeyBytes(keyPair.public)
        val keyId = computeKeyId(pubKeyBytes)
        return KeyGenerationResult(alias, pubKeyBytes, keyId, KeySecurityLevel.TEE)
    }

    fun getPublicKey(alias: String = DEFAULT_KEY_ALIAS): PublicKeyBytes? {
        val cert = keyStore.getCertificate(alias) ?: return null
        return extractPublicKeyBytes(cert.publicKey)
    }

    fun hasKey(alias: String = DEFAULT_KEY_ALIAS): Boolean = keyStore.containsAlias(alias)

    fun createSigner(alias: String = DEFAULT_KEY_ALIAS): Signer {
        check(hasKey(alias)) { "Key alias not found in Keystore: $alias" }
        return AndroidSigner(alias)
    }

    fun createVerifier(): Verifier = AndroidVerifier()

    private fun extractPublicKeyBytes(publicKey: java.security.PublicKey): PublicKeyBytes {
        val ecKey = publicKey as ECPublicKey
        val x = ecKey.w.affineX.toByteArray()
        val y = ecKey.w.affineY.toByteArray()

        val result = ByteArray(65)
        result[0] = 0x04.toByte()

        val xTrimmed = if (x.size > 32 && x[0] == 0.toByte()) x.copyOfRange(1, x.size) else x
        val yTrimmed = if (y.size > 32 && y[0] == 0.toByte()) y.copyOfRange(1, y.size) else y

        xTrimmed.copyInto(result, 1 + (32 - xTrimmed.size))
        yTrimmed.copyInto(result, 33 + (32 - yTrimmed.size))

        return PublicKeyBytes(result)
    }
}
