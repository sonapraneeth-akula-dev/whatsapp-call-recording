package com.soakula.wacr.ui.allowlist

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppDatabase
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class AllowlistViewModelTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var database: AppDatabase
    private lateinit var app: WacrApp
    private lateinit var model: AllowlistViewModel
    private val store = ViewModelStore()
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val provider = SelectedContacts()
    private val selected = Uri.parse("content://com.android.contacts/contacts/lookup/synthetic/7")

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        // Synthetic in-memory data only; production uses the unchanged SQLCipher factory.
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        model = AllowlistViewModel(app)
        store.put("allowlist", model)
        observers.launch { model.state.collect {} }
    }

    @After fun cleanup() {
        observers.cancel()
        store.clear()
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun selectedNameOnlyIsReadAndReactiveRemoveUndoRestoresTheExactRule() = runBlocking {
        add()
        assertEquals(selected, provider.queried)
        assertEquals(listOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), provider.projection)
        assertTrue(checkNotNull(provider.cursor).isClosed)
        val rule = rules().single()
        assertEquals("Synthetic Contact", rule.displayName)
        model.remove(rule.id)
        await { model.actions.value.removed != null && !model.actions.value.busy && rules().isEmpty() }
        val ticket = checkNotNull(model.actions.value.removed)
        model.undo(ticket)
        await { model.actions.value.removed == null && !model.actions.value.busy && rules().size == 1 }
        assertEquals(rule, rules().single())
    }

    @Test fun normalizedDuplicateIsDisclosedWithoutReplacingExistingRule() = runBlocking {
        add()
        val original = rules().single()
        provider.name = "Ｓｙｎｔｈｅｔｉｃ Contact 😀"
        model.addPickedContact(selected)
        await { model.actions.value.error == R.string.allowlist_duplicate && !model.actions.value.busy }
        assertEquals(original, rules().single())
    }

    @Test fun undoConflictNeverOverwritesNewRule() = runBlocking {
        add()
        model.remove(rules().single().id)
        await { model.actions.value.removed != null && !model.actions.value.busy && rules().isEmpty() }
        val removed = checkNotNull(model.actions.value.removed)
        add()
        val replacement = rules().single()
        assertNotEquals(removed.rule.id, replacement.id)
        model.undo(removed)
        await { model.actions.value.error == R.string.allowlist_undo_conflict && !model.actions.value.busy }
        assertEquals(replacement, rules().single())
    }

    @Test fun undoExpiresAtFiveSecondsAndDoesNotChangeStoredRules() = runBlocking {
        add()
        model.remove(rules().single().id)
        await { model.actions.value.removed != null && !model.actions.value.busy && rules().isEmpty() }
        val removed = checkNotNull(model.actions.value.removed)
        ShadowSystemClock.advanceBy(Duration.ofMillis(removed.deadlineMs - SystemClock.elapsedRealtime()))
        model.undo(removed)
        await { model.actions.value.error == R.string.allowlist_undo_error && !model.actions.value.busy }
        assertTrue(rules().isEmpty())
        model.dismissUndo(removed)
        assertNull(model.actions.value.removed)
    }

    @Test fun broadOrForeignUrisAreRejectedBeforeQueryingAndProviderFailuresAreExplicit() = runBlocking {
        await { model.state.value is AllowlistUiState.Ready }
        for (uri in listOf("content://com.android.contacts/contacts", "content://other/contacts/7",
                "file:///contacts/7", "content://com.android.contacts/data/7")) {
            model.dismissError()
            model.addPickedContact(Uri.parse(uri))
            await { model.actions.value.error == R.string.allowlist_pick_error && !model.actions.value.busy }
        }
        assertEquals(0, provider.queries)
        provider.denied = true
        rejectedSelection()
        provider.denied = false
        provider.rows = 0
        rejectedSelection()
        provider.rows = 2
        rejectedSelection()
        provider.rows = 1
        provider.name = "😀"
        rejectedSelection()
        assertTrue(rules().isEmpty())
    }

    private suspend fun rejectedSelection() {
        model.dismissError()
        model.addPickedContact(selected)
        await { model.actions.value.error == R.string.allowlist_pick_error && !model.actions.value.busy }
    }

    private suspend fun add() {
        await { model.state.value is AllowlistUiState.Ready }
        model.addPickedContact(selected)
        await { !model.actions.value.busy && model.actions.value.error == null && rules().size == 1 }
    }

    private fun rules() = (model.state.value as? AllowlistUiState.Ready)?.rules.orEmpty()

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }
    }

    private class SelectedContacts : ContentProvider() {
        var name = "Synthetic Contact"
        var rows = 1
        var denied = false
        var queries = 0
        var queried: Uri? = null
        var projection: List<String>? = null
        var cursor: MatrixCursor? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            queries++
            if (denied) throw SecurityException("Synthetic permission denial")
            queried = uri
            this.projection = projection?.toList()
            return MatrixCursor(arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)).also {
                repeat(rows) { _ -> it.addRow(arrayOf(name)) }
                cursor = it
            }
        }
        override fun getType(uri: Uri) = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Unexpected contact write")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected contact write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Unexpected contact write")
    }
}
