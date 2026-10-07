package antiproxy.core.storage

import antiproxy.core.attendance.TrustLease
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.crypto.KeyId
import antiproxy.core.protocol.Session

/**
 * Authority-side local persistence.
 * The attendance transaction (validate + insert + outbox) MUST be atomic.
 * Attendance record MUST be durable before ACK is sent.
 */
interface AuthorityStore {
    /** Store/retrieve trust lease. */
    fun saveTrustLease(lease: TrustLease)
    fun getTrustLease(): TrustLease?

    /** Store active session. */
    fun saveSession(session: Session)
    fun getSession(sessionId: ByteArray): Session?
    fun deleteSession(sessionId: ByteArray)

    /** Student public key cache. */
    fun cacheStudentKey(keyId: KeyId, publicKey: PublicKeyBytes)
    fun getStudentKey(keyId: ByteArray): PublicKeyBytes?
    fun isDeviceRevoked(keyId: ByteArray): Boolean
    fun revokeDevice(keyId: ByteArray)

    /**
     * Atomically commit attendance decision and enqueue sync event.
     * MUST be transactional: BEGIN -> insert attendance -> insert/update sync outbox -> COMMIT.
     * Returns true on success, false on storage failure.
     */
    fun commitAttendance(record: AttendanceRecord, syncEvent: SyncEvent): Boolean

    /** Check if attendance already recorded for this session+device. */
    fun hasAttendance(sessionId: ByteArray, deviceKeyId: ByteArray): Boolean

    /** Get attendance count for current session. */
    fun getAttendanceCount(sessionId: ByteArray): Int

    /** Get pending sync events. */
    fun getPendingSyncEvents(limit: Int = 100): List<SyncEvent>
    fun updateSyncState(eventId: String, state: SyncState, error: String? = null)

    /** Clock rollback detection. */
    fun getLastKnownTime(): Long?
    fun setLastKnownTime(timeSeconds: Long)
}
