package com.soakula.wacr.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.soakula.wacr.R
import com.soakula.wacr.recording.WaveformPeaks
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaveformTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hourLongEnvelopeRendersThroughNativeCanvasWithoutLoadingOrDecodingAudio() {
        val peaks = WaveformPeaks(51_200, 57_600_000, ByteArray(1125) { (it % 256).toByte() })
        compose.setContent { MaterialTheme { Waveform(peaks, .5f) } }
        compose.onNodeWithContentDescription(text(R.string.player_waveform)).assertIsDisplayed()
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888)
        try { compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) } }
        finally { bitmap.recycle() }
    }

    @Test fun waveformLoadFailureIsVisibleReportedAndRetryCanRecoverWithoutReopeningAudio() {
        var requests = 0
        var failures = 0
        compose.setContent {
            MaterialTheme {
                WaveformPanel(UUID(0, 1), 0f, {
                    if (++requests == 1) throw IOException("Synthetic storage failure")
                    WaveformPeaks(1600, 1, byteArrayOf(100))
                }, { failures++ })
            }
        }
        compose.onNodeWithText(text(R.string.player_waveform_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_retry)).performClick()
        compose.onNodeWithContentDescription(text(R.string.player_waveform)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests); assertEquals(1, failures) }
    }

    @Test fun missingLegacyEnvelopeShowsExplicitUnavailableStateInsteadOfAFabricatedWaveform() {
        compose.setContent { MaterialTheme { WaveformPanel(UUID(0, 1), 0f, { null }, { throw AssertionError(it) }) } }
        compose.onNodeWithText(text(R.string.player_waveform_missing)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_waveform)).assertDoesNotExist()
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
