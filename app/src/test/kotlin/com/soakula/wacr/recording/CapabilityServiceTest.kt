package com.soakula.wacr.recording

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import androidx.room.Room
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.WacrApp
import com.soakula.wacr.capability.*
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.*
import com.soakula.wacr.detection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class, shadows = [CaptureEngineTest.RecordingMonitorShadow::class])
@LazyApplication(LazyApplication.LazyLoad.ON)
class CapabilityServiceTest {
    private val app: WacrApp get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private lateinit var database: AppDatabase
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var activeId: UUID? = null
    private var reads = 0
    private val inputs = mutableSetOf<AudioRecord>()

    @Before fun prepareIsolatedPipeline() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.VIBRATE)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
        ReflectionHelpers.setField(app, "recovered", RecoveryReport(0, 0, 0))
        ShadowStatFs.registerStats(app.filesDir.path, 1_000_000, 1_000_000, 1_000_000)
        StreamingAeadConfig.register()
        val crypto = CryptoStore(app.recordingFiles, KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java))
        ReflectionHelpers.setField(app, "crypto\$delegate", lazyOf(crypto))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.whatsapp"
            versionName = "synthetic"
            setLongVersionCode(1)
            applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
        })
        val audio = app.getSystemService(AudioManager::class.java)
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        shadowOf(audio).addAvailableCommunicationDevice(device, false)
        assertTrue(audio.setCommunicationDevice(device))
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        ShadowLooper.idleMainLooper()
        CaptureEngineTest.RecordingMonitorShadow.silenceNextConfig = false
        ShadowAudioRecord.setSourceProvider { record ->
            inputs += record
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    reads++
                    data.fill(1000, offset, offset + size)
                    if (reads >= 4) activeId?.let { app.recorder.stop(it) }
                    return size
                }
            }
        }
    }

    @After fun releaseStores() {
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun deliver(id: UUID) {
        activeId = id
        val service = Robolectric.buildService(RecorderService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START).putExtra(RecorderService.EXTRA_ID, id.toString()), 0, 1)
            assertNotNull(shadowOf(service.get()).lastForegroundNotification)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                val state = app.recorder.state.value
                if (state !is RecorderState.Preparing && state !is RecorderState.Recording && state !is RecorderState.Saving) return
                Thread.sleep(5)
            }
            fail("Foreground recorder did not reach a terminal state")
        } finally {
            service.destroy()
            ShadowLooper.idleMainLooper()
        }
    }

    @Test fun guidedRequestRunsThroughTheActualForegroundServiceAndKeepsCancellationUnreviewed() = runBlocking {
        val id = checkNotNull(app.recorder.startCapability(CapabilityRequest(
            AudioRoute.EARPIECE, CaptureSource.MIC, "com.whatsapp")))
        deliver(id)
        assertEquals(RecorderState.Idle, app.recorder.state.value)
        val run = checkNotNull(app.recorder.capability.value)
        assertEquals(id, run.id)
        assertTrue(run.complete)
        assertNull(run.failure)
        val row = database.capabilities().run(id).single()
        assertEquals(CapabilityStatus.CANCELLED, row.status)
        assertEquals(CaptureSource.MIC, row.source)
        assertNull(row.ownOk)
        assertNull(row.remoteOk)
        val clip = checkNotNull(database.recordings().recording(checkNotNull(row.recordingId)))
        assertEquals("Capability test - MIC", clip.title)
        assertEquals(RecordingStatus.FINALIZED, clip.status)
        assertNull(clip.remoteCaptured)
        assertEquals(CaptureSource.MIC.androidId, inputs.single().audioSource)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, app.getSystemService(AudioManager::class.java).mode)
    }

    private suspend fun approveMic() {
        val device = CapabilityDevice.read(app, "com.whatsapp")
        val clip = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(clip, 1, 1, "MIC",
            status = RecordingStatus.FINALIZED, durationMs = 30_000, audioPath = "$clip/audio.aac.enc"))
        val row = CapabilityResult(UUID.randomUUID(), device, AudioRoute.EARPIECE, CaptureSource.MIC,
            CapabilityStatus.COMPLETE, 1, clip, CapabilityMeter.TOTAL_FRAMES, -20.0, -25.0, 0.0)
        val id = database.capabilities().insert(row)
        CapabilityProfiles(database, app.preferences).review(id, device, true, true)
    }

    @Test fun ordinaryRecordingActuallyUsesTheReviewedSourceForItsKnownWhatsAppSession() = runBlocking {
        approveMic()
        database.recordings().insertSession(CallSessionEntity(id = 7, callType = CallType.VOICE))
        val state = ReflectionHelpers.getField<MutableStateFlow<CallDetectionState>>(app.detection, "mutableState")
        state.value = CallDetectionState(ready = true, sessionId = 7, call = DetectedCall(
            1, "com.whatsapp", "synthetic", 1, CallerName("Synthetic", null), CallDirection.OUTGOING,
            CallType.VOICE, null, null, 1))
        val id = checkNotNull(app.recorder.start(7))
        deliver(id)
        assertTrue(app.recorder.state.value is RecorderState.Finished)
        assertEquals("MIC", database.recordings().recording(id)?.captureSource)
        assertEquals(CaptureSource.MIC.androidId, inputs.single().audioSource)
        assertNull(database.recordings().recording(id)?.remoteCaptured)
    }

    @Test fun unknownManualCallDoesNotBorrowAnotherWhatsAppPackagesProfile() = runBlocking {
        approveMic()
        val id = checkNotNull(app.recorder.start())
        deliver(id)
        assertTrue(app.recorder.state.value is RecorderState.Finished)
        assertEquals("VOICE_RECOGNITION", database.recordings().recording(id)?.captureSource)
    }

    @Test fun noActiveCommunicationCallRefusesTheGuidedTestBeforeOpeningInput() = runBlocking {
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        val id = checkNotNull(app.recorder.startCapability(CapabilityRequest(
            AudioRoute.EARPIECE, CaptureSource.MIC, "com.whatsapp")))
        deliver(id)
        assertEquals(RecorderFailure.CAPABILITY_ROUTE, app.recorder.capability.value?.failure)
        assertEquals(0, reads)
        assertTrue(database.capabilities().run(id).isEmpty())
        assertTrue(inputs.isEmpty())
    }
}
