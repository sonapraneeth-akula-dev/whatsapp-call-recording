package com.soakula.wacr.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.R
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.playback.DecryptingDataSource
import com.soakula.wacr.playback.createRecordingPlayer
import com.soakula.wacr.util.AppLog
import com.soakula.wacr.recording.WaveformPeaks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.UUID

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeControlsSeekAndContinueOnStopThenReleaseUiOnDisposal() {
        StreamingAeadConfig.register()
        val encryption = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val store = CryptoStore(RecordingFiles(temporary.newFolder("audio")), encryption)
        val log = AppLog(temporary.newFolder("logs"), true, {}, { throw it })
        val engine = createRecordingPlayer(compose.activity, store, log)
        // Exercise real native controls without claiming a host AAC decoder or audio surface.
        val ready = object : ForwardingPlayer(engine) {
            override fun prepare() = Unit
            override fun getPlaybackState() = Player.STATE_READY
            override fun getDuration() = 64_000L
        }
        val entry = entry()
        val shown = mutableStateOf(true)
        val blocked = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                if (shown.value) PlayerRoute(entry, blocked.value, {}, { ready }, { throw AssertionError(it) },
                    loadWaveform = { WaveformPeaks(1600, 1_024_000, ByteArray(640) { 100 }) })
            }
        }
        compose.runOnIdle {
            assertFalse(engine.playWhenReady)
            assertEquals(DecryptingDataSource.uri(entry.recording.id), engine.currentMediaItem?.localConfiguration?.uri)
        }
        compose.onNodeWithContentDescription(text(R.string.player_waveform)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_play)).performClick()
        compose.runOnIdle { assertTrue(engine.playWhenReady) }
        compose.onNodeWithText(text(R.string.player_pause)).performClick()
        compose.runOnIdle { assertFalse(engine.playWhenReady) }
        compose.onNodeWithContentDescription(text(R.string.player_seek))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(32_000f)) }
        compose.runOnIdle { assertEquals(32_000L, engine.currentPosition) }
        compose.onNodeWithText(text(R.string.player_skip_back)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(22_000L, engine.currentPosition) }
        compose.onNodeWithText(text(R.string.player_skip_forward)).performClick()
        compose.runOnIdle { assertEquals(32_000L, engine.currentPosition) }
        compose.onNodeWithText(compose.activity.getString(R.string.player_speed, "1")).performClick()
        compose.onNodeWithText("2x").performClick()
        compose.runOnIdle {
            assertEquals(2f, engine.playbackParameters.speed, 0f)
            assertEquals(1f, engine.playbackParameters.pitch, 0f)
            assertEquals(10_000L, engine.seekBackIncrement)
            assertEquals(10_000L, engine.seekForwardIncrement)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(compose.activity.getString(R.string.player_speed, "2")).performClick()
        compose.onNodeWithText("0.75x").performClick()
        compose.runOnIdle {
            assertEquals(.75f, engine.playbackParameters.speed, 0f)
            assertEquals(1f, engine.playbackParameters.pitch, 0f)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(compose.activity.getString(R.string.player_speed, "0.75")).performClick()
        compose.onNodeWithText("2x").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(text(R.string.player_play)).performClick()
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText(text(R.string.player_pause)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.player_play)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.player_skip_back)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.player_speed, "2")).assertIsNotEnabled()
        compose.runOnIdle { assertFalse(engine.playWhenReady); blocked.value = false }
        compose.onNodeWithText(text(R.string.player_play)).performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(engine.playWhenReady)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(engine.playWhenReady); shown.value = false }
        compose.runOnIdle { assertTrue(engine.isReleased) }
    }

    @Test fun keyOrStorageFailureIsVisibleAndNeverBecomesPlayable() {
        var reported: Throwable? = null
        val entry = entry()
        compose.setContent {
            MaterialTheme {
                PlayerRoute(entry, false, {}, { throw IOException("Synthetic private failure") }, { reported = it })
            }
        }
        compose.onNodeWithText(text(R.string.player_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_play)).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(reported is IOException) }
    }

    @Test fun connectionFailureCanRetryWithoutAutoplayOrReusingTheFailedPlayer() {
        var opens = 0
        val entry = entry()
        compose.setContent {
            MaterialTheme {
                PlayerRoute(entry, false, {}, {
                    opens++
                    throw IOException("Synthetic connection failure")
                }, {})
            }
        }
        compose.onNodeWithText(text(R.string.player_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.playback_retry)).performClick()
        compose.runOnIdle { assertEquals(2, opens) }
        compose.onNodeWithText(text(R.string.player_play)).assertIsNotEnabled()
    }

    @Test fun activeOrFailedMetadataNeverOpensAPlayer() {
        var opened = false
        val entry = entry().let { it.copy(recording = it.recording.copy(status = RecordingStatus.RECORDING)) }
        compose.setContent {
            MaterialTheme {
                PlayerRoute(entry,
                    false, {}, { opened = true; error("Should not open") }, { throw AssertionError(it) })
            }
        }
        compose.onNodeWithText(text(R.string.player_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_play)).assertIsNotEnabled()
        compose.runOnIdle { assertFalse(opened) }
    }

    private fun entry(): RecordingWithSession {
        val id = UUID.randomUUID()
        return RecordingWithSession(RecordingEntity(id, 1000, 1, "MIC", title = "Synthetic recording",
            status = RecordingStatus.FINALIZED, durationMs = 64_000, audioPath = "$id/audio.aac.enc"), null)
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
