package com.soakula.wacr.recording

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import com.soakula.wacr.R
import com.soakula.wacr.util.AppLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecorderControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var log: AppLog
    private val launches = mutableListOf<Intent>()
    private val notices = mutableListOf<Int>()
    private fun controller(launch: (Intent) -> Unit = { launches += it }) =
        RecorderController(application, log, launch, notices::add)

    @Before fun setup() {
        log = AppLog(temporary.newFolder(), false, {}, { throw it })
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun oneActiveRecordingSharesProgressAndRejectsStaleStopAndCompletion() {
        val controller = controller()
        val id = checkNotNull(controller.start(7))
        val request = checkNotNull(controller.request(id))
        assertEquals(7L, request.sessionId)
        assertEquals(RecorderState.Preparing(id), controller.state.value)
        assertNull(controller.start(8))
        assertEquals(1, launches.size)
        assertEquals(ComponentName(application, RecorderService::class.java), launches.single().component)
        assertEquals(id, RecorderService.parseId(launches.single()))
        controller.recording(request, 1000, false)
        assertEquals(RecorderState.Recording(id, 1000, false), controller.state.value)
        controller.recording(request, 1000, true)
        assertEquals(RecorderState.Recording(id, 1000, true), controller.state.value)
        controller.stop(UUID.randomUUID())
        assertFalse(request.stopRequested.get())
        controller.stop(id)
        assertTrue(request.stopRequested.get())
        controller.saving(request)
        assertEquals(RecorderState.Saving(id), controller.state.value)
        controller.finished(request, RecordingResult(FinalizedAudio(64_000, 16, 64), RecordingStopReason.REQUESTED))
        assertEquals(RecorderState.Finished(id, 64, RecordingStopReason.REQUESTED), controller.state.value)
        val nextId = checkNotNull(controller.start())
        val next = checkNotNull(controller.request(nextId))
        controller.stop(id)
        controller.failed(request, RecorderFailure.IO_OR_CRYPTO)
        controller.finished(request, null)
        controller.recording(request, 1000, false)
        assertFalse(next.stopRequested.get())
        assertEquals(RecorderState.Preparing(nextId), controller.state.value)
        controller.stop()
        assertTrue(next.stopRequested.get())
        controller.finished(next, null)
        assertEquals(RecorderState.Idle, controller.state.value)
        assertTrue(notices.isEmpty())
    }

    @Test fun permissionsAndBlockedChannelRefuseStartWithAnExplicitError() {
        val controller = controller()
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertNull(controller.start())
        assertEquals(RecorderState.Failed(null, RecorderFailure.NO_MIC_PERMISSION), controller.state.value)
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNull(controller.start())
        assertEquals(RecorderState.Failed(null, RecorderFailure.NOTIFICATIONS_DISABLED), controller.state.value)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        application.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RecorderNotification.CHANNEL, "Synthetic status", NotificationManager.IMPORTANCE_NONE)
        )
        assertNull(controller.start())
        assertEquals(RecorderState.Failed(null, RecorderFailure.NOTIFICATIONS_DISABLED), controller.state.value)
        assertTrue(launches.isEmpty())
        assertEquals(3, notices.size)
        assertThrows(IllegalArgumentException::class.java) { controller.start(0) }
    }

    @Test fun foregroundStartDenialReleasesTheSlotForAnExplicitRetry() {
        var denied = true
        val controller = controller { if (denied) throw IllegalStateException("synthetic background denial") else launches += it }
        assertNull(controller.start())
        assertEquals(RecorderFailure.FGS_START_DENIED, (controller.state.value as RecorderState.Failed).error)
        denied = false
        val id = checkNotNull(controller.start())
        val request = checkNotNull(controller.request(id))
        controller.failed(request, RecorderFailure.CAPTURE_UNAVAILABLE)
        assertEquals(RecorderState.Failed(id, RecorderFailure.CAPTURE_UNAVAILABLE), controller.state.value)
        assertNotNull(controller.start())
        assertEquals(2, notices.size)
    }

    @Test fun criticalStorageStopExplainsThatTheAudioWasSaved() {
        val controller = controller()
        val id = checkNotNull(controller.start())
        controller.finished(checkNotNull(controller.request(id)), RecordingResult(
            FinalizedAudio(64_000, 16, 64), RecordingStopReason.LOW_STORAGE
        ))
        assertEquals(listOf(R.string.recording_low_storage_saved), notices)
        assertEquals(RecordingStopReason.LOW_STORAGE, (controller.state.value as RecorderState.Finished).reason)
    }

    @Test fun manifestAndIntentIdentityPermitOnlyPrivateNonStickyMicrophoneCapture() {
        val service = application.packageManager.getServiceInfo(ComponentName(application, RecorderService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        val id = UUID.fromString("12345678-abcd-4000-8000-123456789abc")
        assertEquals(id, RecorderService.parseId(Intent().putExtra(RecorderService.EXTRA_ID, id.toString())))
        assertNull(RecorderService.parseId(null))
        assertNull(RecorderService.parseId(Intent().putExtra(RecorderService.EXTRA_ID, "invalid")))
        assertNull(RecorderService.parseId(Intent().putExtra(RecorderService.EXTRA_ID, id.toString().uppercase())))
    }
}
