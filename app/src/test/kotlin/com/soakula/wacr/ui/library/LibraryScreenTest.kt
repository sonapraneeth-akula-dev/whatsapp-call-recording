package com.soakula.wacr.ui.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.soakula.wacr.R
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
                    {}, {}, {}, {})
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
        compose.setContent { MaterialTheme { LibraryScreen(state.value) { retries++ } } }
        compose.onNodeWithText(text(R.string.library_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_retry)).performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = LibraryUiState.Ready(emptyList()) }
        compose.onNodeWithText(text(R.string.library_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_error)).assertDoesNotExist()
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
