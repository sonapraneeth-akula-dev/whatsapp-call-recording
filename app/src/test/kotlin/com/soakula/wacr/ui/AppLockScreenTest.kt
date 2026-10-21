package com.soakula.wacr.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.security.AppLockState
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.theme.WacrTheme
import org.junit.Assert.*
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
class AppLockScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun relockHidesProtectedDestinationAndNavigationButSetupStaysOpen() {
        val locked = mutableStateOf(false)
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, locked = locked.value,
                    lockContent = { setup ->
                        AppLockScreen(AppLockState.Locked(1), false, null, {}, {}, {}, setup)
                    }, settingsContent = { Text("Protected settings") },
                    setupContent = { close ->
                        Column { Text("Open setup"); Button(onClick = close) { Text("Close setup") } }
                    }) { _, _ -> Text("Protected player") }
            }
        }
        compose.onNodeWithText(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText("Protected settings").assertIsDisplayed()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText("Protected settings").assertDoesNotExist()
        listOf(R.string.nav_home, R.string.nav_library, R.string.nav_allowlist, R.string.nav_settings).forEach {
            compose.onNodeWithText(text(it)).assertDoesNotExist()
        }
        compose.onNodeWithText(text(R.string.setup_title)).performClick()
        compose.onNodeWithText("Open setup").assertIsDisplayed()
        compose.onNodeWithText("Close setup").performClick()
        compose.onNodeWithText(text(R.string.app_lock_unlock)).assertIsDisplayed()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithText("Protected settings").assertIsDisplayed()
    }

    @Test fun lockScreenHasUsableNativeControlsInBothThemesAndAtTwoHundredPercentText() {
        val theme = mutableStateOf(ThemeChoice.LIGHT)
        val density = mutableStateOf(Density(1f, 1f))
        var unlocked = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                WacrTheme(Appearance(theme.value, false)) {
                    AppLockScreen(AppLockState.Locked(1), false, R.string.app_lock_auth_error,
                        { unlocked++ }, {}, {}, {})
                }
            }
        }
        for (dark in listOf(false, true)) for (font in listOf(1f, 2f)) {
            compose.runOnIdle {
                theme.value = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT
                density.value = Density(1f, font)
            }
            compose.onNodeWithText(text(R.string.app_lock_unlock)).performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(text(R.string.app_lock_security_settings)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.setup_title)).performScrollTo().assertIsDisplayed()
        }
        assertEquals(4, unlocked)
    }

    @Test fun incomingPlaybackRequestCannotComposeThePlayerUntilUnlocked() {
        val locked = mutableStateOf(true)
        val id = UUID.randomUUID()
        val entry = RecordingWithSession(RecordingEntity(id, 1, 1, "MIC"), null)
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(listOf(entry)),
                    {}, {}, {}, {}, locked = locked.value, playbackRequest = id to 1,
                    lockContent = { AppLockScreen(AppLockState.Locked(1), false, null, {}, {}, {}, it) }) { _, _ ->
                    Text("Protected player")
                }
            }
        }
        compose.onNodeWithText("Protected player").assertDoesNotExist()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithText("Protected player").assertIsDisplayed()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText("Protected player").assertDoesNotExist()
    }

    @Test fun preferenceFailureHasRetryAndNeverOffersAnUnlockBypass() {
        var retry = 0
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                AppLockScreen(AppLockState.Failed, false, null, {}, { retry++ }, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.app_lock_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_lock_unlock)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_retry)).performClick()
        assertEquals(1, retry)
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
