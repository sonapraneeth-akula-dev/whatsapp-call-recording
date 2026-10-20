package com.soakula.wacr.ui.onboarding

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.soakula.wacr.R
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.settings.SettingsActionsState
import com.soakula.wacr.ui.settings.SettingsScreen
import com.soakula.wacr.ui.settings.SettingsUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupChecklistScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstRunCanContinueAndSettingsReopensChecklistWithoutLosingItsDestination() {
        val firstRun = mutableStateOf(true)
        var refreshed = 0
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, forceSetup = firstRun.value, settingsContent = { openSetup ->
                        SettingsScreen(SettingsUiState.Ready(Appearance()), SettingsActionsState(), {}, {}, {}, {}, openSetup)
                    }, setupContent = { close ->
                        SetupChecklistScreen(HomeUiState(loaded = true), false, false, { refreshed++ }, {}, {}, {}, {}) {
                            if (firstRun.value) firstRun.value = false else close()
                        }
                    }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.setup_intro)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_home)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_record)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.setup_continue)).performClick()
        compose.onNodeWithText(text(R.string.home_record)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.setup_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_intro)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_continue)).performClick()
        compose.onNodeWithText(text(R.string.settings_appearance)).performScrollTo().assertIsDisplayed()
        assertEquals(2, refreshed)
    }

    @Test fun allStepsGuidanceAndNativeFixCallbacksAreAvailable() {
        val microphone = HealthIssue(HealthProblem.MICROPHONE_PERMISSION, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        val state = HomeUiState(loaded = true, issues = listOf(microphone, HealthIssue(HealthProblem.DETECTION_UNAVAILABLE, null)))
        var granted: HealthIssue? = null
        var fixed: HealthIssue? = null
        var appSettings = false
        var batterySettings = false
        compose.setContent {
            MaterialTheme {
                SetupChecklistScreen(state, false, false, {}, { fixed = it }, { granted = it },
                    { appSettings = true }, { batterySettings = true }, {})
            }
        }
        compose.onNodeWithText(text(R.string.setup_grant)).performScrollTo().performClick()
        assertEquals(microphone, granted)
        compose.onNodeWithText(text(R.string.home_review_settings)).performScrollTo().performClick()
        assertEquals(microphone, fixed)
        HealthProblem.entries.forEach {
            scrollTo(setupTitle(it)).assertIsDisplayed()
        }
        scrollTo(R.string.setup_app_info).performClick()
        assertTrue(appSettings)
        scrollTo(R.string.setup_samsung_detail).assertIsDisplayed()
        scrollTo(R.string.setup_battery_list).performClick()
        assertTrue(batterySettings)
    }

    @Test fun missingAdaptersAndReadFailuresNeverProduceFakeVerifiedSteps() {
        val missing = HomeUiState(loaded = true, issues = listOf(HealthIssue(HealthProblem.DETECTION_UNAVAILABLE, null)))
        assertEquals(R.string.setup_action_required, setupStatus(missing, HealthProblem.DETECTION_UNAVAILABLE))
        assertEquals(R.string.setup_unverified, setupStatus(missing, HealthProblem.ACCESSIBILITY_DISABLED))
        assertEquals(R.string.setup_unverified, setupStatus(missing, HealthProblem.NOTIFICATION_ACCESS_DISABLED))
        assertEquals(R.string.setup_checked, setupStatus(missing, HealthProblem.MICROPHONE_PERMISSION))
        for (problem in HealthProblem.entries) {
            assertEquals(R.string.setup_unverified, setupStatus(HomeUiState(), problem))
            assertEquals(R.string.setup_unverified, setupStatus(HomeUiState(loaded = true, error = R.string.home_health_error), problem))
            assertEquals(R.string.setup_checked, setupStatus(HomeUiState(loaded = true), problem))
            assertEquals(R.string.setup_action_required,
                setupStatus(HomeUiState(loaded = true, issues = listOf(HealthIssue(problem, null))), problem))
        }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)

    private fun scrollTo(resource: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(resource)))
        return compose.onNodeWithText(text(resource))
    }
}
