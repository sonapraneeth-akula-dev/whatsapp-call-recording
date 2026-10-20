package com.soakula.wacr.recording

import android.Manifest
import android.app.Service
import android.content.Intent
import com.soakula.wacr.WacrApp
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class RecorderServiceTest {
    private val app: WacrApp get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun grantPermissions() {
        // Android installs a crash handler before Application.onCreate; the host JVM need not.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun restoreCrashHandler() { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }

    @Test fun unknownOrMalformedCommandsNeverAcquireForegroundCapture() {
        val service = Robolectric.buildService(RecorderService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(
                Intent().setAction(RecorderService.ACTION_START).putExtra(RecorderService.EXTRA_ID, UUID.randomUUID().toString()), 0, 1
            ))
            assertNull(shadowOf(service.get()).lastForegroundNotification)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
        } finally {
            service.destroy()
        }
    }

    @Test fun revokedPermissionBeforeServiceDeliveryFailsWithoutOpeningKeysOrMicrophone() {
        val id = checkNotNull(app.recorder.start())
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = Robolectric.buildService(RecorderService::class.java).create()
        try {
            service.get().onStartCommand(start(id), 0, 1)
            assertEquals(RecorderState.Failed(id, RecorderFailure.NO_MIC_PERMISSION), app.recorder.state.value)
            assertNull(shadowOf(service.get()).lastForegroundNotification)
        } finally {
            service.destroy()
        }
    }

    @Test fun stopWhileStartIsQueuedShowsForegroundStatusThenExitsWithoutOpeningKeys() {
        val id = checkNotNull(app.recorder.start())
        app.recorder.stop()
        val service = Robolectric.buildService(RecorderService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(start(id), 0, 1))
            val notification = checkNotNull(shadowOf(service.get()).lastForegroundNotification)
            assertEquals(RecorderNotification.CHANNEL, notification.channelId)
            val action = notification.actions.single().actionIntent
            assertTrue(action.isImmutable)
            val stop = shadowOf(action).savedIntent
            assertEquals(id, RecorderService.parseId(stop))
            assertEquals(RecorderService.ACTION_STOP, stop.action)
            assertEquals("wacr://recording/$id/stop", stop.data.toString())
            val deadline = System.nanoTime() + 5_000_000_000
            while (app.recorder.state.value != RecorderState.Idle && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(5)
            }
            assertEquals(RecorderState.Idle, app.recorder.state.value)
        } finally {
            service.destroy()
        }
    }

    @Test fun interruptedWorkInvalidatesTheCompletedRecoveryGate() = runBlocking {
        val previous = RecoveryReport(0, 0, 0)
        ReflectionHelpers.setField(app, "recovered", previous)
        assertSame(previous, app.ensureRecovered())
        app.requireRecovery()
        assertNull(ReflectionHelpers.getField<RecoveryReport?>(app, "recovered"))
    }

    @Test fun playbackReleaseFailureBlocksCaptureBeforeOpeningDatabaseKeysOrMicrophone() {
        app.playbackReleaseFailed = true
        val id = checkNotNull(app.recorder.start())
        val service = Robolectric.buildService(RecorderService::class.java).create()
        try {
            service.get().onStartCommand(start(id), 0, 1)
            assertEquals(RecorderState.Failed(id, RecorderFailure.PLAYBACK_RELEASE), app.recorder.state.value)
            assertNull(ReflectionHelpers.getField<Any?>(app, "openDatabase"))
        } finally { service.destroy() }
    }

    private fun start(id: UUID) = Intent(app, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        .putExtra(RecorderService.EXTRA_ID, id.toString())
}
