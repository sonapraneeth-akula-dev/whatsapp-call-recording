package com.soakula.wacr.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.data.ThemeChoice
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
    data class Ready(val appearance: Appearance) : SettingsUiState
}
data class SettingsActionsState(val busy: Boolean = false, val failed: Boolean = false)

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
        preferences.appearance.map<Appearance, SettingsUiState> { SettingsUiState.Ready(it) }
            .onStart { emit(SettingsUiState.Loading) }
            .catch { error ->
                if (error is CancellationException) throw error
                if (!expectedFailure(error)) throw error
                logFailure(error)
                emit(SettingsUiState.Failed)
            }.flowOn(Dispatchers.IO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), SettingsUiState.Loading)

    fun retry() { refresh.update { it + 1 } }
    fun dismissError() { mutableActions.update { it.copy(failed = false) } }
    fun setTheme(theme: ThemeChoice) = save { preferences.setTheme(theme) }
    fun setDynamicColor(enabled: Boolean) = save { preferences.setDynamicColor(enabled) }

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
