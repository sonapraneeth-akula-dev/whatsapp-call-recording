package com.soakula.wacr.recording

import android.app.Application
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.util.AppLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], application = Application::class, manifest = Config.NONE,
    shadows = [CaptureEngineTest.RecordingMonitorShadow::class]
)
class CaptureEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var log: AppLog

    @Before fun createLog() {
        log = AppLog(temporary.newFolder(), false, {}, { throw it })
        RecordingMonitorShadow.silenceNextConfig = false
    }

    @Test fun provisionalDefaultAndRouteOverridesKeepConfiguredOrder() {
        val profile = CaptureProfile(
            routeSources = mapOf(AudioRoute.WIRED to listOf(CaptureSource.MIC, CaptureSource.VOICE_RECOGNITION))
        )
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profile.sourcesFor(null))
        assertEquals(listOf(CaptureSource.MIC, CaptureSource.VOICE_RECOGNITION), profile.sourcesFor(AudioRoute.WIRED))
        assertThrows(IllegalArgumentException::class.java) { CaptureProfile(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureProfile(listOf(CaptureSource.MIC, CaptureSource.MIC))
        }
    }

    @Test fun unavailablePreferredSourceFallsBackToMonoPcmAndReleasesOnClose() {
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    assertFalse(blocking)
                    data.fill(8192, offset, offset + size)
                    return size
                }
            }
        }
        val attempted = mutableListOf<CaptureSource>()
        val opened = mutableListOf<AudioRecord>()
        val engine = CaptureEngine(CaptureProfile(listOf(CaptureSource.VOICE_RECOGNITION, CaptureSource.MIC)), null, log, {
            attempted += it
            if (it == CaptureSource.VOICE_RECOGNITION) throw IllegalArgumentException("synthetic unsupported source")
            CaptureEngine.buildInput(it).also { input -> opened += input }
        })
        engine.use {
            val pcm = ShortArray(320)
            assertEquals(320, it.read(pcm))
            assertTrue(pcm.all { sample -> sample == 8192.toShort() })
            assertEquals(-12.04, checkNotNull(it.rmsDbfs), 0.01)
            assertEquals(1, it.format.channels)
            assertEquals(CaptureSource.MIC, it.source)
            assertEquals(16_000, opened.single().sampleRate)
            assertEquals(1, opened.single().channelCount)
        }
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION, CaptureSource.MIC), attempted)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, opened.single().state)
        engine.close()
        assertThrows(IllegalStateException::class.java) { engine.read(ShortArray(320)) }
    }

    @Test fun digitalZeroSpeechPausesNeverTriggerSourceFallback() {
        var now = 0L
        CaptureEngine(CaptureProfile(), null, log, elapsedMs = { now }).use { engine ->
            assertEquals(320, engine.read(ShortArray(320)))
            assertEquals(-120.0, checkNotNull(engine.rmsDbfs), 0.0)
            now = 10_000
            assertEquals(320, engine.read(ShortArray(320)))
            assertEquals(CaptureSource.VOICE_RECOGNITION, engine.source)
        }
    }

    @Test fun deadInputFallsBackButPermissionFailuresDoNotTryOtherSources() {
        ShadowAudioRecord.setSourceProvider { record ->
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int =
                    if (record.audioSource == CaptureSource.VOICE_RECOGNITION.androidId) AudioRecord.ERROR_DEAD_OBJECT else size
            }
        }
        val profile = CaptureProfile(listOf(CaptureSource.VOICE_RECOGNITION, CaptureSource.MIC))
        CaptureEngine(profile, null, log).use { engine ->
            assertEquals(320, engine.read(ShortArray(320)))
            assertEquals(CaptureSource.MIC, engine.source)
        }
        var attempts = 0
        assertThrows(SecurityException::class.java) {
            CaptureEngine(profile, null, log, { attempts++; throw SecurityException("synthetic denied permission") })
        }
        assertEquals(1, attempts)
    }

    @Test fun noProgressIsBoundedAndFailedEnginesCannotKeepReading() {
        var now = 0L
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int = 0
            }
        }
        CaptureEngine(CaptureProfile(), null, log, elapsedMs = { now }).use { engine ->
            assertEquals(0, engine.read(ShortArray(320)))
            now = CaptureEngine.NO_DATA_TIMEOUT_MS
            assertThrows(IOException::class.java) { engine.read(ShortArray(320)) }
            assertThrows(IllegalStateException::class.java) { engine.read(ShortArray(320)) }
        }
    }

    @Test fun frameworkSilencingFallsBackAndReleasesOnlyOurInput() {
        val opened = mutableListOf<AudioRecord>()
        RecordingMonitorShadow.silenceNextConfig = true
        CaptureEngine(CaptureProfile(listOf(CaptureSource.VOICE_RECOGNITION, CaptureSource.MIC)), null, log, {
            CaptureEngine.buildInput(it).also { record ->
                opened += record
            }
        }).use { engine ->
            assertEquals(320, engine.read(ShortArray(320)))
            assertEquals(CaptureSource.MIC, engine.source)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, opened.first().state)
        }
        assertTrue(opened.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Implements(className = "android.media.AudioRecordingMonitorImpl", isInAndroidSdk = false)
    class RecordingMonitorShadow {

        // Robolectric's AudioRecord monitor does not have a real audio-service configuration list.
        @Implementation protected fun getActiveRecordingConfiguration(): AudioRecordingConfiguration {
            val silenced = silenceNextConfig
            silenceNextConfig = false
            return Shadow.newInstanceOf(AudioRecordingConfiguration::class.java).also {
                ReflectionHelpers.setField(it, "mClientSilenced", silenced)
            }
        }

        companion object {
            var silenceNextConfig = false
        }
    }
}
