package com.soakula.wacr.ui.home

import android.app.Application
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.health.HealthIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val loaded: Boolean = false,
    val issues: List<HealthIssue> = emptyList(),
    val availableBytes: Long? = null,
    val error: Int? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = checkNotNull(application as? WacrApp)
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            app.detection.state.map { it.ready to it.failed }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) {
                    val available = StatFs(app.filesDir.absolutePath).availableBytes
                    check(available >= 0) { "Invalid available storage" }
                    HomeUiState(loaded = true, issues = app.refreshHealth(), availableBytes = available)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                failed(error)
            } catch (error: IllegalStateException) {
                failed(error)
            } catch (error: SecurityException) {
                failed(error)
            } catch (error: PackageManager.NameNotFoundException) {
                failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        app.log.log("home", "health_read_failed", mapOf("error_type" to error.javaClass.simpleName))
        mutableState.value = HomeUiState(loaded = true, error = R.string.home_health_error)
    }
}
