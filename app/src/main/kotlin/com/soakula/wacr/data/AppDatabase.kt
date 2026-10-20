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
        ContactRuleEntity::class, PendingRecordingDeletion::class, CallTypeEventEntity::class,
        RecordingWaveformEntity::class, CapabilityResult::class
    ],
    version = 7, exportSchema = true
)
@TypeConverters(StorageConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun contactRules(): ContactRuleDao
    abstract fun capabilities(): CapabilityDao

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
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS call_type_event (
                        session_id INTEGER NOT NULL, from_type TEXT NOT NULL, to_type TEXT NOT NULL,
                        at_ms INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES call_session(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_type_event_session_id_at_ms ON call_type_event (session_id, at_ms)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recording_waveform (
                        recording_id TEXT NOT NULL PRIMARY KEY, frames_per_peak INTEGER NOT NULL,
                        frame_count INTEGER NOT NULL, peaks BLOB NOT NULL,
                        FOREIGN KEY(recording_id) REFERENCES recording(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS capability_result (
                        run_id TEXT NOT NULL, device_model TEXT NOT NULL, os_build TEXT NOT NULL,
                        wa_package TEXT NOT NULL, wa_version TEXT NOT NULL, wa_version_code INTEGER NOT NULL, route TEXT NOT NULL,
                        source TEXT NOT NULL, status TEXT NOT NULL, tested_at INTEGER NOT NULL,
                        recording_id TEXT, sample_frames INTEGER NOT NULL, own_rms_db REAL,
                        remote_rms_db REAL, zero_pct REAL, client_silenced INTEGER,
                        own_ok INTEGER, remote_ok INTEGER, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        FOREIGN KEY(recording_id) REFERENCES recording(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_capability_result_run_id_source ON capability_result (run_id, source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_result_route_source ON capability_result (route, source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_result_recording_id ON capability_result (recording_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_result_tested_at ON capability_result (tested_at)")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
    @TypeConverter fun uuidToString(value: UUID?): String? = value?.toString()
    @TypeConverter fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)
}
