package antiproxy.core.storage

import antiproxy.core.crypto.KeySecurityLevel

/** Sync event states for the local outbox. */
enum class SyncState {
    PENDING,
    SENDING,
    SYNCED,
    FAILED_PERMANENT
}

/** Local sync outbox event. */
data class SyncEvent(
    val eventId: String,              // UUID string
    val payload: ByteArray,           // serialized event payload
    val idempotencyKey: ByteArray,    // SHA-256 idempotency hash
    val createdAt: Long,              // UTC Unix seconds
    val syncState: SyncState,
    val retryCount: Int,
    val nextAttemptAt: Long?,         // UTC Unix seconds, null if not scheduled
    val lastError: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyncEvent) return false
        return eventId == other.eventId && payload.contentEquals(other.payload) &&
            idempotencyKey.contentEquals(other.idempotencyKey) &&
            createdAt == other.createdAt && syncState == other.syncState &&
            retryCount == other.retryCount && nextAttemptAt == other.nextAttemptAt &&
            lastError == other.lastError
    }
    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + idempotencyKey.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + syncState.hashCode()
        result = 31 * result + retryCount
        result = 31 * result + (nextAttemptAt?.hashCode() ?: 0)
        result = 31 * result + (lastError?.hashCode() ?: 0)
        return result
    }
}

/** Local attendance record. */
data class AttendanceRecord(
    val sessionId: ByteArray,         // 16 bytes
    val deviceKeyId: ByteArray,       // 16 bytes
    val authorityKeyId: ByteArray,    // 16 bytes
    val policyId: Int,
    val policyVersion: Int,
    val rssiMean: Double,
    val rssiVariance: Double,
    val sampleCount: Int,
    val recordedAt: Long,             // UTC Unix seconds
    val idempotencyKey: ByteArray     // SHA-256
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttendanceRecord) return false
        return sessionId.contentEquals(other.sessionId) && deviceKeyId.contentEquals(other.deviceKeyId) &&
            authorityKeyId.contentEquals(other.authorityKeyId) && policyId == other.policyId &&
            policyVersion == other.policyVersion && rssiMean == other.rssiMean &&
            rssiVariance == other.rssiVariance && sampleCount == other.sampleCount &&
            recordedAt == other.recordedAt && idempotencyKey.contentEquals(other.idempotencyKey)
    }
    override fun hashCode(): Int {
        var result = sessionId.contentHashCode()
        result = 31 * result + deviceKeyId.contentHashCode()
        return result
    }
}

/** Student enrollment state. */
data class EnrollmentState(
    val isEnrolled: Boolean,
    val deviceKeyId: ByteArray?,
    val keySecurityLevel: KeySecurityLevel?,
    val enrolledAt: Long?,
    val lastSyncAt: Long?
)
