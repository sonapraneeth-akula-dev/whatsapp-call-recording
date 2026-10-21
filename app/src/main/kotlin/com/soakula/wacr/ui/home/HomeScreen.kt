package com.soakula.wacr.ui.home

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.library.LibraryFailure
import com.soakula.wacr.ui.library.RecordingRow
import kotlinx.coroutines.delay
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    recorder: RecorderState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFix: (HealthIssue) -> Unit,
    library: LibraryUiState? = null,
    onRetryLibrary: () -> Unit = {},
    onRetryHealth: () -> Unit = {},
    onOpen: (UUID) -> Unit = {}
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, modifier = Modifier.animateContentSize(),
                        color = if (recorder is RecorderState.Failed) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (recorder is RecorderState.Failed) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(stringResource(when (recorder) {
                            is RecorderState.Preparing -> R.string.recording_preparing
                            is RecorderState.Recording -> R.string.recording_active
                            is RecorderState.Saving -> R.string.recording_saving
                            is RecorderState.Finished -> R.string.home_saved
                            is RecorderState.Failed -> R.string.recording_notice_title
                            RecorderState.Idle -> R.string.home_manual_recording
                        }), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    when (recorder) {
                        is RecorderState.Recording -> {
                            var seconds by remember(recorder.id) { mutableLongStateOf(0) }
                            LaunchedEffect(recorder.id) {
                                val initial = (System.currentTimeMillis() - recorder.startedAt).coerceAtLeast(0)
                                val elapsed = android.os.SystemClock.elapsedRealtime()
                                while (true) {
                                    seconds = (initial + android.os.SystemClock.elapsedRealtime() - elapsed) / 1000
                                    delay(1000)
                                }
                            }
                            Text(stringResource(R.string.home_recorded_duration, DateUtils.formatElapsedTime(seconds)))
                            if (recorder.lowStorage) Text(stringResource(R.string.recording_storage_warning))
                        }
                        is RecorderState.Finished -> Text(stringResource(
                            R.string.home_recorded_duration, DateUtils.formatElapsedTime(recorder.durationMs / 1000)
                        ))
                        is RecorderState.Failed -> Text(stringResource(recorder.error.message), color = MaterialTheme.colorScheme.error)
                        is RecorderState.Preparing, is RecorderState.Saving -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        RecorderState.Idle -> Text(stringResource(R.string.home_start_explanation))
                    }
                    if (recorder is RecorderState.Preparing || recorder is RecorderState.Recording) {
                        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_stop)) }
                    } else if (recorder !is RecorderState.Saving) {
                        Button(onClick = onRecord, enabled = state.loaded && state.error == null,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_record)) }
                    }
                }
            }
            if (library != null) {
                Text(stringResource(R.string.library_recent), style = MaterialTheme.typography.titleLarge)
                when (library) {
                    LibraryUiState.Loading -> CircularProgressIndicator()
                    LibraryUiState.Failed -> LibraryFailure(onRetryLibrary)
                    is LibraryUiState.Ready -> {
                        if (library.entries.isEmpty()) Text(stringResource(R.string.library_empty))
                        for (entry in library.entries.take(5)) RecordingRow(entry, onOpen)
                        Text(stringResource(R.string.library_storage_used, Formatter.formatShortFileSize(
                            LocalContext.current, library.entries.sumOf { it.recording.sizeBytes ?: 0 }
                        )))
                        Text(stringResource(R.string.library_storage_detail), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_capture_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_capture_detail))
                    Text(stringResource(R.string.home_permission_reason), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.home_storage_title), style = MaterialTheme.typography.titleMedium)
            Text(state.availableBytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
                ?: stringResource(R.string.home_storage_unavailable))
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryHealth) { Text(stringResource(R.string.library_retry)) }
            }
            Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.titleLarge)
            if (!state.loaded) CircularProgressIndicator()
            for (issue in state.issues) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(healthTitle(issue.problem)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(healthDetail(issue.problem)))
                        if (issue.fixIntent != null) {
                            TextButton(onClick = { onFix(issue) }) { Text(stringResource(R.string.home_review_settings)) }
                        }
                    }
                }
            }
            if (state.loaded && state.issues.isEmpty() && state.error == null) Text(stringResource(R.string.home_settings_checked))
        }
    }
}

@StringRes
fun healthTitle(problem: HealthProblem): Int = when (problem) {
    HealthProblem.MICROPHONE_PERMISSION -> R.string.health_microphone
    HealthProblem.NOTIFICATIONS_DISABLED -> R.string.health_notifications
    HealthProblem.RECORDING_CHANNEL_DISABLED -> R.string.health_channel
    HealthProblem.REQUEST_CHANNEL_DISABLED -> R.string.health_request_channel
    HealthProblem.DETECTION_UNAVAILABLE -> R.string.health_detector
    HealthProblem.ACCESSIBILITY_DISABLED -> R.string.health_accessibility
    HealthProblem.NOTIFICATION_ACCESS_DISABLED -> R.string.health_listener
    HealthProblem.BATTERY_OPTIMIZED -> R.string.health_battery
    HealthProblem.BACKGROUND_RESTRICTED -> R.string.health_background
}

@StringRes
internal fun healthDetail(problem: HealthProblem): Int = when (problem) {
    HealthProblem.MICROPHONE_PERMISSION -> R.string.health_microphone_detail
    HealthProblem.NOTIFICATIONS_DISABLED, HealthProblem.RECORDING_CHANNEL_DISABLED,
    HealthProblem.REQUEST_CHANNEL_DISABLED -> R.string.health_notifications_detail
    HealthProblem.DETECTION_UNAVAILABLE -> R.string.health_detector_detail
    HealthProblem.ACCESSIBILITY_DISABLED -> R.string.health_accessibility_detail
    HealthProblem.NOTIFICATION_ACCESS_DISABLED -> R.string.health_listener_detail
    HealthProblem.BATTERY_OPTIMIZED, HealthProblem.BACKGROUND_RESTRICTED -> R.string.health_battery_detail
}
