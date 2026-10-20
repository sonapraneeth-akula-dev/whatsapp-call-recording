package com.soakula.wacr.ui.home

import android.app.Application
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.activity.ComponentActivity
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.recording.RecorderFailure
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.library.LibraryUiState
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
class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recordAndStopFollowActualRecorderStates() {
        val id = UUID.randomUUID()
        val state = mutableStateOf<RecorderState>(RecorderState.Idle)
        var starts = 0
        var stops = 0
        compose.setContent {
            MaterialTheme { HomeScreen(HomeUiState(loaded = true), state.value, { starts++ }, { stops++ }, {}) }
        }
        compose.onNodeWithText(text(R.string.home_record)).performClick()
        assertEquals(1, starts)
        compose.runOnIdle { state.value = RecorderState.Preparing(id) }
        compose.onNodeWithText(text(R.string.recording_stop)).performClick()
        assertEquals(1, stops)
        compose.runOnIdle { state.value = RecorderState.Recording(id, System.currentTimeMillis(), true) }
        compose.onNodeWithText(text(R.string.recording_storage_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recording_stop)).performClick()
        assertEquals(2, stops)
        compose.runOnIdle { state.value = RecorderState.Saving(id) }
        compose.onNodeWithText(text(R.string.recording_stop)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_record)).assertDoesNotExist()
        compose.runOnIdle { state.value = RecorderState.Failed(id, RecorderFailure.NO_MIC_PERMISSION) }
        compose.onNodeWithText(text(R.string.recording_error_mic)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_record)).assertIsDisplayed()
    }

    @Test fun noRecordActionUntilStorageAndHealthAreLoadedSuccessfully() {
        val state = mutableStateOf(HomeUiState())
        var retried = 0
        compose.setContent {
            MaterialTheme {
                AppScaffold(state.value, RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, onRetryHome = { retried++ }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.home_record)).assertIsNotEnabled()
        compose.runOnIdle { state.value = HomeUiState(loaded = true, error = R.string.home_health_error) }
        compose.onNodeWithText(text(R.string.home_record)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.home_health_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_retry)).performScrollTo().performClick()
        assertEquals(1, retried)
        compose.onNodeWithText(text(R.string.home_record)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun consentDisclosureAndRealSettingsRemedyRemainReachable() {
        val issue = HealthIssue(HealthProblem.MICROPHONE_PERMISSION, Intent("test.settings"))
        var opened: HealthIssue? = null
        compose.setContent {
            MaterialTheme {
                HomeScreen(HomeUiState(loaded = true, issues = listOf(issue)), RecorderState.Idle,
                    {}, {}, { opened = it })
            }
        }
        compose.onNodeWithText(text(R.string.home_capture_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_permission_reason)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_review_settings)).performScrollTo().performClick()
        assertEquals(issue, opened)
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
