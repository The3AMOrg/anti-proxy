package antiproxy.core.crypto

import java.security.*
import java.security.spec.*

/**
 * Test ECDSA signer using an in-memory JCA key pair.
 * NOT for production use. Android production uses AndroidSigner.
 */
class JvmTestSigner(private val privateKey: PrivateKey) : Signer {
    override fun sign(message: ByteArray): RawSignature {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message)
        val derBytes = sig.sign()
        return derToRaw(derBytes)
    }
}

/**
 * Test ECDSA verifier using JCA.
 */
class JvmTestVerifier : Verifier {
    override fun verify(publicKey: PublicKeyBytes, message: ByteArray, signature: RawSignature): Boolean {
        if (!isLowS(signature.s())) return false
        return try {
            val ecPublicKey = decodePublicKey(publicKey)
            val derSig = rawToDer(signature)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(ecPublicKey)
            sig.update(message)
            sig.verify(derSig)
        } catch (_: Exception) {
            false
        }
    }

    private fun decodePublicKey(publicKey: PublicKeyBytes): PublicKey {
        val bytes = publicKey.bytes
        val x = bytes.copyOfRange(1, 33)
        val y = bytes.copyOfRange(33, 65)
        val xInt = java.math.BigInteger(1, x)
        val yInt = java.math.BigInteger(1, y)
        val point = ECPoint(xInt, yInt)
        val keyFactory = KeyFactory.getInstance("EC")
        val paramSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        return keyFactory.generatePublic(ECPublicKeySpec(point, paramSpec))
    }
}

/** Generate an ECDSA P-256 key pair for testing. Returns (Signer, PublicKeyBytes, KeyId). */
fun generateTestKeyPair(): Triple<Signer, PublicKeyBytes, KeyId> {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    val keyPair = kpg.generateKeyPair()
    val ecPub = keyPair.public as java.security.interfaces.ECPublicKey
    val x = ecPub.w.affineX.toByteArray()
    val y = ecPub.w.affineY.toByteArray()
    val pubBytes = ByteArray(65)
    pubBytes[0] = 0x04
    val xTrimmed = if (x.size > 32 && x[0] == 0.toByte()) x.copyOfRange(1, x.size) else x
    xTrimmed.copyInto(pubBytes, 1 + (32 - xTrimmed.size))
    val yTrimmed = if (y.size > 32 && y[0] == 0.toByte()) y.copyOfRange(1, y.size) else y
    yTrimmed.copyInto(pubBytes, 33 + (32 - yTrimmed.size))
    val publicKey = PublicKeyBytes(pubBytes)
    val keyId = computeKeyId(publicKey)
    return Triple(JvmTestSigner(keyPair.private), publicKey, keyId)
}
