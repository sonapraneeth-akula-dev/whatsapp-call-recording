package com.soakula.wacr.ui.allowlist

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.ContactRuleEntity
import com.soakula.wacr.data.ContactRuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

sealed interface AllowlistUiState {
    data object Loading : AllowlistUiState
    data object Failed : AllowlistUiState
    data class Ready(val rules: List<ContactRuleEntity>) : AllowlistUiState
}

data class RemovedContact(val rule: ContactRuleEntity, val deadlineMs: Long)

data class AllowlistActionsState(
    val busy: Boolean = false,
    val removed: RemovedContact? = null,
    val error: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class AllowlistViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = checkNotNull(getApplication<Application>() as? WacrApp)
    private val refresh = MutableStateFlow(0L)
    private val mutableActions = MutableStateFlow(AllowlistActionsState())
    private val actionLock = Mutex()
    val actions = mutableActions.asStateFlow()
    val state = refresh.flatMapLatest {
        flow<AllowlistUiState> {
            emit(AllowlistUiState.Loading)
            emitAll(repository().observe().map { AllowlistUiState.Ready(it) })
        }.catch { error ->
            if (error is CancellationException) throw error
            if (!expectedFailure(error)) throw error
            logFailure(error)
            emit(AllowlistUiState.Failed)
        }.flowOn(Dispatchers.IO)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AllowlistUiState.Loading)

    fun retry() { refresh.update { it + 1 } }

    fun addPickedContact(uri: Uri) = mutate(R.string.allowlist_pick_error, R.string.allowlist_duplicate) {
        repository().addContact(pickedContactName(app, uri), System.currentTimeMillis())
    }

    fun remove(id: Long) = mutate(R.string.allowlist_remove_error) {
        check(mutableActions.value.removed == null) { "Another removal is awaiting Undo" }
        val rule = repository().remove(id)
        mutableActions.update { it.copy(removed = RemovedContact(rule, SystemClock.elapsedRealtime() + 5000)) }
    }

    fun undo(ticket: RemovedContact) = mutate(R.string.allowlist_undo_error, R.string.allowlist_undo_conflict) {
        check(mutableActions.value.removed == ticket && SystemClock.elapsedRealtime() < ticket.deadlineMs) {
            "Contact removal Undo expired"
        }
        repository().restore(ticket.rule)
        dismissUndo(ticket)
    }

    fun dismissUndo(ticket: RemovedContact) {
        mutableActions.update { if (it.removed == ticket) it.copy(removed = null) else it }
    }

    fun dismissError() { mutableActions.update { it.copy(error = null) } }

    fun pickerFailed(error: Exception) {
        logFailure(error)
        mutableActions.update { it.copy(error = R.string.allowlist_picker_unavailable) }
    }

    private suspend fun repository() = ContactRuleRepository(app.database())

    private fun mutate(message: Int, conflict: Int = message, operation: suspend () -> Unit) {
        viewModelScope.launch {
            actionLock.withLock {
                mutableActions.update { it.copy(busy = true, error = null) }
                try {
                    withContext(Dispatchers.IO) { operation() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (!expectedFailure(error)) throw error
                    logFailure(error)
                    mutableActions.update { it.copy(error = if (error is SQLiteConstraintException) conflict else message) }
                } finally {
                    mutableActions.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun logFailure(error: Throwable) =
        app.log.log("allowlist", "operation_failed", mapOf("error_type" to error.javaClass.simpleName))

    private fun expectedFailure(error: Throwable) = error is IOException || error is SQLException ||
        error is GeneralSecurityException || error is ProviderException || error is IllegalArgumentException ||
        error is IllegalStateException || error is SecurityException || error is UnsatisfiedLinkError
}

internal fun pickedContactName(context: Context, uri: Uri): String {
    val path = uri.pathSegments
    val contactId = path.size == 2 && path[1].toLongOrNull()?.let { it > 0 } == true
    val lookup = path.size in 3..4 && path[1] == "lookup" && path[2].isNotBlank() &&
        (path.size == 3 || path[3].toLongOrNull()?.let { it > 0 } == true)
    require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == ContactsContract.AUTHORITY &&
        path.firstOrNull() == "contacts" && (contactId || lookup)) { "Expected one selected contact URI" }
    return context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)
        ?.use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) { "Selected contact unavailable" }
            checkNotNull(cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))) {
                "Selected contact has no display name"
            }
        } ?: throw IOException("Selected contact unavailable")
}
