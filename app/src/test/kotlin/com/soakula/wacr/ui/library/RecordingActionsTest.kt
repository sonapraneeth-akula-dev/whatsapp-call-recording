package com.soakula.wacr.ui.library

import android.app.Application
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.soakula.wacr.R
import com.soakula.wacr.data.PendingRecordingDeletion
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.home.HomeUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun renameValidatesInputAndClosesOnlyAfterSuccessfulMutation() {
        val state = mutableStateOf(RecordingActionsState())
        var renamed: String? = null
        val entry = entry()
        compose.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { RecordingActions(entry, state.value, { renamed = it }, {}) } }
        }
        compose.onNodeWithText(text(R.string.library_rename)).performClick()
        compose.runOnIdle {
            val input = titleInput()
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
            assertTrue(input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        replaceTitle("   ")
        compose.onNodeWithText(text(R.string.library_save)).assertIsNotEnabled()
        replaceTitle("x".repeat(201))
        compose.onNodeWithText(text(R.string.library_save)).assertIsNotEnabled()
        replaceTitle("Synthetic renamed")
        compose.onNodeWithText(text(R.string.library_save)).performClick()
        assertEquals("Synthetic renamed", renamed)
        compose.onNodeWithText(text(R.string.library_save)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText(text(R.string.library_save)).assertIsNotEnabled()
        compose.runOnIdle { assertFalse(titleInput().isEnabled) }
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = R.string.library_rename_error) }
        compose.onNodeWithText(text(R.string.library_rename_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_save)).assertIsDisplayed()
        renamed = null
        replaceTitle("Synthetic IME rename")
        compose.runOnIdle { titleInput().onEditorAction(EditorInfo.IME_ACTION_DONE) }
        assertEquals("Synthetic IME rename", renamed)
        compose.runOnIdle { state.value = state.value.copy(renameVersion = 1) }
        compose.onNodeWithText(text(R.string.library_save)).assertDoesNotExist()
    }

    @Test fun deletionRequiresConfirmationAndActiveRecordingCannotBeDeleted() {
        val entry = mutableStateOf(entry())
        var deleted = false
        compose.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { RecordingActions(entry.value, RecordingActionsState(), {}, { deleted = true }) } }
        }
        compose.onNodeWithText(text(R.string.library_delete)).performClick()
        assertFalse(deleted)
        compose.onNodeWithText(text(R.string.library_delete_confirm)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.library_delete))[1].performClick()
        assertTrue(deleted)
        compose.runOnIdle { entry.value = entry.value.copy(recording = entry.value.recording.copy(status = RecordingStatus.RECORDING)) }
        compose.onNodeWithText(text(R.string.library_delete)).assertIsNotEnabled()
    }

    @Test fun snackbarUndoInvokesStoredTicketAndExpiredTicketIsNotShown() {
        val ticket = PendingRecordingDeletion(UUID.randomUUID(), UUID.randomUUID(), SystemClock.elapsedRealtime() + 5000, 1)
        val actions = mutableStateOf(RecordingActionsState(undo = ticket))
        var undone: PendingRecordingDeletion? = null
        var expired: PendingRecordingDeletion? = null
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, actions = actions.value,
                    onUndo = { undone = it; actions.value = RecordingActionsState() },
                    onDismissUndo = { expired = it; actions.value = RecordingActionsState() }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.library_undo)).performClick()
        compose.runOnIdle { assertEquals(ticket, undone) }
        val old = ticket.copy(requestId = UUID.randomUUID(), undoUntilElapsedMs = SystemClock.elapsedRealtime())
        compose.runOnIdle { actions.value = RecordingActionsState(undo = old) }
        compose.onNodeWithText(text(R.string.library_undo)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(old, expired) }
    }

    private fun entry() = RecordingWithSession(RecordingEntity(UUID.randomUUID(), 1000, 1, "MIC",
        title = "Synthetic title", status = RecordingStatus.FINALIZED), null)

    private fun text(resource: Int) = compose.activity.getString(resource)

    private fun titleInput(): EditText =
        checkNotNull(ShadowDialog.getLatestDialog()).findViewById(R.id.recording_title_input)

    private fun replaceTitle(value: String) {
        compose.runOnIdle { titleInput().setText(value) }
        compose.waitForIdle()
    }
}
