package org.ap.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.protocol.Session
import org.ap.protocol.storage.AndroidAuthorityStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Regression test: byte IDs are stored as BLOBs, so lookups must compare hex(column),
 * not the hex text. Before the fix every lookup returned null (UNKNOWN_SESSION / UNKNOWN_DEVICE).
 */
@RunWith(AndroidJUnit4::class)
class AndroidAuthorityStoreTest {

    private val store = AndroidAuthorityStore(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    @Test
    fun sessionIsFoundAfterSave() {
        val sessionId = randomBytes(16)
        store.saveSession(
            Session(
                authorityKeyId = randomBytes(16),
                sessionId = sessionId,
                nonce = randomBytes(16),
                issuedAt = 1L,
                expiresAt = 2L,
                policyId = 1
            )
        )

        assertNotNull(store.getSession(sessionId))

        store.deleteSession(sessionId)
        assertNull(store.getSession(sessionId))
    }

    @Test
    fun studentKeyIsFoundAndRevocable() {
        val keyId = KeyId(randomBytes(16))
        // 0x04 prefix = uncompressed P-256 point, which PublicKeyBytes requires
        store.cacheStudentKey(keyId, PublicKeyBytes(byteArrayOf(0x04) + randomBytes(64)))

        assertNotNull(store.getStudentKey(keyId.bytes))
        assertFalse(store.isDeviceRevoked(keyId.bytes))

        store.revokeDevice(keyId.bytes)
        assertTrue(store.isDeviceRevoked(keyId.bytes))
    }
}
