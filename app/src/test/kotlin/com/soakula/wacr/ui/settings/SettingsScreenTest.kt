package com.soakula.wacr.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.theme.WacrTheme
import com.soakula.wacr.ui.theme.isDark
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
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsNavigationThemeAndWallpaperControlsAreUsable() {
        val appearance = mutableStateOf(Appearance(dynamicColor = false))
        val actions = mutableStateOf(SettingsActionsState())
        var colors: ColorScheme? = null
        compose.setContent {
            WacrTheme(appearance.value, systemDark = false) {
                val current = MaterialTheme.colorScheme
                val shape = MaterialTheme.shapes.medium
                SideEffect {
                    colors = current
                    assertEquals(RoundedCornerShape(16.dp), shape)
                }
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, settingsContent = {
                        SettingsScreen(SettingsUiState.Ready(appearance.value), actions.value,
                            { appearance.value = appearance.value.copy(theme = it) },
                            { appearance.value = appearance.value.copy(dynamicColor = it) }, {}, {})
                    }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.settings_theme_dark)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemeChoice.DARK, appearance.value.theme)
            assertTrue(checkNotNull(colors).background.luminance() < 0.1f)
            assertContrast(checkNotNull(colors))
        }
        compose.onNodeWithText(text(R.string.settings_theme_light)).performClick()
        compose.runOnIdle {
            assertTrue(checkNotNull(colors).background.luminance() > 0.8f)
            assertContrast(checkNotNull(colors))
        }
        compose.onNodeWithText(text(R.string.settings_wallpaper_colors)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(appearance.value.dynamicColor) }
        compose.runOnIdle { actions.value = SettingsActionsState(busy = true) }
        compose.onNodeWithText(text(R.string.settings_theme_dark)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.nav_home)).performClick()
        compose.onNodeWithText(text(R.string.home_record)).assertIsDisplayed()
    }

    @Test fun preferenceReadFailureIsVisibleAcrossTheAppAndHasWorkingRetry() {
        var retried = false
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, appearanceError = true, onRetryAppearance = { retried = true }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.settings_read_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_retry)).performClick()
        assertTrue(retried)
    }

    @Test fun systemModeTracksSystemAndExplicitModesOverrideIt() {
        assertTrue(ThemeChoice.SYSTEM.isDark(true))
        assertFalse(ThemeChoice.SYSTEM.isDark(false))
        assertTrue(ThemeChoice.DARK.isDark(false))
        assertFalse(ThemeChoice.LIGHT.isDark(true))
    }

    private fun assertContrast(colors: ColorScheme) {
        for ((foreground, background) in listOf(colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer, colors.onSecondary to colors.secondary,
                colors.onSecondaryContainer to colors.secondaryContainer, colors.onSurface to colors.surface)) {
            assertTrue("Fallback text contrast must be at least 4.5:1", contrast(foreground, background) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
