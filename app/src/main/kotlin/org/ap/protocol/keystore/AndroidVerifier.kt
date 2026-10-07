package org.ap.protocol.keystore

import antiproxy.core.crypto.*
import java.math.BigInteger
import java.security.*
import java.security.spec.*

/**
 * Verifies ECDSA-P256-SHA256 signatures using JCA.
 * Enforces canonical low-S and valid scalar bounds before delegating to JCA.
 */
class AndroidVerifier : Verifier {

    override fun verify(publicKey: PublicKeyBytes, message: ByteArray, signature: RawSignature): Boolean {
        if (!isLowS(signature.s())) {
            return false
        }
        if (!isValidScalar(signature.r())) {
            return false
        }

        return try {
            val ecPublicKey = decodePublicKey(publicKey)
            val derSignature = rawToDer(signature)

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(ecPublicKey)
            sig.update(message)
            sig.verify(derSignature)
        } catch (_: Exception) {
            false
        }
    }

    private fun decodePublicKey(publicKey: PublicKeyBytes): PublicKey {
        val bytes = publicKey.bytes
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Invalid uncompressed point" }

        val xBytes = bytes.copyOfRange(1, 33)
        val yBytes = bytes.copyOfRange(33, 65)

        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val keyFactory = KeyFactory.getInstance("EC")
        val paramSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        return keyFactory.generatePublic(ECPublicKeySpec(point, paramSpec))
    }
}
