package com.soakula.wacr.data

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactRuleMigrationTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    // Room compares Android '/' basenames; resolve the test helper's name on Windows.
    private val factory = SupportSQLiteOpenHelper.Factory { configuration ->
        val delegate = FrameworkSQLiteOpenHelperFactory().create(configuration)
        object : SupportSQLiteOpenHelper by delegate {
            override val databaseName: String?
                get() = delegate.databaseName?.let { configuration.context.getDatabasePath(it).absolutePath }
        }
    }
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), factory
    )

    @After fun deleteTestDatabase() { application.deleteDatabase(TEST_DB) }

    @Test fun preservesExistingRecordingsAndCreatesValidatedRuleSchema() = runBlocking {
        val id = UUID.randomUUID()
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("""
                INSERT INTO call_session (id, wa_display_name, match_key, is_group, direction, call_type, decision)
                VALUES (7, 'Synthetic', 'synthetic', 0, 'OUTGOING', 'VOICE', 'MANUAL')
            """.trimIndent())
            db.execSQL("""
                INSERT INTO recording (id, started_at, channels, capture_source, session_id, status, has_video, starred)
                VALUES (?, 1000, 1, 'VOICE_RECOGNITION', 7, 'RECORDING', 0, 1)
            """.trimIndent(), arrayOf(id.toString()))
            db.execSQL("INSERT INTO segment (recording_id, idx, duration_us, plain_bytes, written_at) VALUES (?, 1, 64000, 20, 1064)",
                arrayOf(id.toString()))
            db.execSQL("INSERT INTO route_event (recording_id, at_ms, route) VALUES (?, 0, 'EARPIECE')", arrayOf(id.toString()))
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.execSQL("""
                INSERT INTO contact_rule (id, display_name, match_key, kind, created_at)
                VALUES (9, 'Existing', 'existing', 'CONTACT', 1500)
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3).close()
        val db = Room.databaseBuilder(application, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
        try {
            val recording = checkNotNull(db.recordings().recording(id))
            assertTrue(recording.starred)
            assertEquals(7L, recording.sessionId)
            assertEquals("Synthetic", db.recordings().session(7)?.waDisplayName)
            assertFalse(checkNotNull(db.recordings().session(7)).isGroupKnown)
            assertEquals(64_000L, db.recordings().segments(id).single().durationUs)
            assertEquals(AudioRoute.EARPIECE, db.recordings().routes(id).single().route)
            val rules = ContactRuleRepository(db)
            assertEquals("Existing", rules.matchingContact("existing")?.displayName)
            assertTrue(db.recordings().pendingDeletions().isEmpty())
            val added = rules.addContact("Demo", 2000)
            assertEquals(added, rules.matchingContact("demo"))
        } finally {
            db.close()
        }
    }

    @Test fun groupConfidenceMigrationPreservesDataWithoutCertifyingLegacyOneToOneDefaults() = runBlocking {
        val id = UUID.randomUUID()
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("""
                INSERT INTO call_session (id, wa_display_name, is_group, direction, call_type, decision)
                VALUES (7, 'Synthetic caller', 0, 'OUTGOING', 'VOICE', 'MANUAL'),
                       (8, 'Synthetic group', 1, 'INCOMING', 'VIDEO', 'MANUAL')
            """.trimIndent())
            db.execSQL("""
                INSERT INTO recording (id, started_at, channels, capture_source, session_id, status, has_video, starred)
                VALUES (?, 1000, 1, 'VOICE_RECOGNITION', 7, 'RECORDING', 0, 1)
            """.trimIndent(), arrayOf(id.toString()))
            db.execSQL("""
                INSERT INTO recording_deletion (recording_id, request_id, undo_until_elapsed_ms, boot_count, committed)
                VALUES (?, ?, 9000, 1, 0)
            """.trimIndent(), arrayOf(id.toString(), UUID.randomUUID().toString()))
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4).close()
        val db = Room.databaseBuilder(application, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
        try {
            val legacy = checkNotNull(db.recordings().session(7))
            assertFalse(legacy.isGroup)
            assertFalse(legacy.isGroupKnown)
            assertEquals(CallType.VOICE, legacy.callType)
            assertTrue(checkNotNull(db.recordings().session(8)).isGroupKnown)
            assertTrue(checkNotNull(db.recordings().session(8)).isGroup)
            assertEquals(id, db.recordings().pendingDeletions().single().recordingId)
            assertEquals(7L, db.recordings().recording(id)?.sessionId)
            val unknownId = db.recordings().insertSession(CallSessionEntity())
            val unknown = checkNotNull(db.recordings().session(unknownId))
            assertEquals(CallType.UNKNOWN, unknown.callType)
            assertFalse(unknown.isGroupKnown)
            val knownId = db.recordings().insertSession(CallSessionEntity(isGroup = false, isGroupKnown = true))
            assertTrue(checkNotNull(db.recordings().session(knownId)).isGroupKnown)
        } finally {
            db.close()
        }
    }

    @Test fun typeHistoryMigrationPreservesSessionsRecordingsAndAddsCascadingEvents() = runBlocking {
        val id = UUID.randomUUID()
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL("""
                INSERT INTO call_session (id, is_group, group_known, direction, call_type, decision, connected_at)
                VALUES (7, 0, 1, 'INCOMING', 'VOICE', 'MANUAL', 1000)
            """.trimIndent())
            db.execSQL("""
                INSERT INTO recording (id, started_at, channels, capture_source, session_id, status, has_video, starred)
                VALUES (?, 1000, 1, 'VOICE_RECOGNITION', 7, 'FINALIZED', 0, 1)
            """.trimIndent(), arrayOf(id.toString()))
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5).close()
        val db = Room.databaseBuilder(application, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(CallType.VOICE, db.recordings().session(7)?.callType)
            assertTrue(checkNotNull(db.recordings().session(7)).isGroupKnown)
            assertEquals(7L, db.recordings().recording(id)?.sessionId)
            assertTrue(db.recordings().sessionTypeEvents(7).isEmpty())
            db.recordings().insertCallTypeEvent(CallTypeEventEntity(7, CallType.VOICE, CallType.VIDEO, 2000))
            assertEquals(CallType.VIDEO, db.recordings().sessionTypeEvents(7).single().to)
            db.recordings().deleteSession(7)
            assertTrue(db.recordings().sessionTypeEvents(7).isEmpty())
            assertNull(db.recordings().recording(id)?.sessionId)
        } finally {
            db.close()
        }
    }

    @Test fun waveformMigrationPreservesExistingRowsAndAddsCascadingBoundedMetadata() = runBlocking {
        val id = UUID.randomUUID()
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL("""
                INSERT INTO recording (id, started_at, channels, capture_source, status, has_video, starred)
                VALUES (?, 1000, 1, 'MIC', 'FINALIZED', 0, 1)
            """.trimIndent(), arrayOf(id.toString()))
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6).close()
        val db = Room.databaseBuilder(application, AppDatabase::class.java, TEST_DB).build()
        try {
            assertTrue(checkNotNull(db.recordings().recording(id)).starred)
            assertNull(db.recordings().waveform(id))
            val peaks = com.soakula.wacr.recording.WaveformPeaks(1600, 1024, byteArrayOf(100))
            db.recordings().putWaveform(RecordingWaveformEntity.encode(id, peaks))
            assertArrayEquals(byteArrayOf(100), db.recordings().waveform(id)?.decode()?.encodedPeaks())
            db.recordings().deleteRecording(id)
            assertNull(db.recordings().waveform(id))
        } finally { db.close() }
    }

    private companion object { const val TEST_DB = "synthetic-contact-rule-migration.db" }
}
