package com.soakula.wacr.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class SettingsViewModelTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val store = ViewModelStore()
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
    }

    @After fun cleanup() {
        observers.cancel()
        store.clear()
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
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }
    }
}
