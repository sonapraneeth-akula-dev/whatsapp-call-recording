package com.soakula.wacr.ui.library

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: LibraryUiState, onRetry: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                LibraryUiState.Loading -> item { CircularProgressIndicator() }
                LibraryUiState.Failed -> item { LibraryFailure(onRetry) }
                is LibraryUiState.Ready -> {
                    if (state.entries.isEmpty()) item { Text(stringResource(R.string.library_empty)) }
                    items(state.entries, key = { it.recording.id }) { RecordingRow(it) }
                }
            }
        }
    }
}

@Composable
fun LibraryFailure(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.library_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
    }
}

@Composable
fun RecordingRow(entry: RecordingWithSession) {
    val recording = entry.recording
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recording.title ?: entry.session?.waDisplayName ?: stringResource(R.string.library_manual),
                style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.startedAt)))
            entry.session?.let { session ->
                Text(stringResource(
                    if (session.callType == CallType.VIDEO) R.string.library_video else R.string.library_voice
                ) + " / " + stringResource(when (session.direction) {
                    CallDirection.INCOMING -> R.string.library_incoming
                    CallDirection.OUTGOING -> R.string.library_outgoing
                    CallDirection.UNKNOWN -> R.string.library_unknown_direction
                }))
            }
            Text(stringResource(when (recording.status) {
                RecordingStatus.RECORDING -> R.string.recording_active
                RecordingStatus.FINALIZED -> R.string.home_saved
                RecordingStatus.RECOVERED -> R.string.library_recovered
                RecordingStatus.FAILED -> R.string.library_failed
            }))
            recording.durationMs?.let { Text(DateUtils.formatElapsedTime(it / 1000)) }
            recording.sizeBytes?.let { Text(Formatter.formatShortFileSize(LocalContext.current, it)) }
            Text(stringResource(when (recording.remoteCaptured) {
                true -> R.string.library_remote_yes
                false -> R.string.library_remote_no
                null -> R.string.library_remote_unknown
            }), style = MaterialTheme.typography.bodySmall)
        }
    }
}
