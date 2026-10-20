package com.soakula.wacr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.google.crypto.tink.Aead
import com.soakula.wacr.crypto.DatabaseSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@Database(
    entities = [
        CallSessionEntity::class, RecordingEntity::class, SegmentEntity::class, RouteEventEntity::class,
        ContactRuleEntity::class, PendingRecordingDeletion::class
    ],
    version = 4, exportSchema = true
)
@TypeConverters(StorageConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun contactRules(): ContactRuleDao

    companion object {
        const val NAME = "wacr.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contact_rule (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, display_name TEXT NOT NULL,
                        match_key TEXT NOT NULL, kind TEXT NOT NULL, mode_override TEXT, created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contact_rule_kind_match_key ON contact_rule (kind, match_key)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recording_deletion (
                        recording_id TEXT NOT NULL PRIMARY KEY, request_id TEXT NOT NULL,
                        undo_until_elapsed_ms INTEGER NOT NULL, boot_count INTEGER NOT NULL,
                        committed INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_session ADD COLUMN group_known INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE call_session SET group_known = 1 WHERE is_group = 1")
            }
        }

        suspend fun open(context: Context, encryption: Aead): AppDatabase = withContext(Dispatchers.IO) {
            val application = context.applicationContext
            val password = DatabaseSecret.open(application, application.getDatabasePath(NAME), encryption).load()
            var database: AppDatabase? = null
            var opened = false
            try {
                System.loadLibrary("sqlcipher")
                database = Room.databaseBuilder(application, AppDatabase::class.java, NAME)
                    .openHelperFactory(SupportSQLiteOpenHelper.Factory { CipherOpenHelper(it, password) })
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                database.openHelper.writableDatabase
                opened = true
                database
            } finally {
                if (!opened) {
                    try {
                        database?.close()
                    } finally {
                        password.fill(0)
                    }
                }
            }
        }
    }
}

class StorageConverters {
    @TypeConverter fun uuidToString(value: UUID): String = value.toString()
    @TypeConverter fun stringToUuid(value: String): UUID = UUID.fromString(value)
}
