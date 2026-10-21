package com.soakula.wacr.security

import android.app.KeyguardManager
import android.app.Activity
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.PowerManager
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import com.soakula.wacr.MainActivity
import com.soakula.wacr.WacrApp
import com.soakula.wacr.ui.settings.DiagnosticsViewModel
import com.soakula.wacr.ui.settings.DiagnosticExportState
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], application = WacrApp::class,
    shadows = [AppAuthenticationTest.ManagerShadow::class, AppAuthenticationTest.PromptShadow::class])
@LazyApplication(LazyApplication.LazyLoad.ON)
class AppAuthenticationTest {
    private val app get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private lateinit var activity: ActivityController<MainActivity>
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        ManagerShadow.availability = BiometricManager.BIOMETRIC_SUCCESS
        PromptShadow.request = null
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        runBlocking {
            app.preferences.completeOnboarding()
            app.preferences.setAppLock(true)
        }
        await { app.appLock.state.value is AppLockState.Locked }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After fun close() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
        runBlocking { app.preferences.setAppLock(false) }
        app.appLock.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun nativePromptRequiresStrongBiometricsOrDeviceCredentialAndSuccessUnlocks() {
        authenticate()
        val request = checkNotNull(PromptShadow.request)
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL, request.authenticators)
        assertFalse(app.appLock.canAccess())
        succeed(request, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
        await { app.appLock.state.value is AppLockState.Unlocked }
        assertTrue(app.appLock.canAccess())
    }

    @Test fun cancellationAllowsRetryAndDeviceCredentialCompletionUnlocks() {
        authenticate()
        val first = checkNotNull(PromptShadow.request)
        first.callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "Synthetic cancellation")
        assertFalse(app.appLock.canAccess())
        authenticate()
        val second = checkNotNull(PromptShadow.request)
        assertNotSame(first, second)
        succeed(second, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)
        await { app.appLock.canAccess() }
    }

    @Test fun screenLockInvalidatesAnInFlightNativeAuthenticationResult() {
        authenticate()
        val request = checkNotNull(PromptShadow.request)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        succeed(request, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
        ShadowLooper.idleMainLooper()
        assertFalse(app.appLock.canAccess())
        assertTrue(app.appLock.state.value is AppLockState.Locked)
    }

    @Test fun unavailableAuthenticationNeverOpensProtectedRoutesOrLaunchesPrompt() {
        ManagerShadow.availability = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        authenticate()
        assertNull(PromptShadow.request)
        assertFalse(app.appLock.canAccess())
        assertNotNull(ReflectionHelpers.callInstanceMethod<Int?>(activity.get(), "getAuthError"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "requestRecording")
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun aLateDiagnosticDocumentResultCannotExportAfterScreenLock() {
        authenticate()
        succeed(checkNotNull(PromptShadow.request), BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)
        await { app.appLock.canAccess() }
        ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "chooseDiagnosticDocument")
        val request = checkNotNull(shadowOf(activity.get()).nextStartedActivityForResult)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        shadowOf(activity.get()).receiveResult(request.intent, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://synthetic/diagnostics")))
        assertEquals(DiagnosticExportState.IDLE,
            ViewModelProvider(activity.get())[DiagnosticsViewModel::class.java].state.value)
        assertFalse(app.appLock.canAccess())
    }

    private fun authenticate() { ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "authenticate") }

    private fun succeed(request: Request, type: Int) {
        val constructor = BiometricPrompt.AuthenticationResult::class.java.getDeclaredConstructor(
            BiometricPrompt.CryptoObject::class.java, Integer.TYPE)
        constructor.isAccessible = true
        val result = constructor.newInstance(null, type)
        assertEquals(type, result.authenticationType)
        request.executor.execute { request.callback.onAuthenticationSucceeded(result) }
        ShadowLooper.idleMainLooper()
    }

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < end) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        assertTrue("Authentication did not reach expected state", predicate())
    }

    data class Request(val signal: CancellationSignal, val executor: Executor,
        val callback: BiometricPrompt.AuthenticationCallback, val authenticators: Int)

    @Implements(BiometricManager::class)
    class ManagerShadow {
        @Implementation fun canAuthenticate(authenticators: Int): Int = availability
        companion object { var availability = BiometricManager.BIOMETRIC_SUCCESS }
    }

    @Implements(BiometricPrompt::class)
    class PromptShadow {
        @RealObject private lateinit var prompt: BiometricPrompt
        @Implementation fun authenticate(signal: CancellationSignal, executor: Executor,
            callback: BiometricPrompt.AuthenticationCallback) {
            request = Request(signal, executor, callback, prompt.allowedAuthenticators)
        }
        companion object { var request: Request? = null }
    }
}
