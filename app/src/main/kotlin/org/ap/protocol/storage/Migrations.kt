package org.ap.protocol.storage

import android.database.sqlite.SQLiteDatabase

/**
 * Explicit versioned database migrations for AntiProxy.
 */
object Migrations {

    fun applyInitialSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS enrollment (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_key_id BLOB NOT NULL,
                key_security_level TEXT NOT NULL,
                enrolled_at INTEGER NOT NULL,
                last_sync_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attendance_records (
                session_id BLOB NOT NULL,
                device_key_id BLOB NOT NULL,
                authority_key_id BLOB NOT NULL,
                policy_id INTEGER NOT NULL,
                policy_version INTEGER NOT NULL,
                rssi_mean REAL NOT NULL,
                rssi_variance REAL NOT NULL,
                sample_count INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                idempotency_key BLOB NOT NULL UNIQUE,
                classroom_id TEXT,
                PRIMARY KEY (session_id, device_key_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_outbox (
                event_id TEXT PRIMARY KEY,
                payload BLOB NOT NULL,
                idempotency_key BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                sync_state TEXT NOT NULL,
                retry_count INTEGER NOT NULL,
                next_attempt_at INTEGER,
                last_error TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS authority_trust_lease (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                authority_device_id BLOB NOT NULL,
                issued_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                policy_version INTEGER NOT NULL,
                authority_key_id BLOB NOT NULL,
                server_signature BLOB NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS authority_sessions (
                session_id BLOB PRIMARY KEY,
                authority_key_id BLOB NOT NULL,
                nonce BLOB NOT NULL,
                issued_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                policy_id INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS student_key_cache (
                key_id BLOB PRIMARY KEY,
                public_key BLOB NOT NULL,
                is_revoked INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trusted_authority_keys (
                authority_key_id BLOB PRIMARY KEY,
                public_key BLOB NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clock_guard (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                last_known_time INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: a student's check-in remembers its class, so one teacher's classes don't share a history
        if (oldVersion < 2) db.execSQL("ALTER TABLE attendance_records ADD COLUMN classroom_id TEXT")
    }
}
