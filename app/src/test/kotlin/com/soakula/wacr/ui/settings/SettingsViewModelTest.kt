package com.soakula.wacr.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.ViewModelStore
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.os.PowerManager
import android.content.Intent
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.security.AppLockState
import com.soakula.wacr.security.AppAuthenticationTest
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], application = WacrApp::class, shadows = [AppAuthenticationTest.ManagerShadow::class])
@LazyApplication(LazyApplication.LazyLoad.ON)
class SettingsViewModelTest {
    private val app get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val store = ViewModelStore()
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        AppAuthenticationTest.ManagerShadow.availability = BiometricManager.BIOMETRIC_SUCCESS
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        runBlocking {
            app.preferences.setAppLock(false) { true }
            await { app.appLock.state.value == AppLockState.Disabled }
        }
    }

    @After fun cleanup() {
        observers.cancel()
        store.clear()
        runBlocking { app.preferences.setAppLock(false) { true } }
        app.appLock.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun readFailureSupportsRetryAndFailedWriteRetainsPreviouslyLoadedAppearance() = runBlocking {
        var readFails = true
        val failing = object : DataStore<Preferences> {
            override val data = flow {
                if (readFails) throw IOException("Synthetic read failure")
                emit(emptyPreferences())
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Synthetic write failure")
        }
        val app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        val model = SettingsViewModel(app, AppPreferences(failing))
        store.put("settings", model)
        observers.launch { model.state.collect {} }
        await { model.state.value is SettingsUiState.Failed }
        readFails = false
        model.retry()
        await { model.state.value is SettingsUiState.Ready }
        model.setTheme(ThemeChoice.DARK)
        await { model.actions.value.failed && !model.actions.value.busy }
        assertEquals(SettingsUiState.Ready(Appearance()), model.state.value)
        model.dismissError()
        assertFalse(model.actions.value.failed)
        model.completeOnboarding()
        await { model.actions.value.failed && !model.actions.value.busy }
        assertFalse(checkNotNull(model.state.value as? SettingsUiState.Ready).onboardingDone)
    }

    @Test fun enablingLocksTheAppAndDisablingRequiresCurrentAuthentication() = runBlocking {
        val model = readyModel(app.preferences)
        assertFalse((model.state.value as SettingsUiState.Ready).appLock)
        model.setAppLock(true)
        await { app.appLock.state.value is AppLockState.Locked &&
            (model.state.value as? SettingsUiState.Ready)?.appLock == true && !model.actions.value.busy }
        model.setAppLock(false)
        assertTrue(model.actions.value.failed)
        assertTrue((model.state.value as SettingsUiState.Ready).appLock)
        val owner = Robolectric.buildActivity(Activity::class.java).get()
        app.appLock.foreground(owner)
        assertTrue(app.appLock.authenticated((app.appLock.state.value as AppLockState.Locked).revision))
        model.setAppLock(false)
        await { app.appLock.state.value == AppLockState.Disabled &&
            (model.state.value as? SettingsUiState.Ready)?.appLock == false && !model.actions.value.busy }
        assertFalse(model.actions.value.failed)
    }

    @Test fun unavailableAuthenticationKeepsTheToggleOffAndExplainsTheRemedy() = runBlocking {
        val model = readyModel(app.preferences)
        AppAuthenticationTest.ManagerShadow.availability = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        model.setAppLock(true)
        assertTrue(model.actions.value.failed)
        assertEquals(R.string.app_lock_auth_unavailable, model.actions.value.error)
        assertFalse((model.state.value as SettingsUiState.Ready).appLock)
        assertEquals(AppLockState.Disabled, app.appLock.state.value)
        model.dismissError()
        assertFalse(model.actions.value.failed)
        assertNull(model.actions.value.error)
    }

    @Test fun failedLockWriteRetainsThePreviousPersistedChoice() = runBlocking {
        val failing = object : DataStore<Preferences> {
            override val data = flow { emit(emptyPreferences()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("Synthetic setting write failure")
        }
        val model = readyModel(AppPreferences(failing))
        model.setAppLock(true)
        await { model.actions.value.failed && !model.actions.value.busy }
        assertFalse((model.state.value as SettingsUiState.Ready).appLock)
        assertEquals(AppLockState.Disabled, app.appLock.state.value)
    }

    @Test fun aQueuedDisableCannotPersistAfterThePhoneLocks() = runBlocking {
        app.preferences.setAppLock(true) { true }
        await { app.appLock.state.value is AppLockState.Locked }
        val owner = Robolectric.buildActivity(Activity::class.java).get()
        app.appLock.foreground(owner)
        assertTrue(app.appLock.authenticated((app.appLock.state.value as AppLockState.Locked).revision))
        val values = MutableStateFlow(preferencesOf(booleanPreferencesKey("app_lock") to true))
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val delayed = object : DataStore<Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                entered.complete(Unit)
                resume.await()
                return transform(values.value).also { values.value = it }
            }
        }
        val model = readyModel(AppPreferences(delayed))
        model.setAppLock(false)
        await { entered.isCompleted }
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        await { app.appLock.state.value is AppLockState.Locked }
        resume.complete(Unit)
        await { model.actions.value.failed && !model.actions.value.busy }
        assertTrue((model.state.value as SettingsUiState.Ready).appLock)
        assertFalse(app.appLock.canAccess())
    }

    private suspend fun readyModel(preferences: AppPreferences): SettingsViewModel {
        val model = SettingsViewModel(app, preferences)
        store.put("settings", model)
        observers.launch { model.state.collect {} }
        await { model.state.value is SettingsUiState.Ready }
        return model
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }
    }
}
