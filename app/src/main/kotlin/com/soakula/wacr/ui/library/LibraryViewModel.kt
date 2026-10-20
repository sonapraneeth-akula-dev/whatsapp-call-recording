package com.soakula.wacr.ui.library

import android.app.Application
import android.database.SQLException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.RecordingRepository
import com.soakula.wacr.data.RecordingWithSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.security.GeneralSecurityException

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Ready(val entries: List<RecordingWithSession>) : LibraryUiState
    data object Failed : LibraryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel internal constructor(
    application: Application,
    private val observe: suspend () -> Flow<List<RecordingWithSession>>
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, {
        val app = checkNotNull(application as? WacrApp)
        app.ensureRecovered()
        RecordingRepository(app.database()).observeAll()
    })

    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        flow<LibraryUiState> {
            emit(LibraryUiState.Loading)
            emitAll(observe().map { LibraryUiState.Ready(it) })
        }.catch { error ->
            when (error) {
                is CancellationException -> throw error
                is IOException, is GeneralSecurityException, is SQLException,
                is IllegalStateException, is SecurityException, is UnsatisfiedLinkError -> {
                    checkNotNull(application as? WacrApp).log.log(
                        "library", "read_failed", mapOf("error_type" to error.javaClass.simpleName)
                    )
                    emit(LibraryUiState.Failed)
                }
                else -> throw error
            }
        }.flowOn(Dispatchers.IO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), LibraryUiState.Loading)

    fun retry() { refresh.update { it + 1 } }
}
