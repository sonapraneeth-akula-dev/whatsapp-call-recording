package com.soakula.wacr.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.soakula.wacr.R
import com.soakula.wacr.capability.CapabilityDevice
import com.soakula.wacr.capability.CapabilityMeter
import com.soakula.wacr.data.*
import com.soakula.wacr.recording.CapabilityRequest
import com.soakula.wacr.recording.CaptureSource
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
class CapabilityScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = CapabilityDevice("Synthetic phone", "Synthetic OS", "com.whatsapp", "synthetic", 1)
    private val clip = UUID.randomUUID()
    private val row = CapabilityResult(
        UUID.randomUUID(), device, AudioRoute.EARPIECE, CaptureSource.MIC, CapabilityStatus.COMPLETE, 1000, clip,
        sampleFrames = CapabilityMeter.TOTAL_FRAMES, ownRmsDb = -20.0, remoteRmsDb = -25.0, zeroPct = 0.0, id = 1
    )

    private fun text(id: Int) = compose.activity.getString(id)
    private fun scroll(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    @Test fun startsOnlyTheSelectedPackageSourceAndKnownActiveRoute() {
        val state = mutableStateOf<CapabilityUiState>(CapabilityUiState.Ready(listOf(device), AudioRoute.EARPIECE, emptyList()))
        val starts = mutableListOf<CapabilityRequest>()
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                CapabilityScreen(state.value, CapabilityActions(), null, false, emptySet(),
                    starts::add, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        scroll(text(R.string.capability_start)).performClick()
        compose.runOnIdle {
            assertEquals(CapabilityRequest(AudioRoute.EARPIECE, CaptureSource.VOICE_RECOGNITION, "com.whatsapp"), starts.single())
            state.value = CapabilityUiState.Ready(listOf(device), null, emptyList())
        }
        scroll(text(R.string.capability_start)).assertIsNotEnabled()
    }

    @Test fun highEnergyNeverChecksEitherVerdictAndBothExplicitChoicesAreRequiredAtTwoHundredPercentFont() {
        val reviewed = mutableListOf<Pair<Boolean, Boolean>>()
        var preview: UUID? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                WacrTheme(Appearance(dynamicColor = false)) {
                    CapabilityScreen(CapabilityUiState.Ready(listOf(device), AudioRoute.EARPIECE, listOf(row)),
                        CapabilityActions(), null, false, setOf(clip), {}, {}, { _, own, other -> reviewed += own to other },
                        { preview = it }, {}, {}, {}, {})
                }
            }
        }
        scroll(text(R.string.capability_save_verdict)).assertIsNotEnabled()
        val you = compose.activity.getString(R.string.capability_choice, text(R.string.capability_you), text(R.string.capability_heard))
        val other = compose.activity.getString(R.string.capability_choice, text(R.string.capability_other), text(R.string.capability_not_heard))
        scroll(you).performClick()
        scroll(text(R.string.capability_save_verdict)).assertIsNotEnabled()
        scroll(other).performClick()
        scroll(text(R.string.capability_save_verdict)).performClick()
        scroll(text(R.string.capability_listen)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(true to false), reviewed)
            assertEquals(clip, preview)
        }
    }

    @Test fun loadingFailureEmptyAndBusyStatesStayExplicitAndRetryable() {
        val state = mutableStateOf<CapabilityUiState>(CapabilityUiState.Loading)
        val busy = mutableStateOf(false)
        var retries = 0
        var backs = 0
        compose.setContent {
            WacrTheme(Appearance(dynamicColor = false)) {
                CapabilityScreen(state.value, CapabilityActions(), null, busy.value, emptySet(), {}, {},
                    { _, _, _ -> }, {}, { retries++ }, {}, {}, { backs++ })
            }
        }
        compose.runOnIdle { state.value = CapabilityUiState.Failed }
        scroll(text(R.string.library_retry)).performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            state.value = CapabilityUiState.Ready(listOf(device), AudioRoute.EARPIECE, emptyList())
        }
        scroll(text(R.string.capability_empty)).assertIsDisplayed()
        compose.runOnIdle { busy.value = true }
        scroll(text(R.string.capability_start)).assertIsNotEnabled()
        scroll(text(R.string.capability_busy)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.capability_back)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
