package org.ap.protocol.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.KeySecurityLevel
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.storage.*

/**
 * SQLite WAL implementation of StudentStore.
 */
class AndroidStudentStore(context: Context) : StudentStore {

    private val dbHelper = AntiproxyDatabaseHelper.getInstance(context)

    override fun getEnrollmentState(): EnrollmentState {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT device_key_id, key_security_level, enrolled_at, last_sync_at FROM enrollment WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val keyId = cursor.getBlob(0)
                val secLevelStr = cursor.getString(1)
                val secLevel = try { KeySecurityLevel.valueOf(secLevelStr) } catch (_: Exception) { KeySecurityLevel.SOFTWARE }
                val enrolledAt = cursor.getLong(2)
                val lastSyncAt = if (cursor.isNull(3)) null else cursor.getLong(3)
                return EnrollmentState(
                    isEnrolled = true,
                    deviceKeyId = keyId,
                    keySecurityLevel = secLevel,
                    enrolledAt = enrolledAt,
                    lastSyncAt = lastSyncAt
                )
            }
        }
        return EnrollmentState(
            isEnrolled = false,
            deviceKeyId = null,
            keySecurityLevel = null,
            enrolledAt = null,
            lastSyncAt = null
        )
    }

    override fun saveEnrollment(deviceKeyId: KeyId, securityLevel: KeySecurityLevel, enrolledAt: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
            put("device_key_id", deviceKeyId.bytes)
            put("key_security_level", securityLevel.name)
            put("enrolled_at", enrolledAt)
            putNull("last_sync_at")
        }
        db.insertWithOnConflict("enrollment", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun recordAttendance(record: AttendanceRecord, syncEvent: SyncEvent) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val recordValues = ContentValues().apply {
                put("session_id", record.sessionId)
                put("device_key_id", record.deviceKeyId)
                put("authority_key_id", record.authorityKeyId)
                put("policy_id", record.policyId)
                put("policy_version", record.policyVersion)
                put("rssi_mean", record.rssiMean)
                put("rssi_variance", record.rssiVariance)
                put("sample_count", record.sampleCount)
                put("recorded_at", record.recordedAt)
                put("idempotency_key", record.idempotencyKey)
                put("classroom_id", record.classroomId)
            }
            db.insertWithOnConflict("attendance_records", null, recordValues, SQLiteDatabase.CONFLICT_REPLACE)

            val eventValues = ContentValues().apply {
                put("event_id", syncEvent.eventId)
                put("payload", syncEvent.payload)
                put("idempotency_key", syncEvent.idempotencyKey)
                put("created_at", syncEvent.createdAt)
                put("sync_state", syncEvent.syncState.name)
                put("retry_count", syncEvent.retryCount)
                if (syncEvent.nextAttemptAt != null) put("next_attempt_at", syncEvent.nextAttemptAt) else putNull("next_attempt_at")
                if (syncEvent.lastError != null) put("last_error", syncEvent.lastError) else putNull("last_error")
            }
            db.insertWithOnConflict("sync_outbox", null, eventValues, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getAttendanceHistory(limit: Int): List<AttendanceRecord> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<AttendanceRecord>()
        db.rawQuery(
            """
            SELECT session_id, device_key_id, authority_key_id, policy_id, policy_version,
                   rssi_mean, rssi_variance, sample_count, recorded_at, idempotency_key, classroom_id
            FROM attendance_records
            ORDER BY recorded_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    AttendanceRecord(
                        sessionId = cursor.getBlob(0),
                        deviceKeyId = cursor.getBlob(1),
                        authorityKeyId = cursor.getBlob(2),
                        policyId = cursor.getInt(3),
                        policyVersion = cursor.getInt(4),
                        rssiMean = cursor.getDouble(5),
                        rssiVariance = cursor.getDouble(6),
                        sampleCount = cursor.getInt(7),
                        recordedAt = cursor.getLong(8),
                        idempotencyKey = cursor.getBlob(9),
                        classroomId = if (cursor.isNull(10)) null else cursor.getString(10)
                    )
                )
            }
        }
        return results
    }

    override fun getLastSyncTime(): Long? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT last_sync_at FROM enrollment WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    fun setLastSyncTime(timeSeconds: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("last_sync_at", timeSeconds)
        }
        db.update("enrollment", values, "id = 1", null)
    }

    override fun getPendingSyncEvents(limit: Int): List<SyncEvent> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<SyncEvent>()
        db.rawQuery(
            """
            SELECT event_id, payload, idempotency_key, created_at, sync_state, retry_count, next_attempt_at, last_error
            FROM sync_outbox
            WHERE sync_state = 'PENDING' OR sync_state = 'SENDING'
            ORDER BY created_at ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val state = try { SyncState.valueOf(cursor.getString(4)) } catch (_: Exception) { SyncState.PENDING }
                results.add(
                    SyncEvent(
                        eventId = cursor.getString(0),
                        payload = cursor.getBlob(1),
                        idempotencyKey = cursor.getBlob(2),
                        createdAt = cursor.getLong(3),
                        syncState = state,
                        retryCount = cursor.getInt(5),
                        nextAttemptAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                        lastError = if (cursor.isNull(7)) null else cursor.getString(7)
                    )
                )
            }
        }
        return results
    }

    override fun updateSyncState(eventId: String, state: SyncState, error: String?) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("sync_state", state.name)
            if (error != null) put("last_error", error) else putNull("last_error")
        }
        db.update("sync_outbox", values, "event_id = ?", arrayOf(eventId))
    }

    override fun getLastKnownTime(): Long? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT last_known_time FROM clock_guard WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    override fun setLastKnownTime(timeSeconds: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
            put("last_known_time", timeSeconds)
        }
        db.insertWithOnConflict("clock_guard", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveTrustedAuthorityKey(authorityKeyId: KeyId, publicKey: PublicKeyBytes) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("authority_key_id", authorityKeyId.bytes)
            put("public_key", publicKey.bytes)
        }
        db.insertWithOnConflict("trusted_authority_keys", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTrustedAuthorityKeys(): Map<KeyId, PublicKeyBytes> {
        val db = dbHelper.readableDatabase
        val map = mutableMapOf<KeyId, PublicKeyBytes>()
        db.rawQuery("SELECT authority_key_id, public_key FROM trusted_authority_keys", null).use { cursor ->
            while (cursor.moveToNext()) {
                val keyId = KeyId(cursor.getBlob(0))
                val pubKey = PublicKeyBytes(cursor.getBlob(1))
                map[keyId] = pubKey
            }
        }
        return map
    }
}
