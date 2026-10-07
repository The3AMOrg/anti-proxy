package antiproxy.core.integration

import antiproxy.core.protocol.StatusCode
import antiproxy.core.storage.AttendanceRecord
import antiproxy.core.storage.EnrollmentState

/**
 * Student-side API for UI team integration.
 * The UI team calls these methods; the BLE/crypto subsystem implements them.
 */
interface StudentApi {
    /** Get current enrollment state. */
    fun getEnrollmentState(): EnrollmentState

    /** Get recent attendance records. */
    fun getAttendanceHistory(limit: Int = 50): List<AttendanceRecord>

    /** Get last successful backend sync time (null if never synced). */
    fun getLastSyncTime(): Long?

    /**
     * Trigger attendance verification flow.
     * Scans for Authority, connects, verifies session, collects RSSI,
     * signs proof, submits, and returns the Authority's response.
     *
     * This is a suspend function because BLE operations are asynchronous.
     */
    suspend fun performAttendance(): AttendanceResult
}

/** Result of a student attendance verification attempt. */
sealed class AttendanceResult {
    data class Success(val record: AttendanceRecord) : AttendanceResult()
    data class Rejected(val status: StatusCode, val description: String) : AttendanceResult()
    data class Error(val message: String, val cause: Throwable? = null) : AttendanceResult()
    object NoAuthorityFound : AttendanceResult()
    object Timeout : AttendanceResult()
}
