package com.soakula.wacr.ui.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.soakula.wacr.R
import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun navigationShowsRealRecentFiveThenLibraryAndReturnsHome() {
        val entries = (1..6).map {
            RecordingWithSession(RecordingEntity(UUID(0, it.toLong()), 10_000L - it, 1, "MIC", title = "Synthetic $it"), null)
        }
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(entries),
                    {}, {}, {}, {}) { _, _ -> }
            }
        }
        compose.onNodeWithText("Synthetic 5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic 6").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nav_library)).performClick()
        compose.onNodeWithText(text(R.string.library_title)).assertIsDisplayed()
        compose.onNodeWithText("Synthetic 1").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.library_remote_unknown))[0].assertExists()
        compose.onNodeWithText(text(R.string.nav_home)).performClick()
        compose.onNodeWithText(text(R.string.home_record)).assertIsDisplayed()
    }

    @Test fun failedLibraryOffersWorkingRetryAndEmptyStateIsDistinct() {
        val state = mutableStateOf<LibraryUiState>(LibraryUiState.Failed)
        var retries = 0
        compose.setContent { MaterialTheme { LibraryScreen(state.value, { retries++ }) } }
        compose.onNodeWithText(text(R.string.library_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_retry)).performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = LibraryUiState.Ready(emptyList()) }
        compose.onNodeWithText(text(R.string.library_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_error)).assertDoesNotExist()
    }

    @Test fun removingSelectedMetadataDisposesItsPlayerDestination() {
        val entry = RecordingWithSession(RecordingEntity(UUID.randomUUID(), 1000, 1, "MIC"), null)
        val state = mutableStateOf<LibraryUiState>(LibraryUiState.Ready(listOf(entry)))
        var disposed = false
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, state.value, {}, {}, {}, {}) { _, _ ->
                    DisposableEffect(Unit) { onDispose { disposed = true } }
                    Text("Synthetic player destination")
                }
            }
        }
        compose.onNodeWithText(text(R.string.library_open)).performScrollTo().performClick()
        compose.onNodeWithText("Synthetic player destination").assertIsDisplayed()
        compose.runOnIdle { state.value = LibraryUiState.Ready(emptyList()) }
        compose.onNodeWithText("Synthetic player destination").assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, disposed) }
    }

    @Test fun unknownSessionMetadataIsNotLabelledManualOrVerifiedVoice() {
        val entry = RecordingWithSession(
            RecordingEntity(UUID.randomUUID(), 1000, 1, "MIC"),
            CallSessionEntity(id = 1)
        )
        compose.setContent { MaterialTheme { RecordingRow(entry) } }
        compose.onNodeWithText(text(R.string.library_unknown_caller)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_unknown_type), substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_manual)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_voice), substring = true).assertDoesNotExist()
    }

    @Test fun libraryShowsContactHeadingCountAndExistingMetadataForEveryRecording() {
        val session = CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact",
            callType = CallType.VOICE, direction = CallDirection.INCOMING, isGroupKnown = true)
        val entries = listOf(
            RecordingWithSession(RecordingEntity(UUID(0, 1), 3000, 1, "MIC", title = "Clip latest", durationMs = 90_000), session),
            RecordingWithSession(RecordingEntity(UUID(0, 2), 1000, 1, "MIC", title = "Clip old", durationMs = 60_000), session)
        )
        compose.setContent { MaterialTheme { LibraryScreen(LibraryUiState.Ready(entries), {}) } }
        compose.onNodeWithText("Demo Contact").assertIsDisplayed()
        compose.onNodeWithText("2 recordings").assertIsDisplayed()
        compose.onNodeWithText("Clip latest").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.library_voice), substring = true).assertCountEquals(2)
        compose.onAllNodesWithText(text(R.string.library_incoming), substring = true).assertCountEquals(2)
        compose.onNodeWithText("Clip old").performScrollTo().assertIsDisplayed()
    }

    @Test fun contactInitialsKeepCombiningCharactersEmojiAndUnicodeWordSeparators() {
        assertEquals("DC", contactInitials("Demo Contact"))
        assertEquals("A\u0301P", contactInitials("A\u0301lex\u00A0Person"))
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBBE", contactInitials("\uD83D\uDC69\u200D\uD83D\uDCBB Engineer"))
        assertEquals("", contactInitials("  "))
    }

    @Test fun contactRouteSurvivesPlayerBackTabChangesAndSavedStateRestore() {
        val session = CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact", isGroupKnown = true)
        val entries = listOf(
            RecordingWithSession(RecordingEntity(UUID(0, 1), 3000, 1, "MIC", title = "Demo clip"), session),
            RecordingWithSession(RecordingEntity(UUID(0, 2), 2000, 1, "MIC", title = "Other clip"),
                session.copy(waDisplayName = "Other Person", matchKey = "other person"))
        )
        val restore = StateRestorationTester(compose)
        restore.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(entries),
                    {}, {}, {}, {}) { entry, back ->
                    Column {
                        Text("Playing ${entry.recording.title}")
                        TextButton(onClick = back) { Text("Back to calls") }
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.nav_library)).performClick()
        compose.onNodeWithText(text(R.string.library_search_name)).performTextInput("demo")
        compose.onNodeWithContentDescription(compose.activity.getString(
            R.string.library_view_contact_description, "Demo Contact"
        )).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.library_clear_filters)).assertIsDisplayed()
        compose.onNodeWithText("Other clip").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_open)).performScrollTo().performClick()
        compose.onNodeWithText("Playing Demo clip").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Playing Demo clip").assertIsDisplayed()
        compose.onNodeWithText("Back to calls").performClick()
        compose.onNodeWithText(text(R.string.library_clear_filters)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_search_name)).assertTextContains("demo")
        compose.onNodeWithText(text(R.string.nav_home)).performClick()
        compose.onNodeWithText(text(R.string.nav_library)).performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.library_clear_filters)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_search_name)).assertTextContains("demo")
        compose.onNodeWithText("Demo clip").assertIsDisplayed()
        compose.onNodeWithText("Other clip").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.library_clear_filters)).assertDoesNotExist()
        compose.onNodeWithText("Demo Contact").assertIsDisplayed()
    }

    @Test fun deletedContactResultsStayExplicitlyFilteredUntilOwnerClearsThem() {
        val route = mutableStateOf(LibraryRoute("CONTACT:missing"))
        compose.setContent {
            MaterialTheme {
                LibraryScreen(LibraryUiState.Ready(emptyList()), {}, route.value, { route.value = it })
            }
        }
        compose.onNodeWithText(text(R.string.library_contact_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_clear_filters)).performClick()
        compose.onNodeWithText(text(R.string.library_empty)).assertIsDisplayed()
    }

    @Test fun repeatedNotificationEntryOpensExactRecordingWithoutReplacingContactSearchFilters() {
        val session = CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact", isGroupKnown = true)
        val entries = listOf(
            RecordingWithSession(RecordingEntity(UUID(0, 1), 3000, 1, "MIC", title = "Demo clip"), session),
            RecordingWithSession(RecordingEntity(UUID(0, 2), 2000, 1, "MIC", title = "Other clip"),
                session.copy(waDisplayName = "Other Person", matchKey = "other person"))
        )
        val request = mutableStateOf<Pair<UUID, Int>?>(null)
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(entries),
                    {}, {}, {}, {}, playbackRequest = request.value) { entry, back ->
                    Column {
                        Text("Playing ${entry.recording.title}")
                        TextButton(onClick = back) { Text("Back to calls") }
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.nav_library)).performClick()
        compose.onNodeWithText(text(R.string.library_search_name)).performTextInput("demo")
        compose.onNodeWithContentDescription(compose.activity.getString(
            R.string.library_view_contact_description, "Demo Contact")).performScrollTo().performClick()
        for (nonce in 1..2) {
            compose.runOnIdle { request.value = entries[1].recording.id to nonce }
            compose.onNodeWithText("Playing Other clip").assertIsDisplayed()
            compose.onNodeWithText("Back to calls").performClick()
            compose.onNodeWithText(text(R.string.library_search_name)).assertTextContains("demo")
            compose.onNodeWithText("Other clip").assertDoesNotExist()
            compose.onNodeWithText("Demo clip").assertIsDisplayed()
        }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
