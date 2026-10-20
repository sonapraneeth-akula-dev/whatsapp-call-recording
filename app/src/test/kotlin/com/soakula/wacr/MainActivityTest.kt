package com.soakula.wacr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.WindowManager
import com.soakula.wacr.recording.RecorderService
import com.soakula.wacr.recording.RecorderState
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
    }

    @After fun restoreCrashHandler() { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }

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
