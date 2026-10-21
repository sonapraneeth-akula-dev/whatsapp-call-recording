package com.soakula.wacr.ui.benchmark

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.RecordingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class PerformanceFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: AppDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var app: WacrApp
    private var handler: Thread.UncaughtExceptionHandler? = null

    @Before fun stores() {
        handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        app = RuntimeEnvironment.getApplication() as WacrApp
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val settings = temporary.newFile("settings.preferences_pb")
        preferences = AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { settings })
        ReflectionHelpers.setField(app, "openDatabase", database)
        ReflectionHelpers.setField(app, "preferences\$delegate", lazy { preferences })
    }

    @After fun close() = runBlocking {
        database.close()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    @Test fun seedsExactlyOneThousandSyntheticMetadataRowsAndIsIdempotent() = runBlocking {
        val fixture = Robolectric.buildActivity(PerformanceFixtureActivity::class.java).get()
        fixture.seed(app)
        val first = database.recordings().observeAll().first()
        assertEquals(1_000, first.size)
        assertEquals(100, first.map { it.session?.waDisplayName }.distinct().size)
        assertTrue(first.all {
            it.recording.audioPath == null && it.recording.videoPath == null && it.recording.remoteCaptured == null
        })
        assertTrue(preferences.values.first().onboardingDone)
        fixture.seed(app)
        assertEquals(first, database.recordings().observeAll().first())
        assertFalse(File(app.filesDir, "rec").exists())
    }

    @Test fun conflictingDataFailsWithoutOverwritingOrCompletingPreparation() = runBlocking {
        val fixture = Robolectric.buildActivity(PerformanceFixtureActivity::class.java).get()
        val original = RecordingEntity(
            UUID.nameUUIDFromBytes("wacr-benchmark-999".toByteArray(Charsets.UTF_8)),
            1, 1, "MIC", title = "Changed sample"
        )
        database.recordings().insertRecording(original)
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.seed(app) } }
        assertEquals(original, database.recordings().recording(original.id))
        assertEquals(1, database.recordings().observeAll().first().size)
        assertNull(database.recordings().session(1_000_000))
        assertFalse(preferences.values.first().onboardingDone)
    }
}
