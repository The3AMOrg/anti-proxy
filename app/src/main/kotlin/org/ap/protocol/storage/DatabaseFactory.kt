package org.ap.protocol.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DB_NAME = "antiproxy_app.db"
private const val DB_VERSION = 1

/**
 * SQLite open helper configuring WAL mode and foreign keys.
 * Uses a singleton to ensure a single helper instance is shared across the entire app.
 */
class AntiproxyDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        @Volatile
        private var instance: AntiproxyDatabaseHelper? = null

        fun getInstance(context: Context): AntiproxyDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AntiproxyDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        Migrations.applyInitialSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Migrations.migrate(db, oldVersion, newVersion)
    }
}
