package com.soakula.wacr.ui.library

import android.app.Application
import android.database.SQLException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.WacrApp
import com.soakula.wacr.R
import com.soakula.wacr.data.PendingRecordingDeletion
import com.soakula.wacr.data.RecordingRepository
import com.soakula.wacr.data.RecordingWithSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID

data class RecordingActionsState(
    val busy: Boolean = false,
    val undo: PendingRecordingDeletion? = null,
    val error: Int? = null,
    val renameVersion: Long = 0
)

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
    private val mutableActions = MutableStateFlow(RecordingActionsState())
    private val actionLock = Mutex()
    val actions = mutableActions.asStateFlow()
    val state = refresh.flatMapLatest {
        flow<LibraryUiState> {
            emit(LibraryUiState.Loading)
            emitAll(observe().map { LibraryUiState.Ready(it) })
        }.catch { error ->
            when (error) {
                is CancellationException -> throw error
                is IOException, is GeneralSecurityException, is ProviderException, is SQLException,
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

    fun restoreUndo() = mutate(R.string.library_undo_restore_error) {
        val pending = application().deletions().pendingUndo()
        mutableActions.update { it.copy(undo = pending) }
    }

    fun rename(id: UUID, title: String) = mutate(R.string.library_rename_error) {
        RecordingRepository(application().database()).rename(id, title)
        mutableActions.update { it.copy(renameVersion = it.renameVersion + 1) }
    }

    fun delete(id: UUID) = mutate(R.string.library_delete_error) {
        check(mutableActions.value.undo == null) { "A deletion is already awaiting Undo" }
        val pending = application().deletions().request(id)
        mutableActions.update { it.copy(undo = pending) }
    }

    fun undo(ticket: PendingRecordingDeletion) = mutate(R.string.library_undo_error) {
        try {
            application().deletions().undo(ticket)
        } finally {
            dismissUndo(ticket)
        }
    }

    fun dismissUndo(ticket: PendingRecordingDeletion) {
        mutableActions.update { if (it.undo?.requestId == ticket.requestId) it.copy(undo = null) else it }
    }

    fun dismissError() { mutableActions.update { it.copy(error = null) } }

    private fun application() = checkNotNull(getApplication<Application>() as? WacrApp)

    private fun mutate(message: Int, operation: suspend () -> Unit) {
        viewModelScope.launch {
            actionLock.withLock {
                mutableActions.update { it.copy(busy = true, error = null) }
                try {
                    withContext(Dispatchers.IO) { operation() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    when (error) {
                        is IOException, is GeneralSecurityException, is ProviderException, is SQLException,
                        is IllegalArgumentException, is IllegalStateException, is SecurityException -> failed(message, error)
                        else -> throw error
                    }
                } catch (error: UnsatisfiedLinkError) {
                    failed(message, error)
                } finally {
                    mutableActions.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun failed(message: Int, error: Throwable) {
        application().log.log("library", "action_failed", mapOf("error_type" to error.javaClass.simpleName))
        mutableActions.update { it.copy(error = message) }
    }
}
