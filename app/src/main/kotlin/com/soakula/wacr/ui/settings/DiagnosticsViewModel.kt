package com.soakula.wacr.ui.settings

import android.app.Application
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soakula.wacr.WacrApp
import com.soakula.wacr.health.exportDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class DiagnosticExportState { IDLE, CHOOSING, WRITING, SAVED, FAILED }

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = checkNotNull(getApplication<Application>() as? WacrApp)
    private val mutableState = MutableStateFlow(DiagnosticExportState.IDLE)
    val state = mutableState.asStateFlow()

    fun chooseDestination(): Boolean {
        if (mutableState.value == DiagnosticExportState.CHOOSING || mutableState.value == DiagnosticExportState.WRITING) return false
        mutableState.value = DiagnosticExportState.CHOOSING
        return true
    }

    fun cancelSelection() {
        if (mutableState.value == DiagnosticExportState.CHOOSING) mutableState.value = DiagnosticExportState.IDLE
    }

    fun pickerFailed(error: Exception) { failed(error) }

    fun export(uri: Uri) {
        check(mutableState.value != DiagnosticExportState.WRITING) { "Diagnostic export already running" }
        mutableState.value = DiagnosticExportState.WRITING
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank()) {
                        "Expected a selected document content URI"
                    }
                    app.log.log("diagnostics", "export_started")
                    val output = app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Document unavailable")
                    output.use { exportDiagnostics(app, app.log, it) }
                }
                app.log.log("diagnostics", "export_completed")
                mutableState.value = DiagnosticExportState.SAVED
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                failed(error)
            } catch (error: SecurityException) {
                failed(error)
            } catch (error: IllegalArgumentException) {
                failed(error)
            } catch (error: IllegalStateException) {
                failed(error)
            } catch (error: PackageManager.NameNotFoundException) {
                failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        app.log.log("diagnostics", "export_failed", mapOf("error_type" to error.javaClass.simpleName))
        mutableState.value = DiagnosticExportState.FAILED
    }
}
