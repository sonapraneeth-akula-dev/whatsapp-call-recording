package com.soakula.wacr.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.database.SQLException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.capability.CapabilityDevice
import com.soakula.wacr.capability.CapabilityProfiles
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.data.CapabilityResult
import com.soakula.wacr.detection.CallDetection
import com.soakula.wacr.recording.CapabilityRequest
import com.soakula.wacr.recording.RouteWatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

sealed interface CapabilityUiState {
    data object Loading : CapabilityUiState
    data object Failed : CapabilityUiState
    data class Ready(
        val devices: List<CapabilityDevice>, val route: AudioRoute?, val results: List<CapabilityResult>
    ) : CapabilityUiState
}

data class CapabilityActions(val busy: Boolean = false, val error: Int? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityViewModel(application: Application) : AndroidViewModel(application) {
    private val app = checkNotNull(application as? WacrApp)
    private val refresh = MutableStateFlow(0L)
    private val mutableActions = MutableStateFlow(CapabilityActions())
    val actions = mutableActions.asStateFlow()
    val run = app.recorder.capability
    val state = refresh.flatMapLatest {
        flow<CapabilityUiState> {
            emit(CapabilityUiState.Loading)
            RouteWatcher(app, app.log).use { routes ->
                val devices = CallDetection.PACKAGES.mapNotNull { pkg ->
                    try { CapabilityDevice.read(app, pkg) } catch (_: PackageManager.NameNotFoundException) { null }
                }
                app.ensureRecovered()
                emitAll(combine(app.database().capabilities().observeRecent(), routes.current) { rows, route ->
                    CapabilityUiState.Ready(devices, route, rows)
                })
            }
        }.catch { error ->
            if (!expected(error)) throw error
            app.log.log("capability", "read_failed", mapOf("error_type" to error.javaClass.simpleName))
            emit(CapabilityUiState.Failed)
        }.flowOn(Dispatchers.IO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CapabilityUiState.Loading)

    fun retry() { refresh.update { it + 1 } }
    fun dismissError() { mutableActions.update { it.copy(error = null) } }

    fun start(request: CapabilityRequest) {
        mutableActions.value = CapabilityActions(
            error = if (app.recorder.startCapability(request) == null) R.string.capability_start_failed else null
        )
    }

    fun stop() {
        run.value?.takeUnless { it.complete }?.let { app.recorder.stop(it.id) }
    }

    fun review(result: CapabilityResult, own: Boolean, remote: Boolean) {
        if (mutableActions.value.busy) return
        mutableActions.value = CapabilityActions(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val current = CapabilityDevice.read(app, result.device.waPackage)
                    CapabilityProfiles(app.database(), app.preferences).review(result.id, current, own, remote)
                }
                mutableActions.value = CapabilityActions()
            } catch (error: Throwable) {
                if (!expected(error)) throw error
                app.log.log("capability", "review_failed", mapOf("error_type" to error.javaClass.simpleName))
                mutableActions.value = CapabilityActions(error = R.string.capability_review_failed)
            }
        }
    }

    private fun expected(error: Throwable): Boolean = when (error) {
        is CancellationException -> throw error
        is IOException, is GeneralSecurityException, is ProviderException, is SQLException,
        is IllegalStateException, is IllegalArgumentException, is SecurityException,
        is PackageManager.NameNotFoundException, is UnsatisfiedLinkError -> true
        else -> false
    }
}
