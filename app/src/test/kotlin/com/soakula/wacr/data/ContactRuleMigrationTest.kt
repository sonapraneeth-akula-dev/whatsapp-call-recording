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
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).close()
        val db = Room.databaseBuilder(application, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val recording = checkNotNull(db.recordings().recording(id))
            assertTrue(recording.starred)
            assertEquals(7L, recording.sessionId)
            assertEquals("Synthetic", db.recordings().session(7)?.waDisplayName)
            assertEquals(64_000L, db.recordings().segments(id).single().durationUs)
            assertEquals(AudioRoute.EARPIECE, db.recordings().routes(id).single().route)
            val rules = ContactRuleRepository(db)
            val added = rules.addContact("Demo", 2000)
            assertEquals(added, rules.matchingContact("demo"))
        } finally {
            db.close()
        }
    }

    private companion object { const val TEST_DB = "synthetic-contact-rule-migration.db" }
}
