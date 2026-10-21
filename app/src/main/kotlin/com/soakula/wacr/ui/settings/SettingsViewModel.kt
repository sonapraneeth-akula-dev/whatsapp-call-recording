package com.soakula.wacr.ui.settings

import android.app.Application
import android.hardware.biometrics.BiometricManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.WacrApp
import com.soakula.wacr.R
import com.soakula.wacr.security.AppLock
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
import com.soakula.wacr.data.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data object Failed : SettingsUiState
    data class Ready(
        val appearance: Appearance,
        val onboardingDone: Boolean = false,
        val appLock: Boolean = false
    ) : SettingsUiState
}
data class SettingsActionsState(
    val busy: Boolean = false,
    val failed: Boolean = false,
    @param:StringRes val error: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel internal constructor(
    application: Application,
    private val preferences: AppPreferences
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, checkNotNull(application as? WacrApp).preferences)

    private val app get() = checkNotNull(getApplication<Application>() as? WacrApp)
    private val refresh = MutableStateFlow(0L)
    private val mutableActions = MutableStateFlow(SettingsActionsState())
    private val lock = Mutex()
    val actions = mutableActions.asStateFlow()
    val state = refresh.flatMapLatest {
        preferences.values.map<UserPreferences, SettingsUiState> { SettingsUiState.Ready(it.appearance, it.onboardingDone, it.appLock) }
            .onStart { emit(SettingsUiState.Loading) }
            .catch { error ->
                if (error is CancellationException) throw error
                if (!expectedFailure(error)) throw error
                logFailure(error)
                emit(SettingsUiState.Failed)
            }.flowOn(Dispatchers.IO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), SettingsUiState.Loading)

    fun retry() { refresh.update { it + 1 } }
    fun dismissError() { mutableActions.update { it.copy(failed = false, error = null) } }
    fun setTheme(theme: ThemeChoice) = save { preferences.setTheme(theme) }
    fun setDynamicColor(enabled: Boolean) = save { preferences.setDynamicColor(enabled) }
    fun completeOnboarding() = save { preferences.completeOnboarding() }

    fun setAppLock(enabled: Boolean) {
        if (mutableActions.value.busy) {
            app.log.log("settings", "app_lock_change_busy")
            return
        }
        val ready = state.value as? SettingsUiState.Ready
        val authorization = app.appLock.settingAuthorization()
        if (ready == null || authorization == null || !app.appLock.canChangeSetting(authorization, enabled)) {
            rejectLockChange(R.string.app_lock_auth_error)
            return
        }
        if (ready.appLock == enabled) return
        if (enabled) {
            try {
                val availability = app.getSystemService(BiometricManager::class.java).canAuthenticate(AppLock.AUTHENTICATORS)
                if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                    app.log.log("settings", "app_lock_unavailable", mapOf("code" to availability))
                    rejectLockChange(R.string.app_lock_auth_unavailable)
                    return
                }
            } catch (error: SecurityException) {
                logFailure(error)
                rejectLockChange(R.string.app_lock_auth_unavailable)
                return
            } catch (error: IllegalStateException) {
                logFailure(error)
                rejectLockChange(R.string.app_lock_auth_unavailable)
                return
            }
        }
        save { preferences.setAppLock(enabled) { app.appLock.canChangeSetting(authorization, enabled) } }
    }

    private fun rejectLockChange(@StringRes message: Int) {
        app.log.log("settings", "app_lock_change_rejected")
        mutableActions.update { it.copy(failed = true, error = message) }
    }

    private fun save(operation: suspend () -> Unit) {
        viewModelScope.launch {
            lock.withLock {
                mutableActions.value = SettingsActionsState(busy = true)
                try {
                    withContext(Dispatchers.IO) { operation() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!expectedFailure(error)) throw error
                    logFailure(error)
                    mutableActions.update { it.copy(failed = true) }
                } finally {
                    mutableActions.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun expectedFailure(error: Throwable) = error is IOException || error is IllegalArgumentException ||
        error is IllegalStateException || error is SecurityException

    private fun logFailure(error: Throwable) =
        app.log.log("settings", "preferences_failed", mapOf("error_type" to error.javaClass.simpleName))
}
