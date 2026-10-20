package com.soakula.wacr.ui.library

import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class RecordingActionsViewModelTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var database: AppDatabase
    private lateinit var app: WacrApp
    private val store = ViewModelStore()

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 1)
        // Synthetic in-memory database only; production factory remains SQLCipher.
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
    }

    @After fun cleanup() {
        store.clear()
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun renameDeleteAndUndoUsePersistentDataOperations() = runBlocking {
        val id = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(id, 1000, 1, "MIC", status = RecordingStatus.FINALIZED))
        val model = model("first")
        model.rename(id, "  Synthetic title  ")
        await { model.actions.value.renameVersion == 1L && !model.actions.value.busy }
        assertEquals("Synthetic title", database.recordings().recording(id)?.title)
        model.delete(id)
        await { model.actions.value.undo != null && !model.actions.value.busy }
        val pending = checkNotNull(model.actions.value.undo)
        assertEquals(pending, database.recordings().deletion(id))
        val recreated = model("second")
        recreated.restoreUndo()
        await { recreated.actions.value.undo == pending && !recreated.actions.value.busy }
        recreated.undo(pending)
        await { recreated.actions.value.undo == null && !recreated.actions.value.busy }
        assertNull(database.recordings().deletion(id))
        assertEquals("Synthetic title", database.recordings().recording(id)?.title)
    }

    @Test fun activeDeleteAndInvalidTitleSurfaceFailuresWithoutMutation() = runBlocking {
        val id = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(id, 1000, 1, "MIC"))
        val model = model("first")
        model.rename(id, "\u0001bad")
        await { model.actions.value.error == R.string.library_rename_error && !model.actions.value.busy }
        assertNull(database.recordings().recording(id)?.title)
        assertEquals(0L, model.actions.value.renameVersion)
        model.delete(id)
        await { model.actions.value.error == R.string.library_delete_error && !model.actions.value.busy }
        assertNull(database.recordings().deletion(id))
        assertNotNull(database.recordings().recording(id))
        model.dismissError()
        assertNull(model.actions.value.error)
    }

    private fun model(key: String) = LibraryViewModel(app) { flowOf(emptyList()) }.also { store.put(key, it) }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(10)
        }
    }
}
