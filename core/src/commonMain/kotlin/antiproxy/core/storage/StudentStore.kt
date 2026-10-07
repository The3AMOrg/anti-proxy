package antiproxy.core.storage

import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.KeySecurityLevel

/**
 * Student-side local persistence.
 * Platform implementations must use durable storage (SQLite WAL).
 */
interface StudentStore {
    /** Get current enrollment state. */
    fun getEnrollmentState(): EnrollmentState

    /** Save enrollment after initial key generation. */
    fun saveEnrollment(deviceKeyId: KeyId, securityLevel: KeySecurityLevel, enrolledAt: Long)

    /** Record local attendance event and enqueue sync. */
    fun recordAttendance(record: AttendanceRecord, syncEvent: SyncEvent)

    /** Get attendance history, newest first. */
    fun getAttendanceHistory(limit: Int = 50): List<AttendanceRecord>

    /** Get pending sync events. */
    fun getPendingSyncEvents(limit: Int = 100): List<SyncEvent>

    /** Update sync event state. */
    fun updateSyncState(eventId: String, state: SyncState, error: String? = null)

    /** Get/set last known time for clock rollback detection. */
    fun getLastKnownTime(): Long?
    fun setLastKnownTime(timeSeconds: Long)

    /** Get last successful sync time. */
    fun getLastSyncTime(): Long?
}
