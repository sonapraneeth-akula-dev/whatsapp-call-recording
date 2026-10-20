package com.soakula.wacr.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DiagnosticsScreen(state: DiagnosticExportState, onExport: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.diagnostics_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.diagnostics_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.diagnostics_scope)) }
            item { Text(stringResource(R.string.diagnostics_destination_warning)) }
            item {
                Button(onClick = onExport,
                    enabled = state != DiagnosticExportState.CHOOSING && state != DiagnosticExportState.WRITING,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostics_export)) }
            }
            item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (state == DiagnosticExportState.WRITING) CircularProgressIndicator()
                    Text(stringResource(when (state) {
                        DiagnosticExportState.IDLE -> R.string.diagnostics_idle
                        DiagnosticExportState.CHOOSING -> R.string.diagnostics_choosing
                        DiagnosticExportState.WRITING -> R.string.diagnostics_writing
                        DiagnosticExportState.SAVED -> R.string.diagnostics_saved
                        DiagnosticExportState.FAILED -> R.string.diagnostics_failed
                    }), color = if (state == DiagnosticExportState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
