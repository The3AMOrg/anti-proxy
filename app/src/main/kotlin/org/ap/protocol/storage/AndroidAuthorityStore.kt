package org.ap.protocol.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import antiproxy.core.attendance.TrustLease
import antiproxy.core.crypto.KeyId
import antiproxy.core.crypto.PublicKeyBytes
import antiproxy.core.protocol.Session
import antiproxy.core.storage.*

/**
 * SQLite WAL implementation of AuthorityStore.
 * The attendance transaction (insert decision + insert outbox) is fully atomic (committed before ACK).
 */
class AndroidAuthorityStore(context: Context) : AuthorityStore {

    private val dbHelper = AntiproxyDatabaseHelper.getInstance(context)

    override fun saveTrustLease(lease: TrustLease) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
            put("authority_device_id", lease.authorityDeviceId)
            put("issued_at", lease.issuedAt)
            put("expires_at", lease.expiresAt)
            put("policy_version", lease.policyVersion)
            put("authority_key_id", lease.authorityKeyId)
            put("server_signature", lease.serverSignature)
        }
        db.insertWithOnConflict("authority_trust_lease", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getTrustLease(): TrustLease? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT authority_device_id, issued_at, expires_at, policy_version, authority_key_id, server_signature FROM authority_trust_lease WHERE id = 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return TrustLease(
                    authorityDeviceId = cursor.getBlob(0),
                    issuedAt = cursor.getLong(1),
                    expiresAt = cursor.getLong(2),
                    policyVersion = cursor.getInt(3),
                    authorityKeyId = cursor.getBlob(4),
                    serverSignature = cursor.getBlob(5)
                )
            }
        }
        return null
    }

    override fun saveSession(session: Session) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("session_id", session.sessionId)
            put("authority_key_id", session.authorityKeyId)
            put("nonce", session.nonce)
            put("issued_at", session.issuedAt)
            put("expires_at", session.expiresAt)
            put("policy_id", session.policyId)
        }
        db.insertWithOnConflict("authority_sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getSession(sessionId: ByteArray): Session? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT authority_key_id, nonce, issued_at, expires_at, policy_id FROM authority_sessions WHERE hex(session_id) = ?",
            arrayOf(sessionId.toHex())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return Session(
                    authorityKeyId = cursor.getBlob(0),
                    sessionId = sessionId,
                    nonce = cursor.getBlob(1),
                    issuedAt = cursor.getLong(2),
                    expiresAt = cursor.getLong(3),
                    policyId = cursor.getInt(4)
                )
            }
        }
        return null
    }

    override fun deleteSession(sessionId: ByteArray) {
        val db = dbHelper.writableDatabase
        db.delete("authority_sessions", "hex(session_id) = ?", arrayOf(sessionId.toHex()))
    }

    override fun cacheStudentKey(keyId: KeyId, publicKey: PublicKeyBytes) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("key_id", keyId.bytes)
            put("public_key", publicKey.bytes)
            put("is_revoked", 0)
        }
        db.insertWithOnConflict("student_key_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getStudentKey(keyId: ByteArray): PublicKeyBytes? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT public_key FROM student_key_cache WHERE hex(key_id) = ?",
            arrayOf(keyId.toHex())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return PublicKeyBytes(cursor.getBlob(0))
            }
        }
        return null
    }

    fun getAllStudentKeys(): List<Pair<KeyId, PublicKeyBytes>> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<Pair<KeyId, PublicKeyBytes>>()
        db.rawQuery("SELECT key_id, public_key FROM student_key_cache WHERE is_revoked = 0", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(Pair(KeyId(cursor.getBlob(0)), PublicKeyBytes(cursor.getBlob(1))))
            }
        }
        return list
    }

    override fun isDeviceRevoked(keyId: ByteArray): Boolean {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT is_revoked FROM student_key_cache WHERE hex(key_id) = ?",
            arrayOf(keyId.toHex())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) == 1
            }
        }
        return false
    }

    override fun revokeDevice(keyId: ByteArray) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("is_revoked", 1)
        }
        db.update("student_key_cache", values, "hex(key_id) = ?", arrayOf(keyId.toHex()))
    }

    override fun commitAttendance(record: AttendanceRecord, syncEvent: SyncEvent): Boolean {
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
            }
            val insertedRow = db.insertWithOnConflict("attendance_records", null, recordValues, SQLiteDatabase.CONFLICT_ABORT)
            if (insertedRow == -1L) {
                return false
            }

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
            return true
        } catch (_: Exception) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    override fun hasAttendance(sessionId: ByteArray, deviceKeyId: ByteArray): Boolean {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT 1 FROM attendance_records WHERE hex(session_id) = ? AND hex(device_key_id) = ?",
            arrayOf(sessionId.toHex(), deviceKeyId.toHex())
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    override fun getAttendanceCount(sessionId: ByteArray): Int {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM attendance_records WHERE hex(session_id) = ?",
            arrayOf(sessionId.toHex())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    fun getAttendanceRecordsForSession(sessionId: ByteArray): List<AttendanceRecord> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<AttendanceRecord>()
        db.rawQuery(
            """
            SELECT session_id, device_key_id, authority_key_id, policy_id, policy_version,
                   rssi_mean, rssi_variance, sample_count, recorded_at, idempotency_key
            FROM attendance_records
            WHERE hex(session_id) = ?
            ORDER BY recorded_at DESC
            """.trimIndent(),
            arrayOf(sessionId.toHex())
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
                        idempotencyKey = cursor.getBlob(9)
                    )
                )
            }
        }
        return results
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

    // Matches SQLite hex(), which returns uppercase, for comparing against BLOB columns
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
