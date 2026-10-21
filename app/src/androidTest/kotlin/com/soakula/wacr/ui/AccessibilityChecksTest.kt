package com.soakula.wacr.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.test.filters.SdkSuppress
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.allowlist.AllowlistActionsState
import com.soakula.wacr.ui.allowlist.AllowlistScreen
import com.soakula.wacr.ui.allowlist.AllowlistUiState
import com.soakula.wacr.ui.home.HomeScreen
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryScreen
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.onboarding.SetupChecklistScreen
import com.soakula.wacr.ui.player.PlaybackUiState
import com.soakula.wacr.ui.player.PlayerScreen
import com.soakula.wacr.ui.settings.CapabilityActions
import com.soakula.wacr.ui.settings.CapabilityScreen
import com.soakula.wacr.ui.settings.CapabilityUiState
import com.soakula.wacr.ui.settings.DiagnosticExportState
import com.soakula.wacr.ui.settings.DiagnosticsScreen
import com.soakula.wacr.ui.settings.SettingsActionsState
import com.soakula.wacr.ui.settings.SettingsScreen
import com.soakula.wacr.ui.settings.SettingsUiState
import com.soakula.wacr.ui.theme.WacrTheme
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@SdkSuppress(minSdkVersion = 34)
class AccessibilityChecksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun productionScreensLightDarkAndLargeText() {
        val screen = mutableStateOf(0)
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        val ready = HomeUiState(loaded = true, availableBytes = 2_000_000_000)
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val clip = RecordingWithSession(RecordingEntity(id, 1, 1, "MIC", title = "Synthetic sample",
            status = RecordingStatus.FINALIZED, durationMs = 30_000, audioPath = "$id/audio.aac.enc"), null)
        val screens: List<@Composable () -> Unit> = listOf(
            { AppScaffold(ready, RecorderState.Idle, LibraryUiState.Ready(emptyList()), {}, {}, {}, {}) { _, _ -> } },
            { HomeScreen(ready, RecorderState.Idle, {}, {}, {}, LibraryUiState.Ready(emptyList())) },
            { LibraryScreen(LibraryUiState.Ready(emptyList()), {}) },
            { AllowlistScreen(AllowlistUiState.Ready(emptyList()), AllowlistActionsState(), {}, {}, {}, {}, {}, {}) },
            { SetupChecklistScreen(ready, false, false, {}, {}, {}, {}, {}, {}) },
            { SettingsScreen(SettingsUiState.Ready(Appearance()), SettingsActionsState(), {}, {}, {}, {}) },
            { DiagnosticsScreen(DiagnosticExportState.IDLE, {}, {}) },
            { PlayerScreen(clip, PlaybackUiState(ready = true, durationMs = 30_000), false, {}, {}, {}) },
            { CapabilityScreen(CapabilityUiState.Ready(emptyList(), null, emptyList()), CapabilityActions(),
                null, false, emptySet(), {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}) }
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                WacrTheme(Appearance(if (dark.value) ThemeChoice.DARK else ThemeChoice.LIGHT, dynamicColor = false)) {
                    screens[screen.value]()
                }
            }
        }
        compose.enableAccessibilityChecks()
        for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
            for (index in screens.indices) {
                compose.runOnIdle { dark.value = night; scale.value = font; screen.value = index }
                compose.onRoot().tryPerformAccessibilityChecks()
            }
        }
    }
}
