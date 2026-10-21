package com.soakula.wacr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.health.HealthNotification
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import com.soakula.wacr.ui.home.HomeViewModel
import com.soakula.wacr.ui.settings.DiagnosticsViewModel
import com.soakula.wacr.ui.settings.DiagnosticExportState
import com.soakula.wacr.recording.RecorderService
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.security.AppLockState
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class MainActivityTest {
    private val app: WacrApp get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepareApplication() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        runBlocking {
            app.preferences.setAppLock(false)
            withTimeout(10_000) {
                while (app.appLock.state.value != AppLockState.Disabled) {
                    ShadowLooper.idleMainLooper()
                    delay(10)
                }
            }
        }
    }

    @After fun restoreCrashHandler() {
        app.appLock.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun launchIsPrivateAndNeverStartsRecordingWithoutAnExplicitAction() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun healthNotificationOpensSetupOnceWithoutArmingRecording() {
        val activity = Robolectric.buildActivity(MainActivity::class.java,
            Intent(app, MainActivity::class.java).setAction(HealthNotification.ACTION_SETUP)).setup()
        try {
            assertEquals(1, ReflectionHelpers.callInstanceMethod<Int>(activity.get(), "getSetupRequest"))
            assertNull(activity.get().intent.action)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertFalse(ReflectionHelpers.getField(activity.get(), "recordAfterPermission"))
            assertNull(shadowOf(app).nextStartedService)
            activity.newIntent(Intent(app, MainActivity::class.java).setAction(HealthNotification.ACTION_SETUP))
            assertEquals(2, ReflectionHelpers.callInstanceMethod<Int>(activity.get(), "getSetupRequest"))
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun explicitResumedActionHandsOffToPrivateForegroundService() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "requestRecording")
            val intent = checkNotNull(shadowOf(app).nextStartedService)
            assertEquals(RecorderService::class.java.name, intent.component?.className)
            assertEquals(RecorderService.ACTION_START, intent.action)
            assertTrue(app.recorder.state.value is RecorderState.Preparing)
        } finally {
            app.recorder.stop()
            activity.pause().stop().destroy()
        }
    }

    @Test fun deniedPermissionAndStoppedActivityCannotLaunchCapture() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "requestRecording")
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            activity.pause().stop()
            assertFalse(ReflectionHelpers.getField(activity.get(), "recordAfterPermission"))
            assertFalse(ReflectionHelpers.getField(activity.get(), "startWhenResumed"))
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "startRecording")
            assertNull(shadowOf(app).nextStartedService)
            activity.restart().start().resume()
            assertEquals(RecorderState.Idle, app.recorder.state.value)
        } finally {
            activity.pause().stop().destroy()
        }

    }

    @Test fun setupPermissionRequestNeverArmsOrStartsRecording() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            val issue = HealthIssue(HealthProblem.MICROPHONE_PERMISSION, null)
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "grantSetupPermission",
                ReflectionHelpers.ClassParameter.from(HealthIssue::class.java, issue))
            assertFalse(ReflectionHelpers.getField(activity.get(), "recordAfterPermission"))
            assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO),
                shadowOf(activity.get()).lastRequestedPermission.requestedPermissions)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun resumeRefreshesRealSetupStateAfterPermissionRevocationAndRestoration() = runBlocking {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val home = ViewModelProvider(activity.get())[HomeViewModel::class.java]
        suspend fun await(missing: Boolean) = withTimeout(10_000) {
            while (!home.state.value.loaded ||
                home.state.value.issues.any { it.problem == HealthProblem.MICROPHONE_PERMISSION } != missing) {
                ShadowLooper.idleMainLooper()
                delay(10)
            }
        }
        try {
            await(false)
            activity.pause().stop()
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            activity.restart().start().resume()
            await(true)
            activity.pause().stop()
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            activity.restart().start().resume()
            await(false)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun diagnosticExportUsesCreateDocumentAndCancelDoesNotReportSuccess() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "chooseDiagnosticDocument")
            val request = checkNotNull(shadowOf(activity.get()).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("application/zip", request.intent.type)
            assertEquals("wacr-diagnostics.zip", request.intent.getStringExtra(Intent.EXTRA_TITLE))
            shadowOf(activity.get()).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            assertEquals(DiagnosticExportState.IDLE, ViewModelProvider(activity.get())[DiagnosticsViewModel::class.java].state.value)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun contactPickerUsesNativeActionPickAndCancellationNeedsNoContactsPermission() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "pickContact")
            val request = checkNotNull(shadowOf(activity.get()).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_PICK, request.intent.action)
            assertEquals(ContactsContract.Contacts.CONTENT_TYPE, request.intent.type)
            assertEquals(PackageManager.PERMISSION_DENIED, activity.get().checkSelfPermission(Manifest.permission.READ_CONTACTS))
            shadowOf(activity.get()).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
